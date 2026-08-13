package com.laddu100.hdghartv

import com.lagradost.cloudstream3.*

class HDGharCollectionProvider : BaseHDGharProvider() {
    override var name = "HDGhar Collections"
    override val mainPage = mainPageOf("collections" to "Collections")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncAndCrawl()
        val lists = mutableListOf<HomePageList>()
        val allRecords = HDGharTVStorage.getAll()
        
        // Group by each collection name inside the list
        val collections = mutableMapOf<String, MutableList<HDGharTVStorage.MediaRecord>>()
        allRecords.forEach { rec ->
            rec.collection.forEach { colName ->
                collections.getOrPut(colName) { mutableListOf() }.add(rec)
            }
        }

        if (collections.isEmpty()) {
            lists.add(HomePageList("No collections found yet.", emptyList(), isHorizontalImages = false))
            lists.add(HomePageList("Open a few movies in 'HDGhar Smart' to populate this catalog.", emptyList(), isHorizontalImages = false))
        } else {
            collections.entries.sortedByDescending { it.value.size }.forEach { (col, items) ->
                val mapped = items.map { it.toSearchResponse() }
                if (mapped.size >= 1) lists.add(HomePageList("🎞️ $col (${mapped.size})", mapped, isHorizontalImages = false))
            }
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return HDGharTVStorage.getAll().filter { it.collection.any { col -> col.contains(query, ignoreCase = true) } }.map { it.toSearchResponse() }
    }
}
