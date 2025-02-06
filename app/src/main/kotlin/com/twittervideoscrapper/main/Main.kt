package com.twittervideoscraper.main

import okhttp3.*
import java.io.IOException

import com.twittervideoscraper.scrapers.TwitterVideoScraper


fun runTwitterVideoScraper(twitterUrl: String) {

    val twitterVideo = TwitterVideoScraper()
    
    val restId = twitterVideo.getRestIdFromTwUrl(twitterUrl)

    twitterVideo.getGuestToken()

    val (twitterVideoUrl, videoThumbnails, nsfw) = twitterVideo.getVideoUrlByIdGraphQL(restId)

    val fileSizes = twitterVideo.getContentLengths(twitterVideoUrl)

}

fun main() {
    // set ur tt url
    var twitterUrl = ""

    val maxRetries = 50
    var retries = 0
    var success = false

    do {
        try {
            runTwitterVideoScraper(twitterUrl)
            success = true
        } catch (e: Exception) {
            println("retries ${retries + 1} fail: $e")
            retries++
        }
    } while (retries < maxRetries && !success)

    if (!success) {
        println("Error with $maxRetries retries")
    }

}
