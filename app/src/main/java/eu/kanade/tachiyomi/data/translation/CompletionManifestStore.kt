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
        val recovery = checkpoint(previous)
        verifiedWrite(CompletionManifestCodec.PRIMARY, pages)
        // Failure here is still reported to the caller, but the verified primary truthfully records
        // pages whose image writes already succeeded. Resume may skip those pages; older recovery
        // copies are retained and the caller keeps its original-image recovery marker on failure.
        val completed = if (pages == previous) recovery else checkpoint(pages)
        // A verified current primary AND checkpoint exist before any older copy is removed.
        readFiles().keys.filter { it != completed && CompletionManifestCodec.isCheckpoint(it) }.forEach(delete)
    }

    private fun checkpoint(pages: Set<String>): String =
        (CompletionManifestCodec.CHECKPOINT_PREFIX + UUID.randomUUID()).also { verifiedWrite(it, pages) }

    private fun verifiedWrite(name: String, pages: Set<String>) {
        check(write(name, CompletionManifestCodec.encode(pages))) { "Cannot write translation manifest $name" }
        check(readFiles()[name]?.let(CompletionManifestCodec::decode) == pages) {
            "Translation manifest verification failed: $name"
        }
    }
}
