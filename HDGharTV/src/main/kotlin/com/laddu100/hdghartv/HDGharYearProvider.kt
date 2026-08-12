package com.laddu100.hdghartv

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.newHomePageResponse

class HDGharYearProvider : BaseHDGharProvider() {
    override var name = "HDGhar Years"
    override val mainPage = mainPageOf("years" to "Years")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        syncAndCrawl()
        val lists = mutableListOf<HomePageList>()
        val allRecords = HDGharTVStorage.getAll()
        
        val years = allRecords.map { it.getYear() }.filter { it.isNotBlank() }.distinct().sortedDescending()
        years.forEach { year ->
            val items = allRecords.filter { it.getYear() == year }.map { it.toSearchResponse() }
            if (items.size >= 2) lists.add(HomePageList("📅 $year (${items.size})", items, isHorizontalImages = false))
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    // Search specifically for Years
    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return HDGharTVStorage.getAll().filter { it.getYear().contains(query) }.map { it.toSearchResponse() }
    }
}
