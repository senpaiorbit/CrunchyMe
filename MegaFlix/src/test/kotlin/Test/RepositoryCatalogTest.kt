package Test

import com.megaflix.RepositoryCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepositoryCatalogTest {
    @Test
    fun parsesBothStringAndObjectEntries() {
        val entries = RepositoryCatalog.parseCatalog(
            """
            [
              "https://example.com/plain/repo.json",
              {"url":"https://example.com/verified/repo.json","verified":true},
              {"url":"ftp://ignored.example/repo.json"},
              {"notUrl":"https://ignored.example/repo.json"},
              "https://example.com/plain/repo.json"
            ]
            """.trimIndent(),
        )
        assertEquals(2, entries.size)
        assertEquals("https://example.com/plain/repo.json", entries[0].url)
        assertTrue(entries[1].verified)
    }

    @Test
    fun invalidJsonStartsEmpty() {
        assertTrue(RepositoryCatalog.parseCatalog("not json").isEmpty())
    }
}
