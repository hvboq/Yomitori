package eu.kanade.tachiyomi.data.translation

import android.content.Context
import com.hippo.unifile.UniFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Page-lock-owned journal for partial OCR results. Originals belong to [OriginalMaterial]; this
 * store only verifies them and never creates or replaces them. SAF rename is deliberately unused.
 * A commit consists of immutable output/mask/record files followed by a verified WIP, replacement
 * of the visible page, and a final commit record. Before that last record, recovery restores the
 * previous committed snapshot (or the explicitly captured first baseline, otherwise the original).
 * The OCR original retains its exact/legacy/unknown provenance and is never promoted by copying.
 * After the commit point, a changed page is an external
 * modification and must not be silently replaced, even if WIP cleanup was interrupted.
 *
 * Call load and commit under the shared page lock. Inference may run outside it; `baseRevision`
 * rejects results computed from an obsolete generation when the lock is acquired again.
 */
internal class PartialPageStore(private val io: Io) {
    interface Io {
        fun materialNames(): Set<String>
        fun readMaterial(name: String, maxBytes: Int): ByteArray?
        fun createMaterial(name: String, bytes: ByteArray): Boolean
        fun deleteMaterial(name: String): Boolean
        fun readPage(name: String, maxBytes: Int): ByteArray?
        fun replacePage(name: String, bytes: ByteArray): Boolean
    }

    @Serializable
    enum class OriginalProvenance { EXACT_BYTES, LEGACY_REENCODED, WEBP_COMPATIBLE_UNKNOWN }

    @Serializable
    data class Original(
        val name: String,
        val sha256: String,
        val length: Long,
        val provenance: OriginalProvenance = OriginalProvenance.EXACT_BYTES,
    )

    /** Explicit capture of the visible output before the first journal generation exists. */
    data class InitialBaseline(val bytes: ByteArray, val expectedTargetSha256: String)

    @Serializable
    data class Baseline(val revision: String, val sha256: String, val length: Int)

    @Serializable
    data class State(
        val pageName: String,
        val revision: String,
        val baseRevision: String?,
        val generation: Long,
        val original: Original,
        val previousOutputSha256: String,
        val outputSha256: String,
        val outputLength: Int,
        val rawMaskSha256: String,
        val rawMaskLength: Int,
        val width: Int,
        val height: Int,
        val progressPayload: String,
        val complete: Boolean,
        val initialBaseline: Baseline? = null,
        /** Caller-owned safe JSON. Credentials must never be placed in this payload. */
        val safeContextPayload: String? = null,
    ) {
        val outputSnapshotName: String get() = names(pageName, revision).output
        val rawMaskSnapshotName: String get() = names(pageName, revision).mask
        val initialBaselineSnapshotName: String? get() = initialBaseline?.let { names(pageName, it.revision).baseline }
    }

