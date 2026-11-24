package com.crunchyme

import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/** Small, dependency-free TMDB client with an in-memory cache. */
object Tmdb {

    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMG_500 = "https://image.tmdb.org/t/p/w500"
    private const val IMG_1280 = "https://image.tmdb.org/t/p/w1280"
    private const val IMG_300 = "https://image.tmdb.org/t/p/w300"
    private const val CACHE_TTL_MS = 15 * 60 * 1000L
    private const val CACHE_MAX = 128

    @Volatile
    var keyProvider: () -> String? = { null }

    @Volatile
    var language: String = "en-US"

    data class Item(
        val id: Int,
        val type: String,
        val title: String,
        val year: Int?,
        val poster: String?,
        val score: Double?,
        val overview: String?,
        val popularity: Double,
        val originalLanguage: String?,
    )

    data class Cast(val name: String, val character: String?, val photo: String?)

    data class Detail(
        val id: Int,
        val type: String,
        val title: String,
        val overview: String?,
        val poster: String?,
        val backdrop: String?,
        val year: Int?,
        val runtime: Int?,
        val score: Double?,
        val genres: List<String>,
        val imdbId: String?,
        val originalLanguage: String?,
        val cast: List<Cast>,
        val seasonNumbers: List<Int>,
        val numberOfSeasons: Int,
    )

    data class Ep(
        val season: Int,
        val number: Int,
        val name: String?,
        val overview: String?,
        val still: String?,
        val airDate: String?,
        val rating: Double?,
        val runtime: Int?,
    )

    private data class Entry(val at: Long, val json: JSONObject)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun hasKey(): Boolean = !keyProvider()?.trim().isNullOrEmpty()

    fun clearCache() = cache.clear()

    fun image(path: String?): String? = path?.takeIf { it.startsWith("/") }?.let { IMG_500 + it }

    fun imageBig(path: String?): String? = path?.takeIf { it.startsWith("/") }?.let { IMG_1280 + it }

    fun imageSmall(path: String?): String? = path?.takeIf { it.startsWith("/") }?.let { IMG_300 + it }

    suspend fun testKey(key: String): Boolean {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return false
        val url = "$BASE/configuration?api_key=" + URLEncoder.encode(trimmed, "UTF-8")
        val body = Http.text(url, emptyMap(), 15) ?: return false
        return runCatching { JSONObject(body).has("images") }.getOrDefault(false)
    }

    suspend fun search(query: String): List<Item>? {
        val text = query.trim()
        if (text.isEmpty()) return null
        val json = get(
            "/search/multi",
            mapOf("query" to text, "include_adult" to "false", "page" to "1"),
        ) ?: return null
        val items = parseItems(json, null)
            .filter { it.title.isNotBlank() }
            .distinctBy { it.type to it.id }
        if (items.isEmpty()) return emptyList()
        return rank(text, items).take(20)
    }

    suspend fun row(key: String, page: Int): List<Item>? {
        val safePage = page.coerceAtLeast(1)
        return when (key) {
            "trending_movie" -> list("/trending/movie/day", safePage, "movie")
            "trending_tv" -> list("/trending/tv/day", safePage, "tv")
            "popular_movie" -> list("/movie/popular", safePage, "movie")
            "top_movie" -> list("/movie/top_rated", safePage, "movie")
            "upcoming_movie" -> list("/movie/upcoming", safePage, "movie")
            "popular_tv" -> list("/tv/popular", safePage, "tv")
            "top_tv" -> list("/tv/top_rated", safePage, "tv")
            "airing_tv" -> list("/tv/airing_today", safePage, "tv")
            "hindi" -> discover(safePage, "movie", "hi")
            "tamil" -> discover(safePage, "movie", "ta")
            "telugu" -> discover(safePage, "movie", "te")
            "malayalam" -> discover(safePage, "movie", "ml")
            "kannada" -> discover(safePage, "movie", "kn")
            "bengali" -> discover(safePage, "movie", "bn")
            else -> null
        }
    }

    private suspend fun list(path: String, page: Int, type: String): List<Item>? {
        val json = get(path, mapOf("page" to page.toString())) ?: return null
        return parseItems(json, type)
    }

