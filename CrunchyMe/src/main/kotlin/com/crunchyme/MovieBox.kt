package com.crunchyme

import org.json.JSONArray
import org.json.JSONObject

/** MovieBox's H5 API is title-keyed, which also makes it usable from an OMDb-only catalog. */
object MovieBox {

    private const val BASE = "https://h5-api.aoneroom.com"
    private const val H5 = "https://h5.aoneroom.com"
    private const val MOVIEBOX_SITE = "https://movieboxonline.net"
    private const val NETNAIJA_SITE = "https://netnaija.film"
    private const val MOVIEBOX_SOURCE = "webMovieBoxSite"
    private const val NETNAIJA_SOURCE = "webNetnaijaSite"
    private const val TOKEN_TTL_MS = 30 * 60 * 1000L

    private val SEASON_END = Regex("""(?i)\bS(?:eason)?\s*0?(\d{1,2})(?:\s*[-–]\s*S?0?(\d{1,2}))?\b""")
    private val YEAR = Regex("""\b(?:19|20)\d{2}\b""")
    private val BRACKETS = Regex("""[\[(]([^\])]+)[\])]""")
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

    @Volatile private var token: String? = null
    @Volatile private var tokenAt: Long = 0L

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> =
        resolve(spec, request, MOVIEBOX_SITE, MOVIEBOX_SOURCE)

    suspend fun resolveNetNaija(spec: ServerSpec, request: LinkRequest): List<Candidate> =
        resolve(spec, request, NETNAIJA_SITE, NETNAIJA_SOURCE)

    private suspend fun resolve(
        spec: ServerSpec,
        request: LinkRequest,
        site: String,
        source: String,
    ): List<Candidate> {
        val title = request.title.trim()
        if (title.isBlank()) return emptyList()
        val out = LinkedHashMap<String, Candidate>()
        if (request.isMovie) {
            MovieBoxMobile.resolve(spec, request).forEach { out.putIfAbsent(it.url, it) }
        }
        val bearer = bearer(false, site, source) ?: return out.values.toList()
        val subjectType = if (request.isMovie) 1 else 2
        var items = search(title, subjectType, bearer, site, source)
        if (items.length() == 0 && request.year != null) {
            items = search("$title ${request.year}", subjectType, bearer, site, source)
        }
        if (items.length() == 0) {
            token = null
            val fresh = bearer(true, site, source) ?: return out.values.toList()
            items = search(title, subjectType, fresh, site, source)
        }
        if (items.length() == 0) return out.values.toList()

        val wanted = normalize(title)
        val subjects = mutableListOf<Subject>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val id = item.optString("subjectId").trim().takeIf { it.isNotBlank() } ?: continue
            val rawTitle = item.optString("title")
            val clean = rawTitle
                .replace(SEASON_END, "")
                .replace(BRACKETS, "")
                .replace(YEAR, "")
                .replace(Regex("""\s{2,}"""), " ")
            val normalized = normalize(clean)
            if (!matchesTitle(normalized, wanted)) continue
            val audio = BRACKETS.findAll(rawTitle).map { it.groupValues[1] }
                .firstOrNull { value -> value.any { it.isLetter() } && value.none { it.isDigit() } }
                .orEmpty()
            subjects += Subject(
                id = id,
                detailPath = item.optString("detailPath").trim(),
                seasonEnd = seasonEnd(rawTitle) ?: 0,
                language = Naming.displayLabel(audio).ifBlank { Naming.labelOf(request.originalLanguage) },
            )
        }
        if (subjects.isEmpty()) return out.values.toList()

