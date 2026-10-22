package eu.kanade.tachiyomi.data.translation

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

/** Read-only archive completion check. A partial journal overrides every older manifest. */
internal object ArchiveTranslationState {
    fun isComplete(
        images: Set<String>,
        files: Set<String>,
        open: (String) -> InputStream?,
    ): Boolean = try {
        if (images.isEmpty()) {
            false
        } else {
            val metadata = files.filter {
                it == CompletionManifestCodec.PRIMARY || it == CompletionManifestCodec.PREVIOUS ||
                    CompletionManifestCodec.isCheckpoint(it)
            }.mapNotNull { name ->
                open(name)?.use(CompletionManifestCodec::read)?.let { name to it }
            }.toMap()
            val store = PartialPageStore(object : PartialPageStore.Io {
                override fun materialNames(): Set<String> = files.filter { it.startsWith(".yakuyomi/") }
                    .mapTo(HashSet()) { it.removePrefix(".yakuyomi/") }

                override fun readMaterial(name: String, maxBytes: Int): ByteArray? =
                    read(".yakuyomi/$name", maxBytes)

                override fun readPage(name: String, maxBytes: Int): ByteArray? = read(name, maxBytes)

                override fun createMaterial(name: String, bytes: ByteArray): Boolean = readOnly()
                override fun deleteMaterial(name: String): Boolean = readOnly()
                override fun replacePage(name: String, bytes: ByteArray): Boolean = readOnly()

                private fun read(name: String, maxBytes: Int): ByteArray? {
                    if (name !in files) return null
                    return open(name)?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size().toLong() + count >
                                maxBytes
                            ) {
                                throw IOException("Archive material exceeds size limit")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                }

                private fun readOnly(): Nothing = throw IOException("Archive state check cannot modify files")
            })
            val done = store.applyDoneOverlay(CompletionManifestCodec.resolve(metadata))
            images.all { name ->
                if (name !in done) {
                    false
                } else {
                    val state = store.load(name, recover = false)
                    state == null || (
                        state.complete &&
                            !OcrProgressCodec.decode(state.progressPayload, state.width, state.height).hasPending
                        )
                }
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}
