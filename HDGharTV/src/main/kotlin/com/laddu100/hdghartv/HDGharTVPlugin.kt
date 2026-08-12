package com.laddu100.hdghartv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDGharTVPlugin : Plugin() {
    override fun load(context: Context) {
        HDGharTVStorage.init(context.applicationContext) // Initialize local storage
        registerMainAPI(HDGharTVProvider())
        registerMainAPI(HDGharTVSmartProvider()) // Register the new smart catalog
    }
}
