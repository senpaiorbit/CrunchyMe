package test

import com.crunchyme.JsonWalk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.json.JSONObject

class JsonWalkTest {

    @Test
    fun findsNestedSourcesAndReadsMetadata() {
        val body = """
            {
              "sources": {
                "auto": {
                  "url": "https:\/\/cdn.example\/1080\/index.m3u8",
                  "quality": "1080p",
                  "language": "hi"
                }
              },
              "tracks": [
                {"file": "https://cdn.example/subs/en.vtt", "label": "English"}
              ]
            }
        """.trimIndent()
        val hits = JsonWalk.harvest(JSONObject(body), "https://api.example/embed/1")
        assertEquals(1, hits.size)
        assertEquals("https://cdn.example/1080/index.m3u8", hits[0].url)
        assertEquals(1080, hits[0].quality)
        assertEquals("Hindi", hits[0].language)
    }

    @Test
    fun walksArraysAndResolvesRelativeUrls() {
        val body = """
            {"data": [
              {"file": "/media/720/play.m3u8", "label": "720p"},
              {"file": "https://cdn.example/480.mp4", "label": "480p"}
            ]}
        """.trimIndent()
        val hits = JsonWalk.harvest(JSONObject(body), "https://cdn.example/api/list")
        assertEquals(2, hits.size)
        assertTrue(hits.any { it.url == "https://cdn.example/media/720/play.m3u8" })
        assertTrue(hits.any { it.quality == 720 })
    }

    @Test
    fun extractsUrlsFromPlainText() {
        val urls = JsonWalk.mediaUrls(
            """{"file":"https:\/\/cdn.example\/x\/master.m3u8?token=abc\u0026exp=1"}""",
        )
        assertEquals(listOf("https://cdn.example/x/master.m3u8?token=abc&exp=1"), urls)
    }

    @Test
    fun ignoresNonMediaUrls() {
        val body = """{"poster":"https://cdn.example/poster.jpg","page":"https://cdn.example/watch"}"""
        assertTrue(JsonWalk.harvest(JSONObject(body), "https://cdn.example/").isEmpty())
    }
}
