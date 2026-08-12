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
    data class ProductionCountry(@JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SpokenLanguage(@JsonProperty("englishName") val englishName: String? = null, @JsonProperty("name") val name: String? = null)

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
    data class MediaListResponse(
        @JsonProperty("data") val data: List<MediaItem>? = null,
        @JsonProperty("totalPages") val totalPages: Int? = null,
        @JsonProperty("total") val total: Int? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LoadData(val id: String, val type: String, val title: String, val posterUrl: String? = null)

    // Helper to determine region for local storage
    private fun MediaItem.getRegion(): String {
        val countries = productionCountries?.joinToString(",") { it.name ?: "" }?.lowercase() ?: ""
        val langs = spokenLanguages?.joinToString(",") { it.englishName ?: it.name ?: "" }?.lowercase() ?: ""
        return when {
            countries.contains("united states") || langs.contains("english") -> "Hollywood"
            countries.contains("india") || langs.contains("hindi") || langs.contains("tamil") || langs.contains("telugu") -> "Bollywood"
            countries.contains("korea") -> "Korean"
            countries.contains("japan") || langs.contains("japanese") || genres?.any { it.name?.equals("Anime", true) == true } == true -> "Anime"
            else -> ""
        }
    }

    // --- BACKGROUND CRAWLER ---
    private fun kickCrawler() {
        if (crawling) return
        crawling = true
        crawlerScope.launch {
            try {
                val base = apiBase()
                var (moviePage, seriesPage) = HDGharTVStorage.getCrawlerState()
                val limit = 50

                // Fetch 2 pages of movies and 2 pages of series per background cycle
                for (i in 1..2) {
                    val mRes = app.get("$base/api/movies/public?page=$moviePage&limit=$limit", referer = "$base/").text
                    val mParsed = parseJson<MediaListResponse>(mRes)
                    val mRecords = mParsed.data?.mapNotNull { item ->
                        val id = item.id ?: return@mapNotNull null
                        val title = item.title ?: item.originalTitle ?: return@mapNotNull null
                        HDGharTVStorage.MediaRecord(
                            id = id, type = "movie", title = title,
                            genres = item.genres?.mapNotNull { it.name } ?: emptyList(),
                            year = (item.releaseDate ?: "").substringBefore("-"),
                            rating = item.voteAverage ?: 0.0,
                            region = item.getRegion()
                        )
                    } ?: emptyList()
                    if (mRecords.isNotEmpty()) HDGharTVStorage.addRichBatch(mRecords)
                    if (mParsed.data?.size ?: 0 < limit) moviePage = 1 else moviePage++
                }

                for (i in 1..2) {
                    val sRes = app.get("$base/api/series/public?page=$seriesPage&limit=$limit", referer = "$base/").text
                    val sParsed = parseJson<MediaListResponse>(sRes)
                    val sRecords = sParsed.data?.mapNotNull { item ->
                        val id = item.id ?: return@mapNotNull null
                        val title = item.title ?: item.originalTitle ?: return@mapNotNull null
                        HDGharTVStorage.MediaRecord(
                            id = id, type = "series", title = title,
                            genres = item.genres?.mapNotNull { it.name } ?: emptyList(),
                            year = (item.firstAirDate ?: "").substringBefore("-"),
                            rating = item.voteAverage ?: 0.0,
                            region = item.getRegion()
                        )
                    } ?: emptyList()
                    if (sRecords.isNotEmpty()) HDGharTVStorage.addRichBatch(sRecords)
                    if (sParsed.data?.size ?: 0 < limit) seriesPage = 1 else seriesPage++
                }

                HDGharTVStorage.saveCrawlerState(moviePage, seriesPage)
            } catch (e: Exception) {
                Log.e(TAG, "Crawler error: ${e.message}")
            } finally {
                crawling = false
            }
        }
    }

    // --- HOMEPAGE (Built entirely from Local Storage) ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        kickCrawler() // Start fetching in background
        val allRecords = HDGharTVStorage.getAll()
        val rows = mutableListOf<HomePageList>()

        if (allRecords.isEmpty()) {
            return newHomePageResponse(listOf(HomePageList("Loading Catalog...", emptyList(), false)), hasNext = false)
        }

        // 1. Recently Added (Sorted by timestamp)
        val recent = allRecords.sortedByDescending { it.ts }.take(60).map { it.toSearchResponse() }
        if (recent.isNotEmpty()) rows.add(HomePageList("🆕 Recently Added (${recent.size})", recent, isHorizontalImages = false))

        // 2. Top Trending Movies
        val trendingMovies = allRecords.filter { it.type == "movie" && it.rating >= 7.0 }.take(60).map { it.toSearchResponse() }
        if (trendingMovies.isNotEmpty()) rows.add(HomePageList("🔥 Top Trending Movies (${trendingMovies.size})", trendingMovies.shuffled(), isHorizontalImages = false))

        // 3. Top Trending Series
        val trendingSeries = allRecords.filter { it.type == "series" && it.rating >= 7.0 }.take(60).map { it.toSearchResponse() }
        if (trendingSeries.isNotEmpty()) rows.add(HomePageList("🔥 Top Trending Series (${trendingSeries.size})", trendingSeries.shuffled(), isHorizontalImages = false))

        // 4. Region Grouping
        val regions = listOf("Hollywood", "Bollywood", "Korean", "Anime")
        for (region in regions) {
            val items = allRecords.filter { it.region == region }.take(60).map { it.toSearchResponse() }
            if (items.size >= 3) {
                val flag = when(region) { "Hollywood" -> "🎬"; "Bollywood" -> "🇮🇳"; "Korean" -> "🇰🇷"; "Anime" -> "🌸"; else -> "🌐" }
                rows.add(HomePageList("$flag $region (${items.size})", items.shuffled(), isHorizontalImages = false))
            }
        }

        // 5. Genre Grouping
        val genreBuckets = LinkedHashMap<String, MutableList<HDGharTVStorage.MediaRecord>>()
        allRecords.forEach { rec ->
            rec.genres.forEach { genre ->
                genreBuckets.getOrPut(genre) { mutableListOf() }.add(rec)
            }
        }
        genreBuckets.entries.sortedByDescending { it.value.size }.take(10).forEach { (genre, items) ->
            val mapped = items.take(60).map { it.toSearchResponse() }
            if (mapped.size >= 3) rows.add(HomePageList("🎭 $genre (${mapped.size})", mapped.shuffled(), isHorizontalImages = false))
        }

        // 6. Year Grouping
        val years = allRecords.map { it.year }.filter { it.isNotBlank() }.distinct().sortedDescending()
        years.take(5).forEach { year ->
            val items = allRecords.filter { it.year == year }.take(60).map { it.toSearchResponse() }
            if (items.size >= 3) rows.add(HomePageList("📅 Year $year (${items.size})", items.shuffled(), isHorizontalImages = false))
        }

        return newHomePageResponse(rows, hasNext = false)
    }

    private fun HDGharTVStorage.MediaRecord.toSearchResponse(): SearchResponse {
        val loadData = LoadData(id = this.id, type = this.type, title = this.title)
        return newMovieSearchResponse(this.title, loadData.toJson(), if (this.type == "series") TvType.TvSeries else TvType.Movie) {
            this.posterUrl = "https://image.tmdb.org/t/p/w500/${this@toSearchResponse.id}.jpg" // Fallback poster logic if needed, or leave blank
        }
    }

    // --- SEARCH (Powered entirely by Local Storage) ---
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val allRecords = HDGharTVStorage.getAll()
        return allRecords.filter { it.title.contains(query, ignoreCase = true) }.map { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val loadData = try { parseJson<LoadData>(url) } catch (e: Exception) { return null }
        val base = apiBase()

        return try {
            val endpoint = if (loadData.type == "movie") "movies" else "series"
            val res = app.get("$base/api/$endpoint/public/${loadData.id}", referer = "$base/")
            val item = parseJson<MediaItem>(res.text)
            val title = item.title ?: item.originalTitle ?: loadData.title
            val streams = item.streamingLinks?.filter { it.isActive != false && !it.url.isNullOrBlank() } ?: emptyList()

            val tags = mutableListOf<String>()
            item.genres?.mapNotNull { it.name }?.let { tags.addAll(it) }
            item.spokenLanguages?.mapNotNull { it.englishName }?.let { tags.addAll(it) }
            item.productionCountries?.mapNotNull { it.name }?.let { tags.addAll(it) }

            if (loadData.type == "movie") {
                newMovieLoadResponse(title, url, TvType.Movie, streams.toJson()) {
                    this.posterUrl = item.posterPath ?: loadData.posterUrl
                    this.backgroundPosterUrl = item.backdropPath
                    this.plot = item.overview
                    this.year = item.releaseDate?.substring(0, 4)?.toIntOrNull()
                    this.tags = tags
                    this.duration = item.runtime
                    this.score = item.voteAverage?.let { Score.from10(it.toString()) }
                    this.contentRating = item.certification ?: item.contentRating
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
                    this.contentRating = item.certification ?: item.contentRating
                }
            }
        } catch (e: Exception) { null }
    }

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
