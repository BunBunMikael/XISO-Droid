package dev.xdvdfs.android

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import java.io.IOException

class JobCancelledException : RuntimeException("cancelled")

/**
 * Result of walking a picked folder. [manifest] is the flat text description sent to
 * the native code (see manifest.rs) and [uris] maps manifest entry indices to the
 * document URIs of files (null for directories).
 */
class ScanResult(
    val manifest: String,
    val uris: List<Uri?>,
    val fileCount: Int,
    val totalBytes: Long,
)

/** Helpers around the Storage Access Framework. */
object Saf {
    fun displayName(resolver: ContentResolver, uri: Uri, isTree: Boolean): String {
        val target = if (isTree) {
            DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        } else {
            uri
        }
        try {
            val cursor = resolver.query(target, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null) {
                cursor.use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) {
                        return c.getString(0)
                    }
                }
            }
        } catch (_: Exception) {
            // fall through to the URI based name
        }
        val last = uri.lastPathSegment ?: return "selected item"
        return last.substringAfterLast(':').substringAfterLast('/')
    }

    private fun grantFlags(write: Boolean): Int =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)

    /**
     * The picker only grants temporary access, which can end when the activity goes away
     * while the (foreground service backed) job is still running, so keep it.
     */
    fun persist(resolver: ContentResolver, uri: Uri, write: Boolean) {
        try {
            resolver.takePersistableUriPermission(uri, grantFlags(write))
        } catch (_: Exception) {
            // not every provider supports persistable grants
        }
    }

    fun release(resolver: ContentResolver, uri: Uri, write: Boolean) {
        try {
            resolver.releasePersistableUriPermission(uri, grantFlags(write))
        } catch (_: Exception) {
        }
    }

    fun openRead(resolver: ContentResolver, uri: Uri): ParcelFileDescriptor =
        resolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open $uri for reading")

    /** Opens (and truncates) a document for random access writing. */
    fun openWrite(resolver: ContentResolver, uri: Uri): ParcelFileDescriptor {
        try {
            val pfd = resolver.openFileDescriptor(uri, "rwt")
            if (pfd != null) return pfd
        } catch (_: Exception) {
            // some providers refuse read/write access, retry write only
        }
        return resolver.openFileDescriptor(uri, "wt") ?: throw IOException("Cannot open $uri for writing")
    }

    fun deleteQuietly(resolver: ContentResolver, uri: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (_: Exception) {
        }
    }

    private fun querySize(resolver: ContentResolver, uri: Uri): Long {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot read the size of $uri")
        pfd.use { return it.statSize }
    }

    /** Walks a picked folder tree and builds the manifest for the native side. */
    fun scanTree(resolver: ContentResolver, treeUri: Uri, isCancelled: () -> Boolean): ScanResult {
        val manifest = StringBuilder()
        val uris = ArrayList<Uri?>()
        var fileCount = 0
        var totalBytes = 0L

        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_FLAGS,
        )

        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(Pair(DocumentsContract.getTreeDocumentId(treeUri), ""))

        while (stack.isNotEmpty()) {
            if (isCancelled()) throw JobCancelledException()

            val (parentId, parentRel) = stack.removeLast()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val cursor = resolver.query(childrenUri, projection, null, null, null)
                ?: throw IOException("Cannot list folder \"$parentRel\"")

            cursor.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1)
                    if (id == null || name == null) continue
                    val mime = c.getString(2)
                    val flags = if (c.isNull(4)) 0 else c.getInt(4)
                    if ((flags and Document.FLAG_VIRTUAL_DOCUMENT) != 0) continue

                    if (name.any { it == '\t' || it == '\n' || it == '\r' || it == '/' }) {
                        throw IOException("Unsupported character in file name \"${name.replace("\n", " ")}\"")
                    }

                    val rel = if (parentRel.isEmpty()) name else "$parentRel/$name"
                    if (mime == Document.MIME_TYPE_DIR) {
                        manifest.append("D\t0\t").append(rel).append('\n')
                        uris.add(null)
                        stack.addLast(Pair(id, rel))
                    } else {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                        var size = if (c.isNull(3)) -1L else c.getLong(3)
                        if (size < 0) size = querySize(resolver, docUri)

                        manifest.append("F\t").append(size).append('\t').append(rel).append('\n')
                        uris.add(docUri)
                        fileCount++
                        totalBytes += size
                    }
                }
            }
        }

        return ScanResult(manifest.toString(), uris, fileCount, totalBytes)
    }
}

/**
 * Creates directories and files inside a picked output folder on demand, reusing
 * (and truncating) entries that already exist. Not thread safe; one job uses it.
 */
class TreeWriter(private val resolver: ContentResolver, private val treeUri: Uri) {
    private class Child(val docId: String, val isDir: Boolean)
    private class Dir(val docId: String, val children: MutableMap<String, Child>)

    private val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
    private val dirs = HashMap<String, Dir>()

    private fun docUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)

    private fun listChildren(docId: String): MutableMap<String, Child> {
        val result = HashMap<String, Child>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val cursor = resolver.query(
            childrenUri,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
            null,
            null,
            null,
        ) ?: return result
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1)
                if (id == null || name == null) continue
                result[name] = Child(id, c.getString(2) == Document.MIME_TYPE_DIR)
            }
        }
        return result
    }

    private fun splitParent(rel: String): Pair<String, String> {
        val idx = rel.lastIndexOf('/')
        return if (idx < 0) Pair("", rel) else Pair(rel.substring(0, idx), rel.substring(idx + 1))
    }

    private fun dir(rel: String): Dir {
        val cached = dirs[rel]
        if (cached != null) return cached

        if (rel.isEmpty()) {
            val root = Dir(rootDocId, listChildren(rootDocId))
            dirs[rel] = root
            return root
        }

        val (parentRel, name) = splitParent(rel)
        val parent = dir(parentRel)
        val existing = parent.children[name]
        val created = if (existing != null && existing.isDir) {
            Dir(existing.docId, listChildren(existing.docId))
        } else {
            val uri = DocumentsContract.createDocument(resolver, docUri(parent.docId), Document.MIME_TYPE_DIR, name)
                ?: throw IOException("Cannot create folder \"$rel\"")
            val id = DocumentsContract.getDocumentId(uri)
            parent.children[name] = Child(id, true)
            Dir(id, HashMap())
        }
        dirs[rel] = created
        return created
    }

    fun createDir(rel: String) {
        dir(rel)
    }

    /** Returns a detached, writable file descriptor for a new (or truncated) file at [rel]. */
    fun createFile(rel: String): Int {
        val (parentRel, name) = splitParent(rel)
        val parent = dir(parentRel)
        val existing = parent.children[name]

        val uri = if (existing != null && !existing.isDir) {
            docUri(existing.docId)
        } else {
            val created = DocumentsContract.createDocument(
                resolver,
                docUri(parent.docId),
                "application/octet-stream",
                name,
            ) ?: throw IOException("Cannot create file \"$rel\"")
            parent.children[name] = Child(DocumentsContract.getDocumentId(created), false)
            created
        }

        // Read/write mode first: the CISO writer seeks back, which needs a real file descriptor.
        return Saf.openWrite(resolver, uri).detachFd()
    }
}
