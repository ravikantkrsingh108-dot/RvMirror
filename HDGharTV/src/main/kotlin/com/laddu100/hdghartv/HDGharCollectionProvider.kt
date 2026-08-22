package com.laddu100.hdghartv

import com.lagradost.cloudstream3.*

class HDGharCollectionProvider : BaseHDGharProvider() {
    override var name = "HDGhar Collections"
    override val mainPage = mainPageOf("collections" to "Collections")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncAndCrawl()
        val lists = mutableListOf<HomePageList>()
        val allRecords = HDGharTVStorage.getAll()

        val collections = LinkedHashMap<String, MutableList<HDGharTVStorage.MediaRecord>>()
        allRecords.forEach { rec ->
            rec.collection.forEach { colName ->
                collections.getOrPut(colName) { mutableListOf() }.add(rec)
            }
        }

        if (collections.isEmpty()) {
            // FIX: accurate empty state — the catalog self-populates now, no need to
            // tell users to open items manually.
            lists.add(HomePageList("Catalog is loading... Try again in a moment.", emptyList(), isHorizontalImages = false))
        } else {
            collections.entries.sortedByDescending { it.value.size }.forEach { (col, items) ->
                val mapped = items.map { it.toSearchResponse() }
                lists.add(HomePageList("🎞️ $col (${mapped.size})", mapped, isHorizontalImages = false))
            }
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        // FIX: works on a fresh install now
        ensureCatalogReady()
        return HDGharTVStorage.getAll()
            .filter { it.collection.any { col -> col.contains(query, ignoreCase = true) } }
            .map { it.toSearchResponse() }
    }
}
