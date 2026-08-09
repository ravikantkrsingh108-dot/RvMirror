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
            "hs", "dp" -> "hs/v"
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

    // --- AI KNOWLEDGE BASE ---
    // Curated list of themes, franchises, and moods mapped to strict keywords
    private val knowledgeBase = mapOf(
        "🧙‍♂️ Wizarding World (Harry Potter)" to listOf("harry potter", "fantastic beasts", "hogwarts", "dumbledore", "voldemort"),
        "🕷️ Spider-Man Universe" to listOf("spider-man", "spiderman", "venom", "morbius", "madame web"),
        "🦇 Batman & Gotham" to listOf("batman", "dark knight", "joker", "gotham", "penguin"),
        "🤠 Marvel Cinematic Universe" to listOf("avengers", "iron man", "captain america", "thor", "black panther", "doctor strange", "guardians of the galaxy", "wakanda", "hulk"),
        "🦸 DC Extended Universe" to listOf("superman", "wonder woman", "aquaman", "flash", "justice league", "green lantern", "shazam"),
        "⚔️ Middle Earth (Lord of the Rings)" to listOf("lord of the rings", "the hobbit", "gandalf", "aragorn"),
        "🚀 Star Wars Galaxy" to listOf("star wars", "mandalorian", "skywalker", "boba fett", "ahsoka", "yoda"),
        "🏎️ Fast & Furious" to listOf("fast and furious", "fast & furious", "toretto", "hobbs and shaw"),
        "🤖 Transformers" to listOf("transformers", "bumblebee", "optimus prime", "megatron"),
        "🦖 Jurassic Park & World" to listOf("jurassic", "dinosaurs"),
        "🔪 Classic Slashers" to listOf("saw", "conjuring", "annabelle", "nun", "insidious", "friday the 13th", "nightmare on elm street", "halloween"),
        "🦠 Zombies & Apocalypse" to listOf("zombie", "apocalypse", "resident evil", "walking dead", "world war z"),
        "🕵️ James Bond" to listOf("james bond", "007", "skyfall", "casino royale", "no time to die"),
        "📈 Business, Money & Success" to listOf("business", "wall street", "money", "rich", "ceo", "company", "invest", "stock", "bank", "empire", "founder"),
        "⏳ Time Travel Adventures" to listOf("time travel", "time machine", "time loop", "back to the future"),
        "🧛 Vampires & Werewolves" to listOf("vampire", "werewolf", "dracula", "twilight", "underworld", "blade"),
        "👽 Alien & Space Invaders" to listOf("alien", "ufo", "extraterrestrial", "invasion"),
        "💰 Heists & Robberies" to listOf("heist", "robbery", "thief", "bank rob"),
        "🔫 Assassins & Hitmen" to listOf("assassin", "hitman", "contract killer"),
        "🏫 Coming of Age" to listOf("coming of age", "high school", "teenage"),
        "🎓 College & University" to listOf("college", "university", "campus"),
        "🎭 Best Comedies" to listOf("comedy", "funny", "hilarious"),
        "👻 Best Horror" to listOf("horror", "scary", "terrifying"),
        "🚀 Best Sci-Fi" to listOf("sci-fi", "science fiction", "scifi", "space"),
        "💖 Romantic Dramas" to listOf("romance", "romantic", "love story"),
        "🔪 Crime Thrillers" to listOf("crime", "thriller", "mafia", "gangster"),
        "🍃 Studio Ghibli Magic" to listOf("studio ghibli", "ghibli", "miyazaki")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (bypassResult == null || bypassResult?.cookie.isNullOrBlank()) {
            bypassResult = bypass(mainUrl)
        }

        val rows = ArrayList<HomePageList>()
        val allRecords = mutableListOf<Triple<String, String, CatalogRecord>>()

        for (o in otts) {
            NetflixMirrorStorage.getAll(o.code).forEach { (id, rec) ->
                if (rec.n.isNotEmpty()) allRecords.add(Triple(o.code, id, rec))
            }
        }

        // 1. Recently Added
        val recent = allRecords.sortedByDescending { it.third.ts }.map { (ott, id, rec) -> card(ott, id, rec.n) }
        if (recent.size >= 5) rows.add(HomePageList("🆕 Recently Added (${recent.size})", recent))

        // 2. Indian Cinema / Bollywood (Smart Filter)
        val hollywoodKeywords = listOf("spider", "batman", "avengers", "marvel", "dc ", "justice league", "superman", "jurassic", "star wars", "fast and furious", "hobbs", "transformers", "bond", "007", "godzilla", "kong", "matrix", "terminator", "alien", "predator")
        val indianContent = allRecords.filter { (_, _, rec) ->
            val hasIndianLang = rec.l.any { lang ->
                val lg = lang.lowercase()
                lg == "hindi" || lg == "tamil" || lg == "telugu" || lg == "malayalam" || lg == "kannada" || lg == "punjabi" || lg == "marathi" || lg == "bengali"
            }
            val hasEnglish = rec.l.any { it.equals("english", true) }
            val isHollywoodFranchise = hollywoodKeywords.any { kw -> rec.n.lowercase().contains(kw) }
            return@filter hasIndianLang && !hasEnglish && !isHollywoodFranchise
        }.map { (ott, id, rec) -> card(ott, id, rec.n) }
        if (indianContent.size >= 5) rows.add(HomePageList("🇮🇳 Indian Cinema / Bollywood (${indianContent.size})", indianContent))

        // 3. Anime in Hindi
        val animeHindi = allRecords.filter { (_, _, rec) ->
            rec.g.any { it.equals("anime", true) || it.equals("animation", true) } && rec.l.any { it.equals("hindi", true) }
        }.map { (ott, id, rec) -> card(ott, id, rec.n) }
        if (animeHindi.size >= 3) rows.add(HomePageList("🇮🇳 Anime in Hindi (${animeHindi.size})", animeHindi))

        // 4. Hotstar Specials
        val hotstarOriginals = allRecords.filter { (ott, _, _) -> ott == "hs" }.map { (ott, id, rec) -> card(ott, id, rec.n) }
        if (hotstarOriginals.size >= 5) rows.add(HomePageList("🟠 Hotstar Specials (${hotstarOriginals.size})", hotstarOriginals))

        // 5. AI Knowledge Base Curated Rows
        for ((rowName, keywords) in knowledgeBase) {
            val items = allRecords.filter { (_, _, rec) ->
                rec.n.isNotEmpty() && keywords.any { kw -> rec.n.lowercase().contains(kw) }
            }.map { (ott, id, rec) -> card(ott, id, rec.n) }

            if (items.size >= 2) {
                rows.add(HomePageList("$rowName (${items.size})", items))
            }
        }

        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (bypassResult == null || bypassResult?.cookie.isNullOrBlank()) {
            bypassResult = bypass(mainUrl)
        }
        val q = query.trim()

        val local = otts.flatMap { o ->
            NetflixMirrorStorage.getAll(o.code).entries
                .filter { it.value.n.isNotEmpty() && it.value.n.contains(q, ignoreCase = true) }
                .map { (id, rec) -> card(o.code, id, rec.n) }
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
                        this.posterUrl = posterUrl(o.code, r.id)
                        posterHeaders = mapOf("Referer" to "$mainUrl/home")
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
        val ottCode = ref.ott
        val path = when(ottCode) {
            "pv" -> "pv/"
            "hs", "dp" -> "hs/"
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
        val rawGenre = data.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val langs = languagesOf(data)
        val runTime = convertRuntimeToMinutes(data.runtime.toString())

        val people = ArrayList<ActorData>()
        data.cast?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.forEach { people.add(ActorData(Actor(it))) }
        data.director?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { people.add(ActorData(Actor(it), roleString = "Director")) }

        val chips = rawGenre + langs.map { "${flagFor(it)} $it" }

        // --- SMART LOCAL RECOMMENDATION ENGINE (50+ Items) ---
        val localRecs = ArrayList<SearchResponse>()
        val allRecords = mutableListOf<Triple<String, String, CatalogRecord>>()
        for (o in otts) {
            NetflixMirrorStorage.getAll(o.code).forEach { (recId, rec) ->
                if (recId != id && rec.n.isNotEmpty()) allRecords.add(Triple(o.code, recId, rec))
            }
        }

        val titleLower = title.lowercase()

        // Tier 1: Exact Franchise Match (from AI Knowledge Base)
        for ((_, keywords) in knowledgeBase) {
            if (keywords.any { kw -> titleLower.contains(kw) }) {
                val franchiseMatches = allRecords.filter { (_, _, rec) ->
                    val recTitleLower = rec.n.lowercase()
                    keywords.any { kw -> recTitleLower.contains(kw) }
                }.map { (ott, recId, rec) -> card(ott, recId, rec.n) }
                localRecs.addAll(franchiseMatches)
            }
        }

        // Tier 2: Title Keyword Match (Words longer than 4 chars)
        val stopWords = setOf("the", "a", "an", "and", "of", "in", "to", "is", "it", "for", "on", "with")
        val titleKeywords = title.split(" ").map { it.lowercase().replace(":", "").replace("-", "") }
            .filter { it.length > 4 && it !in stopWords }

        val tier2 = allRecords.filter { (_, _, rec) ->
            val recTitleLower = rec.n.lowercase()
            titleKeywords.any { kw -> recTitleLower.contains(kw) }
        }.map { (ott, recId, rec) -> card(ott, recId, rec.n) }
        localRecs.addAll(tier2)

        // Tier 3: Exact Genre Match
        if (localRecs.size < 50) {
            val tier3 = allRecords.filter { (_, _, rec) ->
                val recGenres = rec.g.map { it.lowercase() }
                rawGenre.any { g -> recGenres.contains(g.lowercase()) }
            }.map { (ott, recId, rec) -> card(ott, recId, rec.n) }
            localRecs.addAll(tier3)
        }

        // Deduplicate and limit to 50
        val finalRecs = localRecs.distinctBy { it.url }.take(50)
        val suggest = if (finalRecs.isNotEmpty()) finalRecs else data.suggest?.map { card(ottCode, it.id) }

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

        NetflixMirrorStorage.addRich(ottCode, id, if (isMovie) "m" else "s", rawGenre, title, data.year, langs)
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
            "hs", "dp" -> "hs/playlist.php"
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
