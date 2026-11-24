package com.crunchyme

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

interface StreamSource {
    val id: String
    val name: String
    suspend fun resolve(request: LinkRequest): List<Candidate>
}

data class SourceConfig(
    val archiveEnabled: Boolean = true,
    val stremioEnabled: Boolean = true,
    val addonUrls: List<String> = emptyList(),
    val providers: List<ProviderTemplate> = emptyList(),
    val servers: List<ServerSpec> = emptyList(),
    val parallelism: Int = Settings.DEFAULT_PARALLEL,
)

/** A link returned by the CloudStream host-extractor registry, kept free of app types. */
data class BridgeLink(
    val url: String,
    val referer: String?,
    val quality: Int,
    val type: String,
)

object ExtractorBridge {
    /** Installed by the plugin so pure code can fall back to CloudStream's host extractors. */
    @Volatile
    var loader: suspend (String, String?, (SubtitleTrack) -> Unit, (BridgeLink) -> Unit) -> Boolean =
        { _, _, _, _ -> false }
}

/** User-defined provider: a URL template resolved as an embed page or a JSON API. */
class CustomProviderSource(private val template: ProviderTemplate) : StreamSource {

    override val id: String = providerId(template)
    override val name: String = template.name

    override suspend fun resolve(request: LinkRequest): List<Candidate> {
        val url = ProviderTemplates.urlFor(template, request) ?: return emptyList()
        val headers = Http.withReferer(template.headers, template.referer)
        val started = System.currentTimeMillis()
        val body = Http.text(url, headers, 12) ?: run {
            Logx.w("Custom", "${template.name}: no response")
            throw IllegalStateException("no response")
        }
        if (HtmlHarvest.isChallenge(body)) {
            Logx.w("Custom", "${template.name}: bot challenge, skipped")
            return emptyList()
        }

        val found = if (template.json) fromJson(url, body) else fromHtml(url, body)
        val elapsed = System.currentTimeMillis() - started
        return found
            .map { it.copy(sourceId = id, sourceName = name, speedMs = elapsed) }
            .distinctBy { it.url }
    }

    private fun fromJson(url: String, body: String): List<Candidate> {
        val root = runCatching { JSONObject(body) }.getOrNull()
        val hits = if (root != null) {
            JsonWalk.harvest(root, url)
        } else {
            JsonWalk.mediaUrls(body).map { JsonWalk.Hit(it, 0, "", "", "") }
        }
        return hits.map { hit ->
            val label = Naming.displayLabel(hit.label)
            Candidate(
                sourceId = id,
                sourceName = name,
                url = hit.url,
                kind = kindOf(hit.url),
                referer = template.referer,
                headers = template.headers,
                qualityHint = hit.quality,
                audioLabel = hit.language.ifBlank { label },
                sizeLabel = hit.size,
            )
        }
    }

    private suspend fun fromHtml(url: String, body: String): List<Candidate> =
        EmbedResolver.resolve(
            url = url,
            body = body,
            referer = template.referer,
            headers = template.headers,
            sourceId = id,
            sourceName = name,
        )

    private fun kindOf(url: String, type: String = ""): MediaKind =
        EmbedResolver.kindOf(url, type)

    companion object {
        fun providerId(template: ProviderTemplate): String =
            "p:" + template.name.trim().lowercase().replace(Regex("""\s+"""), "-")
    }
}

object Sources {

    private const val SOURCE_TIMEOUT_MS = 45_000L

    private data class Task(
        val id: String,
        val block: suspend () -> List<Candidate>,
    )

