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
 * "All NetMirror" — a single catalog that aggregates Netflix / Prime Video /
 * Hotstar content into grouped, genre-tagged rows. It is driven by ids collected
 * passively while browsing those providers (see NetflixMirrorStorage).
 *
 * Ids start "bare" (type/genres unknown). A background enrichment pass fetches
 * each one's type + genres + title so it lands in the right Movies/Series and
 * genre rows. No display caps — every collected title is shown.
 */
class CustomCatalogProvider : MainAPI() {
    companion object {
        var context: Context? = null
        private const val MIN_GENRE_SIZE = 1     // show a genre row even for a single title
        private const val ENRICH_CONCURRENCY = 10 // parallel post.php fetches per round
        private const val PERSIST_EVERY = 100     // flush to storage after this many enriched
    }

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )
    override var lang = "en"
    override var mainUrl = "https://net52.cc"
    override var name = "All NetMirror"
    override val hasMainPage = true

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Kick off background categorization of any not-yet-known ids.
        kickEnrichment()

        val rows = ArrayList<HomePageList>()
        val genreBuckets = LinkedHashMap<String, MutableList<SearchResponse>>()

        for (o in otts) {
            val all = NetflixMirrorStorage.getAll(o.code).toMutableMap()
            if (o.code == "nf") {
                CustomCatalogIds.ids.forEach { id ->
                    if (id.isNotBlank() && !all.containsKey(id)) all[id] = CatalogRecord()
                }
            }
            if (all.isEmpty()) continue

            val movies = ArrayList<SearchResponse>()
            val series = ArrayList<SearchResponse>()
            val more = ArrayList<SearchResponse>()

            all.forEach { (id, rec) ->
                val c = card(o, id, rec.n)
                when (rec.t) {
                    "m" -> movies.add(c)
                    "s" -> series.add(c)
                    else -> more.add(c)
                }
                rec.g.forEach { genre ->
                    genreBuckets.getOrPut(genre) { ArrayList() }.add(c)
                }
            }

            if (movies.isNotEmpty()) rows.add(HomePageList("${o.label} Movies", movies.shuffled()))
            if (series.isNotEmpty()) rows.add(HomePageList("${o.label} Series", series.shuffled()))
            if (more.isNotEmpty()) rows.add(HomePageList("${o.label} • More", more.shuffled()))
        }

        // Genre rows, aggregated across all sources, biggest buckets first — no cap.
        genreBuckets.entries
            .filter { it.value.size >= MIN_GENRE_SIZE }
            .sortedByDescending { it.value.size }
            .forEach { (genre, items) ->
                rows.add(HomePageList(genre, items.shuffled()))
            }

        return newHomePageResponse(rows, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        cookie_value = if (cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
        val q = query.trim()

        // 1) Local saved content — no 50 cap, matches everything we've categorized.
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

        // Merge, de-duplicating by payload (id + source); local first.
        val seen = HashSet<String>()
        val out = ArrayList<SearchResponse>()
        for (sr in local + live) {
            if (seen.add(sr.url)) out.add(sr)
        }
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
        val cast = (data.cast?.split(",")?.map { it.trim() } ?: emptyList()).map { ActorData(Actor(it)) }
        val genre = data.genre?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val rating = data.match?.replace("IMDb ", "")
        val runTime = convertRuntimeToMinutes(data.runtime.toString())

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

        // Feed the catalog: record this title (type + genres + name) and its suggestions.
        NetflixMirrorStorage.addRich(o.code, id, if (isMovie) "m" else "s", genre ?: emptyList(), title)
        NetflixMirrorStorage.addBareIds(o.code, data.suggest?.mapNotNull { it.id } ?: emptyList())

        val languages = formatLanguages(data.language, data.lang)
        val richPlot = buildInfoPlot(o, data, genre, rating, runTime, languages)

        val type = if (isMovie) TvType.Movie else TvType.TvSeries
        return newTvSeriesLoadResponse(title, url, type, episodes) {
            posterUrl = "https://imgcdn.kim/${o.poster}/$id.jpg"
            backgroundPosterUrl = "https://imgcdn.kim/${o.backdrop}/$id.jpg"
            posterHeaders = mapOf("Referer" to "$mainUrl/home")
            plot = richPlot
            year = data.year.toIntOrNull()
            tags = genre
            actors = cast
            this.score = Score.from10(rating)
            this.duration = runTime
            this.contentRating = data.ua
            this.recommendations = suggest
        }
    }

    /** Split a String/array language value into individual, trimmed names. */
    private fun languageList(value: Any?): List<String> = when (value) {
        is String -> value.split(",", "/", "|", "&").map { it.trim() }.filter { it.isNotEmpty() }
        is Collection<*> -> value.mapNotNull { it?.toString()?.trim()?.ifBlank { null } }
        else -> emptyList()
    }

    /** Best-effort flag for a (spoken) language. Falls back to a globe. */
    private fun flagFor(language: String): String = when (language.lowercase().trim()) {
        "hindi", "tamil", "telugu", "malayalam", "kannada", "bengali", "marathi",
        "punjabi", "gujarati", "bhojpuri", "urdu", "odia", "assamese" -> "🇮🇳" // 🇮🇳
        "english" -> "🇬🇧"        // 🇬🇧
        "korean" -> "🇰🇷"         // 🇰🇷
        "japanese" -> "🇯🇵"       // 🇯🇵
        "chinese", "mandarin", "cantonese" -> "🇨🇳" // 🇨🇳
        "spanish", "español" -> "🇪🇸"  // 🇪🇸
        "french" -> "🇫🇷"         // 🇫🇷
        "german" -> "🇩🇪"         // 🇩🇪
        "italian" -> "🇮🇹"        // 🇮🇹
        "portuguese" -> "🇵🇹"     // 🇵🇹
        "russian" -> "🇷🇺"        // 🇷🇺
        "arabic" -> "🇸🇦"         // 🇸🇦
        "thai" -> "🇹🇭"           // 🇹🇭
        "turkish" -> "🇹🇷"        // 🇹🇷
        "indonesian" -> "🇮🇩"     // 🇮🇩
        "filipino", "tagalog" -> "🇵🇭" // 🇵🇭
        "vietnamese" -> "🇻🇳"     // 🇻🇳
        else -> "🌐"                          // 🌐
    }

    /** Combine source language fields into a "🇮🇳 Hindi  🇺🇸 English" style string. */
    private fun formatLanguages(vararg values: Any?): String? {
        val langs = values.flatMap { languageList(it) }
            .map { it.replaceFirstChar { c -> c.uppercase() } }
            .distinct()
        if (langs.isEmpty()) return null
        return langs.joinToString("   ") { "${flagFor(it)} $it" }
    }

    /**
     * A clean, scannable detail-page header rendered above the synopsis:
     *
     *   📺 Netflix  ·  ⭐ 8.1  ·  🗓 2023  ·  ⏱ 124m  ·  🔞 16+
     *   🌐 🇮🇳 Hindi  🇬🇧 English
     *   🎭 Action · Thriller
     *   🎬 Christopher Nolan
     *   ──────────
     *   <synopsis>
     */
    private fun buildInfoPlot(
        o: Ott,
        data: PostData,
        genre: List<String>?,
        rating: String?,
        runTime: Int,
        languages: String?
    ): String? {
        val facts = mutableListOf("📺 ${o.label}")
        rating?.takeIf { it.isNotBlank() }?.let { facts.add("⭐ $it") }
        data.year.takeIf { it.isNotBlank() }?.let { facts.add("🗓 $it") }
        if (runTime > 0) facts.add("⏱ ${runTime}m")
        data.ua?.takeIf { it.isNotBlank() }?.let { facts.add("🔞 $it") }

        return buildString {
            appendLine(facts.joinToString("   ·   "))
            languages?.let { appendLine("🌐 $it") }
            if (!genre.isNullOrEmpty()) appendLine("🎭 ${genre.joinToString(" · ")}")
            data.director?.trim()?.takeIf { it.isNotEmpty() }?.let { appendLine("🎬 $it") }
            data.desc?.trim()?.takeIf { it.isNotEmpty() }?.let {
                appendLine("──────────")
                append(it)
            }
        }.trim().ifBlank { data.desc }
    }

    /**
     * Background pass that categorizes every still-unknown id (type + genres +
     * title) so it moves out of "• More" into the right grouped/genre rows.
     * Runs once at a time, resumes across sessions (progress is persisted).
     */
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
                // best-effort; will resume on the next home open
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
        CatalogRecord(type, genres, data.title.trim())
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
