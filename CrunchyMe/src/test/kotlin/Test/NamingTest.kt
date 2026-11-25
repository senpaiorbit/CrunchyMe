package test

import com.crunchyme.Naming
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NamingTest {

    @Test
    fun detectsDeclaredLanguages() {
        assertEquals(listOf("Hindi", "English"), Naming.languages("Hindi + English"))
        assertEquals(listOf("Tamil", "Telugu"), Naming.languages("TAMIL | TELUGU"))
        assertEquals(listOf("Hindi"), Naming.languages("हिंदी"))
        assertEquals(listOf("English"), Naming.languages("en-US"))
        assertEquals("Hindi", Naming.fromCode("hi"))
    }

    @Test
    fun doesNotMistakeShortWordsForCodes() {
        assertTrue(Naming.languages("It").isEmpty())
        assertTrue(Naming.languages("Up").isEmpty())
        assertTrue(Naming.languages("Us").isEmpty())
        assertTrue(Naming.languages("No").isEmpty())
    }

    @Test
    fun displayLabelCapsAtThreeThenMulti() {
        assertEquals("Hindi", Naming.displayLabel("Hindi"))
        assertEquals("Hindi, English", Naming.displayLabel("Hindi, English"))
        assertEquals("Hindi, English, Tamil", Naming.displayLabel("Hindi, English, Tamil"))
        assertEquals("Multi", Naming.displayLabel("Hindi, English, Tamil, Telugu"))
        assertEquals("Multi", Naming.displayLabel("Dual Audio"))
        assertEquals("", Naming.displayLabel(""))
    }

    @Test
    fun originalLanguageIsUsedForOriginalMarker() {
        assertEquals("Hindi", Naming.displayLabel("Original", "hi"))
        assertEquals("Original", Naming.displayLabel("Original", null))
    }

    @Test
    fun parsesQualityAndSize() {
        assertEquals(1080, Naming.qualityHeight("1080p WEB-DL"))
        assertEquals(2160, Naming.qualityHeight("4K HDR"))
        assertEquals(1440, Naming.qualityHeight("2K"))
        assertEquals(0, Naming.qualityHeight("WEB-DL"))
        assertEquals("4K", Naming.qualityName(2160))
        assertEquals("1080p", Naming.qualityName(1080))
        assertEquals("2.4 GB", Naming.sizeLabel("2.4GB"))
        assertEquals("700 MB", Naming.sizeLabel("700 MB"))
    }

    @Test
    fun prefersEnglishWhenOneValueIsJapanese() {
        assertEquals("Attack on Titan", Naming.preferEnglish("進撃の巨人", "Attack on Titan"))
        assertEquals("Attack on Titan", Naming.preferEnglish("Attack on Titan", "進撃の巨人"))
        assertEquals("進撃の巨人", Naming.preferEnglish("進撃の巨人", "進撃の巨人"))
        assertEquals("English", Naming.preferEnglish("English", null))
        assertEquals("English", Naming.preferEnglish(null, "English"))
    }

    @Test
    fun buildsLinkLabel() {
        assertEquals("1080p · Hindi · 2.4 GB", Naming.linkLabel(1080, "Hindi", "2.4 GB"))
        assertEquals("720p · Hindi", Naming.linkLabel(720, "Hindi", ""))
    }

    @Test
    fun buildsIndStreamStyleDisplayNames() {
        assertEquals("VidLink (Unknown)", Naming.priorityName("VidLink"))
        assertTrue(Naming.displayName("VidLink", audioLabel = "Hindi").startsWith("VidLink (🇮🇳 Hindi)"))
        assertEquals("VidLink (Multi)", Naming.priorityName("VidLink", "Hindi, English, Tamil, Telugu"))
        assertEquals("4KHDHub WEB-DL HEVC (Unknown)", Naming.displayName("4KHDHub", mediaLabel = "WEB-DL HEVC"))
        assertEquals("VidLink (🇮🇳 Hindi)", Naming.displayName("VidLink 720p", "Hindi"))
        assertEquals("English", Naming.canonicalSubtitleName("English SDH"))
        assertEquals("1.9 GB", Naming.bytesLabel(1_900_000_000L))
        assertEquals("777 MB", Naming.bytesLabel(777_303_372L))
    }
}
