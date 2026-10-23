package eu.kanade.tachiyomi.data.translation

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/**
 * Explicit replacement of a captured OCR source. The caller owns page-lock -> manifest-lock and
 * validates the new image before entering this store. Archives are immutable, flat, and retained.
 * No image is sent to a service. Readers must recover pending transactions before using materials.
 * [sourceEpoch] reads metadata only: later translation is allowed to change the visible image.
 * A corrupt prepare fails closed and requires explicit verified repair; blobs alone cannot
 * establish its previous inventory, epoch, or completion. Fatal errors retain durable preparation
 * for recovery on the next entry rather than trying further allocations during an OOM.
 */
internal class CapturedSourceStore(private val io: Io) {
    interface Io {
        fun materialNames(): Set<String>
        fun readMaterial(name: String, maxBytes: Int): ByteArray?
        fun createMaterial(name: String, bytes: ByteArray): Boolean
        fun replaceMaterial(name: String, bytes: ByteArray): Boolean
        fun deleteMaterial(name: String): Boolean
        fun readPage(pageName: String, maxBytes: Int): ByteArray?
        fun replacePage(pageName: String, bytes: ByteArray): Boolean
    }

    data class Receipt(val epoch: String, val archiveId: String)

    @Serializable
    private data class Blob(val name: String, val length: Int, val sha256: String)

    @Serializable
    private data class Material(val name: String, val blob: Blob)

    @Serializable
    private data class Prepare(
        val version: Int = 1,
        val pageName: String,
        val epoch: String,
        val parentEpoch: String?,
        val sequence: Long,
        val previousComplete: Boolean,
        val oldPage: Blob,
        val newSource: Blob,
        val materials: List<Material>,
    )

    @Serializable
    private data class Marker(val kind: String, val prepareSha256: String)

    private data class Entry(val id: String, val prepare: Prepare, val bytes: ByteArray, val names: Set<String>)
    private data class Scan(val epoch: String?, val sequence: Long, val entries: List<Entry>, val pending: Entry?)

    /** Null means no successful explicit recapture. Corrupt or pending authority is never ignored. */
    fun sourceEpoch(pageName: String): String? = scan(pageName).also {
        if (it.pending != null) fail("Captured source requires recovery")
    }.epoch

    fun hasPending(pageName: String): Boolean = scan(pageName).pending != null

    /** Metadata-only chapter guard; does not read any archived or active image. */
    fun pendingPages(): Set<String> {
        val names = materialNames()
        val prepares = names.filter { it.startsWith(PREFIX) && it.endsWith(".prepare") }
        val pages = prepares.map { name ->
            requireCaptureName(name)
            decodePrepare(readRecord(name)).also { validatePrepare(it, name.removeSuffix(".prepare")) }.pageName
        }.toSet()
        // A marker without its prepared authority cannot be assigned safely to a page.
        names.filter { it.startsWith(PREFIX) && MARKER_SUFFIXES.any(it::endsWith) }.forEach { name ->
            requireCaptureName(name)
            if (name.substringBeforeLast('.') + ".prepare" !in names) fail("Missing captured source authority")
        }
        return pages.filterTo(mutableSetOf()) { scan(it, names).pending != null }
    }