        for (subject in subjects.take(6)) {
            if (!request.isMovie && subject.seasonEnd in 1 until request.season) continue
            val detailPath = subject.detailPath.ifBlank { detailPath(subject.id, site, source) }
            if (detailPath.isBlank()) continue
            val encodedPath = java.net.URLEncoder.encode(detailPath, "UTF-8")
            val params = buildString {
                append("subjectId=").append(subject.id)
                append("&se=").append(if (request.isMovie) 0 else request.season)
                append("&ep=").append(if (request.isMovie) 0 else request.episode)
                append("&detailPath=").append(encodedPath)
            }
            val playHeaders = headers(bearer, site, source).toMutableMap().apply {
                put("Referer", "$site/videoPlayPage/$detailPath")
                put("Origin", site)
            }
            val play = requestJson("$BASE/wefeed-h5api-bff/subject/play?$params", playHeaders)
            val download = requestJson("$BASE/wefeed-h5api-bff/subject/download?$params", playHeaders)
            for (root in listOfNotNull(play, download)) {
                val data = unwrap(root)
                addStreams(spec, out, data.optJSONArray("streams"), request, subject.language, playHeaders, site, null)
                addStreams(spec, out, data.optJSONArray("hls"), request, subject.language, playHeaders, site, MediaKind.HLS)
                addStreams(spec, out, data.optJSONArray("dash"), request, subject.language, playHeaders, site, MediaKind.DASH)
                addStreams(spec, out, data.optJSONArray("downloads"), request, subject.language, playHeaders, site, null)
            }
            if (out.size >= 12) break
        }
        return out.values.toList()
    }

    private data class Subject(val id: String, val detailPath: String, val seasonEnd: Int, val language: String)

    private suspend fun bearer(force: Boolean, site: String, source: String): String? {
        if (!force) {
            token?.takeIf { System.currentTimeMillis() - tokenAt < TOKEN_TTL_MS }?.let { return it }
        }
        val response = Http.request(
            "$BASE/wefeed-h5api-bff/app/get-latest-app-pkgs?app_name=moviebox",
            headers(null, site, source),
            10,
        ) ?: return null
        val raw = response.headers.entries.firstOrNull { it.key.equals("x-user", true) }?.value ?: return null
        val fresh = runCatching { JSONObject(raw).optString("token") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: return null
        token = fresh
        tokenAt = System.currentTimeMillis()
        return fresh
    }

    private suspend fun search(
        title: String,
        subjectType: Int,
        bearer: String,
        site: String,
        source: String,
    ): JSONArray {
        val body = JSONObject()
            .put("keyword", title)
            .put("page", 1)
            .put("perPage", 24)
            .put("subjectType", subjectType)
            .toString()
        val raw = Http.post(
            "$BASE/wefeed-h5api-bff/subject/search",
            body,
            headers(bearer, site, source),
            "application/json; charset=utf-8",
            15,
        ) ?: return JSONArray()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return JSONArray()
        return unwrap(root).optJSONArray("items") ?: JSONArray()
    }

    private suspend fun detailPath(subjectId: String, site: String, source: String): String {
        val raw = Http.text(
            "$H5/wefeed-h5-bff/web/post/list/subject?id=$subjectId",
            headers(token, site, source),
            8,
        ) ?: return ""
        return runCatching {
            JSONObject(raw).optJSONObject("data")
                ?.optJSONArray("items")?.optJSONObject(0)
                ?.optJSONObject("subject")?.optString("detailPath").orEmpty()
        }.getOrDefault("")
    }

    private suspend fun requestJson(url: String, headers: Map<String, String>): JSONObject? {
        val raw = Http.text(url, headers, 10) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun matchesTitle(candidate: String, wanted: String): Boolean {
        if (candidate == wanted) return true
        if (wanted.length < 4 || !candidate.startsWith(wanted)) return false
        return !candidate[wanted.length].isDigit()
    }

    private fun addStreams(
        spec: ServerSpec,
        out: MutableMap<String, Candidate>,
        array: JSONArray?,
        request: LinkRequest,
        language: String,
        headers: Map<String, String>,
        site: String,
        forceKind: MediaKind?,
    ) {
        if (array == null) return
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optBoolean("vipLocked", false)) continue
            val rawUrl = item.optString("url").trim()
            val signCookie = item.optString("signCookie")
            val policyUrl = policyResource(signCookie)
            val url = (policyUrl ?: rawUrl).takeIf { it.startsWith("http") } ?: continue
            if (url.contains("macdn.aoneroom.com/other/", true)) continue
            val rawResolution = item.optString("resolutions")
            val resolution = Naming.qualityHeight(rawResolution)
                .takeIf { it > 0 }
                ?: rawResolution.toIntOrNull()?.takeIf { it in 144..4320 }
                ?: item.optInt("resolution", 0)
            val inferred = Naming.qualityHeight(url).takeIf { it > 0 } ?: 0
            val finalResolution = if (resolution > 0) resolution else inferred
            val kind = forceKind ?: when {
                url.contains(".mpd", true) -> MediaKind.DASH
                url.contains(".m3u8", true) -> MediaKind.HLS
                else -> ProviderSupport.kind(url)
            }
            val streamHeaders = headers.toMutableMap().apply {
                remove("Authorization")
                remove("Cookie")
                put("Accept", "*/*")
                put("Referer", "$site/")
                put("Origin", site)
                val signHeaderKey = item.optString("signHeaderKey").trim()
                if (signHeaderKey.isNotBlank() && signCookie.isNotBlank()) {
                    put(signHeaderKey, signCookie)
                }
                cloudFrontCookie(signCookie)?.takeIf { it.isNotBlank() }?.let { put("Cookie", it) }
            }
            if (!out.containsKey(url)) {
                out[url] = Candidate(
                    sourceId = spec.id,
                    sourceName = spec.name,
                    url = url,
                    kind = if (policyUrl != null) MediaKind.DASH else kind,
                    referer = site,
                    headers = streamHeaders,
                    qualityHint = finalResolution,
                    audioLabel = language,
                    mediaLabel = request.title,
                )
            }
        }
    }

    private fun unwrap(root: JSONObject): JSONObject {
        val data = root.optJSONObject("data") ?: return root
        return data.optJSONObject("data") ?: data
    }

    private fun headers(bearer: String?, site: String, source: String): Map<String, String> = buildMap {
        put("User-Agent", Http.UA)
        put("Accept", "application/json")
        put("Origin", site)
        put("Referer", "$site/")
        put("X-Request-Lang", "en")
        put("X-Client-Info", "{\"timezone\":\"Asia/Calcutta\"}")
        put("Accept-Language", "en-US,en;q=0.9")
        put("X-Source", source)
        bearer?.takeIf { it.isNotBlank() }?.let {
            put("Authorization", "Bearer $it")
            put("Cookie", "token=$it")
        }
    }

    private fun policyResource(signCookie: String?): String? {
        if (signCookie.isNullOrBlank()) return null
        Regex("""Edge-Cache-Cookie=urlprefix=([^:;\s]+)""").find(signCookie)?.groupValues?.get(1)?.let { encoded ->
            B64.decodeText(encoded)?.trimEnd('/')?.let { return "$it/index.mpd" }
        }
        val policy = Regex("""CloudFront-Policy=([^;]+)""").find(signCookie)?.groupValues?.get(1) ?: return null
        val decoded = B64.decodeText(policy) ?: return null
        val resource = runCatching {
            JSONObject(decoded).optJSONArray("Statement")?.optJSONObject(0)?.optString("Resource")
        }.getOrNull()?.trimEnd('*', '/') ?: return null
        return if (resource.endsWith(".mpd", true)) resource else "$resource/index.mpd"
    }

    private fun cloudFrontCookie(signCookie: String?): String? {
        if (signCookie.isNullOrBlank()) return null
        val parts = signCookie.split(";").map { it.trim().trim('"') }
            .filter { it.contains("=") && it.substringAfter("=").isNotBlank() }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun seasonEnd(title: String): Int? {
        val match = SEASON_END.find(title) ?: return null
        return match.groupValues[2].toIntOrNull() ?: match.groupValues[1].toIntOrNull()
    }

    private fun normalize(value: String): String =
        NON_ALNUM.replace(value.lowercase(), "")
}

