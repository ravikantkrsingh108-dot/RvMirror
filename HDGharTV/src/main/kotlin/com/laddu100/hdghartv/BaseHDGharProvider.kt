package com.laddu100.hdghartv

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

open class BaseHDGharProvider : MainAPI() {
    override var mainUrl = "https://hdghartv.cc"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    protected val fallbackApiBase = "https://hdghartv.cc"
    protected val TAG = "HDGharBase"

    protected suspend fun apiBase(): String {
        val domain = FirebaseDomainHelper.getDomain("hdghartv")
        return (domain ?: fallbackApiBase).removeSuffix("/")
    }

    protected val crawlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile protected var crawling = false

    // API Data Classes
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiStreamLink(@JsonProperty("quality") val quality: String? = null, @JsonProperty("url") val url: String? = null, @JsonProperty("type") val type: String? = null, @JsonProperty("language") val language: String? = null, @JsonProperty("isActive") val isActive: Boolean? = null, @JsonProperty("headers") val headers: String? = null, @JsonProperty("userAgent") val userAgent: String? = null)
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiEpisode(@JsonProperty("episodeNumber") val episodeNumber: Int? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("overview") val overview: String? = null, @JsonProperty("stillPath") val stillPath: String? = null, @JsonProperty("runtime") val runtime: Int? = null, @JsonProperty("streamingLinks") val streamingLinks: List<ApiStreamLink>? = null)
    
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiSeason(@JsonProperty("seasonNumber") val seasonNumber: Int? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("overview") val overview: String? = null, @JsonProperty("posterPath") val posterPath: String? = null, @JsonProperty("episodes") val episodes: List<ApiEpisode>? = null)
    
