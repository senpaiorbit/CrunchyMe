package test

import com.crunchyme.Tvdb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions

class LiveTvdbTest {

    @Test
    fun animeSeriesUsesEnglishTranslation() = runBlocking {
        val series = Tvdb.series(1429, "tt2560140", 267440)
        Assumptions.assumeTrue(series != null, "TheTVDB is unreachable")
        assertEquals("Attack on Titan", series!!.name)
    }

    @Test
    fun animeEpisodesUseEnglishNames() = runBlocking {
        val episodes = Tvdb.episodes(1429, "tt2560140", 267440, listOf(1))
        Assumptions.assumeTrue(!episodes.isNullOrEmpty(), "TheTVDB is unreachable")
        assertEquals("To You, in 2,000 Years: The Fall of Shiganshina (1)", episodes!!.first().name)
    }
}
