package eu.kanade.tachiyomi.ui.reader.loader

import android.content.Context
import android.graphics.BitmapFactory
import android.system.Os
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.PageSizeCap
import eu.kanade.tachiyomi.data.translation.PageTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tachiyomi.core.common.util.system.ImageUtil
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Private loose-page authorities. No entry is a DownloadProvider chapter or a user file. */
internal class OnlinePageCache(context: Context, private val limits: Limits = Limits()) {
    data class Limits(
        // Ordinary retained pages target 256 MiB. A single supported 40 MP page may need
        // more room for its immutable source, partial journal and transactional rollback.
        val bytes: Long = 3L * 1024 * 1024 * 1024,
        val entries: Int = 64,
        val sourceBytes: Long = 192L * 1024 * 1024,
        val pagePixels: Long = PageSizeCap.MAX_PIXELS,
        val processingReserve: Long = 192L * 1024 * 1024,
        val retainedBytes: Long = minOf(bytes, 256L * 1024 * 1024),
    ) {
        init {
            require(entries in 1..256 && sourceBytes > 0 && processingReserve >= 0)
            require(bytes >= processingReserve && pagePixels > 0)
            require(retainedBytes in 1..bytes)
        }
    }

    class TooLarge : IOException("Online page exceeds the bounded reader cache")
    class TooManyPixels : IOException("Online page exceeds the translation pixel limit")

    class Entry internal constructor(
        val directory: File,
        val page: File,
        val identity: String,
        val sourceSha256: String,
        val sourceBytes: Long,
    ) {
        val chapterDir: UniFile get() = checkNotNull(UniFile.fromFile(directory))
        val pageFile: UniFile get() = checkNotNull(UniFile.fromFile(page))
        val uri get() = page.toUri()
    }

    class Lease internal constructor(val entry: Entry, private val release: () -> Unit) : Closeable {
        private var closed = false
        override fun close() = synchronized(this) {
            if (!closed) {
                closed = true
                release()
            }
        }
    }

    private val root = File(context.cacheDir.canonicalFile, "online-reader-pages-v1").apply {
        if (!exists() && !mkdir()) throw IOException("Cannot create online reader cache")
    }.canonicalFile
    private val translator by lazy { PageTranslator(context) }

    init {
        check(root.parentFile == context.cacheDir.canonicalFile && root.isDirectory)
    }

