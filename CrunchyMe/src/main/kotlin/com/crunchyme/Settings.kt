package com.crunchyme

import android.content.Context
import android.content.SharedPreferences

object Settings {

    const val TMDB_LINK = "https://www.themoviedb.org/settings/api"
    const val OMDB_LINK = "https://www.omdbapi.com/apikey.aspx"
    const val DEFAULT_FLOOR = 720
    const val DEFAULT_PARALLEL = 8
    const val MAX_PARALLEL = 10

    private const val PREFS = "crunchyme_settings"
    private const val KEY_TMDB = "tmdb_key"
    private const val KEY_CATALOG = "catalog_provider"
    private const val KEY_OMDB = "omdb_key"
    private const val KEY_FLOOR = "quality_floor"
    private const val KEY_ARCHIVE = "archive_enabled"
    private const val KEY_STREMIO = "stremio_enabled"
    private const val KEY_ADDONS = "addons"
    private const val KEY_PROVIDERS = "providers"
    private const val KEY_SERVERS = "servers"
    private const val KEY_PRIORITY = "server_priority"
    private const val KEY_PARALLEL = "parallel_tasks"
    private const val KEY_SERVER_SCHEMA = "server_schema"
    private const val KEY_BTTR_POSTERS = "btttr_posters"
    private const val KEY_TVDB_EPISODES = "tvdb_episodes"
    private const val SERVER_SCHEMA = 2
    private const val SECTION_PRIORITY_PREFIX = "server_priority_"

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

    fun catalogProvider(): CatalogProvider = when (
        prefs()?.getString(KEY_CATALOG, null)?.lowercase()
    ) {
        "imdb" -> CatalogProvider.IMDB
        else -> CatalogProvider.TMDB
    }

    fun saveCatalogProvider(provider: CatalogProvider) {
        prefs()?.edit()?.putString(KEY_CATALOG, provider.name)?.apply()
        Tmdb.clearCache()
        Omdb.clearCache()
        LinkCache.clear()
    }

    fun isValidTmdbKey(value: String): Boolean {
        val key = value.trim()
        return key.length >= 32 && !key.startsWith("eyJ")
    }

    fun omdbKey(): String = prefs()
        ?.getString(KEY_OMDB, null)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: Omdb.DEFAULT_KEY

    fun saveOmdbKey(value: String) {
        val key = value.trim()
        prefs()?.edit()?.putString(KEY_OMDB, key.ifBlank { Omdb.DEFAULT_KEY })?.apply()
        Omdb.clearCache()
        LinkCache.clear()
    }

    fun isValidOmdbKey(value: String): Boolean = Regex("""^[A-Za-z0-9]{6,32}$""").matches(value.trim())

    fun qualityFloor(): Int = prefs()?.getInt(KEY_FLOOR, DEFAULT_FLOOR) ?: DEFAULT_FLOOR

    fun saveQualityFloor(value: Int) {
        prefs()?.edit()?.putInt(KEY_FLOOR, value)?.apply()
        LinkCache.clear()
    }

    fun btttrPostersEnabled(): Boolean =
        prefs()?.getBoolean(KEY_BTTR_POSTERS, true) ?: true