    /**
     * Read and validate the latest committed state and its original, output, mask, and visible page.
     * A verified unfinished WIP is rolled back unless [recover] is false. Recovery deliberately
     * ignores cancellation: leaving a truncated visible page is worse than completing a small copy.
     * Corruption and externally replaced pages throw [IOException]; null means no journal exists.
     */
    fun load(pageName: String, expectedOriginal: Original? = null, recover: Boolean = true): State? {
        requirePageName(pageName)
        var scan = scan(pageName)
        if (scan.invalidWips.isNotEmpty()) {
            if (!recover) fail("Page journal requires recovery")
            // A torn WIP never authorized a target write. It is safe to discard only when the
            // visible page is already exactly the last committed snapshot/original. Otherwise
            // its missing authority cannot be invented from a filename or a damaged record.
            val prepared = scan.invalidWips.mapNotNull { name ->
                runCatching {
                    readPreparedState(name.substringBeforeLast('.') + ".record", pageName)
                }.getOrNull()
            }
            val original = scan.latest?.original ?: expectedOriginal ?: prepared.map { it.original }
                .distinct().singleOrNull() ?: fail("Cannot establish original for torn recovery marker")
            if (expectedOriginal != null && original != expectedOriginal) fail("Original descriptor changed")
            val originalBytes = readOriginal(pageName, original)
            val committedLatest = scan.latest
            val previous = if (committedLatest != null) {
                readOutput(committedLatest)
            } else {
                if (prepared.any { it.baseRevision != null || it.generation != 1L } ||
                    prepared.map { it.initialBaseline }.distinct().size > 1
                ) {
                    fail("Cannot establish baseline for torn recovery marker")
                }
                prepared.singleOrNull()?.let(::readInitialBaseline) ?: originalBytes
            }
            val current = io.readPage(pageName, MAX_IMAGE_BYTES) ?: fail("Cannot read current page")
            if (!current.contentEquals(previous)) fail("Torn recovery marker cannot authorize page replacement")
            for (name in scan.invalidWips) {
                val revision = revisionFromName(name)
                val commitName = names(pageName, revision).commit
                if (revision !in scan.committed && commitName in io.materialNames()) deleteVerified(commitName)
                deleteVerified(name)
            }
            scan = scan(pageName)
        }
        val unfinished = scan.wips.filter { it.state.revision !in scan.committed }
        if (unfinished.size > 1) fail("Ambiguous pending page journal")
        if (unfinished.isNotEmpty()) {
            if (!recover) fail("Page journal requires recovery")
            val candidate = unfinished.single().state
            if (candidate.baseRevision != scan.latest?.revision) fail("Pending journal has a stale parent")
            val original = scan.latest?.original ?: candidate.original
            if (candidate.original != original) fail("Pending journal has a different original")
            if (expectedOriginal != null && original != expectedOriginal) fail("Original descriptor changed")
            val originalBytes = readOriginal(pageName, original)
            val previous = scan.latest?.let(::readOutput) ?: readInitialBaseline(candidate) ?: originalBytes
            replaceVerified(pageName, previous)
            // A torn final commit is not a commit. Remove it before removing recovery authority.
            val candidateNames = names(pageName, candidate.revision)
            if (candidateNames.commit in io.materialNames()) deleteVerified(candidateNames.commit)
            deleteVerified(candidateNames.wip)
            scan = scan(pageName)
        }
        val latest = scan.latest
        if (latest != null) {
            if (expectedOriginal != null && latest.original != expectedOriginal) fail("Original descriptor changed")
            readOriginal(pageName, latest.original)
            readInitialBaseline(latest)
            readOutput(latest)
            readRawMask(latest)
            val page = io.readPage(pageName, MAX_IMAGE_BYTES) ?: fail("Cannot read current page")
            if (digest(page) != latest.outputSha256 || page.size != latest.outputLength) {
                fail("Page changed outside its committed partial journal")
            }
        } else {
            rejectOrphanOutput(pageName)
        }
        // Completed WIPs never authorize overwriting a changed page. Validation above comes first.
        if (recover) scan.wips.forEach { deleteVerified(names(pageName, it.state.revision).wip) }
        return latest
    }

    /** Side-effect-free metadata for UI status only; never use it instead of load before skipping. */
    fun currentStateInfo(pageName: String): State? {
        requirePageName(pageName)
        val metadata = scan(pageName)
        if (metadata.invalidWips.isNotEmpty() || metadata.wips.any { it.state.revision !in metadata.committed }) {
            fail("Page journal requires recovery")
        }
        return metadata.latest
    }

    /**
     * Small metadata overlay, not a replacement for [load]. A pending or corrupt journal takes
     * precedence over every older completion checkpoint. Callers MUST call load before actually
     * skipping a completed page: this fast path does not read image/mask snapshots or target bytes.
     */
    fun applyDoneOverlay(done: Set<String>): Set<String> {
        val result = done.toMutableSet()
        val all = io.materialNames().filter { JOURNAL_NAME.matches(it) }
        val groups = all.filter { it.endsWith(".commit") || it.endsWith(".wip") }
            .groupBy { it.substring(0, PREFIX.length + 64) }
        if (groups.isEmpty()) return result
        val recordsByPrefix = all.filter { it.endsWith(".record") }.groupBy { it.take(PREFIX.length + 64) }
        val doneByPrefix = done.groupBy(::prefix)
        for ((pagePrefix, _) in groups) {
            val pages = recordsByPrefix[pagePrefix].orEmpty().mapNotNull { file ->
                runCatching { decodeState(io.readMaterial(file, MAX_RECORD_BYTES) ?: return@runCatching null) }
                    .getOrNull()?.pageName?.takeIf { prefix(it) == pagePrefix }
            }.toSet()
            // A broken record can still be associated with a manifest page by its hashed filename.
            val associated = pages + doneByPrefix[pagePrefix].orEmpty()
            for (page in associated) {
                val metadata = runCatching { scan(page) }.getOrNull()
                val pending = metadata == null || metadata.invalidWips.isNotEmpty() ||
                    metadata.wips.any { it.state.revision !in metadata.committed }
                if (!pending && metadata?.latest?.complete == true) result.add(page) else result.remove(page)
            }
        }
        return result
    }

