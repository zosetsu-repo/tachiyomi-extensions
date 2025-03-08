package eu.kanade.tachiyomi.extension.all.misskon

import android.content.SharedPreferences
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.api.get

class MissKon : ConfigurableSource, HttpSource() {
    override val name = "MissKon (MrCong)"
    override val lang = "all"
    override val supportsLatest = true
    override val versionId = 2

    override val baseUrl = "https://misskon.com"

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    private val SharedPreferences.topDays
        get() = getString(PREF_TOP_DAYS, DEFAULT_TOP_DAYS)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_TOP_DAYS
            title = "Default Top-Days used for Popular"
            summary = "%s"
            entries = topDaysList().map { it.name }.toTypedArray()
            entryValues = topDaysList().indices.map { it.toString() }.toTypedArray()
            setDefaultValue(DEFAULT_TOP_DAYS)
        }.also(screen::addPreference)
    }

    override fun popularMangaRequest(page: Int): Request {
        val topDays = (preferences.topDays?.toInt() ?: 0) + 1
        val topDaysFilter = TopDaysFilter(
            "",
            arrayOf(
                getTopDaysList()[0],
                getTopDaysList()[topDays],
            ),
        ).apply { state = 1 }
        return searchMangaRequest(page, "", FilterList(topDaysFilter))
    }

    override fun popularMangaParse(response: Response) = latestUpdatesParse(response)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/page/$page", headers)

    override fun latestUpdatesParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div#main-content div.post-listing article.item-list")
            .map { element ->
                SManga.create().apply {
                    val post = element.select("h2.post-box-title a").first()!!
                    setUrlWithoutDomain(post.absUrl("href"))
                    title = post.text()
                    thumbnail_url = element.selectFirst("div.post-thumbnail img")?.imgAttr()
                    val meta = element.selectFirst("p.post-meta")
                    description = "View: ${meta?.select("span.post-views")?.text() ?: "---"}"
                    genre = meta?.parseTags()
                }
            }
        val isLastPage = document.selectFirst("div#main-content div.pagination span.current + a.page")
        return MangasPage(mangas, hasNextPage = isLastPage != null)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val tagFilter = filters.filterIsInstance<TagsFilter>().firstOrNull()
        val topDaysFilter = filters.filterIsInstance<TopDaysFilter>().firstOrNull()
        val url = baseUrl.toHttpUrl().newBuilder()
        when {
            query.isNotBlank() -> {
                if (listOf("photo", "photos", "video", "videos").contains(query.trim())) {
                    return GET("$baseUrl/search")
                }
                if (page > 1) {
                    url.addPathSegment("page")
                    url.addPathSegment(page.toString())
                }
                url.addQueryParameter("s", query.trim())
            }
            topDaysFilter != null && topDaysFilter.state > 0 -> {
                url.addPathSegment(topDaysFilter.toUriPart())
            }
            tagFilter != null && tagFilter.state > 0 -> {
                url.addPathSegment("tag")
                url.addPathSegment(tagFilter.toUriPart())

                url.addPathSegment("page")
                url.addPathSegment(page.toString())
            }
            else -> return latestUpdatesRequest(page)
        }
        return GET(url.build(), headers)
    }

    override fun searchMangaParse(response: Response) = latestUpdatesParse(response)

    /* Related titles */
    override fun relatedMangaListParse(response: Response): List<SManga> {
        val document = response.asJsoup()
        return document.select(".content > .yarpp-related a.yarpp-thumbnail").map { element ->
            SManga.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                title = element.attr("title")
                thumbnail_url = element.selectFirst("img")?.imgAttr()
            }
        }
    }

    /* Details */
    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.select(".post-title span").text()
            val view = document.select("p.post-meta span.post-views").text()
            val info = document.select("div.info div.box-inner-block")

            val password = info.select("input").attr("value")
            val downloadAvailable = document.select("div#fukie2.entry a[href]:has(i.fa-download)")
            val downloadLinks = downloadAvailable.joinToString("\n") { element ->
                val serviceText = element.text()
                val link = element.attr("href")
                "$serviceText: $link"
            }

            description = "View: $view\n" +
                "${info.html()
                    .replace("<input.*?>".toRegex(), password)
                    .replace("<.+?>".toRegex(), "")}\n" +
                "Password: $password\n" +
                downloadLinks
            genre = document.parseTags()
        }
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        throw UnsupportedOperationException()
    }

    override fun pageListParse(response: Response): List<Page> {
        throw UnsupportedOperationException()
    }

    private fun Element.parseTags(selector: String = ".post-tag a, .post-cats a"): String {
        return select(selector)
            .also { tags ->
                tags.map {
                    val uri = it.attr("href")
                        .removeSuffix("/")
                        .substringAfterLast('/')
                    tagList[it.text()] = uri
                }
            }
            .joinToString { it.text() }
    }

    override suspend fun getChapterList(manga: SManga): List<SChapter> {
        return listOf(
            SChapter.create().apply {
                setUrlWithoutDomain(manga.url)
                name = "Gallery"
            },
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterPage = mutableListOf<String>()
        client.newCall(pageListRequest(chapter))
            .execute().use { response ->
                val document = response.asJsoup()
                val pages = document
                    .select("div.page-link:first-child a")
                    .mapNotNull {
                        it.absUrl("href")
                    }

                chapterPage += parseImageList(document).toMutableList()

                pages.forEach { url ->
                    val request = GET(url, headers)
                    chapterPage += parseImageList(client.newCall(request).execute().asJsoup())
                }
            }

        return chapterPage.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    private fun parseImageList(document: Document): List<String> = document
        .select("div.entry p img").map { image ->
            image.imgAttr()
        }

    override fun imageUrlParse(response: Response) = throw UnsupportedOperationException()

    /* Filters */
    private val tagList: MutableMap<String, String> = mutableMapOf()

    private val scope = CoroutineScope(Dispatchers.IO)
    private fun launchIO(block: () -> Unit) = scope.launch { block() }
    private var tagsFetched = false
    private var tagsFetchAttempt = 0

    private fun getTags() {
        launchIO {
            if (!tagsFetched && tagsFetchAttempt < 3) {
                val result = runCatching {
                    client.newCall(GET("$baseUrl/sets/", headers))
                        .execute().asJsoup()
                        .select(".entry .tag-counterz a[href*=/tag/]")
                        .mapNotNull {
                            Pair(
                                it.select("strong").text(),
                                it.attr("href")
                                    .removeSuffix("/")
                                    .substringAfterLast('/'),
                            )
                        }
                }
                if (result.isSuccess) {
                    tagList["<Select>"] = ""
                    tagList.putAll(result.getOrNull() ?: emptyList())
                    tagsFetched = true
                }
                tagsFetchAttempt++
            }
        }
    }

    override fun getFilterList(): FilterList {
        getTags()
        return FilterList(
            TopDaysFilter("Top days", getTopDaysList()),
            if (tagList.isEmpty()) {
                Filter.Header("Hit refresh to load Tags")
            } else {
                TagsFilter("Tag", tagList.toList())
            },
        )
    }

    private fun Element.imgAttr(): String {
        return when {
            hasAttr("data-original") -> attr("abs:data-original")
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-bg") -> attr("abs:data-bg")
            hasAttr("data-srcset") -> attr("abs:data-srcset")
            hasAttr("data-srcset") -> attr("abs:data-srcset")
            else -> attr("abs:src")
        }
    }

    companion object {
        private const val PREF_TOP_DAYS = "pref_top_days"
        private const val DEFAULT_TOP_DAYS = "1"
    }
}
