package Test

import com.megaflix.MassCatalog
import com.megaflix.MassInstaller
import com.megaflix.MassPlugin
import kotlin.test.Test
import kotlin.test.assertEquals

class MassInstallerTest {
    private fun plugin(
        name: String,
        version: Int,
        status: Int = 1,
        size: Long = 100,
    ) = MassPlugin(
        name = name,
        internalName = name,
        url = "https://example.test/$name.cs3",
        repositoryUrl = "https://example.test/repo.json",
        version = version,
        status = status,
        language = "en",
        fileSize = size,
        fileHash = null,
    )

    @Test
    fun dedupeKeepsHighestVersionOfEachInternalName() {
        val merged = MassInstaller.dedupe(
            listOf(
                plugin("Same", 3),
                plugin("same", 7),
                plugin("Other", 2),
            ),
        )

        assertEquals(2, merged.size)
        assertEquals("same", merged.first { it.name.equals("Same", true) }.internalName)
        assertEquals(7, merged.first { it.name.equals("Same", true) }.version)
    }

    @Test
    fun catalogTotalBytesSumsProviderSizes() {
        val catalog = MassCatalog(
            repositoryCount = 2,
            plugins = listOf(plugin("A", 1, size = 2_000), plugin("B", 1, size = 3_000)),
            skippedDown = 1,
        )

        assertEquals(5_000, catalog.totalBytes)
    }
}
