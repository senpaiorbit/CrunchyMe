package com.megaflix

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import java.util.concurrent.ConcurrentHashMap

object ProviderHealth {
    private val failures = ConcurrentHashMap<String, Int>()

    fun penalty(name: String): Int = failures[name] ?: 0

    fun recordSuccess(name: String) {
        failures.computeIfPresent(name) { _, value -> (value - 1).coerceAtLeast(0) }
    }

    fun recordFailure(name: String) {
        failures.compute(name) { _, value -> ((value ?: 0) + 1).coerceAtMost(12) }
    }

    fun reset() = failures.clear()

    fun snapshot(): Map<String, Int> = failures.toMap()
}

object ProviderRegistry {
    private val ignoredNames = setOf("MegaFlix", "None", "Random", "NONE")

    /**
     * These providers only provide catalog metadata or redirects, not playable sources.
     * They are hidden by default to keep search results useful, but the user can opt in.
     */
    private val catalogNames = setOf(
        "TmdbProvider",
        "Tmdb",
        "TraktProvider",
        "Trakt",
        "MyDramaList",
        "CrossTmdbProvider",
        "SyncRedirector",
        "AniyomiProvider",
    )

    fun all(): List<MainAPI> =
        APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }

    fun isCatalog(name: String): Boolean = name in catalogNames

    fun selectable(self: MainAPI? = null): List<MainAPI> =
        all()
            .asSequence()
            .filter { it !== self }
            .filter { it.name.isNotBlank() && it.name !in ignoredNames }
            .distinctBy { it.name + "|" + it.mainUrl }
            .sortedWith(compareBy({ ProviderHealth.penalty(it.name) }, { it.name.lowercase() }))
            .toList()

    fun knownNames(self: MainAPI? = null): Set<String> =
        selectable(self).map { it.name }.toSet()

    fun candidates(self: MainAPI? = null): List<MainAPI> {
        return selectable(self)
            .filter { Settings.providerEnabled(it.name) }
            .filter { Settings.includeCatalogProviders() || !isCatalog(it.name) }
            .take(Settings.maxProviders())
    }

    fun resolve(ref: ProviderRef): MainAPI? {
        val exact = all().firstOrNull {
            it.name == ref.provider && (ref.mainUrl.isBlank() || it.mainUrl == ref.mainUrl)
        }
        return exact ?: APIHolder.getApiFromNameNull(ref.provider)
    }

    fun homeProviders(self: MainAPI?, limit: Int): List<MainAPI> {
        if (limit <= 0) return emptyList()
        return selectable(self)
            .filter { Settings.providerEnabled(it.name) }
            .filter { Settings.includeCatalogProviders() || !isCatalog(it.name) }
            .filter { it.hasMainPage }
            .filter { provider ->
                runCatching { provider.mainPage }.getOrNull()?.any { row ->
                    row.data.isNotBlank() && row.data != "NONE"
                } == true
            }
            .take(limit)
    }
}
