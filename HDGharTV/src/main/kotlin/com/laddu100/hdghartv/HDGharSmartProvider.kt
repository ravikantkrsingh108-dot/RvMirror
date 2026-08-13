package com.laddu100.hdghartv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson // Added this import

class HDGharSmartProvider : BaseHDGharProvider() {
    override var name = "HDGhar Smart"
    override val mainPage = mainPageOf("movies" to "All Movies", "series" to "All Series", "smart" to "Smart Catalog")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = mutableListOf<HomePageList>()
        val base = apiBase()
        var hasNext = false
        val limit = 20

        try {
            when (request.data) {
                "movies" -> {
                    val res = app.get("$base/api/movies/public?page=$page&limit=$limit", headers = browserHeaders, referer = "$base/")
                    val parsed = tryParseJson<ApiMediaListResponse>(res.text)
                    
                    if (parsed != null) {
                        val items = parsed.data?.mapNotNull { it.toSearchResponse("movie") } ?: emptyList()
                        if (items.isNotEmpty()) lists.add(HomePageList("All Movies (${parsed.total ?: items.size})", items, isHorizontalImages = false))
                        val totalPages = parsed.totalPages ?: if (parsed.total != null) (parsed.total + limit - 1) / limit else 1
                        hasNext = page < totalPages
                    } else {
                        if (page == 1) lists.add(HomePageList("Error loading Movies. The API might be blocked or down.", emptyList(), isHorizontalImages = false))
                    }
                }
                "series" -> {
                    val res = app.get("$base/api/series/public?page=$page&limit=$limit", headers = browserHeaders, referer = "$base/")
                    val parsed = tryParseJson<ApiMediaListResponse>(res.text)
                    
                    if (parsed != null) {
                        val items = parsed.data?.mapNotNull { it.toSearchResponse("series") } ?: emptyList()
                        if (items.isNotEmpty()) lists.add(HomePageList("All Series (${parsed.total ?: items.size})", items, isHorizontalImages = false))
                        val totalPages = parsed.totalPages ?: if (parsed.total != null) (parsed.total + limit - 1) / limit else 1
                        hasNext = page < totalPages
                    } else {
                        if (page == 1) lists.add(HomePageList("Error loading Series. The API might be blocked or down.", emptyList(), isHorizontalImages = false))
                    }
                }
                "smart" -> {
                    syncAndCrawl()
                    val allRecords = HDGharTVStorage.getAll()
                    
                    if (allRecords.isEmpty()) {
                        lists.add(HomePageList("Smart Catalog is empty. Crawler failed to fetch data. Try again later.", emptyList(), isHorizontalImages = false))
                    } else {
                        val popMovies = allRecords.filter { it.type == "movie" }.sortedByDescending { it.voteAverage }.map { it.toSearchResponse() }
                        if (popMovies.isNotEmpty()) lists.add(HomePageList("🏆 Most Popular Movies (${popMovies.size})", popMovies, isHorizontalImages = false))
                        
                        val popSeries = allRecords.filter { it.type == "series" }.sortedByDescending { it.voteAverage }.map { it.toSearchResponse() }
                        if (popSeries.isNotEmpty()) lists.add(HomePageList("🏆 Most Popular Series (${popSeries.size})", popSeries, isHorizontalImages = false))

                        val viewed = allRecords.filter { it.viewCount > 0 }.sortedByDescending { it.viewCount }.map { it.toSearchResponse() }
                        if (viewed.isNotEmpty()) lists.add(HomePageList("👁️ Most Viewed (${viewed.size})", viewed, isHorizontalImages = false))

                        val catBuckets = LinkedHashMap<String, MutableList<HDGharTVStorage.MediaRecord>>()
                        allRecords.forEach { rec -> rec.categories.forEach { catBuckets.getOrPut(it) { mutableListOf() }.add(rec) } }
                        catBuckets.entries.sortedByDescending { it.value.size }.forEach { (cat, items) ->
                            val mapped = items.map { it.toSearchResponse() }
                            if (mapped.size >= 2) lists.add(HomePageList("🏷️ $cat (${mapped.size})", mapped, isHorizontalImages = false))
                        }

                        val genreBuckets = LinkedHashMap<String, MutableList<HDGharTVStorage.MediaRecord>>()
                        allRecords.forEach { rec -> rec.genres.forEach { genreBuckets.getOrPut(it) { mutableListOf() }.add(rec) } }
                        genreBuckets.entries.sortedByDescending { it.value.size }.forEach { (genre, items) ->
                            val mapped = items.map { it.toSearchResponse() }
                            if (mapped.size >= 2) lists.add(HomePageList("🎭 $genre (${mapped.size})", mapped, isHorizontalImages = false))
                        }
                    }
                    hasNext = false
                }
            }
        } catch (e: Exception) {
            if (lists.isEmpty()) lists.add(HomePageList("Network Error: ${e.message}", emptyList(), isHorizontalImages = false))
        }
        return newHomePageResponse(lists, hasNext = hasNext)
    }

    private fun ApiMediaItem.toSearchResponse(type: String): SearchResponse? {
        val id = id ?: return null
        val title = title ?: originalTitle ?: return null
        val loadData = LoadData(id = id, type = type, title = title, posterUrl = posterPath)
        return newMovieSearchResponse(title, loadData.toJson(), if (type == "series") TvType.TvSeries else TvType.Movie) { this.posterUrl = posterPath }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return HDGharTVStorage.getAll().filter { it.title.contains(query, ignoreCase = true) }.map { it.toSearchResponse() }
    }
}
