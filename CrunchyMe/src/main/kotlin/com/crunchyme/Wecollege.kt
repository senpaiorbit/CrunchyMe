package com.crunchyme

import java.net.URLEncoder
import org.json.JSONObject

/** Vidy and Movy share the encrypted WeCollege source lanes. */
object Vidy {
    private val LANES = listOf(
        "miami", "boise", "seattle", "atlanta", "phoenix", "cancun",
        "vegas", "reno", "oakland", "portland", "austin", "dallas",
        "tampa", "orlando", "munich", "berlin", "paris", "delhi",
    )

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> =
        Wecollege.resolve(spec, request, LANES, "https://www.vidy.st/")
}

object Movy {
    private val LANES = listOf(
        "miami", "boise", "seattle", "atlanta", "phoenix", "cancun",
        "denver", "portland", "paris", "austin", "dallas", "delhi",
        "munich", "orlando", "tampa", "berlin",
    )

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> =
        Wecollege.resolve(spec, request, LANES, "https://www.movy.sx/")
}

private object Wecollege {

    private const val BASE = "https://api.wecollege.net"
    private const val GOLDEN = 0x9e3779b9.toInt()
    private val MAGIC = byteArrayOf(109, 118, 109, 49)

    suspend fun resolve(
        spec: ServerSpec,
        request: LinkRequest,
        lanes: List<String>,
        referer: String,
    ): List<Candidate> {
        if (request.tmdbId <= 0) return emptyList()
        if (!request.isMovie && !request.hasEpisode) return emptyList()
        val headers = mapOf(
            "Referer" to referer,
            "Origin" to referer.trimEnd('/'),
            "User-Agent" to Http.UA,
        )
        val seedBody = Http.text("$BASE/seed?mediaId=${request.tmdbId}", headers, 8) ?: return emptyList()
        val seed = runCatching { JSONObject(seedBody).optString("seed") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return emptyList()

        val detail = if (Tmdb.hasKey()) Tmdb.detail(request.tmdbId, request.type) else null
        val omdb = if (detail == null && request.imdbId != null) Omdb.detail(request.imdbId) else null
        val title = detail?.title ?: omdb?.title ?: request.title
        val year = detail?.year ?: omdb?.year ?: request.year
        val totalSeasons = detail?.numberOfSeasons ?: omdb?.totalSeasons ?: 1
        val imdb = detail?.imdbId ?: request.imdbId.orEmpty()

        val params = listOf(
            "title" to encode(title),
            "mediaType" to if (request.isMovie) "movie" else "tv",
            "year" to (year?.toString() ?: ""),
            "totalSeasons" to totalSeasons.toString(),
            "seasonId" to (if (request.isMovie) 1 else request.season).toString(),
            "episodeId" to (if (request.isMovie) 1 else request.episode).toString(),
            "tmdbId" to request.tmdbId.toString(),
            "imdbId" to imdb,
        )
        val out = LinkedHashMap<String, Candidate>()
        val deadline = System.currentTimeMillis() + 20_000L
        for (lane in lanes) {
            if (out.size >= 8 || System.currentTimeMillis() > deadline) break
            val query = params.joinToString("&") { "${it.first}=${it.second}" } + "&enc=2&seed=$seed"
            val cipher = Http.text("$BASE/$lane/sources?$query", headers, 8) ?: continue
            val plain = decrypt(cipher, seed, request.tmdbId) ?: continue
            for (source in sources(plain)) {
                val height = heightOf(source.quality)
                if (height in 1..719) continue
                val audio = Naming.displayLabel(source.quality)
                out[source.url] = ProviderSupport.candidate(
                    spec = spec,
                    url = source.url,
                    referer = referer,
                    quality = height,
                    audio = audio,
                    media = source.quality,
                    headers = headers,
                )
            }
        }
        return out.values.toList()
    }

    private data class Source(val url: String, val quality: String)

    private fun sources(plaintext: String): List<Source> {
        val root = runCatching { JSONObject(plaintext) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("sources") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val url = item.optString("url").takeIf { it.startsWith("http") } ?: return@mapNotNull null
            Source(url, item.optString("quality"))
        }
    }

    private fun encode(value: String): String {
        val once = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        return URLEncoder.encode(once, "UTF-8").replace("+", "%20")
    }

    private fun heightOf(quality: String): Int =
        Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE).find(quality)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun u32(value: Int): Long = value.toLong() and 0xFFFFFFFFL

    private fun fmix(value: Int): Int {
        var h = value
        h = h xor (h ushr 16)
        h *= 0x85ebca6b.toInt()
        h = h xor (h ushr 13)
        h *= 0xc2b2ae35.toInt()
        h = h xor (h ushr 16)
        return h
    }

    private fun rotl(value: Int, amount0: Int): Int {
        val amount = amount0 and 31
        return if (amount == 0) value else (value shl amount) or (value ushr (32 - amount))
    }

    private fun keyed(value: String): Int {
        var hash = 0x811c9dc5.toInt()
        for (char in value) hash = (hash xor char.code) * 16777619
        return fmix(hash)
    }

    private class Keystream(val table: HashMap<Int, Int>, var acc: Int)

    private fun initKeystream(seed: String, mediaId: Int): Keystream {
        val table = HashMap<Int, Int>()
        var n = fmix(keyed(seed) xor fmix(mediaId xor GOLDEN))
        for (index in 0 until 8) {
            val slot = (u32(n) % 61).toInt()
            n = rotl(n + GOLDEN, 7 + (7 and index))
            table[slot] = n xor fmix(n)
            n = fmix(n + slot)
        }
        return Keystream(table, fmix(0xa5a5a5a5.toInt() xor n))
    }

    private fun nextWord(stream: Keystream, counter: Int): Int {
        val slot = (u32(stream.acc) % 61).toInt()
        val inTable = if (stream.table.containsKey(slot)) -1 else 0
        val r = stream.table[slot] ?: 0
        val mixed = r xor (GOLDEN * (counter + 1))
        val b = (stream.acc xor mixed) or (stream.acc and mixed and inTable)
        val next = fmix(
            (rotl(b + stream.acc, 31 and slot) xor rotl(stream.acc, 31 and (slot * 7))) + GOLDEN,
        )
        stream.table[slot] = next
        stream.acc = next
        return next
    }

    private fun decrypt(payload: String, seed: String, mediaId: Int): String? {
        return try {
            val cipher = B64.decode(payload) ?: return null
            val stream = initKeystream(seed, mediaId)
            val keystream = ByteArray(cipher.size)
            var counter = 0
            var index = 0
            while (index < cipher.size) {
                val word = nextWord(stream, counter++)
                if (index < cipher.size) keystream[index++] = (word and 0xFF).toByte()
                if (index < cipher.size) keystream[index++] = ((word ushr 8) and 0xFF).toByte()
                if (index < cipher.size) keystream[index++] = ((word ushr 16) and 0xFF).toByte()
                if (index < cipher.size) keystream[index++] = ((word ushr 24) and 0xFF).toByte()
            }
            for (i in MAGIC.indices) {
                if (i >= cipher.size || (cipher[i].toInt() xor keystream[i].toInt()).toByte() != MAGIC[i]) {
                    return null
                }
            }
            val plain = ByteArray(cipher.size)
            for (i in cipher.indices) plain[i] = (cipher[i].toInt() xor keystream[i].toInt()).toByte()
            String(plain, MAGIC.size, plain.size - MAGIC.size, Charsets.UTF_8)
        } catch (t: Throwable) {
            null
        }
    }
}
