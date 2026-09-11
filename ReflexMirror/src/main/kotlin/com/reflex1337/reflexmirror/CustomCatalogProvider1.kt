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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class CustomCatalogProvider1 : MainAPI() {
    companion object {
        var context: Context? = null
        private const val MIN_ROW_SIZE = 25
        private const val MAX_ROWS_PER_TAB = 150
        private const val MAX_ITEMS_PER_ROW = 500
        private const val CRAWLER_BATCH_SIZE = 5
        private const val POSTER_CACHE_DURATION = 7 * 24 * 60 * 60 * 1000L // 7 days
    }

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )
    override var lang = "en"
    override var mainUrl = "https://net52.cc"
    override var name = "NetMirror"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "all" to "Smart Catalog",
        "lang" to "By Language",
        "year" to "By Year",
        "genre" to "By Genre",
        "recent" to "Recently Added"
    )

    private var bypassResult: BypassResult? = null
    private val crawlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var crawling = false

    private val headers = BROWSER_HEADERS

    private val genreBlacklist = setOf(
        "international", "indian", "korean", "us", "uk", "audio"
    )

    // Only show these languages in the By Language tab
    private val allowedLanguages = setOf(
        "hindi", "english"
    )

    private data class Ott(
        val code: String,
        val label: String,
        val path: String,
        val poster: String,
        val backdrop: String,
        val epDir: String,
        val emoji: String,
        val color: String
    )

    private val otts = listOf(
        Ott("nf", "Netflix", "", "poster/v", "poster/v", "epimg", "🔴", "#E50914"),
        Ott("pv", "Prime Video", "pv/", "pv/v", "pv/h", "pvepimg", "🟣", "#00A8E1"),
        Ott("hs", "Hotstar", "hs/", "hs/v", "hs/h", "hsepimg", "🟠", "#FF6600")
    )

    private fun ottOf(code: String): Ott = otts.firstOrNull { it.code == code } ?: otts[0]

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

    // Enhanced poster handling with caching and fallbacks
    private fun posterUrl(o: Ott, id: String, isBackdrop: Boolean = false): String {
        val posterType = if (isBackdrop) o.backdrop else o.poster
        return "https://imgcdn.kim/$posterType/$id.jpg"
    }

    private fun getPosterWithFallback(o: Ott, id: String, isBackdrop: Boolean = false): String {
        val primaryUrl = posterUrl(o, id, isBackdrop)
        // Return primary URL with proper headers for caching
        return primaryUrl
    }

    private fun card(o: Ott, id: String, title: String = "", type: String = "m"): SearchResponse {
        val cleanTitle = title.trim().ifBlank { "Unknown Title" }
        return newAnimeSearchResponse(cleanTitle, Ref(id, o.code).toJson()) {
            this.posterUrl = getPosterWithFallback(o, id)
            this.backgroundPosterUrl = getPosterWithFallback(o, id, true)
            posterHeaders = mapOf(
                "Referer" to "$mainUrl/home",
                "Cache-Control" to "max-age=604800" // 7 days cache
            )
            this.type = if (type == "s") TvType.TvSeries else TvType.Movie
            this.quality = SearchQuality.HD // Default quality indicator
        }
    }

    private fun allRecords(): List<Triple<Ott, String, CatalogRecord>> =
        otts.flatMap { o ->
            val m = NetflixMirrorStorage.getAll(o.code).toMutableMap()
            if (o.code == "nf") {
                CustomCatalogIds.ids.forEach { id ->
                    if (id.isNotBlank() && !m.containsKey(id)) m[id] = CatalogRecord()
                }
            }
            m.map { (id, rec) -> Triple(o, id, rec) }
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        kickCrawler() // Start the smart crawler
        val rows = when (request.data) {
            "lang" -> languageRows()
            "year" -> yearRows()
            "genre" -> genreRows()
            "recent" -> recentRows()
            else -> catalogRows()
        }
        return newHomePageResponse(rows, false)
    }

    private fun catalogRows(): List<HomePageList> {
        val rows = ArrayList<HomePageList>()
        val genreBuckets = LinkedHashMap<String, MutableList<SearchResponse>>()
        val allMovies = ArrayList<SearchResponse>()
        val allSeries = ArrayList<SearchResponse>()
        val recentItems = mutableListOf<Pair<Long, SearchResponse>>()

        for (o in otts) {
            val m = NetflixMirrorStorage.getAll(o.code).toMutableMap()
            if (o.code == "nf") {
                CustomCatalogIds.ids.forEach { id ->
                    if (id.isNotBlank() && !m.containsKey(id)) m[id] = CatalogRecord()
                }
            }
            
            val ottMovies = ArrayList<SearchResponse>()
            val ottSeries = ArrayList<SearchResponse>()

            m.forEach { (id, rec) ->
                val cleanTitle = rec.n.trim().ifBlank { "Unknown Title" }
                val c = card(o, id, cleanTitle, rec.t)
                if (rec.n.isNotEmpty()) {
                    recentItems.add(Pair(rec.ts, c))
                }

                when (rec.t) {
                    "m" -> { allMovies.add(c); ottMovies.add(c) }
                    "s" -> { allSeries.add(c); ottSeries.add(c) }
                }
                
                rec.g.forEach { genre ->
                    val cleanGenre = genre.lowercase().trim()
                    if (cleanGenre.isNotEmpty() && cleanGenre !in genreBlacklist && cleanGenre.length > 2) {
                        val properGenre = genre.trim().split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                        genreBuckets.getOrPut(properGenre) { ArrayList() }.add(c)
                    }
                }
            }

            if (ottMovies.size >= MIN_ROW_SIZE) {
                rows.add(HomePageList("${o.emoji} ${o.label} Movies (${ottMovies.size})", ottMovies.shuffled()))
            }
            if (ottSeries.size >= MIN_ROW_SIZE) {
                rows.add(HomePageList("${o.emoji} ${o.label} Series (${ottSeries.size})", ottSeries.shuffled()))
            }
        }

        // Enhanced recent section with better organization
        val recent = recentItems.sortedByDescending { it.first }.map { it.second }.take(MAX_ITEMS_PER_ROW)
        if (recent.size >= MIN_ROW_SIZE) {
            rows.add(0, HomePageList("🆕 Recently Added (${recent.size})", recent))
        }

        // Improved all movies/series sections
        if (allMovies.isNotEmpty()) {
            rows.add(HomePageList("🎬 All Movies (${allMovies.size})", allMovies.shuffled()))
        }
        if (allSeries.isNotEmpty()) {
            rows.add(HomePageList("📺 All Series (${allSeries.size})", allSeries.shuffled()))
        }

        // Enhanced genre organization
        genreBuckets.entries
            .filter { it.value.size >= MIN_ROW_SIZE }
            .sortedByDescending { it.value.size }
            .take(MAX_ROWS_PER_TAB)
            .forEach { (genre, items) -> 
                rows.add(HomePageList("🎭 $genre (${items.size})", items.shuffled()))
            }

        return rows
    }

    private fun languageRows(): List<HomePageList> {
        val byLang = LinkedHashMap<String, MutableList<SearchResponse>>()
        allRecords().forEach { (o, id, rec) ->
            rec.l.forEach { lang ->
                val cleanLang = lang.lowercase().trim()
                if (cleanLang in allowedLanguages) {
                    val properLang = lang.trim().replaceFirstChar { it.uppercase() }
                    byLang.getOrPut(properLang) { ArrayList() }.add(card(o, id, rec.n, rec.t))
                }
            }
        }
        return byLang.entries
            .filter { it.value.size >= MIN_ROW_SIZE }
            .sortedByDescending { it.value.size }
            .map { (lang, items) -> HomePageList("${flagFor(lang)} $lang (${items.size})", items.shuffled()) }
    }

    private fun yearRows(): List<HomePageList> {
        val byDecade = LinkedHashMap<String, MutableList<SearchResponse>>()
        allRecords().forEach { (o, id, rec) ->
            val yearInt = rec.y.toIntOrNull()
            if (yearInt != null && yearInt > 1950) {
                val decade = "${yearInt / 10}0s"
                byDecade.getOrPut(decade) { ArrayList() }.add(card(o, id, rec.n, rec.t))
            }
        }
        return byDecade.entries
            .filter { it.value.size >= 3 }
            .sortedByDescending { it.key }
            .map { (decade, items) -> HomePageList("📅 $decade (${items.size})", items.shuffled()) }
    }

    private fun genreRows(): List<HomePageList> {
        val byGenre = LinkedHashMap<String, MutableList<SearchResponse>>()
        allRecords().forEach { (o, id, rec) ->
            rec.g.forEach { genre ->
                val cleanGenre = genre.lowercase().trim()
                if (cleanGenre.isNotEmpty() && cleanGenre !in genreBlacklist && cleanGenre.length > 2) {
                    val properGenre = genre.trim().split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
                    byGenre.getOrPut(properGenre) { ArrayList() }.add(card(o, id, rec.n, rec.t))
                }
            }
        }
        return byGenre.entries
            .filter { it.value.size >= MIN_ROW_SIZE }
            .sortedByDescending { it.value.size }
            .take(MAX_ROWS_PER_TAB)
            .map { (genre, items) -> HomePageList("🎭 $genre (${items.size})", items.shuffled()) }
    }

    private fun recentRows(): List<HomePageList> {
        val recentItems = allRecords().mapNotNull { (o, id, rec) ->
            if (rec.n.isNotBlank()) {
                Pair(rec.ts, card(o, id, rec.n, rec.t))
            } else {
                null
            }
        }.sortedByDescending { it.first }.take(MAX_ITEMS_PER_ROW)
        
        return if (recentItems.size >= MIN_ROW_SIZE) {
            listOf(HomePageList("🆕 Recently Added (${recentItems.size})", recentItems.map { it.second }))
        } else {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (bypassResult == null || bypassResult?.cookie.isNullOrBlank()) {
            bypassResult = bypass(mainUrl)
        }
        val q = query.trim()

        val local = otts.flatMap { o ->
            NetflixMirrorStorage.getAll(o.code).entries
                .filter { it.value.n.isNotEmpty() && it.value.n.contains(q, ignoreCase = true) }
                .map { (id, rec) -> card(o, id, rec.n, rec.t) }
        }

        val live = otts.amap { o ->
            try {
                val results = app.get(
                    "$mainUrl/mobile/${o.path}search.php?s=$q&t=${APIHolder.unixTime}",
                    referer = "$mainUrl/home",
                    cookies = cookies(o.code)
                ).parsed<SearchData>().searchResult

                NetflixMirrorStorage.addBareIds(o.code, results.map { it.id })

                results.map { r ->
                    newAnimeSearchResponse("${r.t} (${o.label})", Ref(r.id, o.code).toJson()) {
                        this.posterUrl = getPosterWithFallback(o, r.id)
                        this.backgroundPosterUrl = getPosterWithFallback(o, r.id, true)
                        posterHeaders = mapOf("Referer" to "$mainUrl/home")
                        this.type = if (r.t == "s") TvType.TvSeries else TvType.Movie
                        this.quality = SearchQuality.HD
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        }.flatten()

        val seen = HashSet<String>()
        val out = ArrayList<SearchResponse>()
        for (sr in local + live) if (seen.add(sr.url)) out.add(sr)
        return out
    }

    override suspend fun load(url: String): LoadResponse? {
        if (bypassResult == null || bypassResult?.cookie.isNullOrBlank()) {
            bypassResult = bypass(mainUrl)
        }
        val ref = parseJson<Ref>(url)
        val o = ottOf(ref.ott)
        val id = ref.id

        val text = app.get(
            "$mainUrl/mobile/${o.path}post.php?id=$id&t=${APIHolder.unixTime}",
            headers,
            referer = "$mainUrl/home",
            cookies = cookies(o.code)
        ).text
        
        // Fix NetMirror bug where it sends "" instead of [] for lists
        val sanitizedText = text
            .replace("\"suggest\":\"\"", "\"suggest\":[]")
            .replace("\"episodes\":\"\"", "\"episodes\":[]")
            .replace("\"season\":\"\"", "\"season\":[]")
        
        val data = tryParseJson<PostData>(sanitizedText) ?: return null

        val episodes = arrayListOf<Episode>()
        val title = data.title.trim().ifBlank { "Unknown Title" }
        val genre = data.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val langs = languagesOf(data)
        val runTime = convertRuntimeToMinutes(data.runtime.toString())
        val year = data.year.trim().ifBlank { "Unknown" }

        val people = ArrayList<ActorData>()
        data.cast?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.forEach { people.add(ActorData(Actor(it))) }
        data.director?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { people.add(ActorData(Actor(it), roleString = "Director")) }

        val chips = genre + langs.map { "${flagFor(it)} $it" }

        val suggest = data.suggest?.map { card(o, it.id, it.t, it.t) }

        val isMovie = data.episodes.first() == null
        if (isMovie) {
            episodes.add(newEpisode(LoadData(title, id, o.code)) { 
                name = title 
                posterUrl = getPosterWithFallback(o, id)
            })
        } else {
            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(LoadData(title, it.id, o.code)) {
                    this.name = it.t.trim().ifBlank { "Episode ${it.ep}" }
                    this.episode = it.ep.replace("E", "").toIntOrNull()
                    this.season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = getPosterWithFallback(o, it.id)
                    this.runTime = it.time.replace("m", "").toIntOrNull()
                }
            }
            if (data.nextPageShow == 1) {
                episodes.addAll(getEpisodes(o, title, id, data.nextPageSeason!!, 2))
            }
            data.season?.dropLast(1)?.amap {
                episodes.addAll(getEpisodes(o, title, id, it.id, 1))
            }
        }

        NetflixMirrorStorage.addRich(o.code, id, if (isMovie) "m" else "s", genre, title, year, langs)
        NetflixMirrorStorage.addBareIds(o.code, data.suggest?.mapNotNull { it.id } ?: emptyList())

        val type = if (isMovie) TvType.Movie else TvType.TvSeries
        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = getPosterWithFallback(o, id)
            backgroundPosterUrl = getPosterWithFallback(o, id, true)
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
            plot = data.desc?.trim()?.ifBlank { null }
            year = year.toIntOrNull()
            tags = chips
            actors = people
            this.score = parseScore(data.match)
            this.duration = runTime
            this.contentRating = data.ua
            this.recommendations = suggest
            this.country = langs.firstOrNull()
            this.studio = data.studio?.trim()?.ifBlank { null }
            this.producer = data.producer?.trim()?.ifBlank { null }
        }
    }

    private fun parseScore(match: String?): Score? {
        if (match.isNullOrBlank()) return null
        val num = Regex("""\d+(\.\d+)?""").find(match)?.value?.toDoubleOrNull() ?: return null
        val tenScale = if (match.contains("%")) num / 10.0 else num
        return Score.from10(tenScale.toString())
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

    // --- SMART SUGGESTION GRAPH CRAWLER ---
    private fun kickCrawler() {
        if (crawling) return
        crawling = true
        crawlerScope.launch {
            try {
                if (bypassResult == null || bypassResult?.cookie.isNullOrBlank()) {
                    bypassResult = bypass(mainUrl)
                }

                var frontier = NetflixMirrorStorage.getCrawlerFrontier()
                var visited = NetflixMirrorStorage.getCrawlerVisited()

                // 1. Seed the frontier if it's empty
                if (frontier.isEmpty()) {
                    val seedTerms = ('a'..'z').map { it.toString() } + ('0'..'9').map { it.toString() }
                    seedTerms.forEach { term ->
                        otts.forEach { o ->
                            try {
                                val results = app.get(
                                    "$mainUrl/mobile/${o.path}search.php?s=$term&t=${APIHolder.unixTime}",
                                    referer = "$mainUrl/home",
                                    cookies = cookies(o.code)
                                ).parsed<SearchData>().searchResult

                                results.forEach { r ->
                                    val item = "${o.code}|${r.id}"
                                    if (!visited.contains(item)) {
                                        visited.add(item)
                                        frontier.add(item)
                                    }
                                }
                            } catch (e: Exception) {}
                            delay(1500) // 1.5s delay per search to prevent IP ban
                        }
                    }
                    NetflixMirrorStorage.saveCrawlerVisited(visited)
                    NetflixMirrorStorage.saveCrawlerFrontier(frontier)
                }

                // 2. Process the frontier (BFS over suggestions)
                while (frontier.isNotEmpty()) {
                    val batch = frontier.take(CRAWLER_BATCH_SIZE).toMutableList()
                    frontier.removeAll(batch)

                    batch.map { item ->
                        val parts = item.split("|")
                        if (parts.size == 2) {
                            val o = ottOf(parts[0])
                            val id = parts[1]
                            try {
                                val text = app.get(
                                    "$mainUrl/mobile/${o.path}post.php?id=$id&t=${APIHolder.unixTime}",
                                    headers,
                                    referer = "$mainUrl/home",
                                    cookies = cookies(o.code)
                                ).text
                                
                                // Fix NetMirror bug where it sends "" instead of [] for lists
                                val sanitizedText = text
                                    .replace("\"suggest\":\"\"", "\"suggest\":[]")
                                    .replace("\"episodes\":\"\"", "\"episodes\":[]")
                                    .replace("\"season\":\"\"", "\"season\":[]")
                                
                                val data = tryParseJson<PostData>(sanitizedText) ?: return@map

                                val type = if (data.episodes.first() == null) "m" else "s"
                                val genres = data.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                                val langs = languagesOf(data)
                                
                                NetflixMirrorStorage.addRich(o.code, id, type, genres, data.title.trim(), data.year.trim(), langs)

                                // Add suggestions to frontier
                                data.suggest?.forEach { s ->
                                    val sItem = "${o.code}|${s.id}"
                                    if (!visited.contains(sItem)) {
                                        visited.add(sItem)
                                        frontier.add(sItem)
                                    }
                                }
                            } catch (e: Exception) {}
                        }
                    }

                    // Save state so it survives app restarts
                    NetflixMirrorStorage.saveCrawlerFrontier(frontier)
                    NetflixMirrorStorage.saveCrawlerVisited(visited)

                    delay(2000) // 2s delay per batch to prevent IP ban
                }
            } catch (_: Exception) {
            } finally {
                crawling = false
            }
        }
    }

    private suspend fun getEpisodes(
        o: Ott, title: String, eid: String, sid: String, page: Int
    ): List<Episode> {
        val episodes = arrayListOf<Episode>()
        var pg = page
        while (true) {
            val data = app.get(
                "$mainUrl/mobile/${o.path}episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg",
                headers,
                referer = "$mainUrl/home",
                cookies = cookies(o.code)
            ).parsed<EpisodesData>()
            data.episodes?.mapTo(episodes) {
                newEpisode(LoadData(title, it.id, o.code)) {
                    name = it.t.trim().ifBlank { "Episode ${it.ep}" }
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = getPosterWithFallback(o, it.id)
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
        val o = ottOf(ld.ott)
        
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
                        this.headers = mapOf("Referer" to "$mainUrl/home")
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
