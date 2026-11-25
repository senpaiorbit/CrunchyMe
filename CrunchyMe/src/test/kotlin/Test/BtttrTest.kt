package Test

import com.crunchyme.Btttr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BtttrTest {

    @Test
    fun buildsPosterUrlsFromImdb() {
        assertEquals(
            "https://btttr.cc/poster/movie/tt0137523/auto~gr.png?nocache=1",
            Btttr.posterUrl("tt0137523", "movie"),
        )
        assertEquals(
            "https://btttr.cc/poster/series/tt0944947/auto~gr.png?nocache=1",
            Btttr.posterUrl("tt0944947", "tv"),
        )
        assertNull(Btttr.posterUrl("tmdb:550", "movie"))
        assertNull(Btttr.posterByImdb("tt0137523", "movie"))
    }
}
