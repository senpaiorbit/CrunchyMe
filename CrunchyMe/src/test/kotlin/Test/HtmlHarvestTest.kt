package test

import com.crunchyme.HtmlHarvest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HtmlHarvestTest {

    @Test
    fun resolvesIframeSources() {
        val html = """
            <html><body>
              <iframe src="//cdn.example/embed/abc"></iframe>
              <iframe src="/embed/def"></iframe>
            </body></html>
        """.trimIndent()
        assertEquals(
            listOf("https://cdn.example/embed/abc", "https://site.example/embed/def"),
            HtmlHarvest.iframes(html, "https://site.example/watch/1"),
        )
    }

    @Test
    fun unescapesJsonEncodedMediaUrls() {
        val html = """<script>var f = "https:\/\/cdn.example\/hls\/master.m3u8?token=1\u0026x=2";</script>"""
        assertEquals(
            listOf("https://cdn.example/hls/master.m3u8?token=1&x=2"),
            HtmlHarvest.mediaUrls(html),
        )
    }

    @Test
    fun readsVideoTag() {
        val html = """<video controls><source src="/v/720.mp4" type="video/mp4"></video>"""
        assertEquals("https://site.example/v/720.mp4", HtmlHarvest.videoTag(html, "https://site.example/e/1"))
    }

    @Test
    fun detectsBotChallenges() {
        assertTrue(HtmlHarvest.isChallenge("<title>Just a moment...</title>"))
        assertFalse(HtmlHarvest.isChallenge("<html>movie</html>"))
        assertFalse(HtmlHarvest.isChallenge(null))
    }

    @Test
    fun readsPageTitle() {
        assertEquals("Great Movie", HtmlHarvest.pageTitle("<title>Great Movie</title>"))
    }
}
