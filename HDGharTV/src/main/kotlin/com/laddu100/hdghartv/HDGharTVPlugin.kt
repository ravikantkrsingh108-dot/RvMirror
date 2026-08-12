package com.laddu100.hdghartv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDGharTVPlugin : Plugin() {
    override fun load(context: Context) {
        HDGharTVStorage.init(context.applicationContext)
        registerMainAPI(HDGharTvSmartProvider())
        registerMainAPI(HDGharSmartProvider())
        registerMainAPI(HDGharCollectionProvider())
        registerMainAPI(HDGharNetworkProvider())
        registerMainAPI(HDGharYearProvider())
        registerMainAPI(HDGharCastProvider())
    }
}
