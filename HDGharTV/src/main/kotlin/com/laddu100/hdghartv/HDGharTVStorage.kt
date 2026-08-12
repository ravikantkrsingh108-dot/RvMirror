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

    // Data class for storing rich metadata locally
    data class MediaRecord(
        val id: String,
        val type: String, // "movie" or "series"
        val title: String,
        val genres: List<String> = emptyList(),
        val year: String = "",
        val rating: Double = 0.0,
        val region: String = "", // Hollywood, Bollywood, Korean, Anime
        val ts: Long = System.currentTimeMillis()
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
        prefs.edit().putString("hdghartv_catalog", toJson(Store(cache))).apply()
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

    fun resetCrawler() {
        if (!::prefs.isInitialized) return
        prefs.edit().remove("crawler_movie_page").remove("crawler_series_page").apply()
    }
}
