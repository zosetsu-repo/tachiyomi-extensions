package eu.kanade.tachiyomi.extension.en.omegascans

import android.util.Base64
import eu.kanade.tachiyomi.multisrc.heancms.HeanCms
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.network.interceptor.rateLimitHost
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import rx.Observable

class OmegaScans : HeanCms("Omega Scans", "https://omegascans.org", "en") {

    override val client = super.client.newBuilder()
        .rateLimitHost(apiUrl.toHttpUrl(), 1)
        .build()

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
            .associateBy { it.substringBeforeLast(".json").lowercase() }
    }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        val chapters = client.newCall(chapterListRequest(manga)).execute()
            .use(::chapterListParse)
            .toMutableList()

        val seenChapters = HashSet<Float>()
        seenChapters.addAll(chapters.map { it.chapter_number })

        val cubariChapters = run {
            val jsonFile = cubariList[manga.title.lowercase()]
                ?: return Observable.just(chapters)

            val cubariId = Base64.encodeToString(
                "raw/Laicht/images/master/$jsonFile".toByteArray(),
                Base64.DEFAULT,
            )

            val cubariUrl = "https://cubari.moe/read/api/gist/series/$cubariId/"

            client.newCall(GET(cubariUrl, cubariHeaders))
                .execute()
                .parseAs<CubariChaptersResponse>()
        }

        cubariChapters.chapters.entries.forEach { (num, chap) ->
            val number = num.toFloat()
            if (number !in seenChapters) {
                val chapter = SChapter.create().apply {
                    url = "https://cubari.moe" + chap.groups["0"]!!
                        .replace("/proxy/", "/read/")
                        .removeSuffix("/")
                        .plus("/")
                    name = "Chapter $num"
                    scanlator = "Early Access"
                    date_upload = chap.release_date["0"]!! * 1000
                }

                seenChapters.add(number)
                chapters.add(0, chapter)
            }
        }

        return Observable.just(chapters)
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
                response.parseAs<List<String>>().map {
                    Page(0, imageUrl = it)
                }
            }
    }
}

private val CHAP_NUM_REGEX = Regex("""\d+.?\d+$""")
