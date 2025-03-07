package eu.kanade.tachiyomi.extension.en.mangaplanet

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.lib.cookieinterceptor.CookieInterceptor
import eu.kanade.tachiyomi.lib.speedbinb.SpeedBinbInterceptor
import eu.kanade.tachiyomi.lib.speedbinb.SpeedBinbReader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale

class MangaPlanet : ConfigurableSource, ParsedHttpSource() {

    override val name = "Manga Planet"
    override val baseUrl = "https://mangaplanet.com"
    override val lang = "en"
    override val supportsLatest = false

    private val json = Injekt.get<Json>()
    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val client = network.client.newBuilder()
        .addInterceptor(SpeedBinbInterceptor(json))
        .addNetworkInterceptor(CookieInterceptor(baseUrl.toHttpUrl().host, "mpaconf" to "18"))
        .build()

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun popularMangaRequest(page: Int) =
        GET("$baseUrl/browse/title?ttlpage=$page", headers)

    override fun popularMangaSelector() = ".book-list"

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.attr("href"))
        title = element.selectFirst("h3")!!.text()
        author = element.selectFirst("p:has(.fa-pen-nib)")?.text()
        description = element.selectFirst("h3 + p")?.text()
        thumbnail_url = element.selectFirst("img")?.absUrl("data-src")
        status = when {
            element.selectFirst(".fa-flag-alt") != null -> SManga.COMPLETED
            element.selectFirst(".fa-arrow-right") != null -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    override fun popularMangaNextPageSelector() = "ul.pagination a.page-link[rel=next]"

    override fun latestUpdatesRequest(page: Int) = throw UnsupportedOperationException()
    override fun latestUpdatesSelector() = throw UnsupportedOperationException()
    override fun latestUpdatesFromElement(element: Element) = throw UnsupportedOperationException()
    override fun latestUpdatesNextPageSelector() = throw UnsupportedOperationException()

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val url = baseUrl.toHttpUrl().newBuilder().apply {
            if (query.isNotEmpty()) {
                addPathSegment("search")
                addQueryParameter("keyword", query)
            } else {
                addPathSegments("browse/title")
            }

            filters.ifEmpty { getFilterList() }
                .filterIsInstance<UrlFilter>()
                .forEach { it.addToUrl(this) }

            if (page > 1) {
                addQueryParameter("ttlpage", page.toString())
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaSelector() = popularMangaSelector()
    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)
    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
        val alternativeTitlesElement = document.selectFirst("h3#manga_title + p")
        val alternativeTitles = alternativeTitlesElement?.textNodes()?.filterNot { it.isBlank() }
            ?.map { it.text().trim() } ?: emptyList()

        val useJapaneseTitles = preferences.getBoolean("useJapaneseTitles", false)
        val japaneseTitle = alternativeTitles.getOrNull(1) ?: ""
        val englishTitle = document.selectFirst("h3#manga_title")!!.text()
        val originalTitle = alternativeTitles.getOrNull(0) ?: ""

        return SManga.create().apply {
            title = if (useJapaneseTitles && japaneseTitle.isNotEmpty()) {
                japaneseTitle
            } else {
                document.selectFirst("h3#manga_title")!!.text()
            }
            author = document.select("h3:has(.fa-pen-nib) a").joinToString { it.text() }

            description = buildString {
                val altTitles = if (useJapaneseTitles) {
                    listOfNotNull(originalTitle, englishTitle)
                } else {
                    alternativeTitles
                }
                if (altTitles.isNotEmpty()) {
                    append("Alternative Titles:\n")
                    altTitles.forEach { append("• $it\n") }
                    append("\n")
                }
                document.selectFirst("h3#manga_title ~ p:eq(2)")?.text()?.let {
                    appendLine(it)
                }
            }

            genre = buildList {
                addAll(document.select("h3:has(.fa-layer-group) a").map { it.text() })
                if (document.select(".fa-pepper-hot").isNotEmpty()) {
                    add("🌶️".repeat(document.select(".fa-pepper-hot").size))
                }
                addAll(document.select(".tags-btn button").map { it.text() })
                document.selectFirst("span:has(.fa-book-spells,.fa-book)")?.text()?.let { add(it) }
                document.selectFirst("span:has(.fa-user-friends)")?.text()?.let { add(it) }

                // Target the specific tags on the manga details page

                val bookDetailDiv = document.selectFirst(".book-detail:has(img.img-thumbnail)")

                if (bookDetailDiv != null) {
                    bookDetailDiv.select("> p").forEach { p ->
                        val text = p.text()
                        if (text.contains("Free Preview") || text.contains("Buy or Rental") || text.contains("Manga Planet Pass")) {
                            if (text.contains("Free Preview")) add("Free Preview")
                            if (text.contains("Buy or Rental")) add("Buy or Rental")
                            if (text.contains("Manga Planet Pass")) add("Manga Planet Pass")
                        }
                    }
                }
            }.joinToString()

            status = when {
                document.selectFirst(".fa-flag-alt") != null -> SManga.COMPLETED
                document.selectFirst(".fa-arrow-right") != null -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
            thumbnail_url = document.selectFirst("img.img-thumbnail")?.absUrl("data-src")
        }
    }

    override fun chapterListSelector() = "ul.ep_ul li.list-group-item"

    override fun chapterFromElement(element: Element) = SChapter.create().apply {
        element.selectFirst("h3 p")!!.let {
            val id = it.id().substringAfter("epi_title_")

            url = "/reader?cid=$id"
            name = it.text()
        }

        date_upload = try {
            val date = element.selectFirst("p")!!.ownText()
            dateFormat.parse(date)!!.time
        } catch (_: Exception) {
            0L
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        return document.select(chapterListSelector())
            .filter { e ->
                e.selectFirst("p")?.ownText()?.contains("Arrives on") != true
            }
            .map { chapterFromElement(it) }
            .reversed()
    }

    private val reader by lazy { SpeedBinbReader(client, headers, json) }

    override fun pageListParse(document: Document): List<Page> {
        if (document.selectFirst("a[href\$=account/sign-up]") != null) {
            throw Exception("Sign up in WebView to read this chapter")
        }

        if (document.selectFirst("a:contains(UNLOCK NOW)") != null) {
            throw Exception("Purchase this chapter in WebView")
        }

        return reader.pageListParse(document)
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    override fun getFilterList() = FilterList(
        SortFilter(),
        AccessTypeFilter(),
        ReleaseStatusFilter(),
        LetterFilter(),
        CategoryFilter(),
        SpicyLevelFilter(),
        FormatFilter(),
        RatingFilter(),
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val useJapaneseTitlesPref = CheckBoxPreference(screen.context).apply {
            key = "useJapaneseTitles"
            title = "Use Japanese Titles"
            summary = "Display Japanese titles instead of English."
        }
        screen.addPreference(useJapaneseTitlesPref)
    }

    private val dateFormat = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
}