/** Mobile app API; used when the H5 play endpoint is region-blocked. */
private object MovieBoxMobile {

    private const val SIGN_KEY = "NzZpUmwwN3MweFNOOWpxbUVXQXQ3OUVCSlp1bElRSXNWNjRGWnIyTw=="
    private const val UA =
        "com.community.oneroom/50020042 (Linux; U; Android 14; en_IN; 22101316G; " +
            "Build/UD1A.230803.041; Cronet/145.0.7582.0)"
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private val HOSTS = listOf(
        "https://api6.aoneroom.com", "https://api5.aoneroom.com", "https://api4.aoneroom.com",
        "https://api4sg.aoneroom.com", "https://api3.aoneroom.com", "https://api6sg.aoneroom.com",
        "https://apig.inmoviebox.com", "https://apii.inmoviebox.com", "https://api7.aoneroom.com",
        "https://api.inmoviebox.com",
    )
    private val CLIENT_INFO = JSONObject()
        .put("package_name", "com.community.oneroom")
        .put("version_name", "3.0.03.0529.03")
        .put("version_code", 50020042)
        .put("os", "android")
        .put("os_version", "14")
        .put("device_id", "1b2212c1dadf43c3a0c8bd6ce48ae22d")
        .put("install_store", "official")
        .put("gaid", "1b2212c1-dadf-43c3-a0c8-bd6ce48ae22d")
        .put("brand", "xiaomi")
        .put("model", "22101316G")
        .put("system_language", "en")
        .put("net", "NETWORK_WIFI")
        .put("region", "IN")
        .put("timezone", "Asia/Calcutta")
        .put("sp_code", "")
        .toString()
    private val DUB = mapOf(
        "hi" to "Hindi", "ta" to "Tamil", "te" to "Telugu", "bn" to "Bengali",
        "mr" to "Marathi", "pa" to "Punjabi", "ml" to "Malayalam", "kn" to "Kannada", "en" to "English",
    )

