package com.crunchyme

import java.net.URLEncoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/**
 * Internet Archive is the built-in legal source: public-domain and openly licensed
 * films with direct MP4 files. It is movie-only; series support is not reliable.
 */
object ArchiveSource {

    const val ID = "archive"
    const val NAME = "Internet Archive"

    private const val SEARCH_URL = "https://archive.org/advancedsearch.php"
    private const val METADATA_URL = "https://archive.org/metadata/"
    private const val DOWNLOAD_URL = "https://archive.org/download/"
    private const val REFERER = "https://archive.org/"
    private const val MAX_ITEMS = 5
    private const val MAX_FILES = 10

    private val VIDEO_EXT = Regex("""(?i)\.(mp4|m4v|mkv|webm|ogv)$""")
    private val IGNORED_NAME = Regex("""(?i)(trailer|sample|thumb|preview)""")
    private val ALLOWED_FORMAT = Regex("""(?i)(mpeg4|h\.264|matroska|webm|ogg video)""")

    data class ArchiveItem(val identifier: String, val title: String, val year: Int?)

    fun searchUrl(title: String): String {
        val clean = title.replace(Regex("""[^\p{L}\p{N}\s'\-]"""), " ").replace(Regex("""\s+"""), " ").trim()
        val query = "title:($clean) AND mediatype:(movies)"
        return buildString {
            append(SEARCH_URL)
            append("?q=").append(encode(query))
            append("&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=year")
            append("&rows=25&page=1&output=json")
        }
    }

    fun parseSearch(body: String?): List<ArchiveItem> {
        if (body.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val docs = root.optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).mapNotNull { index ->
            val doc = docs.optJSONObject(index) ?: return@mapNotNull null
            val identifier = doc.optString("identifier").trim()
            if (identifier.isEmpty()) return@mapNotNull null
            ArchiveItem(
                identifier = identifier,
                title = doc.optString("title").trim(),
                year = yearOf(doc.opt("year")),
            )
        }
    }

    fun rank(items: List<ArchiveItem>, title: String, year: Int?): List<ArchiveItem> {
        val wanted = normalize(title)
        return items.map { it to score(wanted, year, it) }
            .filter { (item, value) -> value > 0 && !IGNORED_NAME.containsMatchIn(item.title) }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(MAX_ITEMS)
    }

    suspend fun resolve(request: LinkRequest): List<Candidate> {
        if (!request.isMovie) return emptyList()
        val started = System.currentTimeMillis()
        val body = Http.text(
            searchUrl(request.title),
            mapOf("Accept" to "application/json"),
            20,
        ) ?: return emptyList()

        val items = rank(parseSearch(body), request.title, request.year)
        if (items.isEmpty()) return emptyList()

        val fetched = coroutineScope {
            items.map { item ->
                async {
                    val meta = Http.text(
                        METADATA_URL + encode(item.identifier),
                        mapOf("Accept" to "application/json"),
                        20,
                    ) ?: return@async emptyList()
                    parseFiles(meta, item.identifier)
                }
            }.awaitAll()
        }

        val out = LinkedHashMap<String, Candidate>()
        fetched.flatten().forEach { out.putIfAbsent(it.url, it) }

        val elapsed = System.currentTimeMillis() - started
        return out.values
            .sortedByDescending { it.qualityHint }
            .take(MAX_FILES)
            .map { it.copy(speedMs = elapsed) }
    }

    fun parseFiles(metadataBody: String?, identifier: String): List<Candidate> {
        if (metadataBody.isNullOrBlank()) return emptyList()
        val root = runCatching { JSONObject(metadataBody) }.getOrNull() ?: return emptyList()
        val files = root.optJSONArray("files") ?: return emptyList()
        val language = languageOf(root.optJSONObject("metadata"))

        val out = ArrayList<Candidate>()
        for (index in 0 until files.length()) {
            val file = files.optJSONObject(index) ?: continue
            val name = file.optString("name").trim()
            if (name.isEmpty()) continue
            if (!VIDEO_EXT.containsMatchIn(name)) continue
            if (name.contains(".ia.", ignoreCase = true)) continue
            if (IGNORED_NAME.containsMatchIn(name)) continue
            val format = file.optString("format")
            if (format.isNotBlank() && !ALLOWED_FORMAT.containsMatchIn(format)) continue

            val height = file.optInt("height", 0).takeIf { it > 0 }
                ?: file.optString("height").toIntOrNull()?.takeIf { it > 0 }
                ?: 0
            val bytes = file.optString("size").toLongOrNull() ?: 0L

            out += Candidate(
                sourceId = ID,
                sourceName = NAME,
                url = DOWNLOAD_URL + encode(identifier) + "/" + encodePath(name),
                kind = MediaKind.FILE,
                referer = REFERER,
                qualityHint = height,
                audioLabel = language,
                sizeLabel = humanSize(bytes),
            )
        }
        return out.sortedByDescending { it.qualityHint }
    }

    private fun languageOf(metadata: JSONObject?): String {
        val raw = metadata?.optString("language").orEmpty().trim()
        if (raw.isEmpty()) return ""
        return Naming.fromCode(raw).ifBlank { Naming.labelOf(raw) }
    }

    private fun yearOf(value: Any?): Int? = when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.take(4).toIntOrNull()
        else -> null
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun score(query: String, year: Int?, item: ArchiveItem): Int {
        val title = normalize(item.title)
        if (query.isEmpty() || title.isEmpty()) return 0
        val base = when {
            title == query -> 1000
            title.startsWith(query) -> 800
            title.contains(query) -> 600
            query.contains(title) -> 500
            else -> (overlap(query, title) * 400).toInt()
        }
        val yearBonus = when {
            year == null || item.year == null -> 0
            year == item.year -> 60
            kotlin.math.abs(year - item.year) <= 1 -> 30
            else -> -60
        }
        return base + yearBonus
    }

    private fun overlap(a: String, b: String): Double {
        val left = a.split(' ').filter { it.isNotBlank() }.toSet()
        val right = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.size
    }

    private fun humanSize(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes >= 1_073_741_824 -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format(java.util.Locale.US, "%.0f MB", bytes / 1_048_576.0)
        else -> ""
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun encodePath(path: String): String =
        path.split('/').joinToString("/") { encode(it) }
}
