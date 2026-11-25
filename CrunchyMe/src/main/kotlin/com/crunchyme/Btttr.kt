package com.crunchyme

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * BetterPosters (btttr.cc) exposes a Stremio meta endpoint that accepts TMDB ids.
 * The poster endpoint is keyed by IMDb, so the meta response is the useful part
 * for a TMDB-first catalog.
 */
object Btttr {

    private const val BASE = "https://btttr.cc"
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private const val MAX_ENTRIES = 256

    data class Meta(
        val poster: String?,
        val logo: String?,
        val background: String?,
        val trailers: List<String> = emptyList(),
    )

    private data class Entry(val at: Long, val meta: Meta)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun clearCache() = cache.clear()

    fun posterByImdb(imdbId: String?, type: String): String? {
        if (!Settings.btttrPostersEnabled()) return null
        val id = imdbId?.trim()?.takeIf { it.startsWith("tt") } ?: return null
        val kind = if (type == "movie") "movie" else "series"
        return "$BASE/poster/$kind/$id/auto~gr.png?nocache=1"
    }

    suspend fun meta(tmdbId: Int, type: String, timeoutSec: Long = 3): Meta? {
        if (!Settings.btttrPostersEnabled() || tmdbId <= 0) return null
        val kind = if (type == "movie") "movie" else "series"
        val key = "$kind:$tmdbId"
        cache[key]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.meta }
        val url = "$BASE/meta/$kind/tmdb:$tmdbId.json"
        val body = Http.text(url, mapOf("User-Agent" to Http.UA), timeoutSec) ?: return null
        val meta = parse(body) ?: return null
        cache[key] = Entry(System.currentTimeMillis(), meta)
        if (cache.size > MAX_ENTRIES) {
            cache.entries.minByOrNull { it.value.at }?.let { cache.remove(it.key) }
        }
        return meta
    }

    suspend fun metaByImdb(imdbId: String?, type: String): Meta? {
        if (!Settings.btttrPostersEnabled()) return null
        val id = imdbId?.trim()?.takeIf { it.startsWith("tt") } ?: return null
        val kind = if (type == "movie") "movie" else "series"
        val key = "$kind:$id"
        cache[key]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.meta }
        val url = "$BASE/meta/$kind/$id.json"
        val body = Http.text(url, mapOf("User-Agent" to Http.UA), 3) ?: return null
        val meta = parse(body) ?: return null
        cache[key] = Entry(System.currentTimeMillis(), meta)
        return meta
    }

    suspend fun enrich(items: List<Tmdb.Item>): Map<Int, Meta> {
        if (!Settings.btttrPostersEnabled() || items.isEmpty()) return emptyMap()
        val gate = Semaphore(8)
        return coroutineScope {
            items.map { item ->
                async {
                    val meta = gate.withPermit {
                        withTimeoutOrNull(2_500) {
                            runCatching { meta(item.id, item.type, 2) }.getOrNull()
                        }
                    }
                    item.id to meta
                }
            }.awaitAll().mapNotNull { (id, meta) -> meta?.let { id to it } }.toMap()
        }
    }

    private fun parse(body: String): Meta? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val meta = root.optJSONObject("meta") ?: return null
        val trailers = LinkedHashSet<String>()
        meta.optJSONArray("trailerStreams")?.let { array ->
            for (index in 0 until array.length()) {
                val id = array.optJSONObject(index)?.optString("ytId").orEmpty().trim()
                if (id.isNotBlank()) trailers += "https://www.youtube.com/watch?v=$id"
            }
        }
        meta.optJSONArray("trailers")?.let { array ->
            for (index in 0 until array.length()) {
                val id = array.optJSONObject(index)?.optString("source").orEmpty().trim()
                if (id.isNotBlank()) trailers += "https://www.youtube.com/watch?v=$id"
            }
        }
        return Meta(
            poster = meta.optString("poster").takeIf { it.startsWith("http") },
            logo = meta.optString("logo").takeIf { it.startsWith("http") },
            background = meta.optString("background").takeIf { it.startsWith("http") },
            trailers = trailers.toList(),
        )
    }
}
