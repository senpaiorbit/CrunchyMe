package com.megaflix

import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.base64Encode

/**
 * Opaque, self-contained references passed through CloudStream's normal result and
 * episode data strings. They deliberately start with https:// so CloudStream's
 * MainAPI.fixUrl does not prefix them with MegaFlix's mainUrl.
 */
sealed interface MegaRef {
    data class Result(
        val title: String,
        val year: Int?,
        val type: TvType,
        val refs: List<ProviderRef>,
    ) : MegaRef

    data class Episode(
        val title: String,
        val season: Int?,
        val episode: Int?,
        val refs: List<ProviderRef>,
    ) : MegaRef

    data class Links(val refs: List<ProviderRef>) : MegaRef

    data class Home(
        val provider: String,
        val mainUrl: String,
        val rowName: String,
        val rowData: String,
        val horizontal: Boolean,
    ) : MegaRef
}

data class ProviderRef(
    val provider: String,
    val mainUrl: String,
    val value: String,
)

object Refs {
    private const val BASE = "https://megaflix.local/"
    private const val RESULT = "r"
    private const val EPISODE = "e"
    private const val LINKS = "l"
    private const val HOME = "h"
    private const val FIELD = "\u001F"
    private const val RECORD = "\u001E"

    fun result(title: String, year: Int?, type: TvType, refs: List<ProviderRef>): String =
        encode(RESULT, listOf(title, year?.toString().orEmpty(), type.name, encodeRefs(refs)))

    fun episode(title: String, season: Int?, episode: Int?, refs: List<ProviderRef>): String =
        encode(
            EPISODE,
            listOf(title, season?.toString().orEmpty(), episode?.toString().orEmpty(), encodeRefs(refs)),
        )

    fun links(refs: List<ProviderRef>): String = encode(LINKS, listOf(encodeRefs(refs)))

    fun home(
        provider: String,
        mainUrl: String,
        rowName: String,
        rowData: String,
        horizontal: Boolean,
    ): String = encode(
        HOME,
        listOf(provider, mainUrl, rowName, rowData, if (horizontal) "1" else "0"),
    )

    fun parse(value: String?): MegaRef? {
        if (value.isNullOrBlank() || !value.startsWith(BASE)) return null
        val rest = value.removePrefix(BASE)
        val kind = rest.substringBefore('/', "")
        val payload = rest.substringAfter('/', "")
        if (payload.isBlank()) return null
        return try {
            val fields = unpack(payload).split(FIELD, limit = 8)
            when (kind) {
                RESULT -> {
                    if (fields.size < 4) return null
                    val type = runCatching { TvType.valueOf(fields[2]) }.getOrNull() ?: return null
                    MegaRef.Result(
                        title = fields[0],
                        year = fields[1].toIntOrNull(),
                        type = type,
                        refs = decodeRefs(fields[3]),
                    )
                }
                EPISODE -> {
                    if (fields.size < 4) return null
                    MegaRef.Episode(
                        title = fields[0],
                        season = fields[1].toIntOrNull(),
                        episode = fields[2].toIntOrNull(),
                        refs = decodeRefs(fields[3]),
                    )
                }
                LINKS -> {
                    if (fields.isEmpty()) return null
                    MegaRef.Links(decodeRefs(fields[0]))
                }
                HOME -> {
                    if (fields.size < 5) return null
                    MegaRef.Home(
                        provider = fields[0],
                        mainUrl = fields[1],
                        rowName = fields[2],
                        rowData = fields[3],
                        horizontal = fields[4] == "1",
                    )
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun encode(kind: String, fields: List<String>): String =
        BASE + kind + "/" + pack(fields.joinToString(FIELD))

    private fun encodeRefs(refs: List<ProviderRef>): String =
        pack(
            refs.joinToString(RECORD) { ref ->
                listOf(ref.provider, ref.mainUrl, ref.value).joinToString(FIELD) { pack(it) }
            },
        )

    private fun decodeRefs(value: String): List<ProviderRef> {
        if (value.isBlank()) return emptyList()
        return unpack(value).split(RECORD).mapNotNull { record ->
            val parts = record.split(FIELD, limit = 3)
            if (parts.size != 3) return@mapNotNull null
            runCatching {
                ProviderRef(
                    provider = unpack(parts[0]),
                    mainUrl = unpack(parts[1]),
                    value = unpack(parts[2]),
                )
            }.getOrNull()
        }.distinctBy { it.provider + "|" + it.mainUrl + "|" + it.value }
    }

    private fun pack(value: String): String = base64Encode(value.toByteArray(Charsets.UTF_8))

    private fun unpack(value: String): String =
        base64DecodeArray(value).decodeToString()
}
