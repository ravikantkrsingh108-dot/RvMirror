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

    // API Data Classes (Prefixed to avoid collisions)
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
    @JsonIgnoreProperties(ignoreUnknown = true) data class ApiCertification(@JsonProperty("certification") val certification: String? = null, @JsonProperty("iso_3166_1") val country: String? = null)

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
        @JsonProperty("certifications") val certifications: List<ApiCertification>? = null, @JsonProperty("contentRating") val contentRating: String? = null,
        @JsonProperty("cast") val cast: List<ApiCastMember>? = null, @JsonProperty("streamingLinks") val streamingLinks: List<ApiStreamLink>? = null,
        @JsonProperty("seasons") val seasons: List<ApiSeason>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true) data class ApiMediaListResponse(@JsonProperty("data") val data: List<ApiMediaItem>? = null, @JsonProperty("totalPages") val totalPages: Int? = null, @JsonProperty("total") val total: Int? = null)
    @JsonIgnoreProperties(ignoreUnknown = true) data class LoadData(val id: String, val type: String, val title: String, val posterUrl: String? = null)

    protected fun ApiMediaItem.toLocalRecord(type: String): HDGharTVStorage.MediaRecord? {
        val recordId = id ?: return null
        val recordTitle = title ?: originalTitle ?: return null
        val cert = certifications?.firstOrNull { c -> !c.certification.isNullOrBlank() }?.certification ?: contentRating ?: ""
        return HDGharTVStorage.MediaRecord(
            id = recordId, type = type, title = recordTitle, overview = overview ?: "", posterPath = posterPath ?: "",
            backdropPath = backdropPath ?: "", releaseDate = releaseDate ?: "", firstAirDate = firstAirDate ?: "",
            genres = genres?.mapNotNull { g -> g.name } ?: emptyList(), categories = categories ?: emptyList(),
            networks = networks?.mapNotNull { n -> n.name } ?: emptyList(), studios = productionCompanies?.mapNotNull { p -> p.name } ?: emptyList(),
            collection = collection?.name ?: "", originalLanguage = originalLanguage ?: "",
            spokenLanguages = spokenLanguages?.mapNotNull { l -> l.englishName ?: l.name } ?: emptyList(),
            voteAverage = voteAverage ?: 0.0, viewCount = voteCount ?: viewCount ?: 0, popularity = popularity ?: 0.0,
            runtime = runtime ?: 0, status = status ?: "", certification = cert,
            cast = cast?.mapNotNull { c -> val name = c.name ?: return@mapNotNull null; HDGharTVStorage.CastMember(name, c.character ?: "", c.profilePath) } ?: emptyList()
        )
    }

    protected suspend fun syncAndCrawl() {
        val base = apiBase()
        var allRecords = HDGharTVStorage.getAll()
        if (allRecords.isEmpty()) {
            try {
                for (i in 1..2) {
                    val mRes = app.get("$base/api/movies/public?page=$i&limit=50", referer = "$base/").text
                    val mParsed = parseJson<ApiMediaListResponse>(mRes)
                    val mRecords = mParsed.data?.mapNotNull { item -> item.toLocalRecord("movie") } ?: emptyList()
                    if (mRecords.isNotEmpty()) HDGharTVStorage.addRichBatch(mRecords)
                    if (mParsed.data?.size ?: 0 < 50) break
                }
                for (i in 1..2) {
                    val sRes = app.get("$base/api/series/public?page=$i&limit=50", referer = "$base/").text
                    val sParsed = parseJson<ApiMediaListResponse>(sRes)
                    val sRecords = sParsed.data?.mapNotNull { item -> item.toLocalRecord("series") } ?: emptyList()
                    if (sRecords.isNotEmpty()) HDGharTVStorage.addRichBatch(sRecords)
                    if (sParsed.data?.size ?: 0 < 50) break
                }
                allRecords = HDGharTVStorage.getAll()
                delay(500) 
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
                        val mParsed = parseJson<ApiMediaListResponse>(mRes)
                        val mRecords = mParsed.data?.mapNotNull { item -> item.toLocalRecord("movie") } ?: emptyList()
                        if (mRecords.isNotEmpty()) HDGharTVStorage.addRichBatch(mRecords)
                        if (mParsed.data?.size ?: 0 < limit) moviePage = 1 else moviePage++
                    }
                    for (i in 1..2) {
                        val sRes = app.get("$base/api/series/public?page=$seriesPage&limit=$limit", referer = "$base/").text
                        val sParsed = parseJson<ApiMediaListResponse>(sRes)
                        val sRecords = sParsed.data?.mapNotNull { item -> item.toLocalRecord("series") } ?: emptyList()
                        if (sRecords.isNotEmpty()) HDGharTVStorage.addRichBatch(sRecords)
                        if (sParsed.data?.size ?: 0 < limit) seriesPage = 1 else seriesPage++
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
            val title = item.title ?: item.originalTitle ?: loadData.title
            val streams = item.streamingLinks?.filter { link -> link.isActive != false && !link.url.isNullOrBlank() } ?: emptyList()

            val tags = mutableListOf<String>()
            item.genres?.mapNotNull { g -> g.name }?.let { list -> tags.addAll(list) }
            item.spokenLanguages?.mapNotNull { l -> l.englishName ?: l.name }?.let { list -> tags.addAll(list) }
            item.categories?.let { list -> tags.addAll(list) }
            item.networks?.mapNotNull { n -> n.name }?.let { list -> tags.addAll(list) }
            item.productionCompanies?.mapNotNull { p -> p.name }?.let { list -> tags.addAll(list) }

            val actors = item.cast?.mapNotNull { c -> 
                val name = c.name ?: return@mapNotNull null
                ActorData(Actor(name), roleString = c.character) 
            } ?: emptyList()
            
            val cert = item.certifications?.firstOrNull { c -> !c.certification.isNullOrBlank() }?.certification ?: item.contentRating

            val extraInfo = buildString {
                item.collection?.name?.takeIf { colName -> colName.isNotBlank() }?.let { colName -> append("🎞️ Collection: $colName\n") }
                item.releaseDate?.takeIf { rd -> rd.isNotBlank() }?.let { rd -> append("📅 Release Date: $rd\n") }
                item.firstAirDate?.takeIf { fad -> fad.isNotBlank() }?.let { fad -> append("📅 First Air Date: $fad\n") }
                item.originalLanguage?.takeIf { ol -> ol.isNotBlank() }?.let { ol -> append("🗣️ Language: ${ol.replaceFirstChar { c -> c.uppercase() }}\n") }
                item.voteCount?.takeIf { vc -> vc > 0 }?.let { vc -> append("🗳️ Vote Count: ${formatNumber(vc)}\n") }
                item.viewCount?.takeIf { vc -> vc > 0 }?.let { vc -> append("👁️ Views: ${formatNumber(vc)}\n") }
                item.popularity?.takeIf { pop -> pop > 0.0 }?.let { pop -> append("📊 Popularity: ${"%.2f".format(pop)}\n") }
                item.status?.takeIf { st -> st.isNotBlank() }?.let { st -> append("📌 Status: $st\n") }
            }
            val finalPlot = if (extraInfo.isNotBlank()) "${item.overview?.trim()}\n\n--- Info ---\n$extraInfo".trim() else item.overview?.trim()

            if (loadData.type == "movie") {
                newMovieLoadResponse(title, url, TvType.Movie, streams.toJson()) {
                    this.posterUrl = item.posterPath ?: loadData.posterUrl
                    this.backgroundPosterUrl = item.backdropPath
                    this.plot = finalPlot
                    this.year = item.releaseDate?.substring(0, 4)?.toIntOrNull()
                    this.tags = tags
                    this.duration = item.runtime
                    this.score = item.voteAverage?.let { Score.from10(it.toString()) }
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
                        val epStreams = ep.streamingLinks?.filter { link -> link.isActive != false && !link.url.isNullOrBlank() } ?: emptyList()
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
                    this.score = item.voteAverage?.let { Score.from10(it.toString()) }
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
