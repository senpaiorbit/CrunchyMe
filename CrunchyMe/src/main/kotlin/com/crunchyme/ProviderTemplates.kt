package com.crunchyme

import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * A provider the user can add from settings, without writing code.
 *
 * URL templates accept {tmdb}, {imdb}, {type}, {season}, {episode}, {title} and {year}.
 * `json = true` means the URL returns JSON; otherwise it is treated as an embed page.
 */
data class ProviderTemplate(
    val name: String,
    val movieUrl: String,
    val tvUrl: String = "",
    val referer: String? = null,
    val json: Boolean = false,
    val enabled: Boolean = true,
    val headers: Map<String, String> = emptyMap(),
)

object ProviderTemplates {

    fun encode(list: List<ProviderTemplate>): String {
        val array = JSONArray()
        for (item in list) {
            val headers = JSONObject()
            item.headers.forEach { (key, value) -> headers.put(key, value) }
            array.put(
                JSONObject()
                    .put("n", item.name)
                    .put("m", item.movieUrl)
                    .put("t", item.tvUrl)
                    .put("r", item.referer ?: "")
                    .put("j", item.json)
                    .put("e", item.enabled)
                    .put("h", headers),
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<ProviderTemplate> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()
        return if (text.startsWith("[")) decodeJson(text) else decodeLines(text)
    }

    private fun decodeJson(text: String): List<ProviderTemplate> = try {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            val name = obj.optString("n").trim().ifBlank { obj.optString("name").trim() }
            val movie = obj.optString("m").trim().ifBlank { obj.optString("movieUrl").trim() }
            if (name.isEmpty() || movie.isEmpty()) return@mapNotNull null
            val headerObject = obj.optJSONObject("h")
            val headers = buildMap {
                headerObject?.keys()?.forEach { key ->
                    headerObject.optString(key).trim().takeIf { it.isNotEmpty() }?.let { put(key, it) }
                }
            }
            ProviderTemplate(
                name = name,
                movieUrl = movie,
                tvUrl = obj.optString("t").trim().ifBlank { obj.optString("tvUrl").trim() },
                referer = obj.optString("r").trim().ifBlank { obj.optString("referer").trim() }.ifBlank { null },
                json = obj.optBoolean("j", obj.optBoolean("json", false)),
                enabled = obj.optBoolean("e", true),
                headers = headers,
            )
        }
    } catch (t: Throwable) {
        emptyList()
    }

    /** "Name | movie template | tv template | referer | json" one per line. */
    private fun decodeLines(text: String): List<ProviderTemplate> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split('|').map { it.trim() }
            val name = parts.getOrNull(0).orEmpty()
            val movie = parts.getOrNull(1).orEmpty()
            if (name.isEmpty() || movie.isEmpty()) return@mapNotNull null
            ProviderTemplate(
                name = name,
                movieUrl = movie,
                tvUrl = parts.getOrNull(2).orEmpty(),
                referer = parts.getOrNull(3)?.takeIf { it.isNotEmpty() },
                json = parts.getOrNull(4)?.equals("json", true) == true,
            )
        }
        .toList()

    fun urlFor(template: ProviderTemplate, request: LinkRequest): String? {
        val raw = if (request.isMovie) template.movieUrl else template.tvUrl
        if (raw.isBlank()) return null
        return substitute(raw, request)
    }

    fun substitute(template: String, request: LinkRequest): String? {
        if (template.contains("{imdb}") && request.imdbId.isNullOrBlank()) return null
        var out = template
        out = out.replace("{tmdb}", request.tmdbId.toString())
        out = out.replace("{imdb}", request.imdbId.orEmpty())
        out = out.replace("{type}", request.type)
        out = out.replace("{season}", request.season.toString())
        out = out.replace("{episode}", request.episode.toString())
        out = out.replace("{title}", encode(request.title))
        out = out.replace("{year}", request.year?.toString().orEmpty())
        return out
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
