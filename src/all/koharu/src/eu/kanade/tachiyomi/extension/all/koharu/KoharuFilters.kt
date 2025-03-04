package eu.kanade.tachiyomi.extension.all.koharu

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

fun getFilters(): FilterList {
    return FilterList(
        SortFilter("Sort by", getSortsList),
        CategoryFilter("Category"),
        Filter.Separator(),
        GenreConditionFilter("Include condition", genreConditionIncludeFilterOptions, "i"),
        GenreConditionFilter("Exclude condition", genreConditionExcludeFilterOptions, "e"),
        GenreFilter("Tags", genreList),
        Filter.Separator(),
        Filter.Header("Separate tags with commas (,)"),
        Filter.Header("Prepend with dash (-) to exclude"),
        TextFilter("Artists", "artist"),
        TextFilter("Magazines", "magazine"),
        TextFilter("Publishers", "publisher"),
        TextFilter("Characters", "character"),
        TextFilter("Cosplayers", "cosplayer"),
        TextFilter("Parodies", "parody"),
        TextFilter("Circles", "circle"),
        TextFilter("Male Tags", "male"),
        TextFilter("Female Tags", "female"),
        TextFilter("Tags ( Universal )", "tag"),
        Filter.Header("Filter by pages, for example: (>20)"),
        TextFilter("Pages", "pages"),
    )
}

internal open class TextFilter(name: String, val type: String) : Filter.Text(name)
internal open class SortFilter(name: String, private val vals: List<Pair<String, String>>, state: Int = 0) :
    Filter.Select<String>(name, vals.map { it.first }.toTypedArray(), state) {
    fun getValue() = vals[state].second
}

internal class CategoryFilter(name: String) :
    Filter.Group<CheckBoxFilter>(
        name,
        listOf(
            Pair("Manga", 2),
            Pair("Doujinshi", 4),
            Pair("Illustration", 8),
        ).map { CheckBoxFilter(it.first, it.second, true) },
    )
internal open class CheckBoxFilter(name: String, val value: Int, state: Boolean) : Filter.CheckBox(name, state)

private val getSortsList: List<Pair<String, String>> = listOf(
    Pair("Recently Posted", "4"),
    Pair("Title", "2"),
    Pair("Pages", "3"),
    Pair("Most Viewed", "8"),
    Pair("Most Favorited", "9"),
)

internal class GenreConditionFilter(title: String, options: List<Pair<String, String>>, val param: String) : UriPartFilter(
    title,
    options.toTypedArray(),
)

internal class GenreFilter(title: String, genres: List<Genre>) :
    Filter.Group<GenreTriState>(title, genres.map { GenreTriState(it.name, it.id) })
internal class GenreTriState(name: String, val id: Int) : Filter.TriState(name)

open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>, state: Int = 0) :
    Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray(), state) {
    fun toUriPart() = vals[state].second
}

// https://api.schale.network/books?include=<id>,<id>&i=1&exclude=<id>,<id>&e=1
val genreConditionIncludeFilterOptions: List<Pair<String, String>> =
    listOf(
        "AND" to "",
        "OR" to "1",
    )

val genreConditionExcludeFilterOptions: List<Pair<String, String>> =
    listOf(
        "OR" to "",
        "AND" to "1",
    )
