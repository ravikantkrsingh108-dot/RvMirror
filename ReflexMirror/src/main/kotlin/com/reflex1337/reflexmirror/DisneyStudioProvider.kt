package com.reflex1337.reflexmirror

import android.content.Context
import com.reflex1337.reflexmirror.entities.EpisodesData
import com.reflex1337.reflexmirror.entities.PostData
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.APIHolder.unixTime

open class DisneyStudioProvider(
    private val studio: String,
    displayName: String
) : MainAPI() {
    companion object {
        var context: Context? = null
    }
    
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )
    override var lang = "en"

    override var mainUrl = "https://net52.cc"
    override var name = displayName

    override val hasMainPage = true
    private var bypassResult: BypassResult? = null
    private val headers = BROWSER_HEADERS

    private fun buildCookies(): Map<String, String> {
        val cookies = mutableMapOf(
            "t_hash_t" to (bypassResult?.cookie ?: ""),
            "ott" to "dp",
            "hd" to "on"
        )
        bypassResult?.addhash?.takeIf { it.isNotEmpty() }?.let { cookies["addhash"] = it }
        bypassResult?.usertoken?.takeIf { it.isNotEmpty() }?.let { cookies["usertoken"] = it }
        
        if (studio.isNotEmpty()) {
            cookies["studio"] = studio
        }
        return cookies
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (bypassResult == null || bypassResult?.cookie.isNullOrEmpty()) {
            bypassResult = bypass(mainUrl)
        }
        
        val document = app.get(
            "$mainUrl/mobile/home?app=1",
            cookies = buildCookies(),
            headers = headers,
            referer = "$mainUrl/mobile/home?app=1",
        ).document
        val items = document.select(".tray-container, #top10").map {
            it.toHomePageList()
        }
        return newHomePageResponse(items, false)
    }

    private fun Element.toHomePageList(): HomePageList {
        val name = select("h2, span").text()
        val items = select("article, .top10-post").mapNotNull {
            it.toSearchResult()
        }
        return HomePageList(name, items, isHorizontalImages = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val id = selectFirst("a")?.attr("data-post") ?: attr("data-post")

        return newAnimeSearchResponse("", Id(id).toJson()) {
            posterUrl = "https://imgcdn.kim/hs/v/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (bypassResult == null || bypassResult?.cookie.isNullOrEmpty()) {
            bypassResult = bypass(mainUrl)
        }
        
        val id = parseJson<Id>(url).id
        val data = app.get(
            "$mainUrl/mobile/hs/post.php?id=$id&t=${APIHolder.unixTime}",
            headers = headers,
            referer = "$mainUrl/home",
            cookies = buildCookies()
        ).parsed<PostData>()

        val episodes = arrayListOf<Episode>()
        val title = data.title
        val castList = data.cast?.split(",")?.map { it.trim() } ?: emptyList()
        val cast = castList.map { ActorData(Actor(it)) }
        val genre = data.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }

        val rating = data.match?.replace("IMDb ", "")
        val parsedRunTime = convertRuntimeToMinutes(data.runtime.toString())

        val suggest = data.suggest?.map { item ->
            newAnimeSearchResponse("", Id(item.id).toJson()) {
                posterUrl = "https://imgcdn.kim/hs/v/${item.id}.jpg"
                posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }

        if (data.episodes.first() == null) {
            episodes.add(newEpisode(LoadData(title, id)) {
                name = data.title
            })
        } else {
            data.episodes.filterNotNull().forEach { item ->
                episodes.add(newEpisode(LoadData(title, item.id)) {
                    name = item.t
                    episode = item.ep.replace("E", "").toIntOrNull()
                    season = item.s.replace("S", "").toIntOrNull()
                    posterUrl = "https://imgcdn.kim/hsepimg/150/${item.id}.jpg"
                    runTime = item.time.replace("m", "").toIntOrNull()
                })
            }

            if (data.nextPageShow == 1) {
                episodes.addAll(getEpisodes(title, id, data.nextPageSeason!!, 2))
            }

            data.season?.dropLast(1)?.amap { seasonItem ->
                episodes.addAll(getEpisodes(title, id, seasonItem.id, 1))
            }
        }

        val type = if (data.episodes.first() == null) TvType.Movie else TvType.TvSeries

        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = "https://imgcdn.kim/hs/v/$id.jpg"
            backgroundPosterUrl = "https://imgcdn.kim/hs/h/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
            plot = data.desc
            year = data.year.toIntOrNull()
            tags = genre
            actors = cast
            score = Score.from10(rating)
            duration = parsedRunTime
            contentRating = data.ua
            recommendations = suggest
        }
    }

    private suspend fun getEpisodes(
        title: String, eid: String, sid: String, page: Int
    ): List<Episode> {
        val episodes = arrayListOf<Episode>()
        var pg = page
        while (true) {
            val data = app.get(
                "$mainUrl/mobile/hs/episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg",
                headers = headers,
                referer = "$mainUrl/home",
                cookies = buildCookies()
            ).parsed<EpisodesData>()
            
            data.episodes?.filterNotNull()?.forEach { item ->
                episodes.add(newEpisode(LoadData(title, item.id)) {
                    name = item.t
                    episode = item.ep.replace("E", "").toIntOrNull()
                    season = item.s.replace("S", "").toIntOrNull()
                    posterUrl = "https://imgcdn.kim/hsepimg/${item.id}.jpg"
                    runTime = item.time.replace("m", "").toIntOrNull()
                })
            }
            if (data.nextPageShow == 0) break
            pg++
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ld = parseJson<LoadData>(data)
        
        val result = try {
            getPlaylistLink(mainUrl, bypassResult, ld.id, "dp", "hs/playlist.php")
        } catch (_: Exception) { null }

        if (result != null) {
            val source = result.sources.firstOrNull { !it.file.isNullOrBlank() }
            if (source != null) {
                val url = source.file!!
                val fullUrl = if (url.startsWith("http")) url else "$mainUrl$url"
                callback.invoke(
                    newExtractorLink(name, name, fullUrl, type = ExtractorLinkType.M3U8) {
                        this.referer = mainUrl
                    }
                )
            }
            result.tracks?.forEach { track ->
                val url = track.file ?: return@forEach
                val label = track.label ?: "Unknown"
                val kind = track.kind ?: ""
                if (kind == "captions" || url.endsWith(".srt") || url.endsWith(".vtt")) {
                    subtitleCallback.invoke(SubtitleFile(label, url))
                }
            }
            return true
        }

        val apiBase = resolveApiUrl()
        val response = app.get(
            "$apiBase/newtv/player.php?id=${ld.id}",
            headers = buildNewTvHeaders("hs", mapOf("Usertoken" to ""))
        ).parsed<NewTvPlayerResponse>()

        if (response.status != "ok" || response.video_link.isNullOrBlank()) return false

        callback.invoke(
            newExtractorLink(name, name, response.video_link, type = ExtractorLinkType.M3U8) {
                referer = response.referer ?: apiBase
            }
        )
        return true
    }

    @Suppress("ObjectLiteralToLambda")
    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        return object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): Response {
                val request = chain.request()
                val urlStr = request.url.toString()
                if (urlStr.contains(".m3u8") || urlStr.contains(".ts") || urlStr.contains(".jpg")) {
                    val bypass = bypassResult
                    val cookieParts = mutableListOf("t_hash_t=${bypass?.cookie ?: ""}", "hd=on", "ott=dp")
                    if (bypass != null && bypass.addhash.isNotEmpty()) cookieParts.add("addhash=${bypass.addhash}")
                    if (bypass != null && bypass.usertoken.isNotEmpty()) cookieParts.add("usertoken=${bypass.usertoken}")
                    if (studio.isNotEmpty()) cookieParts.add("studio=$studio")

                    val newRequest = request.newBuilder()
                        .header("Referer", "$mainUrl/mobile/home?app=1")
                        .header("Cookie", cookieParts.joinToString("; "))
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 Safari/537.36 /OS.Gatu v3.0")
                        .header("Origin", mainUrl)
                        .build()
                    return chain.proceed(newRequest)
                }
                return chain.proceed(request)
            }
        }
    }

    data class Id(
        val id: String
    )

    data class LoadData(
        val title: String,
        val id: String
    )
}
