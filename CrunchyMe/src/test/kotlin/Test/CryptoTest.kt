package Test

import com.crunchyme.B64
import com.crunchyme.CryptoKit
import com.crunchyme.LinkRequest
import com.crunchyme.ServerFarm
import com.crunchyme.VidLink
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CryptoTest {

    @Test
    fun secretboxMatchesNaclVector() {
        val key = "c75136c5668bbfe65a7ecad431a745db68b5f381555b38d8f6c699449cf11fcd"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val nonce = ByteArray(24)
        val message = "27205".toByteArray() + byteArrayOf(
            0xD2.toByte(), 0x02.toByte(), 0x96.toByte(), 0x49.toByte(), 0x00, 0x00, 0x00, 0x00,
        )
        val expected = "671861e465a0d7cc6db9d31f8f1dca92c2840ace7892548f90c44a88a6"
        assertContentEquals(expected.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), CryptoKit.secretbox(message, nonce, key))
    }

    @Test
    fun vidLinkTokenIsSelfContained() {
        val token = VidLink.token("27205", 1234567890)
        val bytes = assertNotNull(B64.decode(token))
        assertEquals(53, bytes.size)
        assertTrue(bytes.take(24).all { it == 0.toByte() })
    }

    @Test
    fun vidLinkApiUrlCarriesSeasonAndEpisode() {
        val spec = ServerFarm.spec("vidlink")!!
        val request = LinkRequest(
            tmdbId = 1399,
            imdbId = "tt0944947",
            type = "tv",
            season = 2,
            episode = 5,
            title = "Game of Thrones",
            year = 2011,
            originalLanguage = "en",
        )
        val url = VidLink.apiUrl(spec, request, "1399", 0)
        assertTrue(url.contains("/tv/"))
        assertTrue(url.endsWith("/2/5?multiLang=1"))
    }
}