    fun saveBtttrPostersEnabled(value: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_BTTR_POSTERS, value)?.apply()
        Btttr.clearCache()
    }

    fun tvdbEpisodesEnabled(): Boolean =
        prefs()?.getBoolean(KEY_TVDB_EPISODES, true) ?: true

    fun saveTvdbEpisodesEnabled(value: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_TVDB_EPISODES, value)?.apply()
        Tvdb.clearCache()
    }

    fun archiveEnabled(): Boolean = prefs()?.getBoolean(KEY_ARCHIVE, true) ?: true

    fun saveArchiveEnabled(value: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_ARCHIVE, value)?.apply()
        LinkCache.clear()
    }

    fun stremioEnabled(): Boolean = prefs()?.getBoolean(KEY_STREMIO, true) ?: true

    fun saveStremioEnabled(value: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_STREMIO, value)?.apply()
        LinkCache.clear()
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
        LinkCache.clear()
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
        LinkCache.clear()
    }

    fun setProviderEnabled(name: String, enabled: Boolean) {
        saveProviders(providers().map { if (it.name == name) it.copy(enabled = enabled) else it })
    }

    fun removeProvider(name: String) {
        saveProviders(providers().filterNot { it.name == name })
    }

    fun serverEnabledIds(): Set<String> {
        val store = prefs() ?: return ServerFarm.defaultEnabledIds()
        if (!store.contains(KEY_SERVERS)) return ServerFarm.defaultEnabledIds()
        val saved = store.getStringSet(KEY_SERVERS, emptySet()).orEmpty().toMutableSet()
        if (store.getInt(KEY_SERVER_SCHEMA, 1) < SERVER_SCHEMA) {
            saved += ServerFarm.enabledInSchema2
            store.edit()
                .putStringSet(KEY_SERVERS, HashSet(saved))
                .putInt(KEY_SERVER_SCHEMA, SERVER_SCHEMA)
                .apply()
        }
        return saved
    }

    fun serverEnabled(id: String): Boolean = serverEnabledIds().contains(id)

    fun saveServerEnabled(id: String, enabled: Boolean) {
        val next = serverEnabledIds().toMutableSet()
        if (enabled) next += id else next -= id
        prefs()?.edit()
            ?.putStringSet(KEY_SERVERS, HashSet(next))
            ?.putInt(KEY_SERVER_SCHEMA, SERVER_SCHEMA)
            ?.apply()
        LinkCache.clear()
    }

    fun saveAllServers(enabled: Boolean) {
        val next = if (enabled) ServerFarm.all.map { it.id }.toSet() else emptySet<String>()
        prefs()?.edit()
            ?.putStringSet(KEY_SERVERS, HashSet(next))
            ?.putInt(KEY_SERVER_SCHEMA, SERVER_SCHEMA)
            ?.apply()
        LinkCache.clear()
    }

    private fun legacyServerOrder(): List<String> {
        val known = ServerFarm.all.map { it.id }
        return prefs()?.getString(KEY_PRIORITY, null)
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it in known }
            .distinct()
    }

    fun defaultSectionOrder(category: ServerCategory): List<String> {
        val known = ServerFarm.inCategory(category).map { it.id }
        return ServerFarm.defaultPriorityOrder().filter { it in known }
    }

    /** Saved order for one section, with new providers appended and the old global order as fallback. */
    fun serverSectionPriority(category: ServerCategory): List<String> {
        val known = ServerFarm.inCategory(category).map { it.id }
        val key = SECTION_PRIORITY_PREFIX + category.name.lowercase()
        val saved = prefs()?.getString(key, null)
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it in known }
            .distinct()
        val migrated = legacyServerOrder().filter { it in known }
        val base = saved.ifEmpty { migrated }
        return (base + defaultSectionOrder(category) + known).distinct()
    }

    /** Global order is the concatenation of streaming, file index and animation sections. */
    fun serverPriority(): List<String> =
        ServerCategory.values().flatMap { serverSectionPriority(it) }.distinct()

    fun saveSectionPriority(category: ServerCategory, order: List<String>) {
        val known = ServerFarm.inCategory(category).map { it.id }
        prefs()?.edit()?.putString(
            SECTION_PRIORITY_PREFIX + category.name.lowercase(),
            order.filter { it in known }.distinct().joinToString(","),
        )?.apply()
    }

    fun saveServerPriority(order: List<String>) {
        ServerCategory.values().forEach { category ->
            val ids = order.filter { ServerFarm.categoryOf(it) == category }
            saveSectionPriority(category, ids)
        }
    }

    /** Apply a draft from the providers screen in one write. Order-only edits keep the link cache. */
    fun saveServerState(enabled: Set<String>, order: List<String>) {
        val orders = ServerCategory.values().associateWith { category ->
            order.filter { ServerFarm.categoryOf(it) == category }
        }
        saveServerState(enabled, orders)
    }

    fun saveServerState(enabled: Set<String>, orders: Map<ServerCategory, List<String>>) {
        val oldEnabled = serverEnabledIds()
        val oldOrders = ServerCategory.values().associateWith { serverSectionPriority(it) }
        val normalized = ServerCategory.values().associateWith { category ->
            val known = ServerFarm.inCategory(category).map { it.id }
            (orders[category].orEmpty().filter { it in known } + defaultSectionOrder(category) + known)
                .distinct()
        }
        if (oldEnabled == enabled && oldOrders == normalized) return
        val known = ServerFarm.all.map { it.id }
        val editor = prefs()?.edit() ?: return
        editor.putStringSet(KEY_SERVERS, HashSet(enabled.filter { it in known }))
        normalized.forEach { (category, order) ->
            editor.putString(
                SECTION_PRIORITY_PREFIX + category.name.lowercase(),
                order.joinToString(","),
            )
        }
        editor.putInt(KEY_SERVER_SCHEMA, SERVER_SCHEMA)
        editor.apply()
        if (oldEnabled != enabled) LinkCache.clear()
    }

    fun moveServer(id: String, delta: Int) {
        val category = ServerFarm.categoryOf(id)
        val order = serverSectionPriority(category).toMutableList()
        val index = order.indexOf(id)
        if (index < 0) return
        val target = (index + delta).coerceIn(0, order.lastIndex)
        if (target == index) return
        val item = order.removeAt(index)
        order.add(target, item)
        saveSectionPriority(category, order)
    }

    fun resetServerPriority() {
        val editor = prefs()?.edit() ?: return
        editor.remove(KEY_PRIORITY)
        ServerCategory.values().forEach { category ->
            editor.remove(SECTION_PRIORITY_PREFIX + category.name.lowercase())
        }
        editor.apply()
        LinkCache.clear()
    }

    fun parallelLevel(): Int =
        (prefs()?.getInt(KEY_PARALLEL, DEFAULT_PARALLEL) ?: DEFAULT_PARALLEL)
            .coerceIn(1, MAX_PARALLEL)

    fun saveParallelLevel(value: Int) {
        prefs()?.edit()?.putInt(KEY_PARALLEL, value.coerceIn(1, MAX_PARALLEL))?.apply()
    }

    fun enabledServers(): List<ServerSpec> {
        val order = serverPriority().withIndex().associate { it.value to it.index }
        return ServerFarm.all.filter { serverEnabled(it.id) }
            .sortedBy { order[it.id] ?: Int.MAX_VALUE }
    }

    fun sourceConfig(): SourceConfig = SourceConfig(
        archiveEnabled = archiveEnabled(),
        stremioEnabled = stremioEnabled(),
        addonUrls = addonUrls(),
        providers = providers(),
        servers = enabledServers(),
        parallelism = parallelLevel(),
    )
}
