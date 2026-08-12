// HDGharTV/src/main/kotlin/com/laddu100/hdghartv/HDGharTVStorage.kt
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

    // 存储完整的媒体信息
    data class MediaRecord(
        val id: String,
        val type: String, // "movie" 或 "series"
        val title: String,
        val overview: String = "",
        val posterPath: String = "",
        val backdropPath: String = "",
        val releaseDate: String = "", // "2025-01-29"
        val firstAirDate: String = "",
        val genres: List<String> = emptyList(),
        val category: String = "", // "Chinese", "Hollywood", "Bollywood", "Korean", "Anime"
        val languages: List<String> = emptyList(),
        val voteAverage: Double = 0.0,
        val runtime: Int = 0,
        val status: String = "",
        val certification: String = "",
        val productionCompanies: List<String> = emptyList(),
        val cast: List<CastMember> = emptyList(),
        val ts: Long = System.currentTimeMillis() // 用于排序最近添加
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

    @Synchronized
    fun getById(id: String): MediaRecord? {
        load()
        return cache[id]
    }

    // 爬虫状态持久化（记住爬到了哪一页）
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

    // 智能分类函数
    fun MediaRecord.getRegion(): String {
        // 优先使用 category 字段
        if (category.isNotBlank()) {
            return when (category.lowercase()) {
                "chinese" -> "Chinese"
                "hollywood" -> "Hollywood"
                "bollywood" -> "Bollywood"
                "korean" -> "Korean"
                "anime" -> "Anime"
                else -> category
            }
        }
        
        // 根据 languages 判断
        val langs = languages.joinToString(",").lowercase()
        return when {
            langs.contains("hindi") -> "Bollywood"
            langs.contains("korean") -> "Korean"
            langs.contains("japanese") || genres.any { it.equals("Anime", true) } -> "Anime"
            langs.contains("chinese") || langs.contains("mandarin") -> "Chinese"
            else -> "Hollywood"
        }
    }

    fun MediaRecord.getYear(): String {
        return (releaseDate.ifEmpty { firstAirDate }).substringBefore("-")
    }
}
