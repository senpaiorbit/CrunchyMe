package test

import com.crunchyme.LinkRequest
import com.crunchyme.ProviderTemplate
import com.crunchyme.ProviderTemplates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderTemplatesTest {

    private val movie = LinkRequest(27205, "tt1375666", "movie", -1, -1, "Inception", 2010, "en")
    private val episode = LinkRequest(1399, "tt0944947", "tv", 1, 2, "Game of Thrones", 2011, "en")

    @Test
    fun jsonRoundTripKeepsEveryField() {
        val original = listOf(
            ProviderTemplate(
                name = "Example",
                movieUrl = "https://host/e/{tmdb}",
                tvUrl = "https://host/e/{tmdb}/{season}/{episode}",
                referer = "https://host/",
                json = true,
                enabled = false,
                headers = mapOf("X-Test" to "1"),
            ),
        )
        val decoded = ProviderTemplates.decode(ProviderTemplates.encode(original))
        assertEquals(original, decoded)
    }

    @Test
    fun lineFormatIsAccepted() {
        val decoded = ProviderTemplates.decode(
            """
                # comment
                Host A | https://a/e/{tmdb} | https://a/e/{tmdb}/{season}/{episode} | https://a/ | json
            """.trimIndent(),
        )
        assertEquals(1, decoded.size)
        assertEquals("Host A", decoded[0].name)
        assertTrue(decoded[0].json)
        assertEquals("https://a/", decoded[0].referer)
    }

    @Test
    fun substitutesTokens() {
        assertEquals(
            "https://h/27205/tt1375666/movie",
            ProviderTemplates.substitute("https://h/{tmdb}/{imdb}/{type}", movie),
        )
        assertEquals(
            "https://h/Game%20of%20Thrones/2011/1/2",
            ProviderTemplates.substitute("https://h/{title}/{year}/{season}/{episode}", episode),
        )
    }

    @Test
    fun imdbTokenRequiresAnImdbId() {
        assertNull(ProviderTemplates.substitute("https://h/{imdb}", movie.copy(imdbId = null)))
    }

    @Test
    fun missingTvTemplateMeansNoTvSource() {
        val template = ProviderTemplate(name = "Movie only", movieUrl = "https://h/{tmdb}")
        assertNull(ProviderTemplates.urlFor(template, episode))
        assertEquals("https://h/27205", ProviderTemplates.urlFor(template, movie))
    }

    @Test
    fun invalidJsonDecodesToEmptyList() {
        assertTrue(ProviderTemplates.decode("{not json").isEmpty())
        assertTrue(ProviderTemplates.decode(null).isEmpty())
    }
}
