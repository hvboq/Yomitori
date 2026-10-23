package eu.kanade.tachiyomi.data.translation

import java.util.UUID

/** Storage-independent transaction so partial writes can be injected without a special SAF provider. */
internal class CompletionManifestStore(
    private val readFiles: () -> Map<String, ByteArray>,
    private val write: (String, ByteArray) -> Boolean,
    private val delete: (String) -> Unit,
) {
    fun commit(pages: Set<String>) {
        val previous = CompletionManifestCodec.resolve(readFiles())
        // This is a unique file: no existing recovery copy is touched if writing it fails.
        val recovery = checkpointName()
        verifiedWrite(recovery, previous, CompletionManifestCodec.encode(previous))
        val encodedPages = CompletionManifestCodec.encode(pages)
        var readback = verifiedWrite(CompletionManifestCodec.PRIMARY, pages, encodedPages)
        // Failure here is still reported to the caller, but the verified primary truthfully records
        // pages whose image writes already succeeded. Resume may skip those pages; older recovery
        // copies are retained and the caller keeps its original-image recovery marker on failure.
        val completed = if (pages == previous) {
            recovery
        } else {
            checkpointName().also { readback = verifiedWrite(it, pages, encodedPages) }
        }
        // A verified current primary AND checkpoint exist before any older copy is removed.
        // Use that final readback: cleanup needs no extra full read and cannot touch later copies.
        readback.keys.filter { it != completed && CompletionManifestCodec.isCheckpoint(it) }.forEach(delete)
    }

    private fun checkpointName(): String = CompletionManifestCodec.CHECKPOINT_PREFIX + UUID.randomUUID()

    private fun verifiedWrite(name: String, pages: Set<String>, bytes: ByteArray): Map<String, ByteArray> {
        check(write(name, bytes)) { "Cannot write translation manifest $name" }
        val readback = readFiles()
        check(readback[name]?.let(CompletionManifestCodec::decode) == pages) {
            "Translation manifest verification failed: $name"
        }
        return readback
    }
}
