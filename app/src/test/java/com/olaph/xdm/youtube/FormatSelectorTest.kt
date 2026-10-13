package com.olaph.xdm.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class FormatSelectorTest {

    // ---- a fake stream list, shaped like what YouTube offers ----

    private fun v(itag: Int, height: Int, codec: VideoCodec = VideoCodec.H264, fps: Int = 30, bitrate: Int = height * 1500, len: Long = height * 10_000L, container: String = "mp4") =
        YtStream(itag, StreamKind.VIDEO_ONLY, container, codec, width = height * 16 / 9, height = height, fps = fps, bitrate = bitrate, contentLength = len, url = "https://v/$itag")

    private fun a(itag: Int, bitrate: Int, container: String = "m4a", aac: Boolean = container == "m4a", len: Long = bitrate * 100L, original: Boolean = true) =
        YtStream(itag, StreamKind.AUDIO_ONLY, container, aac = aac, bitrate = bitrate, contentLength = len, url = "https://a/$itag", originalTrack = original)

    private fun muxed(itag: Int, height: Int, len: Long = 3_000_000) =
        YtStream(itag, StreamKind.MUXED, "mp4", VideoCodec.H264, aac = true, width = height * 16 / 9, height = height, fps = 30, bitrate = 600_000, contentLength = len, url = "https://m/$itag")

    private val h264 = listOf(
        v(137, 1080), v(299, 1080, fps = 60), v(136, 720), v(135, 480), v(134, 360), v(133, 240), v(160, 144),
    )
    private val av1 = listOf(v(401, 2160, VideoCodec.AV1), v(400, 1440, VideoCodec.AV1), v(399, 1080, VideoCodec.AV1), v(398, 720, VideoCodec.AV1))
    private val vp9 = listOf(
        v(313, 2160, VideoCodec.VP9, container = "webm"), v(271, 1440, VideoCodec.VP9, container = "webm"),
        v(248, 1080, VideoCodec.VP9, container = "webm"), v(247, 720, VideoCodec.VP9, container = "webm"),
    )
    private val audio = listOf(a(140, 130_000), a(139, 48_000), a(251, 135_000, container = "webm"), a(250, 70_000, container = "webm"))

    private fun info(vararg groups: List<YtStream>, durationSeconds: Long = 600) = YtInfo(
        "dQw4w9WgXcQ", "Some Title", "Some Channel", durationSeconds, null, groups.toList().flatten(),
    )

    private val typical = info(h264, av1, vp9, audio, listOf(muxed(18, 360)))

    // ---- the rows of the sheet ----

    @Test fun rowsAreHighestFirstThenTheFastOneThenAudio() {
        val rows = FormatSelector.options(typical)
        assertEquals(
            listOf("2160p AV1", "1440p AV1", "1080p60", "1080p", "720p", "480p", "360p", "240p", "144p", "360p", "Audio only"),
            rows.map { it.label },
        )
        assertEquals(
            List(9) { OptionKind.VIDEO } + OptionKind.PROGRESSIVE + OptionKind.AUDIO,
            rows.map { it.kind },
        )
        val heights = rows.filter { it.kind == OptionKind.VIDEO }.map { it.height }
        assertEquals(heights.sortedDescending(), heights)
    }

    @Test fun h264IsPreferredAtEveryResolutionItOffers() {
        val rows = FormatSelector.options(typical).filter { it.kind == OptionKind.VIDEO }
        val at1080 = rows.first { it.label == "1080p" }
        assertEquals("avc1 (137), not AV1 (399)", 137, at1080.video!!.itag)
        assertEquals(VideoCodec.H264, at1080.video!!.videoCodec)
        assertEquals(136, rows.first { it.height == 720 }.video!!.itag)
        // above 1080p only AV1 exists in MP4
        assertEquals(401, rows.first { it.height == 2160 }.video!!.itag)
    }

    @Test fun vp9InWebmIsNeverOffered() {
        val rows = FormatSelector.options(typical)
        assertTrue(rows.none { it.video?.videoCodec == VideoCodec.VP9 })
        assertTrue(rows.none { it.video?.container == "webm" })
        // a video whose only high-resolution stream is VP9 simply has no such row
        val only = FormatSelector.options(info(h264, vp9, audio))
        assertEquals(1080, only.first().height)
    }

    @Test fun everyVideoRowPairsItsVideoWithTheBestM4aAudio() {
        for (row in FormatSelector.options(typical).filter { it.kind == OptionKind.VIDEO }) {
            assertEquals("m4a 128k (140), not the Opus webm", 140, row.audio!!.itag)
            assertEquals(StreamKind.VIDEO_ONLY, row.video!!.kind)
        }
    }

    @Test fun audioRowIsTheBestM4aAndOpusIsIgnored() {
        val row = FormatSelector.options(typical).last()
        assertEquals(OptionKind.AUDIO, row.kind)
        assertEquals(140, row.audio!!.itag)
        assertNull(row.video)
        assertEquals("m4a", row.extension)
        // Opus-only audio: no audio row and therefore no merged video rows either
        val opusOnly = FormatSelector.options(info(h264, listOf(a(251, 135_000, container = "webm"), a(250, 70_000, container = "webm"))))
        assertTrue(opusOnly.none { it.kind == OptionKind.AUDIO || it.kind == OptionKind.VIDEO })
    }

    @Test fun theOriginalAudioTrackBeatsADubbedOneEvenAtALowerBitrate() {
        val dubbed = info(h264, listOf(a(140, 130_000, original = false), a(141, 128_000, original = true)))
        assertEquals(141, FormatSelector.bestAudio(dubbed)!!.itag)
    }

    @Test fun progressiveStreamIsOfferedAsTheFastPath() {
        val row = FormatSelector.options(typical).first { it.kind == OptionKind.PROGRESSIVE }
        assertEquals("360p", row.label)
        assertEquals(18, row.video!!.itag)
        assertNull(row.audio)
        assertEquals(3_000_000L, row.sizeBytes)
        assertTrue(row.detail.contains("one file"))
        assertEquals("mp4", row.extension)
        assertEquals("360p", row.fileLabel)
    }

    @Test fun progressiveAloneStillWorksWithoutAnyAdaptiveStream() {
        val onlyMuxed = info(listOf(muxed(18, 360), muxed(22, 720, len = 20_000_000)))
        val rows = FormatSelector.options(onlyMuxed)
        assertEquals(listOf("720p", "360p"), rows.map { it.label })
        assertTrue(rows.all { it.kind == OptionKind.PROGRESSIVE })
    }

    @Test fun sizeIsVideoPlusAudio() {
        val row = FormatSelector.options(typical).first { it.height == 720 && it.kind == OptionKind.VIDEO }
        assertEquals(720 * 10_000L + 130_000 * 100L, row.sizeBytes)
        assertFalse(row.sizeEstimated)
    }

    @Test fun missingLengthsAreEstimatedFromBitrateAndDuration() {
        val streams = info(listOf(v(136, 720, bitrate = 1_000_000, len = -1)), listOf(a(140, 128_000, len = -1)), durationSeconds = 100)
        val row = FormatSelector.options(streams).first { it.kind == OptionKind.VIDEO }
        assertEquals((1_000_000L + 128_000) * 100 / 8, row.sizeBytes)
        assertTrue(row.sizeEstimated)
        // no length and no bitrate: unknown
        val unknown = info(listOf(v(136, 720, bitrate = 0, len = -1)), listOf(a(140, 128_000)))
        assertEquals(-1L, FormatSelector.options(unknown).first { it.kind == OptionKind.VIDEO }.sizeBytes)
    }

    // ---- the "Default YouTube quality" setting ----

    private fun pick(i: YtInfo, q: YoutubeQuality) = FormatSelector.pick(i, q)

    @Test fun askPicksNothing() {
        assertNull(pick(typical, YoutubeQuality.ASK))
    }

    @Test fun bestIsTheTopRow() {
        val best = pick(typical, YoutubeQuality.BEST)!!
        assertEquals(2160, best.height)
        assertEquals(401, best.video!!.itag)
        assertEquals(140, best.audio!!.itag)
        // for an H.264-only video the best is its 1080p60
        assertEquals(299, pick(info(h264, audio), YoutubeQuality.BEST)!!.video!!.itag)
    }

    @Test fun fixedResolutionsPickThatRowWithAvc1AndM4a() {
        val p1080 = pick(typical, YoutubeQuality.P1080)!!
        assertEquals(1080, p1080.height)
        assertEquals("the 60 fps row comes first", 299, p1080.video!!.itag)
        assertEquals(VideoCodec.H264, p1080.video!!.videoCodec)
        val p720 = pick(typical, YoutubeQuality.P720)!!
        assertEquals(listOf(136, 140), listOf(p720.video!!.itag, p720.audio!!.itag))
        val p480 = pick(typical, YoutubeQuality.P480)!!
        assertEquals(listOf(135, 140), listOf(p480.video!!.itag, p480.audio!!.itag))
        assertEquals(OptionKind.VIDEO, p480.kind)
    }

    @Test fun aMissingResolutionFallsBackToTheClosestLowerOne() {
        val no720 = info(listOf(v(137, 1080), v(135, 480), v(134, 360)), audio)
        assertEquals(480, pick(no720, YoutubeQuality.P720)!!.height)
        val no1080 = info(listOf(v(136, 720), v(135, 480)), audio)
        assertEquals(720, pick(no1080, YoutubeQuality.P1080)!!.height)
    }

    @Test fun whenEverythingIsHigherTheLowestAvailableIsUsed() {
        val onlyHigh = info(listOf(v(136, 720), v(137, 1080)), audio)
        assertEquals(720, pick(onlyHigh, YoutubeQuality.P480)!!.height)
    }

    @Test fun audioQualityPicksTheAudioRow() {
        val a = pick(typical, YoutubeQuality.AUDIO)!!
        assertEquals(OptionKind.AUDIO, a.kind)
        assertEquals(140, a.audio!!.itag)
    }

    @Test fun withoutAdaptiveVideoTheProgressiveStreamIsTheAnswer() {
        val onlyMuxed = info(listOf(muxed(18, 360)), audio)
        assertEquals(OptionKind.PROGRESSIVE, pick(onlyMuxed, YoutubeQuality.BEST)!!.kind)
        assertEquals(OptionKind.PROGRESSIVE, pick(onlyMuxed, YoutubeQuality.P720)!!.kind)
        assertEquals(OptionKind.PROGRESSIVE, pick(onlyMuxed, YoutubeQuality.P480)!!.kind)
        assertEquals("audio is still audio", OptionKind.AUDIO, pick(onlyMuxed, YoutubeQuality.AUDIO)!!.kind)
    }

    @Test fun nothingDownloadableMeansNull() {
        assertNull(pick(info(), YoutubeQuality.BEST))
        assertNull(pick(info(listOf(a(251, 135_000, container = "webm"))), YoutubeQuality.AUDIO))
    }

    @Test fun rememberingAChoiceMapsToASetting() {
        val rows = FormatSelector.options(typical)
        fun q(label: String, kind: OptionKind = OptionKind.VIDEO) = FormatSelector.qualityFor(rows.first { it.label == label && it.kind == kind }, rows)
        assertEquals(YoutubeQuality.BEST, q("2160p AV1"))
        assertEquals(YoutubeQuality.P1080, q("1080p"))
        assertEquals(YoutubeQuality.P720, q("720p"))
        assertEquals(YoutubeQuality.P480, q("480p"))
        assertNull("360p is not one of the settings", q("360p"))
        assertNull("the fast stream isn't either", q("360p", OptionKind.PROGRESSIVE))
        assertEquals(YoutubeQuality.AUDIO, q("Audio only", OptionKind.AUDIO))
    }

    // ---- plans: stored itags mapped back onto a fresh response ----

    @Test fun aPlanRoundTripsThroughItsEncoding() {
        val row = FormatSelector.options(typical).first { it.height == 720 && it.kind == OptionKind.VIDEO }
        val plan = YtPlan.forOption("dQw4w9WgXcQ", row)
        assertEquals(YtPlan.Mode.MUX, plan.mode)
        assertEquals(listOf(136, 140), listOf(plan.videoItag, plan.audioItag))
        assertEquals(720 * 10_000L + 130_000 * 100L, plan.totalBytes)
        assertEquals(plan, YtPlan.decode(plan.encode()))
        assertEquals("v:136+140", plan.optionId)
        val audio = YtPlan.forOption("dQw4w9WgXcQ", FormatSelector.options(typical).last())
        assertEquals(YtPlan.Mode.AUDIO, audio.mode)
        assertEquals(audio, YtPlan.decode(audio.encode()))
        val deferred = YtPlan.deferred("dQw4w9WgXcQ", YoutubeQuality.P720)
        assertFalse(deferred.hasStreams)
        assertEquals(deferred, YtPlan.decode(deferred.encode()))
        assertEquals(-1L, deferred.totalBytes)
    }

    @Test fun garbagePlansDecodeToNull() {
        assertNull(YtPlan.decode(null))
        assertNull(YtPlan.decode(""))
        assertNull(YtPlan.decode("nonsense"))
        assertNull(YtPlan.decode("mode=MUX&vi=1"))
    }

    @Test fun aStoredPlanFindsTheSameItagsAgain() {
        val plan = YtPlan.forOption("dQw4w9WgXcQ", FormatSelector.options(typical).first { it.height == 480 })
        val fresh = info(h264, av1, audio, listOf(muxed(18, 360))) // another response, other URLs
            .copy(streams = (h264 + av1 + audio + muxed(18, 360)).map { it.copy(url = "https://fresh/${it.itag}") })
        val option = FormatSelector.resolve(fresh, plan)
        assertEquals(135, option.video!!.itag)
        assertEquals(140, option.audio!!.itag)
        assertEquals("https://fresh/135", option.video!!.url)
        assertEquals("480p", option.label)
    }

    @Test fun aPlanFallsBackToTheSameHeightAndCodecUnderAnotherItag() {
        val plan = YtPlan.forOption("dQw4w9WgXcQ", FormatSelector.options(typical).first { it.height == 720 && it.kind == OptionKind.VIDEO })
        val renumbered = info(listOf(v(9136, 720), v(9135, 480)), audio)
        assertEquals(9136, FormatSelector.resolve(renumbered, plan).video!!.itag)
    }

    @Test fun aPlanWhoseQualityIsGoneFailsInPlainWords() {
        val plan = YtPlan.forOption("dQw4w9WgXcQ", FormatSelector.options(typical).first { it.height == 720 && it.kind == OptionKind.VIDEO })
        try {
            FormatSelector.resolve(info(listOf(v(135, 480)), audio), plan)
            fail("720p is gone")
        } catch (e: YoutubeException) {
            assertEquals(YtErrorKind.FORMAT_GONE, e.kind)
            assertTrue(e.message!!.contains("no longer offers"))
        }
    }

    @Test fun aDeferredPlanIsDecidedByItsQuality() {
        val option = FormatSelector.resolve(typical, YtPlan.deferred("dQw4w9WgXcQ", YoutubeQuality.P480))
        assertEquals(135, option.video!!.itag)
        val audioOnly = FormatSelector.resolve(typical, YtPlan.deferred("dQw4w9WgXcQ", YoutubeQuality.AUDIO))
        assertEquals(OptionKind.AUDIO, audioOnly.kind)
        try {
            FormatSelector.resolve(info(), YtPlan.deferred("dQw4w9WgXcQ", YoutubeQuality.BEST))
            fail("nothing to download")
        } catch (e: YoutubeException) {
            assertEquals(YtErrorKind.UNAVAILABLE, e.kind)
        }
    }

    @Test fun aProgressivePlanStaysProgressive() {
        val row = FormatSelector.options(typical).first { it.kind == OptionKind.PROGRESSIVE }
        val plan = YtPlan.forOption("dQw4w9WgXcQ", row)
        assertEquals(YtPlan.Mode.PROGRESSIVE, plan.mode)
        val option = FormatSelector.resolve(typical, plan)
        assertEquals(OptionKind.PROGRESSIVE, option.kind)
        assertEquals(18, option.video!!.itag)
        assertNull(option.audio)
    }

    // ---- names ----

    private fun name(title: String, i: YtInfo = typical, pickRow: (List<YtOption>) -> YtOption): String =
        FormatSelector.fileName(title, pickRow(FormatSelector.options(i)))

    @Test fun fileNamesCarryTheQualityOrNone() {
        assertEquals("Some Title (720p).mp4", name("Some Title") { r -> r.first { it.height == 720 } })
        assertEquals("Some Title (1080p60).mp4", name("Some Title") { r -> r.first { it.label == "1080p60" } })
        assertEquals("Some Title (2160p AV1).mp4", name("Some Title") { r -> r.first { it.height == 2160 } })
        assertEquals("Some Title (360p).mp4", name("Some Title") { r -> r.first { it.kind == OptionKind.PROGRESSIVE } })
        assertEquals("Some Title.m4a", name("Some Title") { r -> r.last() })
    }

    @Test fun titlesAreMadeSafeForFileSystems() {
        assertEquals("AC-DC_ Back in Black (720p).mp4", name("AC/DC: Back in Black") { r -> r.first { it.height == 720 } })
        assertEquals("What _ Why_ (480p).mp4", name("What ? Why?") { r -> r.first { it.height == 480 } })
        assertEquals("Ends with dot (720p).mp4", name("Ends with dot...") { r -> r.first { it.height == 720 } })
        assertEquals("YouTube video (720p).mp4", name("   ") { r -> r.first { it.height == 720 } })
        assertEquals("---.m4a", name("///") { r -> r.last() })
    }

    @Test fun longTitlesAreCutBeforeTheQualityAndExtension() {
        val n = name("x".repeat(400)) { r -> r.first { it.height == 720 } }
        assertTrue(n.length <= 180)
        assertTrue(n, n.endsWith(" (720p).mp4"))
    }

    @Test fun unicodeTitlesSurvive() {
        assertEquals("日本語のタイトル 🎵 (720p).mp4", name("日本語のタイトル 🎵") { r -> r.first { it.height == 720 } })
    }

    @Test fun noOptionIsNull() {
        assertNotNull(FormatSelector.options(typical))
        assertTrue(FormatSelector.options(info()).isEmpty())
    }
}
