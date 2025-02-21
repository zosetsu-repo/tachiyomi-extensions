package eu.kanade.tachiyomi.extension.all.koharu

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.extension.all.koharu.Koharu.Companion.authorization
import eu.kanade.tachiyomi.extension.all.koharu.Koharu.Companion.token
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Cloudflare Turnstile interceptor */
class TurnstileInterceptor(
    private val client: OkHttpClient,
    private val domainUrl: String,
    private val authUrl: String,
    private val userAgent: String?,
) : Interceptor {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val lazyHeaders by lazy {
        Headers.Builder().apply {
            set("User-Agent", userAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/84.0.4147.105 Safari/537.36")
            set("Referer", "$domainUrl/")
            set("Origin", domainUrl)
        }.build()
    }

    private fun authHeaders(authorization: String) =
        Headers.Builder().apply {
            set("User-Agent", userAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/84.0.4147.105 Safari/537.36")
            set("Referer", "$domainUrl/")
            set("Origin", domainUrl)
            set("Authorization", authorization)
        }.build()

    private val authorizedRequestRegex by lazy { Regex("""(.+\?crt=)(.*)""") }

    override fun intercept(chain: Interceptor.Chain): Response {
        if (token == null) {
            resolveInWebview()
        }
        val request = chain.request()

        val url = request.url.toString()
        Log.e("Koharu", "Requesting URL: $url")
        val matchResult = authorizedRequestRegex.find(url) ?: return chain.proceed(request)
        if (matchResult.groupValues.size == 3) {
            val requestingUrl = matchResult.groupValues[1]
            val crt = matchResult.groupValues[2]
            var newResponse: Response

            if (crt.isNotBlank() && crt != "null") {
                // Token already set in URL, just make the request
                newResponse = chain.proceed(request)
                Log.e("Koharu", "Response code: ${newResponse.code}")
                if (newResponse.code !in listOf(400, 403)) return newResponse
            } else {
                // Token doesn't include, add token then make request
                if (token.isNullOrBlank()) resolveInWebview()
                val newRequest = if (request.method == "POST") {
                    POST("${requestingUrl}$token", lazyHeaders)
                } else {
                    GET("${requestingUrl}$token", lazyHeaders)
                }
                Log.e("Koharu", "New request: ${newRequest.url}")
                newResponse = chain.proceed(newRequest)
                Log.e("Koharu", "Response code: ${newResponse.code}")
                if (newResponse.code !in listOf(400, 403)) return newResponse
            }
            newResponse.close()

            // Request failed, refresh token then try again
            clearToken()
            resolveInWebview()
            val newRequest = if (request.method == "POST") {
                POST("${requestingUrl}$token", lazyHeaders)
            } else {
                GET("${requestingUrl}$token", lazyHeaders)
            }
            Log.e("Koharu", "New re-request: ${newRequest.url}")
            newResponse = chain.proceed(newRequest)
            Log.e("Koharu", "Response code: ${newResponse.code}")
            if (newResponse.code !in listOf(400, 403)) return newResponse
            throw IOException("Solve Captcha in WebView (${newResponse.code})")
        }
        return chain.proceed(request)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun resolveInWebview(): Pair<String?, String?> {
        Log.e("TurnstileInterceptor", "resolveInWebview")
        val latch = CountDownLatch(1)
        var webView: WebView? = null
        var tokenRequested = false

        handler.post {
            val webview = WebView(context)
            webView = webview
            with(webview.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = false
                loadWithOverviewMode = false
            }

            webview.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val authHeader = request?.requestHeaders?.get("Authorization")
                    if (request?.url.toString().contains(authUrl) && authHeader != null) {
                        authorization = authHeader
                        if (request.method == "POST") {
                            Log.e("TurnstileInterceptor", "Authorization: $authorization")
                            tokenRequested = true

                            try {
                                val noRedirectClient = client.newBuilder().followRedirects(false).build()
                                val authHeaders = authHeaders(authHeader)
                                val response = noRedirectClient.newCall(POST(authUrl, authHeaders)).execute()
                                response.use {
                                    if (response.isSuccessful) {
                                        with(response) {
                                            token = body.string()
                                                .removeSurrounding("\"")
                                            Log.e("TurnstileInterceptor", "Requested token: $token")
                                        }
                                        latch.countDown()
                                    } else {
                                        println("Request failed with code: ${response.code}")
                                    }
                                }
                            } catch (e: IOException) {
                                println("Request failed: ${e.message}")
                                latch.countDown()
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        if (request.method == "GET") {
                            // TODO: What to do if it return failed? => should not countdown, let the POST method request again
                            // Can try mitigate a wrong clearance
                            // Token might be rechecked here but just let it fails then we will reset and request a new one
                            // Normally this might not occur because old token should already be returned via onPageFinished
                            Log.e("TurnstileInterceptor", "Authorization: $authorization")
                            token = authorization?.substringAfterLast(" ")
                            tokenRequested = true
                            latch.countDown()
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (view == null) return
                    // Read the saved token in localStorage
                    // Fixme: this might overwrite the one newly requested
                    val script = "javascript:localStorage['clearance']"
                    view.evaluateJavascript(script) {
                        if (!it.isNullOrBlank() && it != "null") {
                            token = it
                                .removeSurrounding("\"")
                            Log.e("TurnstileInterceptor", "Clearance: $token")
                            latch.countDown()
                        }
                        Log.e("TurnstileInterceptor", "Page finished")
                    }
                }
            }

            webview.loadUrl("$domainUrl/")
        }

        latch.await(20, TimeUnit.SECONDS)

        handler.post {
            if (token.isNullOrBlank()) {
                val script = "javascript:localStorage['clearance']"
                webView?.evaluateJavascript(script) {
                    if (!it.isNullOrBlank() && it != "null") {
                        token = it
                            .removeSurrounding("\"")
                    }
                    Log.e("TurnstileInterceptor", "Clearance: $it / $token - Authorization: $authorization")
                }
            }

            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        return token to authorization
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun clearToken() {
        val latch = CountDownLatch(1)
        handler.post {
            val webView = WebView(context)
            with(webView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
            }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (view == null) return
                    val script = "javascript:localStorage.clear()"
                    view.evaluateJavascript(script) {
                        token = null
                        view.stopLoading()
                        view.destroy()
                        latch.countDown()
                    }
                }
            }
            webView.loadUrl(domainUrl)
        }
        latch.await(20, TimeUnit.SECONDS)
    }
}
