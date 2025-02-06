package com.twittervideoscraper.scrapers

import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.JavaNetCookieJar
import java.io.IOException
import com.twittervideoscraper.jsonutils.parseJson
import com.twittervideoscraper.jsonutils.JsonWrapper
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder


class TwitterVideoScraper {
    private var client: OkHttpClient
    private val headers: Headers
    private val cookieManager: CookieManager
    private val twRegex = """https?://(?:(?:www|m(?:obile)?)\.)?(?:twitter\.com|x\.com)/(?:(?:i/web|[^/]+)/status|statuses)/(\d+)(?:/(?:video|photo)/(\d+))?""".toRegex()

    private val variablesTwPost: Map<String, Any> = mapOf(
            "with_rux_injections" to false,
            "includePromotedContent" to true,
            "withCommunity" to true,
            "withQuickPromoteEligibilityTweetFields" to true,
            "withBirdwatchNotes" to true,
            "withDownvotePerspective" to false,
            "withReactionsMetadata" to false,
            "withReactionsPerspective" to false,
            "withVoice" to true,
            "withV2Timeline" to true
        )

    private val featuresTwPost: Map<String, Boolean> = mapOf(
            "responsive_web_graphql_exclude_directive_enabled" to true,
            "verified_phone_label_enabled" to false,
            "responsive_web_graphql_timeline_navigation_enabled" to true,
            "responsive_web_graphql_skip_user_profile_image_extensions_enabled" to false,
            "tweetypie_unmention_optimization_enabled" to true,
            "vibe_api_enabled" to false,
            "responsive_web_edit_tweet_api_enabled" to false,
            "graphql_is_translatable_rweb_tweet_is_translatable_enabled" to false,
            "view_counts_everywhere_api_enabled" to true,
            "longform_notetweets_consumption_enabled" to true,
            "tweet_awards_web_tipping_enabled" to false,
            "freedom_of_speech_not_reach_fetch_enabled" to false,
            "standardized_nudges_misinfo" to false,
            "tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled" to false,
            "interactive_text_enabled" to false,
            "responsive_web_twitter_blue_verified_badge_is_enabled" to true,
            "responsive_web_text_conversations_enabled" to false,
            "longform_notetweets_richtext_consumption_enabled" to false,
            "responsive_web_enhance_cards_enabled" to false,
            "longform_notetweets_inline_media_enabled" to true,
            "longform_notetweets_rich_text_read_enabled" to true,
            "responsive_web_media_download_video_enabled" to true,
            "responsive_web_twitter_article_tweet_consumption_enabled" to true,
            "creator_subscriptions_tweet_preview_api_enabled" to true
        )

    init {
        cookieManager = CookieManager().apply {
            setCookiePolicy(CookiePolicy.ACCEPT_ALL)
        }
        val cookieJar = JavaNetCookieJar(cookieManager)

        client = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .build()

        headers = Headers.Builder()
            .add("authorization", "Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs=1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA")
            .add("User-Agent", "Twitterbot/1.0")
            .build()
    }

    fun setProxy(protocol: String, ip: String, port: Int) {
        val proxyType: Proxy.Type = when (protocol.lowercase()) {
            "http", "https" -> Proxy.Type.HTTP
            "socks4", "socks5" -> Proxy.Type.SOCKS
            else -> throw IllegalArgumentException("Unsupported proxy protocol: $protocol")
        }

        val proxyAddress = InetSocketAddress.createUnresolved(ip, port)
        val proxy = Proxy(proxyType, proxyAddress)

        client = client.newBuilder()
            .proxy(proxy)
            .build()

        println("Proxy set to $protocol://$ip:$port")
    }


    fun getRestIdFromTwUrl(twPostUrl: String): String {
        return try {
            twRegex.find(twPostUrl)?.groupValues?.get(1)
                ?: throw IllegalStateException("Post id not found")
        } catch (e: Exception) {
            throw IllegalStateException("Error getting rest ID", e)
        }
    }

