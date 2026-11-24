package test

import com.crunchyme.Manifest
import com.crunchyme.MediaKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManifestTest {

    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Hindi",LANGUAGE="hi",DEFAULT=YES,URI="audio/hi.m3u8"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",LANGUAGE="en",URI="audio/en.m3u8"
        #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="sub",NAME="English",LANGUAGE="en",URI="subs/en.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aud",SUBTITLES="sub"
        video/1080/index.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
        video/720/index.m3u8
    """.trimIndent()

    @Test
    fun readsHeightAudioAndSubtitles() {
        val info = Manifest.analyze(master, "https://cdn.example/embed/master.m3u8", MediaKind.HLS)
        assertEquals(1080, info.height)
        assertEquals(listOf("Hindi", "English"), info.audio)
        assertTrue(info.master)
        assertEquals(1, info.subtitles.size)
        assertEquals("English", info.subtitles[0].language)
        assertEquals("https://cdn.example/embed/subs/en.m3u8", info.subtitles[0].url)
    }

    @Test
    fun resolvesRelativeUrls() {
        assertEquals(
            "https://cdn.example/embed/audio/hi.m3u8",
            Manifest.resolveUrl("https://cdn.example/embed/master.m3u8", "audio/hi.m3u8"),
        )
        assertEquals(
            "https://cdn.example/audio/hi.m3u8",
            Manifest.resolveUrl("https://cdn.example/embed/master.m3u8", "/audio/hi.m3u8"),
        )
        assertEquals(
            "https://other.example/x.m3u8",
            Manifest.resolveUrl("https://cdn.example/embed/master.m3u8", "https://other.example/x.m3u8"),
        )
        assertEquals(
            "https://cdn.example/x.m3u8",
            Manifest.resolveUrl("https://cdn.example/embed/master.m3u8", "//cdn.example/x.m3u8"),
        )
    }

    @Test
    fun readsDashHeightAndAudio() {
        val mpd = """
            <MPD>
              <Period>
                <AdaptationSet contentType="video" mimeType="video/mp4">
                  <Representation height="2160" bandwidth="12000000" />
                </AdaptationSet>
                <AdaptationSet contentType="audio" mimeType="audio/mp4" lang="hi">
                  <Representation bandwidth="128000" />
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
        val info = Manifest.analyze(mpd, "https://cdn.example/manifest.mpd", MediaKind.DASH)
        assertEquals(2160, info.height)
        assertEquals(listOf("Hindi"), info.audio)
    }

    @Test
    fun undeterminedLanguageFallsBackToName() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="English",LANGUAGE="und",URI="en.m3u8"
            #EXT-X-STREAM-INF:RESOLUTION=1280x720,AUDIO="a"
            720.m3u8
        """.trimIndent()
        val info = Manifest.analyze(text, "https://cdn.example/m.m3u8", MediaKind.HLS)
        assertEquals(listOf("English"), info.audio)
    }

    @Test
    fun emptyInputIsSafe() {
        assertEquals(0, Manifest.analyze(null, "", MediaKind.HLS).height)
        assertEquals(0, Manifest.analyze("", "", MediaKind.DASH).height)
        assertEquals(0, Manifest.analyze("not a manifest", "", MediaKind.FILE).height)
    }
}
