package Test

import com.crunchyme.LinkRequest
import com.crunchyme.MediaKind
import com.crunchyme.parseVidriftHtml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VidriftParseTest {

    private val request = LinkRequest(
        tmdbId = 27205,
        imdbId = "tt1375666",
        type = "movie",
        season = -1,
        episode = -1,
        title = "Inception",
        year = 2010,
        originalLanguage = "en",
    )

    @Test
    fun parsesOrionStreamsAndWarmManifest() {
        val html = """
            <html><script>var embedMeta = {"orionStreams":[
              {"url":"/api/orion/a/master.m3u8","name":"Orion · Original","rungs":[{"height":1080},{"height":720}]},
              {"url":"/api/orion/b/master.m3u8","name":"Orion · Hindi","rungs":[{"height":480}]}
            ],"warmManifests":{"/api/orion/a/master.m3u8":"#EXTM3U\n#EXT-X-STREAM-INF:RESOLUTION=1920x1080\nv.m3u8"}};</script></html>
        """.trimIndent()
        val items = parseVidriftHtml(html, request)
        assertEquals(2, items.size)
        assertEquals("https://embed.vidrift.net/api/orion/a/master.m3u8", items[0].url)
        assertEquals(MediaKind.HLS, items[0].kind)
        assertEquals(1080, items[0].qualityHint)
        assertEquals("English", items[0].audioLabel)
        assertTrue(items[0].inlineManifest!!.contains("#EXTM3U"))
        assertEquals("Hindi", items[1].audioLabel)
        assertEquals(480, items[1].qualityHint)
    }

    @Test
    fun fallsBackToEvionUrl() {
        val html = """<script>var embedMeta = {"evionUrl":"https://cdn.example/e.m3u8","orionStreams":[]};</script>"""
        val item = parseVidriftHtml(html, request).single()
        assertEquals("https://cdn.example/e.m3u8", item.url)
    }

    @Test
    fun missingMetaIsEmpty() {
        assertTrue(parseVidriftHtml("<html>no config</html>", request).isEmpty())
    }
}
