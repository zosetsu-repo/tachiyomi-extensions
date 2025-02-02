package eu.kanade.tachiyomi.extension.en.readcomicnet

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class ReadcomicNet : ParsedHttpSource() {

    override val name = "AZComix"

    override val id = 6225640379944128563

    override val baseUrl = "https://azcomix.me"

    override val lang = "en"

    override val supportsLatest = true

    override fun popularMangaSelector() = ".eg-box"

    override fun latestUpdatesSelector() = "ul.line-list > li > a.big-link"

    override fun searchMangaSelector() = ".dl-box, .ig-box"

    override fun popularMangaNextPageSelector() = ".general-nav a:last-child"

    override fun latestUpdatesNextPageSelector() = popularMangaNextPageSelector()

    override fun searchMangaNextPageSelector() = popularMangaNextPageSelector()

    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/popular-comics?page=$page", headers)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/comic-updates?page=$page", headers)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()
        val genreList = filters.filterIsInstance<GenreList>().firstOrNull()

        val genreBrowse = genreList?.let { genres ->
            genres.included.singleOrNull().takeIf { genres.excluded.isEmpty() }
        }
        return if (genreBrowse != null) {
            GET(
                "$baseUrl/genre".toHttpUrl().newBuilder().apply {
                    addPathSegment(genreBrowse.lowercase().replace('+', '-'))
                    addQueryParameter("page", page.toString())
                }.build(),
                headers,
            )
        } else {
            GET(
                "$baseUrl/advanced-search".toHttpUrl().newBuilder().apply {
                    addQueryParameter("key", query)
                    addQueryParameter("page", page.toString())
                    statusFilter?.let {
                        addQueryParameter("status", it.stateAsQueryString())
                    }
                    genreList?.let {
                        addEncodedQueryParameter("wg", it.included.joinToString(","))
                        addEncodedQueryParameter("wog", it.excluded.joinToString(","))
                    }
                }.build(),
                headers,
            )
        }
    }

    override fun popularMangaFromElement(element: Element): SManga =
        SManga.create().apply {
            setUrlWithoutDomain(element.selectFirst("a")!!.attr("abs:href"))
            title = element.selectFirst("a.egb-serie, a.dlb-title, a.igb-name")!!.ownText()
            thumbnail_url = element.selectFirst("img")?.getImgAttr()
            genre = element.select(".egb-details a, .dlb-details a, .igb-genres a").joinToString { it.ownText() }
        }

    override fun latestUpdatesFromElement(element: Element): SManga =
        SManga.create().apply {
            setUrlWithoutDomain(element.attr("abs:href"))
            title = element.ownText()
        }

    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        val detailsElement = document.selectFirst(".anime-details")
        val infoElement = detailsElement?.selectFirst(".anime-desc")

        val alternate = infoElement?.selectFirst("td:contains(Alternate Name:)+ td")?.text()
        val year = infoElement?.selectFirst("td:contains(Year of Release:)+ td")?.text()
        val views = infoElement?.selectFirst("td:contains(Views:)+ td")?.text()
        val rating = infoElement?.selectFirst("td:contains(Rating)+ td .rating")?.ownText()

        author = infoElement?.selectFirst("td:contains(Author:)+ td")?.text()

        status = detailsElement?.selectFirst(".anime-genres .status")?.text()
            .orEmpty().let { parseStatus(it) }
        genre = detailsElement?.select(".anime-genres li:not(.status)")
            ?.joinToString { it.text() }
        thumbnail_url = detailsElement?.selectFirst(".anime-image img")?.getImgAttr()
        description = listOfNotNull(
            listOfNotNull(
                alternate?.let { "Alternate Name: $it" },
                year?.let { "Year of Release: $it" },
                views?.let { "Views: $it" },
                rating?.let { "Rating: $it" },
            ).joinToString("\n"),
            document.selectFirst(".detail-desc-content p")?.text(),
        ).joinToString("\n\n")
    }

    private fun parseStatus(status: String) = when {
        status.contains("Ongoing") -> SManga.ONGOING
        status.contains("Completed") -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    override fun chapterListSelector() = "ul li a.ch-name"

    private val dateFormat = SimpleDateFormat("MM/dd/yy", Locale.getDefault())

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

    private class StatusFilter :
        Filter.Select<String>("Status", arrayOf("", "Complete", "On Going"), 0) {
        val stateArray = arrayOf("", "CMP", "ONG")
        fun stateAsQueryString(): String {
            return stateArray[this.state]
        }
    }

    private class Genre(name: String, val gid: String) : Filter.TriState(name)
    private class GenreList(genres: List<Genre>) : Filter.Group<Genre>("Genres", genres) {
        val included: List<String>
            get() = state.filter { it.isIncluded() }.map { it.gid }

        val excluded: List<String>
            get() = state.filter { it.isExcluded() }.map { it.gid }
    }

    override fun getFilterList() = FilterList(
        StatusFilter(),
        GenreList(getGenreList()),
    )

    override fun imageUrlParse(document: Document): String = throw UnsupportedOperationException()

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

    // console.log([...document.querySelectorAll(".genre-check-list li")].map((el) => `Genre("${el.innerText}", "${el.innerText.replaceAll(" ","+")}")`).join(',\n'))
    // on https://azcomix.me/advanced-search
    private fun getGenreList() = listOf(
        Genre("Marvel", "Marvel"),
        Genre("DC Comics", "DC+Comics"),
        Genre("Action", "Action"),
        Genre("Adventure", "Adventure"),
        Genre("Anthology", "Anthology"),
        Genre("Anthropomorphic", "Anthropomorphic"),
        Genre("Biography", "Biography"),
        Genre("Children", "Children"),
        Genre("Comedy", "Comedy"),
        Genre("Crime", "Crime"),
        Genre("Cyborgs", "Cyborgs"),
        Genre("Dark Horse", "Dark+Horse"),
        Genre("Demons", "Demons"),
        Genre("Drama", "Drama"),
        Genre("Fantasy", "Fantasy"),
        Genre("Family", "Family"),
        Genre("Fighting", "Fighting"),
        Genre("Gore", "Gore"),
        Genre("Graphic Novels", "Graphic+Novels"),
        Genre("Historical", "Historical"),
        Genre("Horror", "Horror"),
        Genre("Leading Ladies", "Leading+Ladies"),
        Genre("Literature", "Literature"),
        Genre("Magic", "Magic"),
        Genre("Manga", "Manga"),
        Genre("Martial Arts", "Martial+Arts"),
        Genre("Mature", "Mature"),
        Genre("Mecha", "Mecha"),
        Genre("Military", "Military"),
        Genre("Movie Cinematic Link", "Movie+Cinematic+Link"),
        Genre("Mystery", "Mystery"),
        Genre("Mythology", "Mythology"),
        Genre("Psychological", "Psychological"),
        Genre("Personal", "Personal"),
        Genre("Political", "Political"),
        Genre("Post-Apocalyptic", "Post-Apocalyptic"),
        Genre("Pulp", "Pulp"),
        Genre("Robots", "Robots"),
        Genre("Romance", "Romance"),
        Genre("Sci-Fi", "Sci-Fi"),
        Genre("Slice of Life", "Slice+of+Life"),
        Genre("Science Fiction", "Science+Fiction"),
        Genre("Sports", "Sports"),
        Genre("Spy", "Spy"),
        Genre("Superhero", "Superhero"),
        Genre("Supernatural", "Supernatural"),
        Genre("Suspense", "Suspense"),
        Genre("Thriller", "Thriller"),
        Genre("Tragedy", "Tragedy"),
        Genre("Vampires", "Vampires"),
        Genre("Vertigo", "Vertigo"),
        Genre("Video Games", "Video+Games"),
        Genre("War", "War"),
        Genre("Western", "Western"),
        Genre("Zombies", "Zombies"),
    )

    override fun relatedMangaListSelector() = searchMangaSelector()
}