    /**
     * Persist one generation. [progressPayload] is engine-owned JSON and [rawMaskBytes] is the
     * original DBNet PNG, not a mask already modified by inpainting. Successful translations can
     * therefore be rendered again from the preserved OCR source without OCR of translated pixels.
     * [initialBaseline] is only for the first generation: both its captured bytes and expected SHA
     * must match the visible target. It preserves a previous translation separately from the OCR
     * source, which may be a legacy image. Later generations always use the last committed output.
     * [safeContextPayload] is never inferred or inherited; callers explicitly preserve or replace it.
     * The cancellation hook is checked before the commit point; rollback never calls it.
     */
    fun commit(
        pageName: String,
        original: Original,
        width: Int,
        height: Int,
        progressPayload: String,
        outputBytes: ByteArray,
        rawMaskBytes: ByteArray,
        baseRevision: String?,
        complete: Boolean,
        initialBaseline: InitialBaseline? = null,
        safeContextPayload: String? = null,
        checkCancellation: () -> Unit = {},
    ): State {
        requirePageName(pageName)
        validateOriginal(pageName, original)
        validateDimensions(width, height)
        validatePayload(progressPayload)
        validateSafeContext(safeContextPayload)
        if (outputBytes.isEmpty() || outputBytes.size > MAX_IMAGE_BYTES) fail("Invalid output snapshot size")
        validateMask(rawMaskBytes, width, height)
        val previous = load(pageName, original)
        if (previous?.revision != baseRevision) fail("Stale partial page revision")
        if (previous != null &&
            initialBaseline != null
        ) {
            fail("Initial baseline is only valid for a first journal generation")
        }
        if (previous != null && (previous.width != width || previous.height != height)) fail("Page dimensions changed")
        val originalBytes = readOriginal(pageName, original)
        val current = io.readPage(pageName, MAX_IMAGE_BYTES) ?: fail("Cannot read current page")
        if (initialBaseline != null && (
                initialBaseline.bytes.isEmpty() || initialBaseline.bytes.size > MAX_IMAGE_BYTES ||
                    !SHA256.matches(initialBaseline.expectedTargetSha256) ||
                    digest(initialBaseline.bytes) != initialBaseline.expectedTargetSha256 ||
                    !current.contentEquals(initialBaseline.bytes) ||
                    digest(current) != initialBaseline.expectedTargetSha256
                )
        ) {
            fail("Captured initial output no longer matches the current page")
        }
        val previousBytes = previous?.let(::readOutput) ?: if (initialBaseline != null) current else originalBytes
        if (!current.contentEquals(previousBytes)) fail("Page changed before partial commit")
        val revision = UUID.randomUUID().toString()
        val state = State(
            pageName = pageName,
            revision = revision,
            baseRevision = baseRevision,
            generation = (previous?.generation ?: 0) + 1,
            original = original,
            previousOutputSha256 = digest(previousBytes),
            outputSha256 = digest(outputBytes),
            outputLength = outputBytes.size,
            rawMaskSha256 = digest(rawMaskBytes),
            rawMaskLength = rawMaskBytes.size,
            width = width,
            height = height,
            progressPayload = progressPayload,
            complete = complete,
            initialBaseline = previous?.initialBaseline ?: initialBaseline?.let {
                Baseline(revision, digest(previousBytes), previousBytes.size)
            },
            safeContextPayload = safeContextPayload,
        )
        val fileNames = names(pageName, state.revision)
        val record = encodeState(state)
        val wip = encodeRef(Reference("wip", pageName, state.revision, digest(record)))
        val committed = encodeRef(Reference("commit", pageName, state.revision, digest(record)))
        var authorized = false
        var commitAttempted = false
        try {
            checkCancellation()
            if (previous == null && state.initialBaseline != null) {
                createVerified(fileNames.baseline, previousBytes, MAX_IMAGE_BYTES)
                readInitialBaseline(state)
                checkCancellation()
            }
            createVerified(fileNames.output, outputBytes, MAX_IMAGE_BYTES)
            checkCancellation()
            createVerified(fileNames.mask, rawMaskBytes, MAX_IMAGE_BYTES)
            checkCancellation()
            createVerified(fileNames.record, record, MAX_RECORD_BYTES)
            checkCancellation()
            createVerified(fileNames.wip, wip, MAX_RECORD_BYTES)
            authorized = true
            checkCancellation()
            replaceVerified(pageName, outputBytes)
            checkCancellation()
            commitAttempted = true
            createVerified(fileNames.commit, committed, MAX_RECORD_BYTES)
        } catch (failure: Exception) {
            // A provider can throw after writing all bytes. A verified final record is the commit
            // point even if its writer reported failure; rolling it back would contradict history.
            val commitRead = runCatching { io.readMaterial(fileNames.commit, MAX_RECORD_BYTES) }
            val landedCommit = commitRead.getOrNull()
            if (landedCommit?.contentEquals(committed) == true) {
                runCatching { deleteVerified(fileNames.wip) }
                return state
            }
            // If the final marker may exist but cannot be read, rollback could contradict a
            // durable commit. Keep recovery authority until a later load can determine its state.
            if (commitAttempted && commitRead.isFailure) {
                failure.addSuppressed(commitRead.exceptionOrNull()!!)
                throw failure
            }
            if (authorized) {
                try {
                    replaceVerified(pageName, previousBytes)
                    if (fileNames.commit in io.materialNames()) deleteVerified(fileNames.commit)
                    deleteVerified(fileNames.wip)
                } catch (recoveryFailure: Exception) {
                    failure.addSuppressed(recoveryFailure)
                }
            }
            throw failure
        }
        // Cleanup is not part of the commit point. load verifies the visible page before retrying it.
        runCatching { deleteVerified(fileNames.wip) }
        return state
    }

