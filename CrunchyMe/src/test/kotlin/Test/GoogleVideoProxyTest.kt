package Test

import com.crunchyme.GoogleVideoProxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleVideoProxyTest {

    @Test
    fun recognizesGoogleVideoHostsOnly() {
        assertTrue(GoogleVideoProxy.isGoogleVideo("https://video-downloads.googleusercontent.com/abc"))
        assertTrue(GoogleVideoProxy.isGoogleVideo("https://sub.video-downloads.googleusercontent.com/abc"))
        assertFalse(GoogleVideoProxy.isGoogleVideo("https://drive.google.com/file/d/abc"))
        assertFalse(GoogleVideoProxy.isGoogleVideo("https://example.com/video-downloads.googleusercontent.com"))
        assertFalse(GoogleVideoProxy.isGoogleVideo(""))
    }

    @Test
    fun fullRequestIsNotPartial() {
        val range = assertNotNull(GoogleVideoProxy.rangeFor(1000, null))
        assertEquals(0, range.start)
        assertEquals(999, range.end)
        assertFalse(range.partial)
    }

    @Test
    fun parsesOpenEndedAndBoundedRanges() {
        val open = assertNotNull(GoogleVideoProxy.rangeFor(1000, "bytes=500-"))
        assertEquals(500, open.start)
        assertEquals(999, open.end)
        assertTrue(open.partial)

        val bounded = assertNotNull(GoogleVideoProxy.rangeFor(1000, "bytes=100-199"))
        assertEquals(100, bounded.start)
        assertEquals(199, bounded.end)
        assertTrue(bounded.partial)
    }

    @Test
    fun clampsRangeEndToFileSize() {
        val range = assertNotNull(GoogleVideoProxy.rangeFor(1000, "bytes=900-5000"))
        assertEquals(900, range.start)
        assertEquals(999, range.end)
    }

    @Test
    fun parsesSuffixRange() {
        val range = assertNotNull(GoogleVideoProxy.rangeFor(1000, "bytes=-100"))
        assertEquals(900, range.start)
        assertEquals(999, range.end)
    }

    @Test
    fun rejectsUnsatisfiableOrMalformedRanges() {
        assertNull(GoogleVideoProxy.rangeFor(1000, "bytes=1000-"))
        assertNull(GoogleVideoProxy.rangeFor(1000, "bytes=500-400"))
        assertNull(GoogleVideoProxy.rangeFor(1000, "bytes=-0"))
        assertNull(GoogleVideoProxy.rangeFor(1000, "items=0-10"))
        assertNull(GoogleVideoProxy.rangeFor(0, null))
    }
}
