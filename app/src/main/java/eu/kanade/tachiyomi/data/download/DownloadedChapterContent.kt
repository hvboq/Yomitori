package eu.kanade.tachiyomi.data.download

import android.content.Context
import com.hippo.unifile.UniFile
import mihon.core.archive.archiveReader
import tachiyomi.core.common.storage.extension
import tachiyomi.core.common.util.system.ImageUtil
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Tests for reader-visible body content, rather than the existence of a chapter container.
 *
 * Like the reader, this recognizes image names or a short image header. It does not decode images
 * or prove that every page is intact. Empty image files are excluded. Directories expose only their
 * direct image children; archive material under `.yakuyomi` is never a reader page. Archive header
 * enumeration and image probes are bounded so a metadata-only archive cannot cause an unbounded scan.
 */
class DownloadedChapterContent(private val context: Context) {

    fun hasReadablePage(chapter: UniFile): Boolean = try {
        when {
            chapter.isDirectory -> chapter.listFiles().orEmpty().any(::isReadableImageFile)
            chapter.isFile && chapter.extension == "cbz" -> hasReadableArchivePage(chapter)
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    fun isReadableImageFile(file: UniFile): Boolean = try {
        file.isFile && isImageFile(file)
    } catch (_: Exception) {
        false
    }

    internal fun hasReadableArchivePage(archive: UniFile): Boolean = try {
        archive.isFile && archive.length() > 0 && inspectArchive(archive).hasPage
    } catch (_: Exception) {
        false
    }

    /** A negative UI presence check is not permission to replace or relocate existing files. */
    internal fun requireMetadataOnly(chapter: UniFile) {
        when {
            chapter.isDirectory -> {
                val children = chapter.listFiles() ?: throw IOException("Cannot inspect the existing chapter")
                for (file in children) {
                    val name = file.name ?: throw IOException("Cannot identify existing chapter material")
                    if (isRecoveryName(name)) recoveryMaterialError()
                    if (!file.isFile) throw IOException("Existing chapter contains preserved or unknown directories")
                    if (isImageFile(file)) throw IOException("The chapter already contains reader pages")
                }
            }
            chapter.isFile && chapter.extension == "cbz" -> {
                val size = chapter.length()
                if (size < 0) throw IOException("Cannot determine the existing archive length")
                if (size == 0L) {
                    chapter.openInputStream().use {
                        if (it.read() != -1) throw IOException("Cannot verify the existing archive length")
                    }
                    return
                }
                val inspection = inspectArchive(chapter)
                if (inspection.hasRecoveryMaterial) recoveryMaterialError()
                if (inspection.hasPage) throw IOException("The chapter already contains reader pages")
            }
            else -> throw IOException("Cannot verify the existing chapter container")
        }
    }

    private fun isImageFile(file: UniFile): Boolean {
        val size = file.length()
        if (size < 0) throw IOException("Cannot determine the image length")
        if (size == 0L) {
            file.openInputStream().use {
                if (it.read() != -1) throw IOException("Cannot verify the image length")
            }
            return false
        }
        if (ImageUtil.isImage(file.name)) return true
        return file.openInputStream().use { isImageHeader(it.readImageHeader()) }
    }

    private fun inspectArchive(archive: UniFile): ArchiveContent = archive.archiveReader(context).use { reader ->
        var hasPage = false
        var hasRecoveryMaterial = false
        var entryCount = 0
        var probeCount = 0
        reader.useEntries { entries ->
            entries.forEach { entry ->
                if (++entryCount > MAX_ARCHIVE_ENTRIES) throw IOException("Archive entry limit exceeded")
                val path = entry.name.replace('\\', '/').split('/')
                if (path.any(::isRecoveryName)) hasRecoveryMaterial = true
                val material = path.dropLast(1).any { it.equals(".yakuyomi", ignoreCase = true) }
                if (entry.isFile && !material && !hasPage) {
                    if (++probeCount > MAX_ARCHIVE_PROBES) throw IOException("Archive image probe limit exceeded")
                    val stream = reader.getInputStream(entry.name)
                        ?: throw IOException("Cannot inspect an archive entry")
                    hasPage = stream.use {
                        if (ImageUtil.isImage(entry.name)) it.read() != -1 else isImageHeader(it.readImageHeader())
                    }
                }
            }
        }
        ArchiveContent(hasPage, hasRecoveryMaterial)
    }

    private fun isImageHeader(header: ByteArray): Boolean =
        header.isNotEmpty() && ImageUtil.findImageType(ByteArrayInputStream(header)) != null

    private fun InputStream.readImageHeader(): ByteArray {
        val bytes = ByteArray(IMAGE_HEADER_BYTES)
        var count = 0
        while (count < bytes.size) {
            val bytesRead = read(bytes, count, bytes.size - count)
            if (bytesRead < 0) break
            if (bytesRead == 0) {
                val nextByte = read()
                if (nextByte < 0) break
                bytes[count++] = nextByte.toByte()
            } else {
                count += bytesRead
            }
        }
        return bytes.copyOf(count)
    }

    private fun isRecoveryName(name: String): Boolean = name.startsWith(".yakuyomi", ignoreCase = true)

    private fun recoveryMaterialError(): Nothing = throw IOException(
        "Preserved original material exists. Restore the original pages before downloading again.",
    )

    private data class ArchiveContent(val hasPage: Boolean, val hasRecoveryMaterial: Boolean)

    private companion object {
        const val IMAGE_HEADER_BYTES = 32
        const val MAX_ARCHIVE_ENTRIES = 100_000
        const val MAX_ARCHIVE_PROBES = 4_096
    }
}

/**
 * Refuses destinations observed before publication; only the app staging candidate is renamed.
 * A failed or ambiguous rename remains an error. This is not an atomic no-replace guarantee
 * against a separate process modifying storage between the check and the provider rename.
 */
class DownloadedChapterPublisher(context: Context) {
    private val content = DownloadedChapterContent(context)

    fun publish(mangaDir: UniFile, candidate: UniFile, finalName: String): UniFile {
        requireChildName(finalName)
        val candidateName = candidate.name ?: throw IOException("Cannot identify the downloaded chapter")
        requireChildName(candidateName)
        if (!candidateName.endsWith(Downloader.TMP_DIR_SUFFIX) || candidateName == finalName) {
            throw IOException("The downloaded chapter is not an app staging container")
        }
        if (!mangaDir.isDirectory) throw IOException("The manga download directory is unavailable")
        val directory = candidate.isDirectory
        if (!directory && (!candidate.isFile || !finalName.endsWith(".cbz"))) {
            throw IOException("Invalid downloaded chapter container")
        }
        requireBoundChild(mangaDir, candidate, candidateName, directory)
        val hasBody = if (directory) content.hasReadablePage(candidate) else content.hasReadableArchivePage(candidate)
        if (!hasBody) throw IOException("Downloaded chapter has no readable pages")
        if (mangaDir.findFile(finalName) != null) {
            throw IOException("The chapter destination already exists; existing and downloaded files were preserved")
        }
        if (!candidate.renameTo(finalName)) throw IOException("Cannot publish the downloaded chapter")
        val published = requireBoundChild(mangaDir, candidate, finalName, directory)
        if (!content.hasReadablePage(published)) throw IOException("Published chapter has no readable pages")
        return published
    }

    private fun requireBoundChild(parent: UniFile, file: UniFile, name: String, directory: Boolean): UniFile {
        val bound = parent.findFile(name) ?: throw IOException("The chapter container is unavailable")
        if (bound.uri != file.uri || file.name != name || bound.isDirectory != directory ||
            (!directory && !bound.isFile)
        ) {
            throw IOException("The chapter container identity changed")
        }
        return bound
    }

    private fun requireChildName(name: String) {
        if (name.isBlank() || name in listOf(".", "..") || name.any { it == '/' || it == '\\' || it == '\u0000' } ||
            name.toByteArray(Charsets.UTF_8).size > 255
        ) {
            throw IOException("Invalid chapter container name")
        }
    }
}
