// use an integer for version numbers
version = 6

android {
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

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