    fun readOutput(state: State): ByteArray {
        validateState(state)
        return readVerified(state.outputSnapshotName, state.outputLength, state.outputSha256)
    }

    fun readRawMask(state: State): ByteArray {
        validateState(state)
        return readVerified(state.rawMaskSnapshotName, state.rawMaskLength, state.rawMaskSha256).also {
            validateMask(it, state.width, state.height)
        }
    }

    /** Verifies the preserved pre-journal output separately from the OCR source image. */
    fun readInitialBaseline(state: State): ByteArray? {
        validateState(state)
        val baseline = state.initialBaseline ?: return null
        return readVerified(names(state.pageName, baseline.revision).baseline, baseline.length, baseline.sha256)
    }

    private fun scan(pageName: String): Scan {
        val pagePrefix = prefix(pageName) + "-"
        val all = io.materialNames().filter { it.startsWith(pagePrefix) && JOURNAL_NAME.matches(it) }
        if (all.size > MAX_JOURNAL_FILES) fail("Too many partial page journal files")
        val wipNames = all.filter { it.endsWith(".wip") }
        val invalidWips = mutableListOf<String>()
        val wips = wipNames.mapNotNull { name ->
            try {
                readEntry(name, "wip", pageName)
            } catch (_: Exception) {
                invalidWips.add(name)
                null
            }
        }
        val wipRevisions = wipNames.map(::revisionFromName).toSet()
        val committed = all.filter { it.endsWith(".commit") }.mapNotNull { name ->
            try {
                readEntry(name, "commit", pageName)
            } catch (failure: Exception) {
                val revision = revisionFromName(name)
                if (revision !in wipRevisions) throw failure
                null // A verified WIP authorizes rollback of this torn, uncommitted final marker.
            }
        }.associateBy { it.state.revision }
        val ordered = committed.values.sortedBy { it.state.generation }
        var previous: State? = null
        for (entry in ordered) {
            val state = entry.state
            if (state.baseRevision != previous?.revision || state.generation != (previous?.generation ?: 0) + 1) {
                fail("Broken or forked partial page journal")
            }
            if (previous != null && (
                    state.original != previous.original || state.width != previous.width ||
                        state.height != previous.height || state.initialBaseline != previous.initialBaseline
                    )
            ) {
                fail("Partial page source changed between revisions")
            }
            if (state.previousOutputSha256 !=
                (previous?.outputSha256 ?: state.initialBaseline?.sha256 ?: state.original.sha256)
            ) {
                fail("Partial page parent output does not match")
            }
            previous = state
        }
        return Scan(previous, committed, wips, invalidWips)
    }

