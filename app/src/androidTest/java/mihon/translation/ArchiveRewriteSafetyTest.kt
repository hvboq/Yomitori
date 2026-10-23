package mihon.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.TranslationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(AndroidJUnit4::class)
class ArchiveRewriteSafetyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun realManagerPreservesArchiveBackupMaterialsAndExistingTemporaryDirectory() = runBlocking<Unit> {
        val directory = File(context.cacheDir, "archive-android-${System.nanoTime()}").apply { mkdirs() }
        try {
            val archive = File(directory, "chapter.cbz")
            val originalPage = byteArrayOf(1, 2, 3, 4)
            zip(archive, mapOf("001.jpg" to originalPage))
            val originalArchive = archive.readBytes()
            val unrelated = File(directory, "chapter.cbz.yakutmp").apply { mkdirs() }
            File(unrelated, "keep.txt").writeText("user data")
            val result = rewrite(archive) { extracted ->
                val path = File(checkNotNull(extracted.uri.path))
                assertArrayEquals(originalPage, File(path, "001.jpg").readBytes())
                File(path, ".yakuyomi").mkdir()
                File(path, ".yakuyomi/001.orig.jpg").writeBytes(originalPage)
                File(path, "001.jpg").writeBytes(byteArrayOf(9, 8))
            }
            assertTrue(result)
            assertEquals("user data", File(unrelated, "keep.txt").readText())
            val backup = directory.listFiles()!!.single { it.name.startsWith("chapter.cbz.yomitori-original-") }
            assertArrayEquals(originalArchive, backup.readBytes())
            ZipFile(archive).use { packed ->
                assertArrayEquals(
                    originalPage,
                    packed.getInputStream(packed.getEntry(".yakuyomi/001.orig.jpg")).use {
                        it.readBytes()
                    },
                )
                assertArrayEquals(
                    byteArrayOf(9, 8),
                    packed.getInputStream(packed.getEntry("001.jpg")).use {
                        it.readBytes()
                    },
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unsupportedNestedArchiveFailsBeforeProcessingAndKeepsOriginalBytes() = runBlocking<Unit> {
        val directory = File(context.cacheDir, "archive-nested-${System.nanoTime()}").apply { mkdirs() }
        try {
            val archive = File(directory, "chapter.cbz")
            zip(archive, mapOf("a/001.jpg" to byteArrayOf(1), "b/001.jpg" to byteArrayOf(2)))
            val original = archive.readBytes()
            try {
                rewrite(archive) { fail("Unsupported nested archive must not reach processing") }
                fail("Nested archive must fail explicitly")
            } catch (_: IllegalArgumentException) {
                assertArrayEquals(original, archive.readBytes())
                assertEquals(listOf("chapter.cbz"), directory.list()!!.toList())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun softStopCommitsPartialArchiveAndOpaquePendingFilesWithoutReportingCompletion() = runBlocking<Unit> {
        withOwnedDirectory("archive-soft-stop") { directory ->
            val archive = File(directory, "chapter.cbz")
            val originalPage = byteArrayOf(1, 2, 3, 4)
            val untouchedPage = byteArrayOf(5, 6, 7)
            val changedPage = byteArrayOf(9, 8)
            val opaquePending = byteArrayOf(3, 1, 4, 1, 5)
            zip(archive, mapOf("001.jpg" to originalPage, "002.jpg" to untouchedPage))
            val originalArchive = archive.readBytes()
            val softStop = AtomicBoolean(false)
            var staging: File? = null

            val result = rewrite(archive, swap = true, success = false, shouldStop = softStop::get) { extracted ->
                staging = ownedStaging(extracted)
                val pages = File(checkNotNull(extracted.uri.path))
                val materials = File(pages, ".yakuyomi").apply { check(mkdir()) }
                File(materials, "001.orig.jpg").writeBytes(originalPage)
                File(materials, "opaque-pending.fixture").writeBytes(opaquePending)
                File(pages, "001.jpg").writeBytes(changedPage)
                softStop.set(true)
            }

            assertTrue(softStop.get())
            assertFalse("Partial checkpoint must not report full chapter completion", result)
            assertFalse("Owned staging must be removed after checkpoint", checkNotNull(staging).exists())
            assertPackedBytes(archive, "001.jpg", changedPage)
            assertPackedBytes(archive, "002.jpg", untouchedPage)
            assertPackedBytes(archive, ".yakuyomi/001.orig.jpg", originalPage)
            assertPackedBytes(archive, ".yakuyomi/opaque-pending.fixture", opaquePending)
            val backup = directory.listFiles()!!.single { it.name.startsWith("chapter.cbz.yomitori-original-") }
            assertTrue(backup.name.endsWith(".bak"))
            assertArrayEquals(originalArchive, backup.readBytes())
            assertPackedBytes(backup, "001.jpg", originalPage)
            assertPackedBytes(backup, "002.jpg", untouchedPage)
        }
    }

    @Test
    fun softStopWithoutSwappableProgressKeepsArchiveBytesAndCreatesNoBackup() = runBlocking<Unit> {
        withOwnedDirectory("archive-no-progress") { directory ->
            val archive = File(directory, "chapter.cbz")
            zip(archive, mapOf("001.jpg" to byteArrayOf(1, 2, 3)))
            val originalArchive = archive.readBytes()
            val softStop = AtomicBoolean(false)
            var staging: File? = null

            val result = rewrite(archive, swap = false, success = false, shouldStop = softStop::get) { extracted ->
                staging = ownedStaging(extracted)
                // Deliberately changed staging does not authorize a swap when process reports none.
                File(checkNotNull(extracted.uri.path), "001.jpg").writeBytes(byteArrayOf(9))
                softStop.set(true)
            }

            assertTrue(softStop.get())
            assertFalse(result)
            assertArrayEquals(originalArchive, archive.readBytes())
            assertEquals(listOf("chapter.cbz"), directory.list()!!.toList())
            assertFalse("Non-swappable owned staging must be removed", checkNotNull(staging).exists())
        }
    }

    @Test
    fun hardCancellationOfOnlyChildPropagatesAndCannotCommitSwappableStaging() = runBlocking<Unit> {
        withOwnedDirectory("archive-hard-cancel") { directory ->
            val archive = File(directory, "chapter.cbz")
            zip(archive, mapOf("001.jpg" to byteArrayOf(1, 2, 3)))
            val originalArchive = archive.readBytes()
            var staging: File? = null
            coroutineScope {
                val child = async {
                    rewrite(archive, swap = true, success = true) { extracted ->
                        staging = ownedStaging(extracted)
                        val pages = File(checkNotNull(extracted.uri.path))
                        val materials = File(pages, ".yakuyomi").apply { check(mkdir()) }
                        File(materials, "001.orig.jpg").writeBytes(byteArrayOf(1, 2, 3))
                        File(pages, "001.jpg").writeBytes(byteArrayOf(9, 8))
                        // Cancels this child only. The callback returns swap=true, so the actual
                        // wrapper's checkpoint ensureActive must reject it before backup creation.
                        currentCoroutineContext().cancel(CancellationException("Owned archive child cancellation"))
                    }
                }
                var propagated = false
                try {
                    child.await()
                    fail("Hard coroutine cancellation must propagate")
                } catch (_: CancellationException) {
                    propagated = true
                }
                assertTrue(propagated)
                assertTrue(child.isCancelled)
                currentCoroutineContext().ensureActive()
            }
            assertArrayEquals(originalArchive, archive.readBytes())
            assertEquals(listOf("chapter.cbz"), directory.list()!!.toList())
            assertFalse("Cancelled child's owned staging must be removed", checkNotNull(staging).exists())
        }
    }

    /** Private wrapper controls only; these are not public queue pause/resume or OCR tests. */
    private suspend fun rewrite(
        archive: File,
        swap: Boolean = true,
        success: Boolean = true,
        shouldStop: () -> Boolean = { false },
        process: suspend (UniFile) -> Unit,
    ): Boolean {
        val manager = TranslationManager(context)
        val resultClass = TranslationManager::class.java.declaredClasses.single { it.simpleName == "ProcessResult" }
        val resultConstructor = resultClass.getDeclaredConstructor(
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
            .apply { isAccessible = true }
        val callback: suspend (UniFile) -> Any = { directory ->
            process(directory)
            resultConstructor.newInstance(swap, success)
        }
        val method = TranslationManager::class.java.declaredMethods.single { it.name == "processArchiveInPlace" }
            .apply { isAccessible = true }
        try {
            return suspendCoroutineUninterceptedOrReturn { continuation ->
                try {
                    method.invoke(manager, UniFile.fromFile(archive), { _: Int, _: Int ->
                    }, shouldStop, callback, continuation)
                } catch (error: InvocationTargetException) {
                    throw checkNotNull(error.cause)
                }
            }
        } finally {
            // This helper owns a separate manager, never the application's singleton queue.
            val scope = TranslationManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
                .get(manager) as CoroutineScope
            scope.cancel()
        }
    }

    private suspend fun withOwnedDirectory(prefix: String, block: suspend (File) -> Unit) {
        assertEquals(
            "Archive controls require the isolated audit installation",
            "app.yomitori.yakuyomi.audit",
            context.packageName,
        )
        val cache = context.cacheDir.canonicalFile
        val name = "$prefix-${UUID.randomUUID()}"
        val directory = File(cache, name)
        check(directory.mkdir())
        try {
            block(directory)
        } finally {
            check(directory.canonicalFile.parentFile == cache && directory.name == name)
            check(directory.deleteRecursively()) { "Cannot remove owned archive control fixture" }
        }
    }

    private fun ownedStaging(extracted: UniFile): File {
        val work = checkNotNull(File(checkNotNull(extracted.uri.path)).canonicalFile.parentFile)
        check(work.name.startsWith("yakutr_") && work.parentFile == context.cacheDir.canonicalFile)
        return work
    }

    private fun assertPackedBytes(archive: File, name: String, expected: ByteArray) {
        ZipFile(archive).use { zip ->
            val entry = checkNotNull(zip.getEntry(name))
            assertEquals(expected.size.toLong(), entry.size)
            val actual = zip.getInputStream(entry).use { it.readBytes() }
            assertArrayEquals(expected, actual)
            assertEquals(java.util.zip.CRC32().apply { update(actual) }.value, entry.crc)
        }
    }

    private fun zip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }
}
