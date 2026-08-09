package com.reflex1337.reflexmirror

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
open class reflexmirrorPlugin: Plugin() {
    override fun load(context: Context) {
        NetflixMirrorStorage.init(context.applicationContext)
        DisneyStudioProvider.context = context
        NetflixMirrorProvider.context = context
        PrimeVideoMirrorProvider.context = context
        HotStarMirrorProvider.context = context
        CustomCatalogProvider.context = context

        // Register core standard providers
        registerMainAPI(NetflixMirrorProvider())
        registerMainAPI(PrimeVideoMirrorProvider())
        registerMainAPI(HotStarMirrorProvider())

        // Custom, id-driven catalog (edit CustomCatalogIds to add cards)
        registerMainAPI(CustomCatalogProvider())
        registerMainAPI(ExperimentalCatalogProvider())
        registerMainAPI(ExperimentalCatalogProvider1())
        
        // Force-enable all 4 specialized sub-studios directly on startup
        //registerMainAPI(DisneyStudioProvider("disney", "Disney"))
        //registerMainAPI(DisneyStudioProvider("marvel", "Marvel"))
        //registerMainAPI(DisneyStudioProvider("starwars", "Star Wars"))
        //registerMainAPI(DisneyStudioProvider("pixar", "Pixar"))
    }
}
