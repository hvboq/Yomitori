package mihon.testprovider

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.Binder
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * UID-guarded, debug-only real DocumentsProvider for UUID-owned cache fixtures.
 * Two deliberately synthetic tree roots expose the same underlying document IDs.
 * This does not emulate persisted picker grants or any external provider implementation.
 */
class SafAliasDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        ownUidOnly()
        return super.call(method, arg, extras)
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        ownUidOnly()
        // Never advertise synthetic fixtures to the system document picker.
        return MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val (session, file) = resolve(documentId)
        session.reads.add(documentId)
        return MatrixCursor(projection ?: COLUMNS).apply { include(documentId, file) }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val (session, directory) = resolve(parentDocumentId)
        check(directory.isDirectory)
        session.reads.add(parentDocumentId)
        return MatrixCursor(projection ?: COLUMNS).apply {
            for (child in requireNotNull(directory.listFiles()).sortedBy { it.name }) {
                include(session.documentId(child), session.checked(child))
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        val (session, file) = resolve(documentId)
        signal?.throwIfCanceled()
        if (!file.isFile) throw FileNotFoundException("Fixture document is not a file")
        if ('w' in mode) {
            session.writeModes.add(documentId to mode)
            val pendingWriteFailure = session.failNextWriteDocumentId.get()
            // Provider arguments may be equal strings carried across Binder, not the same object.
            val failSelectedWrite = pendingWriteFailure == documentId &&
                session.failNextWriteDocumentId.compareAndSet(pendingWriteFailure, null)
            if (
                (file == session.page && session.failNextPageWrite.compareAndSet(true, false)) ||
                failSelectedWrite
            ) {
                // Exercise replacement rollback after an actual provider-side truncate.
                file.writeBytes(byteArrayOf(1, 2, 3))
                throw FileNotFoundException("Intentional owned-fixture replacement failure")
            }
        } else {
            session.reads.add(documentId)
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val (session, parent) = resolve(parentDocumentId)
        check(parent.isDirectory)
        requireName(displayName)
        session.createRequests.add(mimeType to displayName)
        // UniFile separates recognized suffixes from the requested display name. Mirror a real
        // provider's MIME-aware naming while retaining the original request for fixture assertions.
        val mimeTypes = MimeTypeMap.getSingleton()
        val extension = when (mimeType) {
            DocumentsContract.Document.MIME_TYPE_DIR, "application/octet-stream" -> null
            else -> mimeTypes.getExtensionFromMimeType(mimeType)
        }
        val currentExtension = displayName.substringAfterLast('.', "").lowercase()
        val hasMatchingExtension = extension != null &&
            (
                displayName.endsWith(".$extension", ignoreCase = true) ||
                    mimeTypes.getMimeTypeFromExtension(currentExtension) == mimeType
                )
        val fileName = if (!extension.isNullOrEmpty() && !hasMatchingExtension) {
            "$displayName.$extension"
        } else {
            displayName
        }
        requireName(fileName)
        val child = session.checked(File(parent, fileName))
        check(!child.exists()) { "Fixture entry already exists" }
        check(if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) child.mkdir() else child.createNewFile())
        return session.documentId(child)
    }

    override fun deleteDocument(documentId: String) {
        val (session, file) = resolve(documentId)
        check(file != session.directory && file.delete()) { "Cannot delete fixture entry" }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val (session, file) = resolve(documentId)
        requireName(displayName)
        check(file != session.directory)
        val destination = session.checked(File(requireNotNull(file.parentFile), displayName))
        check(!destination.exists() && file.renameTo(destination))
        return session.documentId(destination)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (parentSession, parent) = resolve(parentDocumentId)
        val (childSession, child) = resolve(documentId)
        return parentSession === childSession && child != parent && child.toPath().startsWith(parent.toPath())
    }

    private fun resolve(documentId: String): Pair<Session, File> {
        ownUidOnly()
        val uuid = documentId.substringBefore('|')
        require(UUID.fromString(uuid).toString() == uuid)
        val session = sessions[uuid] ?: throw FileNotFoundException("Unknown fixture session")
        val path = documentId.substringAfter('|', missingDelimiterValue = "")
        val file = when (path) {
            "root-a", "root-b" -> session.directory
            else -> {
                require(path.isNotEmpty())
                path.split('/').forEach(::requireName)
                session.checked(File(session.directory, path))
            }
        }
        if (!file.exists()) throw FileNotFoundException("Fixture document missing")
        return session to file
    }

    private fun ownUidOnly() {
        check(Binder.getCallingUid() == Process.myUid()) { "Private fixture provider" }
    }

    private fun MatrixCursor.include(id: String, file: File) {
        newRow().apply {
            for (column in columnNames) {
                add(
                    when (column) {
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID -> id
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME -> file.name
                        DocumentsContract.Document.COLUMN_MIME_TYPE -> when {
                            file.isDirectory -> DocumentsContract.Document.MIME_TYPE_DIR
                            file.extension.equals("png", true) -> "image/png"
                            file.extension.equals("jpg", true) -> "image/jpeg"
                            else -> "application/octet-stream"
                        }
                        DocumentsContract.Document.COLUMN_SIZE -> file.length()
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED -> file.lastModified()
                        DocumentsContract.Document.COLUMN_FLAGS -> if (file.isDirectory) {
                            DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                        } else {
                            DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                                DocumentsContract.Document.FLAG_SUPPORTS_DELETE or
                                DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                        }
                        else -> null
                    },
                )
            }
        }
    }

    class Session internal constructor(val directory: File, val id: String) {
        val chapter = checked(File(directory, "chapter")).apply { check(mkdir()) }
        val page = checked(File(chapter, "target.png"))
        val failNextPageWrite = AtomicBoolean(false)
        val failNextWriteDocumentId = AtomicReference<String?>(null)
        val writeModes = CopyOnWriteArrayList<Pair<String, String>>()
        val createRequests = CopyOnWriteArrayList<Pair<String, String>>()
        val reads = CopyOnWriteArrayList<String>()

        fun documentId(file: File): String = "$id|${checked(file).relativeTo(directory).invariantSeparatorsPath}"

        fun checked(file: File): File {
            val root = directory.absoluteFile
            val absolute = file.absoluteFile
            // Canonical equality rejects symlinks in addition to traversal and sibling prefixes.
            require(root.canonicalFile == root && absolute.canonicalFile == absolute)
            require(absolute.toPath().startsWith(root.toPath()))
            return absolute
        }

        fun close() {
            check(sessions.remove(id, this))
            val root = checked(directory)
            check(root.name == "saf-alias-$id" && root.parentFile?.name == "cache")
            check(root.deleteRecursively()) { "Cannot remove owned SAF fixture" }
            check(!root.exists())
        }
    }

    companion object {
        private val sessions = ConcurrentHashMap<String, Session>()
        private val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        fun createSession(context: Context): Session {
            check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit only" }
            val id = UUID.randomUUID().toString()
            val cache = context.cacheDir.canonicalFile
            val directory = File(cache, "saf-alias-$id")
            check(directory.parentFile == cache && directory.mkdir())
            return try {
                Session(directory, id).also { check(sessions.putIfAbsent(id, it) == null) }
            } catch (failure: Throwable) {
                try {
                    check(directory.canonicalFile.parentFile == cache && directory.name == "saf-alias-$id")
                    check(directory.deleteRecursively()) { "Cannot remove failed owned SAF setup" }
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

        private fun requireName(name: String) {
            require(name.isNotBlank() && name != "." && name != "..")
            require(name.none { it == '/' || it == '\\' || it == '|' || it.code < 32 || it.code == 127 })
        }
    }
}
