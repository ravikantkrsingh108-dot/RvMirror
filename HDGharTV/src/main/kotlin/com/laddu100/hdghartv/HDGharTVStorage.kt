package com.laddu100.hdghartv

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

object HDGharTVStorage {
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        this.prefs = context.getSharedPreferences("HDGharTVPrefs", Context.MODE_PRIVATE)
    }

    // Stores complete media info locally
    data class MediaRecord(
        val id: String,
        val type: String, // "movie" or "series"
        val title: String,
        val overview: String = "",
        val posterPath: String = "",
        val backdropPath: String = "",
        val releaseDate: String = "",
        val firstAirDate: String = "",
        val genres: List<String> = emptyList(),
        val categories: List<String> = emptyList(),
        val networks: List<String> = emptyList(),
        val studios: List<String> = emptyList(),
        val collection: List<String> = emptyList(),
        val originalLanguage: String = "",
        val spokenLanguages: List<String> = emptyList(),
        val voteAverage: Double = 0.0,
        val viewCount: Int = 0,
        val popularity: Double = 0.0,
        val runtime: Int = 0,
        val status: String = "",
        val certification: String = "",
        val cast: List<CastMember> = emptyList(),
        val ts: Long = System.currentTimeMillis() 
    )

    data class CastMember(
        val name: String,
        val character: String,
        val profilePath: String?
    )

    private data class Store(val items: MutableMap<String, MediaRecord> = mutableMapOf())

    private var cache: MutableMap<String, MediaRecord> = mutableMapOf()
    private var initialized = false

    @Synchronized
    private fun load() {
        if (initialized) return
        val json = if (::prefs.isInitialized) prefs.getString("hdghartv_catalog", null) else null
        cache = if (json.isNullOrBlank()) {
            mutableMapOf()
        } else {
            try {
                parseJson<Store>(json).items
            } catch (e: Exception) {
                mutableMapOf()
            }
        }
        initialized = true
    }

    @Synchronized
    private fun persist() {
        if (!::prefs.isInitialized) return
        prefs.edit().putString("hdghartv_catalog", Store(cache).toJson()).apply()
    }

    @Synchronized
    fun addRich(record: MediaRecord) {
        load()
        cache[record.id] = record
        persist()
    }

    @Synchronized
    fun addRichBatch(records: List<MediaRecord>) {
        if (records.isEmpty()) return
        load()
        records.forEach { cache[it.id] = it }
        persist()
    }

    @Synchronized
    fun getAll(): List<MediaRecord> {
        load()
        return cache.values.toList()
    }

    // Crawler state persistence (remembers which page it left off at)
    fun getCrawlerState(): Pair<Int, Int> {
        val mPage = if (::prefs.isInitialized) prefs.getInt("crawler_movie_page", 1) else 1
        val sPage = if (::prefs.isInitialized) prefs.getInt("crawler_series_page", 1) else 1
        return Pair(mPage, sPage)
    }

    fun saveCrawlerState(moviePage: Int, seriesPage: Int) {
        if (!::prefs.isInitialized) return
        prefs.edit().putInt("crawler_movie_page", moviePage).putInt("crawler_series_page", seriesPage).apply()
    }
}

// Top-level extension functions
fun HDGharTVStorage.MediaRecord.getRegion(): String {
    if (categories.isNotEmpty()) return categories.first()
    val langs = spokenLanguages.joinToString(",").lowercase()
    return when {
        langs.contains("hindi") -> "Bollywood"
        langs.contains("korean") -> "Korean"
        langs.contains("japanese") || genres.any { it.equals("Anime", true) } -> "Anime"
        langs.contains("chinese") || langs.contains("mandarin") -> "Chinese"
        else -> "Hollywood"
    }
}

fun HDGharTVStorage.MediaRecord.getYear(): String {
    return (releaseDate.ifEmpty { firstAirDate }).substringBefore("-")
}
