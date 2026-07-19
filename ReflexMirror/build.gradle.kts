// use an integer for version numbers
version = 9 // Bumped to 9 to force CloudStream to recognize the update

android {
    namespace = "com.reflex1337.reflexmirror" // CRITICAL: This must be here!
    buildFeatures {
        buildConfig = true
    }
}

// Do NOT add any implementation dependencies here! It breaks the .cs3 file.

cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

    description = "Netflix, PrimeVideo, Disney+ Hotstar Contents in Multiple Languages"
    authors = listOf("Reflex1337, NivinCNC")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    requiresResources = false

    iconUrl = "https://raw.githubusercontent.com/ravikantkrsingh108-dot/RvMirror/refs/heads/main/Logos/ReflexMirror-icon.png"
}
