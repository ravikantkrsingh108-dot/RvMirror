package com.reflex1337.reflexmirror

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import kotlin.reflect.KClass
import okhttp3.FormBody
import kotlinx.coroutines.delay
import android.content.Context
import com.lagradost.api.Log
import org.json.JSONObject
import java.util.UUID
import okhttp3.Request
import java.util.Base64

val JSONParser = object : ResponseParser {
    val mapper: ObjectMapper = jacksonObjectMapper().configure(
        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false
    ).configure(
        JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true
    )

    override fun <T : Any> parse(text: String, kClass: KClass<T>): T {
        return mapper.readValue(text, kClass.java)
    }

    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? {
        return try {
            mapper.readValue(text, kClass.java)
        } catch (e: Exception) {
            null
        }
    }

    override fun writeValueAsString(obj: Any): String {
        return mapper.writeValueAsString(obj)
    }
}

val app = Requests(responseParser = JSONParser).apply {
    defaultHeaders = mapOf("User-Agent" to USER_AGENT)
}

inline fun <reified T : Any> parseJson(text: String): T {
    return JSONParser.parse(text, T::class)
}

inline fun <reified T : Any> tryParseJson(text: String): T? {
    return try {
        return JSONParser.parseSafe(text, T::class)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun convertRuntimeToMinutes(runtime: String): Int {
    var totalMinutes = 0
    val parts = runtime.split(" ")
    for (part in parts) {
        when {
            part.endsWith("h") -> {
                val hours = part.removeSuffix("h").trim().toIntOrNull() ?: 0
                totalMinutes += hours * 60
            }
            part.endsWith("m") -> {
                val minutes = part.removeSuffix("m").trim().toIntOrNull() ?: 0
                totalMinutes += minutes
            }
        }
    }
    return totalMinutes
}

val BROWSER_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
    "Accept-Language" to "en-IN,en-US;q=0.9,en;q=0.8",
    "Connection" to "keep-alive",
    "sec-ch-ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Android WebView\";v=\"144\"",
    "sec-ch-ua-mobile" to "?0",
    "sec-ch-ua-platform" to "\"Android\"",
    "Sec-Fetch-Dest" to "document",
    "Sec-Fetch-Mode" to "navigate",
    "Sec-Fetch-Site" to "same-origin",
    "Sec-Fetch-User" to "?1",
    "Upgrade-Insecure-Requests" to "1",
    "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 Safari/537.36 /OS.Gatu v3.0",
    "X-Requested-With" to "XMLHttpRequest"
)

data class BypassResult(val cookie: String, val addhash: String, val usertoken: String, val dataTime: String)

@Volatile var cachedBypass: BypassResult? = null
@Volatile var cachedBypassTime: Long = 0L

suspend fun bypass(mainUrl: String): BypassResult {
    val cached = cachedBypass
    if (cached != null && cached.cookie.isNotEmpty() && System.currentTimeMillis() - cachedBypassTime < 54_000_000) {
        return cached
    }

    // Step 1: GET homepage to get cookie and check for ad wall
    val homeResp = app.get(
        "$mainUrl/mobile/home?app=1",
        headers = BROWSER_HEADERS,
        referer = "$mainUrl/mobile/home?app=1"
    )
    var cookie = ""
    homeResp.okhttpResponse.headers("Set-Cookie").forEach { h ->
        if (h.contains("t_hash_t=")) {
            cookie = h.substringAfter("t_hash_t=").substringBefore(";")
        }
    }
    if (cookie.isEmpty()) {
        cookie = homeResp.cookies["t_hash_t"] ?: ""
    }

    // Fallback to verify.php if homepage didn't give cookie
    if (cookie.isEmpty()) {
        try {
            val client = app.baseClient.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val formBody = FormBody.Builder()
                .add("g-recaptcha-response", UUID.randomUUID().toString())
                .build()
            val request = Request.Builder()
                .url("$mainUrl/verify.php")
                .post(formBody)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36")
                .header("Referer", "$mainUrl/verify2")
                .header("Origin", mainUrl)
                .build()
            val response = client.newCall(request).execute()
            response.headers("Set-Cookie").forEach { h ->
                if (h.contains("t_hash_t=")) {
                    cookie = h.substringAfter("t_hash_t=").substringBefore(";")
                }
            }
            response.close()
        } catch (_: Exception) {}
    }

    if (cookie.isEmpty()) return BypassResult("", "", "", "")

    val doc = homeResp.document
    val html = doc.html()

    // Check if there's NO ad wall
    if (!html.contains("We Need Support") || !html.contains("open-support")) {
        val dataTime = doc.selectFirst("body")?.attr("data-time") ?: ""
        val result = BypassResult(cookie, "", "", dataTime)
        cachedBypass = result
        cachedBypassTime = System.currentTimeMillis()
        NetflixMirrorStorage.saveCookie(cookie)
        return result
    }

    // Ad wall exists - extract addhash
    val addhash = doc.selectFirst("body")?.attr("data-addhash") ?: ""
    val dataTime = doc.selectFirst("body")?.attr("data-time") ?: ""

    if (addhash.isBlank()) {
        val result = BypassResult(cookie, "", "", dataTime)
        cachedBypass = result
        cachedBypassTime = System.currentTimeMillis()
        return result
    }

    // Extract Qury and Vsite2 from page JavaScript
    val qury = Regex("""Qury\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: "ffr455"
    val vsite = Regex("""Vsite2\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1) ?: "userver"

    // Simulate ad click
    val adClickUrl = "https://$vsite.net52.cc/?$qury=$addhash&a=y&t=${Math.random()}"
    try {
        app.get(adClickUrl, headers = BROWSER_HEADERS, referer = "$mainUrl/mobile/home?app=1")
    } catch (_: Exception) {}

    // Wait for ad to "complete" (25 seconds)
    kotlinx.coroutines.delay(25000L)

    // Step 4: POST to verify2.php with addhash to confirm ad was watched
    var usertoken = ""
    var finalCookie = cookie
    for (attempt in 1..10) {
        kotlinx.coroutines.delay(2000L)
        try {
            val verifyResp = app.post(
                "$mainUrl/mobile/verify2.php",
                data = mapOf("verify" to addhash),
                headers = BROWSER_HEADERS,
                referer = "$mainUrl/mobile/home?app=1"
            )
            val newCookie = verifyResp.cookies["t_hash_t"]
            if (!newCookie.isNullOrBlank()) finalCookie = newCookie

            val body = verifyResp.text
            val json = tryParseJson<Map<String, String>>(body)
            if (json != null) {
                val status = json["statusup"] ?: ""
                if (status.equals("All Done", ignoreCase = true)) {
                    usertoken = json["usertoken"] ?: json["token"] ?: json["utoken"] ?: json["user_token"] ?: ""
                    break
                }
            }
        } catch (_: Exception) {}
    }

    val result = BypassResult(finalCookie, addhash, usertoken, dataTime)
    cachedBypass = result
    cachedBypassTime = System.currentTimeMillis()
    NetflixMirrorStorage.saveCookie(finalCookie)
    return result
}

val newTvBaseHeaders = mapOf(
    "Cache-Control" to "no-cache, no-store, must-revalidate",
    "Pragma" to "no-cache",
    "Expires" to "0",
    "X-Requested-With" to "NetmirrorNewTV v1.0",
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0 /OS.GatuNewTV v1.0",
    "Accept" to "application/json, text/plain, */*"
)

val newTvDomains = listOf(
    "aHR0cHM6Ly9tb2JpbGVkZXRlY3RzLmNvbQ==",
    "aHR0cHM6Ly9tb2JpbGVkZXRlY3QuYXBw",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmFydA==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNj",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmNsaWNr",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lmluaw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LmxpdmU=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnBybw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNob3A=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNpdGU=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnNwYWNl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnN0b3Jl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0LnZpcA==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lndpa2k=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0Lnh5eg==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5hcnQ=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5jYw==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbmZv",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5pbms=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5saXZl",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5wcm8=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy5zdG9yZQ==",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy50b3A=",
    "aHR0cHM6Ly9tb2JpZGV0ZWN0cy54eXo="
)

fun decodeBase64(value: String): String {
    return String(Base64.getDecoder().decode(value))
}

private var resolvedApiUrl: String = ""

suspend fun resolveApiUrl(): String {
    if (resolvedApiUrl.isNotBlank()) return resolvedApiUrl
    for (encoded in newTvDomains) {
        val base = decodeBase64(encoded).trimEnd('/')
        try {
            val response = app.get("$base/checknewtv.php", headers = newTvBaseHeaders)
                .parsed<NewTvTokenResponse>()
            val tokenHash = response.token_hash
            if (!tokenHash.isNullOrBlank()) {
                resolvedApiUrl = decodeBase64(tokenHash).trimEnd('/')
                return resolvedApiUrl
            }
        } catch (_: Exception) {
            // Try next domain.
        }
    }
    throw Exception("Failed to resolve NewTV API base URL")
}

fun buildNewTvHeaders(ott: String, extra: Map<String, String> = emptyMap()): Map<String, String> {
    val result = newTvBaseHeaders.toMutableMap()
    result["Ott"] = ott
    extra.forEach { (key, value) ->
        result[key] = value
    }
    return result
}

data class NewTvTokenResponse(
    val token_hash: String? = null
)

data class NewTvPlayerResponse(
    val status: String? = null,
    val video_link: String? = null,
    val referer: String? = null
)

// --- PLAYLIST FETCHING LOGIC ---

data class PlaylistResult(val sources: List<Source>, val tracks: List<Tracks>?)
data class PlayListItem(val sources: List<Source>? = null, val tracks: List<Tracks>? = null)
data class Source(val file: String? = null, val label: String? = null, val type: String? = null)
data class Tracks(val file: String? = null, val kind: String? = null, val label: String? = null)

suspend fun getPlaylistLink(mainUrl: String, bypass: BypassResult?, id: String, ott: String, playlistPath: String): PlaylistResult? {
    if (bypass == null || bypass.cookie.isEmpty()) return null

    val cookies = mutableMapOf(
        "t_hash_t" to bypass.cookie,
        "hd" to "on",
        "ott" to ott
    )
    if (bypass.addhash.isNotEmpty()) cookies["addhash"] = bypass.addhash
    if (bypass.usertoken.isNotEmpty()) cookies["usertoken"] = bypass.usertoken

    val response = app.get(
        "$mainUrl/mobile/$playlistPath?id=$id",
        headers = BROWSER_HEADERS,
        referer = "$mainUrl/home",
        cookies = cookies
    ).text

    // Try JSON array format: [{"sources":[...],"tracks":[...]}]
    try {
        val playlist = tryParseJson<List<PlayListItem>>(response)
        val item = playlist?.firstOrNull()
        if (item != null && !item.sources.isNullOrEmpty()) {
            return PlaylistResult(item.sources, item.tracks)
        }
    } catch (_: Exception) {}

    // Try single object
    try {
        val item = tryParseJson<PlayListItem>(response)
        if (item != null && !item.sources.isNullOrEmpty()) {
            return PlaylistResult(item.sources, item.tracks)
        }
    } catch (_: Exception) {}

    // Regex fallback for m3u8
    val m3u8 = Regex("""(/mobile/hls/[^\s"']+\.m3u8[^\s"']*)""").find(response)?.groupValues?.get(1)
    if (!m3u8.isNullOrBlank()) {
        return PlaylistResult(listOf(Source("$mainUrl$m3u8", "Auto", "m3u8")), null)
    }

    val fullUrl = Regex("""(https?://[^\s"'<>\}\]\\]+\.m3u8[^\s"'<>\}\]\\]*)""").find(response)?.groupValues?.get(1)
    if (!fullUrl.isNullOrBlank()) {
        return PlaylistResult(listOf(Source(fullUrl, "Auto", "m3u8")), null)
    }

    return null
}