    @Volatile private var token: String? = null
    @Volatile private var tokenAt = 0L

    suspend fun resolve(spec: ServerSpec, request: LinkRequest): List<Candidate> {
        if (bearer() == null) return emptyList()
        val body = JSONObject()
            .put("page", 1)
            .put("perPage", 20)
            .put("keyword", request.title)
            .toString()
        val root = call("/wefeed-mobile-bff/subject-api/search/v2", "POST", body) ?: return emptyList()
        val data = root.optJSONObject("data") ?: root
        val subjects = mutableListOf<JSONObject>()
        data.optJSONArray("items")?.let { array ->
            for (index in 0 until array.length()) array.optJSONObject(index)?.let { subjects += it }
        }
        data.optJSONArray("results")?.let { results ->
            for (index in 0 until results.length()) {
                results.optJSONObject(index)?.optJSONArray("subjects")?.let { array ->
                    for (child in 0 until array.length()) array.optJSONObject(child)?.let { subjects += it }
                }
            }
        }
        val wanted = normalize(request.title)
        val matched = subjects.filter { item ->
            val clean = item.optString("title")
                .replace(Regex("""(?i)\bS(?:eason)?\s*0?\d{1,2}(?:\s*[-–]\s*S?0?\d{1,2})?\b"""), "")
                .replace(Regex("""[\[(][^\])]*[\])]"""), "")
                .replace(Regex("""\b(?:19|20)\d{2}\b"""), "")
            matchesTitle(normalize(clean), wanted)
        }.mapNotNull { it.optString("subjectId").takeIf { id -> id.isNotBlank() } }.distinct()

        val out = LinkedHashMap<String, Candidate>()
        for (id in matched.take(6)) {
            val detail = call("/wefeed-mobile-bff/subject-api/get?subjectId=$id") ?: continue
            val subject = detail.optJSONObject("data") ?: detail
            val language = dubLanguage(subject, id)
                .ifBlank { languageFromTitle(subject.optString("title")) }
                .ifBlank { Naming.labelOf(request.originalLanguage) }
            val detectors = subject.optJSONArray("resourceDetectors") ?: continue
            for (index in 0 until detectors.length()) {
                val rungs = detectors.optJSONObject(index)?.optJSONArray("resolutionList") ?: continue
                for (rungIndex in 0 until rungs.length()) {
                    val rung = rungs.optJSONObject(rungIndex) ?: continue
                    val url = rung.optString("resourceLink").takeIf { it.startsWith("http") } ?: continue
                    if (url.contains("macdn.aoneroom.com/other/", true)) continue
                    if (rung.optInt("requireMemberType", 0) != 0) continue
                    val height = rung.optInt("resolution", 0)
                    if (height in 1..719) continue
                    val bytes = rung.optString("size").toLongOrNull() ?: 0L
                    if (!out.containsKey(url)) {
                        out[url] = Candidate(
                            sourceId = spec.id,
                            sourceName = spec.name,
                            url = url,
                            kind = ProviderSupport.kind(url),
                            referer = "https://movie-box.co/",
                            headers = mapOf("User-Agent" to UA, "Referer" to "https://movie-box.co/"),
                            qualityHint = height,
                            audioLabel = language,
                            sizeLabel = Naming.bytesLabel(bytes),
                            mediaLabel = request.title,
                        )
                    }
                }
            }
            if (out.size >= 10) break
        }
        return out.values.toList()
    }

    private suspend fun bearer(): String? {
        token?.takeIf { System.currentTimeMillis() - tokenAt < TTL_MS }?.let { return it }
        call("/wefeed-mobile-bff/tab-operating?page=1&tabId=0&version=", bootstrap = true)
        return token
    }

