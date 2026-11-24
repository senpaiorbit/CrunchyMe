package test

import com.crunchyme.ArchiveSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchiveParseTest {

    private val search = """
        {
          "response": {
            "numFound": 2,
            "docs": [
              {"identifier": "nosferatu_1922", "title": "Nosferatu", "year": "1922"},
              {"identifier": "nosferatu_1979", "title": "Nosferatu the Vampyre", "year": 1979}
            ]
          }
        }
    """.trimIndent()

    @Test
    fun parsesSearchDocs() {
        val items = ArchiveSource.parseSearch(search)
        assertEquals(2, items.size)
        assertEquals("nosferatu_1922", items[0].identifier)
        assertEquals(1922, items[0].year)
    }

    @Test
    fun ranksByTitleThenYear() {
        val items = ArchiveSource.parseSearch(search)
        val ranked = ArchiveSource.rank(items, "Nosferatu", 1922)
        assertEquals("nosferatu_1922", ranked.first().identifier)
    }

    @Test
    fun parsesOnlyPlayableFiles() {
        val metadata = """
            {
              "metadata": {"language": "eng"},
              "files": [
                {"name": "Nosferatu.mp4", "format": "h.264", "height": "1080", "size": "1500000000"},
                {"name": "Nosferatu.ia.mp4", "format": "h.264", "height": "1080"},
                {"name": "trailer.mp4", "format": "h.264", "height": "720"},
                {"name": "poster.jpg", "format": "JPEG"},
                {"name": "notes.txt", "format": "Text"}
              ]
            }
        """.trimIndent()
        val candidates = ArchiveSource.parseFiles(metadata, "nosferatu_1922")
        assertEquals(1, candidates.size)
        val candidate = candidates.first()
        assertEquals(1080, candidate.qualityHint)
        assertEquals("English", candidate.audioLabel)
        assertEquals("1.4 GB", candidate.sizeLabel)
        assertEquals(
            "https://archive.org/download/nosferatu_1922/Nosferatu.mp4",
            candidate.url,
        )
        assertEquals("https://archive.org/", candidate.referer)
    }

    @Test
    fun encodesSpacesInFileNames() {
        val metadata = """{"files":[{"name":"My Movie (1922).mp4","format":"MPEG4","height":720}]}"""
        val candidate = ArchiveSource.parseFiles(metadata, "my_item").first()
        assertTrue(candidate.url.contains("My%20Movie%20%281922%29.mp4"))
    }

    @Test
    fun searchUrlEncodesTheQuery() {
        val url = ArchiveSource.searchUrl("Nosferatu (1922)")
        assertTrue(url.startsWith("https://archive.org/advancedsearch.php?q="))
        assertTrue(url.contains("output=json"))
    }

    @Test
    fun brokenInputIsSafe() {
        assertTrue(ArchiveSource.parseSearch("nope").isEmpty())
        assertTrue(ArchiveSource.parseFiles(null, "x").isEmpty())
    }
}
