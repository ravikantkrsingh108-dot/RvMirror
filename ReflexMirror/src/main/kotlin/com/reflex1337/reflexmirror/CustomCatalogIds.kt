package com.reflex1337.reflexmirror

/**
 * Editable id list for the "Custom Catalog" provider.
 *
 * Each entry is a Netflix-mirror post id — the SAME id that appears as
 * `data-post` on the home page and is used in:
 *   - poster:  https://imgcdn.kim/poster/v/<id>.jpg
 *   - details: /mobile/post.php?id=<id>
 *
 * How to add more movie / series cards:
 *   1. Open any title in the "Netflix" provider and note its id, OR
 *      search on the mirror site and grab the id from the URL / data-post.
 *   2. Add the id as a new "..." entry in the list below.
 *
 * These ids are folded into the Netflix "More" row of the Custom Catalog as
 * not-yet-categorized titles. Once a title is opened, its type (movie/series)
 * and genres are detected and it moves into the proper grouped/genre rows.
 *
 * Most of the catalog fills itself as users browse Netflix/Prime/Hotstar — this
 * list is just an optional manual seed for Netflix ids.
 */
object CustomCatalogIds {
    val ids: List<String> = listOf(
        // --- add your ids below, one per line ---
        // "81234567",
        // "80192098",
        // "70143836",
    )
}
