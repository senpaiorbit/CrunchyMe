package com.crunchyme

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.loadExtractor

/** Routes HTTP through the CloudStream client so cookies/interceptors are shared with the app. */
object CloudStreamEngine : HttpEngine {
    override suspend fun request(
        url: String,
        headers: Map<String, String>,
        timeoutSec: Long,
        range: String?,
    ): HttpResult? = try {
        val allHeaders = if (range.isNullOrBlank()) headers else headers + ("Range" to range)
        val response = app.get(url, timeout = timeoutSec, headers = allHeaders)
        val bytes = response.body.byteStream().use { stream ->
            Http.readLimited(stream, Http.responseLimit(range))
        }
        HttpResult(
            code = response.code,
            body = bytes.toString(Charsets.UTF_8),
            bytes = bytes,
            finalUrl = response.url,
            headers = response.headers.names().associateWith { response.headers[it] ?: "" },
        )
    } catch (t: Throwable) {
        null
    }
}

/** Gives custom providers access to CloudStream's host-extractor registry. */
object CloudStreamExtractors {

    fun install() {
        ExtractorBridge.loader = { url, referer, onSubtitle, onLink ->
            val subtitles = mutableListOf<SubtitleTrack>()
            val links = mutableListOf<BridgeLink>()
            val ok = try {
                loadExtractor(
                    url = url,
                    referer = referer ?: "",
                    subtitleCallback = { file ->
                        val language = file.lang.trim()
                        if (file.url.isNotBlank()) subtitles += SubtitleTrack(language, file.url)
                    },
                    callback = { link ->
                        links += BridgeLink(
                            url = link.url,
                            referer = link.referer,
                            quality = link.quality,
                            type = link.type.name,
                        )
                    },
                )
            } catch (t: Throwable) {
                Logx.w("Extractors", "loadExtractor failed: ${t.javaClass.simpleName}")
                false
            }
            subtitles.distinctBy { it.url }.forEach(onSubtitle)
            links.forEach(onLink)
            ok || links.isNotEmpty()
        }
    }
}
