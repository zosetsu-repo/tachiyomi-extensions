package eu.kanade.tachiyomi.extension.all.koharu

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebviewInterceptor(private val domainUrl: String, private val authUrl: String) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val origRes = chain.proceed(request)

        if (origRes.code != 400) return origRes
        origRes.close()

        resolveInWebview(domainUrl, authUrl)

        // If webview failed
        val response = chain.proceed(request)
        if (response.code == 400) {
            response.close()
            throw IOException("Solve Captcha in WebView")
        }
        return response
    }
}

internal fun resolveInWebview(domainUrl: String, authUrl: String) {
    val context: Application by injectLazy()
    val handler by lazy { Handler(Looper.getMainLooper()) }

    Log.e("WebviewInterceptor", "resolveInWebview")
    val latch = CountDownLatch(1)
    var webView: WebView? = null
    var hasSetCookies = false
    var finishedLoad = false

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
                if (request?.url.toString().contains(authUrl) && request?.method == "POST" && request.requestHeaders["Authorization"] != null) {
                    Log.e("WebviewInterceptor", "clearance asked")
                    hasSetCookies = true
//                    latch.await(10, TimeUnit.SECONDS)
//                    Log.e("WebviewInterceptor", "clearance set")
//                    latch.countDown()
                }
                return super.shouldInterceptRequest(view, request)
            }
        }

        webview.loadUrl("$domainUrl/")
    }

    latch.await(20, TimeUnit.SECONDS)

    handler.post {
        val script = "javascript:localStorage['clearance']"
        webView?.evaluateJavascript(script) {
            Log.e("WebviewInterceptor", "Clearance: $it")
            finishedLoad = true
        }

        webView?.stopLoading()
        webView?.destroy()
        webView = null
    }
}
