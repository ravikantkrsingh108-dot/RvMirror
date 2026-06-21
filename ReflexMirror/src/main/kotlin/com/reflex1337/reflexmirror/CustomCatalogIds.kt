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
 * The catalog paginates automatically (PAGE_SIZE per page in
 * CustomCatalogProvider), so this list can be as long as you like and
 * every id will show up as a card.
 *
 * Both movies and series work here — the provider detects the type when
 * the title is opened, exactly like the Netflix provider does.
 */
object CustomCatalogIds {
    val ids: List<String> = listOf(
        // --- add your ids below, one per line ---
        // "81234567",
        // "80192098",
        // "70143836",
    )
}
