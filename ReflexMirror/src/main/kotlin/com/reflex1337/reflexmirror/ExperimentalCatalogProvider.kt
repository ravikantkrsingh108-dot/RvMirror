package com.reflex1337.reflexmirror

import android.content.Context
import com.reflex1337.reflexmirror.entities.EpisodesData
import com.reflex1337.reflexmirror.entities.PostData
import com.reflex1337.reflexmirror.entities.SearchData
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import com.lagradost.cloudstream3.APIHolder.unixTime

class ExperimentalCatalogProvider : MainAPI() {
    override var name = "NetMirror Smart (Experimental)"
    override var mainUrl = "https://net52.cc"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    override var lang = "en"
    override val hasMainPage = true

    private var bypassResult: BypassResult? = null
    private val headers = BROWSER_HEADERS

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

    // Smart Search Queries to dynamically build accurate rows
    private val smartQueries = listOf(
        "James Bond" to "🕶️ James Bond Collection",
        "Slasher" to "🔪 Slashers & Serial Killers",
        "Survival" to "🏔️ Survival & Wilderness",
        "Feel Good" to "😊 Feel Good Movies",
        "Sony Pictures" to "🎥 Sony Pictures Classics",
        "Studio Ghibli" to "🍃 Studio Ghibli Magic",
        "Time Travel" to "⏳ Time Travel Adventures",
        "Vampire" to "🧛 Vampires & Dracula",
        "Zombie" to "🧟 Zombie Apocalypse",
        "Alien" to "👽 Alien & Space Invaders",
        "Heist" to "💰 Heists & Robberies",
        "Assassin" to "🔫 Assassins & Hitmen",
        "Post Apocalyptic" to "☢️ Post-Apocalyptic Worlds",
        "Coming of Age" to "🎓 Coming of Age"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (bypassResult == null || bypassResult?.cookie.isNullOrBlank()) {
            bypassResult = bypass(mainUrl)
        }

        val rows = ArrayList<HomePageList>()

        // Fetch dynamic rows using NetMirror's search engine across all OTTs
        // We shuffle the queries so the homepage looks different every time you open it
        for ((query, rowName) in smartQueries.shuffled().take(8)) {
            val items = ArrayList<SearchResponse>()
            
            for (o in otts) {
                try {
                    val results = app.get(
                        "$mainUrl/mobile/${o.path}search.php?s=$query&t=${APIHolder.unixTime}",
                        referer = "$mainUrl/home",
                        cookies = cookies(o.code)
                    ).parsed<SearchData>().searchResult

                    results.forEach { r ->
                        items.add(newAnimeSearchResponse(r.t, Ref(r.id, o.code).toJson()) {
                            this.posterUrl = posterUrl(o.code, r.id)
                            posterHeaders = mapOf("Referer" to "$mainUrl/home")
                        })
                    }
                } catch (e: Exception) {}
            }

            if (items.isNotEmpty()) {
                rows.add(HomePageList("$rowName (${items.size})", items.shuffled().take(60)))
            }
        }

        // Also add a few rows from local storage (Recently Added & Top Movies)
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

        if (recent.size >= 5) {
            rows.add(0, HomePageList("🆕 Recently Added (${recent.size})", recent))
        }

        val movies = allRecords.filter { it.third.t == "m" && it.third.n.isNotEmpty() }
            .map { (ott, id, rec) -> card(ott, id, rec.n) }
        if (movies.size >= 5) {
            rows.add(HomePageList("🎬 All Movies (${movies.size})", movies.shuffled().take(60)))
        }

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

        // --- SMART LOCAL RECOMMENDATIONS ---
        // We look for exact title keyword matches (length > 4 to avoid "The", "A")
        // AND we ensure the genre matches exactly (case-insensitive).
        val localRecs = ArrayList<SearchResponse>()
        val titleKeywords = title.split(" ").filter { it.length > 4 }.map { it.lowercase() }
        
        for ((recId, rec) in NetflixMirrorStorage.getAll(ottCode)) {
            if (recId == id) continue
            if (rec.n.isEmpty()) continue

            val recTitleLower = rec.n.lowercase()
            val recGenresLower = rec.g.map { it.lowercase() }
            
            val sharesGenre = genre.any { g -> recGenresLower.contains(g.lowercase()) }
            val sharesKeyword = titleKeywords.any { kw -> recTitleLower.contains(kw) }

            // Require BOTH a keyword match AND a genre match to prevent false positives
            if (sharesGenre && sharesKeyword) {
                localRecs.add(card(ottCode, recId, rec.n))
            }
            if (localRecs.size >= 20) break
        }

        // Fallback to NetMirror's suggestions if smart local recs fail
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
