package Test

import com.crunchyme.Fsl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FslTest {

    @Test
    fun extractsDirectGoogleVideoFromFastdl() {
        val page = """
            <script>
            var reurl = "https://fastdl.zip/dl.php?link=https://video-downloads.googleusercontent.com/ABCdef123";
            </script>
        """.trimIndent()
        assertEquals(
            "https://video-downloads.googleusercontent.com/ABCdef123",
            Fsl.extractFastdlDirect(page),
        )
    }

    @Test
    fun extractsEncodedDirectGoogleVideo() {
        val page = """
            <script>
            var reurl = "https://fastdl.zip/dl.php?link=https%3A%2F%2Fvideo-downloads.googleusercontent.com%2Ftok123";
            </script>
        """.trimIndent()
        assertEquals(
            "https://video-downloads.googleusercontent.com/tok123",
            Fsl.extractFastdlDirect(page),
        )
    }

    @Test
    fun ignoresNonVideoGatewayPages() {
        assertNull(Fsl.extractFastdlDirect("<html><body>No video here</body></html>"))
    }
}
