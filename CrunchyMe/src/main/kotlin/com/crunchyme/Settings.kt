package com.crunchyme

import android.content.Context
import android.content.SharedPreferences

object Settings {

    const val TMDB_LINK = "https://www.themoviedb.org/settings/api"
    const val DEFAULT_FLOOR = 720

    private const val PREFS = "crunchyme_settings"
    private const val KEY_TMDB = "tmdb_key"
    private const val KEY_FLOOR = "quality_floor"
    private const val KEY_ARCHIVE = "archive_enabled"
    private const val KEY_STREMIO = "stremio_enabled"
    private const val KEY_ADDONS = "addons"
    private const val KEY_PROVIDERS = "providers"

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isReady(): Boolean = appContext != null

    private fun prefs(): SharedPreferences? =
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tmdbKey(): String? = prefs()
        ?.getString(KEY_TMDB, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    fun saveTmdbKey(value: String) {
        prefs()?.edit()?.putString(KEY_TMDB, value.trim())?.apply()
    }

    fun isValidTmdbKey(value: String): Boolean {
        val key = value.trim()
        return key.length >= 32 && !key.startsWith("eyJ")
    }

    fun qualityFloor(): Int = prefs()?.getInt(KEY_FLOOR, DEFAULT_FLOOR) ?: DEFAULT_FLOOR

    fun saveQualityFloor(value: Int) {
        prefs()?.edit()?.putInt(KEY_FLOOR, value)?.apply()
    }

    fun archiveEnabled(): Boolean = prefs()?.getBoolean(KEY_ARCHIVE, true) ?: true

    fun saveArchiveEnabled(value: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_ARCHIVE, value)?.apply()
    }

    fun stremioEnabled(): Boolean = prefs()?.getBoolean(KEY_STREMIO, true) ?: true

    fun saveStremioEnabled(value: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_STREMIO, value)?.apply()
    }

    fun addonUrls(): List<String> = prefs()
        ?.getString(KEY_ADDONS, "")
        .orEmpty()
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()

    fun saveAddonUrls(urls: List<String>) {
        prefs()?.edit()?.putString(KEY_ADDONS, urls.distinct().joinToString("\n"))?.apply()
    }

    fun addAddon(raw: String): String? {
        val url = StremioSource.normalize(raw) ?: return null
        val current = addonUrls()
        if (current.any { it.equals(url, ignoreCase = true) }) return null
        saveAddonUrls(current + url)
        return url
    }

    fun removeAddon(url: String) {
        saveAddonUrls(addonUrls().filterNot { it == url })
    }

    fun providers(): List<ProviderTemplate> =
        ProviderTemplates.decode(prefs()?.getString(KEY_PROVIDERS, null))

    fun saveProviders(providers: List<ProviderTemplate>) {
        prefs()?.edit()?.putString(KEY_PROVIDERS, ProviderTemplates.encode(providers))?.apply()
    }

    fun setProviderEnabled(name: String, enabled: Boolean) {
        saveProviders(providers().map { if (it.name == name) it.copy(enabled = enabled) else it })
    }

    fun removeProvider(name: String) {
        saveProviders(providers().filterNot { it.name == name })
    }

    fun sourceConfig(): SourceConfig = SourceConfig(
        archiveEnabled = archiveEnabled(),
        stremioEnabled = stremioEnabled(),
        addonUrls = addonUrls(),
        providers = providers(),
    )
}
