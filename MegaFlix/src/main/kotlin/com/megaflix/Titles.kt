package com.megaflix

import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TorrentSearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import java.text.Normalizer

object Titles {
    private val yearPattern = Regex("""[\(\[]\s*(?:19|20)\d{2}\s*[\)\]]""")
    private val punctuation = Regex("""[^\p{L}\p{N}]+""")

    fun normalize(raw: String): String {
        val folded = Normalizer.normalize(raw, Normalizer.Form.NFKD)
            .lowercase()
            .replace(Regex("""\p{M}+"""), "")
            .replace(yearPattern, " ")
            .replace(Regex("""\s+(?:19|20)\d{2}$"""), "")
            .replace(punctuation, " ")
        return folded.trim().replace(Regex("""\s+"""), " ")
    }

    fun yearOf(item: SearchResponse): Int? = when (item) {
        is MovieSearchResponse -> item.year
        is TvSeriesSearchResponse -> item.year
        is AnimeSearchResponse -> item.year
        else -> null
    }

    fun typeOf(item: SearchResponse): TvType = item.type ?: when (item) {
        is MovieSearchResponse -> TvType.Movie
        is TvSeriesSearchResponse -> TvType.TvSeries
        is AnimeSearchResponse -> TvType.Anime
        is LiveSearchResponse -> TvType.Live
        is TorrentSearchResponse -> TvType.Torrent
        else -> TvType.Movie
    }

    fun key(item: SearchResponse): String =
        normalize(item.name) + "|" + (yearOf(item)?.toString() ?: "") + "|" + typeOf(item).name

    fun displayName(title: String, sourceCount: Int, showSourceCount: Boolean): String =
        if (showSourceCount && sourceCount > 1) "$title · $sourceCount sources" else title

    fun isSameTitle(a: String, b: String): Boolean {
        val left = normalize(a)
        val right = normalize(b)
        if (left.isEmpty() || right.isEmpty()) return false
        if (left == right) return true
        val leftWords = left.split(' ').filter { it.length > 1 }.toSet()
        val rightWords = right.split(' ').filter { it.length > 1 }.toSet()
        if (leftWords.isEmpty() || rightWords.isEmpty()) return false
        val overlap = leftWords.intersect(rightWords).size
        return overlap >= minOf(leftWords.size, rightWords.size) - 1 && overlap >= 2
    }
}