    fun getGuestToken() {
        val guestTokenEndpoint = "https://api.x.com/1.1/guest/activate.json"
        
        try {

            val emptyBody = ByteArray(0).toRequestBody(null)

            val request = Request.Builder()
                .url(guestTokenEndpoint)
                .headers(headers)
                .post(emptyBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: throw IllegalStateException("Empty response body")
            
            val igPostData = parseJson(responseBody.toString())
            val guestToken = igPostData["guest_token"].asString()

            val cookie = HttpCookie("gt", guestToken).apply {
                domain = ".x.com"
                path = "/"
            }

            cookieManager.cookieStore.add(URI("https://x.com"), cookie)
            //println("Cookie 'gt' set with value: $guestToken")
                
            //printCookies()

            } catch (e: Exception) {
                e.printStackTrace()
                throw IllegalStateException("Error getting guest token", e)
            }
    }

    fun printCookies() {
        val cookies = cookieManager.cookieStore.cookies
        if (cookies.isEmpty()) {
            println("No cookies found.")
        } else {
            println("Cookies:")
            cookies.forEach { cookie ->
                println("Name: ${cookie.name}, Value: ${cookie.value}, Domain: ${cookie.domain}, Path: ${cookie.path}")
            }
        }
    }

    /*
    * This method gets post details and extracts video/s URL with best bitrate, m3u8 excluded
    * @param restId The Twitter post ID
    * @return Triple containing list of video URLs, list of thumbnails, and boolean indicating if content is NSFW
    */
    fun getVideoUrlByIdGraphQL(restId: String): Triple<List<String>, List<String>, Boolean> {

        val guestToken = cookieManager.cookieStore.cookies
            .firstOrNull { it.name == "gt" && it.domain == ".x.com" }?.value
            ?: throw IllegalStateException("Guest token not found")

        val updatedVariables = variablesTwPost.toMutableMap().apply {
            put("tweetId", restId)
        }

        val variablesJson = buildJsonWithSpaces(updatedVariables)
        val featuresJson = buildJsonWithSpaces(featuresTwPost)

        val encodedVariables = URLEncoder.encode(variablesJson, "UTF-8").replace("+", "%20")
        val encodedFeatures = URLEncoder.encode(featuresJson, "UTF-8").replace("+", "%20")

        val baseUrl = "https://x.com/i/api/graphql/2ICDjqPd81tulZcYrtpTuQ/TweetResultByRestId"
        val finalUrl = "$baseUrl?variables=$encodedVariables&features=$encodedFeatures"

        val request = Request.Builder()
            .url(finalUrl)
            .addHeader("authorization", "Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs=1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA")
            .addHeader("User-Agent", " ")
            .addHeader("cache-control", "no-cache")
            .addHeader("x-guest-token", guestToken)
            .get()
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string()
            throw IOException("Error ${response.code}: $errorBody")
        }

        val responseBody = response.body?.string() 
            ?: throw IOException("Empty response body")

        val jsonResponse = parseJson(responseBody)

        try{
            val nsfwReason = jsonResponse["data"]["tweetResult"]["result"]["reason"].asString()
            if (nsfwReason == "NsfwLoggedOut") {
                println("NSFW")
                return Triple(emptyList(), emptyList(), true)
            }
        }catch(e: Exception){
            
        }

        val mediaEntries = jsonResponse["data"]["tweetResult"]["result"]["legacy"]["entities"]["media"]
            ?: throw IllegalStateException("Media data not found")

        val videoUrls = mutableListOf<String>()
        val thumbnails = mutableListOf<String>()

        for ( media in mediaEntries ){
            thumbnails.add(media["media_url_https"].asString())
            val variants = media["video_info"]["variants"] ?: throw IllegalStateException("Media data not found")
            // getting the last url, in general is the best quality
            var variantUrl = ""
            for( variant in variants ){
                if ( variant["content_type"].asString() == "video/mp4" ){
                    variantUrl = variant["url"].asString()
                }
            }
            videoUrls.add(variantUrl)
        }

        //println(thumbnails)
        //println(videoUrls)

        return Triple(videoUrls, thumbnails, false)
    }

    private fun buildJsonWithSpaces(map: Map<String, Any>): String {
        val json = StringBuilder("{")
        map.entries.forEachIndexed { index, (key, value) ->
            json.append("\"$key\": ")
            when (value) {
                is String -> json.append("\"$value\"")
                is Boolean -> json.append(if (value) "true" else "false")
                else -> json.append(value)
            }
            if (index < map.size - 1) json.append(", ")
        }
        json.append("}")
        return json.toString()
    }

    fun getContentLengths(urls: List<String>): List<Long> {
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .build()
        
        val headers = Headers.headersOf(
            "User-Agent", "Twitterbot/1.0",
            "Accept-Encoding", "identity"
        )
        
        return urls.map { url ->
            var result: Long = -1
            var attempts = 0
            
            while (result == -1L && attempts < 50) {
                attempts++
                
                listOf("HEAD", "GET_RANGE", "GET").forEach { method ->
                    if (result != -1L) return@forEach
                    
                    try {
                        val request = when (method) {
                            "HEAD" -> Request.Builder().url(url).head()
                            "GET_RANGE" -> Request.Builder().url(url).addHeader("Range", "bytes=0-0")
                            else -> Request.Builder().url(url)
                        }.headers(headers).build()
                        
                        client.newCall(request).execute().use { response ->
                            response.header("Content-Length")?.toLongOrNull()?.let {
                                result = it/1024/1024
                                return@use
                            }

                            if (method == "GET_RANGE") {
                                response.header("Content-Range")
                                    ?.substringAfterLast("/")
                                    ?.toLongOrNull()
                                    ?.let {
                                        result = it/1024/1024
                                        return@use
                                    }
                            }
                        }
                    } catch (e: Exception) {
                        println("URL: ${url.take(50)}... | retries $attempts ($method) fail: ${e.javaClass.simpleName}")
                    }
                }
                
                if (result == -1L) Thread.sleep(1000)
            }
            
            result
        }

    }

}
