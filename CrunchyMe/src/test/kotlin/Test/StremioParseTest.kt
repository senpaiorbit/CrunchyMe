package test

import com.crunchyme.MediaKind
import com.crunchyme.StremioSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StremioParseTest {

    private val manifestBody = """
        {
          "id": "org.example.addon",
          "name": "Example Addon",
          "types": ["movie", "series"],
          "resources": [
            {"name": "stream", "types": ["movie", "series"], "idPrefixes": ["tt"]},
            {"name": "catalog", "types": ["movie"]}
          ],
          "idPrefixes": ["tt"]
        }
    """.trimIndent()

    @Test
    fun normalizesAddonUrls() {
        assertEquals("https://addon.example", StremioSource.normalize("stremio://addon.example/manifest.json"))
        assertEquals("https://addon.example", StremioSource.normalize("addon.example/"))
        assertEquals("https://addon.example", StremioSource.normalize("https://addon.example/manifest.json"))
        assertNull(StremioSource.normalize("not a host"))
        assertNull(StremioSource.normalize(""))
    }

    @Test
    fun parsesManifestCapabilities() {
        val manifest = StremioSource.parseManifest("https://addon.example", manifestBody)!!
        assertEquals("Example Addon", manifest.name)
        assertTrue(StremioSource.supports(manifest, "movie"))
        assertTrue(StremioSource.supports(manifest, "tv"))
    }

    @Test
    fun rejectsAddonsWithoutStreamResource() {
        val manifest = StremioSource.parseManifest(
            "https://addon.example",
            """{"name":"Catalog only","types":["movie"],"resources":["catalog"]}""",
        )!!
        assertFalse(StremioSource.supports(manifest, "movie"))
    }

    @Test
    fun buildsProtocolUrls() {
        assertEquals(
            "https://addon.example/stream/movie/tt1375666.json",
            StremioSource.streamUrl("https://addon.example", "movie", "tt1375666", -1, -1),
        )
        assertEquals(
            "https://addon.example/stream/series/tt0944947:1:2.json",
            StremioSource.streamUrl("https://addon.example", "tv", "tt0944947", 1, 2),
        )
    }

    @Test
    fun parsesDirectStreamsAndSkipsTorrents() {
        val body = """
            {
              "streams": [
                {
                  "url": "https://cdn.example/1080/index.m3u8",
                  "name": "1080p",
                  "title": "Hindi",
                  "behaviorHints": {"proxyHeaders": {"request": {"Referer": "https://ref.example/"}}}
                },
                {"infoHash": "abc123", "title": "Torrent 1080p"},
                {"url": "https://cdn.example/file.mp4", "behaviorHints": {"filename": "Movie.2024.720p.WEB-DL.mkv", "videoSize": 734003200}}
              ]
            }
        """.trimIndent()
        val candidates = StremioSource.parseStreams(body, "Example Addon")
        assertEquals(2, candidates.size)

        val hls = candidates.first { it.kind == MediaKind.HLS }
        assertEquals(1080, hls.qualityHint)
        assertEquals("Hindi", hls.audioLabel)
        assertEquals("https://ref.example/", hls.referer)
        assertEquals("https://ref.example/", hls.headers["Referer"])

        val file = candidates.first { it.kind == MediaKind.FILE }
        assertEquals(720, file.qualityHint)
        assertEquals("700 MB", file.sizeLabel)
    }

    @Test
    fun emptyPayloadsAreSafe() {
        assertTrue(StremioSource.parseStreams(null, "x").isEmpty())
        assertTrue(StremioSource.parseStreams("{}", "x").isEmpty())
        assertNull(StremioSource.parseManifest("https://a.example", "nope"))
    }
}
