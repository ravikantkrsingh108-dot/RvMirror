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
    data class StreamLink(@JsonProperty("quality") val quality: String? = null, @JsonProperty("url") val url: String? = null, @JsonProperty("type") val type: String? = null, @JsonProperty("language") val language: String? = null, @JsonProperty("isActive") val isActive: Boolean? = null, @JsonProperty("headers") val headers: String? = null, @JsonProperty("userAgent") val userAgent: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Episode(@JsonProperty("episodeNumber") val episodeNumber: Int? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("overview") val overview: String? = null, @JsonProperty("stillPath") val stillPath: String? = null, @JsonProperty("runtime") val runtime: Int? = null, @JsonProperty("streamingLinks") val streamingLinks: List<StreamLink>? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Season(@JsonProperty("seasonNumber") val seasonNumber: Int? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("overview") val overview: String? = null, @JsonProperty("posterPath") val posterPath: String? = null, @JsonProperty("episodes") val episodes: List<Episode>? = null)
    @JsonIgnoreProperties(ignoreUnknown = true) data class Genre(@JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true) data class Company(@JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true) data class SpokenLanguage(@JsonProperty("englishName") val englishName: String? = null, @JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true) data class Collection(@JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true) data class CastMember(@JsonProperty("name") val name: String? = null, @JsonProperty("character") val character: String? = null, @JsonProperty("profilePath") val profilePath: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class MediaItem(
        @JsonProperty("_id") val id: String? = null, @JsonProperty("title") val title: String? = null, @JsonProperty("originalTitle") val originalTitle: String? = null,
        @JsonProperty("overview") val overview: String? = null, @JsonProperty("posterPath") val posterPath: String? = null, @JsonProperty("backdropPath") val backdropPath: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null, @JsonProperty("firstAirDate") val firstAirDate: String? = null,
        @JsonProperty("genres") val genres: List<Genre>? = null, @JsonProperty("categories") val categories: List<String>? = null,
        @JsonProperty("networks") val networks: List<Company>? = null, @JsonProperty("productionCompanies") val productionCompanies: List<Company>? = null,
        @JsonProperty("belongs_to_collection") val collection: Collection? = null, @JsonProperty("originalLanguage") val originalLanguage: String? = null,
        @JsonProperty("spokenLanguages") val spokenLanguages: List<SpokenLanguage>? = null, @JsonProperty("voteAverage") val voteAverage: Double? = null,
        @JsonProperty("viewCount") val viewCount: Int? = null, @JsonProperty("popularity") val popularity: Double? = null,
        @JsonProperty("runtime") val runtime: Int? = null, @JsonProperty("status") val status: String? = null,
        @JsonProperty("certification") val certification: String? = null, @JsonProperty("contentRating") val contentRating: String? = null,
        @JsonProperty("cast") val cast: List<CastMember>? = null, @JsonProperty("streamingLinks") val streamingLinks: List<StreamLink>? = null,
        @JsonProperty("seasons") val seasons: List<Season>? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true) data class MediaListResponse(@JsonProperty("data") val data: List<MediaItem>? = null, @JsonProperty("totalPages") val totalPages: Int? = null, @JsonProperty("total") val total: Int? = null)
    @JsonIgnoreProperties(ignoreUnknown = true) data class LoadData(val id: String, val type: String, val title: String, val posterUrl: String? = null)

    protected fun MediaItem.toLocalRecord(type: String): HDGharTVStorage.MediaRecord? {
        val id = id ?: return null
        val title = title ?: originalTitle ?: return null
        return HDGharTVStorage.MediaRecord(
            id = id, type = type, title = title, overview = overview ?: "", posterPath = posterPath ?: "",
            backdropPath = backdropPath ?: "", releaseDate = releaseDate ?: "", firstAirDate = firstAirDate ?: "",
            genres = genres?.mapNotNull { it.name } ?: emptyList(), categories = categories ?: emptyList(),
            networks = networks?.mapNotNull { it.name } ?: emptyList(), studios = productionCompanies?.mapNotNull { it.name } ?: emptyList(),
            collection = collection?.name ?: "", originalLanguage = originalLanguage ?: "",
            spokenLanguages = spokenLanguages?.mapNotNull { it.englishName ?: it.name } ?: emptyList(),
            voteAverage = voteAverage ?: 0.0, viewCount = viewCount ?: 0, popularity = popularity ?: 0.0,
            runtime = runtime ?: 0, status = status ?: "", certification = certification ?: contentRating ?: "",
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
            val item = parseJson<MediaItem>(res.text) ?: return null
            val title = item.title ?: item.originalTitle ?: loadData.title
            val streams = item.streamingLinks?.filter { it.isActive != false && !it.url.isNullOrBlank() } ?: emptyList()

            val tags = mutableListOf<String>()
            item.genres?.mapNotNull { it.name }?.let { tags.addAll(it) }
            item.spokenLanguages?.mapNotNull { it.englishName ?: it.name }?.let { tags.addAll(it) }
            item.categories?.let { tags.addAll(it) }
            item.networks?.mapNotNull { it.name }?.let { tags.addAll(it) }
            item.productionCompanies?.mapNotNull { it.name }?.let { tags.addAll(it) }

            val actors = item.cast?.mapNotNull { c -> val name = c.name ?: return@mapNotNull null; ActorData(Actor(name), roleString = c.character) } ?: emptyList()
            val extraInfo = buildString {
                item.collection?.name?.takeIf { it.isNotBlank() }?.let { append("🎞️ Collection: $it\n") }
                item.originalLanguage?.takeIf { it.isNotBlank() }?.let { append("🗣️ Language: ${it.replaceFirstChar { c -> c.uppercase() }}\n") }
                item.viewCount?.takeIf { it > 0 }?.let { append("👁️ Views: ${formatNumber(it)}\n") }
                item.status?.takeIf { it.isNotBlank() }?.let { append("📌 Status: $it\n") }
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
                    this.contentRating = item.certification ?: item.contentRating
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
                            this.season = seasonNum; this.episode = epNum
                            this.posterUrl = ep.stillPath; this.description = ep.overview; this.runTime = ep.runtime
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
                    this.contentRating = item.certification ?: item.contentRating
                    this.actors = actors
                }
            }
        } catch (e: Exception) { Log.e(TAG, "load: ${e.message}"); null }
    }

    private fun formatNumber(count: Int): String {
        return when {
            count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
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
            val headers = mutableMapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36", "Referer" to "$base/")
            if (stream.headers?.isNotBlank() == true) { try { headers.putAll(parseJson<Map<String, String>>(stream.headers)) } catch (_: Exception) {} }
            if (stream.userAgent?.isNotBlank() == true) headers["User-Agent"] = stream.userAgent
            callback.invoke(newExtractorLink(this.name, stream.quality ?: "Unknown", url, type) { this.quality = quality; this.headers = headers })
            found = true
        }
        return found
    }
}
