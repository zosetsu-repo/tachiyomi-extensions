package eu.kanade.tachiyomi.extension.all.koharu

import android.annotation.SuppressLint
import android.app.Application
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.interceptor.rateLimit
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Koharu(
    override val lang: String = "all",
    private val searchLang: String = "",
) : HttpSource(), ConfigurableSource {

    override val name = "SchaleNetwork"

    override val baseUrl = "https://schale.network"

    override val id = if (lang == "en") 1484902275639232927 else super.id

    private val apiUrl = baseUrl.replace("://", "://api.")

    private val apiBooksUrl = "$apiUrl/books"

    private val authUrl = "${baseUrl.replace("://", "://auth.")}/clearance"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient.newBuilder()
        .rateLimit(5)
        .build()

    val interceptedClient: OkHttpClient = network.cloudflareClient.newBuilder()
        .addInterceptor(::interceptCloudFlareTurnstile)
        .rateLimit(5)
        .build()

    private val json: Json by injectLazy()

    private val shortenTitleRegex = Regex("""(\[[^]]*]|[({][^)}]*[)}])""")
    private fun String.shortenTitle() = replace(shortenTitleRegex, "").trim()

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private fun quality() = preferences.getString(PREF_IMAGERES, "1280")!!

    private fun remadd() = preferences.getBoolean(PREF_REM_ADD, false)

    internal var token: String? = null
    internal var authorization: String? = null
    private var _domainUrl: String? = null
    internal val domainUrl: String
        get() {
            return _domainUrl ?: run {
                val domain = getDomain()
                _domainUrl = domain
                domain
            }
        }

    private fun getDomain(): String {
        try {
            val noRedirectClient = client.newBuilder().followRedirects(false).build()
            val host = noRedirectClient.newCall(GET(baseUrl, headers)).execute()
                .headers["Location"]?.toHttpUrlOrNull()?.host
                ?: return baseUrl
            return "https://$host"
        } catch (_: Exception) {
            return baseUrl
        }
    }

    private val lazyHeaders by lazy {
        headersBuilder()
            .set("Referer", "$domainUrl/")
            .set("Origin", domainUrl)
            .build()
    }

    private fun getManga(book: Entry) = SManga.create().apply {
        setUrlWithoutDomain("${book.id}/${book.key}")
        title = if (remadd()) book.title.shortenTitle() else book.title
        thumbnail_url = book.thumbnail.path
    }

    private fun getImagesByMangaEntry(entry: MangaData, entryId: String, entryKey: String): Pair<ImagesInfo, String> {
        val data = entry.data
        fun getIPK(
            ori: DataKey?,
            alt1: DataKey?,
            alt2: DataKey?,
            alt3: DataKey?,
            alt4: DataKey?,
        ): Pair<Int?, String?> {
            return Pair(
                ori?.id ?: alt1?.id ?: alt2?.id ?: alt3?.id ?: alt4?.id,
                ori?.key ?: alt1?.key ?: alt2?.key ?: alt3?.key ?: alt4?.key,
            )
        }
        val (id, public_key) = when (quality()) {
            "1600" -> getIPK(data.`1600`, data.`1280`, data.`0`, data.`980`, data.`780`)
            "1280" -> getIPK(data.`1280`, data.`1600`, data.`0`, data.`980`, data.`780`)
            "980" -> getIPK(data.`980`, data.`1280`, data.`0`, data.`1600`, data.`780`)
            "780" -> getIPK(data.`780`, data.`980`, data.`0`, data.`1280`, data.`1600`)
            else -> getIPK(data.`0`, data.`1600`, data.`1280`, data.`980`, data.`780`)
        }

        if (id == null || public_key == null) {
            throw Exception("No Images Found")
        }

        val realQuality = when (id) {
            data.`1600`?.id -> "1600"
            data.`1280`?.id -> "1280"
            data.`980`?.id -> "980"
            data.`780`?.id -> "780"
            else -> "0"
        }

        val imagesResponse = interceptedClient.newCall(GET("$apiBooksUrl/data/$entryId/$entryKey/$id/$public_key/$realQuality?crt=$token", lazyHeaders)).execute()
        val images = imagesResponse.parseAs<ImagesInfo>() to realQuality
        return images
    }

    // Latest

    override fun latestUpdatesRequest(page: Int) = GET("$apiBooksUrl?page=$page" + if (searchLang.isNotBlank()) "&s=language!:\"$searchLang\"" else "", lazyHeaders)
    override fun latestUpdatesParse(response: Response) = popularMangaParse(response)

    // Popular

    override fun popularMangaRequest(page: Int) = GET("$apiBooksUrl?sort=8&page=$page" + if (searchLang.isNotBlank()) "&s=language!:\"$searchLang\"" else "", lazyHeaders)
    override fun popularMangaParse(response: Response): MangasPage {
        val data = response.parseAs<Books>()
        return MangasPage(data.entries.map(::getManga), data.page * data.limit < data.total)
    }

    // Search

    override fun getFilterList(): FilterList = getFilters()

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return when {
            query.startsWith(PREFIX_ID_KEY_SEARCH) -> {
                val ipk = query.removePrefix(PREFIX_ID_KEY_SEARCH)
                val response = client.newCall(GET("$apiBooksUrl/detail/$ipk", lazyHeaders)).execute()
                Observable.just(searchMangaParse2(response))
            }
            else -> super.fetchSearchManga(page, query, filters)
        }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = apiBooksUrl.toHttpUrl().newBuilder().apply {
            val terms: MutableList<String> = mutableListOf()

            if (lang != "all") terms += "language!:\"$searchLang\""
            filters.forEach { filter ->
                when (filter) {
                    is SortFilter -> addQueryParameter("sort", filter.getValue())

                    is CategoryFilter -> {
                        val activeFilter = filter.state.filter { it.state }
                        if (activeFilter.isNotEmpty()) {
                            addQueryParameter("cat", activeFilter.sumOf { it.value }.toString())
                        }
                    }

                    is TextFilter -> {
                        if (filter.state.isNotEmpty()) {
                            val tags = filter.state.split(",").filter(String::isNotBlank).joinToString(",")
                            if (tags.isNotBlank()) {
                                terms += "${filter.type}!:" + if (filter.type == "pages") tags else '"' + tags + '"'
                            }
                        }
                    }
                    else -> {}
                }
            }
            if (query.isNotEmpty()) terms.add("title:\"$query\"")
            if (terms.isNotEmpty()) addQueryParameter("s", terms.joinToString(" "))
            addQueryParameter("page", page.toString())
        }.build()

        return GET(url, lazyHeaders)
    }

    override fun searchMangaParse(response: Response) = popularMangaParse(response)

    private fun searchMangaParse2(response: Response): MangasPage {
        val entry = response.parseAs<MangaEntry>()

        return MangasPage(
            listOf(
                SManga.create().apply {
                    setUrlWithoutDomain("${entry.id}/${entry.key}")
                    title = if (remadd()) entry.title.shortenTitle() else entry.title
                    thumbnail_url = entry.thumbnails.base + entry.thumbnails.main.path
                },
            ),
            false,
        )
    }
    // Details

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET("$apiBooksUrl/detail/${manga.url}", lazyHeaders)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        return response.parseAs<MangaEntry>().toSManga()
    }

    private val dateReformat = SimpleDateFormat("EEEE, d MMM yyyy HH:mm (z)", Locale.ENGLISH)
    private fun MangaEntry.toSManga() = SManga.create().apply {
        val artists = mutableListOf<String>()
        val circles = mutableListOf<String>()
        val parodies = mutableListOf<String>()
        val magazines = mutableListOf<String>()
        val characters = mutableListOf<String>()
        val cosplayers = mutableListOf<String>()
        val females = mutableListOf<String>()
        val males = mutableListOf<String>()
        val mixed = mutableListOf<String>()
        val other = mutableListOf<String>()
        val uploaders = mutableListOf<String>()
        val tags = mutableListOf<String>()
        for (tag in this@toSManga.tags) {
            when (tag.namespace) {
                1 -> artists.add(tag.name)
                2 -> circles.add(tag.name)
                3 -> parodies.add(tag.name)
                4 -> magazines.add(tag.name)
                5 -> characters.add(tag.name)
                6 -> cosplayers.add(tag.name)
                7 -> tag.name.takeIf { it != "anonymous" }?.let { uploaders.add(it) }
                8 -> males.add(tag.name + " ♂")
                9 -> females.add(tag.name + " ♀")
                10 -> mixed.add(tag.name)
                12 -> other.add(tag.name)
                else -> tags.add(tag.name)
            }
        }

        var appended = false
        fun List<String>.joinAndCapitalizeEach(): String? = this.emptyToNull()?.joinToString { it.capitalizeEach() }?.apply { appended = true }
        title = if (remadd()) this@toSManga.title.shortenTitle() else this@toSManga.title

        author = (circles.emptyToNull() ?: artists).joinToString { it.capitalizeEach() }
        artist = artists.joinToString { it.capitalizeEach() }
        genre = (tags + males + females + mixed + other).joinToString { it.capitalizeEach() }
        description = buildString {
            circles.joinAndCapitalizeEach()?.let {
                append("Circles: ", it, "\n")
            }
            uploaders.joinAndCapitalizeEach()?.let {
                append("Uploaders: ", it, "\n")
            }
            magazines.joinAndCapitalizeEach()?.let {
                append("Magazines: ", it, "\n")
            }
            cosplayers.joinAndCapitalizeEach()?.let {
                append("Cosplayers: ", it, "\n")
            }
            parodies.joinAndCapitalizeEach()?.let {
                append("Parodies: ", it, "\n")
            }
            characters.joinAndCapitalizeEach()?.let {
                append("Characters: ", it, "\n")
            }

            if (appended) append("\n")

            try {
                append("Posted: ", dateReformat.format(created_at), "\n")
            } catch (_: Exception) {}

            /*
            val dataKey = when (quality()) {
                "1600" -> data.`1600` ?: data.`1280` ?: data.`0`
                "1280" -> data.`1280` ?: data.`1600` ?: data.`0`
                "980" -> data.`980` ?: data.`1280` ?: data.`0`
                "780" -> data.`780` ?: data.`980` ?: data.`0`
                else -> data.`0`
            }
            append("Size: ", dataKey.readableSize(), "\n\n")
             */
            append("Pages: ", thumbnails.entries.size, "\n\n")
        }
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
        initialized = true
    }

    private fun String.capitalizeEach() = this.split(" ").joinToString(" ") { s ->
        s.replaceFirstChar { sr ->
            if (sr.isLowerCase()) sr.titlecase(Locale.getDefault()) else sr.toString()
        }
    }

    private fun <T> Collection<T>.emptyToNull(): Collection<T>? {
        return this.ifEmpty { null }
    }

    override fun getMangaUrl(manga: SManga) = "$baseUrl/g/${manga.url}"

    // Chapter

    override fun chapterListRequest(manga: SManga): Request {
        return GET("$apiBooksUrl/detail/${manga.url}", lazyHeaders)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val manga = response.parseAs<MangaEntry>()
        return listOf(
            SChapter.create().apply {
                name = "Chapter"
                url = "${manga.id}/${manga.key}"
                date_upload = (manga.updated_at ?: manga.created_at)
            },
        )
    }

    override fun getChapterUrl(chapter: SChapter) = "$baseUrl/g/${chapter.url}"

    // Page List

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        return interceptedClient.newCall(pageListRequest(chapter))
            .execute()
            .let { response ->
                pageListParse(response)
            }
    }

    override fun pageListRequest(chapter: SChapter): Request {
        return POST("$apiBooksUrl/detail/${chapter.url}?crt=$token", lazyHeaders)
    }

    override fun pageListParse(response: Response): List<Page> {
        val mangaData = response.parseAs<MangaData>()
        val url = response.request.url.toString()
        val matches = Regex("""/detail/(\d+)/([a-z\d]+)""").find(url)
        if (matches == null || matches.groupValues.size < 3) return emptyList()
        val imagesInfo = getImagesByMangaEntry(mangaData, matches.groupValues[1], matches.groupValues[2])

        return imagesInfo.first.entries.mapIndexed { index, image ->
            Page(index, imageUrl = "${imagesInfo.first.base}/${image.path}?w=${imagesInfo.second}")
        }
    }

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!, lazyHeaders)
    }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> = coroutineScope {
        async {
            interceptedClient.newCall(relatedMangaListRequest(manga))
                .execute()
                .let { response ->
                    relatedMangaListParse(response)
                }
        }.await()
    }

    override fun relatedMangaListRequest(manga: SManga) =
        POST("$apiBooksUrl/detail/${manga.url}?crt=$token", lazyHeaders)

    override fun relatedMangaListParse(response: Response): List<SManga> {
        val data = response.parseAs<MangaData>()
        return data.similar.map(::getManga)
    }

    val authorizedRequestRegex by lazy { Regex("""(.+\?crt=)(.*)""") }
    fun interceptCloudFlareTurnstile(chain: Interceptor.Chain): Response {
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

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @SuppressLint("SetJavaScriptEnabled")
    fun resolveInWebview(): Pair<String?, String?> {
        Log.e("WebviewInterceptor", "resolveInWebview")
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
                            Log.e("WebviewInterceptor", "Authorization: $authorization")
                            tokenRequested = true

                            try {
                                val noRedirectClient = client.newBuilder().followRedirects(false).build()
                                val authHeaders = headersBuilder()
                                    .set("Referer", "$domainUrl/")
                                    .set("Origin", domainUrl)
                                    .set("Authorization", authHeader)
                                    .build()
                                val response = noRedirectClient.newCall(POST(authUrl, authHeaders)).execute()
                                response.use {
                                    if (response.isSuccessful) {
                                        with(response) {
                                            token = body.string()
                                                .removeSurrounding("\"")
                                            Log.e("WebviewInterceptor", "Requested token: $token")
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
                            Log.e("WebviewInterceptor", "Authorization: $authorization")
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
                            Log.e("WebviewInterceptor", "Clearance: $token")
                            latch.countDown()
                        }
                        Log.e("WebviewInterceptor", "Page finished")
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
                    Log.e("WebviewInterceptor", "Clearance: $it / $token - Authorization: $authorization")
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
        Handler(Looper.getMainLooper()).post {
            val webView = WebView(Injekt.get<Application>())
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

    // Settings

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_IMAGERES
            title = "Image Resolution"
            entries = arrayOf("780x", "980x", "1280x", "1600x", "Original")
            entryValues = arrayOf("780", "980", "1280", "1600", "0")
            summary = "%s"
            setDefaultValue("1280")
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_REM_ADD
            title = "Remove additional information in title"
            summary = "Remove anything in brackets from manga titles.\n" +
                "Reload manga to apply changes to loaded manga."
            setDefaultValue(false)
        }.also(screen::addPreference)
    }

    private inline fun <reified T> Response.parseAs(): T {
        return json.decodeFromString(body.string())
    }

    companion object {
        const val PREFIX_ID_KEY_SEARCH = "id:"
        private const val PREF_IMAGERES = "pref_image_quality"
        private const val PREF_REM_ADD = "pref_remove_additional"
    }
}
