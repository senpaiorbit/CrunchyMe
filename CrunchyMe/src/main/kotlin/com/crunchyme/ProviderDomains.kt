package com.crunchyme

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Indian file sites rotate domains constantly. The public CSX URL list is the
 * fastest signal, while the branded landing pages are the fallback when the
 * list has not caught up yet.
 */
object ProviderDomains {

    private const val URLS = "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"
    private const val TTL_MS = 6 * 60 * 60 * 1000L

    private val fallback = mapOf(
        "bollyflix" to "https://new1.bollyflix.tours",
        "multimovies" to "https://m1.multimovies.courses",
        "vegamovies" to "https://vegamovies.latino",
        "rogmovies" to "https://rogmovies.beauty",
        "moviesdrive" to "https://new4.moviesdrive.cyou",
        "moviesmod" to "https://moviesmod.fi",
        "hindmoviez" to "https://hindmovie.vin",
        "hdhub4u" to "https://new16.hdhub4u.cl",
        "4khdhub" to "https://4khdhub.one",
    )

    private val landing = mapOf(
        "bollyflix" to "https://bollyflix.deal/",
        "multimovies" to "https://multimovies.wtf/",
    )

    private val hostPattern = mapOf(
        "bollyflix" to Regex("""(?i)^https?://(?:[a-z0-9-]+\.)?bollyflix\.[a-z.]+$"""),
        "multimovies" to Regex("""(?i)^https?://(?:[a-z0-9-]+\.)?multimovies\.[a-z.]+$"""),
    )

    private data class Entry(val base: String, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val mutex = Mutex()
    @Volatile private var listAt = 0L
    @Volatile private var list: JSONObject? = null

    fun fallback(key: String?): String = key?.let { fallback[it] } ?: ""

    suspend fun base(key: String): String {
        cache[key]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.base }
        return mutex.withLock {
            cache[key]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return@withLock it.base }
            val candidates = listOfNotNull(fromList(key), fromLanding(key), fallback[key])
                .map { it.trimEnd('/') }
                .distinct()
            val resolved = candidates.firstOrNull { usable(key, it) } ?: return@withLock ""
            cache[key] = Entry(resolved, System.currentTimeMillis())
            resolved
        }
    }

    private suspend fun usable(key: String, base: String): Boolean {
        if (key != "vegamovies" && key != "rogmovies") return true
        val body = Http.text(
            "$base/ts-search.php?q=test&page=1",
            ProviderSupport.headers("$base/"),
            8,
        ) ?: return false
        return body.trimStart().startsWith("{")
    }

    suspend fun apply(spec: ServerSpec): ServerSpec {
        val key = spec.domainKey ?: return spec
        val base = base(key).ifBlank { return spec }
        fun fill(value: String): String = value
            .replace("{base}", base)
            .replace("https://vegamovies.latino", base)
            .replace("https://rogmovies.beauty", base)
            .replace("https://new4.moviesdrive.cyou", base)
            .replace("https://new1.bollyflix.tours", base)
            .replace("https://m1.multimovies.courses", base)
        return spec.copy(
            movieUrl = fill(spec.movieUrl),
            tvUrl = fill(spec.tvUrl),
            referer = spec.referer?.let(::fill),
        )
    }

    private suspend fun fromList(key: String): String? {
        val now = System.currentTimeMillis()
        val json = list?.takeIf { now - listAt < TTL_MS } ?: run {
            val body = Http.text(URLS, mapOf("User-Agent" to Http.UA), 12) ?: return null
            runCatching { JSONObject(body) }.getOrNull()?.also {
                list = it
                listAt = now
            } ?: return null
        }
        return json.optString(key).trim().takeIf { it.startsWith("http") }
    }

    private suspend fun fromLanding(key: String): String? {
        val url = landing[key] ?: return null
        val html = Http.text(url, ProviderSupport.headers(url), 10) ?: return null
        val pattern = hostPattern[key] ?: return null
        return Regex("""href\s*=\s*["']([^"']+)["']""")
            .findAll(html)
            .map { it.groupValues[1].replace("\\/", "/") }
            .map { it.trimEnd('/') }
            .filter { pattern.matches(it) }
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
    }
}