    private fun readEntry(name: String, kind: String, pageName: String): Entry {
        val reference = decodeRef(io.readMaterial(name, MAX_RECORD_BYTES) ?: fail("Missing journal reference"))
        if (reference.kind != kind || reference.pageName != pageName || !REVISION.matches(reference.revision)) {
            fail("Invalid journal reference")
        }
        val fileNames = names(pageName, reference.revision)
        if (name != if (kind == "wip") fileNames.wip else fileNames.commit) fail("Journal reference name mismatch")
        val bytes = io.readMaterial(fileNames.record, MAX_RECORD_BYTES) ?: fail("Missing page record")
        if (digest(bytes) != reference.recordSha256) fail("Page record checksum mismatch")
        val state = decodeState(bytes)
        validateState(state)
        if (state.pageName != pageName || state.revision != reference.revision) fail("Page record name mismatch")
        return Entry(state)
    }

    private fun readPreparedState(name: String, pageName: String): State {
        val state = decodeState(io.readMaterial(name, MAX_RECORD_BYTES) ?: fail("Missing prepared page record"))
        validateState(state)
        if (state.pageName != pageName ||
            name != names(pageName, state.revision).record
        ) {
            fail("Prepared page record name mismatch")
        }
        return state
    }

    /** Losing both final markers must not revive a stale legacy done checkpoint for partial pixels. */
    private fun rejectOrphanOutput(pageName: String) {
        val pagePrefix = prefix(pageName) + "-"
        val records = io.materialNames().filter {
            it.startsWith(pagePrefix) && JOURNAL_NAME.matches(it) && it.endsWith(".record")
        }
        if (records.isEmpty()) return
        val current = io.readPage(pageName, MAX_IMAGE_BYTES) ?: fail("Cannot read current page")
        val currentSha256 = digest(current)
        for (name in records) {
            val prepared = runCatching { readPreparedState(name, pageName) }.getOrNull() ?: continue
            val previousSha256 = prepared.initialBaseline?.sha256 ?: prepared.original.sha256
            if (currentSha256 == prepared.outputSha256 && currentSha256 != previousSha256) {
                fail("Output snapshot has lost its page commit and recovery authority")
            }
        }
    }

    private fun readOriginal(pageName: String, original: Original): ByteArray {
        validateOriginal(pageName, original)
        return readVerified(original.name, original.length.toInt(), original.sha256)
    }

    private fun readVerified(name: String, length: Int, sha256: String): ByteArray {
        val bytes = io.readMaterial(name, MAX_IMAGE_BYTES) ?: fail("Missing page material")
        if (bytes.size != length || digest(bytes) != sha256) fail("Page material checksum mismatch")
        return bytes
    }

    private fun createVerified(name: String, bytes: ByteArray, maxBytes: Int) {
        var writeFailure: Exception? = null
        try {
            io.createMaterial(name, bytes)
        } catch (failure: Exception) {
            writeFailure = failure
        }
        val actual = io.readMaterial(name, maxBytes)
        if (actual?.contentEquals(bytes) != true) throw writeFailure ?: IOException("Cannot verify new page material")
    }

    private fun replaceVerified(pageName: String, bytes: ByteArray) {
        if (!io.replacePage(pageName, bytes)) fail("Cannot replace page")
        val actual = io.readPage(pageName, MAX_IMAGE_BYTES)
        if (actual?.contentEquals(bytes) != true) fail("Page replacement checksum mismatch")
    }

    private fun deleteVerified(name: String) {
        io.deleteMaterial(name)
        if (name in io.materialNames()) fail("Cannot clear page recovery marker")
    }

    private data class Entry(val state: State)
    private data class Scan(
        val latest: State?,
        val committed: Map<String, Entry>,
        val wips: List<Entry>,
        val invalidWips: List<String>,
    )

    @Serializable
    private data class Reference(val kind: String, val pageName: String, val revision: String, val recordSha256: String)

    private data class Names(
        val output: String,
        val mask: String,
        val record: String,
        val wip: String,
        val commit: String,
        val baseline: String,
    )

