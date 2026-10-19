package com.olaph.xdm.core

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.olaph.xdm.engine.FileCategory
import com.olaph.xdm.engine.FilenameUtils
import com.olaph.xdm.engine.MimeTypes
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream

/**
 * [note] is a short remark for the finished item (the chosen folder was unavailable and the file went to Download/XDM).
 */
data class Saved(val location: String, val displayPath: String, val fileName: String, val note: String? = null)

/** Where finished downloads go; Android-free so the manager can be tested with a fake. */
interface FileStore {
    /** Blocking; call from an IO dispatcher. Deletes nothing: the caller removes [temp] on success. */
    fun save(temp: File, desiredName: String, mimeType: String?): Saved
    /** Blocking; call from an IO dispatcher. Never throws. */
    fun delete(location: String): DeleteStatus
    /** Blocking; reads a saved file. Throws if it is gone or unreadable. */
    fun open(location: String): InputStream
    /** Blocking; is the saved file still there? */
    fun exists(location: String): Boolean = runCatching { open(location).use { true } }.getOrDefault(false)
}

enum class DeleteStatus {
    DELETED,
    /** Nothing there any more (moved or deleted by the user): the record can simply go. */
    MISSING,
    /** The app may not delete it (e.g. owned by a previous install); needs the system's confirmation. */
    DENIED,
    FAILED,
}

/** The "Save location" settings as the saver needs them. [treeUri] null = Download/XDM through MediaStore. */
data class SaveTarget(val treeUri: String? = null, val sortByType: Boolean = false)

/**
 * Moves finished temp files to their final place: Download/XDM (MediaStore on Android 10+), or a folder the user picked
 * with the system folder picker (a storage-access-framework tree: SD cards, cloud providers...). A saved file is
 * identified by its location string: a MediaStore or document `content://` Uri, or an absolute path below Android 10.
 */
