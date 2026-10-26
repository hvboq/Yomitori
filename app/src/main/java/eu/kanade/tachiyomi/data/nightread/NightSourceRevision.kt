package eu.kanade.tachiyomi.data.nightread

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.CapturedSourceStore
import eu.kanade.tachiyomi.data.translation.PageIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Auxiliary invalidation only. This record never authorizes recovery, original ownership or a
 * completed translation. Call writers with the existing page lock held; it deliberately takes
 * no lock itself (journal recovery already owns that non-reentrant lock).
 *
 * Pending is durable and read back BEFORE touching visible pixels or selected Night materials.
 * A failed/unknown write leaves it pending. A verified successor or rollback gets a NEW UUID,
 * including rollback to byte-identical data, so same-size/mtime ABA cannot revive an old proof.
 */
internal object NightSourceRevision {
    private const val HEADER = "night-source-revision-v1"
    const val MAX_BYTES = 256
    private val UUID_HEX = Regex("[a-f0-9]{32}")

    enum class Kind { SOURCE, PUBLICATION }
    data class Revision(
        val id: String,
        val committed: Boolean,
        val capturedEpoch: String?,
        val kind: Kind = Kind.SOURCE,
        val publicationBaseId: String? = null,
        val publicationWitness: String? = null,
    )
    data class Change(val chapterUri: String, val page: String, val sequence: Long)
    private val changeSequence = AtomicLong()
    val sequence: Long get() = changeSequence.get()

    private val mutableChanges = MutableSharedFlow<Change>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes = mutableChanges.asSharedFlow()

    fun pageKey(page: String): String = sha256(page.toByteArray(Charsets.UTF_8))
    fun chapterKey(chapter: UniFile): String = PageIdentity.fromUri(chapter.uri).toString()
    fun fileName(page: String): String = "night-source-${pageKey(page)}.revision"
    fun changed(chapter: UniFile, page: String) {
        mutableChanges.tryEmit(Change(chapterKey(chapter), page, changeSequence.incrementAndGet()))
    }

    fun encode(value: Revision): ByteArray {
        require(UUID_HEX.matches(value.id))
        require(value.capturedEpoch == null || UUID_HEX.matches(value.capturedEpoch.replace("-", "")))
        require(
            if (value.kind == Kind.PUBLICATION) {
                !value.committed && value.publicationBaseId?.let(UUID_HEX::matches) == true &&
                    value.publicationWitness?.matches(Regex("[a-f0-9]{64}")) == true
            } else {
                value.publicationBaseId == null && value.publicationWitness == null
            },
        )
        return listOf(
            HEADER,
            if (value.committed) "committed" else "pending",
            value.id,
            value.capturedEpoch ?: "none",
            value.kind.name,
            value.publicationBaseId ?: "none",
            value.publicationWitness ?: "none",
            "",
        ).joinToString("\n").toByteArray(Charsets.US_ASCII).also { require(it.size <= MAX_BYTES) }
    }

