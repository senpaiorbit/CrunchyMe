package Test

import com.crunchyme.LinkRequest
import com.crunchyme.MediaKind
import com.crunchyme.parseVidboltJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class VidBoltParseTest {

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
    fun parsesSourcesAndInlineManifest() {
        val json = """
            {"stream":{"server":"orion","sources":[
              {"url":"https://cdn.example/master.m3u8","kind":"hls","quality":"1080p",
               "language":"Hindi","label":"HD","master":"#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aac\",LANGUAGE=\"hin\",NAME=\"Hindi\"\n#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO=\"aac\"\nv.m3u8"},
              {"url":"not-a-url","quality":"720p"}
            ]}}
        """.trimIndent()
        val items = parseVidboltJson(json, request)
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals(MediaKind.HLS, item.kind)
        assertEquals(1080, item.qualityHint)
        assertEquals("Hindi", item.audioLabel)
        assertNotNull(item.inlineManifest)
        assertTrue(item.inlineManifest!!.contains("#EXTM3U"))
    }

    @Test
    fun handlesDashManifestAnd4kLabel() {
        val json = """
            {"stream":{"sources":[
              {"url":"https://cdn.example/dash.mpd","kind":"dash","quality":"4K",
               "master":"<MPD><Period><AdaptationSet mimeType=\"audio/mp4\" lang=\"hi\"><Representation height=\"2160\" codecs=\"hvc1\"/></AdaptationSet></Period></MPD>"}
            ]}}
        """.trimIndent()
        val item = parseVidboltJson(json, request).single()
        assertEquals(MediaKind.DASH, item.kind)
        assertEquals(2160, item.qualityHint)
        assertEquals("Hindi", item.audioLabel)
    }

    @Test
    fun malformedJsonIsEmpty() {
        assertTrue(parseVidboltJson("not json", request).isEmpty())
        assertTrue(parseVidboltJson("""{"stream":{"sources":[]}}""", request).isEmpty())
    }
}
