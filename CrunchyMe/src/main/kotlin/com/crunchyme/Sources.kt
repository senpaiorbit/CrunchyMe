package com.crunchyme

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    private suspend fun fromHtml(url: String, body: String): List<Candidate> {
        val headers = Http.withReferer(template.headers, template.referer)
        val found = LinkedHashMap<String, Candidate>()

        fun add(mediaUrl: String, referer: String?) {
            if (mediaUrl.isBlank() || found.containsKey(mediaUrl)) return
            found[mediaUrl] = Candidate(
                sourceId = id,
                sourceName = name,
                url = mediaUrl,
                kind = kindOf(mediaUrl),
                referer = referer,
                headers = template.headers,
            )
        }

        HtmlHarvest.mediaUrls(body).forEach { add(it, template.referer) }

        if (found.isEmpty()) {
            val frames = HtmlHarvest.iframes(body, url).take(3)
            val frameResults = coroutineScope {
                frames.map { frame ->
                    async {
                        val frameBody = Http.text(frame, Http.withReferer(headers, url), 8)
                            ?: return@async emptyList()
                        HtmlHarvest.mediaUrls(frameBody)
                    }
                }.awaitAll()
            }
            frameResults.flatten().forEach { add(it, url) }
        }

        if (found.isEmpty()) {
            HtmlHarvest.videoTag(body, url)?.let { add(it, template.referer) }
        }

        if (found.isEmpty()) {
            val links = mutableListOf<BridgeLink>()
            val subtitles = mutableListOf<SubtitleTrack>()
            val ok = runCatching {
                ExtractorBridge.loader(url, template.referer, { subtitles += it }, { links += it })
            }.getOrDefault(false)
            if (!ok && links.isEmpty()) {
                Logx.d("Custom", "${template.name}: nothing harvested")
            }
            links.forEach { link ->
                if (link.url.isBlank() || found.containsKey(link.url)) return@forEach
                found[link.url] = Candidate(
                    sourceId = id,
                    sourceName = name,
                    url = link.url,
                    kind = kindOf(link.url, link.type),
                    referer = link.referer ?: template.referer,
                    headers = template.headers,
                    qualityHint = link.quality,
                    subtitles = subtitles.toList(),
                )
            }
        }

        return found.values.toList()
    }

    private fun kindOf(url: String, type: String = ""): MediaKind = when {
        type.equals("M3U8", true) || url.contains(".m3u8", true) -> MediaKind.HLS
        type.equals("DASH", true) || url.contains(".mpd", true) -> MediaKind.DASH
        else -> MediaKind.FILE
    }

    companion object {
        fun providerId(template: ProviderTemplate): String =
            "p:" + template.name.trim().lowercase().replace(Regex("""\s+"""), "-")
    }
}

object Sources {

    private const val SOURCE_TIMEOUT_MS = 30_000L

    suspend fun collect(
        request: LinkRequest,
        config: SourceConfig,
        log: (String) -> Unit = { Logx.d("Sources", it) },
    ): List<Candidate> = supervisorScope {
        val jobs = mutableListOf<Deferred<List<Candidate>>>()

        if (config.archiveEnabled && Health.isHealthy(ArchiveSource.ID)) {
            jobs += async {
                guard(ArchiveSource.ID, log) { ArchiveSource.resolve(request) }
            }
        }

        if (config.stremioEnabled &&
            config.addonUrls.any { StremioSource.normalize(it) != null } &&
            Health.isHealthy(StremioSource.ID)
        ) {
            jobs += async {
                guard(StremioSource.ID, log) { StremioSource.resolve(request, config.addonUrls) }
            }
        }

        for (provider in config.providers.filter { it.enabled }) {
            val id = CustomProviderSource.providerId(provider)
            if (!Health.isHealthy(id)) continue
            jobs += async {
                guard(id, log) { CustomProviderSource(provider).resolve(request) }
            }
        }

        if (jobs.isEmpty()) {
            log("no enabled sources")
            return@supervisorScope emptyList()
        }

        jobs.awaitAll().flatten().distinctBy { it.url }
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

    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX_ENTRIES = 32

    private data class Entry(val at: Long, val items: List<Candidate>)

    private val entries = ConcurrentHashMap<String, Entry>()

    private fun key(request: LinkRequest): String =
        "${request.tmdbId}|${request.type}|${request.season}|${request.episode}"

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
