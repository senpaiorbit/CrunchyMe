package com.crunchyme

import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/**
 * OMDb is the IMDb-shaped catalog fallback. It supplies search results, title metadata and
 * season episode lists, so the extension still works without a TMDB key.
 */
object Omdb {

    const val DEFAULT_KEY = "45caeecd"
    private const val BASE = "https://www.omdbapi.com/"
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val CACHE_MAX = 160

    @Volatile
    var keyProvider: () -> String? = { Settings.omdbKey() }

    data class Hit(
        val imdbId: String,
        val title: String,
        val year: Int?,
        val type: String,
        val poster: String?,
    )

    data class Detail(
        val imdbId: String,
        val title: String,
        val type: String,
        val year: Int?,
        val poster: String?,
        val runtime: Int?,
        val score: Double?,
        val genres: List<String>,
        val overview: String?,
        val actors: List<String>,
        val totalSeasons: Int,
        val released: String?,
    )

    data class Ep(
        val season: Int,
        val number: Int,
        val name: String?,
        val airDate: String?,
        val rating: Double?,
        val imdbId: String?,
    )

    private data class Entry(val at: Long, val json: JSONObject)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun key(): String = keyProvider()?.trim().orEmpty().ifBlank { DEFAULT_KEY }

    fun hasKey(): Boolean = key().isNotBlank()

    fun clearCache() = cache.clear()

    suspend fun testKey(key: String): Boolean {
        val value = key.trim()
        if (value.isEmpty()) return false
        val body = Http.text(url("i=tt3896198", value), emptyMap(), 15) ?: return false
        return runCatching { JSONObject(body).optString("Response") == "True" }.getOrDefault(false)
    }

    suspend fun search(query: String): List<Hit> {
        val text = query.trim()
        if (text.isEmpty()) return emptyList()
        val json = get("s=${enc(text)}") ?: return emptyList()
        val array = json.optJSONArray("Search") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val id = obj.optString("imdbID").trim()
            val title = obj.optString("Title").trim()
            if (!id.startsWith("tt") || title.isBlank()) return@mapNotNull null
            val type = when (obj.optString("Type").lowercase()) {
                "series" -> "tv"
                "movie" -> "movie"
                else -> return@mapNotNull null
            }
            Hit(
                imdbId = id,
                title = title,
                year = obj.optString("Year").take(4).toIntOrNull(),
                type = type,
                poster = obj.optString("Poster").takeIf { it.startsWith("http") },
            )
        }
    }

    suspend fun detail(imdbId: String): Detail? {
        val id = imdbId.trim()
        if (!id.startsWith("tt")) return null
        val json = get("i=${enc(id)}&plot=full") ?: return null
        if (json.optString("Response") != "True") return null
        val title = json.optString("Title").trim()
        if (title.isBlank()) return null
        val type = if (json.optString("Type").equals("series", true)) "tv" else "movie"
        return Detail(
            imdbId = json.optString("imdbID").trim().ifBlank { id },
            title = title,
            type = type,
            year = json.optString("Year").take(4).toIntOrNull(),
            poster = json.optString("Poster").takeIf { it.startsWith("http") },
            runtime = Regex("""\d+""").find(json.optString("Runtime"))?.value?.toIntOrNull(),
            score = json.optString("imdbRating").toDoubleOrNull(),
            genres = json.optString("Genre").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            overview = json.optString("Plot").trim().takeIf { it.isNotBlank() && it != "N/A" },
            actors = json.optString("Actors").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            totalSeasons = json.optString("totalSeasons").toIntOrNull()?.coerceAtLeast(0) ?: 0,
            released = json.optString("Released").trim().takeIf { it.length >= 10 },
        )
    }

    suspend fun episodes(imdbId: String): List<Ep> {
        val detail = detail(imdbId) ?: return emptyList()
        if (detail.type != "tv") return emptyList()
        val seasons = if (detail.totalSeasons > 0) {
            (1..detail.totalSeasons.coerceAtMost(40)).toList()
        } else {
            listOf(1)
        }
        return coroutineScope {
            val gate = Semaphore(4)
            seasons.map { season ->
                async { gate.withPermit { seasonEpisodes(imdbId, season) } }
            }.awaitAll()
                .flatten()
                .sortedWith(compareBy({ it.season }, { it.number }))
        }
    }

    suspend fun seasonEpisodes(imdbId: String, season: Int): List<Ep> {
        if (season <= 0) return emptyList()
        val json = get("i=${enc(imdbId)}&Season=$season") ?: return emptyList()
        val array = json.optJSONArray("Episodes") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val number = obj.optString("Episode").toIntOrNull() ?: return@mapNotNull null
            if (number <= 0) return@mapNotNull null
            Ep(
                season = season,
                number = number,
                name = obj.optString("Title").trim().takeIf { it.isNotBlank() && it != "N/A" },
                airDate = obj.optString("Released").trim().takeIf { it.length >= 10 },
                rating = obj.optString("imdbRating").toDoubleOrNull(),
                imdbId = obj.optString("imdbID").trim().takeIf { it.startsWith("tt") },
            )
        }
    }

    private suspend fun get(query: String): JSONObject? {
        val url = url(query)
        cache[url]?.let { entry ->
            if (System.currentTimeMillis() - entry.at < CACHE_TTL_MS) return entry.json
        }
        val body = Http.text(url, emptyMap(), 15) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optString("Response") == "False") return null
        cache[url] = Entry(System.currentTimeMillis(), json)
        if (cache.size > CACHE_MAX) {
            cache.entries.minByOrNull { it.value.at }?.let { cache.remove(it.key) }
        }
        return json
    }

    private fun url(query: String, key: String = key()): String =
        "$BASE?$query&apikey=${enc(key)}"

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
