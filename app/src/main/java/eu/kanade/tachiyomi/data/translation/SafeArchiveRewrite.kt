package eu.kanade.tachiyomi.data.translation

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Fail closed before changing a chapter; retain a verified external backup through every commit. */
internal object SafeArchiveRewrite {
    interface Storage {
        fun read(): InputStream
        fun write(): OutputStream
    }

    const val MAX_ENTRIES = 20_000
    private const val MAX_BYTES = 4L * 1024 * 1024 * 1024
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "jxl", "heic", "heif", "bmp")

    /** The current page processor supports root images and its own flat materials directory only. */
    fun validateEntries(entries: List<Pair<String, Boolean>>) {
        require(entries.size <= MAX_ENTRIES) { "Archive has too many entries" }
        val paths = HashSet<String>()
        val pageBases = HashSet<String>()
        entries.forEach { (raw, isFile) ->
            val path = raw.removeSuffix("/")
            require(path.isNotEmpty() && !raw.startsWith('/') && '\\' !in raw && ':' !in raw) {
                "Unsafe archive entry path"
            }
            val parts = path.split('/')
            require(
                parts.all {
                    it.isNotEmpty() && it != "." && it != ".." && !it.endsWith('.') && !it.endsWith(' ') &&
                        it.none(Char::isISOControl)
                },
            ) {
                "Unsafe archive entry path"
            }
            require(paths.add(path.lowercase())) { "Archive entry names collide" }
            if (!isFile) {
                require(path == ".yakuyomi") { "Unsupported archive directory or special entry" }
            } else {
                require(!raw.endsWith('/') && path != ".yakuyomi") { "Reserved archive path collision" }
                require(parts.size == 1 || (parts.size == 2 && parts[0] == ".yakuyomi")) {
                    "Nested archive pages are not supported; original archive was not modified"
                }
                if (parts.size == 1 && path.substringAfterLast('.', "").lowercase() in imageExtensions) {
                    require(pageBases.add(path.substringBeforeLast('.').lowercase())) {
                        "Archive page names share a materials filename"
                    }
                }
            }
        }
        // A differently cased materials directory aliases the reserved path on some providers.
        require(
            entries.none { (name, _) ->
                name.substringBefore('/').equals(".yakuyomi", true) &&
                    name.substringBefore('/') != ".yakuyomi"
            },
        ) { "Reserved archive path collision" }
    }

    fun copy(
        input: InputStream,
        output: OutputStream,
        shouldStop: () -> Boolean = { false },
        maxBytes: Long = MAX_BYTES,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var bytes = 0L
        while (true) {
            if (shouldStop()) throw IOException("Archive operation stopped")
            val count = input.read(buffer)
            if (count < 0) return bytes
            bytes += count
            require(bytes <= maxBytes) { "Archive data exceeds safety limit" }
            output.write(buffer, 0, count)
        }
    }

    fun extract(
        entries: List<Pair<String, Boolean>>,
        directory: File,
        open: (String) -> InputStream,
        shouldStop: () -> Boolean,
    ) {
        validateEntries(entries)
        var total = 0L
        entries.forEach { (name, isFile) ->
            val file = File(directory, name.removeSuffix("/"))
            require(file.canonicalPath.startsWith(directory.canonicalPath + File.separator)) { "Unsafe archive path" }
            if (isFile) {
                require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) {
                    "Cannot create archive directory"
                }
                open(name).use { input ->
                    file.outputStream().use { output -> total += copy(input, output, shouldStop, MAX_BYTES - total) }
                }
                require(total <= MAX_BYTES) { "Expanded archive exceeds safety limit" }
            } else {
                require(file.isDirectory || file.mkdirs()) { "Cannot create archive directory" }
            }
        }
    }

    /**
     * Repack the accepted root files and flat `.yakuyomi/` materials without filtering by extension.
     * PartialPageStore's output/mask/record/commit/WIP files are opaque recovery data, not reader
     * images; discarding them would lose both pending progress and rollback authority. Preserve
     * existing archive members under [validateEntries]' policy and verify each ZIP member's bytes.
     * This method only reads the caller's isolated staging directory, never other cache artifacts.
     */
    fun packAndVerify(directory: File, zip: File, shouldStop: () -> Boolean) {
        val files = directory.walkTopDown().filter(File::isFile).toList()
        require(files.sumOf(File::length) <= MAX_BYTES) { "Rewritten archive exceeds safety limit" }
        val expected = files.associate {
            it.relativeTo(directory).invariantSeparatorsPath to digest(it.inputStream(), shouldStop)
        }
        validateEntries(expected.keys.map { it to true })
        ZipOutputStream(zip.outputStream().buffered()).use { output ->
            files.forEach { file ->
                output.putNextEntry(ZipEntry(file.relativeTo(directory).invariantSeparatorsPath))
                file.inputStream().use { copy(it, output, shouldStop) }
                output.closeEntry()
            }
        }
        ZipFile(zip).use { archive ->
            val actual = archive.entries().asSequence().toList()
            require(actual.size == expected.size && actual.map { it.name }.toSet() == expected.keys) {
                "Archive verification entry mismatch"
            }
            actual.forEach { entry ->
                require(digest(archive.getInputStream(entry), shouldStop) == expected[entry.name]) {
                    "Archive verification failed"
                }
            }
        }
    }

    /** Backup must be outside the staging directory and kept even after successful replacement. */
    fun commit(
        original: File,
        replacement: File,
        target: Storage,
        backup: Storage,
        shouldStop: () -> Boolean,
    ) {
        val originalHash = digest(original.inputStream(), shouldStop)
        val replacementHash = digest(replacement.inputStream(), shouldStop)
        require(digest(target.read(), shouldStop) == originalHash) { "Archive changed during processing" }
        original.inputStream().use { input -> backup.write().use { copy(input, it, shouldStop) } }
        require(digest(backup.read(), shouldStop) == originalHash) { "Original archive backup verification failed" }
        require(digest(target.read(), shouldStop) == originalHash) { "Archive changed during backup" }
        if (shouldStop()) throw IOException("Archive operation stopped before replacement")
        try {
            replacement.inputStream().use { input -> target.write().use { copy(input, it, shouldStop) } }
            // After writing, finish integrity verification or rollback even if pause arrives now.
            require(digest(target.read()) == replacementHash) { "Replacement archive verification failed" }
        } catch (failure: Throwable) {
            // Restoration ignores pause/cancel: finishing the rollback prevents a truncated chapter.
            try {
                backup.read().use { input -> target.write().use { copy(input, it) } }
                require(digest(target.read()) == originalHash) { "Archive rollback verification failed" }
            } catch (rollback: Throwable) {
                failure.addSuppressed(rollback)
            }
            throw IOException("Archive replacement failed; verified original backup retained", failure)
        }
    }

    private fun digest(input: InputStream, shouldStop: () -> Boolean = { false }): String = input.use {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            if (shouldStop()) throw IOException("Archive operation stopped")
            val count = it.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }
    }
}
