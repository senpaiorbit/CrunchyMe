package com.megaflix

import android.content.Context
import android.content.SharedPreferences

enum class SearchScope(
    val label: String,
    val description: String,
    val maxProviders: Int,
    val timeoutMs: Long,
    val concurrency: Int,
) {
    FAST("Fast", "A quick sweep of the most useful providers", 14, 6_000, 6),
    BALANCED("Balanced", "Good coverage without hammering every source", 30, 9_000, 9),
    DEEP("Deep", "Search as much of the installed catalog as possible", 80, 14_000, 14),
}

object Settings {
    private const val PREFS = "megaflix_settings"
    private const val KEY_SCOPE = "scope"
    private const val KEY_MAX_PROVIDERS = "max_providers"
    private const val KEY_TIMEOUT = "timeout_ms"
    private const val KEY_CONCURRENCY = "concurrency"
    private const val KEY_AGGREGATE_LINKS = "aggregate_links"
    private const val KEY_INCLUDE_CATALOG = "include_catalog"
    private const val KEY_INCLUDE_ADULT = "include_adult"
    private const val KEY_SHOW_SOURCE_COUNT = "show_source_count"
    private const val KEY_HOME_ROWS = "home_rows"
    private const val KEY_AUTO_INSTALL_REPOS = "auto_install_repos"
    private const val KEY_MAX_LOAD_PROVIDERS = "max_load_providers"
    private const val KEY_DISABLED_PROVIDERS = "disabled_providers"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun scope(): SearchScope = runCatching {
        SearchScope.valueOf(
            prefs()?.getString(KEY_SCOPE, SearchScope.BALANCED.name) ?: SearchScope.BALANCED.name,
        )
    }.getOrDefault(SearchScope.BALANCED)

    fun saveScope(scope: SearchScope) {
        prefs()?.edit()
            ?.putString(KEY_SCOPE, scope.name)
            ?.putInt(KEY_MAX_PROVIDERS, scope.maxProviders)
            ?.putLong(KEY_TIMEOUT, scope.timeoutMs)
            ?.putInt(KEY_CONCURRENCY, scope.concurrency)
            ?.apply()
    }

    fun maxProviders(): Int =
        (prefs()?.getInt(KEY_MAX_PROVIDERS, scope().maxProviders) ?: scope().maxProviders)
            .coerceIn(1, 200)

    fun saveMaxProviders(value: Int) {
        prefs()?.edit()?.putInt(KEY_MAX_PROVIDERS, value.coerceIn(1, 200))?.apply()
    }

    fun timeoutMs(): Long =
        (prefs()?.getLong(KEY_TIMEOUT, scope().timeoutMs) ?: scope().timeoutMs)
            .coerceIn(2_000, 60_000)

    fun saveTimeoutMs(value: Long) {
        prefs()?.edit()?.putLong(KEY_TIMEOUT, value.coerceIn(2_000, 60_000))?.apply()
    }

    fun concurrency(): Int =
        (prefs()?.getInt(KEY_CONCURRENCY, scope().concurrency) ?: scope().concurrency)
            .coerceIn(1, 32)

    fun saveConcurrency(value: Int) {
        prefs()?.edit()?.putInt(KEY_CONCURRENCY, value.coerceIn(1, 32))?.apply()
    }

    fun aggregateLinks(): Boolean = prefs()?.getBoolean(KEY_AGGREGATE_LINKS, true) ?: true

    fun saveAggregateLinks(value: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_AGGREGATE_LINKS, value)?.apply()
    }

    fun includeCatalogProviders(): Boolean =
        prefs()?.getBoolean(KEY_INCLUDE_CATALOG, false) ?: false

    fun saveIncludeCatalogProviders(value: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_INCLUDE_CATALOG, value)?.apply()
    }

    fun includeAdult(): Boolean = prefs()?.getBoolean(KEY_INCLUDE_ADULT, false) ?: false

    fun saveIncludeAdult(value: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_INCLUDE_ADULT, value)?.apply()
    }

    fun showSourceCount(): Boolean = prefs()?.getBoolean(KEY_SHOW_SOURCE_COUNT, true) ?: true

    fun saveShowSourceCount(value: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_SHOW_SOURCE_COUNT, value)?.apply()
    }

    fun homeRows(): Int = (prefs()?.getInt(KEY_HOME_ROWS, 6) ?: 6).coerceIn(0, 12)

    fun saveHomeRows(value: Int) {
        prefs()?.edit()?.putInt(KEY_HOME_ROWS, value.coerceIn(0, 12))?.apply()
    }

    fun autoInstallRepos(): Boolean =
        prefs()?.getBoolean(KEY_AUTO_INSTALL_REPOS, true) ?: true

    fun saveAutoInstallRepos(value: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_AUTO_INSTALL_REPOS, value)?.apply()
    }

    fun maxLoadProviders(): Int =
        (prefs()?.getInt(KEY_MAX_LOAD_PROVIDERS, 8) ?: 8).coerceIn(1, 16)

    fun saveMaxLoadProviders(value: Int) {
        prefs()?.edit()?.putInt(KEY_MAX_LOAD_PROVIDERS, value.coerceIn(1, 16))?.apply()
    }

    /** Providers explicitly disabled by the user; new providers are enabled by default. */
    fun disabledProviders(): Set<String> {
        val store = prefs() ?: return emptySet()
        return store.getStringSet(KEY_DISABLED_PROVIDERS, emptySet()).orEmpty().toSet()
    }

    fun providerEnabled(name: String): Boolean = name !in disabledProviders()

    fun setProviderEnabled(name: String, enabled: Boolean, @Suppress("UNUSED_PARAMETER") known: Set<String>) {
        val current = disabledProviders().toMutableSet()
        if (enabled) current -= name else current += name
        prefs()?.edit()
            ?.putStringSet(KEY_DISABLED_PROVIDERS, HashSet(current))
            ?.apply()
    }

    fun setAllProviders(enabled: Boolean, known: Set<String>) {
        val edit = prefs()?.edit() ?: return
        val current = disabledProviders().toMutableSet()
        if (enabled) current -= known else current += known
        edit.putStringSet(KEY_DISABLED_PROVIDERS, HashSet(current)).apply()
    }

    fun enabledCount(known: Set<String>): Int = known.count { providerEnabled(it) }

}
