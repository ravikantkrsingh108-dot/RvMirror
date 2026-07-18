// use an integer for version numbers
version = 4 // Bumped to 4 to force CloudStream to recognize the update

android {
    namespace = "com.reflex1337.reflexmirror"
    buildFeatures {
        buildConfig = true
    }
}

// Do NOT add any implementation dependencies here! It breaks the .cs3 file.

cloudstream {
    language = "en"
    description = "Netflix, PrimeVideo, Disney+ Hotstar Contents in Multiple Languages"
    authors = listOf("Reflex1337, NivinCNC")
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    requiresResources = false
    iconUrl = "https://raw.githubusercontent.com/ravikantkrsingh108-dot/RvMirror/refs/heads/main/Logos/ReflexMirror-icon.png"
}
