package com.crunchyme

import org.json.JSONArray
import org.json.JSONObject

/** Recursively finds playable URLs in arbitrary provider JSON payloads. */
object JsonWalk {

    data class Hit(
        val url: String,
        val quality: Int,
        val language: String,
        val label: String,
        val size: String,
    )

    private val MEDIA_URL = Regex(
        """(?i)https?://[^\s"'<>\\]+\.(?:m3u8|mp4|mpd|webm|mkv)(?:[^\s"'<>\\]*)""",
    )
    private val RELATIVE_MEDIA = Regex(
        """(?i)^(?:\.{0,2}/)?[^\s"'<>\\:]+\.(?:m3u8|mp4|mpd|webm|mkv)(?:[?#][^\s"'<>\\]*)?$""",
    )

    fun mediaUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val unescaped = HtmlHarvest.unescape(text)
        return MEDIA_URL.findAll(unescaped).map { it.value.trimEnd('\\') }.distinct().toList()
    }

    fun harvest(root: JSONObject, baseUrl: String): List<Hit> {
        val out = LinkedHashMap<String, Hit>()
        walk(root, baseUrl, out, 0)
        return out.values.toList()
    }

    private fun walk(node: Any?, baseUrl: String, out: MutableMap<String, Hit>, depth: Int) {
        if (depth > 12 || node == null) return
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    when (val value = node.opt(key)) {
                        is String -> {
                            val text = HtmlHarvest.unescape(value).trim()
                            val found = MEDIA_URL.find(text)?.value
                                ?: text.takeIf { RELATIVE_MEDIA.matches(it) }
                                ?: continue
                            val url = Manifest.resolveUrl(baseUrl, found)
                            if (!out.containsKey(url)) out[url] = hit(url, node)
                        }

                        else -> walk(value, baseUrl, out, depth + 1)
                    }
                }
            }

            is JSONArray -> {
                for (index in 0 until node.length()) {
                    walk(node.opt(index), baseUrl, out, depth + 1)
                }
            }
        }
    }

    private fun hit(url: String, obj: JSONObject): Hit {
        val label = firstNonBlank(obj, "label", "name", "title", "quality", "resolution", "file")
        val declaredHeight = obj.optInt("height", 0)
        val quality = if (declaredHeight > 0) declaredHeight else Naming.qualityHeight(label)
        val rawLanguage = firstNonBlank(obj, "language", "lang", "audio", "label", "name", "title")
        val language = Naming.fromCode(rawLanguage)
            .ifBlank { Naming.displayLabel(rawLanguage) }
        val size = firstNonBlank(obj, "size", "filesize", "file_size")
            .ifBlank { Naming.sizeLabel(label) }
        return Hit(url, quality, language, label, size)
    }

    private fun firstNonBlank(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.optString(key, "").trim()
            if (value.isNotEmpty() && value != "null") return value
        }
        return ""
    }
}
