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
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
        session.childQueries.incrementAndGet()
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
            val pendingGate = session.pendingWriteGate.get()
            if (
                pendingGate != null && pendingGate.documentId == documentId &&
                session.pendingWriteGate.compareAndSet(pendingGate, null)
            ) {
                // The owned file already exists; only this exact session/document write waits.
                pendingGate.waitForRelease()
            }
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
            if (file == session.page) session.sourceImageOpens.incrementAndGet()
            if (file.name.startsWith("capture-")) session.captureHistoryOpens.incrementAndGet()
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
        check(file != session.directory) { "Cannot delete the fixture root" }
        if (session.recursiveDelete && file.isDirectory) {
            deleteOwnedSubtree(session, file)
        } else {
            check(file.delete()) { "Cannot delete fixture entry" }
        }
    }

    /** Explicit normal-directory semantics for one owned session; legacy fault sessions stay strict. */
    private fun deleteOwnedSubtree(session: Session, selected: File) {
        val root = session.checked(selected)
        check(root != session.directory && root.isDirectory)
        val pending = mutableListOf(root)
        val entries = mutableListOf<File>()
        fun checkedEntry(file: File): File = session.checked(file).also {
            check(it == root || it.toPath().startsWith(root.toPath()))
            check(!Files.isSymbolicLink(it.toPath()) && (it.isDirectory || it.isFile))
        }
        // Validate the entire bounded subtree before the first deletion. A newly appearing entry
        // subsequently makes its directory delete fail instead of broadening this saved inventory.
        while (pending.isNotEmpty()) {
            check(entries.size < MAX_RECURSIVE_DELETE_ENTRIES) { "Owned fixture deletion limit exceeded" }
            val entry = checkedEntry(pending.removeAt(pending.lastIndex))
            entries += entry
            if (entry.isDirectory) {
                val children = checkNotNull(entry.listFiles()) { "Cannot list owned fixture directory" }
                check(entries.size + pending.size + children.size <= MAX_RECURSIVE_DELETE_ENTRIES) {
                    "Owned fixture deletion limit exceeded"
                }
                pending += children
            }
        }
        for (entry in entries.asReversed()) {
            check(checkedEntry(entry).delete()) { "Cannot delete owned fixture subtree entry" }
        }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val (session, file) = resolve(documentId)
        requireName(displayName)
        check(file != session.directory)
        val destination = session.checked(File(requireNotNull(file.parentFile), displayName))
        val pending = session.pendingRenameObservation.get()
        if (
            pending != null && pending.documentId == documentId && pending.displayName == displayName &&
            session.pendingRenameObservation.compareAndSet(pending, null)
        ) {
            pending.observe()
        }
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

    /** Bounded one-shot gate for an owned provider write, with no effect on ordinary sessions. */
    class WriteGate internal constructor(val documentId: String) {
        private val enteredLatch = CountDownLatch(1)
        private val releaseLatch = CountDownLatch(1)
        private val entries = AtomicInteger()
        private val exits = AtomicInteger()
        private val timeout = AtomicBoolean(false)
        private val interruption = AtomicBoolean(false)

        val enteredCount: Int get() = entries.get()
        val exitedCount: Int get() = exits.get()
        val timedOut: Boolean get() = timeout.get()
        val interrupted: Boolean get() = interruption.get()

        fun awaitEntered(timeoutMillis: Long): Boolean {
            require(timeoutMillis in 1..10_000)
            return enteredLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        }

        fun release() {
            releaseLatch.countDown()
        }

        internal fun waitForRelease() {
            check(entries.incrementAndGet() == 1)
            enteredLatch.countDown()
            try {
                if (!releaseLatch.await(10, TimeUnit.SECONDS)) {
                    timeout.set(true)
                    throw FileNotFoundException("Owned provider write gate timed out")
                }
            } catch (failure: InterruptedException) {
                interruption.set(true)
                Thread.currentThread().interrupt()
                throw FileNotFoundException("Owned provider write gate interrupted").apply { initCause(failure) }
            } finally {
                exits.incrementAndGet()
            }
        }
    }

    /** One exact owned-document rename observation; the callback never waits or changes the file. */
    class RenameObservation internal constructor(
        val documentId: String,
        val displayName: String,
        callback: () -> Unit,
    ) {
        private val callback = AtomicReference<(() -> Unit)?>(callback)

        internal fun observe() = checkNotNull(callback.getAndSet(null)).invoke()

        internal fun discard() {
            callback.set(null)
        }
    }

    class Session @JvmOverloads internal constructor(
        val directory: File,
        val id: String,
        val recursiveDelete: Boolean = false,
    ) {
        val chapter = checked(File(directory, "chapter")).apply { check(mkdir()) }
        val page = checked(File(chapter, "target.png"))
        val failNextPageWrite = AtomicBoolean(false)
        val failNextWriteDocumentId = AtomicReference<String?>(null)
        val pendingWriteGate = AtomicReference<WriteGate?>(null)
        internal val pendingRenameObservation = AtomicReference<RenameObservation?>(null)
        val writeModes = CopyOnWriteArrayList<Pair<String, String>>()
        val createRequests = CopyOnWriteArrayList<Pair<String, String>>()
        val reads = CopyOnWriteArrayList<String>()
        val childQueries = AtomicInteger()
        val sourceImageOpens = AtomicInteger()
        val captureHistoryOpens = AtomicInteger()

        fun documentId(file: File): String = "$id|${checked(file).relativeTo(directory).invariantSeparatorsPath}"

        fun observeNextRename(file: File, displayName: String, callback: () -> Unit): RenameObservation {
            require(checked(file) != directory)
            requireName(displayName)
            return RenameObservation(documentId(file), displayName, callback).also {
                check(pendingRenameObservation.compareAndSet(null, it)) { "An owned rename observation is pending" }
            }
        }

        /** Call only after the fixture's own downloader jobs have settled. */
        fun clearRenameObservation(observation: RenameObservation) {
            val pending = pendingRenameObservation.get()
            check(pending == null || pending === observation)
            if (pending === observation) check(pendingRenameObservation.compareAndSet(observation, null))
            observation.discard()
        }

        fun gateNextWrite(file: File): WriteGate {
            require(checked(file) != directory)
            return WriteGate(documentId(file)).also {
                check(pendingWriteGate.compareAndSet(null, it)) { "An owned write gate is already pending" }
            }
        }

        fun checked(file: File): File {
            val root = directory.absoluteFile
            val absolute = file.absoluteFile
            // Canonical equality rejects symlinks in addition to traversal and sibling prefixes.
            require(root.canonicalFile == root && absolute.canonicalFile == absolute)
            require(absolute.toPath().startsWith(root.toPath()))
            return absolute
        }

        /** Call after owned IO settles; retained failures remain available to the host by cache path. */
        fun close(retainOwnedFiles: Boolean = false) {
            val root = checked(directory)
            check(root.name == "saf-alias-$id" && root.parentFile?.name == "cache")
            check(sessions.remove(id, this))
            if (retainOwnedFiles) return
            check(root.deleteRecursively()) { "Cannot remove owned SAF fixture" }
            check(!root.exists())
        }
    }

    companion object {
        private const val MAX_RECURSIVE_DELETE_ENTRIES = 256
        private val sessions = ConcurrentHashMap<String, Session>()
        private val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        @JvmOverloads
        fun createSession(context: Context, recursiveDelete: Boolean = false): Session {
            check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit only" }
            val id = UUID.randomUUID().toString()
            val cache = context.cacheDir.canonicalFile
            val directory = File(cache, "saf-alias-$id")
            check(directory.parentFile == cache && directory.mkdir())
            return try {
                Session(directory, id, recursiveDelete).also { check(sessions.putIfAbsent(id, it) == null) }
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
