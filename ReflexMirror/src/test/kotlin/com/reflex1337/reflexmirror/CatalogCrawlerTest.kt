package com.reflex1337.reflexmirror

import com.reflex1337.reflexmirror.entities.PostData
import com.reflex1337.reflexmirror.entities.SearchData
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.APIHolder.unixTime
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Bulk id collector for the "Custom Catalog".
 *
 * Search is capped at ~50 results per query, so we can't enumerate the catalog
 * with search alone. Instead we crawl the SUGGESTION GRAPH:
 *
 *   seed ids  ->  GET /mobile/post.php?id=<id>  ->  read its `suggest` ids
 *             ->  queue any id we haven't seen  ->  repeat until nothing new.
 *
 * Each post only returns a handful of suggestions (the server fixes that count —
 * there's no param to raise it), but unioning every visited title's suggestions
 * snowballs across the whole connected catalog and blows past the 50-cap.
 *
 * Run it (Netflix catalog):
 *   ./gradlew :ReflexMirror:testDebugUnitTest --tests "com.reflex1337.reflexmirror.CatalogCrawlerTest"
 *
 * Output: `ReflexMirror/discovered_ids.kt` — a ready-to-paste `listOf(...)` block
 * you drop straight into CustomCatalogIds.kt.
 */
class CatalogCrawlerTest {

    private val mainUrl = "https://net11.cc"

    // Which catalog to crawl. Netflix: ott="nf", pathPrefix="".
    // Prime Video: ott="pv", pathPrefix="pv/".  (Custom Catalog uses nf.)
    private val ott = "nf"
    private val pathPrefix = ""

    // --- tuning knobs ---
    private val MAX_IDS = 8000                 // safety cap so the crawl always terminates
    private val BATCH = 25                     // parallel post.php requests per round
    private val SEED_TERMS =
        ('a'..'z').map { it.toString() } + ('0'..'9').map { it.toString() }

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
        "Cache-Control" to "max-age=0",
        "Connection" to "keep-alive",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 Safari/537.36 /OS.Gatu v3.0",
        "X-Requested-With" to "XMLHttpRequest"
    )

    @Test
    fun crawlAllIds() = runBlocking {
        val cookieValue = bypass(mainUrl)
        val cookies = mapOf(
            "t_hash_t" to cookieValue,
            "hd" to "on",
            "ott" to ott
        )

        val discovered = LinkedHashSet<String>()
        val frontier = ArrayDeque<String>()

        fun enqueue(id: String?) {
            val clean = id?.trim().orEmpty()
            if (clean.isNotEmpty() && discovered.add(clean)) frontier.add(clean)
        }

        // 1) Seed from the home page (data-post on each card).
        runCatching {
            val doc = app.get(
                "$mainUrl/mobile/home?app=1",
                cookies = cookies,
                headers = headers,
                referer = "$mainUrl/mobile/home?app=1"
            ).document
            doc.select("article, .top10-post").forEach { el ->
                enqueue(el.selectFirst("a")?.attr("data-post"))
                enqueue(el.attr("data-post"))
            }
        }.onFailure { println("home seed failed: ${it.message}") }

        // 2) Seed from search across single-character terms (max coverage of entry points).
        for (term in SEED_TERMS) {
            runCatching {
                val data = app.get(
                    "$mainUrl/mobile/${pathPrefix}search.php?s=$term&t=${APIHolder.unixTime}",
                    referer = "$mainUrl/home",
                    cookies = cookies
                ).parsed<SearchData>()
                data.searchResult.forEach { enqueue(it.id) }
            }.onFailure { println("search seed '$term' failed: ${it.message}") }
        }

        println("Seeded ${discovered.size} ids; starting suggestion-graph crawl…")

        // 3) BFS over the suggestion graph.
        var rounds = 0
        while (frontier.isNotEmpty() && discovered.size < MAX_IDS) {
            val batch = ArrayList<String>(BATCH)
            while (frontier.isNotEmpty() && batch.size < BATCH) batch.add(frontier.removeFirst())

            val suggested = batch.amap { id ->
                runCatching {
                    app.get(
                        "$mainUrl/mobile/${pathPrefix}post.php?id=$id&t=${APIHolder.unixTime}",
                        headers,
                        referer = "$mainUrl/home",
                        cookies = cookies
                    ).parsed<PostData>().suggest?.mapNotNull { it.id } ?: emptyList()
                }.getOrDefault(emptyList())
            }.flatten()

            suggested.forEach { if (discovered.size < MAX_IDS) enqueue(it) }

            if (++rounds % 5 == 0) {
                println("round=$rounds discovered=${discovered.size} frontier=${frontier.size}")
            }
        }

        // 4) Emit a ready-to-paste block.
        val ids = discovered.toList()
        val snippet = buildString {
            appendLine("// Auto-generated by CatalogCrawlerTest — ${ids.size} ids (ott=$ott)")
            appendLine("val ids: List<String> = listOf(")
            ids.forEach { appendLine("    \"$it\",") }
            appendLine(")")
        }
        val out = File("discovered_ids.kt")
        out.writeText(snippet)

        println("DONE: discovered ${ids.size} ids")
        println("Wrote paste-ready block to: ${out.absolutePath}")
        if (discovered.size >= MAX_IDS) {
            println("NOTE: hit MAX_IDS=$MAX_IDS cap with frontier still ${frontier.size} — raise MAX_IDS for a fuller crawl.")
        }
    }
}