class FileSaver(
    private val context: Context,
    private val target: () -> SaveTarget = { SaveTarget() },
    /** Called when the chosen folder could not be used and the file went to Download/XDM instead. */
    private val onFolderUnavailable: () -> Unit = {},
) : FileStore {

    private val authority = "${context.packageName}.fileprovider"
    private val resolver get() = context.contentResolver

    override fun save(temp: File, desiredName: String, mimeType: String?): Saved {
        val mime = MimeTypes.forFileName(desiredName)
            ?: mimeType?.takeIf { it != MimeTypes.OCTET_STREAM }
            ?: MimeTypes.OCTET_STREAM
        val t = target()
        val category = if (t.sortByType) FileCategory.of(desiredName, mime) else null
        var note: String? = null
        val tree = t.treeUri?.let { Uri.parse(it) }
        if (tree != null) {
            // Whatever goes wrong with the chosen folder, the downloaded file must not be lost: it goes to Download/XDM.
            val viaTree = if (treeUsable(tree)) runCatching { saveToTree(tree, temp, desiredName, mime, category) }.getOrNull() else null
            if (viaTree != null) return viaTree
            note = FOLDER_UNAVAILABLE_NOTE
            onFolderUnavailable()
        }
        val saved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveMediaStore(temp, desiredName, mime, category)
        else saveLegacy(temp, desiredName, mime, category)
        return if (note != null) saved.copy(note = note) else saved
    }

    private fun relativePath(category: FileCategory?) = if (category == null) RELATIVE_PATH else "$RELATIVE_PATH/${category.folder}"

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveMediaStore(temp: File, desiredName: String, mime: String, category: FileCategory?): Saved {
        val folder = relativePath(category)
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = FilenameUtils.uniqueName(desiredName) { candidate ->
            resolver.query(
                collection,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(candidate, "$folder/"),
                null,
            )?.use { it.moveToFirst() } ?: false
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, folder)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Cannot create file in Downloads")
        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                temp.inputStream().use { it.copyTo(out, COPY_BUFFER) }
            } ?: throw IOException("Cannot open $uri for writing")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        // MediaProvider may adjust the display name; read back what it actually used.
        val finalName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: name
        return Saved(uri.toString(), "$folder/$finalName", finalName)
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(temp: File, desiredName: String, mime: String, category: FileCategory?): Saved {
        val base = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "XDM")
        val dir = if (category == null) base else File(base, category.folder)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
        val name = FilenameUtils.uniqueName(desiredName) { File(dir, it).exists() }
        val target = File(dir, name)
        if (!temp.renameTo(target)) {
            temp.inputStream().use { input -> target.outputStream().use { input.copyTo(it, COPY_BUFFER) } }
        }
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
        return Saved(target.absolutePath, "${relativePath(category)}/$name", name)
    }

    // ---- a folder picked by the user (storage access framework) ----

    private fun isMediaStore(location: String): Boolean =
        location.startsWith("content://") && Uri.parse(location).authority == MediaStore.AUTHORITY

    /**
     * A file in a chosen folder: any other content Uri. (DocumentsContract.isDocumentUri asks the package manager, which
     * hides other apps' providers from Android 11 on.)
     */
    private fun isDocument(location: String): Boolean = location.startsWith("content://") && !isMediaStore(location)

    private fun rootDocument(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    /** The permission is still ours (write) and the folder still answers: deleted folders and removed cards do not. */
    private fun treeUsable(tree: Uri): Boolean = runCatching {
        resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission } && displayName(rootDocument(tree)) != null
    }.getOrDefault(false)

    // Documents providers only implement the Bundle form of query (Android 8+).
    private fun displayName(doc: Uri): String? = try {
        resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), Bundle.EMPTY, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    /** Document id of the child of [parentId] called [name], or null. */
    private fun child(tree: Uri, parentId: String, name: String): String? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        resolver.query(children, columns, Bundle.EMPTY, null)?.use { c ->
            while (c.moveToNext()) if (c.getString(1) == name) return c.getString(0)
        }
        return null
    }

    private fun childDirectory(tree: Uri, parentId: String, name: String): String {
        child(tree, parentId, name)?.let { return it }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val created = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Cannot create the folder $name")
        return DocumentsContract.getDocumentId(created)
    }

    private fun saveToTree(tree: Uri, temp: File, desiredName: String, mime: String, category: FileCategory?): Saved {
        var parentId = DocumentsContract.getTreeDocumentId(tree)
        if (category != null) parentId = childDirectory(tree, parentId, category.folder)
        val name = FilenameUtils.uniqueName(desiredName) { child(tree, parentId, it) != null }
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentId)
        val created = DocumentsContract.createDocument(resolver, parent, mime, name) ?: throw IOException("Cannot create the file")
        // Always address the document through the tree, so the persisted tree permission covers it.
        var doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(created))
        try {
            resolver.openOutputStream(doc, "w")?.use { out ->
                temp.inputStream().use { it.copyTo(out, COPY_BUFFER) }
            } ?: throw IOException("Cannot open the file for writing")
            var finalName = displayName(doc) ?: name
            if (finalName != name) {
                // Some providers append an extension that matches the MIME type; the name asked for wins when it can.
                runCatching { DocumentsContract.renameDocument(resolver, doc, name) }.getOrNull()?.let {
                    doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getDocumentId(it))
                    finalName = name
                }
            }
            val where = category?.let { "${it.folder}/" }.orEmpty()
            return Saved(doc.toString(), "${folderLabel(tree.toString()) ?: "Chosen folder"}/$where$finalName", finalName)
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(resolver, doc) }
            throw e
        }
    }

    /** The name of the chosen folder as the provider shows it, or null when it cannot be read. */
    fun folderLabel(treeUri: String): String? {
        val tree = Uri.parse(treeUri)
        displayName(runCatching { rootDocument(tree) }.getOrNull() ?: return null)?.let { return it }
        return runCatching {
            DocumentsContract.getTreeDocumentId(tree).substringAfter(':').substringAfterLast('/').ifEmpty { null }
        }.getOrNull()
    }

    /** The folder (as shown in a saved item's path) a new file called [fileName] would be saved into. Blocking. */
    fun plannedFolder(fileName: String): String {
        val t = target()
        val category = if (t.sortByType) FileCategory.of(fileName, null) else null
        val tree = t.treeUri ?: return relativePath(category)
        return (folderLabel(tree) ?: "Chosen folder") + category?.let { "/${it.folder}" }.orEmpty()
    }

    /** Is the chosen folder still usable (permission kept, storage present)? */
    fun folderAvailable(treeUri: String): Boolean = treeUsable(Uri.parse(treeUri))

    /** Free bytes on the chosen folder's volume, or null when that cannot be told (other providers, volume not resolvable). */
    fun targetFreeSpace(): Long? {
        val tree = target().treeUri?.let { Uri.parse(it) } ?: return null
        return runCatching {
            if (tree.authority != EXTERNAL_STORAGE_AUTHORITY) return null
            val volume = DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
            @Suppress("DEPRECATION")
            val root = if (volume == "primary") Environment.getExternalStorageDirectory() else File("/storage/$volume")
            if (root.isDirectory) StatFs(root.path).availableBytes else null
        }.getOrNull()
    }

    // ---- reading, opening, deleting ----

    /** A Uri other apps can read: the MediaStore or document Uri itself, or a FileProvider Uri for legacy paths. */
    fun uriFor(location: String): Uri =
        if (location.startsWith("content://")) Uri.parse(location)
        else FileProvider.getUriForFile(context, authority, File(location))

    override fun exists(location: String): Boolean = runCatching {
        when {
            isDocument(location) -> displayName(Uri.parse(location)) != null
            location.startsWith("content://") ->
                resolver.query(Uri.parse(location), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } ?: false
            else -> File(location).exists()
        }
    }.getOrDefault(false)

    override fun open(location: String): InputStream =
        if (location.startsWith("content://")) {
            resolver.openInputStream(Uri.parse(location)) ?: throw IOException("Cannot read the file")
        } else {
            File(location).inputStream()
        }

    override fun delete(location: String): DeleteStatus = try {
        when {
            isDocument(location) -> deleteDocument(Uri.parse(location))
            location.startsWith("content://") ->
                if (resolver.delete(Uri.parse(location), null, null) > 0) DeleteStatus.DELETED else DeleteStatus.MISSING
            else -> {
                val f = File(location)
                when {
                    !f.exists() -> DeleteStatus.MISSING
                    f.delete() -> DeleteStatus.DELETED
                    else -> DeleteStatus.FAILED
                }
            }
        }
    } catch (_: SecurityException) { // includes RecoverableSecurityException
        DeleteStatus.DENIED
    } catch (_: Exception) {
        DeleteStatus.FAILED
    }

    private fun deleteDocument(doc: Uri): DeleteStatus = try {
        if (DocumentsContract.deleteDocument(resolver, doc)) DeleteStatus.DELETED
        else if (displayName(doc) == null) DeleteStatus.MISSING else DeleteStatus.FAILED
    } catch (_: FileNotFoundException) {
        DeleteStatus.MISSING
    } catch (_: IllegalArgumentException) {
        DeleteStatus.MISSING // the storage is gone (card removed): there is nothing left to delete
    } catch (_: SecurityException) {
        // The system's delete dialog only covers MediaStore files, so asking would not help here.
        DeleteStatus.FAILED
    }

    /**
     * One system confirmation dialog for all [locations] (API 30+; MediaStore Uris only, files in a chosen folder are not
     * part of it). Null below API 30 or when there is nothing to ask about. Launch it, then drop the records if the
     * result is RESULT_OK.
     */
    fun deleteRequest(locations: List<String>): android.content.IntentSender? {
        val uris = locations.filter { isMediaStore(it) }.map { Uri.parse(it) }
        if (uris.isEmpty() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching { MediaStore.createDeleteRequest(resolver, uris).intentSender }.getOrNull()
    }

    companion object {
        const val RELATIVE_PATH = "Download/XDM"
        const val FOLDER_UNAVAILABLE_NOTE = "Saved to Download/XDM: chosen folder unavailable"
        private const val COPY_BUFFER = 256 * 1024
        private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
