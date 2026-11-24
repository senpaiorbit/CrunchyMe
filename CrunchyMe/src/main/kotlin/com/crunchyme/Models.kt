package com.crunchyme

enum class MediaKind { HLS, DASH, FILE }

data class SubtitleTrack(val language: String, val url: String)

/** A playable stream candidate produced by one source, before manifest probing. */
data class Candidate(
    val sourceId: String,
    val sourceName: String,
    val url: String,
    val kind: MediaKind,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val qualityHint: Int = 0,
    val audioLabel: String = "",
    val sizeLabel: String = "",
    val mediaLabel: String = "",
    val inlineManifest: String? = null,
    val subtitles: List<SubtitleTrack> = emptyList(),
    val speedMs: Long = 0L,
)

/** Everything a source needs to resolve one title or episode. */
data class LinkRequest(
    val tmdbId: Int,
    val imdbId: String?,
    val type: String,
    val season: Int,
    val episode: Int,
    val title: String,
    val year: Int?,
    val originalLanguage: String?,
) {
    val isMovie: Boolean get() = type == "movie"
    val hasEpisode: Boolean get() = season > 0 && episode > 0
}