    fun decode(bytes: ByteArray): Revision {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) throw IOException("Invalid Night source revision")
        val lines = bytes.toString(Charsets.US_ASCII).split('\n')
        if (lines.size != 8 || lines[0] != HEADER || lines[1] !in setOf("committed", "pending") ||
            !UUID_HEX.matches(lines[2]) || lines[7].isNotEmpty()
        ) {
            throw IOException("Invalid Night source revision")
        }
        val value = try {
            Revision(
                lines[2],
                lines[1] == "committed",
                lines[3].takeUnless { it == "none" },
                Kind.valueOf(lines[4]),
                lines[5].takeUnless { it == "none" },
                lines[6].takeUnless { it == "none" },
            ).also { if (!encode(it).contentEquals(bytes)) throw IOException("Noncanonical Night source revision") }
        } catch (failure: IllegalArgumentException) {
            throw IOException("Invalid Night source revision", failure)
        }
        return value
    }

    fun read(file: UniFile): Revision = decode(readExact(file, MAX_BYTES))

    class Mutation internal constructor(
        private val context: Context,
        private val chapter: UniFile,
        private val materials: UniFile,
        private val page: String,
        private val file: UniFile,
        private val pending: Revision,
    ) {
        /** Only call after the actual writer's own exact-byte readback/verified recovery. */
        fun committed() {
            if (read(file) != pending) throw IOException("Night source mutation changed concurrently")
            val epoch = capturedEpoch(materials, page)
            verifyPublicationSource(chapter, materials, page, pending, epoch)
            val value = Revision(newId(), true, epoch)
            writeVerified(context, file, value)
            changed(chapter, page)
        }
    }

    fun begin(
        context: Context,
        chapter: UniFile,
        materials: UniFile,
        page: String,
        allowUnsettledCapture: Boolean = false,
    ): Mutation {
        val name = fileName(page)
        val file = materials.findFile(name) ?: materials.createFile(name)
            ?: throw IOException("Cannot create Night source revision")
        if (!file.isFile || file.name != name) throw IOException("Night source revision is not an exact file")
        // A recapture recovery may have pending canonical history. It stays unknown until the
        // real capture store verifies its rollback/commit; this auxiliary file cannot settle it.
        val epoch = try {
            capturedEpoch(materials, page)
        } catch (failure: IOException) {
            if (!allowUnsettledCapture) throw failure
            null
        }
        val pending = Revision(newId(), false, epoch)
        writeVerified(context, file, pending)
        changed(chapter, page)
        return Mutation(context, chapter, materials, page, file, pending)
    }

    /** The caller holds the page lock and already proved this exact source/material witness. */
    fun beginPublication(
        context: Context,
        chapter: UniFile,
        materials: UniFile,
        page: String,
        witness: NightPolicy.Witness,
    ): Mutation {
        val file = materials.findFile(fileName(page)) ?: throw IOException("Missing Night source revision")
        val current = read(file)
        if (!current.committed) throw IOException("Night source is pending")
        val pending = Revision(newId(), false, current.capturedEpoch, Kind.PUBLICATION, current.id, witness.digest)
        verifyPublicationSource(chapter, materials, page, pending, capturedEpoch(materials, page))
        writeVerified(context, file, pending)
        changed(chapter, page)
        return Mutation(context, chapter, materials, page, file, pending)
    }

    inline fun <T> mutate(
        context: Context,
        chapter: UniFile,
        materials: UniFile,
        page: String,
        writeVerified: () -> T,
    ): T {
        val mutation = begin(context, chapter, materials, page)
        val result = writeVerified()
        mutation.committed()
        return result
    }

    /** Initial baseline creation is allowed only by a producer already holding the page lock. */
    fun ensureCommitted(context: Context, chapter: UniFile, materials: UniFile, page: String): Revision {
        val existing = materials.findFile(fileName(page))
        if (existing != null) {
            val value = read(existing)
            if (!value.committed) {
                // Explicit Night retry can repair ONLY its own publication barrier after proving
                // the protected source/materials and canonical epoch are unchanged. Source-write
                // pending has no such authority and can only be lifted by real verified recovery.
                if (value.kind != Kind.PUBLICATION) {
                    throw IOException("Night source revision requires verified mutation/recovery")
                }
                val epoch = capturedEpoch(materials, page)
                verifyPublicationSource(chapter, materials, page, value, epoch)
                val committed = Revision(newId(), true, epoch)
                writeVerified(context, existing, committed)
                changed(chapter, page)
                return committed
            }
            if (value.capturedEpoch != capturedEpoch(materials, page)) throw IOException("Night capture epoch changed")
            return value
        }
        val mutation = begin(context, chapter, materials, page)
        mutation.committed()
        return read(materials.findFile(fileName(page)) ?: throw IOException("Night source revision disappeared"))
    }

    /** Metadata-only canonical capture validation. Readers call this ONCE per explicit refresh. */
    fun capturedEpoch(materials: UniFile, page: String): String? {
        val files = inventory(materials)
        return capturedEpoch(files, page)
    }

    fun capturedEpoch(files: Map<String, UniFile>, page: String): String? {
        var bytesRead = 0
        val io = object : CapturedSourceStore.Io {
            override fun materialNames(): Set<String> = files.keys
            override fun readMaterial(name: String, maxBytes: Int): ByteArray? {
                val file = files[name] ?: return null
                val budget = minOf(maxBytes, MAX_AUTHORITY_BYTES - bytesRead)
                if (budget <= 0) throw IOException("Night capture history exceeds budget")
                return readExact(file, budget).also { bytesRead += it.size }
            }
            override fun readPage(pageName: String, maxBytes: Int): ByteArray? =
                error("Night never hashes source images")
            override fun createMaterial(name: String, bytes: ByteArray): Boolean = error("Read-only Night authority")
            override fun replaceMaterial(name: String, bytes: ByteArray): Boolean = error("Read-only Night authority")
            override fun deleteMaterial(name: String): Boolean = error("Read-only Night authority")
            override fun replacePage(pageName: String, bytes: ByteArray): Boolean = error("Read-only Night authority")
        }
        return CapturedSourceStore(io).sourceEpoch(page)
    }

    fun inventory(materials: UniFile): Map<String, UniFile> {
        val listed = materials.listFiles() ?: throw IOException("Cannot list Night materials")
        if (listed.size > 32768) throw IOException("Night inventory exceeds budget")
        val files = LinkedHashMap<String, UniFile>()
        var size = 0
        for (file in listed) {
            val name = file.name ?: throw IOException("Unnamed Night material")
            size += name.toByteArray(Charsets.UTF_8).size
            if (size > 2 * 1024 * 1024 || files.put(name, file) != null) throw IOException("Ambiguous Night inventory")
        }
        return files
    }

    fun readExact(file: UniFile, maxBytes: Int): ByteArray {
        if (!file.isFile || file.length() !in 0..maxBytes.toLong()) throw IOException("Night record exceeds bounds")
        val input = file.openInputStream() ?: throw IOException("Cannot open Night record")
        return input.use { readBounded(it, maxBytes) }
    }

    fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maxBytes, 1024))
        val buffer = ByteArray(minOf(maxBytes + 1, 4096))
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (count == 0) {
                val one = input.read()
                if (one < 0) return output.toByteArray()
                if (output.size() >= maxBytes) throw IOException("Night record exceeds bounds")
                output.write(one)
            } else {
                if (output.size().toLong() + count > maxBytes) throw IOException("Night record exceeds bounds")
                output.write(buffer, 0, count)
            }
        }
    }

    private fun writeVerified(context: Context, file: UniFile, revision: Revision) {
        val bytes = encode(revision)
        val output = if (file.uri.scheme == "file") {
            file.openOutputStream()
        } else {
            context.contentResolver.openOutputStream(file.uri, "wt")
        }
        (output ?: throw IOException("Cannot write Night source revision")).use { it.write(bytes) }
        if (!readExact(file, MAX_BYTES).contentEquals(bytes)) throw IOException("Cannot verify Night source revision")
    }

    private fun verifyPublicationSource(
        chapter: UniFile,
        materials: UniFile,
        page: String,
        revision: Revision,
        epoch: String?,
    ) {
        if (revision.kind != Kind.PUBLICATION) return
        if (revision.capturedEpoch != epoch || NightPolicy.protectedWitness(
                chapter,
                materials,
                page,
                checkNotNull(revision.publicationBaseId),
                epoch,
            ).digest != revision.publicationWitness
        ) {
            throw IOException("Night publication source/materials changed")
        }
    }

    fun newId(): String = UUID.randomUUID().toString().replace("-", "")

    /** Ordinary optional IO may warn; cancellation/Error identity always survives cleanup. */
    fun settleFailure(failure: Throwable, rollback: () -> Unit) {
        val cleanup = try {
            rollback()
            null
        } catch (caught: Throwable) {
            caught
        }
        if (failure is CancellationException || failure is Error) {
            if (cleanup != null && cleanup !== failure) failure.addSuppressed(cleanup)
            throw failure
        }
        if (cleanup is CancellationException || cleanup is Error) {
            if (cleanup !== failure) cleanup.addSuppressed(failure)
            throw cleanup
        }
        if (cleanup != null && cleanup !== failure) failure.addSuppressed(cleanup)
    }
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private const val MAX_AUTHORITY_BYTES = 256 * 1024
}
