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

/**
 * "All NetMirror" — aggregates Netflix / Prime Video / Hotstar into grouped,
 * genre-tagged rows, driven by ids collected while browsing those providers.
 *
 * Three switchable sections (tabs):
 *   - Catalog      : <Source> Movies / Series / More + genre rows
 *   - By Language  : one row per language
 *   - By Year      : one row per release year (newest first)
 *
 * A background pass enriches each saved id with type + genres + title + year +
 * languages so it lands in the right rows. Row headers show live counts.
 */
class CustomCatalogProvider : MainAPI() {
    companion object {
        var context: Context? = null
        private const val MIN_GENRE_SIZE = 1
        private const val ENRICH_CONCURRENCY = 10
        private const val PERSIST_EVERY = 100
    }

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )
    override var lang = "en"
    override var mainUrl = "https://net11.cc"
    override var name = "All NetMirror"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "all" to "Catalog",
        "lang" to "By Language",
        "year" to "By Year"
    )

    private var cookie_value = ""
    private val enrichScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var enriching = false

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "sec-ch-ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Android WebView\";v=\"144\"",
        "sec-ch-ua-mobile" to "?0",
        "sec-ch-ua-platform" to "\"Android\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 Safari/537.36 /OS.Gatu v3.0",
        "X-Requested-With" to "XMLHttpRequest"
    )

    private data class Ott(
        val code: String,
        val label: String,
        val path: String,
        val poster: String,
        val backdrop: String,
        val epDir: String
    )

    private val otts = listOf(
        Ott("nf", "Netflix", "", "poster/v", "poster/v", "epimg"),
        Ott("pv", "Prime Video", "pv/", "pv/v", "pv/h", "pvepimg"),
        Ott("hs", "Hotstar", "hs/", "hs/v", "hs/h", "hsepimg")
    )

    private fun ottOf(code: String): Ott = otts.firstOrNull { it.code == code } ?: otts[0]

    private fun cookies(ott: String) = mapOf(
        "t_hash_t" to cookie_value,
        "hd" to "on",
        "ott" to ott
    )

    private fun posterUrl(o: Ott, id: String) = "https://imgcdn.kim/${o.poster}/$id.jpg"

    private fun card(o: Ott, id: String, title: String = ""): SearchResponse =
        newAnimeSearchResponse(title, Ref(id, o.code).toJson()) {
            this.posterUrl = posterUrl(o, id)
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
        }

    /** Records across all sources, with the manual seed folded into Netflix. */
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
        kickEnrichment()
        val rows = when (request.data) {
            "lang" -> languageRows()
            "year" -> yearRows()
            else -> catalogRows()
        }
        return newHomePageResponse(rows, false)
    }

    private fun catalogRows(): List<HomePageList> {
        val rows = ArrayList<HomePageList>()
        val genreBuckets = LinkedHashMap<String, MutableList<SearchResponse>>()

        for (o in otts) {
            val m = NetflixMirrorStorage.getAll(o.code).toMutableMap()
            if (o.code == "nf") {
                CustomCatalogIds.ids.forEach { id ->
                    if (id.isNotBlank() && !m.containsKey(id)) m[id] = CatalogRecord()
                }
            }
            if (m.isEmpty()) continue

            val movies = ArrayList<SearchResponse>()
            val series = ArrayList<SearchResponse>()
            val more = ArrayList<SearchResponse>()

            m.forEach { (id, rec) ->
                val c = card(o, id, rec.n)
                when (rec.t) {
                    "m" -> movies.add(c)
                    "s" -> series.add(c)
                    else -> more.add(c)
                }
                rec.g.forEach { genre -> genreBuckets.getOrPut(genre) { ArrayList() }.add(c) }
            }

            if (movies.isNotEmpty()) rows.add(HomePageList("${o.label} Movies (${movies.size})", movies.shuffled()))
            if (series.isNotEmpty()) rows.add(HomePageList("${o.label} Series (${series.size})", series.shuffled()))
            if (more.isNotEmpty()) rows.add(HomePageList("${o.label} • More (${more.size})", more.shuffled()))
        }

        genreBuckets.entries
            .filter { it.value.size >= MIN_GENRE_SIZE }
            .sortedByDescending { it.value.size }
            .forEach { (genre, items) -> rows.add(HomePageList("$genre (${items.size})", items.shuffled())) }

        return rows
    }

    private fun languageRows(): List<HomePageList> {
        val byLang = LinkedHashMap<String, MutableList<SearchResponse>>()
        allRecords().forEach { (o, id, rec) ->
            rec.l.forEach { lang -> byLang.getOrPut(lang) { ArrayList() }.add(card(o, id, rec.n)) }
        }
        return byLang.entries
            .sortedByDescending { it.value.size }
            .map { (lang, items) -> HomePageList("${flagFor(lang)} $lang (${items.size})", items.shuffled()) }
    }

    private fun yearRows(): List<HomePageList> {
        val byYear = LinkedHashMap<String, MutableList<SearchResponse>>()
        allRecords().forEach { (o, id, rec) ->
            rec.y.takeIf { it.isNotBlank() }?.let { y -> byYear.getOrPut(y) { ArrayList() }.add(card(o, id, rec.n)) }
        }
        return byYear.entries
            .sortedByDescending { it.key.toIntOrNull() ?: 0 }
            .map { (year, items) -> HomePageList("$year (${items.size})", items.shuffled()) }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        cookie_value = if (cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val q = query.trim()

        // 1) Local saved content — no 50 cap.
        val local = otts.flatMap { o ->
            NetflixMirrorStorage.getAll(o.code).entries
                .filter { it.value.n.isNotEmpty() && it.value.n.contains(q, ignoreCase = true) }
                .map { (id, rec) -> card(o, id, rec.n) }
        }

        // 2) Live server search across every source (and feed the catalog).
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
                        this.posterUrl = posterUrl(o, r.id)
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
        cookie_value = if (cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val ref = parseJson<Ref>(url)
        val o = ottOf(ref.ott)
        val id = ref.id

        val data = app.get(
            "$mainUrl/mobile/${o.path}post.php?id=$id&t=${APIHolder.unixTime}",
            headers,
            referer = "$mainUrl/home",
            cookies = cookies(o.code)
        ).parsed<PostData>()

        val episodes = arrayListOf<Episode>()
        val title = data.title
        val genre = data.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val langs = languagesOf(data)
        val runTime = convertRuntimeToMinutes(data.runtime.toString())

        // Director shows in the cast row with a role label; languages become chips.
        val people = ArrayList<ActorData>()
        data.cast?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.forEach { people.add(ActorData(Actor(it))) }
        data.director?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { people.add(ActorData(Actor(it), roleString = "Director")) }

        val chips = genre + langs.map { "${flagFor(it)} $it" }

        val suggest = data.suggest?.map { card(o, it.id) }

        val isMovie = data.episodes.first() == null
        if (isMovie) {
            episodes.add(newEpisode(LoadData(title, id, o.code)) { name = data.title })
        } else {
            data.episodes.filterNotNull().mapTo(episodes) {
                newEpisode(LoadData(title, it.id, o.code)) {
                    this.name = it.t
                    this.episode = it.ep.replace("E", "").toIntOrNull()
                    this.season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/${o.epDir}/${it.id}.jpg"
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

        // Feed the catalog with full metadata + suggestions.
        NetflixMirrorStorage.addRich(o.code, id, if (isMovie) "m" else "s", genre, title, data.year, langs)
        NetflixMirrorStorage.addBareIds(o.code, data.suggest?.mapNotNull { it.id } ?: emptyList())

        val type = if (isMovie) TvType.Movie else TvType.TvSeries
        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = "https://imgcdn.kim/${o.poster}/$id.jpg"
            backgroundPosterUrl = "https://imgcdn.kim/${o.backdrop}/$id.jpg"
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

    /** Parse "8.1", "IMDb 8.1" or "57% match" into a 0–10 score. */
    private fun parseScore(match: String?): Score? {
        if (match.isNullOrBlank()) return null
        val num = Regex("""\d+(\.\d+)?""").find(match)?.value?.toDoubleOrNull() ?: return null
        val tenScale = if (match.contains("%")) num / 10.0 else num
        return Score.from10(tenScale.toString())
    }

    /** Language names from the source, which may be a String, an array, or array of {l,s} objects. */
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

    /** Best-effort flag for a spoken language; falls back to a globe. */
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

    private fun kickEnrichment() {
        if (enriching) return
        enriching = true
        enrichScope.launch {
            try {
                if (cookie_value.isEmpty()) cookie_value = bypass(mainUrl)
                for (o in otts) {
                    val bare = NetflixMirrorStorage.getAll(o.code)
                        .filterValues { it.t == "?" }
                        .keys.toList()
                    if (bare.isEmpty()) continue

                    val buffer = HashMap<String, CatalogRecord>()
                    for (chunk in bare.chunked(ENRICH_CONCURRENCY)) {
                        chunk.amap { id -> id to fetchRecord(o, id) }
                            .forEach { (id, rec) -> if (rec != null) buffer[id] = rec }
                        if (buffer.size >= PERSIST_EVERY) {
                            NetflixMirrorStorage.addRichBatch(o.code, HashMap(buffer))
                            buffer.clear()
                        }
                    }
                    if (buffer.isNotEmpty()) NetflixMirrorStorage.addRichBatch(o.code, buffer)
                }
            } catch (_: Exception) {
            } finally {
                enriching = false
            }
        }
    }

    private suspend fun fetchRecord(o: Ott, id: String): CatalogRecord? = try {
        val data = app.get(
            "$mainUrl/mobile/${o.path}post.php?id=$id&t=${APIHolder.unixTime}",
            headers,
            referer = "$mainUrl/home",
            cookies = cookies(o.code)
        ).parsed<PostData>()
        val type = if (data.episodes.first() == null) "m" else "s"
        val genres = data.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        CatalogRecord(type, genres, data.title.trim(), data.year.trim(), languagesOf(data))
    } catch (e: Exception) {
        null
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
                    name = it.t
                    episode = it.ep.replace("E", "").toIntOrNull()
                    season = it.s.replace("S", "").toIntOrNull()
                    this.posterUrl = "https://imgcdn.kim/${o.epDir}/${it.id}.jpg"
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
        val apiBase = resolveApiUrl()
        val ld = parseJson<LoadData>(data)
        val response = app.get(
            "$apiBase/newtv/player.php?id=${ld.id}",
            headers = buildNewTvHeaders(ld.ott, mapOf("Usertoken" to ""))
        ).parsed<NewTvPlayerResponse>()

        if (response.status != "ok" || response.video_link.isNullOrBlank()) return false

        callback.invoke(
            newExtractorLink(name, name, response.video_link, type = ExtractorLinkType.M3U8) {
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
                if (request.url.toString().contains(".m3u8")) {
                    val newRequest = request.newBuilder()
                        .header("Cookie", "hd=on")
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
