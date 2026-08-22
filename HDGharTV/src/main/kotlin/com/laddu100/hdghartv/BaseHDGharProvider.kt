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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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

    companion object {
        // FIX: shared by ALL provider subclasses (Smart / Collections / Networks / Year / Cast),
        // so only ONE initial sync and ONE background crawler ever run at a time.
        private val crawlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        private val crawling = AtomicBoolean(false)
        private val initialSyncLock = Any()
        private var initialSyncJob: Job? = null

        private const val SYNC_AWAIT_TIMEOUT_MS = 15_000L
        private const val PAGE_LIMIT = 50
    }

    // ===================== API models =====================

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiStreamLink(@JsonProperty("quality") val quality: String? = null, @JsonProperty("url") val url: String? = null, @JsonProperty("type") val type: String? = null, @JsonProperty("language") val language: String? = null, @JsonProperty("isActive") val isActive: Boolean? = null, @JsonProperty("headers") val headers: String? = null, @JsonProperty("userAgent") val userAgent: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiEpisode(@JsonProperty("episodeNumber") val episodeNumber: Int? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("overview") val overview: String? = null, @JsonProperty("stillPath") val stillPath: String? = null, @JsonProperty("runtime") val runtime: Int? = null, @JsonProperty("streamingLinks") val streamingLinks: List<ApiStreamLink>? = null)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiSeason(@JsonProperty("seasonNumber") val seasonNumber: Int? = null, @JsonProperty("name") val name: String? = null, @JsonProperty("overview") val overview: String? = null, @JsonProperty("posterPath") val posterPath: String? = null, @JsonProperty("episodes") val episodes: List<ApiEpisode>? = null)
    @JsonIgnoreProperties(ignoreUnknown = true) data class ApiGenre(@JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true) data class ApiCompany(@JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true) data class ApiSpokenLanguage(@JsonProperty("englishName") val englishName: String? = null, @JsonProperty("name") val name: String? = null)
    @JsonIgnoreProperties(ignoreUnknown = true) data class ApiCastMember(@JsonProperty("name") val name: String? = null, @JsonProperty("character") val character: String? = null, @JsonProperty("profilePath") val profilePath: String? = null)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class ApiMediaItem(
        @JsonProperty("_id") val id: String? = null, @JsonProperty("title") val title: String? = null, @JsonProperty("originalTitle") val originalTitle: String? = null,
        @JsonProperty("overview") val overview: String? = null, @JsonProperty("posterPath") val posterPath: String? = null, @JsonProperty("backdropPath") val backdropPath: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null, @JsonProperty("firstAirDate") val firstAirDate: String? = null,
        @JsonProperty("genres") val genres: List<ApiGenre>? = null, @JsonProperty("categories") val categories: List<String>? = null,
        @JsonProperty("networks") val networks: List<ApiCompany>? = null, @JsonProperty("productionCompanies") val productionCompanies: List<ApiCompany>? = null,
        @JsonProperty("collection") val collection: Any? = null,
        @JsonProperty("originalLanguage") val originalLanguage: String? = null,
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

    // ===================== Mapping helpers =====================

    private fun extractCertification(certs: List<Any>?): String {
        if (certs.isNullOrEmpty()) return ""
        for (c in certs) {
            val str = when (c) { is String -> c; is Map<*, *> -> c["certification"]?.toString(); else -> null }
            if (str != null) {
                val parts = str.split(" ")
                if (parts.size == 2 && (parts[0].equals("US", true) || parts[0].equals("IN", true))) return parts[1]
            }
        }
        return certs.mapNotNull { c -> when (c) { is String -> c; is Map<*, *> -> c["certification"]?.toString(); else -> null } }.firstOrNull { it.isNotBlank() } ?: ""
    }

    private fun extractCollectionNames(c: Any?): List<String> {
        return when (c) {
            is String -> if (c.isNotBlank()) listOf(c) else emptyList()
            is Map<*, *> -> { val name = c["name"]?.toString(); if (!name.isNullOrBlank()) listOf(name) else emptyList() }
            is List<*> -> c.mapNotNull { when (it) { is String -> it; is Map<*, *> -> it["name"]?.toString(); else -> null } }.filter { it.isNotBlank() }
            else -> emptyList()
        }
    }

    protected fun ApiMediaItem.toLocalRecord(type: String): HDGharTVStorage.MediaRecord? {
        val recordId = id ?: return null
        val recordTitle = title ?: originalTitle ?: return null
        val cert = extractCertification(certifications).ifBlank { contentRating ?: "" }
        val colNames = extractCollectionNames(collection)
        return HDGharTVStorage.MediaRecord(
            id = recordId, type = type, title = recordTitle, overview = overview ?: "", posterPath = posterPath ?: "",
            backdropPath = backdropPath ?: "", releaseDate = releaseDate ?: "", firstAirDate = firstAirDate ?: "",
            genres = if (!genres.isNullOrEmpty()) genres.mapNotNull { g -> g.name } else emptyList(),
            categories = categories ?: emptyList(),
            networks = if (!networks.isNullOrEmpty()) networks.mapNotNull { n -> n.name } else emptyList(),
            studios = if (!productionCompanies.isNullOrEmpty()) productionCompanies.mapNotNull { p -> p.name } else emptyList(),
            collection = colNames, originalLanguage = originalLanguage ?: "",
            spokenLanguages = if (!spokenLanguages.isNullOrEmpty()) spokenLanguages.mapNotNull { l -> l.englishName ?: l.name } else emptyList(),
            // FIX: votes and views are stored separately now (viewCount used to fall back to voteCount,
            // which made the "Most Viewed" section actually show "Most Voted").
            voteAverage = voteAverage ?: 0.0,
            voteCount = voteCount ?: 0,
            viewCount = viewCount ?: 0,
            popularity = popularity ?: 0.0,
            runtime = runtime ?: 0, status = status ?: "", certification = cert,
            cast = if (!cast.isNullOrEmpty()) cast.mapNotNull { c -> val name = c.name ?: return@mapNotNull null; HDGharTVStorage.CastMember(name, c.character ?: "", c.profilePath) } else emptyList()
        )
    }

    // ===================== Catalog sync & background crawler =====================

    /**
     * Makes sure the catalog is seeded and rotates the background crawler forward.
     *
     * @param awaitInitialSync when true (default), waits up to [SYNC_AWAIT_TIMEOUT_MS] for the
     *        first-ever sync to finish so data-dependent callers (main pages) get results.
     *        Pass false to fire-and-forget (used by the Smart home page so it stays responsive).
     */
    protected suspend fun syncAndCrawl(awaitInitialSync: Boolean = true) {
        val base = apiBase()
        val pending = startInitialSyncIfNeeded(base)
        when {
            pending == null -> maybeStartCrawler(base) // already seeded → advance the crawl
            awaitInitialSync -> withTimeoutOrNull(SYNC_AWAIT_TIMEOUT_MS) { pending.join() }
            // else: initial sync runs in the background; the crawler is chained inside it
        }
    }

    /**
     * Lighter variant for search(): guarantees the catalog is seeded (waiting once, bounded)
     * but never triggers an extra crawl step.
     */
    protected suspend fun ensureCatalogReady() {
        val base = apiBase()
        val job = startInitialSyncIfNeeded(base)
        if (job != null) withTimeoutOrNull(SYNC_AWAIT_TIMEOUT_MS) { job.join() }
    }

    /** Starts the one-time seed sync if needed. Returns the job to await, or null if already seeded. */
    private fun startInitialSyncIfNeeded(base: String): Job? {
        synchronized(initialSyncLock) {
            if (HDGharTVStorage.isInitialSyncDone()) return null
            val existing = initialSyncJob
            if (existing != null && existing.isActive) return existing
            val job = crawlerScope.launch {
                try {
                    performInitialSync(base)
                } catch (e: Exception) {
                    Log.e(TAG, "Initial sync crashed: ${e.message}")
                }
                // Once seeded (or failed), start the rotating crawler from where we left off.
                maybeStartCrawler(base)
            }
            initialSyncJob = job
            job.invokeOnCompletion {
                // Clear so a *failed* sync is retried on the next visit.
                synchronized(initialSyncLock) { if (initialSyncJob === job) initialSyncJob = null }
            }
            return job
        }
    }

    private fun maybeStartCrawler(base: String) {
        // FIX: compareAndSet removes the old check-then-act race, and being in the companion
        // means all 5 providers share one crawler instead of running 5 in parallel.
        if (!crawling.compareAndSet(false, true)) return
        crawlerScope.launch {
            try {
                crawlStep(base)
            } catch (e: Exception) {
                Log.e(TAG, "Crawler error: ${e.message}")
            } finally {
                crawling.set(false)
            }
        }
    }

    /** One-time seed: fetch the first 2 pages of movies & series so the catalog is usable immediately. */
    private suspend fun performInitialSync(base: String) {
        val moviesOk = syncFirstPages(base, "movies", "movie")
        val seriesOk = syncFirstPages(base, "series", "series")
        if (moviesOk && seriesOk) {
            // FIX: an explicit "initial sync done" flag replaces the old
            // "page == 1 && records not empty" heuristic that permanently skipped pages 1–2.
            val (moviePage, seriesPage) = HDGharTVStorage.getCrawlerState()
            HDGharTVStorage.saveCrawlerState(maxOf(moviePage, 3), maxOf(seriesPage, 3))
            HDGharTVStorage.markInitialSyncDone()
        } else {
            Log.w(TAG, "Initial sync incomplete (movies=$moviesOk, series=$seriesOk); will retry on next visit")
        }
    }

    private suspend fun syncFirstPages(base: String, endpoint: String, type: String): Boolean {
        for (page in 1..2) {
            val parsed = fetchMediaPage(base, endpoint, page) ?: return false
            val records = parsed.data?.mapNotNull { it.toLocalRecord(type) } ?: emptyList()
            if (records.isNotEmpty()) HDGharTVStorage.addRichBatch(records)
            if ((parsed.data?.size ?: 0) < PAGE_LIMIT) break // small catalog — no more pages
        }
        return true
    }

    /** Rotating background crawl: 2 movie pages + 2 series pages per visit, wrapping around at the end. */
    private suspend fun crawlStep(base: String) {
        var (moviePage, seriesPage) = HDGharTVStorage.getCrawlerState()
        var moviesWrapped = false
        var seriesWrapped = false

        repeat(2) {
            if (moviesWrapped) return@repeat
            val parsed = fetchMediaPage(base, "movies", moviePage)
            if (parsed != null) {
                val records = parsed.data?.mapNotNull { it.toLocalRecord("movie") } ?: emptyList()
                if (records.isNotEmpty()) HDGharTVStorage.addRichBatch(records)
                if ((parsed.data?.size ?: 0) < PAGE_LIMIT) {
                    // FIX: wrapped past the last page — stop this step (don't re-fetch page 1
                    // in the same pass) and continue from the top on the next visit,
                    // so pages 1–2 stay fresh.
                    moviesWrapped = true
                    moviePage = 1
                } else {
                    moviePage++
                }
            }
            // parsed == null (network error): keep the page counter and retry next visit
        }
        repeat(2) {
            if (seriesWrapped) return@repeat
            val parsed = fetchMediaPage(base, "series", seriesPage)
            if (parsed != null) {
                val records = parsed.data?.mapNotNull { it.toLocalRecord("series") } ?: emptyList()
                if (records.isNotEmpty()) HDGharTVStorage.addRichBatch(records)
                if ((parsed.data?.size ?: 0) < PAGE_LIMIT) {
                    seriesWrapped = true
                    seriesPage = 1
                } else {
                    seriesPage++
                }
            }
        }
        HDGharTVStorage.saveCrawlerState(moviePage, seriesPage)
    }

    private suspend fun fetchMediaPage(base: String, endpoint: String, page: Int): ApiMediaListResponse? {
        return try {
            val res = app.get("$base/api/$endpoint/public?page=$page&limit=$PAGE_LIMIT", referer = "$base/")
            tryParseJson<ApiMediaListResponse>(res.text)
        } catch (e: Exception) {
            Log.e(TAG, "Fetch failed: /api/$endpoint/public?page=$page — ${e.message}")
            null
        }
    }

    protected fun HDGharTVStorage.MediaRecord.toSearchResponse(): SearchResponse {
        val loadData = LoadData(id = this.id, type = this.type, title = this.title, posterUrl = this.posterPath)
        return newMovieSearchResponse(this.title, loadData.toJson(), if (this.type == "series") TvType.TvSeries else TvType.Movie) { this.posterUrl = loadData.posterUrl }
    }

    // ===================== Detail loading =====================

    override suspend fun load(url: String): LoadResponse? {
        val loadData = try { parseJson<LoadData>(url) } catch (e: Exception) { return null }
        val base = apiBase()
        return try {
            val endpoint = if (loadData.type == "movie") "movies" else "series"
            val res = app.get("$base/api/$endpoint/public/${loadData.id}", referer = "$base/")
            val item = tryParseJson<ApiMediaItem>(res.text) ?: return null
            val localRecord = item.toLocalRecord(loadData.type)
            if (localRecord != null) HDGharTVStorage.addRich(localRecord)

            val title = item.title ?: item.originalTitle ?: loadData.title
            val streams = if (!item.streamingLinks.isNullOrEmpty()) item.streamingLinks.filter { link -> link.isActive != false && !link.url.isNullOrBlank() } else emptyList()
            val tags = mutableListOf<String>()
            if (!item.genres.isNullOrEmpty()) item.genres.forEach { g -> if (!g.name.isNullOrBlank()) tags.add(g.name) }
            if (!item.spokenLanguages.isNullOrEmpty()) item.spokenLanguages.forEach { l -> val name = l.englishName ?: l.name; if (!name.isNullOrBlank()) tags.add(name) }
            if (!item.categories.isNullOrEmpty()) item.categories.forEach { c -> tags.add(c) }
            if (!item.networks.isNullOrEmpty()) item.networks.forEach { n -> if (!n.name.isNullOrBlank()) tags.add(n.name) }
            if (!item.productionCompanies.isNullOrEmpty()) item.productionCompanies.forEach { p -> if (!p.name.isNullOrBlank()) tags.add(p.name) }
            val actors = mutableListOf<ActorData>()
            if (!item.cast.isNullOrEmpty()) item.cast.forEach { c -> val name = c.name; if (!name.isNullOrBlank()) actors.add(ActorData(Actor(name), roleString = c.character)) }
            val cert = extractCertification(item.certifications).ifBlank { item.contentRating }
            val collectionNames = extractCollectionNames(item.collection)
            val extraInfo = StringBuilder()
            if (collectionNames.isNotEmpty()) extraInfo.append("🎞️ Collection: ${collectionNames.joinToString(", ")}\n")
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
            if (popularity != null && popularity > 0.0) extraInfo.append("📊 Popularity: ${String.format(Locale.US, "%.2f", popularity)}\n")
            val status = item.status
            if (!status.isNullOrBlank()) extraInfo.append("📌 Status: $status\n")
            val finalPlot = if (extraInfo.isNotEmpty()) "${item.overview?.trim()}\n\n--- Info ---\n$extraInfo".trim() else item.overview?.trim()
            // FIX: no score for missing/zero ratings, short formatted string, locale-stable.
            val voteAvg = item.voteAverage
            val scoreVal = if (voteAvg != null && voteAvg > 0.0) Score.from10(String.format(Locale.US, "%.1f", voteAvg)) else null

            if (loadData.type == "movie") {
                newMovieLoadResponse(title, url, TvType.Movie, streams.toJson()) {
                    this.posterUrl = item.posterPath ?: loadData.posterUrl
                    this.backgroundPosterUrl = item.backdropPath
                    this.plot = finalPlot
                    // FIX: take(4) instead of substring(0, 4) — an empty/short date string
                    // used to throw and kill the whole detail page.
                    this.year = item.releaseDate?.take(4)?.toIntOrNull()
                    this.tags = tags
                    this.duration = item.runtime
                    this.score = scoreVal
                    this.contentRating = cert
                    this.actors = actors
                }
            } else {
                val episodes = mutableListOf<Episode>()
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
                    this.year = item.firstAirDate?.take(4)?.toIntOrNull() // FIX: same as above
                    this.tags = tags
                    this.score = scoreVal
                    this.contentRating = cert
                    this.actors = actors
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "load: ${e.message}")
            // FIX: if the API is unreachable, still open the detail page from the cached
            // catalog (metadata only, no playable links).
            loadFromCache(loadData, url)
        }
    }

    /** Cached fallback for movies — a series with zero episodes would render an empty page, so it stays null. */
    private fun loadFromCache(loadData: LoadData, url: String): LoadResponse? {
        if (loadData.type != "movie") return null
        val cached = HDGharTVStorage.getById(loadData.id) ?: return null
        return newMovieLoadResponse(cached.title, url, TvType.Movie, "[]") {
            this.posterUrl = cached.posterPath.ifBlank { loadData.posterUrl }
            this.backgroundPosterUrl = cached.backdropPath.ifBlank { null }
            this.plot = cached.overview.ifBlank { null }
            this.year = cached.releaseDate.take(4).toIntOrNull()
            this.tags = cached.genres + cached.spokenLanguages + cached.categories + cached.networks + cached.studios
            this.duration = cached.runtime.takeIf { it > 0 }
            this.score = if (cached.voteAverage > 0.0) Score.from10(String.format(Locale.US, "%.1f", cached.voteAverage)) else null
            this.contentRating = cached.certification.ifBlank { null }
            this.actors = cached.cast.map { ActorData(Actor(it.name), roleString = it.character) }
        }
    }

    private fun formatNumber(count: Int): String {
        return when {
            count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }

    // ===================== Link loading =====================

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
