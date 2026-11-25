package com.megaflix

import android.app.Activity
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class MassPlugin(
    val name: String,
    val internalName: String,
    val url: String,
    val repositoryUrl: String,
    val version: Int,
    val status: Int,
    val language: String?,
    val fileSize: Long,
    val fileHash: String?,
)

data class MassCatalog(
    val repositoryCount: Int,
    val plugins: List<MassPlugin>,
    val skippedDown: Int,
) {
    val totalBytes: Long get() = plugins.sumOf { it.fileSize }
}

data class MassInstallReport(
    val total: Int,
    val installed: Int,
    val skipped: Int,
    val failed: Int,
) {
    fun summary(): String = "Installed $installed, already present $skipped, failed $failed (of $total)"
}

object MassInstaller {
    private val ignoredInternalNames = setOf("megaflix", "crunchyme")

    @Volatile
    private var cached: MassCatalog? = null

    suspend fun discover(force: Boolean = false): MassCatalog = withContext(Dispatchers.IO) {
        cached?.takeUnless { force }?.let { return@withContext it }

        val repositories = RepositoryCatalog.catalog()
        val down = AtomicInteger(0)
        val found = coroutineScope {
            repositories.map { entry ->
                async {
                    val data = RepositoryData("", entry.url.substringAfterLast('/'), entry.url)
                    val wrappers = runCatching {
                        RepositoryManager.getRepoPlugins(data)
                    }.getOrNull().orEmpty()
                    wrappers.mapNotNull { wrapper ->
                        val plugin = wrapper.plugin
                        if (plugin.status == 0) {
                            down.incrementAndGet()
                            return@mapNotNull null
                        }
                        if (plugin.internalName.isBlank() ||
                            plugin.internalName.lowercase() in ignoredInternalNames
                        ) {
                            return@mapNotNull null
                        }
                        MassPlugin(
                            name = plugin.name.ifBlank { plugin.internalName },
                            internalName = plugin.internalName,
                            url = plugin.url,
                            repositoryUrl = entry.url,
                            version = plugin.version,
                            status = plugin.status,
                            language = plugin.language,
                            fileSize = plugin.fileSize ?: 0L,
                            fileHash = plugin.fileHash,
                        )
                    }
                }
            }.awaitAll().flatten()
        }

        val catalog = MassCatalog(
            repositoryCount = repositories.size,
            plugins = dedupe(found),
            skippedDown = down.get(),
        )
        cached = catalog
        catalog
    }

    suspend fun installAll(
        activity: Activity,
        catalog: MassCatalog,
        concurrency: Int = 3,
        onProgress: (done: Int, total: Int, name: String) -> Unit = { _, _, _ -> },
    ): MassInstallReport = withContext(Dispatchers.IO) {
        val existing = PluginManager.getPluginsOnline()
            .groupBy { it.internalName }
            .mapValues { (_, values) -> values.maxByOrNull { it.version } }
        val semaphore = Semaphore(concurrency.coerceIn(1, 8))
        val done = AtomicInteger(0)
        val installed = AtomicInteger(0)
        val skipped = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val total = catalog.plugins.size

        coroutineScope {
            catalog.plugins.map { plugin ->
                async {
                    semaphore.withPermit {
                        val current = existing[plugin.internalName]
                        val present = current != null &&
                            current.version >= plugin.version &&
                            File(current.filePath).exists()
                        if (present) {
                            skipped.incrementAndGet()
                        } else {
                            val ok = runCatching {
                                PluginManager.downloadPlugin(
                                    activity = activity,
                                    pluginUrl = plugin.url,
                                    pluginHash = plugin.fileHash,
                                    internalName = plugin.internalName,
                                    repositoryUrl = plugin.repositoryUrl,
                                    loadPlugin = false,
                                )
                            }.getOrDefault(false)
                            if (ok) installed.incrementAndGet() else failed.incrementAndGet()
                        }
                        val count = done.incrementAndGet()
                        onProgress(count, total, plugin.name)
                    }
                }
            }.awaitAll()
        }

        MassInstallReport(
            total = total,
            installed = installed.get(),
            skipped = skipped.get(),
            failed = failed.get(),
        )
    }

    fun dedupe(plugins: List<MassPlugin>): List<MassPlugin> =
        plugins
            .groupBy { it.internalName.lowercase() }
            .values
            .map { group ->
                group.maxWithOrNull(
                    compareBy<MassPlugin> { it.version }.thenBy { it.status },
                ) ?: group.first()
            }
            .sortedBy { it.name.lowercase() }
}