    private suspend fun discover(page: Int, type: String, languageCode: String): List<Item>? {
        val json = get(
            "/discover/$type",
            mapOf(
                "page" to page.toString(),
                "with_original_language" to languageCode,
                "sort_by" to "popularity.desc",
                "vote_count.gte" to "25",
                "include_adult" to "false",
            ),
        ) ?: return null
        return parseItems(json, type)
    }

    suspend fun detail(id: Int, type: String): Detail? {
        val path = if (type == "movie") "/movie/$id" else "/tv/$id"
        val json = get(path, mapOf("append_to_response" to "external_ids,credits")) ?: return null

        val isMovie = type == "movie"
        val title = if (isMovie) json.optString("title") else json.optString("name")
        if (title.isBlank()) return null

        val date = if (isMovie) json.optString("release_date") else json.optString("first_air_date")
        val runtime = if (isMovie) {
            json.optInt("runtime", 0).takeIf { it > 0 }
        } else {
            json.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }
        }
        val score = json.optDouble("vote_average", 0.0).takeIf { it > 0.0 }
        val genres = json.optJSONArray("genres").names("name")
        val imdb = json.optJSONObject("external_ids")
            ?.optString("imdb_id")
            ?.takeIf { it.startsWith("tt") }
        val cast = json.optJSONObject("credits")
            ?.optJSONArray("cast")
            ?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val member = array.optJSONObject(index) ?: return@mapNotNull null
                    val name = member.optString("name").trim()
                    if (name.isEmpty()) return@mapNotNull null
                    Cast(
                        name = name,
                        character = member.optString("character").trim().ifBlank { null },
                        photo = imageSmall(member.optString("profile_path")),
                    )
                }.take(15)
            }
            .orEmpty()

        val seasons = json.optJSONArray("seasons")
        val seasonNumbers = if (seasons == null) {
            emptyList()
        } else {
            (0 until seasons.length()).mapNotNull { index ->
                val season = seasons.optJSONObject(index) ?: return@mapNotNull null
                season.optInt("season_number", -1).takeIf { it > 0 }
            }
        }

        return Detail(
            id = id,
            type = type,
            title = title,
            overview = json.optString("overview").trim().ifBlank { null },
            poster = image(json.optString("poster_path")),
            backdrop = imageBig(json.optString("backdrop_path")),
            year = date.take(4).toIntOrNull(),
            runtime = runtime,
            score = score,
            genres = genres,
            imdbId = imdb,
            originalLanguage = json.optString("original_language").trim().ifBlank { null },
            cast = cast,
            seasonNumbers = seasonNumbers,
            numberOfSeasons = json.optInt("number_of_seasons", seasonNumbers.size),
        )
    }

    suspend fun episodes(tmdbId: Int): List<Ep> = coroutineScope {
        val detail = detail(tmdbId, "tv") ?: return@coroutineScope emptyList()
        val seasons = detail.seasonNumbers.ifEmpty {
            (1..detail.numberOfSeasons.coerceAtLeast(0)).toList()
        }
        seasons.map { season -> async { seasonEpisodes(tmdbId, season) } }
            .awaitAll()
            .flatten()
            .sortedWith(compareBy({ it.season }, { it.number }))
    }

    suspend fun seasonEpisodes(tmdbId: Int, season: Int): List<Ep> {
        val json = get("/tv/$tmdbId/season/$season", emptyMap()) ?: return emptyList()
        val array = json.optJSONArray("episodes") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val episode = array.optJSONObject(index) ?: return@mapNotNull null
            val number = episode.optInt("episode_number", 0)
            if (number <= 0) return@mapNotNull null
            Ep(
                season = episode.optInt("season_number", season).takeIf { it > 0 } ?: season,
                number = number,
                name = episode.optString("name").trim().ifBlank { null },
                overview = episode.optString("overview").trim().ifBlank { null },
                still = imageSmall(episode.optString("still_path")),
                airDate = episode.optString("air_date").trim().takeIf { it.length >= 10 },
                rating = episode.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
                runtime = episode.optInt("runtime", 0).takeIf { it > 0 },
            )
        }
    }

    suspend fun findByImdb(imdbId: String): Item? {
        if (!imdbId.startsWith("tt")) return null
        val json = get("/find/$imdbId", mapOf("external_source" to "imdb_id")) ?: return null
        json.optJSONArray("movie_results")?.optJSONObject(0)?.let { return itemOf(it, "movie") }
        json.optJSONArray("tv_results")?.optJSONObject(0)?.let { return itemOf(it, "tv") }
        return null
    }

    private suspend fun get(path: String, params: Map<String, String>): JSONObject? {
        val key = keyProvider()?.trim().orEmpty()
        if (key.isEmpty()) return null
        val url = buildUrl(path, params, key)

        cache[url]?.let { entry ->
            if (System.currentTimeMillis() - entry.at < CACHE_TTL_MS) return entry.json
        }

        val body = Http.text(url, emptyMap(), 20) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        cache[url] = Entry(System.currentTimeMillis(), json)
        if (cache.size > CACHE_MAX) {
            val oldest = cache.entries.minByOrNull { it.value.at }?.key
            if (oldest != null) cache.remove(oldest)
        }
        return json
    }

    private fun buildUrl(path: String, params: Map<String, String>, key: String): String {
        val query = StringBuilder()
        query.append("api_key=").append(URLEncoder.encode(key, "UTF-8"))
        if (language.isNotBlank()) {
            query.append("&language=").append(URLEncoder.encode(language, "UTF-8"))
        }
        params.forEach { (name, value) ->
            query.append('&').append(URLEncoder.encode(name, "UTF-8"))
                .append('=').append(URLEncoder.encode(value, "UTF-8"))
        }
        return "$BASE$path?$query"
    }

    private fun parseItems(json: JSONObject, forcedType: String?): List<Item> {
        val results = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Item>(results.length())
        for (index in 0 until results.length()) {
            val obj = results.optJSONObject(index) ?: continue
            val type = forcedType ?: obj.optString("media_type").takeIf { it == "movie" || it == "tv" }
                ?: continue
            itemOf(obj, type)?.let { out += it }
        }
        return out
    }

    private fun itemOf(obj: JSONObject, type: String): Item? {
        val id = obj.optInt("id", 0)
        if (id <= 0) return null
        val isMovie = type == "movie"
        val title = if (isMovie) obj.optString("title") else obj.optString("name")
        if (title.isBlank()) return null
        val date = if (isMovie) obj.optString("release_date") else obj.optString("first_air_date")
        return Item(
            id = id,
            type = type,
            title = title,
            year = date.take(4).toIntOrNull(),
            poster = image(obj.optString("poster_path")),
            score = obj.optDouble("vote_average", 0.0).takeIf { it > 0.0 },
            overview = obj.optString("overview").trim().ifBlank { null },
            popularity = obj.optDouble("popularity", 0.0),
            originalLanguage = obj.optString("original_language").trim().ifBlank { null },
        )
    }

    private fun JSONArray?.names(field: String): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index ->
            optJSONObject(index)?.optString(field)?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    private fun normalize(value: String): String {
        var text = value.lowercase()
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
        for (article in listOf("the ", "a ", "an ")) {
            if (text.startsWith(article)) {
                text = text.removePrefix(article)
                break
            }
        }
        return text
    }

    private fun rank(query: String, items: List<Item>): List<Item> {
        val wanted = normalize(query)
        return items.sortedWith(
            compareByDescending<Item> { score(wanted, it) }
                .thenByDescending { it.popularity },
        )
    }

    private fun score(query: String, item: Item): Int {
        val title = normalize(item.title)
        if (query.isEmpty() || title.isEmpty()) return 0
        val base = when {
            title == query -> 1000
            title.startsWith(query) -> 800
            title.contains(query) -> 600
            query.contains(title) -> 500
            else -> (overlap(query, title) * 300).toInt()
        }
        return base
    }

    private fun overlap(a: String, b: String): Double {
        val left = a.split(' ').filter { it.isNotBlank() }.toSet()
        val right = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / left.size
    }
}
