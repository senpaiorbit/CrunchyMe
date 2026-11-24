package test

import com.crunchyme.ArchiveSource
import com.crunchyme.LinkRequest
import com.crunchyme.MediaKind
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class LiveArchiveTest {

    @Test
    fun resolvesAPublicDomainFilm() = runBlocking {
        val request = LinkRequest(
            tmdbId = 653,
            imdbId = null,
            type = "movie",
            season = -1,
            episode = -1,
            title = "Nosferatu",
            year = 1922,
            originalLanguage = "de",
        )
        val candidates = ArchiveSource.resolve(request)
        assertTrue(candidates.isNotEmpty(), "Internet Archive returned no files")
        assertTrue(candidates.all { it.url.startsWith("https://archive.org/download/") })
        assertTrue(candidates.any { it.qualityHint >= 720 }, "no 720p+ file found")
        assertTrue(candidates.all { it.kind == MediaKind.FILE })
    }

    @Test
    fun seriesRequestsAreSkipped() = runBlocking {
        val request = LinkRequest(1399, null, "tv", 1, 1, "Game of Thrones", 2011, "en")
        assertTrue(ArchiveSource.resolve(request).isEmpty())
    }
}
