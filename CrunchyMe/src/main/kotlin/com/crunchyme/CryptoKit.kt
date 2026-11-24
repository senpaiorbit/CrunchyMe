package com.crunchyme

import java.math.BigInteger

/** XSalsa20-Poly1305 secretbox used by the VidLink token endpoint. */
object CryptoKit {

    private val SIGMA = "expand 32-byte k".toByteArray(Charsets.US_ASCII)

    private fun rotl(v: Int, n: Int): Int = (v shl n) or (v ushr (32 - n))

    private fun quarterRound(x: IntArray, a: Int, b: Int, c: Int, d: Int) {
        x[b] = x[b] xor rotl(x[a] + x[d], 7)
        x[c] = x[c] xor rotl(x[b] + x[a], 9)
        x[d] = x[d] xor rotl(x[c] + x[b], 13)
        x[a] = x[a] xor rotl(x[d] + x[c], 18)
    }

    private fun load32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun store32(out: ByteArray, off: Int, v: Int) {
        out[off] = (v and 0xFF).toByte()
        out[off + 1] = ((v ushr 8) and 0xFF).toByte()
        out[off + 2] = ((v ushr 16) and 0xFF).toByte()
        out[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun core(input: IntArray, addOriginal: Boolean): IntArray {
        val x = input.copyOf()
        var i = 20
        while (i > 0) {
            quarterRound(x, 0, 4, 8, 12)
            quarterRound(x, 5, 9, 13, 1)
            quarterRound(x, 10, 14, 2, 6)
            quarterRound(x, 15, 3, 7, 11)
            quarterRound(x, 0, 1, 2, 3)
            quarterRound(x, 5, 6, 7, 4)
            quarterRound(x, 10, 11, 8, 9)
            quarterRound(x, 15, 12, 13, 14)
            i -= 2
        }
        if (addOriginal) for (j in 0 until 16) x[j] += input[j]
        return x
    }

    private fun hsalsa20(key: ByteArray, nonce16: ByteArray): ByteArray {
        val state = IntArray(16)
        state[0] = load32(SIGMA, 0)
        state[1] = load32(key, 0)
        state[2] = load32(key, 4)
        state[3] = load32(key, 8)
        state[4] = load32(key, 12)
        state[5] = load32(SIGMA, 4)
        state[6] = load32(nonce16, 0)
        state[7] = load32(nonce16, 4)
        state[8] = load32(nonce16, 8)
        state[9] = load32(nonce16, 12)
        state[10] = load32(SIGMA, 8)
        state[11] = load32(key, 16)
        state[12] = load32(key, 20)
        state[13] = load32(key, 24)
        state[14] = load32(key, 28)
        state[15] = load32(SIGMA, 12)
        val out = core(state, addOriginal = false)
        val result = ByteArray(32)
        val picks = intArrayOf(0, 5, 10, 15, 6, 7, 8, 9)
        picks.forEachIndexed { index, word -> store32(result, index * 4, out[word]) }
        return result
    }

    private fun salsa20Block(key32: ByteArray, nonce8: ByteArray, blockIndex: Long): ByteArray {
        val state = IntArray(16)
        state[0] = load32(SIGMA, 0)
        state[1] = load32(key32, 0)
        state[2] = load32(key32, 4)
        state[3] = load32(key32, 8)
        state[4] = load32(key32, 12)
        state[5] = load32(SIGMA, 4)
        state[6] = load32(nonce8, 0)
        state[7] = load32(nonce8, 4)
        state[8] = blockIndex.toInt()
        state[9] = (blockIndex ushr 32).toInt()
        state[10] = load32(SIGMA, 8)
        state[11] = load32(key32, 16)
        state[12] = load32(key32, 20)
        state[13] = load32(key32, 24)
        state[14] = load32(key32, 28)
        state[15] = load32(SIGMA, 12)
        val out = core(state, addOriginal = true)
        val result = ByteArray(64)
        for (index in 0 until 16) store32(result, index * 4, out[index])
        return result
    }

    private fun xsalsa20Keystream(key: ByteArray, nonce24: ByteArray, length: Int): ByteArray {
        require(key.size == 32 && nonce24.size == 24)
        val subkey = hsalsa20(key, nonce24.copyOfRange(0, 16))
        val nonce8 = nonce24.copyOfRange(16, 24)
        val out = ByteArray(length)
        var offset = 0
        var block = 0L
        while (offset < length) {
            val blockBytes = salsa20Block(subkey, nonce8, block)
            val count = minOf(64, length - offset)
            blockBytes.copyInto(out, offset, 0, count)
            offset += count
            block++
        }
        return out
    }

    private fun leBytesToBig(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun bigToLeBytes(value: BigInteger, size: Int): ByteArray {
        val out = ByteArray(size)
        var current = value
        for (index in 0 until size) {
            out[index] = current.and(BigInteger.valueOf(0xFF)).toInt().toByte()
            current = current.shiftRight(8)
        }
        return out
    }

    private fun poly1305(key: ByteArray, message: ByteArray): ByteArray {
        require(key.size == 32)
        val prime = BigInteger.valueOf(2).pow(130).subtract(BigInteger.valueOf(5))
        val rBytes = key.copyOfRange(0, 16)
        rBytes[3] = (rBytes[3].toInt() and 15).toByte()
        rBytes[4] = (rBytes[4].toInt() and 252).toByte()
        rBytes[7] = (rBytes[7].toInt() and 15).toByte()
        rBytes[8] = (rBytes[8].toInt() and 252).toByte()
        rBytes[11] = (rBytes[11].toInt() and 15).toByte()
        rBytes[12] = (rBytes[12].toInt() and 252).toByte()
        rBytes[15] = (rBytes[15].toInt() and 15).toByte()
        val r = leBytesToBig(rBytes)
        val s = leBytesToBig(key.copyOfRange(16, 32))
        var h = BigInteger.ZERO
        var index = 0
        while (index < message.size) {
            val end = minOf(index + 16, message.size)
            val block = message.copyOfRange(index, end) + 0x01
            h = h.add(leBytesToBig(block)).multiply(r).mod(prime)
            index += 16
        }
        h = h.add(s).mod(BigInteger.valueOf(2).pow(128))
        return bigToLeBytes(h, 16)
    }

    fun secretbox(message: ByteArray, nonce24: ByteArray, key: ByteArray): ByteArray {
        require(nonce24.size == 24 && key.size == 32)
        val full = ByteArray(32 + message.size)
        message.copyInto(full, 32)
        val keystream = xsalsa20Keystream(key, nonce24, full.size)
        val cipher = ByteArray(full.size) { (full[it].toInt() xor keystream[it].toInt()).toByte() }
        val mac = poly1305(cipher.copyOfRange(0, 32), cipher.copyOfRange(32, cipher.size))
        val out = ByteArray(16 + message.size)
        mac.copyInto(out, 0)
        cipher.copyInto(out, 16, 32, cipher.size)
        return out
    }
}