    /**
     * Resolves sources in priority order and calls [onResult] as each one finishes.
     * The first results can reach the player while slower providers are still running.
     */
    suspend fun stream(
        request: LinkRequest,
        config: SourceConfig,
        log: (String) -> Unit = { Logx.d("Sources", it) },
        onResult: suspend (String, List<Candidate>) -> Unit,
    ) = supervisorScope {
        val tasks = buildList {
            for (spec in config.servers) {
                if (!Health.isHealthy(spec.id)) continue
                if (spec.animeOnly && !request.isAnime) continue
                add(Task(spec.id) { BuiltinServerSource(spec).resolve(request) })
            }
            for (provider in config.providers.filter { it.enabled }) {
                val id = CustomProviderSource.providerId(provider)
                if (!Health.isHealthy(id)) continue
                add(Task(id) { CustomProviderSource(provider).resolve(request) })
            }
            if (config.archiveEnabled && Health.isHealthy(ArchiveSource.ID)) {
                add(Task(ArchiveSource.ID) { ArchiveSource.resolve(request) })
            }
            if (config.stremioEnabled &&
                config.addonUrls.any { StremioSource.normalize(it) != null } &&
                Health.isHealthy(StremioSource.ID)
            ) {
                add(Task(StremioSource.ID) { StremioSource.resolve(request, config.addonUrls) })
            }
        }

        if (tasks.isEmpty()) {
            log("no enabled sources")
            return@supervisorScope
        }

        val queue = Channel<Task>(Channel.UNLIMITED)
        tasks.forEach { queue.send(it) }
        queue.close()

        val workers = List(config.parallelism.coerceIn(1, 16)) {
            launch {
                for (task in queue) {
                    val result = guard(task.id, log, task.block)
                    if (result.isEmpty()) continue
                    try {
                        onResult(task.id, result)
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        log("${task.id}: result handler failed - ${t.javaClass.simpleName}")
                    }
                }
            }
        }
        workers.joinAll()
    }

    suspend fun collect(
        request: LinkRequest,
        config: SourceConfig,
        log: (String) -> Unit = { Logx.d("Sources", it) },
    ): List<Candidate> {
        val all = Collections.synchronizedList(mutableListOf<Candidate>())
        stream(request, config, log) { _, items -> all.addAll(items) }
        return all.toList().distinctBy { it.url }
    }

    private suspend fun guard(
        id: String,
        log: (String) -> Unit,
        block: suspend () -> List<Candidate>,
    ): List<Candidate> = try {
        val result = withTimeoutOrNull(SOURCE_TIMEOUT_MS) { block() }
        if (result == null) {
            Health.failure(id)
            log("$id: timed out after ${SOURCE_TIMEOUT_MS}ms")
            emptyList()
        } else if (result.isEmpty()) {
            Health.cleanMiss(id)
            log("$id: no streams")
            emptyList()
        } else {
            Health.success(id)
            log("$id: ${result.size} streams")
            result
        }
    } catch (c: kotlinx.coroutines.CancellationException) {
        throw c
    } catch (t: Throwable) {
        Health.failure(id)
        log("$id: failed - ${t.javaClass.simpleName}: ${t.message}")
        emptyList()
    }
}

/** Short-lived circuit breaker so a dead source does not slow every tap. */
object Health {

    private const val MAX_FAILURES = 4
    private const val TRIP_MS = 5 * 60 * 1000L

    private data class State(val failures: Int = 0, val until: Long = 0L)

    private val states = ConcurrentHashMap<String, State>()

    fun isHealthy(id: String): Boolean {
        val state = states[id] ?: return true
        if (state.until > 0 && System.currentTimeMillis() >= state.until) {
            states[id] = State()
            return true
        }
        return state.until == 0L
    }

    fun success(id: String) {
        states[id] = State()
    }

    fun cleanMiss(id: String) {
        val state = states[id] ?: return
        states[id] = state.copy(failures = 0)
    }

    fun failure(id: String) {
        val state = states[id] ?: State()
        val failures = state.failures + 1
        states[id] = State(
            failures = failures,
            until = if (failures >= MAX_FAILURES) System.currentTimeMillis() + TRIP_MS else 0L,
        )
    }

    fun reset() = states.clear()
}

/** Keeps resolved links warm for a few minutes so re-opening an episode is instant. */
object LinkCache {

    private const val TTL_MS = 2 * 60 * 1000L
    private const val MAX_ENTRIES = 32

    private data class Entry(val at: Long, val items: List<Candidate>)

    private val entries = ConcurrentHashMap<String, Entry>()

    private fun key(request: LinkRequest): String = listOf(
        request.tmdbId,
        request.imdbId.orEmpty(),
        TitleMatch.normalize(request.title),
        request.type,
        request.season,
        request.episode,
    ).joinToString("|")

    fun get(request: LinkRequest): List<Candidate>? {
        val entry = entries[key(request)] ?: return null
        if (System.currentTimeMillis() - entry.at > TTL_MS) {
            entries.remove(key(request))
            return null
        }
        return entry.items
    }

    fun put(request: LinkRequest, items: List<Candidate>) {
        if (items.isEmpty()) return
        val slim = items.map { it.copy(inlineManifest = null) }
        entries[key(request)] = Entry(System.currentTimeMillis(), slim)
        if (entries.size > MAX_ENTRIES) {
            entries.entries.minByOrNull { it.value.at }?.let { entries.remove(it.key) }
        }
    }

    fun clear() = entries.clear()
}
