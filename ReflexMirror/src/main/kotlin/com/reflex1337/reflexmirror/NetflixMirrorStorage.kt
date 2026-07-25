package com.reflex1337.reflexmirror

import android.content.Context
import android.content.SharedPreferences

object NetflixMirrorStorage {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        this.context = context.applicationContext
        this.prefs = context.getSharedPreferences("NetflixMirrorPrefs", Context.MODE_PRIVATE)
    }

    fun saveCookie(cookie: String) {
        val editor = prefs.edit()
        editor.putString("nf_cookie", cookie)
        editor.putLong("nf_cookie_timestamp", System.currentTimeMillis())
        editor.apply()
    }

    fun getCookie(): Pair<String?, Long> {
        return Pair(
            prefs.getString("nf_cookie", null),
            prefs.getLong("nf_cookie_timestamp", 0L)
        )
    }

    fun clearCookie() {
        val editor = prefs.edit()
        editor.remove("nf_cookie")
        editor.remove("nf_cookie_timestamp")
        editor.apply()
    }

    /**
     * Passive catalog collection. Per ott ("nf", "pv", "hs") we keep a map of
     * id -> {type, genres}. Bare ids (seen on home/suggestions) start as type "?"
     * and get upgraded to "m"/"s" with genres the first time a title is opened.
     * The Custom Catalog reads this to build grouped, genre-tagged rows.
     */
    private val cache = HashMap<String, MutableMap<String, CatalogRecord>>()

    @Synchronized
    private fun loadOtt(ott: String): MutableMap<String, CatalogRecord> {
        cache[ott]?.let { return it }
        val json = if (::prefs.isInitialized) prefs.getString("catalog_$ott", null) else null
        val map: MutableMap<String, CatalogRecord> = if (json.isNullOrBlank()) {
            mutableMapOf()
        } else {
            try {
                JSONParser.parse(json, CatalogStore::class).items.toMutableMap()
            } catch (e: Exception) {
                mutableMapOf()
            }
        }
        cache[ott] = map
        return map
    }

    @Synchronized
    private fun persist(ott: String) {
        if (!::prefs.isInitialized) return
        val map = cache[ott] ?: return
        prefs.edit().putString("catalog_$ott", JSONParser.writeValueAsString(CatalogStore(map))).apply()
    }

    /** Record ids of unknown type (home cards, suggestions). Never downgrades a rich record. */
    @Synchronized
    fun addBareIds(ott: String, ids: Collection<String>) {
        val map = loadOtt(ott)
        var changed = false
        for (raw in ids) {
            val id = raw.trim()
            if (id.isNotEmpty() && !map.containsKey(id)) {
                map[id] = CatalogRecord()
                changed = true
            }
        }
        if (changed) persist(ott)
    }

    /** Upsert a title's metadata. Empty fields keep any previously stored value (no clobber). */
    @Synchronized
    fun addRich(
        ott: String,
        id: String,
        type: String,
        genres: List<String>,
        title: String = "",
        year: String = "",
        languages: List<String> = emptyList()
    ) {
        val clean = id.trim()
        if (clean.isEmpty()) return
        val map = loadOtt(ott)
        val prev = map[clean]
        map[clean] = CatalogRecord(
            t = type,
            g = if (genres.isNotEmpty()) genres else (prev?.g ?: emptyList()),
            n = title.trim().ifEmpty { prev?.n ?: "" },
            y = year.trim().ifEmpty { prev?.y ?: "" },
            l = if (languages.isNotEmpty()) languages else (prev?.l ?: emptyList())
        )
        persist(ott)
    }

    /** Upsert many records at once, persisting only once (used by background enrichment). */
    @Synchronized
    fun addRichBatch(ott: String, records: Map<String, CatalogRecord>) {
        if (records.isEmpty()) return
        val map = loadOtt(ott)
        map.putAll(records)
        persist(ott)
    }

    @Synchronized
    fun getAll(ott: String): Map<String, CatalogRecord> = HashMap(loadOtt(ott))
}

data class CatalogRecord(
    val t: String = "?",        // "m" movie, "s" series, "?" unknown
    val g: List<String> = emptyList(),
    val n: String = "",         // title/name, empty until known
    val y: String = "",         // year, empty until known
    val l: List<String> = emptyList(),  // languages, empty until known
    val ts: Long = System.currentTimeMillis() // Timestamp for Recently Added
)

data class CatalogStore(
    val items: MutableMap<String, CatalogRecord> = mutableMapOf()
)
