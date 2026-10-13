package com.olaph.xdm.youtube

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ParsingException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class YoutubeErrorsTest {
    private fun kind(t: Throwable) = YoutubeErrors.from(t).kind

    @Test fun ageRestrictedNeedsASignIn() {
        assertEquals(YtErrorKind.AGE_RESTRICTED, kind(AgeRestrictedContentException("This age-restricted video cannot be watched anonymously")))
        assertEquals(
            YtErrorKind.AGE_RESTRICTED,
            kind(ContentNotAvailableException("Got error LOGIN_REQUIRED: \"Sign in to confirm your age. This video may be inappropriate for some users.\"")),
        )
        assertTrue(YoutubeErrors.from(AgeRestrictedContentException("x")).message!!.contains("age-restricted"))
    }

    @Test fun privateAndRemovedVideos() {
        assertEquals(YtErrorKind.PRIVATE, kind(PrivateContentException("This video is private")))
        assertEquals(YtErrorKind.UNAVAILABLE, kind(ContentNotAvailableException("Got error ERROR: \"This video has been removed by the uploader\"")))
        assertEquals(YtErrorKind.UNAVAILABLE, kind(ContentNotAvailableException("Not found")))
        assertEquals(YtErrorKind.TERMINATED, kind(AccountTerminatedException("This account has been terminated")))
    }

    @Test fun membersOnlyAndPaidVideosAreToldApart() {
        assertEquals(
            YtErrorKind.MEMBERS_ONLY,
            kind(PaidContentException("This video is only available for members of the channel of this video")),
        )
        assertEquals(YtErrorKind.PAID, kind(PaidContentException("This video is a paid video")))
        assertEquals(YtErrorKind.MEMBERS_ONLY, kind(ContentNotAvailableException("Got error UNPLAYABLE: \"Join this channel to get access to members-only content\"")))
        assertEquals(YtErrorKind.MUSIC_PREMIUM, kind(YoutubeMusicPremiumContentException()))
    }

    @Test fun liveBroadcastsAndPremieres() {
        assertEquals(YtErrorKind.LIVE, kind(ContentNotAvailableException("Got error LIVE_STREAM_OFFLINE: \"This live event will begin in 2 hours.\"")))
        assertEquals(YtErrorKind.LIVE, kind(ContentNotAvailableException("Got error UNPLAYABLE: \"Premieres in 3 days\"")))
        assertTrue(YtErrorKind.LIVE.message.contains("Live streams aren't supported"))
    }

    @Test fun geoBlock() {
        assertEquals(YtErrorKind.GEO_BLOCKED, kind(GeographicRestrictionException("This video is not available in client's country.")))
        assertEquals(YtErrorKind.GEO_BLOCKED, kind(ContentNotAvailableException("Got error UNPLAYABLE: \"The uploader has not made this video available in your country\"")))
    }

    @Test fun botCheckAndRecaptcha() {
        assertEquals(YtErrorKind.BOT_CHECK, kind(SignInConfirmNotBotException("YouTube probably temporarily blocked anonymous watch access")))
        assertEquals(YtErrorKind.BOT_CHECK, kind(ReCaptchaException("reCaptcha challenge requested", "https://www.youtube.com/watch?v=dQw4w9WgXcQ")))
        assertTrue(YtErrorKind.BOT_CHECK.message.contains("not a bot"))
    }

    @Test fun parsingFailuresMeanXdmNeedsAnUpdate() {
        assertEquals(YtErrorKind.EXTRACTOR_CHANGED, kind(ParsingException("Could not get name")))
        assertEquals(YtErrorKind.EXTRACTOR_CHANGED, kind(ExtractionException("ANDROID player response is not valid")))
        assertEquals(YtErrorKind.EXTRACTOR_CHANGED, kind(ParsingException("Could not parse", NullPointerException())))
        assertEquals("YouTube changed something; XDM needs an update.", YtErrorKind.EXTRACTOR_CHANGED.message)
    }

    @Test fun anythingElseTheLibraryThrowsIsContainedToo() {
        assertEquals(YtErrorKind.EXTRACTOR_CHANGED, kind(RuntimeException("boom")))
        assertEquals(YtErrorKind.EXTRACTOR_CHANGED, kind(IllegalStateException("bad json")))
        assertEquals(YtErrorKind.EXTRACTOR_CHANGED, kind(StackOverflowError()))
        assertEquals(YtErrorKind.EXTRACTOR_CHANGED, kind(NoClassDefFoundError("javax/script/ScriptEngine")))
        assertEquals(YtErrorKind.EXTRACTOR_CHANGED, kind(ExceptionInInitializerError()))
    }

    @Test fun networkTroubleIsNotBlamedOnYoutube() {
        assertEquals(YtErrorKind.NETWORK, kind(IOException("unexpected end of stream")))
        assertEquals(YtErrorKind.NETWORK, kind(UnknownHostException("www.youtube.com")))
        assertEquals(YtErrorKind.NETWORK, kind(SocketTimeoutException("timeout")))
        assertEquals(YtErrorKind.NETWORK, kind(ExtractionException("Could not load player", UnknownHostException("www.youtube.com"))))
    }

    @Test fun aWrappedCauseIsFoundThroughTheChain() {
        assertEquals(YtErrorKind.PRIVATE, kind(ExtractionException("outer", PrivateContentException("inner"))))
    }

    @Test fun ourOwnExceptionPassesThroughUnchanged() {
        val e = YoutubeException(YtErrorKind.FORMAT_GONE)
        assertSame(e, YoutubeErrors.from(e))
    }

    @Test fun cancellationIsNeverSwallowed() {
        try {
            YoutubeErrors.from(CancellationException("job cancelled"))
            fail("cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("job cancelled", e.message)
        }
    }

    @Test fun everyMessageIsPlainWordsWithoutLinksOrIds() {
        for (k in YtErrorKind.entries) {
            assertTrue("$k has text", k.message.length > 15)
            assertFalse("$k: no URL", k.message.contains("http", ignoreCase = true))
            assertFalse("$k: no stack-trace words", k.message.contains("Exception"))
            assertEquals(k.message, YoutubeException(k).message)
        }
    }

    @Test fun theUserNeverSeesTheLibrarysOwnMessage() {
        // The library's text may quote a title or a URL; the user-facing message is the fixed one.
        val e = YoutubeErrors.from(ContentNotAvailableException("Got error ERROR: \"Never Gonna Give You Up is gone\" https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertFalse(e.message!!.contains("Never Gonna"))
        assertFalse(e.message!!.contains("dQw4w9WgXcQ"))
    }
}
