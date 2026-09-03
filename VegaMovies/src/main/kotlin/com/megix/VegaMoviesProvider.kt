package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.api.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

const val RANDOM_MOVIES = "random:movies"
const val RANDOM_SERIES = "random:series"

open class VegaMoviesProvider : MainAPI() {
    override var mainUrl = "https://vegamovies.vodka"
    override var name = "VegaMovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    val cinemeta_url = "https://v3-cinemeta.strem.io/meta"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    init {
        runBlocking {
            basemainUrl?.let {
                mainUrl = it
            }
        }
    }

    companion object {
        val basemainUrl: String? by lazy {
            runBlocking {
                try {
                    val response = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json")
                    val json = response.text
                    val jsonObject = JSONObject(json)
                    jsonObject.optString("vegamovies")
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    private val pageCache = ConcurrentHashMap<String, Int>()
    private val countCache = ConcurrentHashMap<String, Int>()

    private val infoKeys = listOf(
        "quality", "language", "audio", "subtitle", "size", "format",
        "resolution", "runtime", "duration", "release date",
        "original language", "running time", "season", "episodes"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Home",
        "$mainUrl/category/web-series/netflix/page/%d/" to "Netflix",
        "$mainUrl/category/web-series/amazon-prime-video/page/%d/" to "Amazon Prime",
        "$mainUrl/category/web-series/disney-plus-hotstar/page/%d/" to "Disney+ Hotstar",
        "$mainUrl/category/web-series/zee5/page/%d/" to "ZEE5",
        "$mainUrl/category/web-series/sonyliv/page/%d/" to "SonyLIV",
        "$mainUrl/category/web-series/jio-cinema/page/%d/" to "JioCinema",
        "$mainUrl/category/web-series/mx-original/page/%d/" to "MX Original",
        "$mainUrl/category/web-series/altbalaji/page/%d/" to "ALT Balaji",
        "$mainUrl/category/web-series/hoichoi/page/%d/" to "Hoichoi",
        "$mainUrl/category/anime-series/page/%d/" to "Anime Series",
        "$mainUrl/category/korean-series/page/%d/" to "Korean Series",
        "$mainUrl/category/chinese-series/page/%d/" to "Chinese Series",
        "$mainUrl/category/hindi-movies/page/%d/" to "Hindi Movies",
        "$mainUrl/category/bollywood/page/%d/" to "Bollywood",
        "$mainUrl/category/south-hindi-dubbed/page/%d/" to "South Hindi Dubbed",
        "$mainUrl/category/hollywood/page/%d/" to "Hollywood",
        "$mainUrl/category/dual-audio/page/%d/" to "Dual Audio",
        RANDOM_MOVIES to "🔀 Movies Shuffle",
        RANDOM_SERIES to "🔀 Series Shuffle"
    )

    private fun movieCategories() = listOf(
        "$mainUrl/category/hindi-movies",
        "$mainUrl/category/bollywood",
        "$mainUrl/category/south-hindi-dubbed",
        "$mainUrl/category/hollywood",
        "$mainUrl/category/dual-audio",
        "$mainUrl/category/punjabi-movies"
    )

    private fun seriesCategories() = listOf(
        "$mainUrl/category/web-series/netflix",
        "$mainUrl/category/web-series/amazon-prime-video",
        "$mainUrl/category/web-series/disney-plus-hotstar",
        "$mainUrl/category/web-series/zee5",
        "$mainUrl/category/web-series/sonyliv",
        "$mainUrl/category/web-series/jio-cinema",
        "$mainUrl/category/anime-series",
        "$mainUrl/category/korean-series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (request.data.startsWith("random:")) {
            val home = getRandomContent(request.data.substringAfter("random:"))
            return newHomePageResponse(request.name, home)
        }

        val document = app.get(request.data.format(page)).document
        val home = document.select("div.movies-grid > a").mapNotNull { it.toSearchResult() }

        val total = getTotalCount(request.data, document, home.size)
        val label = if (total != null && total > 0) "${request.name} (${formatCount(total)})" else request.name

        return newHomePageResponse(label, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select("img").attr("alt").replace("Download ", "")
        val href = this.attr("href")
        var posterUrl = this.select("img").attr("src")
        if (!posterUrl.contains("https:")) posterUrl = this.select("img").attr("data-src")

        return newMovieSearchResponse(title, URI(href).path, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val json = app.get("$mainUrl/search.php?q=$query&page=$page").text
        val response = tryParseJson<VegaSearchResponse>(json) ?: return null
        val results = response.hits.map { hit ->
            val doc = hit.document
            newMovieSearchResponse(doc.post_title.replace("Download ", ""), doc.permalink, TvType.Movie) {
                this.posterUrl = doc.post_thumbnail
            }
        }
        return newSearchResponseList(results)
    }

    // ---------- counts / shuffle ----------
    private fun getLastPage(document: Document): Int {
        val fromText = document.select("a.page-numbers, span.page-numbers, .pagination a, .nav-links a")
            .mapNotNull { it.text().filter(Char::isDigit).toIntOrNull() }
        val fromHref = document.select("a[href*=\"/page/\"]")
            .mapNotNull { Regex("/page/(\\d+)").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
        return (fromText + fromHref).maxOrNull() ?: 1
    }

    private suspend fun getTotalCount(catalogUrl: String, document: Document, itemsOnPage: Int): Int? {
        val cacheKey = catalogUrl.substringBefore("/page/").trimEnd('/')
        countCache[cacheKey]?.let { return it }

        try {
            if (catalogUrl.contains("/category/")) {
                val slug = cacheKey.substringAfterLast('/')
                val res = app.get("$mainUrl/wp-json/wp/v2/categories?slug=$slug&_fields=count", timeout = 5000L)
                val count = JSONArray(res.text).optJSONObject(0)?.optInt("count") ?: 0
                if (count > 0) {
                    countCache[cacheKey] = count
                    return count
                }
            } else {
                val res = app.get("$mainUrl/wp-json/wp/v2/posts?per_page=1&_fields=id", timeout = 5000L)
                val count = res.headers["X-WP-Total"]?.toIntOrNull() ?: 0
                if (count > 0) {
                    countCache[cacheKey] = count
                    return count
                }
            }
        } catch (e: Exception) {
            // REST API disabled/blocked -> estimate from pagination below
        }

        val lastPage = getLastPage(document)
        pageCache[cacheKey] = lastPage
        return if (itemsOnPage > 0 && lastPage > 0) itemsOnPage * lastPage else null
    }

    private fun formatCount(count: Int): String = when {
        count >= 1_000_000 -> String.format(Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1000 -> String.format(Locale.US, "%.1fK", count / 1000.0)
        else -> count.toString()
    }

    private suspend fun getRandomContent(type: String): List<SearchResponse> {
        val categories = if (type.equals("movies", true)) movieCategories() else seriesCategories()

        repeat(3) {
            try {
                val category = categories.random()

                var page1Doc: Document? = null
                val lastPage = pageCache[category] ?: run {
                    val doc = app.get("$category/page/1/").document
                    getLastPage(doc).also {
                        pageCache[category] = it
                        page1Doc = doc
                    }
                }

                val randomPage = Random.nextInt(1, lastPage.coerceAtLeast(1) + 1)
                val doc = if (randomPage == 1 && page1Doc != null) page1Doc!!
                          else app.get("$category/page/$randomPage/").document

                val items = doc.select("div.movies-grid > a").mapNotNull { it.toSearchResult() }.shuffled()
                if (items.isNotEmpty()) return items
            } catch (e: Exception) {
                Log.d("VegaMovies", "shuffle failed: ${e.message}")
            }
        }
        return emptyList()
    }

    // ---------- content page helpers ----------
    private fun parseDuration(runtime: String?): Int? {
        if (runtime.isNullOrBlank()) return null
        var minutes = 0
        Regex("(\\d+)\\s*h").find(runtime)?.groupValues?.get(1)?.toIntOrNull()?.let { minutes += it * 60 }
        Regex("(\\d+)\\s*m").find(runtime)?.groupValues?.get(1)?.toIntOrNull()?.let { minutes += it }
        if (minutes > 0) return minutes
        return runtime.filter(Char::isDigit).toIntOrNull()?.takeIf { it in 1..1000 }
    }

    private fun normalizeTrailer(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return when {
            raw.contains("youtu.be/") -> "https://www.youtube.com/watch?v=${raw.substringAfterLast("/").substringBefore("?")}"
            raw.contains("youtube.com/embed/") -> "https://www.youtube.com/watch?v=${raw.substringAfter("embed/").substringBefore("?").substringBefore("&")}"
            raw.contains("youtube.com/watch") -> raw
            raw.startsWith("http") -> raw
            else -> "https://www.youtube.com/watch?v=$raw"
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(fixUrl(url)).document
        var title = document.select("title").text().replace("Download ", "").substringBefore("|").trim()
        var posterUrl = document.select("p > img").attr("src")
        val imdbUrl = document.select("a[href*=\"imdb\"]").attr("href")
        val imdbId = imdbUrl.substringAfter("title/").substringBefore("/")

        val tvtype = if (
            document.selectFirst("h3:matches((?i)Series-SYNOPSIS/PLOT)") != null ||
            document.selectFirst("h3:matches((?i)Series Info)") != null ||
            document.selectFirst("h3:matches((?i)Series synopsis/PLOT)") != null
        ) {
            "series"
        } else {
            "movie"
        }

        var description = document
            .selectFirst("h3:has(span:matches((?i)SYNOPSIS/PLOT))")
            ?.nextElementSibling()
            ?.text()

        val siteInfo = document.select("main p, article p, div.thecontent p, div.entry-content p")
            .map { it.text().trim() }
            .filter { it.contains(":") && infoKeys.any { key -> it.lowercase().contains("$key:") } }
            .distinct()
            .take(8)
            .joinToString("\n")

        val jsonResponse = app.get("$cinemeta_url/$tvtype/$imdbId.json").text
        val responseData = tryParseJson<ResponseData>(jsonResponse)

        var cast: List<String> = emptyList()
        var genre: List<String> = emptyList()
        var imdbRating: String = ""
        var year: String = ""
        var background: String = posterUrl

        if (responseData != null) {
            description = responseData.meta.description ?: description
            cast = responseData.meta.cast ?: emptyList()
            title = responseData.meta.name ?: title
            genre = responseData.meta.genre ?: emptyList()
            imdbRating = responseData.meta.imdbRating ?: ""
            year = responseData.meta.year
                ?: responseData.meta.releaseInfo?.substringBefore("–")?.substringBefore("-")?.trim()
                ?: ""
            posterUrl = responseData.meta.poster ?: posterUrl
            background = responseData.meta.background ?: background
        }

        if (siteInfo.isNotBlank()) {
            description = listOfNotNull(
                description?.takeIf { it.isNotBlank() },
                siteInfo
            ).joinToString("\n\n")
        }

        val extraTags = mutableListOf<String>()
        responseData?.meta?.language?.split(",")?.map { it.trim() }
            ?.filter { it.isNotBlank() }?.take(2)?.let { extraTags.addAll(it) }
        responseData?.meta?.country?.takeIf { it.isNotBlank() }?.let { extraTags.add(it) }

        val duration = parseDuration(responseData?.meta?.runtime)

        val showStatus = when (responseData?.meta?.status?.lowercase()?.trim()) {
            "returning series", "running", "continuing" -> ShowStatus.Ongoing
            "ended", "completed", "canceled", "cancelled" -> ShowStatus.Completed
            else -> null
        }

        val trailerUrl = normalizeTrailer(
            responseData?.meta?.trailers?.firstOrNull { !it.source.isNullOrBlank() }?.source
                ?: responseData?.meta?.youtubeTrailer
                ?: document.selectFirst("iframe[src*=\"youtube.com\"], iframe[src*=\"youtu.be\"]")?.attr("src")
        )

        if (tvtype == "series") {
            val hTags = document.select("main > h3:matches((?i)(4K|[0-9]*0p)),main > h5:matches((?i)(4K|[0-9]*0p))")
                .filter { element -> !element.text().contains("Zip", true) }

            val tvSeriesEpisodes = mutableListOf<Episode>()
            val episodesMap: MutableMap<Pair<Int, Int>, List<String>> = mutableMapOf()

            for (tag in hTags) {
                val realSeasonRegex = Regex("""(?:Season |S)(\d+)""")
                val realSeason = realSeasonRegex.find(tag.toString())?.groupValues?.get(1)?.toIntOrNull() ?: 0

                val pTag = tag.nextElementSibling()
                val aTags: List<Element>? = if (pTag != null && pTag.tagName() == "p") {
                    pTag.select("a")
                } else {
                    tag.select("a")
                }

                var unilink = aTags?.find {
                    it.text().contains("V-Cloud", ignoreCase = true) ||
                    it.text().contains("Episode", ignoreCase = true) ||
                    it.text().contains("Download", ignoreCase = true)
                }

                if (unilink == null) {
                    unilink = aTags?.find {
                        it.text().contains("G-Direct", ignoreCase = true)
                    }
                }

                val Eurl = unilink?.attr("href")
                Eurl?.let { eurl ->
                    val document2 = app.get(eurl).document
                    val vcloudLinks = document2.select("p > a").mapNotNull {
                        if (it.attr("href").contains("vcloud", true)) {
                            it.attr("href")
                        } else {
                            null
                        }
                    }

                    vcloudLinks.mapNotNull { vcloudlink ->
                        val key = Pair(realSeason, vcloudLinks.indexOf(vcloudlink) + 1)
                        if (episodesMap.containsKey(key)) {
                            val currentList = episodesMap[key] ?: emptyList()
                            val newList = currentList.toMutableList()
                            newList.add(vcloudlink)
                            episodesMap[key] = newList
                        } else {
                            episodesMap[key] = mutableListOf(vcloudlink)
                        }
                    }
                }
            }

            for ((key, value) in episodesMap) {
                val episodeInfo = responseData?.meta?.videos?.find { it.season == key.first && it.episode == key.second }
                val data = value.map { source ->
                    EpisodeLink(source)
                }
                tvSeriesEpisodes.add(
                    newEpisode(data) {
                        this.name = episodeInfo?.name ?: episodeInfo?.title
                            ?: "Season ${key.first} - Episode ${key.second}"
                        this.season = key.first
                        this.episode = key.second
                        this.posterUrl = episodeInfo?.thumbnail
                        this.description = episodeInfo?.overview
                    }
                )
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, tvSeriesEpisodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = (genre + extraTags).distinct()
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull() ?: year.substringBefore("–").toIntOrNull()
                this.duration = duration
                this.showStatus = showStatus
                this.backgroundPosterUrl = background
                trailerUrl?.let { addTrailer(it) }
                addActors(cast)
                addImdbUrl(imdbUrl)
            }
        } else {
            val buttons = document.select("a:has(button.dwd-button)")
            val data = buttons.mapNotNull { button ->
                val link = fixUrl(button.attr("href"))
                val doc = app.get(link).document
                val source = doc.select("a:contains(V-Cloud)").attr("href")
                EpisodeLink(source)
            }
            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = (genre + extraTags).distinct()
                this.score = Score.from10(imdbRating)
                this.year = year.toIntOrNull()
                this.duration = duration
                this.backgroundPosterUrl = background
                trailerUrl?.let { addTrailer(it) }
                addActors(cast)
                addImdbUrl(imdbUrl)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = parseJson<ArrayList<EpisodeLink>>(data)
        sources.amap {
            val source = it.source
            if (source.contains("vcloud")) VCloud().getUrl(source, "", subtitleCallback, callback)
            else loadExtractor(source, "", subtitleCallback, callback)
        }
        return true
    }

    data class Meta(
        val id: String?,
        val imdb_id: String?,
        val type: String?,
        val poster: String?,
        val background: String?,
        val moviedb_id: Int?,
        val name: String?,
        val description: String?,
        val genre: List<String>?,
        val genres: List<String>?,
        val releaseInfo: String?,
        val status: String?,
        val runtime: String?,
        val cast: List<String>?,
        val language: String?,
        val country: String?,
        val imdbRating: String?,
        val year: String?,
        val trailers: List<CinemetaTrailer>?,
        val youtubeTrailer: String?,
        val videos: List<EpisodeDetails>?,
    )

    data class CinemetaTrailer(
        val source: String?,
        val type: String?
    )

    data class EpisodeDetails(
        val id: String?,
        val name: String?,
        val title: String?,
        val season: Int,
        val episode: Int,
        val released: String?,
        val firstAired: String?,
        val overview: String?,
        val thumbnail: String?,
        val moviedb_id: Int?,
        val imdb_id: String?,
        val imdbSeason: Int?,
        val imdbEpisode: Int?,
    )

    data class ResponseData(
        val meta: Meta,
    )

    data class EpisodeLink(
        val source: String
    )

    data class VegaSearchResponse(
        val hits: List<VegaHit>
    )

    data class VegaHit(
        val document: VegaDocument
    )

    data class VegaDocument(
        val id: String,
        val imdb_id: String?,
        val post_title: String,
        val permalink: String,
        val post_thumbnail: String
    )
}
