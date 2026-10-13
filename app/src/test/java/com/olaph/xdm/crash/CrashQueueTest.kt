package com.olaph.xdm.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashQueueTest {

    @get:Rule val tmp = TemporaryFolder()

    private val dir get() = File(tmp.root, "crash")
    private fun queue() = CrashQueue(dir)
    private fun names() = dir.list().orEmpty().sorted()

    private class FakeUploader(var result: UploadResult) : CrashUploader {
        val bodies = mutableListOf<String>()
        override fun upload(body: ByteArray): UploadResult {
            bodies += body.toString(Charsets.UTF_8)
            return result
        }
    }

    @Test fun `report is written as pending-millis json`() {
        val f = queue().write("{}", nowMillis = 1000)
        assertEquals("pending-1000.json", f?.name)
        assertEquals(listOf("pending-1000.json"), names())
    }

    @Test fun `same millisecond does not overwrite`() {
        val q = queue()
        q.write("a", nowMillis = 5)
        q.write("b", nowMillis = 5)
        assertEquals(2, q.pendingCount())
    }

    @Test fun `at most 10 files are kept and the oldest are dropped`() {
        val q = queue()
        for (i in 1..13L) q.write("r$i", nowMillis = i * 100)
        assertEquals(10, q.pendingCount())
        assertEquals((4..13).map { "pending-${it * 100}.json" }.sorted(), names())
    }

    @Test fun `2xx deletes the file`() {
        val q = queue()
        q.write("{\"a\":1}", nowMillis = 1)
        val up = FakeUploader(UploadResult.ACCEPTED)
        q.flush(up)
        assertEquals(listOf("{\"a\":1}"), up.bodies)
        assertEquals(0, q.pendingCount())
    }

    @Test fun `403 deletes the file`() {
        val q = queue()
        q.write("x", nowMillis = 1)
        q.flush(FakeUploader(UploadResult.REJECTED))
        assertEquals(0, q.pendingCount())
    }

    @Test fun `server error keeps the file and counts the attempt`() {
        val q = queue()
        q.write("x", nowMillis = 7)
        q.flush(FakeUploader(UploadResult.RETRY))
        assertEquals(listOf("pending-7-1.json"), names())
        q.flush(FakeUploader(UploadResult.RETRY))
        assertEquals(listOf("pending-7-2.json"), names())
    }

    @Test fun `gives up after 5 failed attempts`() {
        val q = queue()
        q.write("x", nowMillis = 7)
        val up = FakeUploader(UploadResult.RETRY)
        repeat(4) { q.flush(up) }
        assertEquals(listOf("pending-7-4.json"), names())
        q.flush(up)
        assertEquals(5, up.bodies.size)
        assertTrue(names().isEmpty())
    }

    @Test fun `a retried file can still succeed later`() {
        val q = queue()
        q.write("x", nowMillis = 7)
        q.flush(FakeUploader(UploadResult.RETRY))
        q.flush(FakeUploader(UploadResult.ACCEPTED))
        assertEquals(0, q.pendingCount())
    }

    @Test fun `files are sent oldest first`() {
        val q = queue()
        q.write("new", nowMillis = 200)
        q.write("old", nowMillis = 100)
        val up = FakeUploader(UploadResult.ACCEPTED)
        q.flush(up)
        assertEquals(listOf("old", "new"), up.bodies)
    }

    @Test fun `leftover tmp files and unrelated files are ignored`() {
        dir.mkdirs()
        File(dir, "pending-3.tmp").writeText("half")
        File(dir, "notes.txt").writeText("keep")
        val q = queue()
        val up = FakeUploader(UploadResult.ACCEPTED)
        q.flush(up)
        assertTrue(up.bodies.isEmpty())
        assertFalse(File(dir, "pending-3.tmp").exists())
        assertTrue(File(dir, "notes.txt").exists())
    }
}
