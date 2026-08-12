package com.laddu100.hdghartv

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class HDGharTVSmartProvider : MainAPI() {
    override var mainUrl = "https://hdghartv.cc"
    override var name = "HDGharTV Smart"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val fallbackApiBase = "https://hdghartv.cc"
    private val TAG = "HDGharTVSmart"

    private suspend fun apiBase(): String {
        val domain = FirebaseDomainHelper.getDomain("hdghartv")
        return (domain ?: fallbackApiBase).removeSuffix("/")
    }

    private val crawlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var crawling = false

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StreamLink(
        @JsonProperty("quality") val quality: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("isActive") val isActive: Boolean? = null,
        @JsonProperty("headers") val headers: String? = null,
        @JsonProperty("userAgent") val userAgent: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Episode(
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("stillPath") val stillPath: String? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("streamingLinks") val streamingLinks: List<StreamLink>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Season(
        @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("episodes") val episodes: List<Episode>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Genre(@JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ProductionCompany(@JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class CastMember(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("character") val character: String? = null,
        @JsonProperty("profilePath") val profilePath: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MediaItem(
        @JsonProperty("_id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("originalTitle") val originalTitle: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("posterPath") val posterPath: String? = null,
        @JsonProperty("backdropPath") val backdropPath: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("firstAirDate") val firstAirDate: String? = null,
        @JsonProperty("genres") val genres: List<Genre>? = null,
        @JsonProperty("category") val category: String? = null,
        @JsonProperty("languages") val languages: List<String>? = null,
        @JsonProperty("voteAverage") val voteAverage: Double? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("certification") val certification: String? = null,
        @JsonProperty("contentRating") val contentRating: String? = null,
        @JsonProperty("productionCompanies") val productionCompanies: List<ProductionCompany>? = null,
        @JsonProperty("cast") val cast: List<CastMember>? = null,
        @JsonProperty("streamingLinks") val streamingLinks: List<StreamLink>? = null,
        @JsonProperty("seasons") val seasons: List<Season>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MediaListResponse(
        @JsonProperty("data") val data: List<MediaItem>? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null,
        @JsonProperty("total") val total: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LoadData(val id: String, val type: String, val title: String, val posterUrl: String? = null)

    override val mainPage = mainPageOf(
        "smart" to "Smart Catalog",
        "movies" to "All Movies",
        "series" to "All Series"
    )

    // --- INSTANT SYNC & BACKGROUND CRAWLER ---
    // Fetches 100 items synchronously if storage is empty, then continues in background
    private suspend fun syncAndCrawl() {
        val base = apiBase()
        var allRecords = HDGharTVStorage.getAll()
        
        // Instant sync on first launch
        if (allRecords.isEmpty()) {
            try {
                for (i in 1..2) {
                    val mRes = app.get("$base/api/movies/public?page=$i&limit=50", referer = "$base/").text
                    val mParsed = parseJson<MediaListResponse>(mRes)
                    val mRecords = mParsed.data?.mapNotNull { it.toLocalRecord("movie") } ?: emptyList()
                    if (mRecords.isNotEmpty()) HDGharTVStorage.addRichBatch(mRecords)
                    if (mParsed.data?.size ?: 0 < 50) break
                }
                for (i in 1..2) {
                    val sRes = app.get("$base/api/series/public?page=$i&limit=50", referer = "$base/").text
                    val sParsed = parseJson<MediaListResponse>(sRes)
                    val sRecords = sParsed.data?.mapNotNull { it.toLocalRecord("series") } ?: emptyList()
                    if (sRecords.isNotEmpty()) HDGharTVStorage.addRichBatch(sRecords)
                    if (sParsed.data?.size ?: 0 < 50) break
                }
                allRecords = HDGharTVStorage.getAll()
            } catch (e: Exception) {
                Log.e(TAG, "Instant Sync Error: ${e.message}")
            }
        }

        // Background crawler for remaining pages
        if (!crawling) {
            crawling = true
            crawlerScope.launch {
                try {
                    var (moviePage, seriesPage) = HDGharTVStorage.getCrawlerState()
                    // Start from page 3 if instant sync just ran pages 1 and 2
                    if (moviePage == 1 && allRecords.isNotEmpty()) moviePage = 3
                    if (seriesPage == 1 && allRecords.isNotEmpty()) seriesPage = 3
                    
                    val limit = 50
                    for (i in 1..2) {
                        val mRes = app.get("$base/api/movies/public?page=$moviePage&limit=$limit", referer = "$base/").text
                        val mParsed = parseJson<MediaListResponse>(mRes)
                        val mRecords = mParsed.data?.mapNotNull { it.toLocalRecord("movie") } ?: emptyList()
                        if (mRecords.isNotEmpty()) HDGharTVStorage.addRichBatch(mRecords)
                        if (mParsed.data?.size ?: 0 < limit) moviePage = 1 else moviePage++
                    }
                    for (i in 1..2) {
                        val sRes = app.get("$base/api/series/public?page=$seriesPage&limit=$limit", referer = "$base/").text
                        val sParsed = parseJson<MediaListResponse>(sRes)
                        val sRecords = sParsed.data?.mapNotNull { it.toLocalRecord("series") } ?: emptyList()
                        if (sRecords.isNotEmpty()) HDGharTVStorage.addRichBatch(sRecords)
                        if (sParsed.data?.size ?: 0 < limit) seriesPage = 1 else seriesPage++
                    }
                    HDGharTVStorage.saveCrawlerState(moviePage, seriesPage)
                } catch (e: Exception) {
                    Log.e(TAG, "Background Crawler Error: ${e.message}")
                } finally {
                    crawling = false
                }
            }
        }
    }

    private fun MediaItem.toLocalRecord(type: String): HDGharTVStorage.MediaRecord? {
        val id = id ?: return null
        val title = title ?: originalTitle ?: return null
        return HDGharTVStorage.MediaRecord(
            id = id, type = type, title = title,
            overview = overview ?: "", posterPath = posterPath ?: "",
            backdropPath = backdropPath ?: "", releaseDate = releaseDate ?: "",
            firstAirDate = firstAirDate ?: "",
            genres = genres?.mapNotNull { it.name } ?: emptyList(),
            category = category ?: "", languages = languages ?: emptyList(),
            voteAverage = voteAverage ?: 0.0, runtime = runtime ?: 0,
            status = status ?: "", certification = certification ?: contentRating ?: "",
            productionCompanies = productionCompanies?.mapNotNull { it.name } ?: emptyList(),
            cast = cast?.mapNotNull { c ->
                val name = c.name ?: return@mapNotNull null
                HDGharTVStorage.CastMember(name, c.character ?: "", c.profilePath)
            } ?: emptyList()
        )
    }

    // --- HOMEPAGE ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = mutableListOf<HomePageList>()
        val base = apiBase()
        var hasNext = false
        val limit = 20

        try {
            when (request.data) {
                "movies" -> {
                    val res = app.get("$base/api/movies/public?page=$page&limit=$limit", referer = "$base/")
                    val parsed = parseJson<MediaListResponse>(res.text)
                    val items = parsed.data?.mapNotNull { it.toSearchResponse("movie") } ?: emptyList()
                    if (items.isNotEmpty()) {
                        val totalCount = parsed.total ?: items.size
                        lists.add(HomePageList("All Movies ($totalCount)", items, isHorizontalImages = false)) // Unshuffled
                    }
                    val totalPages = parsed.totalPages ?: if (parsed.total != null) (parsed.total + limit - 1) / limit else 1
                    hasNext = page < totalPages
                }
                "series" -> {
                    val res = app.get("$base/api/series/public?page=$page&limit=$limit", referer = "$base/")
                    val parsed = parseJson<MediaListResponse>(res.text)
                    val items = parsed.data?.mapNotNull { it.toSearchResponse("series") } ?: emptyList()
                    if (items.isNotEmpty()) {
                        val totalCount = parsed.total ?: items.size
                        lists.add(HomePageList("All Series ($totalCount)", items, isHorizontalImages = false)) // Unshuffled
                    }
                    val totalPages = parsed.totalPages ?: if (parsed.total != null) (parsed.total + limit - 1) / limit else 1
                    hasNext = page < totalPages
                }
                "smart" -> {
                    syncAndCrawl() // Run instant sync & background crawler
                    val allRecords = HDGharTVStorage.getAll()

                    if (allRecords.isNotEmpty()) {
                        // 1. Recently Added
                        val recent = allRecords.sortedByDescending { it.ts }.map { it.toSearchResponse() }
                        if (recent.isNotEmpty()) lists.add(HomePageList("🆕 Recently Added (${recent.size})", recent, isHorizontalImages = false))

                        // 2. Top Trending Movies
                        val trendingMovies = allRecords.filter { it.type == "movie" && it.voteAverage >= 7.0 }
                            .sortedByDescending { it.voteAverage }.map { it.toSearchResponse() }
                        if (trendingMovies.isNotEmpty()) lists.add(HomePageList("🔥 Top Trending Movies (${trendingMovies.size})", trendingMovies.shuffled(), isHorizontalImages = false))

                        // 3. Top Trending Series
                        val trendingSeries = allRecords.filter { it.type == "series" && it.voteAverage >= 7.0 }
                            .sortedByDescending { it.voteAverage }.map { it.toSearchResponse() }
                        if (trendingSeries.isNotEmpty()) lists.add(HomePageList("🔥 Top Trending Series (${trendingSeries.size})", trendingSeries.shuffled(), isHorizontalImages = false))

                        // 4. Region Grouping (No limit)
                        val regions = listOf("Hollywood", "Bollywood", "Chinese", "Korean", "Anime")
                        for (region in regions) {
                            val items = allRecords.filter { it.getRegion() == region }.map { it.toSearchResponse() }
                            if (items.size >= 2) {
                                val flag = when(region) { "Hollywood" -> "🎬"; "Bollywood" -> "🇮🇳"; "Chinese" -> "🇨🇳"; "Korean" -> "🇰🇷"; "Anime" -> "🌸"; else -> "🌐" }
                                lists.add(HomePageList("$flag $region (${items.size})", items.shuffled(), isHorizontalImages = false))
                            }
                        }

                        // 5. Genre Grouping (No limit)
                        val genreBuckets = LinkedHashMap<String, MutableList<HDGharTVStorage.MediaRecord>>()
                        allRecords.forEach { rec ->
                            rec.genres.forEach { genre ->
                                genreBuckets.getOrPut(genre) { mutableListOf() }.add(rec)
                            }
                        }
                        genreBuckets.entries.sortedByDescending { it.value.size }.forEach { (genre, items) ->
                            val mapped = items.map { it.toSearchResponse() }
                            if (mapped.size >= 2) lists.add(HomePageList("🎭 $genre (${mapped.size})", mapped.shuffled(), isHorizontalImages = false))
                        }

                        // 6. Year Grouping (No limit)
                        val years = allRecords.map { it.getYear() }.filter { it.isNotBlank() }.distinct().sortedDescending()
                        years.forEach { year ->
                            val items = allRecords.filter { it.getYear() == year }.map { it.toSearchResponse() }
                            if (items.size >= 2) lists.add(HomePageList("📅 Year $year (${items.size})", items.shuffled(), isHorizontalImages = false))
                        }
                    }
                    hasNext = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage: ${e.message}")
        }

        return newHomePageResponse(lists, hasNext = hasNext)
    }

    private fun MediaItem.toSearchResponse(type: String): SearchResponse? {
        val id = id ?: return null
        val title = title ?: originalTitle ?: return null
        val loadData = LoadData(id = id, type = type, title = title, posterUrl = posterPath)
        return newMovieSearchResponse(title, loadData.toJson(), if (type == "series") TvType.TvSeries else TvType.Movie) {
            this.posterUrl = posterPath // Fixed poster mapping
        }
    }

    private fun HDGharTVStorage.MediaRecord.toSearchResponse(): SearchResponse {
        val loadData = LoadData(id = this.id, type = this.type, title = this.title, posterUrl = this.posterPath)
        return newMovieSearchResponse(this.title, loadData.toJson(), if (this.type == "series") TvType.TvSeries else TvType.Movie) {
            this.posterUrl = this.posterPath // Fixed poster mapping
        }
    }

    // --- SEARCH (Powered by Local Storage) ---
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val allRecords = HDGharTVStorage.getAll()
        return allRecords.filter { it.title.contains(query, ignoreCase = true) }.map { it.toSearchResponse() }
    }

    // --- DETAILS PAGE ---
    override suspend fun load(url: String): LoadResponse? {
        val loadData = try { parseJson<LoadData>(url) } catch (e: Exception) { return null }
        val base = apiBase()

        return try {
            val endpoint = if (loadData.type == "movie") "movies" else "series"
            val res = app.get("$base/api/$endpoint/public/${loadData.id}", referer = "$base/")
            val item = parseJson<MediaItem>(res.text) ?: return null
            val title = item.title ?: item.originalTitle ?: loadData.title
            val streams = item.streamingLinks?.filter { it.isActive != false && !it.url.isNullOrBlank() } ?: emptyList()

            // Build UI Tags (Genres + Languages + Production Companies)
            val tags = mutableListOf<String>()
            item.genres?.mapNotNull { it.name }?.let { tags.addAll(it) }
            item.languages?.let { tags.addAll(it) }
            item.productionCompanies?.mapNotNull { it.name }?.let { tags.addAll(it) }

            // Build Cast List
            val actors = item.cast?.mapNotNull { c ->
                val name = c.name ?: return@mapNotNull null
                ActorData(Actor(name), roleString = c.character)
            } ?: emptyList()

            if (loadData.type == "movie") {
                newMovieLoadResponse(title, url, TvType.Movie, streams.toJson()) {
                    this.posterUrl = item.posterPath ?: loadData.posterUrl
                    this.backgroundPosterUrl = item.backdropPath
                    this.plot = item.overview
                    this.year = item.releaseDate?.substring(0, 4)?.toIntOrNull()
                    this.tags = tags
                    this.duration = item.runtime
                    this.score = item.voteAverage?.let { Score.from10(it.toString()) }
                    this.contentRating = item.certification ?: item.contentRating // Fixed certification mapping
                    this.actors = actors
                }
            } else {
                val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
                item.seasons?.forEach { season ->
                    val seasonNum = season.seasonNumber ?: return@forEach
                    season.episodes?.forEach { ep ->
                        val epNum = ep.episodeNumber ?: return@forEach
                        val epStreams = ep.streamingLinks?.filter { it.isActive != false && !it.url.isNullOrBlank() } ?: emptyList()
                        episodes.add(newEpisode(epStreams.toJson()) {
                            this.name = ep.name ?: "Episode $epNum"
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = ep.stillPath
                            this.description = ep.overview
                            this.runTime = ep.runtime
                        })
                    }
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = item.posterPath ?: loadData.posterUrl
                    this.backgroundPosterUrl = item.backdropPath
                    this.plot = item.overview
                    this.year = item.firstAirDate?.substring(0, 4)?.toIntOrNull()
                    this.tags = tags
                    this.score = item.voteAverage?.let { Score.from10(it.toString()) }
                    this.contentRating = item.certification ?: item.contentRating // Fixed certification mapping
                    this.actors = actors
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            null
        }
    }

    // --- VIDEO LINKS ---
    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streams = try { parseJson<List<StreamLink>>(data) } catch (e: Exception) { return false }
        if (streams.isEmpty()) return false
        val base = apiBase()
        var found = false

        for (stream in streams) {
            val url = stream.url ?: continue
            if (url.isBlank()) continue

            val quality = when (stream.quality?.lowercase()) {
                "4k", "2160p" -> Qualities.P2160.value
                "1080p", "fhd" -> Qualities.P1080.value
                "720p", "hd" -> Qualities.P720.value
                "480p", "sd" -> Qualities.P480.value
                "360p" -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            val type = when {
                url.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                url.contains(".mp4", ignoreCase = true) -> ExtractorLinkType.VIDEO
                else -> ExtractorLinkType.VIDEO
            }

            val headers = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                "Referer" to "$base/"
            )
            if (stream.headers?.isNotBlank() == true) {
                try { headers.putAll(parseJson<Map<String, String>>(stream.headers)) } catch (_: Exception) {}
            }
            if (stream.userAgent?.isNotBlank() == true) headers["User-Agent"] = stream.userAgent

            callback.invoke(newExtractorLink(this.name, stream.quality ?: "Unknown", url, type) {
                this.quality = quality; this.headers = headers
            })
            found = true
        }
        return found
    }
}
