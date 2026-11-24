package com.crunchyme

/** Regex-level helpers for generic embed pages. No browser, no JS execution. */
object HtmlHarvest {

    private val IFRAME = Regex("""(?is)<iframe[^>]*\bsrc\s*=\s*["']([^"']+)["']""")
    private val VIDEO = Regex("""(?is)<(?:video|source)[^>]*\bsrc\s*=\s*["']([^"']+)["']""")
    private val MEDIA_URL = Regex(
        """(?i)https?://[^\s"'<>\\]+\.(?:m3u8|mp4|mpd|webm|mkv)(?:[^\s"'<>\\]*)""",
    )
    private val OG_TITLE = Regex(
        """(?is)<meta[^>]+property\s*=\s*["']og:title["'][^>]+content\s*=\s*["']([^"']+)["']""",
    )
    private val TITLE = Regex("""(?is)<title[^>]*>([^<]+)</title>""")

    fun unescape(html: String): String = html
        .replace("\\/", "/")
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\u003D", "=", ignoreCase = true)
        .replace("\\u003F", "?", ignoreCase = true)
        .replace("\\u003A", ":", ignoreCase = true)
        .replace("&amp;", "&")
        .replace("\\", "")

    fun iframes(html: String, baseUrl: String): List<String> {
        val text = unescape(html)
        return IFRAME.findAll(text)
            .map { Manifest.resolveUrl(baseUrl, it.groupValues[1].trim()) }
            .filter { it.startsWith("http") }
            .distinct()
            .toList()
    }

    fun mediaUrls(html: String): List<String> {
        val text = unescape(html)
        return MEDIA_URL.findAll(text)
            .map { it.value.trimEnd('\\', '"', '\'') }
            .distinct()
            .toList()
    }

    fun videoTag(html: String, baseUrl: String): String? {
        val text = unescape(html)
        val raw = VIDEO.find(text)?.groupValues?.get(1)?.trim() ?: return null
        return Manifest.resolveUrl(baseUrl, raw)
    }

    fun pageTitle(html: String): String? {
        OG_TITLE.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return TITLE.find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** True when the response is a bot-protection interstitial rather than content. */
    fun isChallenge(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        val lower = html.lowercase()
        return lower.contains("just a moment") ||
            lower.contains("cf-chl") ||
            lower.contains("attention required") ||
            lower.contains("enable javascript and cookies") ||
            lower.contains("ddos-guard") ||
            lower.contains("checking your browser")
    }
}
