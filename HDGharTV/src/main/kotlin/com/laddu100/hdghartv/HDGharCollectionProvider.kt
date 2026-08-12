package com.laddu100.hdghartv

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.newHomePageResponse

class HDGharCollectionProvider : BaseHDGharProvider() {
    override var name = "HDGhar Collections"
    override val mainPage = mainPageOf("collections" to "Collections")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncAndCrawl()
        val lists = mutableListOf<HomePageList>()
        val allRecords = HDGharTVStorage.getAll()
        
        val collections = allRecords.filter { it.collection.isNotBlank() }.groupBy { it.collection }
        collections.entries.sortedByDescending { it.value.size }.forEach { (col, items) ->
            val mapped = items.map { it.toSearchResponse() }
            if (mapped.size >= 2) lists.add(HomePageList("🎞️ $col (${mapped.size})", mapped, isHorizontalImages = false))
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    // Search specifically for Collection names
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return HDGharTVStorage.getAll().filter { it.collection.contains(query, ignoreCase = true) }.map { it.toSearchResponse() }
    }
}
