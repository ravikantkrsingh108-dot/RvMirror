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
import com.lagradost.cloudstream3.APIHolder.unixTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class ExperimentalCatalogProvider : MainAPI() {
    override var name = "NetMirror Premium (TMDB)"
    override var mainUrl = "https://net52.cc"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    override var lang = "en"
    override val hasMainPage = true

    private var bypassResult: BypassResult? = null
    private val headers = BROWSER_HEADERS

    // PASTE YOUR TMDB API KEY HERE
    private val tmdbApiKey = "a39a5db3f106797c36ec426da8b94095" 
    private val tmdbUrl = "https://api.themoviedb.org/3"

    private fun cookies(ott: String): Map<String, String> {
        val c = mutableMapOf(
            "t_hash_t" to (bypassResult?.cookie ?: ""),
            "hd" to "on",
            "ott" to ott
        )
        bypassResult?.addhash?.takeIf { it.isNotEmpty() }?.let { c["addhash"] = it }
        bypassResult?.usertoken?.takeIf { it.isNotEmpty() }?.let { c["usertoken"] = it }
        return c
    }

    private fun posterUrl(o: String, id: String): String {
        val prefix = when(o) {
            "pv" -> "pv/v"
            "hs" -> "hs/v"
            else -> "poster/v"
        }
        return "https://imgcdn.kim/$prefix/$id.jpg"
    }

    private fun card(ott: String, id: String, title: String = ""): SearchResponse =
        newAnimeSearchResponse(title, Ref(id, ott).toJson()) {
            this.posterUrl = posterUrl(ott, id)
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
        }

    private data class OttInfo(val code: String, val label: String, val path: String)
    private val otts = listOf(
        OttInfo("nf", "Netflix", ""),
        OttInfo("pv", "Prime Video", "pv/"),
        OttInfo("hs", "Hotstar", "hs/")
    )

    // Data classes for TMDB API response
    data class TmdbResponse(val results: List<TmdbItem>? = null)
    data class TmdbItem(val id: Int, val title: String? = null, val name: String? = null, val release_date: String? = null, val first_air_date: String? = null)

    // Fetches a list from TMDB and cross-references with local storage
    private suspend fun fetchTmdbRow(rowName: String, tmdbListId: String, isMovie: Boolean): HomePageList? {
        try {
            val tmdbData = app.get("$tmdbUrl/list/$tmdbListId?api_key=$tmdbApiKey&language=en-US").parsed<TmdbResponse>()
            val items = ArrayList<SearchResponse>()
            val allRecords = mutableListOf<Triple<String, String, CatalogRecord>>()

            for (o in otts) {
                NetflixMirrorStorage.getAll(o.code).forEach { (id, rec) ->
                    allRecords.add(Triple(o.code, id, rec))
                }
            }

            for (tmdbItem in tmdbData.results ?: emptyList()) {
                val tmdbTitle = tmdbItem.title ?: tmdbItem.name ?: continue
                val tmdbYear = (tmdbItem.release_date ?: tmdbItem.first_air_date ?: "").substringBefore("-")

                // Find a match in local storage
                val match = allRecords.find { (_, _, rec) ->
                    rec.n.equals(tmdbTitle, ignoreCase = true) && (rec.y == tmdbYear || rec.y.isEmpty())
                }

                if (match != null) {
                    items.add(card(match.first, match.second, match.third.n))
                }
            }

            return if (items.size >= 5) HomePageList(rowName, items.shuffled().take(60)) else null
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (bypassResult == null || bypassResult?.cookie.isNullOrBlank()) {
            bypassResult = bypass(mainUrl)
        }

        val rows = ArrayList<HomePageList>()

        // 1. Local Recently Added
        val allRecords = mutableListOf<Triple<String, String, CatalogRecord>>()
        for (o in otts) {
            NetflixMirrorStorage.getAll(o.code).forEach { (id, rec) ->
                allRecords.add(Triple(o.code, id, rec))
            }
        }
        val recent = allRecords.filter { it.third.n.isNotEmpty() }
            .sortedByDescending { it.third.ts }
            .take(60)
            .map { (ott, id, rec) -> card(ott, id, rec.n) }
        if (recent.size >= 5) rows.add(HomePageList("🆕 Recently Added", recent))

        // 2. TMDB Curated Lists (Requires TMDB List IDs)
        // You can create these lists on TMDB website and put their IDs here
        // Example: "Top 100 Movies" -> list ID 12345
        val tmdbLists = mapOf(
            "🎬 Top 100 Movies" to "5e7c5f3c3a4d6c0001e1c2b3", // Replace with real TMDB List ID
            "⚔️ Best Action Movies" to "5e7c5f3c3a4d6c0001e1c2b4", // Replace with real TMDB List ID
            "😂 Best Comedies" to "5e7c5f3c3a4d6c0001e1c2b5",    // Replace with real TMDB List ID
            "👻 Horror Classics" to "5e7c5f3c3a4d6c0001e1c2b6"   // Replace with real TMDB List ID
        )

        // Fetch TMDB rows in parallel for speed
        val tmdbJobs = tmdbLists.map { (name, id) ->
            coroutineScope {
                async { fetchTmdbRow(name, id, true) }
            }
        }.awaitAll()

        tmdbJobs.filterNotNull().forEach { rows.add(it) }

        // 3. Local All Movies/Series Fallback
        val movies = allRecords.filter { it.third.t == "m" && it.third.n.isNotEmpty() }
            .map { (ott, id, rec) -> card(ott, id, rec.n) }
        if (movies.size >= 5) rows.add(HomePageList("🎬 All Local Movies", movies.shuffled().take(60)))

        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        if (bypassResult == null || bypassResult?.cookie.isNullOrBlank()) {
            bypassResult = bypass(mainUrl)
        }
        val ref = parseJson<Ref>(url)
        val ottCode = ref.ott
        val path = when(ottCode) {
            "pv" -> "pv/"
            "hs" -> "hs/"
            else -> ""
        }
        val id = ref.id

        val text = app.get(
            "$mainUrl/mobile/${path}post.php?id=$id&t=${APIHolder.unixTime}",
            headers,
            referer = "$mainUrl/home",
            cookies = cookies(ottCode)
        ).text

        val sanitizedText = text
            .replace("\"suggest\":\"\"", "\"suggest\":[]")
            .replace("\"episodes\":\"\"", "\"episodes\":[]")
            .replace("\"season\":\"\"", "\"season\":[]")

        val data = tryParseJson<PostData>(sanitizedText) ?: return null

        val episodes = arrayListOf<Episode>()
        val title = data.title
        val genre = data.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val langs = languagesOf(data)
        val runTime = convertRuntimeToMinutes(data.runtime.toString())

        val people = ArrayList<ActorData>()
        data.cast?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.forEach { people.add(ActorData(Actor(it))) }
        data.director?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { people.add(ActorData(Actor(it), roleString = "Director")) }

        val chips = genre + langs.map { "${flagFor(it)} $it" }

        // --- TMDB RECOMMENDATIONS ---
        val localRecs = ArrayList<SearchResponse>()
        try {
            // 1. Search TMDB for the current movie to get TMDB ID
            val searchUrl = "$tmdbUrl/search/movie?api_key=$tmdbApiKey&query=${java.net.URLEncoder.encode(title, "UTF-8")}&year=${data.year}"
            val searchResp = app.get(searchUrl).parsed<TmdbResponse>()
            val tmdbId = searchResp.results?.firstOrNull()?.id

            if (tmdbId != null) {
                // 2. Get TMDB Recommendations
                val recUrl = "$tmdbUrl/movie/$tmdbId/recommendations?api_key=$tmdbApiKey"
                val recResp = app.get(recUrl).parsed<TmdbResponse>()

                // 3. Cross-reference TMDB recommendations with local storage
                val allRecords = mutableListOf<Triple<String, String, CatalogRecord>>()
                for (o in otts) {
                    NetflixMirrorStorage.getAll(o.code).forEach { (id, rec) ->
                        allRecords.add(Triple(o.code, id, rec))
                    }
                }

                for (tmdbRec in recResp.results ?: emptyList()) {
                    val recTitle = tmdbRec.title ?: tmdbRec.name ?: continue
                    val recYear = (tmdbRec.release_date ?: tmdbRec.first_air_date ?: "").substringBefore("-")

                    val match = allRecords.find { (_, _, rec) ->
                        rec.n.equals(recTitle, ignoreCase = true) && (rec.y == recYear || rec.y.isEmpty())
                    }

                    if (match != null) {
                        localRecs.add(card(match.first, match.second, match.third.n))
                    }
                    if (localRecs.size >= 20) break
                }
            }
        } catch (e: Exception) {}

        // Use TMDB recs if found, otherwise fallback to NetMirror's suggestions
        val suggest = if (localRecs.isNotEmpty()) localRecs else data.suggest?.map { card(ottCode, it.id) }

        val isMovie = data.episodes.first() == null
        if (isMovie) {
            episodes.add(newEpisode(LoadData(title, id, ottCode)) { name = data.title })
        } else {
            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(LoadData(title, it.id, ottCode)) {
                    this.name = it.t
                    this.episode = it.ep.replace("E", "").toIntOrNull()
                    this.season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/epimg/150/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }
            if (data.nextPageShow == 1) {
                episodes.addAll(getEpisodes(ottCode, path, title, id, data.nextPageSeason!!, 2))
            }
            data.season?.dropLast(1)?.amap {
                episodes.addAll(getEpisodes(ottCode, path, title, id, it.id, 1))
            }
        }

        NetflixMirrorStorage.addRich(ottCode, id, if (isMovie) "m" else "s", genre, title, data.year, langs)
        NetflixMirrorStorage.addBareIds(ottCode, data.suggest?.mapNotNull { it.id } ?: emptyList())

        val type = if (isMovie) TvType.Movie else TvType.TvSeries
        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = posterUrl(ottCode, id)
            backgroundPosterUrl = posterUrl(ottCode, id)
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
            plot = data.desc?.trim()?.ifBlank { null }
            year = data.year.toIntOrNull()
            tags = chips
            actors = people
            this.score = parseScore(data.match)
            this.duration = runTime
            this.contentRating = data.ua
            this.recommendations = suggest
        }
    }

    private fun languagesOf(data: PostData): List<String> =
        (languageList(data.language) + languageList(data.lang))
            .map { it.replaceFirstChar { c -> c.uppercase() } }
            .distinct()

    private fun languageList(value: Any?): List<String> = when (value) {
        null -> emptyList()
        is String -> value.split(",", "/", "|", "&").map { it.trim() }.filter { it.isNotEmpty() }
        is Map<*, *> -> listOfNotNull(langFromMap(value))
        is Collection<*> -> value.flatMap { languageList(it) }
        else -> emptyList()
    }

    private fun langFromMap(m: Map<*, *>): String? {
        for (k in listOf("l", "lang", "language", "name", "title", "label", "n", "audio")) {
            (m[k] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun flagFor(language: String): String = when (language.lowercase().trim()) {
        "hindi", "tamil", "telugu", "malayalam", "kannada", "bengali", "marathi",
        "punjabi", "gujarati", "bhojpuri", "urdu", "odia", "assamese" -> "🇮🇳"
        "english" -> "🇬🇧"
        "korean" -> "🇰🇷"
        "japanese" -> "🇯🇵"
        "chinese", "mandarin", "cantonese" -> "🇨🇳"
        "spanish", "español" -> "🇪🇸"
        "french" -> "🇫🇷"
        "german" -> "🇩🇪"
        "italian" -> "🇮🇹"
        "portuguese" -> "🇵🇹"
        "russian" -> "🇷🇺"
        "arabic" -> "🇸🇦"
        "thai" -> "🇹🇭"
        "turkish" -> "🇹🇷"
        "indonesian" -> "🇮🇩"
        "filipino", "tagalog" -> "🇵🇭"
        "vietnamese" -> "🇻🇳"
        else -> "🌐"
    }

    private fun parseScore(match: String?): Score? {
        if (match.isNullOrBlank()) return null
        val num = Regex("""\d+(\.\d+)?""").find(match)?.value?.toDoubleOrNull() ?: return null
        val tenScale = if (match.contains("%")) num / 10.0 else num
        return Score.from10(tenScale.toString())
    }

    private suspend fun getEpisodes(
        ottCode: String, path: String, title: String, eid: String, sid: String, page: Int
    ): List<Episode> {
        val episodes = arrayListOf<Episode>()
        var pg = page
        while (true) {
            val data = app.get(
                "$mainUrl/mobile/${path}episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg",
                headers,
                referer = "$mainUrl/home",
                cookies = cookies(ottCode)
            ).parsed<EpisodesData>()
            data.episodes?.mapTo(episodes) {
                newEpisode(LoadData(title, it.id, ottCode)) {
                    name = it.t
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/epimg/150/${it.id}.jpg"
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
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
        val playlistPath = when(ld.ott) {
            "pv" -> "pv/playlist.php"
            "hs" -> "hs/playlist.php"
            else -> "playlist.php"
        }

        val result = try {
            getPlaylistLink(mainUrl, bypassResult, ld.id, ld.ott, playlistPath)
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
        if (apiBase.isBlank()) return false

        val text = app.get(
            "$apiBase/newtv/player.php?id=${ld.id}",
            headers = buildNewTvHeaders(ld.ott, mapOf("Usertoken" to ""))
        ).text
        val response = tryParseJson<NewTvPlayerResponse>(text)

        if (response?.video_link.isNullOrBlank()) return false

        callback.invoke(
            newExtractorLink(name, name, response!!.video_link!!, type = ExtractorLinkType.M3U8) {
                this.referer = response.referer ?: apiBase
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
                    val cookieParts = mutableListOf("t_hash_t=${bypass?.cookie ?: ""}", "hd=on", "ott=nf")
                    if (bypass != null && bypass.addhash.isNotEmpty()) cookieParts.add("addhash=${bypass.addhash}")
                    if (bypass != null && bypass.usertoken.isNotEmpty()) cookieParts.add("usertoken=${bypass.usertoken}")

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

    data class Ref(
        val id: String,
        val ott: String
    )

    data class LoadData(
        val title: String,
        val id: String,
        val ott: String
    )
}
