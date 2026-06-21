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
     * Passive catalog collection: every id a user sees while browsing is unioned
     * into a persistent set, keyed by ott ("nf", "pv", ...). The Custom Catalog
     * reads this back, so the catalog grows itself as the app is used.
     */
    @Synchronized
    fun addIds(ott: String, ids: Collection<String>) {
        if (!::prefs.isInitialized) return
        val cleaned = ids.mapNotNull { it.trim().ifBlank { null } }
        if (cleaned.isEmpty()) return
        val key = "ids_$ott"
        // getStringSet returns a shared instance that must not be mutated — copy it.
        val current = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
        val sizeBefore = current.size
        current.addAll(cleaned)
        if (current.size != sizeBefore) {
            prefs.edit().putStringSet(key, current).apply()
        }
    }

    @Synchronized
    fun getIds(ott: String): Set<String> {
        if (!::prefs.isInitialized) return emptySet()
        return prefs.getStringSet("ids_$ott", emptySet())?.toSet() ?: emptySet()
    }
}