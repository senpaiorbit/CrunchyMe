package Test

import com.crunchyme.LinkRequest
import com.crunchyme.ServerFarm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerFarmTest {

    private val movie = LinkRequest(
        tmdbId = 27205,
        imdbId = "tt1375666",
        type = "movie",
        season = -1,
        episode = -1,
        title = "Inception",
        year = 2010,
        originalLanguage = "en",
    )

    private val episode = movie.copy(type = "tv", season = 1, episode = 2)

    @Test
    fun serverIdsAreUnique() {
        val ids = ServerFarm.all.map { it.id }
        assertEquals(21, ids.size)
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun titleKeyedProvidersUseTheTitle() {
        val hdhub = ServerFarm.spec("hdhub4u")!!
        assertEquals(
            "https://new8.hdhub4u.cl/search/Inception/",
            ServerFarm.urlFor(hdhub, movie),
        )
        val moviebox = ServerFarm.spec("moviebox")!!
        assertTrue(ServerFarm.urlFor(moviebox, movie)!!.contains("app_name=moviebox"))
    }

    @Test
    fun buildsMovieAndEpisodeUrls() {
        val vidbolt = ServerFarm.spec("vidbolt")!!
        assertEquals(
            "https://vidbolt.xyz/api/resolve?type=movie&id=27205",
            ServerFarm.urlFor(vidbolt, movie),
        )
        assertEquals(
            "https://vidbolt.xyz/api/resolve?type=tv&id=27205&season=1&episode=2",
            ServerFarm.urlFor(vidbolt, episode),
        )
        val netmirror = ServerFarm.spec("netmirror")!!
        assertEquals(
            "https://net78.cc/api/embed-tmdb/27205?type=tv&se=1&ep=2",
            ServerFarm.urlFor(netmirror, episode),
        )
    }

    @Test
    fun defaultPriorityStartsWithReliableProviders() {
        val order = ServerFarm.defaultPriorityOrder()
        assertEquals(ServerFarm.all.size, order.size)
        assertEquals(order.size, order.distinct().size)
        assertEquals("moviebox", order.first())
        assertTrue(order.indexOf("vidrift") < order.indexOf("4khdhub"))
        assertTrue(order.indexOf("vidrift") < order.indexOf("animeworld"))
    }

    @Test
    fun defaultsEnableTheWorkingServers() {
        val defaults = ServerFarm.defaultEnabledIds()
        listOf(
            "vidy", "vidbolt", "vidly", "vidlink", "moviebox", "netmirror",
            "bingr", "vidrift", "movy", "atlantic", "animeworld", "toonix_anime", "hianime",
        ).forEach { assertTrue(it in defaults, "expected $it to be on by default") }
        listOf("4khdhub", "fsl", "hindmoviez", "hdhub4u", "moviesdrive", "movies4u", "netnaija", "toonix")
            .forEach { assertFalse(it in defaults) }
    }
}
