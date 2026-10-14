package com.olaph.xdm.vault

import com.olaph.xdm.core.DeleteStatus
import com.olaph.xdm.core.FileStore
import com.olaph.xdm.core.Saved
import com.olaph.xdm.engine.FilenameUtils
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The private folder: plain files in app-internal storage (`filesDir/private`). Nothing here is visible to MediaStore or
 * other apps; protection is the app sandbox plus the device's file-based encryption, there is no extra cipher.
 * Locations are absolute paths. Android-free, so the manager tests use it directly.
 */
class VaultStore(val dir: File) : FileStore {

    /** Moves [temp] in (a rename when it is on the same file system, else copy + verify). [temp] may be gone afterwards. */
    override fun save(temp: File, desiredName: String, mimeType: String?): Saved {
        ensureDir()
        val size = temp.length()
        val target = File(dir, uniqueName(desiredName))
        if (!temp.renameTo(target)) {
            temp.inputStream().use { import(it, target, size) }
        }
        return saved(target)
    }

    /** Copies [input] into the folder as [desiredName]; [expectedBytes] (if >= 0) must match what arrives. */
    fun importStream(input: InputStream, desiredName: String, expectedBytes: Long): Saved {
        ensureDir()
        val target = File(dir, uniqueName(desiredName))
        import(input, target, expectedBytes)
        return saved(target)
    }

    private fun import(input: InputStream, target: File, expectedBytes: Long) {
        // Written under a dot name first, so a half-copied file never shows up in the listing.
        val part = File(dir, ".${target.name}.part")
        try {
            val copied = part.outputStream().use { out -> input.copyTo(out, 256 * 1024) }
            if (expectedBytes >= 0 && copied != expectedBytes) {
                throw IOException("Copy incomplete: $copied of $expectedBytes bytes")
            }
            if (!part.renameTo(target)) throw IOException("Cannot finish the copy")
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    override fun delete(location: String): DeleteStatus {
        val f = File(location)
        if (!contains(f)) return DeleteStatus.FAILED
        return when {
            !f.exists() -> DeleteStatus.MISSING
            f.delete() -> DeleteStatus.DELETED
            else -> DeleteStatus.FAILED
        }
    }

    override fun open(location: String): InputStream {
        val f = File(location)
        if (!contains(f)) throw IOException("Not in the private folder")
        return f.inputStream()
    }

    /** Renames a file in the folder; null if [location] is not there or the rename fails. */
    fun rename(location: String, newName: String): Saved? {
        val f = File(location)
        if (!contains(f) || !f.isFile) return null
        val clean = FilenameUtils.sanitize(newName)
        if (clean == f.name) return saved(f)
        val target = File(dir, uniqueName(clean))
        return if (f.renameTo(target)) saved(target) else null
    }

    fun file(location: String): File? = File(location).takeIf { contains(it) }

    /** Everything goes: "Reset private folder". */
    fun wipe(): Boolean {
        dir.deleteRecursively()
        return !dir.exists()
    }

    private fun contains(f: File): Boolean = f.canonicalFile.parentFile == dir.canonicalFile

    private fun ensureDir() {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create the private folder")
    }

    private fun uniqueName(desired: String): String =
        FilenameUtils.uniqueName(FilenameUtils.sanitize(desired)) { File(dir, it).exists() }

    private fun saved(f: File) = Saved(f.absolutePath, "$DISPLAY_FOLDER/${f.name}", f.name)

    companion object {
        const val DIR_NAME = "private"
        const val DISPLAY_FOLDER = "Private folder"
    }
}
