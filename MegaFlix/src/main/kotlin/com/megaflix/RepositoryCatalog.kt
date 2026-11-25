package com.megaflix

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

data class CatalogEntry(val url: String, val verified: Boolean = false)

data class InstallReport(
    val total: Int,
    val added: Int,
    val skipped: Int,
    val failed: Int,
) {
    fun summary(): String = "Added $added, already present $skipped, failed $failed (of $total)"
}

object RepositoryCatalog {
    const val DATABASE_URL =
        "https://raw.githubusercontent.com/recloudstream/cs-repos/master/repos-db.json"

    private const val TIMEOUT_MS = 15_000L

    fun parseCatalog(text: String): List<CatalogEntry> {
        val array = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                when (val node = array.opt(index)) {
                    is String -> if (node.startsWith("http")) add(CatalogEntry(node))
                    is JSONObject -> {
                        val url = node.optString("url")
                        if (url.startsWith("http")) {
                            add(CatalogEntry(url, node.optBoolean("verified", false)))
                        }
                    }
                }
            }
        }.distinctBy { it.url }
    }
    suspend fun catalog(): List<CatalogEntry> = withContext(Dispatchers.IO) {
        runCatching {
            parseCatalog(app.get(DATABASE_URL, cacheTime = 60).text)
        }.getOrDefault(emptyList())
    }

    suspend fun installedUrls(): Set<String> = withContext(Dispatchers.IO) {
        RepositoryManager.getRepositories().map { it.url }.toSet()
    }

    suspend fun installAll(
        onProgress: (done: Int, total: Int, label: String) -> Unit = { _, _, _ -> },
    ): InstallReport = withContext(Dispatchers.IO) {
        val entries = catalog()
        val installed = installedUrls().toMutableSet()
        var added = 0
        var skipped = 0
        var failed = 0

        entries.forEachIndexed { index, entry ->
            onProgress(index, entries.size, entry.url)
            if (entry.url in installed) {
                skipped++
                return@forEachIndexed
            }
            val repository = withTimeoutOrNull(TIMEOUT_MS) {
                runCatching { RepositoryManager.parseRepository(entry.url) }.getOrNull()
            }
            if (repository == null) {
                failed++
                return@forEachIndexed
            }
            val ok = runCatching {
                RepositoryManager.addRepository(
                    RepositoryData(repository.iconUrl, repository.name, entry.url),
                )
            }.isSuccess
            if (ok) {
                installed += entry.url
                added++
            } else {
                failed++
            }
        }
        onProgress(entries.size, entries.size, "Done")
        InstallReport(entries.size, added, skipped, failed)
    }

    suspend fun ensureAllInstalled() {
        if (!Settings.autoInstallRepos()) return
        runCatching { installAll() }
    }
}
