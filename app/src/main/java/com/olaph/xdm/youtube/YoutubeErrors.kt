package com.olaph.xdm.youtube

import kotlinx.coroutines.CancellationException
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.UnsupportedContentInCountryException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import java.io.IOException

enum class YtErrorKind(val message: String) {
    AGE_RESTRICTED("This video is age-restricted. YouTube only shows it to signed-in accounts, and XDM can't sign in."),
    PRIVATE("This video is private."),
    UNAVAILABLE("This video is unavailable. It may have been removed or made private by the uploader."),
    MEMBERS_ONLY("This video is for channel members only."),
    PAID("This is a paid video (rental or purchase) and can't be downloaded."),
    LIVE("This is a live broadcast (or a premiere that hasn't ended). Live streams aren't supported; try again once it has finished."),
    GEO_BLOCKED("This video isn't available in your country."),
    BOT_CHECK("YouTube asked to confirm you're not a bot. Open YouTube in your browser, finish the check, and try again later."),
    EXTRACTOR_CHANGED("YouTube changed something; XDM needs an update."),
    NETWORK("Couldn't reach YouTube. Check your connection and try again."),
    FORMAT_GONE("YouTube no longer offers the quality you picked for this video. Add the link again to choose another."),
    TERMINATED("The channel that published this video has been closed."),
    MUSIC_PREMIUM("This is YouTube Music Premium content and can't be downloaded."),
    EXPIRED("YouTube keeps refusing the download (error 403), even with a fresh link. Try again later or on another network."),
    OTHER("YouTube couldn't provide this video."),
}

/**
 * Anything that goes wrong while talking to YouTube, in words for the user. The message is fixed text per [kind]; it
 * never contains titles, ids or URLs, so it is safe in crash reports as well.
 */
class YoutubeException(val kind: YtErrorKind, cause: Throwable? = null) : Exception(kind.message, cause)

object YoutubeErrors {
    /**
     * Turns whatever the extractor (or the network, or Rhino running YouTube's player script) threw into a
     * [YoutubeException]. Nothing the library does may crash the app, so this takes every [Throwable] except cancellation.
     */
    fun from(t: Throwable): YoutubeException {
        if (t is CancellationException) throw t
        if (t is YoutubeException) return t
        val chain = generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(8).toList()
        val text = chain.mapNotNull { it.message }.joinToString(" | ").lowercase()
        val kind = when {
            chain.any { it is AgeRestrictedContentException } -> YtErrorKind.AGE_RESTRICTED
            chain.any { it is PrivateContentException } -> YtErrorKind.PRIVATE
            chain.any { it is SignInConfirmNotBotException } -> YtErrorKind.BOT_CHECK
            chain.any { it is ReCaptchaException } -> YtErrorKind.BOT_CHECK
            chain.any { it is GeographicRestrictionException || it is UnsupportedContentInCountryException } -> YtErrorKind.GEO_BLOCKED
            chain.any { it is YoutubeMusicPremiumContentException } -> YtErrorKind.MUSIC_PREMIUM
            chain.any { it is AccountTerminatedException } -> YtErrorKind.TERMINATED
            chain.any { it is PaidContentException } ->
                if ("members" in text) YtErrorKind.MEMBERS_ONLY else YtErrorKind.PAID
            chain.any { it is ContentNotAvailableException } -> classifyUnavailable(text)
            // Network trouble: a plain IOException from the downloader, or one underneath an extraction error.
            chain.any { isNetwork(it) } -> YtErrorKind.NETWORK
            else -> YtErrorKind.EXTRACTOR_CHANGED
        }
        return YoutubeException(kind, t)
    }

    /** `ContentNotAvailableException` carries YouTube's own reason text; a few of those reasons deserve their own message. */
    private fun classifyUnavailable(text: String): YtErrorKind = when {
        "age" in text && ("confirm" in text || "inappropriate" in text) -> YtErrorKind.AGE_RESTRICTED
        "members" in text -> YtErrorKind.MEMBERS_ONLY
        "live event" in text || "premiere" in text || "will begin" in text || "live stream" in text -> YtErrorKind.LIVE
        "country" in text -> YtErrorKind.GEO_BLOCKED
        "private" in text -> YtErrorKind.PRIVATE
        else -> YtErrorKind.UNAVAILABLE
    }

    private fun isNetwork(t: Throwable): Boolean = t is IOException ||
        t is java.net.UnknownHostException || t is java.net.SocketException || t is javax.net.ssl.SSLException
}
