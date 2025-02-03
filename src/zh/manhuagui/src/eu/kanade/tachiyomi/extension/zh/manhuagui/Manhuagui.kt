package eu.kanade.tachiyomi.extension.zh.manhuagui

import android.app.Application
import android.content.SharedPreferences
import eu.kanade.tachiyomi.lib.i18n.Intl
import eu.kanade.tachiyomi.lib.lzstring.LZString
import eu.kanade.tachiyomi.lib.unpacker.Unpacker
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import rx.Observable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

class Manhuagui(
    override val name: String = "漫画柜",
    override val lang: String = "zh",
) : ConfigurableSource, ParsedHttpSource() {

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val baseHost = if (preferences.getBoolean(USE_MIRROR_URL_PREF, false)) {
        "mhgui.com"
    } else {
        "manhuagui.com"
    }

    override val baseUrl =
        if (preferences.getBoolean(SHOW_ZH_HANT_WEBSITE_PREF, false)) {
            "https://tw.$baseHost"
        } else {
            "https://www.$baseHost"
        }
    override val supportsLatest = true

    private val imageServer = arrayOf("https://i.hamreus.com", "https://cf.hamreus.com")
    private val mobileWebsiteUrl = "https://m.$baseHost"
    private val json: Json by injectLazy()
    private val baseHttpUrl: HttpUrl = baseUrl.toHttpUrl()

    private val intl by lazy {
        Intl(
            language = Locale.getDefault().language,
            baseLanguage = "en",
            availableLanguages = setOf("en", "zh"),
            classLoader = this::class.java.classLoader!!,
        )
    }

    // Add rate limit to fix manga thumbnail load failure
    override val client: OkHttpClient =
        if (getShowR18()) {
            network.client.newBuilder()
                .rateLimitHost(baseHttpUrl, preferences.getString(MAINSITE_RATELIMIT_PREF, MAINSITE_RATELIMIT_DEFAULT_VALUE)!!.toInt(), 10)
                .rateLimitHost(imageServer[0].toHttpUrl(), preferences.getString(IMAGE_CDN_RATELIMIT_PREF, IMAGE_CDN_RATELIMIT_DEFAULT_VALUE)!!.toInt())
                .rateLimitHost(imageServer[1].toHttpUrl(), preferences.getString(IMAGE_CDN_RATELIMIT_PREF, IMAGE_CDN_RATELIMIT_DEFAULT_VALUE)!!.toInt())
                .addNetworkInterceptor(AddCookieHeaderInterceptor(baseHttpUrl.host))
                .build()
        } else {
            network.client.newBuilder()
                .rateLimitHost(baseHttpUrl, preferences.getString(MAINSITE_RATELIMIT_PREF, MAINSITE_RATELIMIT_DEFAULT_VALUE)!!.toInt(), 10)
                .rateLimitHost(imageServer[0].toHttpUrl(), preferences.getString(IMAGE_CDN_RATELIMIT_PREF, IMAGE_CDN_RATELIMIT_DEFAULT_VALUE)!!.toInt())
                .rateLimitHost(imageServer[1].toHttpUrl(), preferences.getString(IMAGE_CDN_RATELIMIT_PREF, IMAGE_CDN_RATELIMIT_DEFAULT_VALUE)!!.toInt())
                .build()
        }

    // Add R18 verification cookie
    class AddCookieHeaderInterceptor(private val baseHost: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            if (chain.request().url.host == baseHost) {
                val originalCookies = chain.request().header("Cookie") ?: ""
                if (originalCookies != "" && !originalCookies.contains("isAdult=1")) {
                    return chain.proceed(
                        chain.request().newBuilder()
                            .header("Cookie", "$originalCookies; isAdult=1")
                            .build(),
                    )
                }
            }
            return chain.proceed(chain.request())
        }
    }

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/list/view_p$page.html", headers)
    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/list/update_p$page.html", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        if (query != "") {
            // Normal search
            return GET("$baseUrl/s/${query}_p$page.html", headers)
        } else {
            // Filters search
            val params = filters.map {
                if (it !is SortFilter && it is UriPartFilter) {
                    it.toUriPart()
                } else {
                    ""
                }
            }.filter { it != "" }.joinToString("_")

            val sortOrder = filters.filterIsInstance<SortFilter>()
                .joinToString("") {
                    (it as UriPartFilter).toUriPart()
                }

            // Example: https://www.manhuagui.com/list/japan_maoxian_qingnian_2020_b/update_p1.html
            //                                        /$params                      /$sortOrder $page
            var url = "$baseUrl/list"
            if (params != "") {
                url += "/$params"
            }
            url += if (sortOrder == "") {
                "/index_p$page.html"
            } else {
                "/${sortOrder}_p$page.html"
            }
            return GET(url, headers)
        }
    }

    // Return mobile webpage url to "Open in browser" and "Share manga".
    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(mobileWebsiteUrl + manga.url)
    }

    // Bypass mangaDetailsRequest
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        val call = client.newCall(GET(baseUrl + manga.url, headers))
        val bid = Regex("""\d+""").find(manga.url)?.value
        if (bid != null) {
            // Send a get request to https://www.manhuagui.com/tools/vote.ashx?act=get&bid=$bid
            // and a post request to https://www.manhuagui.com/tools/submit_ajax.ashx?action=user_check_login
            // to simulate what web page javascript do and get "country" cookie.
            // Send requests using coroutine in another (IO) thread.
            GlobalScope.launch {
                withContext(Dispatchers.IO) {
                    // Delay 1 second to wait main manga details request complete
                    delay(1000L)
                    client.newCall(
                        POST(
                            "$baseUrl/tools/submit_ajax.ashx?action=user_check_login",
                            headersBuilder()
                                .set("Referer", manga.url)
                                .set("X-Requested-With", "XMLHttpRequest")
                                .build(),
                        ),
                    ).enqueue(
                        object : Callback {
                            override fun onFailure(call: Call, e: IOException) = e.printStackTrace()
                            override fun onResponse(call: Call, response: Response) = response.close()
                        },
                    )

                    client.newCall(
                        GET(
                            "$baseUrl/tools/vote.ashx?act=get&bid=$bid",
                            headersBuilder()
                                .set("Referer", manga.url)
                                .set("X-Requested-With", "XMLHttpRequest").build(),
                        ),
                    ).enqueue(
                        object : Callback {
                            override fun onFailure(call: Call, e: IOException) = e.printStackTrace()
                            override fun onResponse(call: Call, response: Response) = response.close()
                        },
                    )
                }
            }
        }
        return call
            .asObservableSuccess()
            .map { response ->
                mangaDetailsParse(response).apply { initialized = true }
            }
    }

    // For ManhuaguiUrlActivity
    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/comic/$id", headers)

    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val sManga = mangaDetailsParse(response)
        sManga.url = "/comic/$id/"
        return MangasPage(listOf(sManga), false)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        return if (query.startsWith(PREFIX_ID_SEARCH)) {
            val id = query.removePrefix(PREFIX_ID_SEARCH)
            client.newCall(searchMangaByIdRequest(id))
                .asObservableSuccess()
                .map { response -> searchMangaByIdParse(response, id) }
        } else {
            super.fetchSearchManga(page, query, filters)
        }
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        if (response.request.url.encodedPath.startsWith("/s/")) {
            // Normal search
            val mangas = document.select(searchMangaSelector()).map { element ->
                searchMangaFromElement(element)
            }
            val hasNextPage = searchMangaNextPageSelector().let { selector ->
                document.select(selector).first()
            } != null

            return MangasPage(mangas, hasNextPage)
        } else {
            // Filters search
            val mangas = document.select(popularMangaSelector()).map { element ->
                popularMangaFromElement(element)
            }
            val hasNextPage = document.select(popularMangaNextPageSelector()).first() != null
            return MangasPage(mangas, hasNextPage)
        }
    }

    override fun popularMangaSelector() = "ul#contList > li"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = "div.book-result > ul > li"
    override fun chapterListSelector() = "ul > li > a.status0"

    override fun searchMangaNextPageSelector() = "span.current + a" // "a.prev" contain 2~4 elements: first, previous, next and last page, "span.current + a" is a better choice.
    override fun popularMangaNextPageSelector() = searchMangaNextPageSelector()
    override fun latestUpdatesNextPageSelector() = searchMangaNextPageSelector()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .set("Referer", baseUrl)
        .set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/84.0.4147.105 Safari/537.36")
        .set("Accept-Language", "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7")

    override fun popularMangaFromElement(element: Element) = mangaFromElement(element)
    override fun latestUpdatesFromElement(element: Element) = mangaFromElement(element)
    private fun mangaFromElement(element: Element): SManga {
        val manga = SManga.create()
        element.select("a.bcover").first()!!.let {
            manga.url = it.attr("href")
            manga.title = it.attr("title").trim()

            // Fix thumbnail lazy load
            val thumbnailElement = it.select("img").first()!!
            manga.thumbnail_url = if (thumbnailElement.hasAttr("src")) {
                thumbnailElement.attr("abs:src")
            } else {
                thumbnailElement.attr("abs:data-src")
            }
        }
        return manga
    }

    override fun searchMangaFromElement(element: Element): SManga {
        val manga = SManga.create()

        element.select("div.book-detail").first()!!.let {
            manga.url = it.select("dl > dt > a").first()!!.attr("href")
            manga.title = it.select("dl > dt > a").first()!!.attr("title").trim()
            manga.thumbnail_url = element.select("div.book-cover > a.bcover > img").first()!!.attr("abs:src")
        }

        return manga
    }

    override fun chapterFromElement(element: Element) = throw UnsupportedOperationException()
    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val chapters = mutableListOf<SChapter>()

        // Try to get R18 manga hidden chapter list
        val hiddenEncryptedChapterList = document.select("#__VIEWSTATE").first()
        if (hiddenEncryptedChapterList != null) {
            if (getShowR18()) {
                // Hidden chapter list is LZString encoded
                val decodedHiddenChapterList = LZString.decompressFromBase64(hiddenEncryptedChapterList.`val`())
                val hiddenChapterList = Jsoup.parse(decodedHiddenChapterList, response.request.url.toString())

                // Replace R18 warning with actual chapter list
                document.select("#erroraudit_show").first()!!.replaceWith(hiddenChapterList)
                // Remove hidden chapter list element
                document.select("#__VIEWSTATE").first()!!.remove()
            } else {
                // "You need to enable R18 switch and restart Tachiyomi to read this manga"
                error(intl["R18_NEED_ENABLE"])
            }
        }
        val latestChapterHref = document.select("div.book-detail > ul.detail-list > li.status > span > a.blue").first()?.attr("href")
        val chNumRegex = Regex("""\d+""")

        val sectionList = document.select("[id^=chapter-list-]")
        sectionList.forEach { section ->
            val pageList = section.select("ul")
            pageList.reverse()
            pageList.forEach { page ->
                val pageChapters = mutableListOf<SChapter>()
                val chapterList = page.select("li > a.status0")
                chapterList.forEach {
                    val currentChapter = SChapter.create()
                    currentChapter.url = it.attr("href")
                    currentChapter.name = it.attr("title").trim().ifEmpty { it.select("span").first()!!.ownText() }
                    currentChapter.chapter_number = chNumRegex.find(currentChapter.name)?.value?.toFloatOrNull() ?: -1F

                    // Manhuagui only provide upload date for latest chapter
                    if (currentChapter.url == latestChapterHref) {
                        currentChapter.date_upload = parseDate(document.select("div.book-detail > ul.detail-list > li.status > span > span.red").last()!!)
                    }
                    pageChapters.add(currentChapter)
                }

                chapters.addAll(pageChapters)
            }
        }

        return chapters
    }

    private fun parseDate(element: Element): Long = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(element.text())?.time ?: 0

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        /**
         * When searching manga from intent filter, sometimes will cause the error below and manga don't appear in search result:
         *   eu.kanade.tachiyomi.debug E/GlobalSearchPresenter$search: kotlin.UninitializedPropertyAccessException: lateinit property title has not been initialized
         *      at eu.kanade.tachiyomi.source.model.SMangaImpl.getTitle(SMangaImpl.kt:7)
         *      at eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchPresenter.networkToLocalManga(GlobalSearchPresenter.kt:259)
         *      at eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchPresenter$search$1$4.call(GlobalSearchPresenter.kt:172)
         *      at eu.kanade.tachiyomi.ui.browse.source.globalsearch.GlobalSearchPresenter$search$1$4.call(GlobalSearchPresenter.kt:34)
         * Parse manga.title here can solve it.
         */
        manga.title = document.select("div.book-title > h1:nth-child(1)").text()
        manga.description = document.select("div#intro-all").text()
        manga.thumbnail_url = document.select("p.hcover > img").attr("abs:src")
        manga.author = document.select("span:contains(漫画作者) > a , span:contains(漫畫作者) > a").text().replace(" ", ", ")
        manga.genre = document.select("span:contains(漫画剧情) > a , span:contains(漫畫劇情) > a").text()
            .split(' ').joinToString(transform = ::translateGenre)
        manga.status = when (document.select("div.book-detail > ul.detail-list > li.status > span > span").first()?.text()) {
            "连载中" -> SManga.ONGOING
            "已完结" -> SManga.COMPLETED
            "連載中" -> SManga.ONGOING
            "已完結" -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }

        return manga
    }

    // Page list is inside [packed](http://dean.edwards.name/packer/) JavaScript with a special twist:
    // the normal content array (`'a|b|c'.split('|')`) is replaced with LZString and base64-encoded
    // version.
    //
    // These "\" can't be remove: "\}", more info in pull request 3926.
    @Suppress("RegExpRedundantEscape")
    private val packedRegex = Regex("""window\[".*?"\](\(.*\)\s*\{[\s\S]+\}\s*\(.*\))""")

    @Suppress("RegExpRedundantEscape")
    private val blockCcArgRegex = Regex("""\{.*\}""")

    private val packedContentRegex = Regex("""['"]([0-9A-Za-z+/=]+)['"]\[['"].*?['"]]\(['"].*?['"]\)""")

    private val singleQuoteRegex = Regex("""\\'""")

    override fun pageListParse(document: Document): List<Page> {
        // R18 warning element (#erroraudit_show) is remove by web page javascript, so here the warning element
        // will always exist if this manga is R18 limited whether R18 verification cookies has been sent or not.
        // But it will not interfere parse mechanism below.
        if (document.select("#erroraudit_show").first() != null && !getShowR18()) {
            error(intl["R18_NOT_EFFECTIVE"]) // "R18 setting didn't enabled or became effective"
        }

        val html = document.html()
        val imgCode = packedRegex.find(html)!!.groupValues[1].let {
            // Make the packed content normal again so :lib:unpacker can do its job
            it.replace(packedContentRegex) { match ->
                val lzs = match.groupValues[1]
                val decoded = LZString.decompressFromBase64(lzs)
                "'$decoded'.split('|')"
            }
        }
        // Convert single quote to dash before passing to unpack, since unpack will replace it
        // with double quote, which may make json parse fail.
        val imgDecode = Unpacker.unpack(singleQuoteRegex.replace(imgCode, "-"))
        val imgJsonStr = blockCcArgRegex.find(imgDecode)!!.groupValues[0]
        val imageJson: Comic = json.decodeFromString(imgJsonStr)

        return imageJson.files!!.mapIndexed { i, imgStr ->
            val imgurl = "${imageServer[0]}${imageJson.path}$imgStr?e=${imageJson.sl?.e}&m=${imageJson.sl?.m}"
            Page(i, "", imgurl)
        }
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        val mainSiteRateLimitPreference = androidx.preference.ListPreference(screen.context).apply {
            key = MAINSITE_RATELIMIT_PREF
            title = intl["MAINSITE_RATELIMIT_PREF_TITLE"]
            entries = ENTRIES_ARRAY
            entryValues = ENTRIES_ARRAY
            summary = intl["MAINSITE_RATELIMIT_PREF_SUMMARY"]

            setDefaultValue(MAINSITE_RATELIMIT_DEFAULT_VALUE)
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putString(MAINSITE_RATELIMIT_PREF, newValue as String).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        val imgCDNRateLimitPreference = androidx.preference.ListPreference(screen.context).apply {
            key = IMAGE_CDN_RATELIMIT_PREF
            title = intl["IMAGE_CDN_RATELIMIT_PREF_TITLE"]
            entries = ENTRIES_ARRAY
            entryValues = ENTRIES_ARRAY
            summary = intl["IMAGE_CDN_RATELIMIT_PREF_SUMMARY"]

            setDefaultValue(IMAGE_CDN_RATELIMIT_DEFAULT_VALUE)
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putString(IMAGE_CDN_RATELIMIT_PREF, newValue as String).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        // Simplified/Traditional Chinese version website switch
        val zhHantPreference = androidx.preference.CheckBoxPreference(screen.context).apply {
            key = SHOW_ZH_HANT_WEBSITE_PREF
            title = intl["SHOW_ZH_HANT_WEBSITE_PREF_TITLE"]
            summary = intl["SHOW_ZH_HANT_WEBSITE_PREF_SUMMARY"]

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val setting = preferences.edit().putBoolean(SHOW_ZH_HANT_WEBSITE_PREF, newValue as Boolean).commit()
                    setting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        // R18+ switch
        val r18Preference = androidx.preference.CheckBoxPreference(screen.context).apply {
            key = SHOW_R18_PREF
            title = intl["SHOW_R18_PREF_TITLE"]
            summary = intl["SHOW_R18_PREF_SUMMARY"]

            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val newSetting = preferences.edit().putBoolean(SHOW_R18_PREF, newValue as Boolean).commit()
                    newSetting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        val mirrorURLPreference = androidx.preference.CheckBoxPreference(screen.context).apply {
            key = USE_MIRROR_URL_PREF
            title = intl["USE_MIRROR_URL_PREF_TITLE"]
            summary = intl["USE_MIRROR_URL_PREF_SUMMARY"]

            setDefaultValue(false)
            setOnPreferenceChangeListener { _, newValue ->
                try {
                    val newSetting = preferences.edit().putBoolean(USE_MIRROR_URL_PREF, newValue as Boolean).commit()
                    newSetting
                } catch (e: Exception) {
                    e.printStackTrace()
                    false
                }
            }
        }

        screen.addPreference(mainSiteRateLimitPreference)
        screen.addPreference(imgCDNRateLimitPreference)
        screen.addPreference(zhHantPreference)
        screen.addPreference(r18Preference)
        screen.addPreference(mirrorURLPreference)
    }

    private fun getShowR18(): Boolean = preferences.getBoolean(SHOW_R18_PREF, false)

    private open class UriPartFilter(
        displayName: String,
        val pair: Array<Pair<String, String>>,
        defaultState: Int = 0,
    ) : Filter.Select<String>(displayName, pair.map { it.first }.toTypedArray(), defaultState) {
        open fun toUriPart() = pair[state].second
    }

    override fun getFilterList() = FilterList(
        SortFilter(
            intl["SORT_BY"],
            arrayOf(
                Pair(intl["SORT_BY_POPULAR"], "view"), // Same to popularMangaRequest()
                Pair(intl["SORT_BY_RELEASE"], ""), // Publish date
                Pair(intl["SORT_BY_UPDATE"], "update"),
                Pair(intl["SORT_BY_RATE"], "rate"),
            ),
        ),
        LocaleFilter(
            intl["BY_REGION"],
            arrayOf(
                Pair(intl["REGION_ALL"], ""), // all
                Pair(intl["REGION_JAPAN"], "japan"),
                Pair(intl["REGION_HONGKONG"], "hongkong"),
                Pair(intl["REGION_OTHER"], "other"),
                Pair(intl["REGION_EUROPE"], "europe"),
                Pair(intl["REGION_CHINA"], "china"),
                Pair(intl["REGION_KOREA"], "korea"),
            ),
        ),
        GenreFilter(
            intl["BY_GENRE"],
            arrayOf(
                Pair(intl["GENRE_ALL"], ""),
                Pair(intl["GENRE_rexue"], "rexue"),
                Pair(intl["GENRE_maoxian"], "maoxian"),
                Pair(intl["GENRE_mohuan"], "mohuan"),
                Pair(intl["GENRE_shengui"], "shengui"),
                Pair(intl["GENRE_gaoxiao"], "gaoxiao"),
                Pair(intl["GENRE_mengxi"], "mengxi"),
                Pair(intl["GENRE_aiqing"], "aiqing"),
                Pair(intl["GENRE_kehuan"], "kehuan"),
                Pair(intl["GENRE_mofa"], "mofa"),
                Pair(intl["GENRE_gedou"], "gedou"),
                Pair(intl["GENRE_wuxia"], "wuxia"),
                Pair(intl["GENRE_jizhan"], "jizhan"),
                Pair(intl["GENRE_zhanzheng"], "zhanzheng"),
                Pair(intl["GENRE_jingji"], "jingji"),
                Pair(intl["GENRE_tiyu"], "tiyu"),
                Pair(intl["GENRE_xiaoyuan"], "xiaoyuan"),
                Pair(intl["GENRE_shenghuo"], "shenghuo"),
                Pair(intl["GENRE_lizhi"], "lizhi"),
                Pair(intl["GENRE_lishi"], "lishi"),
                Pair(intl["GENRE_weiniang"], "weiniang"),
                Pair(intl["GENRE_zhainan"], "zhainan"),
                Pair(intl["GENRE_funv"], "funv"),
                Pair(intl["GENRE_danmei"], "danmei"),
                Pair(intl["GENRE_baihe"], "baihe"),
                Pair(intl["GENRE_hougong"], "hougong"),
                Pair(intl["GENRE_zhiyu"], "zhiyu"),
                Pair(intl["GENRE_meishi"], "meishi"),
                Pair(intl["GENRE_tuili"], "tuili"),
                Pair(intl["GENRE_xuanyi"], "xuanyi"),
                Pair(intl["GENRE_kongbu"], "kongbu"),
                Pair(intl["GENRE_sige"], "sige"),
                Pair(intl["GENRE_zhichang"], "zhichang"),
                Pair(intl["GENRE_zhentan"], "zhentan"),
                Pair(intl["GENRE_shehui"], "shehui"),
                Pair(intl["GENRE_yinyue"], "yinyue"),
                Pair(intl["GENRE_wudao"], "wudao"),
                Pair(intl["GENRE_zazhi"], "zazhi"),
                Pair(intl["GENRE_heidao"], "heidao"),
            ),
        ),
        ReaderFilter(
            intl["BY_AUDIENCE"],
            arrayOf(
                Pair(intl["AUDIENCE_ALL"], ""),
                Pair(intl["AUDIENCE_shaonv"], "shaonv"),
                Pair(intl["AUDIENCE_shaonian"], "shaonian"),
                Pair(intl["AUDIENCE_qingnian"], "qingnian"),
                Pair(intl["AUDIENCE_ertong"], "ertong"),
                Pair(intl["AUDIENCE_tongyong"], "tongyong"),
            ),
        ),
        PublishDateFilter(
            intl["BY_YEAR"],
            arrayOf(
                Pair(intl["YEAR_ALL"], ""),
                Pair(intl["YEAR_2020"], "2020"),
                Pair(intl["YEAR_2019"], "2019"),
                Pair(intl["YEAR_2018"], "2018"),
                Pair(intl["YEAR_2017"], "2017"),
                Pair(intl["YEAR_2016"], "2016"),
                Pair(intl["YEAR_2015"], "2015"),
                Pair(intl["YEAR_2014"], "2014"),
                Pair(intl["YEAR_2013"], "2013"),
                Pair(intl["YEAR_2012"], "2012"),
                Pair(intl["YEAR_2011"], "2011"),
                Pair(intl["YEAR_2010"], "2010"),
                Pair(intl["YEAR_200x"], "200x"),
                Pair(intl["YEAR_199x"], "199x"),
                Pair(intl["YEAR_198x"], "198x"),
                Pair(intl["YEAR_197x"], "197x"),
            ),
        ),
        FirstLetterFilter(
            intl["BY_FIRST_LETER"],
            arrayOf(
                Pair(intl["FIRST_LETTER_ALL"], ""),
                Pair("A", "a"),
                Pair("B", "b"),
                Pair("C", "c"),
                Pair("D", "d"),
                Pair("E", "e"),
                Pair("F", "f"),
                Pair("G", "g"),
                Pair("H", "h"),
                Pair("I", "i"),
                Pair("J", "j"),
                Pair("K", "k"),
                Pair("L", "l"),
                Pair("M", "m"),
                Pair("N", "n"),
                Pair("O", "o"),
                Pair("P", "p"),
                Pair("Q", "q"),
                Pair("R", "r"),
                Pair("S", "s"),
                Pair("T", "t"),
                Pair("U", "u"),
                Pair("V", "v"),
                Pair("W", "w"),
                Pair("X", "x"),
                Pair("Y", "y"),
                Pair("Z", "z"),
                Pair("0-9", "0-9"),
            ),
        ),
        StatusFilter(
            intl["BY_PROGRESS"],
            arrayOf(
                Pair(intl["PROGRESS_ALL"], ""),
                Pair(intl["PROGRESS_ONGOING"], "lianzai"),
                Pair(intl["PROGRESS_COMPLETED"], "wanjie"),
            ),
        ),
    )

    private class SortFilter(
        displayName: String,
        pairs: Array<Pair<String, String>>,
    ) : UriPartFilter(displayName, pairs)

    private class LocaleFilter(
        displayName: String,
        pairs: Array<Pair<String, String>>,
    ) : UriPartFilter(displayName, pairs)

    private class GenreFilter(
        displayName: String,
        pairs: Array<Pair<String, String>>,
    ) : UriPartFilter(displayName, pairs)

    private class ReaderFilter(
        displayName: String,
        pairs: Array<Pair<String, String>>,
    ) : UriPartFilter(displayName, pairs)

    private class PublishDateFilter(
        displayName: String,
        pairs: Array<Pair<String, String>>,
    ) : UriPartFilter(displayName, pairs)

    private class FirstLetterFilter(
        displayName: String,
        pairs: Array<Pair<String, String>>,
    ) : UriPartFilter(displayName, pairs)

    private class StatusFilter(
        displayName: String,
        pairs: Array<Pair<String, String>>,
    ) : UriPartFilter(displayName, pairs)

    private fun translateGenre(it: String): String {
        return when (it) {
            "热血" -> intl["GENRE_rexue"]
            "冒险" -> intl["GENRE_maoxian"]
            "魔幻" -> intl["GENRE_mohuan"]
            "神鬼" -> intl["GENRE_shengui"]
            "搞笑" -> intl["GENRE_gaoxiao"]
            "萌系" -> intl["GENRE_mengxi"]
            "爱情" -> intl["GENRE_aiqing"]
            "科幻" -> intl["GENRE_kehuan"]
            "魔法" -> intl["GENRE_mofa"]
            "格斗" -> intl["GENRE_gedou"]
            "武侠" -> intl["GENRE_wuxia"]
            "机战" -> intl["GENRE_jizhan"]
            "战争" -> intl["GENRE_zhanzheng"]
            "竞技" -> intl["GENRE_jingji"]
            "体育" -> intl["GENRE_tiyu"]
            "校园" -> intl["GENRE_xiaoyuan"]
            "生活" -> intl["GENRE_shenghuo"]
            "励志" -> intl["GENRE_lizhi"]
            "历史" -> intl["GENRE_lishi"]
            "伪娘" -> intl["GENRE_weiniang"]
            "宅男" -> intl["GENRE_zhainan"]
            "腐女" -> intl["GENRE_funv"]
            "耽美" -> intl["GENRE_danmei"]
            "百合" -> intl["GENRE_baihe"]
            "后宫" -> intl["GENRE_hougong"]
            "治愈" -> intl["GENRE_zhiyu"]
            "美食" -> intl["GENRE_meishi"]
            "推理" -> intl["GENRE_tuili"]
            "悬疑" -> intl["GENRE_xuanyi"]
            "恐怖" -> intl["GENRE_kongbu"]
            "四格" -> intl["GENRE_sige"]
            "职场" -> intl["GENRE_zhichang"]
            "侦探" -> intl["GENRE_zhentan"]
            "社会" -> intl["GENRE_shehui"]
            "音乐" -> intl["GENRE_yinyue"]
            "舞蹈" -> intl["GENRE_wudao"]
            "杂志" -> intl["GENRE_zazhi"]
            "黑道" -> intl["GENRE_heidao"]
            else -> it
        }
    }

    companion object {
        private const val SHOW_R18_PREF = "showR18Default"

        private const val SHOW_ZH_HANT_WEBSITE_PREF = "showZhHantWebsite"

        private const val USE_MIRROR_URL_PREF = "useMirrorWebsitePreference"

        private const val MAINSITE_RATELIMIT_PREF = "mainSiteRatelimitPreference"
        private const val MAINSITE_RATELIMIT_DEFAULT_VALUE = "10"

        private const val IMAGE_CDN_RATELIMIT_PREF = "imgCDNRatelimitPreference"
        private const val IMAGE_CDN_RATELIMIT_DEFAULT_VALUE = "4"

        private val ENTRIES_ARRAY = (1..10).map { i -> i.toString() }.toTypedArray()
        const val PREFIX_ID_SEARCH = "id:"
    }
}
