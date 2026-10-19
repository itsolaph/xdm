package com.olaph.xdm.core

import com.olaph.xdm.data.DownloadEntity
import com.olaph.xdm.data.DownloadKind
import com.olaph.xdm.data.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateCheckTest {
    private val present = HashSet<String>()
    private var planned: String? = "Download/XDM"
    private val check get() = DuplicateCheck(fileExists = { it in present }, folderFor = { planned })

    private var nextId = 1L

    private fun entity(
        url: String = "https://cdn.test/a/file.zip",
        name: String = "file.zip",
        status: DownloadStatus = DownloadStatus.PAUSED,
        total: Long = 1000,
        done: Long = 250,
        saved: String? = null,
        path: String? = null,
        isPrivate: Boolean = false,
        kind: String = DownloadKind.HTTP,
        extra: String? = null,
    ) = DownloadEntity(
        id = nextId++, url = url, fileName = name, status = status, totalBytes = total, downloadedBytes = done,
        savedLocation = saved, savedPath = path, isPrivate = isPrivate, kind = kind, extra = extra,
    )

    private fun completed(name: String = "file.zip", url: String = "https://cdn.test/a/file.zip", folder: String = "Download/XDM", total: Long = 1000): DownloadEntity {
        val location = "/saved/$folder/$name"
        present += location
        return entity(url = url, name = name, status = DownloadStatus.COMPLETED, total = total, done = total, saved = location, path = "$folder/$name")
    }

    private fun candidate(url: String = "https://cdn.test/a/file.zip", name: String = "file.zip", size: Long = -1, isPrivate: Boolean = false) =
        DuplicateCandidate(url, name, size, isPrivate)

    // ---- nothing in common ----

    @Test fun anEmptyListHasNoDuplicate() {
        assertNull(check.find(candidate(), emptyList()))
    }

    @Test fun aDifferentFileIsNotADuplicate() {
        val other = entity(url = "https://cdn.test/b/other.iso", name = "other.iso", total = 5000)
        assertNull(check.find(candidate(size = 1000), listOf(other)))
    }

    // ---- the link ----

    @Test fun theSameLinkMatches() {
        val e = entity(url = "https://cdn.test/a/file.zip", name = "renamed.zip")
        val m = check.find(candidate(), listOf(e))!!
        assertEquals(DuplicateReason.SAME_LINK, m.reason)
        assertEquals(e.id, m.existing.id)
    }

    @Test fun theLinkIsComparedNormalised() {
        val e = entity(url = "HTTPS://CDN.test:443/a/file.zip#part", name = "x.zip")
        assertEquals(DuplicateReason.SAME_LINK, check.find(candidate(), listOf(e))!!.reason)
    }

    @Test fun aDifferentQueryIsADifferentLink() {
        val e = entity(url = "https://cdn.test/a/file.zip?token=1", name = "x.zip", total = 77)
        assertNull(check.find(candidate(url = "https://cdn.test/a/file.zip?token=2", name = "y.zip"), listOf(e)))
    }

    @Test fun twoQualitiesOfOneYoutubeVideoAreNotTheSameLink() {
        val watch = "https://www.youtube.com/watch?v=abc"
        val existing = entity(url = watch, name = "Clip (720p).mp4", kind = DownloadKind.YOUTUBE, extra = "plan-720", total = -1)
        val sameQuality = DuplicateCandidate(watch, "Clip (720p).mp4", extra = "plan-720")
        val otherQuality = DuplicateCandidate(watch, "Clip (1080p).mp4", extra = "plan-1080")
        val anyQuality = DuplicateCandidate(watch, "Clip.mp4", extra = "plan-deferred", anyQuality = true)
        assertEquals(DuplicateReason.SAME_LINK, check.find(sameQuality, listOf(existing))!!.reason)
        assertNull(check.find(otherQuality, listOf(existing)))
        assertEquals(DuplicateReason.SAME_LINK, check.find(anyQuality, listOf(existing))!!.reason)
    }

    // ---- name and folder ----

    @Test fun theSameNameInTheSameFolderMatches() {
        val e = completed(name = "file.zip", url = "https://other.test/x")
        val m = check.find(candidate(url = "https://cdn.test/new"), listOf(e))!!
        assertEquals(DuplicateReason.SAME_NAME, m.reason)
    }

    @Test fun theNameIsComparedIgnoringCase() {
        val e = entity(url = "https://other.test/x", name = "File.ZIP")
        assertEquals(DuplicateReason.SAME_NAME, check.find(candidate(url = "https://cdn.test/new"), listOf(e))!!.reason)
    }

    @Test fun theSameNameInAnotherFolderIsNotADuplicate() {
        val e = completed(name = "file.zip", url = "https://other.test/x", folder = "Download/XDM/Video")
        assertNull(check.find(candidate(url = "https://cdn.test/new"), listOf(e)))
    }

    @Test fun anUnknownTargetFolderCountsAsTheSame() {
        planned = null
        val e = completed(name = "file.zip", url = "https://other.test/x", folder = "Somewhere")
        assertEquals(DuplicateReason.SAME_NAME, check.find(candidate(url = "https://cdn.test/new"), listOf(e))!!.reason)
    }

    @Test fun anUnfinishedDownloadWithTheNameWillLandInTheSameFolder() {
        val e = entity(url = "https://other.test/x", name = "file.zip", status = DownloadStatus.FAILED)
        assertEquals(DuplicateReason.SAME_NAME, check.find(candidate(url = "https://cdn.test/new"), listOf(e))!!.reason)
    }

    // ---- size and stem ----

    @Test fun theSameSizeAndStemMatches() {
        val e = completed(name = "movie (1).mp4", url = "https://other.test/x", total = 4096)
        val m = check.find(candidate(url = "https://cdn.test/new", name = "Movie.mp4", size = 4096), listOf(e))!!
        assertEquals(DuplicateReason.SAME_SIZE, m.reason)
    }

    @Test fun theSameSizeWithAnotherNameIsNotADuplicate() {
        val e = completed(name = "holiday.mp4", url = "https://other.test/x", total = 4096)
        assertNull(check.find(candidate(url = "https://cdn.test/new", name = "movie.mp4", size = 4096), listOf(e)))
    }

    @Test fun theSameStemWithAnotherExtensionIsNotADuplicate() {
        val e = completed(name = "movie.mkv", url = "https://other.test/x", total = 4096)
        assertNull(check.find(candidate(url = "https://cdn.test/new", name = "movie.mp4", size = 4096), listOf(e)))
    }

    @Test fun theSameStemWithAnotherSizeIsNotADuplicate() {
        val e = completed(name = "movie (1).mp4", url = "https://other.test/x", total = 4096)
        assertNull(check.find(candidate(url = "https://cdn.test/new", name = "movie.mp4", size = 9999), listOf(e)))
    }

    @Test fun anUnknownSizeNeverMatchesBySize() {
        val e = entity(url = "https://other.test/x", name = "movie (1).mp4", total = -1)
        assertNull(check.find(candidate(url = "https://cdn.test/new", name = "movie.mp4", size = -1), listOf(e)))
    }

    // ---- finished downloads, cancelled ones, private ones ----

    @Test fun aCompletedDownloadWhoseFileIsGoneIsNotADuplicate() {
        val e = completed()
        present.clear() // deleted outside the app
        assertNull(check.find(candidate(), listOf(e)))
    }

    @Test fun aCompletedDownloadWhoseFileIsStillThereIsADuplicate() {
        val e = completed()
        assertEquals(DuplicateReason.SAME_LINK, check.find(candidate(), listOf(e))!!.reason)
    }

    @Test fun aCompletedDownloadWithoutALocationIsNotADuplicate() {
        val e = entity(status = DownloadStatus.COMPLETED, saved = null)
        assertNull(check.find(candidate(), listOf(e)))
    }

    @Test fun aCancelledDownloadIsIgnored() {
        val e = entity(status = DownloadStatus.CANCELLED)
        assertNull(check.find(candidate(), listOf(e)))
    }

    @Test fun privateDownloadsNeitherMatchNorAreChecked() {
        val hidden = entity(isPrivate = true)
        assertNull("a private item never shows up for a public add", check.find(candidate(), listOf(hidden)))
        assertNull("a private add is not checked at all", check.find(candidate(isPrivate = true), listOf(entity())))
    }

    // ---- which one is reported ----

    @Test fun theSameLinkBeatsTheSameName() {
        val byName = entity(url = "https://other.test/x", name = "file.zip")
        val byLink = entity(url = "https://cdn.test/a/file.zip", name = "other.zip")
        assertEquals(byLink.id, check.find(candidate(), listOf(byName, byLink))!!.existing.id)
    }

    @Test fun anUnfinishedMatchBeatsAFinishedOneOfTheSameKind() {
        val done = completed(name = "file.zip")
        val paused = entity(name = "file.zip", status = DownloadStatus.PAUSED)
        val m = check.find(candidate(), listOf(done, paused))!!
        assertEquals(paused.id, m.existing.id)
        assertTrue(m.resumable)
    }

    @Test fun onlyPausedAndFailedMatchesCanBeResumed() {
        fun resumable(s: DownloadStatus) = DuplicateMatch(entity(status = s), DuplicateReason.SAME_LINK).resumable
        assertTrue(resumable(DownloadStatus.PAUSED))
        assertTrue(resumable(DownloadStatus.FAILED))
        assertFalse(resumable(DownloadStatus.QUEUED))
        assertFalse(resumable(DownloadStatus.RUNNING))
        assertFalse(resumable(DownloadStatus.COMPLETED))
    }

    // ---- several at once ----

    @Test fun aBatchSaysHowManyAreAlreadyThere() {
        val existing = listOf(entity(url = "https://v.test/1", name = "one.mp4"), completed(name = "three.mp4", url = "https://v.test/3"))
        val urls = listOf("https://v.test/1", "https://v.test/2", "https://v.test/3", "https://v.test/4", "https://v.test/5")
        val batch = check.findAll(urls, existing) { DuplicateCandidate(it, it.substringAfterLast('/') + ".bin") }
        assertEquals(5, batch.total)
        assertEquals(2, batch.duplicates)
        assertEquals("2 of 5 already in your list", batch.summary)
        assertEquals(listOf("https://v.test/2", "https://v.test/4", "https://v.test/5"), batch.withoutDuplicates())
    }

    @Test fun aBatchWithoutDuplicatesHasNoSummary() {
        val batch = check.findAll(listOf("https://v.test/1"), emptyList()) { DuplicateCandidate(it, "1.bin") }
        assertNull(batch.summary)
        assertEquals(0, batch.duplicates)
        assertEquals(listOf("https://v.test/1"), batch.withoutDuplicates())
    }

    @Test fun skippingEverythingLeavesNothing() {
        val existing = listOf(entity(url = "https://v.test/1", name = "1.bin"), entity(url = "https://v.test/2", name = "2.bin"))
        val batch = check.findAll(listOf("https://v.test/1", "https://v.test/2"), existing) { DuplicateCandidate(it, it.substringAfterLast('/') + ".bin") }
        assertEquals("2 of 2 already in your list", batch.summary)
        assertTrue(batch.withoutDuplicates().isEmpty())
    }

    // ---- the text ----

    @Test fun theDialogDescribesProgress() {
        assertEquals("Paused · 250 B of 1000 B (25%)", DuplicateCheck.describe(entity(status = DownloadStatus.PAUSED)))
        assertEquals("Completed · 1000 B", DuplicateCheck.describe(completed()))
        assertEquals("Failed", DuplicateCheck.describe(entity(status = DownloadStatus.FAILED, total = -1, done = 0)))
    }
}
