package com.reflex1337.reflexmirror.entities

data class PostData(
    val desc: String?,
    val director: String?,
    val ua: String?,
    val episodes: List<Episode?>,
    val genre: String?,
    val nextPage: Int?,
    val nextPageSeason: String?,
    val nextPageShow: Int?,
    val season: List<Season>?,
    val title: String,
    val year: String,
    val cast: String?,
    val match: String?,
    val runtime: String?,
    var suggest: List<Suggest>?,
    // Best-effort: shown on the info page if the source returns either key.
    // Typed as Any? because the source may return a String or an array of strings.
    val lang: Any? = null,
    val language: Any? = null,
)