    private suspend fun call(
        path: String,
        method: String = "GET",
        body: String? = null,
        bootstrap: Boolean = false,
    ): JSONObject? {
        val accept = "application/json"
        val contentType = "application/json; charset=utf-8"
        for (host in HOSTS) {
            val url = host + path
            val ts = System.currentTimeMillis()
            val headers = LinkedHashMap<String, String>()
            headers["User-Agent"] = UA
            headers["Accept"] = accept
            headers["Content-Type"] = contentType
            headers["x-client-token"] = "$ts," + md5(ts.toString().reversed())
            headers["x-tr-signature"] = signature(method, accept, contentType, url, body, ts)
            headers["x-client-info"] = CLIENT_INFO
            headers["x-client-status"] = "0"
            if (!bootstrap) token?.let { headers["Authorization"] = "Bearer $it" }
            val response = if (method == "POST") {
                Http.engine.post(url, body.orEmpty(), contentType, headers, 12)
            } else {
                Http.engine.request(url, headers, 12)
            } ?: continue
            response.headers.entries.firstOrNull { it.key.equals("x-user", true) }?.value?.let { raw ->
                runCatching { JSONObject(raw).optString("token") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }?.let {
                        token = it
                        tokenAt = System.currentTimeMillis()
                    }
            }
            if (response.code == 200) return runCatching { JSONObject(response.body) }.getOrNull()
            if (response.code !in setOf(403, 407, 429, 500, 502, 503, 504)) break
        }
        return null
    }

    private fun signature(method: String, accept: String, contentType: String, url: String, body: String?, ts: Long): String {
        val bytes = body?.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        val hash = bytes?.let { md5Bytes(it.copyOfRange(0, minOf(it.size, 102400))) }.orEmpty()
        val canonical = listOf(
            method.uppercase(), accept, contentType, bytes?.size?.toString().orEmpty(),
            ts.toString(), hash, canonicalUrl(url),
        ).joinToString("\n")
        val outer = B64.decodeText(SIGN_KEY) ?: return ""
        val key = B64.decode(outer) ?: return ""
        val mac = javax.crypto.Mac.getInstance("HmacMD5")
        mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacMD5"))
        return "$ts|2|" + B64.encode(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))
    }

    private fun canonicalUrl(url: String): String {
        val uri = runCatching { java.net.URI.create(url) }.getOrNull() ?: return url
        val path = uri.path.ifBlank { "/" }
        val query = uri.rawQuery ?: return path
        val pairs = query.split('&').filter { it.isNotEmpty() }.map { part ->
            val cut = part.indexOf('=')
            if (cut < 0) part to "" else part.substring(0, cut) to part.substring(cut + 1)
        }.sortedWith(compareBy({ it.first }, { it.second }))
        return "$path?" + pairs.joinToString("&") { "${it.first}=${it.second}" }
    }

    private fun md5(value: String): String = md5Bytes(value.toByteArray(Charsets.UTF_8))

    private fun md5Bytes(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun matchesTitle(candidate: String, wanted: String): Boolean {
        if (candidate == wanted) return true
        if (wanted.length < 4 || !candidate.startsWith(wanted)) return false
        return !candidate[wanted.length].isDigit()
    }

    private fun languageFromTitle(title: String): String =
        Regex("""[\[(]([^\])]*)[\])]""").findAll(title)
            .map { it.groupValues[1] }
            .firstOrNull { tag -> tag.any { it.isLetter() } && tag.none { it.isDigit() } }
            ?.let { Naming.displayLabel(it) }
            .orEmpty()

    private fun dubLanguage(detail: JSONObject, subjectId: String): String {
        val dubs = detail.optJSONArray("dubs") ?: return ""
        for (index in 0 until dubs.length()) {
            val dub = dubs.optJSONObject(index) ?: continue
            if (dub.optString("subjectId") != subjectId) continue
            if (dub.optBoolean("original", false)) return ""
            val code = dub.optString("lanCode").lowercase()
            DUB[code]?.let { return it }
            val named = dub.optString("lanName").replace(Regex("""(?i)\s*dub\b"""), "").trim()
            Naming.labelOf(named).takeIf { it.isNotBlank() }?.let { return it }
            Naming.labelOf(code).takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }


    private fun normalize(value: String): String =
        Regex("""[^\p{L}\p{N}]+""").replace(value.lowercase(), "")
}
