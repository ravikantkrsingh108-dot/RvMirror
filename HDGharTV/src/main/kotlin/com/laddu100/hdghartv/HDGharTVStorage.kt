package com.laddu100.hdghartv

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.api.Log
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.io.File

object HDGharTVStorage {
    private const val TAG = "HDGharTVStorage"

    private const val PREFS_NAME = "HDGharTVPrefs"
    private const val KEY_CATALOG_LEGACY = "hdghartv_catalog"   // old catalog location (SharedPreferences)
    private const val KEY_INITIAL_SYNC_DONE = "initial_sync_done"
    private const val KEY_CRAWLER_MOVIE_PAGE = "crawler_movie_page"
    private const val KEY_CRAWLER_SERIES_PAGE = "crawler_series_page"
    private const val CATALOG_FILE_NAME = "hdghartv_catalog.json"

    private lateinit var appContext: Context
    private lateinit var prefs: SharedPreferences

    data class MediaRecord(
        val id: String,
        val type: String,
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
        val voteCount: Int = 0,          // FIX: votes and views are now tracked separately
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

    fun init(context: Context) {
        synchronized(this) {
            appContext = context.applicationContext
            prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun catalogFile(): File = File(appContext.filesDir, CATALOG_FILE_NAME)

    @Synchronized
    private fun load() {
        if (initialized) return
        cache = mutableMapOf()
        if (::appContext.isInitialized) {
            try {
                migrateLegacyCatalogIfNeeded()
                val file = catalogFile()
                if (file.exists()) cache = parseJson<Store>(file.readText()).items
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load catalog: ${e.message}")
                cache = mutableMapOf()
            }
        }
        initialized = true
    }

    /** One-time migration from the old SharedPreferences-based catalog to file storage. */
    private fun migrateLegacyCatalogIfNeeded() {
        val legacyJson = prefs.getString(KEY_CATALOG_LEGACY, null) ?: return
        try {
            val file = catalogFile()
            if (!file.exists()) file.writeText(legacyJson)
            prefs.edit().remove(KEY_CATALOG_LEGACY).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Legacy catalog migration failed: ${e.message}")
        }
    }

    @Synchronized
    private fun persist() {
        if (!::appContext.isInitialized) return
        try {
            // FIX: catalog lives in a file now (SharedPreferences re-parsed its entire XML
            // on every access and isn't meant for megabyte-sized blobs). Atomic via temp file + rename.
            val dir = appContext.filesDir
            val tmp = File(dir, "$CATALOG_FILE_NAME.tmp")
            tmp.writeText(Store(cache).toJson())
            val target = File(dir, CATALOG_FILE_NAME)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist catalog: ${e.message}")
        }
    }

    /** Equality ignoring the timestamp, so unchanged records don't trigger a disk write. */
    private fun MediaRecord.sameContentAs(other: MediaRecord): Boolean =
        copy(ts = 0L) == other.copy(ts = 0L)

    @Synchronized
    fun addRich(record: MediaRecord) {
        load()
        val existing = cache[record.id]
        if (existing != null && existing.sameContentAs(record)) return  // FIX: skip write when unchanged
        cache[record.id] = record
        persist()
    }

    @Synchronized
    fun addRichBatch(records: List<MediaRecord>) {
        if (records.isEmpty()) return
        load()
        var changed = false
        for (record in records) {
            val existing = cache[record.id]
            if (existing != null && existing.sameContentAs(record)) continue
            cache[record.id] = record
            changed = true
        }
        if (changed) persist()
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

    // --- Small state stays in SharedPreferences (cheap key/value reads) ---

    fun isInitialSyncDone(): Boolean =
        if (::prefs.isInitialized) prefs.getBoolean(KEY_INITIAL_SYNC_DONE, false) else false

    fun markInitialSyncDone() {
        if (!::prefs.isInitialized) return
        prefs.edit().putBoolean(KEY_INITIAL_SYNC_DONE, true).apply()
    }

    fun getCrawlerState(): Pair<Int, Int> {
        val mPage = if (::prefs.isInitialized) prefs.getInt(KEY_CRAWLER_MOVIE_PAGE, 1) else 1
        val sPage = if (::prefs.isInitialized) prefs.getInt(KEY_CRAWLER_SERIES_PAGE, 1) else 1
        return Pair(mPage, sPage)
    }

    fun saveCrawlerState(moviePage: Int, seriesPage: Int) {
        if (!::prefs.isInitialized) return
        prefs.edit().putInt(KEY_CRAWLER_MOVIE_PAGE, moviePage).putInt(KEY_CRAWLER_SERIES_PAGE, seriesPage).apply()
    }
}

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