    @JsonIgnoreProperties(ignoreUnknown = true) data class ApiGenre(@JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true) data class ApiCompany(@JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true) data class ApiSpokenLanguage(@JsonProperty("englishName") val englishName: String? = null, @JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true) data class ApiCollection(@JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true) data class ApiCastMember(@JsonProperty("name") val name: String? = null, @JsonProperty("character") val character: String? = null, @JsonProperty("profilePath") val profilePath: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiMediaItem(
        @JsonProperty("_id") val id: String? = null, @JsonProperty("title") val title: String? = null, @JsonProperty("originalTitle") val originalTitle: String? = null,
        @JsonProperty("overview") val overview: String? = null, @JsonProperty("posterPath") val posterPath: String? = null, @JsonProperty("backdropPath") val backdropPath: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null, @JsonProperty("firstAirDate") val firstAirDate: String? = null,
        @JsonProperty("genres") val genres: List<ApiGenre>? = null, @JsonProperty("categories") val categories: List<String>? = null,
        @JsonProperty("networks") val networks: List<ApiCompany>? = null, @JsonProperty("productionCompanies") val productionCompanies: List<ApiCompany>? = null,
        @JsonProperty("belongs_to_collection") val collection: ApiCollection? = null, @JsonProperty("originalLanguage") val originalLanguage: String? = null,
        @JsonProperty("spokenLanguages") val spokenLanguages: List<ApiSpokenLanguage>? = null, @JsonProperty("voteAverage") val voteAverage: Double? = null,
        @JsonProperty("voteCount") val voteCount: Int? = null, @JsonProperty("viewCount") val viewCount: Int? = null, @JsonProperty("popularity") val popularity: Double? = null,
        @JsonProperty("runtime") val runtime: Int? = null, @JsonProperty("status") val status: String? = null,
        @JsonProperty("certifications") val certifications: List<Any>? = null, 
        @JsonProperty("contentRating") val contentRating: String? = null,
        @JsonProperty("cast") val cast: List<ApiCastMember>? = null, @JsonProperty("streamingLinks") val streamingLinks: List<ApiStreamLink>? = null,
        @JsonProperty("seasons") val seasons: List<ApiSeason>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true) data class ApiMediaListResponse(@JsonProperty("data") val data: List<ApiMediaItem>? = null, @JsonProperty("totalPages") val totalPages: Int? = null, @JsonProperty("total") val total: Int? = null)
    @JsonIgnoreProperties(ignoreUnknown = true) data class LoadData(val id: String, val type: String, val title: String, val posterUrl: String? = null)

    // Helper to extract certification prioritizing US and IN
    private fun extractCertification(certs: List<Any>?): String {
        if (certs.isNullOrEmpty()) return ""
        
        // First pass: Look specifically for US and IN
        for (c in certs) {
            when (c) {
                is Map<*, *> -> {
                    val country = c["iso_3166_1"]?.toString()
                    val cert = c["certification"]?.toString()
                    if (!cert.isNullOrBlank() && (country == "US" || country == "IN")) {
                        return cert
                    }
                }
            }
        }
        
        // Fallback: If US/IN not found, return the first available certification
        return certs.mapNotNull { c ->
            when (c) {
                is String -> c
                is Map<*, *> -> c["certification"]?.toString()
                else -> null
            }
        }.firstOrNull { it.isNotBlank() } ?: ""
    }

    protected fun ApiMediaItem.toLocalRecord(type: String): HDGharTVStorage.MediaRecord? {
        val recordId = id ?: return null
        val recordTitle = title ?: originalTitle ?: return null
        val cert = extractCertification(certifications).ifBlank { contentRating ?: "" }
        return HDGharTVStorage.MediaRecord(
            id = recordId, type = type, title = recordTitle, overview = overview ?: "", posterPath = posterPath ?: "",
            backdropPath = backdropPath ?: "", releaseDate = releaseDate ?: "", firstAirDate = firstAirDate ?: "",
            genres = if (!genres.isNullOrEmpty()) genres.mapNotNull { g -> g.name } else emptyList(),
            categories = categories ?: emptyList(),
            networks = if (!networks.isNullOrEmpty()) networks.mapNotNull { n -> n.name } else emptyList(),
            studios = if (!productionCompanies.isNullOrEmpty()) productionCompanies.mapNotNull { p -> p.name } else emptyList(),
            collection = collection?.name ?: "", originalLanguage = originalLanguage ?: "",
            spokenLanguages = if (!spokenLanguages.isNullOrEmpty()) spokenLanguages.mapNotNull { l -> l.englishName ?: l.name } else emptyList(),
            voteAverage = voteAverage ?: 0.0, viewCount = voteCount ?: viewCount ?: 0, popularity = popularity ?: 0.0,
            runtime = runtime ?: 0, status = status ?: "", certification = cert,
            cast = if (!cast.isNullOrEmpty()) cast.mapNotNull { c -> val name = c.name ?: return@mapNotNull null; HDGharTVStorage.CastMember(name, c.character ?: "", c.profilePath) } else emptyList()
        )
    }

    protected suspend fun syncAndCrawl() {
        val base = apiBase()
        var allRecords = HDGharTVStorage.getAll()
        if (allRecords.isEmpty()) {
            try {
                for (i in 1..2) {
                    val mRes = app.get("$base/api/movies/public?page=$i&limit=50", referer = "$base/").text
                    val mParsed = tryParseJson<ApiMediaListResponse>(mRes)
                    val mRecords = if (mParsed != null && !mParsed.data.isNullOrEmpty()) mParsed.data.mapNotNull { item -> item.toLocalRecord("movie") } else emptyList()
                    if (mRecords.isNotEmpty()) HDGharTVStorage.addRichBatch(mRecords)
                    if ((mParsed?.data?.size ?: 0) < 50) break
                }
                for (i in 1..2) {
                    val sRes = app.get("$base/api/series/public?page=$i&limit=50", referer = "$base/").text
                    val sParsed = tryParseJson<ApiMediaListResponse>(sRes)
                    val sRecords = if (sParsed != null && !sParsed.data.isNullOrEmpty()) sParsed.data.mapNotNull { item -> item.toLocalRecord("series") } else emptyList()
                    if (sRecords.isNotEmpty()) HDGharTVStorage.addRichBatch(sRecords)
                    if ((sParsed?.data?.size ?: 0) < 50) break
                }
                allRecords = HDGharTVStorage.getAll()
            } catch (e: Exception) { Log.e(TAG, "Sync Error: ${e.message}") }
        }

        if (!crawling) {
            crawling = true
            crawlerScope.launch {
                try {
                    var (moviePage, seriesPage) = HDGharTVStorage.getCrawlerState()
                    if (moviePage == 1 && allRecords.isNotEmpty()) moviePage = 3
                    if (seriesPage == 1 && allRecords.isNotEmpty()) seriesPage = 3
                    val limit = 50
                    for (i in 1..2) {
                        val mRes = app.get("$base/api/movies/public?page=$moviePage&limit=$limit", referer = "$base/").text
                        val mParsed = tryParseJson<ApiMediaListResponse>(mRes)
                        val mRecords = if (mParsed != null && !mParsed.data.isNullOrEmpty()) mParsed.data.mapNotNull { item -> item.toLocalRecord("movie") } else emptyList()
                        if (mRecords.isNotEmpty()) HDGharTVStorage.addRichBatch(mRecords)
                        if ((mParsed?.data?.size ?: 0) < limit) moviePage = 1 else moviePage++
                    }
                    for (i in 1..2) {
                        val sRes = app.get("$base/api/series/public?page=$seriesPage&limit=$limit", referer = "$base/").text
                        val sParsed = tryParseJson<ApiMediaListResponse>(sRes)
                        val sRecords = if (sParsed != null && !sParsed.data.isNullOrEmpty()) sParsed.data.mapNotNull { item -> item.toLocalRecord("series") } else emptyList()
                        if (sRecords.isNotEmpty()) HDGharTVStorage.addRichBatch(sRecords)
                        if ((sParsed?.data?.size ?: 0) < limit) seriesPage = 1 else seriesPage++
                    }
                    HDGharTVStorage.saveCrawlerState(moviePage, seriesPage)
                } catch (e: Exception) { Log.e(TAG, "Crawler Error: ${e.message}") } finally { crawling = false }
            }
        }
    }

    protected fun HDGharTVStorage.MediaRecord.toSearchResponse(): SearchResponse {
        val loadData = LoadData(id = this.id, type = this.type, title = this.title, posterUrl = this.posterPath)
        return newMovieSearchResponse(this.title, loadData.toJson(), if (this.type == "series") TvType.TvSeries else TvType.Movie) { this.posterUrl = loadData.posterUrl }
    }

    override suspend fun load(url: String): LoadResponse? {
        val loadData = try { parseJson<LoadData>(url) } catch (e: Exception) { return null }
        val base = apiBase()
        return try {
            val endpoint = if (loadData.type == "movie") "movies" else "series"
            val res = app.get("$base/api/$endpoint/public/${loadData.id}", referer = "$base/")
            
            val item = tryParseJson<ApiMediaItem>(res.text) ?: return null
            
            // Save the rich detail data (like Collection) to local storage when opened
            val localRecord = item.toLocalRecord(loadData.type)
            if (localRecord != null) {
                HDGharTVStorage.addRich(localRecord)
            }
            
            val title = item.title ?: item.originalTitle ?: loadData.title
            
            val streams = if (!item.streamingLinks.isNullOrEmpty()) {
                item.streamingLinks.filter { link -> link.isActive != false && !link.url.isNullOrBlank() }
            } else {
                emptyList()
            }

            val tags = mutableListOf<String>()
            if (!item.genres.isNullOrEmpty()) item.genres.forEach { g -> if (!g.name.isNullOrBlank()) tags.add(g.name) }
            if (!item.spokenLanguages.isNullOrEmpty()) item.spokenLanguages.forEach { l -> val name = l.englishName ?: l.name; if (!name.isNullOrBlank()) tags.add(name) }
            if (!item.categories.isNullOrEmpty()) item.categories.forEach { c -> tags.add(c) }
            if (!item.networks.isNullOrEmpty()) item.networks.forEach { n -> if (!n.name.isNullOrBlank()) tags.add(n.name) }
            if (!item.productionCompanies.isNullOrEmpty()) item.productionCompanies.forEach { p -> if (!p.name.isNullOrBlank()) tags.add(p.name) }

            val actors = mutableListOf<ActorData>()
            if (!item.cast.isNullOrEmpty()) {
                item.cast.forEach { c ->
                    val name = c.name
                    if (!name.isNullOrBlank()) actors.add(ActorData(Actor(name), roleString = c.character))
                }
            }
            
            val cert = extractCertification(item.certifications).ifBlank { item.contentRating }

            val extraInfo = StringBuilder()
            val collectionName = item.collection?.name
            if (!collectionName.isNullOrBlank()) extraInfo.append("🎞️ Collection: $collectionName\n")
            val releaseDate = item.releaseDate
            if (!releaseDate.isNullOrBlank()) extraInfo.append("📅 Release Date: $releaseDate\n")
            val firstAirDate = item.firstAirDate
            if (!firstAirDate.isNullOrBlank()) extraInfo.append("📅 First Air Date: $firstAirDate\n")
            val originalLanguage = item.originalLanguage
            if (!originalLanguage.isNullOrBlank()) extraInfo.append("🗣️ Language: ${originalLanguage.replaceFirstChar { c -> c.uppercase() }}\n")
            val voteCount = item.voteCount
            if (voteCount != null && voteCount > 0) extraInfo.append("🗳️ Vote Count: ${formatNumber(voteCount)}\n")
            val viewCount = item.viewCount
            if (viewCount != null && viewCount > 0) extraInfo.append("👁️ Views: ${formatNumber(viewCount)}\n")
            val popularity = item.popularity
            if (popularity != null && popularity > 0.0) extraInfo.append("📊 Popularity: ${"%.2f".format(popularity)}\n")
            val status = item.status
            if (!status.isNullOrBlank()) extraInfo.append("📌 Status: $status\n")
            
            val finalPlot = if (extraInfo.isNotEmpty()) "${item.overview?.trim()}\n\n--- Info ---\n$extraInfo".trim() else item.overview?.trim()

            var scoreVal: Score? = null
            val voteAvg = item.voteAverage
            if (voteAvg != null) scoreVal = Score.from10(voteAvg.toString())

            if (loadData.type == "movie") {
                newMovieLoadResponse(title, url, TvType.Movie, streams.toJson()) {
                    this.posterUrl = item.posterPath ?: loadData.posterUrl
                    this.backgroundPosterUrl = item.backdropPath
                    this.plot = finalPlot
                    this.year = item.releaseDate?.substring(0, 4)?.toIntOrNull()
                    this.tags = tags
                    this.duration = item.runtime
                    this.score = scoreVal
                    this.contentRating = cert
                    this.actors = actors
                }
            } else {
                val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
                val seasonsList: List<ApiSeason> = item.seasons ?: emptyList()
                for (season in seasonsList) {
                    val seasonNum = season.seasonNumber ?: continue
                    val epsList: List<ApiEpisode> = season.episodes ?: emptyList()
                    for (ep in epsList) {
                        val epNum = ep.episodeNumber ?: continue
                        val rawStreams = ep.streamingLinks
                        val epStreams: List<ApiStreamLink> = if (!rawStreams.isNullOrEmpty()) rawStreams.filter { link -> link.isActive != false && !link.url.isNullOrBlank() } else emptyList()
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
                    this.plot = finalPlot
                    this.year = item.firstAirDate?.substring(0, 4)?.toIntOrNull()
                    this.tags = tags
                    this.score = scoreVal
                    this.contentRating = cert
                    this.actors = actors
                }
            }
        } catch (e: Exception) { 
            Log.e(TAG, "load: ${e.message}")
            null 
        }
    }

    private fun formatNumber(count: Int): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val streams = try { parseJson<List<ApiStreamLink>>(data) } catch (e: Exception) { return false }
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
            val headers = mutableMapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36", "Referer" to "$base/")
            if (stream.headers?.isNotBlank() == true) { try { headers.putAll(parseJson<Map<String, String>>(stream.headers)) } catch (_: Exception) {} }
            if (stream.userAgent?.isNotBlank() == true) headers["User-Agent"] = stream.userAgent
            callback.invoke(newExtractorLink(this.name, stream.quality ?: "Unknown", url, type) { this.quality = quality; this.headers = headers })
            found = true
        }
        return found
    }
}
