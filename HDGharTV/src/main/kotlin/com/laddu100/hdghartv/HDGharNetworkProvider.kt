package com.laddu100.hdghartv

import com.lagradost.cloudstream3.*

class HDGharNetworkProvider : BaseHDGharProvider() {
    override var name = "HDGhar Networks"
    override val mainPage = mainPageOf("networks" to "Networks")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncAndCrawl()
        val lists = mutableListOf<HomePageList>()
        val allRecords = HDGharTVStorage.getAll()

        // FIX: empty state instead of a silently blank page while the catalog seeds
        if (allRecords.isEmpty()) {
            lists.add(HomePageList("Catalog is loading... Try again in a moment.", emptyList(), isHorizontalImages = false))
            return newHomePageResponse(lists, hasNext = false)
        }

        val netBuckets = LinkedHashMap<String, MutableList<HDGharTVStorage.MediaRecord>>()
        allRecords.forEach { rec -> rec.networks.forEach { netBuckets.getOrPut(it) { mutableListOf() }.add(rec) } }
        netBuckets.entries.sortedByDescending { it.value.size }.forEach { (net, items) ->
            val mapped = items.map { it.toSearchResponse() }
            if (mapped.size >= 2) lists.add(HomePageList("📺 $net (${mapped.size})", mapped, isHorizontalImages = false))
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        // FIX: works on a fresh install now
        ensureCatalogReady()
        return HDGharTVStorage.getAll()
            .filter { it.networks.any { n -> n.contains(query, ignoreCase = true) } }
            .map { it.toSearchResponse() }
    }
}
