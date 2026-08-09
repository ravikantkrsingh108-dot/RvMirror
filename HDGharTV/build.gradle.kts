// use an integer for version numbers
version = 3 // Bumped to 2 to force CloudStream to recognize the fix

android {
    namespace = "com.laddu100.hdghartv" // CRITICAL: This must be here!
    buildFeatures {
        buildConfig = true
    }
}

// Do NOT add any implementation dependencies here! 
// CloudStream already provides Jackson and all network libraries.

cloudstream {
    language = "hi"
    description = "Watch Movies and Series on HDGharTV"
    authors = listOf("laddu100")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    requiresResources = false
    iconUrl = "https://hdghartv.cc/favicon.png"
}