    /** The caller supplies the HTTP cache original, never a possibly translated ReaderPage.stream. */
    suspend fun capture(identity: String, openOriginal: () -> InputStream?): Lease {
        var acquired: Lease? = null
        try {
            return withContext(Dispatchers.IO) {
                require(HEX.matches(identity))
                val stage = File(root, "capture-${UUID.randomUUID()}.tmp")
                val reservation = reserve(limits.sourceBytes)
                try {
                    synchronized(monitor) { activeCaptures.add(stage.path) }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var length = 0L
                    val input = openOriginal() ?: throw IOException("Original online page is not ready")
                    input.use { source ->
                        stage.outputStream().use { target ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                length += count
                                if (length > limits.sourceBytes) throw TooLarge()
                                digest.update(buffer, 0, count)
                                target.write(buffer, 0, count)
                            }
                            target.fd.sync()
                        }
                    }
                    check(length > 0)
                    val sourceSha = digest.digest().hex()
                    val extension = ImageUtil.findImageType(stage::inputStream)?.extension
                        ?: throw IOException("Unsupported online page image")
                    val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(stage.path, dimensions)
                    if (dimensions.outWidth <= 0 || dimensions.outHeight <= 0) {
                        throw IOException("Cannot read online page dimensions")
                    }
                    if (dimensions.outWidth.toLong() * dimensions.outHeight > limits.pagePixels) throw TooManyPixels()
                    val name = sha("online-page-v1\n$identity\n$sourceSha")
                    val directory = File(root, name)
                    synchronized(monitor) {
                        if (directory.exists()) {
                            val existing = readEntry(directory)
                            check(existing.identity == identity && existing.sourceSha256 == sourceSha)
                            check(existing.sourceBytes == length)
                            return@synchronized lease(existing).also { acquired = it }
                        }
                        trimFor(length * 2 + 4096, addingEntry = true)
                        val prepared = File(root, "prepare-${UUID.randomUUID()}")
                        check(prepared.mkdir())
                        try {
                            val source = File(prepared, SOURCE)
                            stage.copyTo(source, overwrite = false)
                            val page = File(prepared, "page.$extension")
                            stage.copyTo(page, overwrite = false)
                            val metadata = JSONObject()
                                .put("version", 1)
                                .put("identity", identity)
                                .put("sourceSha256", sourceSha)
                                .put("sourceBytes", length)
                                .put("page", page.name)
                            File(prepared, META).writeText(metadata.toString(), Charsets.UTF_8)
                            check(prepared.renameTo(directory))
                            lease(readEntry(directory)).also { acquired = it }
                        } catch (failure: Throwable) {
                            if (prepared.exists()) deletePrepared(prepared)
                            throw failure
                        }
                    }
                } finally {
                    stage.delete()
                    synchronized(monitor) { activeCaptures.remove(stage.path) }
                    reservation.close()
                }
            }
        } catch (failure: Throwable) {
            // withContext may cancel at the dispatcher handoff after the lease was created.
            acquired?.close()
            throw failure
        }
    }

    /** Pin an exact established authority before storage/native work or an open display stream. */
    fun retain(entry: Entry): Lease = synchronized(monitor) {
        checkEntryPath(entry.directory)
        check(entry.page.isFile && entry.page.canonicalFile.parentFile == entry.directory)
        lease(entry)
    }

    fun open(entry: Entry): InputStream {
        val held = retain(entry)
        try {
            return object : FilterInputStream(entry.page.inputStream()) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        held.close()
                    }
                }
            }
        } catch (failure: Throwable) {
            held.close()
            throw failure
        }
    }

    /** Reserve headroom before PageTranslator creates originals, masks and transactional backups. */
    fun reserveProcessing(entry: Entry): Closeable = synchronized(monitor) {
        checkEntryPath(entry.directory)
        val current = treeBytes(entry.directory)
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(File(entry.directory, SOURCE).path, dimensions)
        check(dimensions.outWidth > 0 && dimensions.outHeight > 0)
        val pixels = dimensions.outWidth.toLong() * dimensions.outHeight
        // A conservative disk reservation, not a bitmap allocation: source copies plus
        // current/new/rollback outputs and masks. Never reduce the established pixel limit.
        val workingBytes = maxOf(limits.processingReserve, entry.sourceBytes * 4 + pixels * 32 + 64L * 1024 * 1024)
        if (current > limits.bytes || workingBytes > limits.bytes) throw TooLarge()
        reserve((workingBytes - current).coerceAtLeast(0))
    }

    fun trim() = synchronized(monitor) {
        trimFor(0, addingEntry = false, budget = limits.retainedBytes, bestEffort = true)
    }

    fun exists(entry: Entry): Boolean = synchronized(monitor) {
        runCatching {
            checkEntryPath(entry.directory)
            entry.page.isFile && entry.page.canonicalFile.parentFile == entry.directory
        }.getOrDefault(false)
    }

    /** Revoke the cache's completed-display proof before any new storage mutation. */
    fun invalidateCompletion(entry: Entry) = synchronized(monitor) {
        checkEntryPath(entry.directory)
        val proof = File(entry.directory, COMPLETE)
        if (proof.exists()) {
            requireRegular(proof)
            check(proof.delete())
        }
    }

    /** Optional eviction proof only; never used to skip OCR or mark a page translated. */
    fun recordCompletion(entry: Entry) = synchronized(monitor) {
        checkEntryPath(entry.directory)
        val displaySha = sha(entry.page)
        if (!translator.canDiscardOnlinePage(
                entry.chapterDir,
                entry.page.name,
                entry.sourceSha256,
                entry.sourceBytes,
                displaySha,
            )
        ) {
            return@synchronized
        }
        val proof = JSONObject()
            .put("version", 1)
            .put("identity", entry.identity)
            .put("sourceSha256", entry.sourceSha256)
            .put("displaySha256", displaySha)
            .put("displayBytes", entry.page.length())
        val stage = File(entry.directory, "completion-${UUID.randomUUID()}.tmp")
        try {
            stage.outputStream().use {
                it.write(proof.toString().toByteArray(Charsets.UTF_8))
                it.fd.sync()
            }
            Os.rename(stage.path, File(entry.directory, COMPLETE).path)
        } finally {
            stage.delete()
        }
    }

    private fun lease(entry: Entry): Lease {
        val key = entry.directory.path
        pins[key] = (pins[key] ?: 0) + 1
        entry.directory.setLastModified(System.currentTimeMillis())
        return Lease(entry) {
            synchronized(monitor) {
                val count = checkNotNull(pins[key])
                if (count == 1) pins.remove(key) else pins[key] = count - 1
            }
        }
    }

    private fun reserve(bytes: Long): Closeable = synchronized(monitor) {
        trimFor(bytes, addingEntry = false)
        val token = UUID.randomUUID().toString()
        reservations[token] = Reservation(root.path, bytes)
        object : Closeable {
            private var closed = false
            override fun close() = synchronized(monitor) {
                if (!closed) {
                    reservations.remove(token)
                    closed = true
                }
            }
        }
    }

    private fun trimFor(
        additional: Long,
        addingEntry: Boolean,
        budget: Long = limits.bytes,
        bestEffort: Boolean = false,
    ) {
        check(root.isDirectory && root.canonicalFile == root)
        val children = root.listFiles() ?: throw IOException("Cannot list online page cache")
        // Only unpublished capture directories can be discarded without reading page journals.
        // No model/storage operation can see these names before their atomic publication.
        children.filter { PREPARED.matches(it.name) }.forEach(::deletePrepared)
        children.filter { CAPTURE.matches(it.name) && it.path !in activeCaptures }.forEach {
            check(it.parentFile == root)
            requireRegular(it)
            check(it.delete())
        }
        val candidates = children.filter { it.isDirectory && HEX.matches(it.name) }
            .sortedBy { it.lastModified() }.toMutableList()
        var total = treeBytes(root)
        var count = candidates.size
        val reserved = if (bestEffort) 0 else reservations.values.filter { it.root == root.path }.sumOf { it.bytes }
        while (total + reserved + additional > budget || count + (if (addingEntry) 1 else 0) > limits.entries) {
            val victim = candidates.firstOrNull { pins[it.path].orZero() == 0 && canEvict(it) }
                ?: if (bestEffort) return else throw IOException("Online page cache is in use or full")
            // A malformed directory is never authority for deleting arbitrary children.
            readEntry(victim)
            val size = treeBytes(victim)
            deleteOwned(victim)
            candidates.remove(victim)
            total -= size
            count--
        }
    }

    private fun readEntry(directory: File): Entry {
        checkEntryPath(directory)
        val metadata = File(directory, META)
        requireRegular(metadata)
        check(metadata.length() in 1..4096)
        val value = JSONObject(metadata.readText(Charsets.UTF_8))
        check(value.getInt("version") == 1)
        val identity = value.getString("identity")
        val sourceSha = value.getString("sourceSha256")
        val sourceBytes = value.getLong("sourceBytes")
        val pageName = value.getString("page")
        check(HEX.matches(identity) && HEX.matches(sourceSha))
        check(sourceBytes in 1..limits.sourceBytes)
        check(pageName in ImageUtil.ImageType.entries.map { "page.${it.extension}" })
        check(directory.name == sha("online-page-v1\n$identity\n$sourceSha"))
        val source = File(directory, SOURCE)
        requireRegular(source)
        check(source.length() == sourceBytes && sha(source) == sourceSha)
        val page = File(directory, pageName)
        requireRegular(page)
        check(page.length() > 0)
        if (!File(directory, ".yakuyomi").exists()) {
            check(sha(page) == sourceSha) { "Unproven online display has lost its source materials" }
        }
        return Entry(directory, page, identity, sourceSha, sourceBytes)
    }

    private fun checkEntryPath(directory: File) {
        check(HEX.matches(directory.name) && directory.canonicalFile == directory)
        check(directory.parentFile == root && directory.isDirectory)
        check(!java.nio.file.Files.isSymbolicLink(directory.toPath()))
    }

    private fun requireRegular(file: File) {
        check(file.isFile && file.canonicalFile == file && Os.lstat(file.path).st_nlink == 1L)
    }

    private fun treeBytes(directory: File): Long {
        var count = 0
        var bytes = 0L
        directory.walkTopDown().onEnter {
            check(it.canonicalFile == it && it.toPath().startsWith(root.toPath()))
            check(!java.nio.file.Files.isSymbolicLink(it.toPath()))
            true
        }.forEach { file ->
            if (!file.isDirectory) {
                requireRegular(file)
                check(++count <= 32_768)
                bytes += file.length()
            }
        }
        return bytes
    }

    private fun deleteOwned(directory: File) {
        checkEntryPath(directory)
        treeBytes(directory)
        directory.walkBottomUp().forEach { check(it.delete()) }
    }

    private fun canEvict(directory: File): Boolean = runCatching {
        val entry = readEntry(directory)
        val materials = File(directory, ".yakuyomi")
        if (!materials.exists()) return@runCatching true
        check(materials.isDirectory && materials.canonicalFile == materials)
        val proof = File(directory, COMPLETE)
        val expectedDisplaySha = if (proof.exists()) {
            runCatching {
                requireRegular(proof)
                check(proof.length() in 1..4096)
                val value = JSONObject(proof.readText(Charsets.UTF_8))
                check(value.getInt("version") == 1 && value.getString("identity") == entry.identity)
                check(value.getString("sourceSha256") == entry.sourceSha256)
                check(value.getLong("displayBytes") == entry.page.length())
                value.getString("displaySha256").also { check(HEX.matches(it)) }
            }.getOrElse { return false }
        } else {
            null
        }
        // A complete journal also has partial-* files. Only the storage layer can prove that
        // it is complete and recoverable; pending, torn or orphan material remains protected.
        translator.canDiscardOnlinePage(
            entry.chapterDir,
            entry.page.name,
            entry.sourceSha256,
            entry.sourceBytes,
            expectedDisplaySha,
        )
    }.getOrDefault(false)

    private fun deletePrepared(directory: File) {
        check(PREPARED.matches(directory.name) && directory.parentFile == root)
        check(directory.canonicalFile == directory && directory.isDirectory)
        treeBytes(directory)
        directory.walkBottomUp().forEach { check(it.delete()) }
    }

    private data class Reservation(val root: String, val bytes: Long)

    companion object {
        private val monitor = Any()
        private val pins = mutableMapOf<String, Int>()
        private val reservations = mutableMapOf<String, Reservation>()
        private val activeCaptures = mutableSetOf<String>()
        private val HEX = Regex("[a-f0-9]{64}")
        private val PREPARED = Regex("prepare-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
        private val CAPTURE = Regex("capture-[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\.tmp")
        private const val SOURCE = "source.bin"
        private const val META = "origin.json"
        private const val COMPLETE = "completed.json"
        private fun Int?.orZero() = this ?: 0
        fun sha(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).hex()
        fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { source ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().hex()
        }
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