    /**
     * All retired names must be exact, existing, page-owned material files supplied by the caller.
     * The manifest callback changes only this page, using a fresh view of other pages. The caller's
     * operation fence must advance even when this method fails before durable preparation.
     */
    fun replace(
        pageName: String,
        newSourceBytes: ByteArray,
        retiredMaterialNames: Set<String>,
        previousComplete: Boolean,
        setComplete: (Boolean) -> Unit,
        checkCancellation: () -> Unit = {},
    ): Receipt {
        requireName(pageName)
        requireImage(newSourceBytes)
        val initialNames = materialNames()
        val initial = scan(pageName, initialNames)
        if (initial.pending != null) fail("Captured source requires recovery")
        if (initialNames.filter { it.startsWith(prefix(pageName)) }.map { it.substringBeforeLast('.') }
                .distinct().size >= MAX_TRANSACTIONS
        ) {
            fail("Captured source archive limit reached")
        }
        validateInventory(pageName, retiredMaterialNames, initialNames)
        checkCancellation()
        val epoch = UUID.randomUUID().toString().replace("-", "")
        val id = prefix(pageName) + epoch
        if (initialNames.any { it.startsWith("$id.") }) fail("Captured source archive collision")
        var total = newSourceBytes.size.toLong()
        val oldPage = archivePage("$id.old-page", pageName, newSourceBytes.size)
        total += oldPage.length
        val newSource = archive("$id.new", newSourceBytes)
        val oldMaterials = retiredMaterialNames.sorted().mapIndexed { index, name ->
            checkCancellation()
            val bytes = io.readMaterial(name, MAX_IMAGE_BYTES) ?: fail("Missing retired captured material")
            if (bytes.size > MAX_IMAGE_BYTES) fail("Captured material is too large")
            total += bytes.size
            requireTotal(total)
            Material(name, archive("$id.old-${index.toString().padStart(3, '0')}", bytes))
        }
        val prepared = Prepare(
            pageName = pageName,
            epoch = epoch,
            parentEpoch = initial.epoch,
            sequence = initial.sequence + 1,
            previousComplete = previousComplete,
            oldPage = oldPage,
            newSource = newSource,
            materials = oldMaterials,
        )
        validatePrepare(prepared, id)
        val preparedBytes = encodePrepare(prepared)
        val entry = Entry(id, prepared, preparedBytes, emptySet())
        // Re-read one file at a time. Never hold all historical images in memory together.
        verifyPrevious(entry)
        checkCancellation()
        // Any durable prepare without a terminal marker requires recovery, even when its pending
        // marker is absent. Otherwise a lost pending marker could expose a changed OCR generation.
        createVerified("$id.prepare", preparedBytes, MAX_RECORD_BYTES)
        var pendingAttempted = false
        try {
            pendingAttempted = true
            createVerified("$id.pending", marker("pending", preparedBytes), MAX_RECORD_BYTES)
            checkCancellation()
            replaceMaterialVerified(OriginalMaterial.exactName(pageName), readBlob(newSource))
            checkCancellation()
            replacePageVerified(pageName, readBlob(newSource))
            checkCancellation()
            for (material in oldMaterials) {
                if (material.name != OriginalMaterial.exactName(pageName)) deleteVerified(material.name)
                checkCancellation()
            }
            setComplete(false)
            checkCancellation()
            verifyNew(entry)
            checkCancellation()
            commit(entry)
            return Receipt(epoch, id)
        } catch (error: Exception) {
            // An unreadable final marker may already be a durable commit. Do not undo it blindly.
            if (pendingAttempted && error !is CommitOutcomeUnknown) {
                try {
                    rollback(entry, setComplete)
                } catch (rollbackFailure: Exception) {
                    error.addSuppressed(rollbackFailure)
                }
            }
            throw error
        }
    }

    /** Recovery deliberately ignores cancellation and retains all archival evidence. */
    fun recover(pageName: String, setComplete: (Boolean) -> Unit): Boolean {
        val pending = scan(pageName).pending ?: return false
        rollback(pending, setComplete)
        if (scan(pageName).pending != null) fail("Captured source recovery remains pending")
        return true
    }

    private fun verifyPrevious(entry: Entry) {
        val prepare = entry.prepare
        verifyArchive(prepare.oldPage)
        verifyPage(prepare.pageName, prepare.oldPage)
        for (material in prepare.materials) {
            verifyArchive(material.blob)
            verifyMaterial(material.name, material.blob)
        }
        val exact = OriginalMaterial.exactName(prepare.pageName)
        if (prepare.materials.none { it.name == exact } && exact in materialNames()) {
            fail("Captured source changed before preparation")
        }
    }

    private fun verifyNew(entry: Entry) {
        val source = entry.prepare.newSource
        verifyArchive(source)
        verifyPage(entry.prepare.pageName, source)
        verifyMaterial(OriginalMaterial.exactName(entry.prepare.pageName), source)
        val names = materialNames()
        if (entry.prepare.materials.any {
                it.name != OriginalMaterial.exactName(entry.prepare.pageName) && it.name in names
            }
        ) {
            fail("Retired captured material remains active")
        }
    }

    private fun rollback(entry: Entry, setComplete: (Boolean) -> Unit) {
        // Verify every archive before changing anything during recovery.
        readBlob(entry.prepare.oldPage)
        entry.prepare.materials.forEach { readBlob(it.blob) }
        for (material in entry.prepare.materials) replaceMaterialVerified(material.name, readBlob(material.blob))
        val exact = OriginalMaterial.exactName(entry.prepare.pageName)
        if (entry.prepare.materials.none { it.name == exact }) deleteVerified(exact)
        replacePageVerified(entry.prepare.pageName, readBlob(entry.prepare.oldPage))
        setComplete(entry.prepare.previousComplete)
        verifyPrevious(entry)
        val name = "${entry.id}.rollback"
        val bytes = marker("rollback", entry.bytes)
        if (name in materialNames()) {
            replaceMaterialVerified(name, bytes)
        } else {
            createVerified(name, bytes, MAX_RECORD_BYTES)
        }
    }

