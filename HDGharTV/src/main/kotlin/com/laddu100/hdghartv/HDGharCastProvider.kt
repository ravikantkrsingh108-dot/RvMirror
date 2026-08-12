package com.laddu100.hdghartv

import com.lagradost.cloudstream3.*

class HDGharCastProvider : BaseHDGharProvider() {
    override var name = "HDGhar Cast"
    override val mainPage = mainPageOf("cast" to "Cast")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncAndCrawl()
        val lists = mutableListOf<HomePageList>()
        val allRecords = HDGharTVStorage.getAll()
        
        val castBuckets = LinkedHashMap<String, MutableList<HDGharTVStorage.MediaRecord>>()
        allRecords.forEach { rec -> rec.cast.forEach { castBuckets.getOrPut(it.name) { mutableListOf() }.add(rec) } }
        
        castBuckets.entries.sortedByDescending { it.value.size }.take(100).forEach { (cast, items) ->
            val mapped = items.map { it.toSearchResponse() }
            if (mapped.size >= 2) lists.add(HomePageList("👤 $cast (${mapped.size})", mapped, isHorizontalImages = false))
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return HDGharTVStorage.getAll().filter { it.cast.any { c -> c.name.contains(query, ignoreCase = true) } }.map { it.toSearchResponse() }
    }
}
