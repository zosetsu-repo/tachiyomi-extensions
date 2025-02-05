package eu.kanade.tachiyomi.extension.en.comicextra

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class ComicExtra : ParsedHttpSource() {

    override val name = "ComicExtra"

    override val baseUrl = "https://azcomix.me"

    override val lang = "en"

    override val supportsLatest = true

    override val client: OkHttpClient = network.cloudflareClient

    override fun popularMangaSelector() = ".eg-box"

    override fun latestUpdatesSelector() = "ul.line-list > li > a.big-link"

    override fun popularMangaRequest(page: Int) = GET("$baseUrl/popular-comics?page=$page", headers)

    override fun latestUpdatesRequest(page: Int) = GET("$baseUrl/comic-updates?page=$page", headers)

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()
        val genreList = filters.filterIsInstance<GenreGroupFilter>().firstOrNull()

        val genreBrowse = genreList?.let { genres ->
            genres.included.singleOrNull().takeIf { genres.excluded.isEmpty() }
        }
        val genreSelected = genreList?.let { it.included.isNotEmpty() || it.excluded.isNotEmpty() }
            ?: false

        val url = if (query.isBlank() && (statusFilter == null || statusFilter.state == 0) &&
            (genreBrowse != null || !genreSelected)
        ) {
            if (genreBrowse != null) {
                "$baseUrl/genre".toHttpUrl().newBuilder().apply {
                    addPathSegment(genreBrowse.lowercase().replace('+', '-'))
                    addQueryParameter("page", page.toString())
                }.build()
            } else {
                "$baseUrl/new-comics".toHttpUrl().newBuilder().apply {
                    addQueryParameter("page", page.toString())
                }.build()
            }
        } else {
            "$baseUrl/advanced-search".toHttpUrl().newBuilder().apply {
                addQueryParameter("key", query)
                addQueryParameter("page", page.toString())
                statusFilter?.let { addQueryParameter("status", it.selected) }
                genreList?.let {
                    addEncodedQueryParameter("wg", it.included.joinToString("%2C"))
                    addEncodedQueryParameter("wog", it.excluded.joinToString("%2C"))
                }
            }.build()
        }
        return GET(url, headers)
    }

    override fun popularMangaFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.selectFirst("a")!!.absUrl("href"))
        title = element.selectFirst("a.egb-serie, a.dlb-title, a.igb-name")!!.ownText()
        element.selectFirst("img")?.also { thumbnail_url = it.getImgAttr() }
        genre = element.select(".egb-details a, .dlb-details a, .igb-genres a").joinToString { it.ownText() }
    }

    override fun latestUpdatesFromElement(element: Element) = SManga.create().apply {
        setUrlWithoutDomain(element.absUrl("href"))
        title = element.ownText()
        thumbnail_url = "$baseUrl/images/sites/default.jpg"
    }

    override fun popularMangaNextPageSelector() = ".general-nav a:last-child"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    // advanced search, genre browse & new-comic browse
    override fun searchMangaSelector() = ".dl-box, .ig-box, ${popularMangaSelector()}"

    override fun searchMangaFromElement(element: Element) = popularMangaFromElement(element)

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun mangaDetailsParse(document: Document): SManga {
        val detailsElement = document.selectFirst(".anime-details")
        val infoElement = detailsElement?.selectFirst(".anime-desc")

        val alternate = infoElement?.selectFirst("td:contains(Alternate Name:)+ td")?.text()
        val year = infoElement?.selectFirst("td:contains(Year of Release:)+ td")?.text()
        val views = infoElement?.selectFirst("td:contains(Views:)+ td")?.text()
        val rating = infoElement?.selectFirst("td:contains(Rating)+ td .rating")?.ownText()

        return SManga.create().apply {
            detailsElement?.selectFirst("h1")?.text()?.also { title = it }
            thumbnail_url = detailsElement?.selectFirst(".anime-image > img")?.getImgAttr()
            detailsElement?.selectFirst(".anime-genres .status")?.text().orEmpty().also { status = parseStatus(it) }
            infoElement?.selectFirst("td:contains(Author:) + td")?.also { author = it.text() }
            genre = detailsElement?.select(".anime-genres li:not(.status)")?.joinToString { it.text() }
            listOfNotNull(
                listOfNotNull(
                    alternate?.let { "Alternate Name: $it" },
                    year?.let { "Year of Release: $it" },
                    views?.let { "Views: $it" },
                    rating?.let { "Rating: $it" },
                ).joinToString("\n"),
                document.selectFirst(".detail-desc-content p")?.text(),
            ).joinToString("\n\n")
                .ifEmpty { null }
                ?.also { description = it }
        }
    }

    private fun parseStatus(element: String): Int = when {
        element.contains("Completed") -> SManga.COMPLETED
        element.contains("Ongoing") -> SManga.ONGOING
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "ul li a.ch-name"

    private val dateFormat by lazy { SimpleDateFormat("MM/dd/yy", Locale.ENGLISH) }

    override fun chapterFromElement(element: Element): SChapter = SChapter.create().apply {
        setUrlWithoutDomain(element.attr("abs:href") + "/full")
        name = element.ownText()
        try {
            date_upload = element.nextElementSibling()!!.text().let {
                dateFormat.parse(it)?.time ?: 0L
            }
        } catch (_: Exception) {
        }
    }

    override fun pageListParse(document: Document): List<Page> {
        return document.select(".chapter-container img").mapIndexed { idx, img ->
            Page(idx, imageUrl = img.getImgAttr())
        }
    }

    override fun imageUrlParse(document: Document) = throw UnsupportedOperationException()

    // Filters

    override fun getFilterList() = FilterList(
        Filter.Header("Leave empty to browse New comics"),
        Filter.Separator(),
        StatusFilter(getStatusList, 0),
        GenreGroupFilter(getGenreList()),
    )

    class SelectFilterOption(val name: String, val value: String)
    class TriStateFilterOption(val value: String, name: String, default: Int = 0) : Filter.TriState(name, default)

    abstract class SelectFilter(name: String, private val options: List<SelectFilterOption>, default: Int = 0) : Filter.Select<String>(name, options.map { it.name }.toTypedArray(), default) {
        val selected: String
            get() = options[state].value
    }

    abstract class TriStateGroupFilter(name: String, options: List<TriStateFilterOption>) : Filter.Group<TriStateFilterOption>(name, options) {
        val included: List<String>
            get() = state.filter { it.isIncluded() }.map { it.value }

        val excluded: List<String>
            get() = state.filter { it.isExcluded() }.map { it.value }
    }

    class StatusFilter(options: List<SelectFilterOption>, default: Int) : SelectFilter("Status", options, default)
    class GenreGroupFilter(options: List<TriStateFilterOption>) : TriStateGroupFilter("Genre", options)

    private val getStatusList = listOf(
        SelectFilterOption("All", ""),
        SelectFilterOption("Ongoing", "ONG"),
        SelectFilterOption("Completed", "CMP"),
    )

    // console.log([...document.querySelectorAll(".genre-check-list li")].map((el) => `TriStateFilterOption("${el.innerText}", "${el.innerText.replaceAll(" ","+")}")`).join(',\n'))
    // on https://azcomix.me/advanced-search
    private fun getGenreList() = listOf(
        TriStateFilterOption("Marvel", "Marvel"),
        TriStateFilterOption("DC Comics", "DC+Comics"),
        TriStateFilterOption("Action", "Action"),
        TriStateFilterOption("Adventure", "Adventure"),
        TriStateFilterOption("Anthology", "Anthology"),
        TriStateFilterOption("Anthropomorphic", "Anthropomorphic"),
        TriStateFilterOption("Biography", "Biography"),
        TriStateFilterOption("Children", "Children"),
        TriStateFilterOption("Comedy", "Comedy"),
        TriStateFilterOption("Crime", "Crime"),
        TriStateFilterOption("Cyborgs", "Cyborgs"),
        TriStateFilterOption("Dark Horse", "Dark+Horse"),
        TriStateFilterOption("Demons", "Demons"),
        TriStateFilterOption("Drama", "Drama"),
        TriStateFilterOption("Fantasy", "Fantasy"),
        TriStateFilterOption("Family", "Family"),
        TriStateFilterOption("Fighting", "Fighting"),
        TriStateFilterOption("Gore", "Gore"),
        TriStateFilterOption("Graphic Novels", "Graphic+Novels"),
        TriStateFilterOption("Historical", "Historical"),
        TriStateFilterOption("Horror", "Horror"),
        TriStateFilterOption("Leading Ladies", "Leading+Ladies"),
        TriStateFilterOption("Literature", "Literature"),
        TriStateFilterOption("Magic", "Magic"),
        TriStateFilterOption("Manga", "Manga"),
        TriStateFilterOption("Martial Arts", "Martial+Arts"),
        TriStateFilterOption("Mature", "Mature"),
        TriStateFilterOption("Mecha", "Mecha"),
        TriStateFilterOption("Military", "Military"),
        TriStateFilterOption("Movie Cinematic Link", "Movie+Cinematic+Link"),
        TriStateFilterOption("Mystery", "Mystery"),
        TriStateFilterOption("Mythology", "Mythology"),
        TriStateFilterOption("Psychological", "Psychological"),
        TriStateFilterOption("Personal", "Personal"),
        TriStateFilterOption("Political", "Political"),
        TriStateFilterOption("Post-Apocalyptic", "Post-Apocalyptic"),
        TriStateFilterOption("Pulp", "Pulp"),
        TriStateFilterOption("Robots", "Robots"),
        TriStateFilterOption("Romance", "Romance"),
        TriStateFilterOption("Sci-Fi", "Sci-Fi"),
        TriStateFilterOption("Slice of Life", "Slice+of+Life"),
        TriStateFilterOption("Science Fiction", "Science+Fiction"),
        TriStateFilterOption("Sports", "Sports"),
        TriStateFilterOption("Spy", "Spy"),
        TriStateFilterOption("Superhero", "Superhero"),
        TriStateFilterOption("Supernatural", "Supernatural"),
        TriStateFilterOption("Suspense", "Suspense"),
        TriStateFilterOption("Thriller", "Thriller"),
        TriStateFilterOption("Tragedy", "Tragedy"),
        TriStateFilterOption("Vampires", "Vampires"),
        TriStateFilterOption("Vertigo", "Vertigo"),
        TriStateFilterOption("Video Games", "Video+Games"),
        TriStateFilterOption("War", "War"),
        TriStateFilterOption("Western", "Western"),
        TriStateFilterOption("Zombies", "Zombies"),
    )

    private fun Element.getImgAttr(): String? {
        return when {
            hasAttr("data-original") -> attr("abs:data-original")
            hasAttr("data-src") -> attr("abs:data-src")
            hasAttr("data-bg") -> attr("abs:data-bg")
            hasAttr("data-srcset") -> attr("abs:data-srcset")
            hasAttr("style") -> attr("style")
                .substringAfter("(").substringBefore(")")
            else -> attr("abs:src")
        }
    }

    override fun relatedMangaListSelector() = searchMangaSelector()
}