    companion object {
        private const val PREFIX = "partial-"
        private const val HEADER = "YOMITORI-PARTIAL-1\n"
        private const val MAX_RECORD_BYTES = 8 * 1024 * 1024
        private const val MAX_IMAGE_BYTES = 192 * 1024 * 1024
        private const val MAX_CONTEXT_BYTES = 8 * 1024
        private const val MAX_JOURNAL_FILES = 32768
        private val REVISION = Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val SHA256 = Regex("[a-f0-9]{64}")
        private val JOURNAL_NAME =
            Regex("$PREFIX[a-f0-9]{64}-${REVISION.pattern}\\.(output|mask|record|wip|commit|baseline)")
        private val JSON = Json { ignoreUnknownKeys = false }
        private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        fun forUniFile(context: Context, chapterDir: UniFile, materialsDir: UniFile): PartialPageStore =
            PartialPageStore(UniFileIo(context, chapterDir, materialsDir))

        fun sha256(bytes: ByteArray): String = digest(bytes)

        /** Exact journal-owned names for an explicit source replacement, including orphan blobs. */
        fun artifactNames(pageName: String, materialNames: Set<String>): Set<String> {
            requirePageName(pageName)
            val pagePrefix = prefix(pageName) + "-"
            return materialNames.filterTo(linkedSetOf()) { it.startsWith(pagePrefix) && JOURNAL_NAME.matches(it) }
        }

        private fun prefix(pageName: String): String = PREFIX + digest(pageName.toByteArray(Charsets.UTF_8))

        private fun names(pageName: String, revision: String): Names {
            val base = "${prefix(pageName)}-$revision"
            return Names("$base.output", "$base.mask", "$base.record", "$base.wip", "$base.commit", "$base.baseline")
        }

        private fun revisionFromName(name: String): String = name.substring(PREFIX.length + 65).substringBeforeLast('.')

        private fun requirePageName(name: String) {
            if (name.isBlank() || name == "." || name == ".." || name.toByteArray(Charsets.UTF_8).size > 1024 ||
                name.any { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }
            ) {
                fail("Invalid page name")
            }
        }

        private fun validateOriginal(pageName: String, original: Original) {
            val allowedName = when (original.provenance) {
                OriginalProvenance.EXACT_BYTES -> original.name == OriginalMaterial.exactName(pageName)
                OriginalProvenance.LEGACY_REENCODED -> original.name.equals(
                    OriginalMaterial.legacyName(pageName),
                    ignoreCase = true,
                )
                OriginalProvenance.WEBP_COMPATIBLE_UNKNOWN -> OriginalMaterial.extension(
                    pageName,
                ).equals("webp", ignoreCase = true) &&
                    original.name.equals(OriginalMaterial.exactName(pageName), ignoreCase = true)
            }
            if (!allowedName || !SHA256.matches(original.sha256) ||
                original.length !in 1..MAX_IMAGE_BYTES.toLong()
            ) {
                fail("Invalid preserved original descriptor")
            }
        }

        private fun validateDimensions(width: Int, height: Int) {
            if (PageSizeCap.check(width, height) != PageSizeCap.Verdict.Ok) fail("Invalid page dimensions")
        }

        private fun validatePayload(payload: String) {
            if (payload.toByteArray(Charsets.UTF_8).size >
                MAX_RECORD_BYTES - 4096
            ) {
                fail("Partial progress is too large")
            }
            if (runCatching {
                    JSON.parseToJsonElement(payload)
                }.getOrNull() !is JsonObject
            ) {
                fail("Invalid partial progress JSON")
            }
        }

        private fun validateSafeContext(payload: String?) {
            if (payload == null) return
            if (payload.toByteArray(Charsets.UTF_8).size > MAX_CONTEXT_BYTES ||
                runCatching { JSON.parseToJsonElement(payload) }.getOrNull() !is JsonObject
            ) {
                fail("Invalid safe translation context JSON")
            }
        }

        private fun validateMask(bytes: ByteArray, width: Int, height: Int) {
            if (bytes.size !in 33..MAX_IMAGE_BYTES || !bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) ||
                bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() ||
                bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()
            ) {
                fail("Invalid original segmentation PNG")
            }
            fun intAt(offset: Int): Int = (0..3).fold(0) { value, i ->
                (value shl 8) or
                    (bytes[offset + i].toInt() and 255)
            }
            if (intAt(8) != 13 || intAt(16) != width || intAt(20) != height) fail("Segmentation dimensions changed")
        }

