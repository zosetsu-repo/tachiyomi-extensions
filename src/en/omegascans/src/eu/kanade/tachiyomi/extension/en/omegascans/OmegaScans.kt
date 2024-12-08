package eu.kanade.tachiyomi.extension.en.omegascans

import android.util.Base64
import eu.kanade.tachiyomi.multisrc.heancms.HeanCms
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okhttp3.Response
import rx.Observable

class OmegaScans : HeanCms("Omega Scans", "https://omegascans.org", "en") {

    // Site changed from MangaThemesia to HeanCms.
    override val versionId = 2

    override val useNewChapterEndpoint = true
    override val useNewQueryEndpoint = true
    override val enableLogin = true

    private val cubariHeaders by lazy {
        headersBuilder()
            .removeAll("Referer")
            .removeAll("Origin")
            .build()
    }

    private val cubariList by lazy {
        val url = "https://api.github.com/repos/Laicht/images/git/trees/master?recursive=1"
        client.newCall(GET(url, cubariHeaders))
            .execute()
            .parseAs<GitHubResponse>()
            .tree.map { it.path }
            .filter { it.endsWith(".json") }
    }

    private fun getClosest(title: String): String =
        cubariList.minByOrNull {
            editDistance(it.substringBeforeLast(".json").lowercase(), title.lowercase())
        }!!

    private fun editDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        var prev: Int
        val curr = IntArray(n + 1)

        for (j in 0..n) curr[j] = j

        for (i in 1..m) {
            prev = curr[0]
            curr[0] = i
            for (j in 1..n) {
                val temp = curr[j]
                if (s1[i - 1] == s2[j - 1]) {
                    curr[j] = prev
                } else {
                    curr[j] = 1 + minOf(curr[j - 1], prev, curr[j])
                }
                prev = temp
            }
        }

        return curr[n]
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = runBlocking {
        val primaryChaptersDeferred = async {
            client.newCall(chapterListRequest(manga)).execute()
                .use(::chapterListParse)
        }

        val cubariChaptersDeferred = async {
            val jsonFile = getClosest(manga.title)

            val cubariId = Base64.encodeToString(
                "raw/Laicht/images/master/$jsonFile".toByteArray(),
                Base64.DEFAULT,
            )

            val cubariUrl = "https://cubari.moe/read/api/gist/series/$cubariId/"

            client.newCall(GET(cubariUrl, cubariHeaders))
                .execute()
                .parseAs<CubariChaptersResponse>()
        }

        val chapters = primaryChaptersDeferred.await().toMutableList()
        val cubariChapters = cubariChaptersDeferred.await()

        val seenChapters = HashSet<Float>()
        seenChapters.addAll(chapters.map { it.chapter_number })
        cubariChapters.chapters.toSortedMap().entries.forEach { (num, chap) ->
            val number = num.toFloat()
            if (number !in seenChapters) {
                val chapter = SChapter.create().apply {
                    url = "https://cubari.moe" + chap.groups["0"]!!
                        .replace("/proxy/", "/read/")
                        .removeSuffix("/")
                        .plus("/")
                    name = "Chapter $num"
                    chapter_number = number
                    scanlator = "Early Access"
                    date_upload = chap.release_date["0"]!! * 1000
                }

                seenChapters.add(number)
                chapters.add(0, chapter)
            }
        }

        Observable.just(chapters)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        return super.chapterListParse(response).map { chapter ->
            chapter.apply {
                chapter_number = CHAP_NUM_REGEX.find(name)?.value?.toFloatOrNull() ?: -1f
            }
        }
    }

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> {
        return if (chapter.url.startsWith("https://cubari.moe")) {
            getCubariPageList(chapter)
        } else {
            super.fetchPageList(chapter)
        }
    }

    private fun getCubariPageList(chapter: SChapter): Observable<List<Page>> {
        return client.newCall(GET(chapter.url, cubariHeaders))
            .asObservableSuccess()
            .map { response ->
                response.parseAs<List<JsonElement>>().map {
                    val page = if (it is JsonObject) {
                        it.jsonObject["src"]!!.jsonPrimitive.content
                    } else {
                        it.jsonPrimitive.content
                    }

                    Page(0, imageUrl = "$page#cubari")
                }
            }
    }

    override fun imageRequest(page: Page): Request {
        return if (page.imageUrl!!.endsWith("#cubari")) {
            GET(page.imageUrl!!, cubariHeaders)
        } else {
            super.imageRequest(page)
        }
    }
}

private val CHAP_NUM_REGEX = Regex("""\d+(\.\d+)?""")
