// use an integer for version numbers
version = 3

android {
    namespace = "com.reflex1337.reflexmirror" // <--- YOU MUST ADD THIS LINE
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
