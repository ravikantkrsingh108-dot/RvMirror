package com.laddu100.hdghartv

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.newHomePageResponse

class HDGharNetworkProvider : BaseHDGharProvider() {
    override var name = "HDGhar Networks"
    override val mainPage = mainPageOf("networks" to "Networks")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncAndCrawl()
        val lists = mutableListOf<HomePageList>()
        val allRecords = HDGharTVStorage.getAll()
        
        val netBuckets = LinkedHashMap<String, MutableList<HDGharTVStorage.MediaRecord>>()
        allRecords.forEach { rec -> rec.networks.forEach { netBuckets.getOrPut(it) { mutableListOf() }.add(rec) } }
        netBuckets.entries.sortedByDescending { it.value.size }.forEach { (net, items) ->
            val mapped = items.map { it.toSearchResponse() }
            if (mapped.size >= 2) lists.add(HomePageList("📺 $net (${mapped.size})", mapped, isHorizontalImages = false))
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    // Search specifically for Network names
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return HDGharTVStorage.getAll().filter { it.networks.any { n -> n.contains(query, ignoreCase = true) } }.map { it.toSearchResponse() }
    }
}
