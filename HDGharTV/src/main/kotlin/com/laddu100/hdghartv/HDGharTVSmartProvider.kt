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

class HDGharTVSmartProvider : MainAPI() {
    override var mainUrl = "https://hdghartv.cc"
    override var name = "HDGharTV Smart"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true // Download button enabled
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val fallbackApiBase = "https://hdghartv.cc"
    private val TAG = "HDGharTVSmart"

    private suspend fun apiBase(): String {
        val domain = FirebaseDomainHelper.getDomain("hdghartv")
        return (domain ?: fallbackApiBase).removeSuffix("/")
    }

    // --- DATA CLASSES (Stores all content details the platform gives) ---
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
        @JsonProperty("voteAverage") val voteAverage: Double? = null,
        @JsonProperty("runtime") val runtime: Int? = null,
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("certification") val certification: String? = null,
        @JsonProperty("contentRating") val contentRating: String? = null,
        @JsonProperty("productionCountries") val productionCountries: List<ProductionCountry>? = null,
        @JsonProperty("spokenLanguages") val spokenLanguages: List<SpokenLanguage>? = null,
        @JsonProperty("streamingLinks") val streamingLinks: List<StreamLink>? = null,
        @JsonProperty("seasons") val seasons: List<Season>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Genre(@JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ProductionCountry(@JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SpokenLanguage(@JsonProperty("englishName") val englishName: String? = null, @JsonProperty("name") val name: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MediaListResponse(
        @JsonProperty("data") val data: List<MediaItem>? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null,
        @JsonProperty("total") val total: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiSearchResponse(
        @JsonProperty("movies") val movies: List<MediaItem>? = null,
        @JsonProperty("series") val series: List<MediaItem>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LoadData(
        val id: String,
        val type: String,
        val title: String,
        val posterUrl: String? = null
    )

    override val mainPage = mainPageOf(
        "smart" to "Smart Catalog",
        "movies" to "All Movies",
        "series" to "All Series"
    )

    private fun MediaItem.isFromCountry(country: String): Boolean {
        return productionCountries?.any { it.name?.contains(country, true) == true } == true ||
               spokenLanguages?.any { it.englishName?.contains(country, true) == true || it.name?.contains(country, true) == true } == true
    }

    private fun MediaItem.getYear(): String? {
        return (releaseDate ?: firstAirDate)?.substring(0, 4)
    }

    private fun MediaItem.toSearchResponse(type: String): SearchResponse? {
        val id = id ?: return null
        val title = title ?: originalTitle ?: return null
        val loadData = LoadData(id = id, type = type, title = title, posterUrl = posterPath)
        return newMovieSearchResponse(title, loadData.toJson(), if (type == "series") TvType.TvSeries else TvType.Movie) {
            this.posterUrl = posterPath
        }
    }

    // Safely fetch 100 movies and 100 series to build smart rows
    private suspend fun fetchSmartBatch(): List<Pair<MediaItem, String>> {
        val base = apiBase()
        val allMedia = mutableListOf<Pair<MediaItem, String>>()
        try {
            for (i in 1..2) {
                val mRes = app.get("$base/api/movies/public?page=$i&limit=50", referer = "$base/").text
                val mParsed = parseJson<MediaListResponse>(mRes)
                mParsed.data?.forEach { allMedia.add(it to "movie") }
                if (mParsed.data?.size ?: 0 < 50) break
            }
            for (i in 1..2) {
                val sRes = app.get("$base/api/series/public?page=$i&limit=50", referer = "$base/").text
                val sParsed = parseJson<MediaListResponse>(sRes)
                sParsed.data?.forEach { allMedia.add(it to "series") }
                if (sParsed.data?.size ?: 0 < 50) break
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchSmartBatch: ${e.message}")
        }
        return allMedia
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = mutableListOf<HomePageList>()
        val base = apiBase()
        var hasNext = false
        val limit = 20 // Safe limit for pagination

        try {
            when (request.data) {
                "movies" -> {
                    val res = app.get("$base/api/movies/public?page=$page&limit=$limit", referer = "$base/")
                    val parsed = parseJson<MediaListResponse>(res.text)
                    val items = parsed.data?.mapNotNull { it.toSearchResponse("movie") } ?: emptyList()
                    if (items.isNotEmpty()) {
                        val totalCount = parsed.total ?: items.size
                        // Unshuffled as requested
                        lists.add(HomePageList("All Movies ($totalCount)", items, isHorizontalImages = false))
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
                        // Unshuffled as requested
                        lists.add(HomePageList("All Series ($totalCount)", items, isHorizontalImages = false))
                    }
                    val totalPages = parsed.totalPages ?: if (parsed.total != null) (parsed.total + limit - 1) / limit else 1
                    hasNext = page < totalPages
                }
                "smart" -> {
                    if (page == 1) {
                        val allMedia = fetchSmartBatch()

                        // 1. Recently Added (Sorted by DB ID which contains timestamp)
                        val recent = allMedia.sortedByDescending { it.first.id ?: "" }.take(30)
                            .mapNotNull { it.first.toSearchResponse(it.second) }
                        if (recent.isNotEmpty()) lists.add(HomePageList("🆕 Recently Added (${recent.size})", recent, isHorizontalImages = false))

                        // 2. Top Trending Movies (Rating >= 7.0)
                        val trendingMovies = allMedia.filter { it.second == "movie" && (it.first.voteAverage ?: 0.0) >= 7.0 }
                            .mapNotNull { it.first.toSearchResponse("movie") }
                        if (trendingMovies.isNotEmpty()) lists.add(HomePageList("🔥 Top Trending Movies (${trendingMovies.size})", trendingMovies.shuffled(), isHorizontalImages = false))

                        // 3. Top Trending Series (Rating >= 7.0)
                        val trendingSeries = allMedia.filter { it.second == "series" && (it.first.voteAverage ?: 0.0) >= 7.0 }
                            .mapNotNull { it.first.toSearchResponse("series") }
                        if (trendingSeries.isNotEmpty()) lists.add(HomePageList("🔥 Top Trending Series (${trendingSeries.size})", trendingSeries.shuffled(), isHorizontalImages = false))

                        // 4. Region Grouping (Shuffled)
                        val hollywood = allMedia.filter { it.first.isFromCountry("United States") || it.first.isFromCountry("English") }
                            .mapNotNull { it.first.toSearchResponse(it.second) }
                        if (hollywood.isNotEmpty()) lists.add(HomePageList("🎬 Hollywood (${hollywood.size})", hollywood.shuffled(), isHorizontalImages = false))

                        val bollywood = allMedia.filter { it.first.isFromCountry("India") || it.first.isFromCountry("Hindi") }
                            .mapNotNull { it.first.toSearchResponse(it.second) }
                        if (bollywood.isNotEmpty()) lists.add(HomePageList("🇮🇳 Bollywood (${bollywood.size})", bollywood.shuffled(), isHorizontalImages = false))

                        val korean = allMedia.filter { it.first.isFromCountry("Korea") }
                            .mapNotNull { it.first.toSearchResponse(it.second) }
                        if (korean.isNotEmpty()) lists.add(HomePageList("🇰🇷 Korean (${korean.size})", korean.shuffled(), isHorizontalImages = false))

                        val anime = allMedia.filter { 
                            it.first.genres?.any { g -> g.name?.contains("Anime", true) == true || g.name?.contains("Animation", true) == true } == true || it.first.isFromCountry("Japan") 
                        }.mapNotNull { it.first.toSearchResponse(it.second) }
                        if (anime.isNotEmpty()) lists.add(HomePageList("🌸 Anime (${anime.size})", anime.shuffled(), isHorizontalImages = false))

                        // 5. Genre-wise Grouping (Shuffled)
                        val genres = listOf("Action", "Comedy", "Horror", "Romance", "Sci-Fi", "Thriller", "Drama", "Crime")
                        for (genre in genres) {
                            val items = allMedia.filter { (m, _) ->
                                m.genres?.any { it.name?.equals(genre, true) == true } == true
                            }.mapNotNull { it.first.toSearchResponse(it.second) }
                            if (items.size >= 3) {
                                lists.add(HomePageList("🎭 $genre (${items.size})", items.shuffled(), isHorizontalImages = false))
                            }
                        }

                        // 6. Year-wise Grouping (Shuffled)
                        val years = allMedia.mapNotNull { it.first.getYear() }.distinct().sortedDescending()
                        for (year in years) {
                            val items = allMedia.filter { it.first.getYear() == year }
                                .mapNotNull { it.first.toSearchResponse(it.second) }
                            if (items.size >= 3) {
                                lists.add(HomePageList("📅 Year $year (${items.size})", items.shuffled(), isHorizontalImages = false))
                            }
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

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val base = apiBase()
        val results = mutableListOf<SearchResponse>()

        try {
            for (page in 1..5) {
                val res = app.get("$base/api/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page", referer = "$base/")
                val parsed = parseJson<ApiSearchResponse>(res.text)
                
                val movies = parsed.movies
                val series = parsed.series
                
                if (movies.isNullOrEmpty() && series.isNullOrEmpty()) break
                
                movies?.forEach { m -> m.toSearchResponse("movie")?.let { results.add(it) } }
                series?.forEach { s -> s.toSearchResponse("series")?.let { results.add(it) } }
                
                if ((movies?.size ?: 0) < 20 && (series?.size ?: 0) < 20) break
            }
        } catch (e: Exception) {
            Log.e(TAG, "search: ${e.message}")
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val loadData = try {
            parseJson<LoadData>(url)
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            return null
        }

        val base = apiBase()

        return try {
            when (loadData.type) {
                "movie" -> {
                    val res = app.get("$base/api/movies/public/${loadData.id}", referer = "$base/")
                    val movie = parseJson<MediaItem>(res.text)
                    val title = movie.title ?: movie.originalTitle ?: loadData.title
                    val streams = movie.streamingLinks?.filter { it.isActive != false && !it.url.isNullOrBlank() } ?: emptyList()

                    // Build UI Tags (Genres + Languages + Countries)
                    val tags = mutableListOf<String>()
                    movie.genres?.mapNotNull { it.name }?.let { tags.addAll(it) }
                    movie.spokenLanguages?.mapNotNull { it.englishName }?.let { tags.addAll(it) }
                    movie.productionCountries?.mapNotNull { it.name }?.let { tags.addAll(it) }

                    newMovieLoadResponse(title, url, TvType.Movie, streams.toJson()) {
                        this.posterUrl = movie.posterPath ?: loadData.posterUrl
                        this.backgroundPosterUrl = movie.backdropPath
                        this.plot = movie.overview
                        this.year = movie.releaseDate?.substring(0, 4)?.toIntOrNull()
                        this.tags = tags
                        this.duration = movie.runtime
                        this.score = movie.voteAverage?.let { Score.from10(it.toString()) }
                        this.contentRating = movie.certification ?: movie.contentRating
                    }
                }
                "series" -> {
                    val res = app.get("$base/api/series/public/${loadData.id}", referer = "$base/")
                    val series = parseJson<MediaItem>(res.text)
                    val title = series.title ?: series.originalTitle ?: loadData.title

                    val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
                    series.seasons?.forEach { season ->
                        val seasonNum = season.seasonNumber ?: return@forEach
                        season.episodes?.forEach { ep ->
                            val epNum = ep.episodeNumber ?: return@forEach
                            val streams = ep.streamingLinks?.filter { it.isActive != false && !it.url.isNullOrBlank() } ?: emptyList()
                            val epData = streams.toJson()

                            episodes.add(
                                newEpisode(epData) {
                                    this.name = ep.name ?: "Episode $epNum"
                                    this.season = seasonNum
                                    this.episode = epNum
                                    this.posterUrl = ep.stillPath
                                    this.description = ep.overview
                                    this.runTime = ep.runtime
                                }
                            )
                        }
                    }

                    val tags = mutableListOf<String>()
                    series.genres?.mapNotNull { it.name }?.let { tags.addAll(it) }
                    series.spokenLanguages?.mapNotNull { it.englishName }?.let { tags.addAll(it) }
                    series.productionCountries?.mapNotNull { it.name }?.let { tags.addAll(it) }

                    newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                        this.posterUrl = series.posterPath ?: loadData.posterUrl
                        this.backgroundPosterUrl = series.backdropPath
                        this.plot = series.overview
                        this.year = series.firstAirDate?.substring(0, 4)?.toIntOrNull()
                        this.tags = tags
                        this.score = series.voteAverage?.let { Score.from10(it.toString()) }
                        this.contentRating = series.certification ?: series.contentRating
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val streams = try {
            parseJson<List<StreamLink>>(data)
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: ${e.message}")
            return false
        }

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
                try {
                    val extraHeaders = parseJson<Map<String, String>>(stream.headers)
                    headers.putAll(extraHeaders)
                } catch (_: Exception) {}
            }
            if (stream.userAgent?.isNotBlank() == true) {
                headers["User-Agent"] = stream.userAgent
            }

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = stream.quality ?: "Unknown",
                    url = url,
                    type = type
                ) {
                    this.quality = quality
                    this.headers = headers
                }
            )
            found = true
        }

        return found
    }
}
