package Test

import com.crunchyme.Health
import com.crunchyme.Http
import com.crunchyme.HttpEngine
import com.crunchyme.HttpResult
import com.crunchyme.LinkRequest
import com.crunchyme.ProviderTemplate
import com.crunchyme.ServerCategory
import com.crunchyme.SourceConfig
import com.crunchyme.Sources
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SourcesStreamTest {

    private val request = LinkRequest(
        tmdbId = 1,
        imdbId = "tt0000001",
        type = "movie",
        season = -1,
        episode = -1,
        title = "Test",
        year = 2026,
        originalLanguage = "en",
    )

    @Test
    fun animeSectionStartsFirst() {
        assertEquals(
            listOf(ServerCategory.ANIMATION, ServerCategory.STREAMING, ServerCategory.FILE_INDEX),
            Sources.categoryOrder(isAnime = true),
        )
        assertEquals(
            listOf(ServerCategory.STREAMING, ServerCategory.FILE_INDEX, ServerCategory.ANIMATION),
            Sources.categoryOrder(isAnime = false),
        )
    }

    @Test
    fun defaultWorkersSplitBySection() {
        val animation = Sources.workerCounts(8, true, true, true)
        assertEquals(2, animation[ServerCategory.STREAMING])
        assertEquals(2, animation[ServerCategory.FILE_INDEX])
        assertEquals(4, animation[ServerCategory.ANIMATION])

        val noAnimation = Sources.workerCounts(8, true, true, false)
        assertEquals(2, noAnimation[ServerCategory.STREAMING])
        assertEquals(6, noAnimation[ServerCategory.FILE_INDEX])
        assertEquals(0, noAnimation[ServerCategory.ANIMATION])

        val six = Sources.workerCounts(6, true, true, true)
        assertEquals(2, six[ServerCategory.STREAMING])
        assertEquals(1, six[ServerCategory.FILE_INDEX])
        assertEquals(3, six[ServerCategory.ANIMATION])

        val ten = Sources.workerCounts(10, true, true, true)
        assertEquals(2, ten[ServerCategory.STREAMING])
        assertEquals(4, ten[ServerCategory.FILE_INDEX])
        assertEquals(4, ten[ServerCategory.ANIMATION])
    }

    @Test
    fun streamsPriorityOrderWithoutWaitingForSlowSources() = runBlocking {
        val original = Http.engine
        Health.reset()
        try {
            val slowDone = AtomicBoolean(false)
            Http.engine = object : HttpEngine {
                override suspend fun request(
                    url: String,
                    headers: Map<String, String>,
                    timeoutSec: Long,
                    range: String?,
                ): HttpResult {
                    if (url.contains("slow")) {
                        delay(400)
                        slowDone.set(true)
                    }
                    val body = """{"url":"https://cdn.test/${if (url.contains("slow")) "slow" else "fast"}.mp4"}"""
                    return HttpResult(200, body, body.toByteArray(), url, emptyMap())
                }
            }

            val config = SourceConfig(
                archiveEnabled = false,
                stremioEnabled = false,
                providers = listOf(
                    ProviderTemplate("Fast", "https://fast.test/embed", json = true),
                    ProviderTemplate("Slow", "https://slow.test/embed", json = true),
                ),
                parallelism = 1,
            )
            val order = mutableListOf<String>()
            Sources.stream(request, config, {}) { id, items ->
                order += id
                if (order.size == 1) {
                    assert(items.isNotEmpty())
                    assertFalse(slowDone.get(), "fast result waited for slow source")
                }
            }
            assertEquals(listOf("p:fast", "p:slow"), order)
        } finally {
            Http.engine = original
            Health.reset()
        }
    }
}
