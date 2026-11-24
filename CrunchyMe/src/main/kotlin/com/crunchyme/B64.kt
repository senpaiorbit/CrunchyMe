package com.crunchyme

/** Small Base64 implementation so providers work on every Android API level. */
object B64 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val REVERSE = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    fun decode(input: String): ByteArray? {
        val clean = input.trim()
            .replace('-', '+')
            .replace('_', '/')
            .filterNot { it.isWhitespace() }
            .trimEnd('=')
        if (clean.isEmpty() || clean.length % 4 == 1) return null
        val out = ByteArray(clean.length * 3 / 4 + 3)
        var written = 0
        var index = 0
        while (index < clean.length) {
            val c0 = clean[index].code.takeIf { it < 128 }?.let { REVERSE[it] } ?: return null
            val c1 = if (index + 1 < clean.length) {
                clean[index + 1].code.takeIf { it < 128 }?.let { REVERSE[it] } ?: return null
            } else 0
            val c2 = if (index + 2 < clean.length) {
                clean[index + 2].code.takeIf { it < 128 }?.let { REVERSE[it] } ?: return null
            } else 0
            val c3 = if (index + 3 < clean.length) {
                clean[index + 3].code.takeIf { it < 128 }?.let { REVERSE[it] } ?: return null
            } else 0
            if (c0 < 0 || c1 < 0 || c2 < 0 || c3 < 0) return null
            out[written++] = ((c0 shl 2) or (c1 shr 4)).toByte()
            if (index + 2 < clean.length) out[written++] = (((c1 and 0xF) shl 4) or (c2 shr 2)).toByte()
            if (index + 3 < clean.length) out[written++] = (((c2 and 0x3) shl 6) or c3).toByte()
            index += 4
        }
        return out.copyOf(written)
    }

    fun decodeText(input: String): String? = decode(input)?.toString(Charsets.UTF_8)

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else 0
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else 0
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 0x3) shl 4) or (b1 shr 4)])
            if (index + 1 < bytes.size) out.append(ALPHABET[((b1 and 0xF) shl 2) or (b2 shr 6)])
            else out.append('=')
            if (index + 2 < bytes.size) out.append(ALPHABET[b2 and 0x3F])
            else out.append('=')
            index += 3
        }
        return out.toString()
    }

    fun encodeUrl(bytes: ByteArray): String =
        encode(bytes).trimEnd('=').replace('+', '-').replace('/', '_')
}