        private fun validateState(state: State) {
            requirePageName(state.pageName)
            validateOriginal(state.pageName, state.original)
            validateDimensions(state.width, state.height)
            validatePayload(state.progressPayload)
            validateSafeContext(state.safeContextPayload)
            state.initialBaseline?.let { baseline ->
                if (!REVISION.matches(baseline.revision) || !SHA256.matches(baseline.sha256) ||
                    baseline.length !in 1..MAX_IMAGE_BYTES ||
                    (state.generation == 1L && baseline.revision != state.revision)
                ) {
                    fail("Invalid initial output baseline")
                }
            }
            if (!REVISION.matches(state.revision) || state.baseRevision?.let { !REVISION.matches(it) } == true ||
                state.generation < 1 || !SHA256.matches(state.previousOutputSha256) ||
                !SHA256.matches(state.outputSha256) || !SHA256.matches(state.rawMaskSha256) ||
                state.outputLength !in 1..MAX_IMAGE_BYTES || state.rawMaskLength !in 33..MAX_IMAGE_BYTES
            ) {
                fail("Invalid partial page record")
            }
        }

        private fun seal(payload: String): ByteArray {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            return (HEADER + digest(bytes) + "\n").toByteArray(Charsets.UTF_8).plus(bytes).also {
                if (it.size > MAX_RECORD_BYTES) fail("Page record is too large")
            }
        }

        private fun unseal(bytes: ByteArray): String {
            if (bytes.size > MAX_RECORD_BYTES) fail("Page record is too large")
            val text = bytes.toString(Charsets.UTF_8)
            val separator = text.indexOf('\n', HEADER.length)
            if (!text.startsWith(HEADER) || separator != HEADER.length + 64) fail("Incomplete page record")
            val payload = text.substring(separator + 1)
            if (digest(payload.toByteArray(Charsets.UTF_8)) !=
                text.substring(HEADER.length, separator)
            ) {
                fail("Page record checksum mismatch")
            }
            return payload
        }

        private fun encodeState(state: State): ByteArray = seal(JSON.encodeToString(state))
        private fun decodeState(bytes: ByteArray): State = JSON.decodeFromString<State>(unseal(bytes))
        private fun encodeRef(reference: Reference): ByteArray = seal(JSON.encodeToString(reference))
        private fun decodeRef(bytes: ByteArray): Reference = JSON.decodeFromString<Reference>(unseal(bytes))
        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        private fun fail(message: String): Nothing = throw IOException(message)
    }

    /** Exact-name, truncating SAF/file adapter. No delete or recursive directory operation is used. */
    private class UniFileIo(
        private val context: Context,
        private val chapter: UniFile,
        private val materials: UniFile,
    ) : Io {
        override fun materialNames(): Set<String> = materials.listFiles()?.mapNotNull { it.name }?.toSet()
            ?: fail("Cannot list partial page materials")

        override fun readMaterial(name: String, maxBytes: Int): ByteArray? = read(materials, name, maxBytes)
        override fun readPage(name: String, maxBytes: Int): ByteArray? = read(chapter, name, maxBytes)

        override fun createMaterial(name: String, bytes: ByteArray): Boolean {
            requirePageName(name)
            if (materials.findFile(name) != null) return false
            val file = materials.createFile(name) ?: return false
            if (!file.isFile || file.name != name) return false
            return write(file, bytes)
        }

        override fun deleteMaterial(name: String): Boolean {
            requirePageName(name)
            val file = materials.findFile(name) ?: return true
            return file.isFile && file.name == name && file.delete()
        }

        override fun replacePage(name: String, bytes: ByteArray): Boolean {
            requirePageName(name)
            val page = chapter.findFile(name) ?: return false
            return page.isFile && page.name == name && write(page, bytes)
        }

        private fun read(dir: UniFile, name: String, maxBytes: Int): ByteArray? {
            requirePageName(name)
            val file = dir.findFile(name) ?: return null
            if (!file.isFile || file.name != name) fail("Page material is not an exact named file")
            if (file.length() > maxBytes) fail("Page material is too large")
            val input = context.contentResolver.openInputStream(file.uri) ?: fail("Cannot open page material")
            return input.use { readBounded(it, maxBytes) }
        }

        private fun write(file: UniFile, bytes: ByteArray): Boolean {
            val output = if (file.uri.scheme ==
                "file"
            ) {
                file.openOutputStream()
            } else {
                context.contentResolver.openOutputStream(file.uri, "wt")
            }
            output?.use { it.write(bytes) } ?: return false
            return file.length() == bytes.size.toLong()
        }

        private fun readBounded(input: InputStream, maxBytes: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                if (output.size().toLong() + count > maxBytes) fail("Page material is too large")
                output.write(buffer, 0, count)
            }
        }
    }
}