    private fun commit(entry: Entry) {
        val name = "${entry.id}.commit"
        val bytes = marker("commit", entry.bytes)
        var writeFailure: Exception? = null
        try {
            if (!io.createMaterial(name, bytes)) writeFailure = IOException("Cannot write capture commit")
        } catch (error: Exception) {
            writeFailure = error
        }
        val actual = try {
            io.readMaterial(name, MAX_RECORD_BYTES)
        } catch (error: Exception) {
            throw CommitOutcomeUnknown(error).also { writeFailure?.let(it::addSuppressed) }
        }
        if (actual?.contentEquals(bytes) != true) {
            throw IOException("Captured source commit did not verify", writeFailure)
        }
        // Exact verified bytes are the commit point, even when the provider threw after writing.
    }

    private fun scan(pageName: String, allNames: Set<String> = materialNames()): Scan {
        requireName(pageName)
        val pagePrefix = prefix(pageName)
        val related = allNames.filter { it.startsWith(pagePrefix) }
        related.forEach(::requireCaptureName)
        val groups = related.groupBy { it.substringBeforeLast('.') }
        if (groups.size > MAX_TRANSACTIONS) fail("Captured source archive limit reached")
        val entries = groups.mapNotNull { (id, group) ->
            val prepareName = "$id.prepare"
            if (prepareName !in group) {
                if (group.any { name -> MARKER_SUFFIXES.any(name::endsWith) }) {
                    fail("Missing captured source authority")
                }
                return@mapNotNull null // A crash while copying an archive authorized no active write.
            }
            val bytes = readRecord(prepareName)
            val prepare = decodePrepare(bytes)
            validatePrepare(prepare, id)
            if (prepare.pageName != pageName) fail("Captured source belongs to another page")
            Entry(id, prepare, bytes, group.toSet())
        }.sortedBy { it.prepare.sequence }
        if (entries.map { it.prepare.sequence }.distinct().size != entries.size) fail("Forked captured source sequence")
        var epoch: String? = null
        var pending: Entry? = null
        for (entry in entries) {
            val rolledBack = validMarker(entry, "rollback")
            val committed = validMarker(entry, "commit")
            if (committed && "${entry.id}.rollback" in entry.names) fail("Conflicting captured source authority")
            val started = validMarker(entry, "pending")
            if (entry.prepare.parentEpoch != epoch) fail("Forked captured source epoch")
            if (pending != null) fail("Captured source has a later transaction before recovery")
            when {
                committed -> {
                    if (!started) fail("Captured source commit has no preparation marker")
                    epoch = entry.prepare.epoch
                }
                rolledBack -> Unit
                else -> pending = entry
            }
        }
        return Scan(epoch, entries.lastOrNull()?.prepare?.sequence ?: 0, entries, pending)
    }

    private fun validMarker(entry: Entry, kind: String): Boolean {
        val name = "${entry.id}.$kind"
        if (name !in entry.names) return false
        val expected = marker(kind, entry.bytes)
        val actual = io.readMaterial(name, MAX_RECORD_BYTES) ?: fail("Missing captured source marker")
        if (actual.contentEquals(expected)) return true
        // Torn pending/commit records retain valid preparation for deterministic rollback. A torn
        // rollback is likewise still pending. A valid rollback can supersede either torn marker.
        return false
    }

    private fun archive(name: String, bytes: ByteArray): Blob {
        createVerified(name, bytes, MAX_IMAGE_BYTES)
        return Blob(name, bytes.size, digest(bytes))
    }

    private fun archivePage(name: String, pageName: String, newSourceLength: Int): Blob {
        // Keep this array out of the transaction's stack frame while later materials are copied.
        val bytes = io.readPage(pageName, MAX_IMAGE_BYTES) ?: fail("Cannot read captured page")
        requireImage(bytes)
        requireTotal(newSourceLength.toLong() + bytes.size)
        return archive(name, bytes)
    }

    private fun verifyArchive(blob: Blob) {
        readBlob(blob)
    }

