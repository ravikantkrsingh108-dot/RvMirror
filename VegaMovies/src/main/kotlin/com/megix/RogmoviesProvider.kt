package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import kotlinx.coroutines.runBlocking

class RogmoviesProvider : VegaMoviesProvider() {
    override var mainUrl = "https://rogmovies.vip"
    override var name = "Rogmovies"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
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
                    jsonObject.optString("rogmovies")
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Home",
        "$mainUrl/category/web-series/netflix/page/%d/" to "Netflix",
        "$mainUrl/category/web-series/amazon-prime-video/page/%d/" to "Amazon Prime",
        "$mainUrl/category/web-series/disney-plus-hotstar/page/%d/" to "Disney+ Hotstar",
        "$mainUrl/category/web-series/zee5/page/%d/" to "ZEE5",
        "$mainUrl/category/web-series/sonyliv/page/%d/" to "SonyLIV",
        "$mainUrl/category/web-series/jio-cinema/page/%d/" to "JioCinema",
        "$mainUrl/category/web-series/mx-original/page/%d/" to "MX Original",
        "$mainUrl/category/anime-series/page/%d/" to "Anime Series",
        "$mainUrl/category/korean-series/page/%d/" to "Korean Series",
        "$mainUrl/category/hindi-movies/page/%d/" to "Hindi Movies",
        "$mainUrl/category/south-hindi-dubbed/page/%d/" to "South Hindi Dubbed",
        "$mainUrl/category/dual-audio/page/%d/" to "Dual Audio",
        RANDOM_MOVIES to "🔀 Movies Shuffle",
        RANDOM_SERIES to "🔀 Series Shuffle"
    )
}
