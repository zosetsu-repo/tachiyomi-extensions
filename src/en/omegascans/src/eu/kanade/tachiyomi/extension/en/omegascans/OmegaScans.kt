package eu.kanade.tachiyomi.extension.en.omegascans

import android.util.Base64
import eu.kanade.tachiyomi.multisrc.heancms.HeanCms
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.await
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
            .filter { it.endsWith(".json") && it !in listOf("gaylorddd!.json", "test.json") }
            .associateBy { cubari ->
                cubari
                    .substringBeforeLast(".json")
                    .filter { it.isLetterOrDigit() || it == ' ' }
                    .lowercase()
            }
    }

    private fun getClosest(title: String): String? {
        val cleanedTitle = title.filter { it.isLetterOrDigit() || it == ' ' }.lowercase()

        var jc = 0.0
        var res: String? = null

        cubariList.forEach { (cleanedCubari, cubari) ->
            val jaccard = jaccardSimilarity(cleanedCubari, cleanedTitle)
            if (jaccard >= 0.6 && jaccard > jc) {
                jc = jaccard
                res = cubari
            }
        }

        return res
    }

    private fun jaccardSimilarity(s1: String, s2: String): Double {
        val set1 = s1.split(" ").toSet()
        val set2 = s2.split(" ").toSet()
        val intersection = set1.intersect(set2).size.toDouble()
        val union = set1.union(set2).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = runBlocking {
        val primaryChaptersDeferred = async {
            client.newCall(chapterListRequest(manga)).await()
                .use(::chapterListParse)
        }

        val cubariChaptersDeferred = async {
            val jsonFile = getClosest(manga.title)
                ?: return@async null

            val cubariId = Base64.encodeToString(
                "raw/Laicht/images/master/$jsonFile".toByteArray(),
                Base64.DEFAULT,
            )

            val cubariUrl = "https://cubari.moe/read/api/gist/series/$cubariId/"

            client.newCall(GET(cubariUrl, cubariHeaders))
                .await()
                .parseAs<CubariChaptersResponse>()
        }

        val chapters = primaryChaptersDeferred.await().toMutableList()
        val cubariChapters = cubariChaptersDeferred.await()
            ?: return@runBlocking Observable.just(chapters)

        val seenChapters = HashSet<Float>()
        seenChapters.addAll(chapters.map { it.chapter_number })
        cubariChapters.chapters.toSortedMap().entries.forEach { (num, chap) ->
            val number = num.toFloat()
            if (number !in seenChapters) {
                val chapter = chap.groups.map { (groupNo, chapUrl) ->
                    SChapter.create().apply {
                        url = "https://cubari.moe" + chapUrl
                            .replace("/proxy/", "/read/")
                            .removeSuffix("/")
                            .plus("/")
                        name = "Chapter $num"
                        chapter_number = number
                        scanlator = "Early Access"
                        date_upload = chap.release_date[groupNo]?.times(1000) ?: 0L
                    }
                }

                seenChapters.add(number)
                chapters.addAll(0, chapter)
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

    override fun getChapterUrl(chapter: SChapter): String {
        return if (chapter.url.startsWith("https://cubari.moe")) {
            return chapter.url
                .replace("/api/", "/")
                .replace("/chapter/", "/")
        } else {
            super.getChapterUrl(chapter)
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

                    Page(0, imageUrl = page)
                }
            }
    }

    override fun imageRequest(page: Page): Request {
        return GET("https://x.0ms.dev/q70/" + page.imageUrl!!, cubariHeaders)
    }
}

private val CHAP_NUM_REGEX = Regex("""\d+(\.\d+)?""")