    private fun readBlob(blob: Blob): ByteArray {
        val bytes = io.readMaterial(blob.name, MAX_IMAGE_BYTES) ?: fail("Missing captured source archive")
        if (bytes.size != blob.length || digest(bytes) != blob.sha256) {
            fail("Captured source archive failed verification")
        }
        return bytes
    }

    private fun createVerified(name: String, bytes: ByteArray, limit: Int) {
        if (name in materialNames()) fail("Captured source archive already exists")
        var failure: Exception? = null
        try {
            if (!io.createMaterial(name, bytes)) failure = IOException("Cannot create captured source archive")
        } catch (error: Exception) {
            failure = error
        }
        if (io.readMaterial(name, limit)?.contentEquals(bytes) != true) {
            throw IOException("Captured source archive did not verify", failure)
        }
    }

    private fun replaceMaterialVerified(name: String, bytes: ByteArray) {
        val success = if (name in materialNames()) io.replaceMaterial(name, bytes) else io.createMaterial(name, bytes)
        if (!success) fail("Cannot replace captured material")
        verifyMaterial(name, bytes)
    }

    private fun verifyMaterial(name: String, bytes: ByteArray) {
        if (io.readMaterial(name, MAX_IMAGE_BYTES)?.contentEquals(bytes) != true) {
            fail("Captured material did not verify")
        }
    }

    private fun verifyMaterial(name: String, blob: Blob) {
        val bytes = io.readMaterial(name, MAX_IMAGE_BYTES) ?: fail("Cannot read captured material")
        if (bytes.size != blob.length || digest(bytes) != blob.sha256) fail("Captured material did not verify")
    }

    private fun replacePageVerified(pageName: String, bytes: ByteArray) {
        if (!io.replacePage(pageName, bytes)) fail("Cannot replace captured page")
        verifyPage(pageName, bytes)
    }

    private fun verifyPage(pageName: String, bytes: ByteArray) {
        if (io.readPage(pageName, MAX_IMAGE_BYTES)?.contentEquals(bytes) != true) fail("Captured page did not verify")
    }

    private fun verifyPage(pageName: String, blob: Blob) {
        val bytes = io.readPage(pageName, MAX_IMAGE_BYTES) ?: fail("Cannot read captured page")
        if (bytes.size != blob.length || digest(bytes) != blob.sha256) fail("Captured page did not verify")
    }

    private fun deleteVerified(name: String) {
        if (name !in materialNames()) return
        if (!io.deleteMaterial(name) || name in materialNames()) fail("Cannot retire captured material")
    }

    private fun materialNames(): Set<String> = io.materialNames().also {
        if (it.count { name -> name.startsWith(PREFIX) } > MAX_CAPTURE_FILES) fail("Too many captured source files")
    }

    private fun readRecord(name: String): ByteArray = io.readMaterial(name, MAX_RECORD_BYTES)
        ?.also { if (it.size > MAX_RECORD_BYTES) fail("Captured source record is too large") }
        ?: fail("Missing captured source record")

    private class CommitOutcomeUnknown(cause: Exception) :
        IOException("Captured source commit outcome is unknown", cause)

    companion object {
        private const val PREFIX = "capture-"
        private const val MAX_TRANSACTIONS = 256
        private const val MAX_CAPTURE_FILES = 32768
        private const val MAX_MATERIALS = 128
        private const val MAX_RECORD_BYTES = 128 * 1024
        private const val MAX_IMAGE_BYTES = 192 * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
        private const val HEADER = "YOMITORI_CAPTURE_1\n"
        private val JSON = Json { encodeDefaults = true }
        private val HEX = Regex("[0-9a-f]{64}")
        private val EPOCH = Regex("[0-9a-f]{32}")
        private val CAPTURE_NAME = Regex(
            "capture-[0-9a-f]{64}-[0-9a-f]{32}\\.(prepare|pending|commit|rollback|new|old-page|old-[0-9]{3})",
        )
        private val MARKER_SUFFIXES = listOf(".pending", ".commit", ".rollback")

        private fun prefix(pageName: String): String = PREFIX + digest(pageName.toByteArray(Charsets.UTF_8)) + "-"

        private fun requireCaptureName(name: String) {
            if (!CAPTURE_NAME.matches(name)) fail("Invalid captured source filename")
        }

        private fun requireName(name: String) {
            if (name.isBlank() || name.length > 240 || name != name.trim() || name == "." || name == ".." ||
                name.any { it == '/' || it == '\\' || it == ':' || it.code < 32 }
            ) {
                fail("Invalid captured source name")
            }
        }

        private fun requireImage(bytes: ByteArray) {
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) fail("Invalid captured source length")
        }

