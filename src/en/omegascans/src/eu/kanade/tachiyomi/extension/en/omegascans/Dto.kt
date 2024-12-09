package eu.kanade.tachiyomi.extension.en.omegascans

import kotlinx.serialization.Serializable

@Serializable
class GitHubResponse(
    val tree: List<Tree>,
)

@Serializable
class Tree(
    val path: String,
)

@Serializable
class CubariChaptersResponse(
    val chapters: Map<String, CubariChapter>,
    val groups: Map<String, String>,
)

@Serializable
class CubariChapter(
    val groups: Map<String, String>,
    val release_date: Map<String, Long>,
)
