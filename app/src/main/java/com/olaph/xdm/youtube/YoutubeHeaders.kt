package com.olaph.xdm.youtube

import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Headers for fetching a googlevideo stream. A signed URL is tied to the client that asked for it (`c=ANDROID`,
 * `c=IOS`, `c=WEB`); the CDN answers 403 when the User-Agent belongs to a different one, so it is picked from the URL.
 */
object YoutubeHeaders {
    private const val BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    fun forStream(url: String): Map<String, String> {
        val ua = runCatching {
            when {
                YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(null)
                YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(null)
                else -> null
            }
        }.getOrNull()
        return if (ua != null) {
            mapOf("User-Agent" to ua)
        } else {
            mapOf("User-Agent" to BROWSER_UA, "Origin" to "https://www.youtube.com", "Referer" to "https://www.youtube.com/")
        }
    }
}