        private fun requireTotal(bytes: Long) {
            if (bytes > MAX_TOTAL_BYTES) fail("Captured source archive exceeds byte limit")
        }

        private fun validateInventory(page: String, names: Set<String>, existing: Set<String>) {
            val exactOriginal = OriginalMaterial.exactName(page)
            requireName(exactOriginal)
            if (names.size > MAX_MATERIALS) fail("Too many retired captured materials")
            names.forEach {
                requireName(it)
                if (it.startsWith(PREFIX) || it !in existing) fail("Invalid retired captured material")
            }
            if (names.map { it.lowercase() }.distinct().size != names.size) fail("Ambiguous captured material names")
            if (existing.any { it.equals(exactOriginal, ignoreCase = true) && it != exactOriginal }) {
                fail("Ambiguous captured original name")
            }
            for (original in setOf(OriginalMaterial.exactName(page), OriginalMaterial.legacyName(page))) {
                if (existing.any { it.equals(original, ignoreCase = true) && it !in names }) {
                    fail("Original is missing from captured material inventory")
                }
            }
        }

        private fun validatePrepare(prepare: Prepare, id: String) {
            requireName(prepare.pageName)
            if (prepare.version != 1 || !EPOCH.matches(prepare.epoch) ||
                prepare.parentEpoch?.let { !EPOCH.matches(it) } == true || prepare.sequence < 1 ||
                id != prefix(prepare.pageName) + prepare.epoch || prepare.materials.size > MAX_MATERIALS
            ) {
                fail("Invalid captured source authority")
            }
            val blobs = listOf(prepare.oldPage, prepare.newSource) + prepare.materials.map { it.blob }
            if (prepare.oldPage.name != "$id.old-page" || prepare.newSource.name != "$id.new" ||
                blobs.map { it.name }.distinct().size != blobs.size ||
                prepare.materials.map { it.name.lowercase() }.distinct().size != prepare.materials.size
            ) {
                fail("Invalid captured source archive inventory")
            }
            prepare.materials.forEachIndexed { index, material ->
                requireName(material.name)
                if (material.name.startsWith(PREFIX) ||
                    material.blob.name != "$id.old-${index.toString().padStart(3, '0')}"
                ) {
                    fail("Invalid captured material authority")
                }
            }
            for (blob in blobs) {
                requireCaptureName(blob.name)
                if (blob.length !in 0..MAX_IMAGE_BYTES || !HEX.matches(blob.sha256)) {
                    fail("Invalid captured archive descriptor")
                }
            }
            if (prepare.oldPage.length == 0 || prepare.newSource.length == 0) fail("Empty captured source")
            requireTotal(blobs.sumOf { it.length.toLong() })
        }

        private fun marker(kind: String, prepareBytes: ByteArray): ByteArray = seal(
            JSON.encodeToString(Marker(kind, digest(prepareBytes))),
        )

        private fun encodePrepare(prepare: Prepare): ByteArray = seal(JSON.encodeToString(prepare))

        private fun decodePrepare(bytes: ByteArray): Prepare = try {
            JSON.decodeFromString<Prepare>(unseal(bytes))
        } catch (error: Exception) {
            throw IOException("Invalid captured source record", error)
        }

        private fun seal(payload: String): ByteArray {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            return (HEADER + digest(bytes) + "\n").toByteArray(Charsets.UTF_8).plus(bytes).also {
                if (it.size > MAX_RECORD_BYTES) fail("Captured source record is too large")
            }
        }

        private fun unseal(bytes: ByteArray): String {
            if (bytes.size > MAX_RECORD_BYTES) fail("Captured source record is too large")
            val text = bytes.toString(Charsets.UTF_8)
            val separator = text.indexOf('\n', HEADER.length)
            if (!text.startsWith(HEADER) || separator != HEADER.length + 64) fail("Incomplete captured source record")
            val payload = text.substring(separator + 1)
            if (digest(payload.toByteArray(Charsets.UTF_8)) != text.substring(HEADER.length, separator)) {
                fail("Captured source record checksum mismatch")
            }
            return payload
        }

        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        private fun fail(message: String): Nothing = throw IOException(message)
    }
}
