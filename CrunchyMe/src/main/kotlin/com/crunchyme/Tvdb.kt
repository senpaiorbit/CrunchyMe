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
 * TheTVDB v4 client used for episode metadata. TMDB remains the catalog and
 * supplies the TVDB id through external_ids; every lookup falls back to TMDB.
 */
object Tvdb {

    private const val API_KEY = "9d89c164-ee5c-484f-b7f8-63a127119870"
    private const val BASE = "https://api4.thetvdb.com/v4"
    private const val LANG = "eng"
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private const val MAX_ENTRIES = 128
    private const val TOKEN_TTL_MS = 12 * 60 * 60 * 1000L

    data class Series(
        val id: Int,
        val name: String?,
        val image: String?,
        val overview: String?,
        val year: Int?,
        val runtime: Int?,
    )

    data class Ep(
        val season: Int,
        val number: Int,
        val name: String?,
        val overview: String?,
        val still: String?,
        val airDate: String?,
        val runtime: Int?,
    )

    private data class Entry(val at: Long, val json: JSONObject)

    private val cache = ConcurrentHashMap<String, Entry>()

    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L

    fun clearCache() {
        cache.clear()
        token = null
        tokenAt = 0L
    }

    suspend fun series(tmdbId: Int, imdbId: String?, tvdbId: Int?): Series? {
        if (!Settings.tvdbEpisodesEnabled()) return null
        if (tvdbId != null && tvdbId > 0) {
            seriesById(tvdbId)?.let { return it }
        }
        if (tmdbId > 0) {
            searchRemote(tmdbId.toString(), "tmdb")?.let { return it }
        }
        val imdb = imdbId?.trim()?.takeIf { it.startsWith("tt") }
        if (imdb != null) {
            searchRemote(imdb, "imdb")?.let { return it }
        }
        return null
    }

    suspend fun episodes(
        tmdbId: Int,
        imdbId: String?,
        tvdbId: Int?,
        seasons: List<Int>,
    ): List<Ep>? {
        if (!Settings.tvdbEpisodesEnabled()) return null
        val series = series(tmdbId, imdbId, tvdbId) ?: return null
        val wanted = seasons.filter { it > 0 }.distinct().ifEmpty { listOf(-1) }
        val gate = Semaphore(4)
        val result = coroutineScope {
            wanted.map { season ->
                async { gate.withPermit { seasonEpisodes(series.id, season) } }
            }.awaitAll().flatten()
        }.filter { it.season > 0 && it.number > 0 }
            .distinctBy { it.season to it.number }
            .sortedWith(compareBy({ it.season }, { it.number }))
        return result.takeIf { it.isNotEmpty() }
    }

    private suspend fun seriesById(id: Int): Series? {
        val key = "series:$id:$LANG"
        val root = cached(key) { get("/series/$id") } ?: return null
        val base = parseSeries(root.optJSONObject("data")) ?: return null
        return translated(base)
    }

    private suspend fun searchRemote(id: String, type: String): Series? {
        val key = "remote:$type:$id"
        val path = "/search/remoteid/${encode(id)}?type=$type"
        val root = cached(key) { get(path) } ?: return null
        val array = root.optJSONArray("data") ?: return null
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val series = item.optJSONObject("series") ?: continue
            parseSeries(series)?.let { return translated(it) }
        }
        return null
    }

    private suspend fun translated(series: Series): Series {
        val key = "translation:${series.id}:$LANG"
        val data = cached(key) { get("/series/${series.id}/translations/$LANG") }
            ?.optJSONObject("data") ?: return series
        return series.copy(
            name = data.optString("name").trim().ifBlank { series.name },
            overview = data.optString("overview").trim().ifBlank { series.overview },
        )
    }

    private suspend fun seasonEpisodes(seriesId: Int, season: Int): List<Ep> {
        val key = "episodes:$seriesId:$season:$LANG"
        val path = buildString {
            append("/series/$seriesId/episodes/default/$LANG")
            if (season > 0) append("?season=$season")
            append(if (season > 0) "&page=0" else "?page=0")
        }
        val root = cached(key) { get(path) } ?: return emptyList()
        val data = root.optJSONObject("data") ?: return emptyList()
        val array = data.optJSONArray("episodes") ?: return emptyList()
        val out = ArrayList<Ep>(array.length())
        for (index in 0 until array.length()) {
            val ep = array.optJSONObject(index) ?: continue
            val number = ep.optInt("number", 0)
            val seasonNumber = ep.optInt("seasonNumber", season)
            if (number <= 0 || seasonNumber <= 0) continue
            out += Ep(
                season = seasonNumber,
                number = number,
                name = ep.optString("name").trim().ifBlank { null },
                overview = ep.optString("overview").trim().ifBlank { null },
                still = ep.optString("image").takeIf { it.startsWith("http") },
                airDate = ep.optString("aired").trim().takeIf { it.length >= 10 },
                runtime = ep.optInt("runtime", 0).takeIf { it > 0 },
            )
        }
        return out
    }

    private fun parseSeries(json: JSONObject?): Series? {
        if (json == null) return null
        val id = json.optInt("id", 0)
        if (id <= 0) return null
        val firstAired = json.optString("firstAired").trim()
        return Series(
            id = id,
            name = json.optString("name").trim().ifBlank { null },
            image = json.optString("image").takeIf { it.startsWith("http") },
            overview = json.optString("overview").trim().ifBlank { null },
            year = firstAired.take(4).toIntOrNull(),
            runtime = json.optInt("averageRuntime", 0).takeIf { it > 0 },
        )
    }

    private suspend fun cached(key: String, block: suspend () -> JSONObject?): JSONObject? {
        cache[key]?.let { entry ->
            if (System.currentTimeMillis() - entry.at < TTL_MS) return entry.json
        }
        val json = block() ?: return null
        cache[key] = Entry(System.currentTimeMillis(), json)
        if (cache.size > MAX_ENTRIES) {
            cache.entries.minByOrNull { it.value.at }?.let { cache.remove(it.key) }
        }
        return json
    }

    private suspend fun get(path: String): JSONObject? {
        val auth = login() ?: return null
        val body = Http.text(
            BASE + path,
            mapOf(
                "Authorization" to "Bearer $auth",
                "Accept" to "application/json",
                "Accept-Language" to "en",
            ),
            20,
        )
        if (body == null) {
            token = null
            val retry = login() ?: return null
            val retryBody = Http.text(
                BASE + path,
                mapOf(
                    "Authorization" to "Bearer $retry",
                    "Accept" to "application/json",
                    "Accept-Language" to "en",
                ),
                20,
            ) ?: return null
            return runCatching { JSONObject(retryBody) }.getOrNull()
        }
        return runCatching { JSONObject(body) }.getOrNull()
    }

    private suspend fun login(): String? {
        val current = token
        if (!current.isNullOrBlank() && System.currentTimeMillis() - tokenAt < TOKEN_TTL_MS) {
            return current
        }
        val body = Http.post(
            "$BASE/login",
            "{\"apikey\":\"$API_KEY\"}",
            mapOf("Accept" to "application/json"),
            "application/json",
            15,
        ) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val fresh = json.optJSONObject("data")?.optString("token").orEmpty()
        if (fresh.isBlank()) return null
        token = fresh
        tokenAt = System.currentTimeMillis()
        return fresh
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
