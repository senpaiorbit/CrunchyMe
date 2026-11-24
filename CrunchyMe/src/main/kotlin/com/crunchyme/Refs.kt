package com.crunchyme

data class TitleRef(
    val tmdbId: Int?,
    val imdbId: String?,
    val type: String,
    val season: Int = -1,
    val episode: Int = -1,
)

/**
 * CrunchyMe identifies titles by TMDB/IMDB id, not by a website URL, so the
 * same catalog entry works for every source.
 */
object Refs {

    private val TMDB_WEB = Regex("""(?i)themoviedb\.org/(movie|tv)/(\d+)""")
    private val IMDB_WEB = Regex("""(?i)imdb\.com/title/(tt\d+)""")
    private val TMDB_EPISODE = Regex("""(?i)themoviedb\.org/tv/\d+/season/(\d+)/episode/(\d+)""")
    private val IMDB_EPISODE = Regex("""(?i)imdb\.com/title/tt\d+/season/(\d+)/episode/(\d+)""")
    private val IMDB = Regex("""(?i)tt\d{7,10}""")

    fun movie(tmdbId: Int, imdbId: String? = null): String =
        "https://www.themoviedb.org/movie/$tmdbId" + imdbSuffix(imdbId)

    fun tv(tmdbId: Int, imdbId: String? = null): String =
        "https://www.themoviedb.org/tv/$tmdbId" + imdbSuffix(imdbId)

    fun episode(tmdbId: Int, imdbId: String?, season: Int, episode: Int): String =
        "https://www.themoviedb.org/tv/$tmdbId/season/$season/episode/$episode" + imdbSuffix(imdbId)

    fun imdb(imdbId: String, type: String, season: Int = -1, episode: Int = -1): String {
        val id = imdbId.trim()
        val base = "https://www.imdb.com/title/$id"
        return if (type == "tv" && season > 0 && episode > 0) {
            "$base/season/$season/episode/$episode?type=tv"
        } else {
            "$base?type=" + if (type == "tv") "tv" else "movie"
        }
    }

    private fun imdbSuffix(imdbId: String?): String =
        imdbId?.trim()?.takeIf { it.startsWith("tt") }?.let { "?imdb=$it" }.orEmpty()

    fun key(ref: TitleRef): String = "${ref.tmdbId ?: 0}|${ref.type}|${ref.season}|${ref.episode}"

    fun parse(url: String?): TitleRef? {
        val value = url?.trim().orEmpty()
        if (value.isEmpty()) return null

        val webMatch = TMDB_WEB.find(value)
        val imdb = query(value, "imdb")?.takeIf { it.startsWith("tt") }
            ?: IMDB_WEB.find(value)?.groupValues?.get(1)
            ?: IMDB.find(value)?.value
        val tmdbId = query(value, "tmdb")?.toIntOrNull()
            ?: webMatch?.groupValues?.get(2)?.toIntOrNull()

        val (season, episode) = episodeOf(value)
        val explicitType = query(value, "type")
        val type = when {
            !explicitType.isNullOrBlank() -> if (explicitType == "movie") "movie" else "tv"
            webMatch?.groupValues?.get(1)?.equals("tv", ignoreCase = true) == true -> "tv"
            value.contains("/tv/", ignoreCase = true) || season > 0 -> "tv"
            else -> "movie"
        }

        if (tmdbId == null && imdb == null) return null
        return TitleRef(tmdbId, imdb, type, season, episode)
    }

    private fun episodeOf(value: String): Pair<Int, Int> {
        TMDB_EPISODE.find(value)?.let {
            return (it.groupValues[1].toIntOrNull() ?: -1) to (it.groupValues[2].toIntOrNull() ?: -1)
        }
        IMDB_EPISODE.find(value)?.let {
            return (it.groupValues[1].toIntOrNull() ?: -1) to (it.groupValues[2].toIntOrNull() ?: -1)
        }
        val season = query(value, "season")?.toIntOrNull() ?: -1
        val episode = query(value, "episode")?.toIntOrNull() ?: -1
        return season to episode
    }

    private fun query(url: String, key: String): String? =
        Regex("""[?&]$key=([^&#]+)""").find(url)?.groupValues?.get(1)
}
