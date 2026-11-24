package com.crunchyme

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stremio-compatible addons (Nuvio uses the same protocol). Addons are user supplied,
 * so this source is empty until one is added in settings.
 */
object StremioSource {

    const val ID = "stremio"
    const val NAME = "Stremio/Nuvio"

    private const val MANIFEST_TTL_MS = 6 * 60 * 60 * 1000L
    private const val MAX_ADDONS = 6
    private const val ADDON_TIMEOUT_MS = 12_000L

    data class Manifest(
        val baseUrl: String,
        val name: String,
        val types: Set<String>,
        val resources: Set<String>,
        val idPrefixes: Set<String>?,
    )

    private data class Cached(val at: Long, val manifest: Manifest?)

    private val manifests = ConcurrentHashMap<String, Cached>()

    fun normalize(raw: String?): String? {
        var value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.startsWith("stremio://", ignoreCase = true)) {
            value = "https://" + value.substring("stremio://".length)
        }
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) {
            value = "https://$value"
        }
        value = value.substringBefore("/manifest.json").trimEnd('/')
        val host = value.substringAfter("://").substringBefore('/')
        return value.takeIf { host.contains('.') }
    }

    fun manifestUrl(base: String): String = "$base/manifest.json"

    fun streamUrl(base: String, type: String, imdbId: String, season: Int, episode: Int): String {
        val stremioType = if (type == "tv") "series" else "movie"
        val id = if (type == "tv") "$imdbId:$season:$episode" else imdbId
        return "$base/stream/$stremioType/$id.json"
    }

    fun parseManifest(baseUrl: String, body: String?): Manifest? {
        if (body.isNullOrBlank()) return null
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val name = root.optString("name").trim().ifBlank { baseUrl.substringAfter("://").substringBefore('/') }
        val types = root.optJSONArray("types").stringSet()
        val resources = LinkedHashSet<String>()
        root.optJSONArray("resources")?.let { array ->
            for (index in 0 until array.length()) {
                when (val value = array.opt(index)) {
                    is String -> resources += value
                    is JSONObject -> value.optString("name").takeIf { it.isNotBlank() }?.let { resources += it }
                }
            }
        }
        val idPrefixes = root.optJSONArray("idPrefixes")?.stringSet()?.takeIf { it.isNotEmpty() }
        return Manifest(baseUrl, name, types, resources, idPrefixes)
    }

    fun supports(manifest: Manifest, type: String): Boolean {
        val stremioType = if (type == "tv") "series" else "movie"
        if (!manifest.resources.contains("stream")) return false
        if (manifest.types.isNotEmpty() && !manifest.types.contains(stremioType)) return false
        val prefixes = manifest.idPrefixes ?: return true
        return prefixes.contains("tt")
    }

    fun parseStreams(body: String?, addonName: String): List<Candidate> {
        if (body.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val streams = root.optJSONArray("streams") ?: return emptyList()
        val out = LinkedHashMap<String, Candidate>()

        for (index in 0 until streams.length()) {
            val stream = streams.optJSONObject(index) ?: continue
            val url = stream.optString("url").trim()
            if (!url.startsWith("http")) continue
            if (stream.has("infoHash") || stream.has("externalUrl") || stream.has("ytId")) continue

            val hints = stream.optJSONObject("behaviorHints")
            val name = stream.optString("name").trim()
            val title = stream.optString("title").trim()
            val description = stream.optString("description").trim()
            val filename = hints?.optString("filename").orEmpty().trim()
            val combined = listOf(filename, name, title, description)
                .filter { it.isNotBlank() }
                .joinToString(" ")

            val quality = Naming.qualityHeight(combined)
            val language = Naming.displayLabel(combined)
            val size = Naming.sizeLabel(combined).ifBlank {
                humanSize(hints?.optLong("videoSize", 0L) ?: 0L)
            }
            val headers = headersOf(stream, hints)

            val kind = when {
                url.contains(".m3u8", ignoreCase = true) -> MediaKind.HLS
                url.contains(".mpd", ignoreCase = true) -> MediaKind.DASH
                else -> MediaKind.FILE
            }

            val candidate = Candidate(
                sourceId = "$ID:$addonName",
                sourceName = addonName,
                url = url,
                kind = kind,
                referer = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value,
                headers = headers,
                qualityHint = quality,
                audioLabel = language,
                sizeLabel = size,
            )
            out.putIfAbsent(url, candidate)
        }
        return out.values.toList()
    }

    suspend fun resolve(request: LinkRequest, addons: List<String>): List<Candidate> {
        val imdbId = request.imdbId?.takeIf { it.startsWith("tt") } ?: return emptyList()
        val bases = addons.mapNotNull { normalize(it) }.distinct().take(MAX_ADDONS)
        if (bases.isEmpty()) return emptyList()

        return coroutineScope {
            bases.map { base ->
                async {
                    withTimeoutOrNull(ADDON_TIMEOUT_MS) {
                        runCatching { resolveAddon(base, request, imdbId) }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun resolveAddon(
        base: String,
        request: LinkRequest,
        imdbId: String,
    ): List<Candidate> {
        val manifest = manifestOf(base) ?: return emptyList()
        if (!supports(manifest, request.type)) return emptyList()
        val body = Http.text(
            streamUrl(base, request.type, imdbId, request.season, request.episode),
            mapOf("Accept" to "application/json"),
            12,
        ) ?: return emptyList()
        return parseStreams(body, manifest.name)
    }

    private suspend fun manifestOf(base: String): Manifest? {
        val cached = manifests[base]
        if (cached != null && System.currentTimeMillis() - cached.at < MANIFEST_TTL_MS) {
            return cached.manifest
        }
        val body = Http.text(manifestUrl(base), mapOf("Accept" to "application/json"), 12)
        val manifest = parseManifest(base, body)
        if (manifest != null) {
            manifests[base] = Cached(System.currentTimeMillis(), manifest)
        } else {
            manifests.remove(base)
        }
        return manifest
    }

    fun clearCache() = manifests.clear()

    private fun headersOf(stream: JSONObject, hints: JSONObject?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val requestHeaders = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")
        requestHeaders?.keys()?.forEach { key ->
            requestHeaders.optString(key).trim().takeIf { it.isNotEmpty() }?.let { out[key] = it }
        }
        stream.optJSONObject("headers")?.keys()?.forEach { key ->
            stream.optJSONObject("headers")?.optString(key)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { out[key] = it }
        }
        return out
    }

    private fun JSONArray?.stringSet(): Set<String> {
        if (this == null) return emptySet()
        val out = LinkedHashSet<String>()
        for (index in 0 until length()) {
            optString(index).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }

    private fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1_073_741_824 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1_048_576.0)
        else -> ""
    }
}
