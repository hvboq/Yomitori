// This test-only package permits delegation through UniFile's package-private constructor.
package com.hippo.unifile

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Public original-only recovery with real private file writes; no Activity, native engine or server. */
@RunWith(AndroidJUnit4::class)
class OriginalRestoreFaultTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 30_000)
    fun originalOnlyPartialReplacementFailureRollsBackPreviousDisplay(): Unit = runBlocking {
        withFixture(Fault.PARTIAL_WRITE) { fixture ->
            assertFalse(fixture.translator.reRenderPage(fixture.chapter, PAGE, PageTranslator.ORIGINAL_METHOD))

            val faults = fixture.faults
            assertTrue("The actual first target stream must write a prefix before failing", faults.injected.get())
            val first = faults.streams.first()
            assertEquals(fixture.originalBytes.size / 2, first.written.size())
            assertArrayEquals(
                fixture.originalBytes.copyOfRange(0, fixture.originalBytes.size / 2),
                first.written.toByteArray(),
            )
            assertArrayEquals(
                "The failed first write really reached the file",
                first.written.toByteArray(),
                first.bytesAtClose,
            )
            assertRestored(fixture)
        }
    }

    @Test(timeout = 30_000)
    fun originalOnlySameLengthReadbackMismatchRollsBackPreviousDisplay(): Unit = runBlocking {
        withFixture(Fault.CORRUPT_AFTER_CLOSE) { fixture ->
            assertFalse(fixture.translator.reRenderPage(fixture.chapter, PAGE, PageTranslator.ORIGINAL_METHOD))

            val faults = fixture.faults
            assertTrue("The first post-close length check must reach the corruption hook", faults.injected.get())
            assertEquals(1, faults.postCloseHooks)
            assertArrayEquals(fixture.originalBytes, faults.streams.first().written.toByteArray())
            assertArrayEquals(
                "The actual first stream finished writing the source",
                fixture.originalBytes,
                faults.streams.first().bytesAtClose,
            )
            val corrupted = requireNotNull(faults.corruptedBytes)
            assertEquals("Corruption preserves the length check", fixture.originalBytes.size, corrupted.size)
            assertEquals(
                "Exactly one real stored byte is altered before readback",
                1,
                fixture.originalBytes.indices.count { fixture.originalBytes[it] != corrupted[it] },
            )
            assertRestored(fixture)
        }
    }

    @Test(timeout = 30_000)
    fun originalOnlyCancellationAfterReplacementRollsBackAndPropagates(): Unit = runBlocking {
        withFixture(Fault.CANCEL_AFTER_CLOSE) { fixture ->
            supervisorScope {
                val child = async {
                    fixture.faults.cancellationJob = requireNotNull(currentCoroutineContext()[Job])
                    fixture.translator.reRenderPage(fixture.chapter, PAGE, PageTranslator.ORIGINAL_METHOD)
                }
                val cancellation = try {
                    child.await()
                    null
                } catch (expected: CancellationException) {
                    expected
                }
                assertNotNull("The public entry point must propagate child cancellation", cancellation)
                assertTrue("Only the operation's child job is cancelled", child.isCancelled)
            }
            currentCoroutineContext().ensureActive()
            val faults = fixture.faults
            assertTrue("The first post-close length check must cancel the operation", faults.injected.get())
            assertEquals(1, faults.postCloseHooks)
            assertTrue("The child was active when the real write completed", faults.cancelledJobWasActive)
            assertArrayEquals(fixture.originalBytes, faults.streams.first().written.toByteArray())
            assertArrayEquals(
                "Cancellation happens after the source was actually stored",
                fixture.originalBytes,
                faults.streams.first().bytesAtClose,
            )
            assertRestored(fixture)
        }
    }

    private suspend fun withFixture(fault: Fault, block: suspend (Fixture) -> Unit) {
        check(context.packageName == "app.yomitori.yakuyomi.audit") {
            "Original recovery write faults require the isolated audit installation"
        }
        assertEquals("No other audit translation may overlap this fixture", 0, TranslationBusy.active.value)
        val name = "original-restore-fault-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, name)
        check(directory.mkdir())
        check(directory.canonicalFile == File(context.cacheDir.canonicalFile, name))
        try {
            val materials = File(directory, ".yakuyomi").apply { check(mkdir()) }
            val originalBytes = encoded(Color.WHITE)
            val original = File(materials, "001.orig.png").apply { writeBytes(originalBytes) }
            val page = File(directory, PAGE).apply { writeBytes(encoded(Color.BLACK)) }
            File(directory, "002.png").writeBytes(encoded(Color.BLUE))
            File(directory, "unrelated.txt").writeText("neutral unrelated chapter file")
            File(materials, "unrelated.bin").writeBytes(byteArrayOf(12, 34, 56))
            val before = snapshot(directory)
            val names = names(directory)
            assertEquals(
                setOf("001.png", "002.png", "unrelated.txt", ".yakuyomi/001.orig.png", ".yakuyomi/unrelated.bin"),
                before.keys,
            )
            assertFalse(
                "The source and previous display are deliberately different",
                originalBytes.contentEquals(page.readBytes()),
            )
            val faults = WriteFaults(page.canonicalFile, originalBytes, fault)
            val chapter = FaultingUniFile(requireNotNull(UniFile.fromFile(directory)), faults)
            val translator = PageTranslator(context)
            assertFalse("No OCR JSON or mask is prepared", translator.hasReRenderMaterials(chapter, PAGE))
            assertFalse("No journal or completion marker is prepared", translator.isPageTranslated(chapter, PAGE))
            block(Fixture(directory, original, page, chapter, translator, originalBytes, before, names, faults))
        } finally {
            check(directory.canonicalFile == File(context.cacheDir.canonicalFile, name))
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(directory.deleteRecursively()) { "Cannot remove the private original recovery fault fixture" }
            check(!directory.exists())
        }
    }

    private fun assertRestored(fixture: Fixture) {
        val faults = fixture.faults
        assertEquals("One replacement stream and one actual rollback stream must open", 2, faults.streams.size)
        for (stream in faults.streams) {
            assertEquals("Each real stream must truncate the private target", 0L, stream.lengthAtOpen)
            assertTrue("Each real stream must receive at least one write", stream.writeCalls > 0)
            assertEquals("Each opened stream must close exactly once", 1, stream.closeCalls)
            assertTrue("Each underlying file stream must close successfully", stream.closeSucceeded)
            assertEquals("Recovery must run inside the public busy scope", 1, stream.busyAtOpen)
        }
        val rollback = faults.streams[1]
        val previousDisplay = fixture.before.getValue(PAGE)
        assertArrayEquals(
            "The rollback stream writes the entire previous display",
            previousDisplay,
            rollback.written.toByteArray(),
        )
        assertEquals(previousDisplay.size, rollback.written.size())
        assertArrayEquals(
            "The rollback stream actually stores the previous display",
            previousDisplay,
            rollback.bytesAtClose,
        )
        assertArrayEquals(
            "The final visible file is byte-identical to its previous display",
            previousDisplay,
            fixture.page.readBytes(),
        )
        assertArrayEquals(
            "The preserved original remains byte-identical",
            fixture.originalBytes,
            fixture.original.readBytes(),
        )
        assertEquals("Failed or cancelled recovery must release the busy counter", 0, TranslationBusy.active.value)
        val after = snapshot(fixture.directory)
        assertEquals(
            "Recovery must not create, delete or rename any file or directory",
            fixture.namesBefore,
            names(fixture.directory),
        )
        assertEquals(
            "No manifest, journal, OCR JSON, mask or completion file may appear",
            fixture.before.keys,
            after.keys,
        )
        for ((name, bytes) in fixture.before) {
            assertArrayEquals(
                "Source, display and unrelated bytes must remain unchanged: $name",
                bytes,
                after.getValue(name),
            )
        }
        assertFalse(
            "Failure must not invent OCR material",
            fixture.translator.hasReRenderMaterials(fixture.chapter, PAGE),
        )
        assertFalse(
            "Failure must not invent a completed translation",
            fixture.translator.isPageTranslated(fixture.chapter, PAGE),
        )
    }

    private fun snapshot(directory: File): Map<String, ByteArray> = directory.walkTopDown()
        .filter { it.isFile }
        .associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes() }

    private fun names(directory: File): Set<String> = directory.walkTopDown()
        .filter { it != directory }
        .map { it.relativeTo(directory).invariantSeparatorsPath + if (it.isDirectory) "/" else "" }
        .toSet()

    private fun encoded(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try {
            ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private data class Fixture(
        val directory: File,
        val original: File,
        val page: File,
        val chapter: UniFile,
        val translator: PageTranslator,
        val originalBytes: ByteArray,
        val before: Map<String, ByteArray>,
        val namesBefore: Set<String>,
        val faults: WriteFaults,
    )

    private enum class Fault { PARTIAL_WRITE, CORRUPT_AFTER_CLOSE, CANCEL_AFTER_CLOSE }

    private class StreamRecord(val lengthAtOpen: Long, val busyAtOpen: Int) {
        val written = ByteArrayOutputStream()
        var writeCalls = 0
        var closeCalls = 0
        var closeSucceeded = false
        var bytesAtClose: ByteArray? = null
    }

    /** Faults apply once, only to the canonical target, after actual private file operations. */
    private class WriteFaults(private val target: File, private val replacement: ByteArray, private val fault: Fault) {
        val injected = AtomicBoolean()
        val streams = mutableListOf<StreamRecord>()
        var postCloseHooks = 0
        var corruptedBytes: ByteArray? = null
        var cancellationJob: Job? = null
        var cancelledJobWasActive = false

        private fun isTarget(file: UniFile): Boolean = file.uri.let { uri ->
            uri.scheme == "file" && uri.path?.let { File(it).canonicalFile == target } == true
        }

        fun output(file: UniFile, append: Boolean? = null): OutputStream {
            val output = if (append == null) file.openOutputStream() else file.openOutputStream(append)
            if (!isTarget(file)) return output
            val record = StreamRecord(target.length(), TranslationBusy.active.value)
            streams += record
            val first = streams.size == 1
            return object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    record.writeCalls++
                    if (first && fault == Fault.PARTIAL_WRITE && injected.compareAndSet(false, true)) {
                        check(length == replacement.size && length > 1) { "Expected the real replacement byte array" }
                        val count = length / 2
                        output.write(bytes, offset, count)
                        record.written.write(bytes, offset, count)
                        throw IOException("Synthetic failure after a real partial original replacement")
                    }
                    output.write(bytes, offset, length)
                    record.written.write(bytes, offset, length)
                }

                override fun flush() = output.flush()

                override fun close() {
                    record.closeCalls++
                    output.close()
                    record.closeSucceeded = true
                    record.bytesAtClose = target.readBytes()
                }
            }
        }

        fun length(file: UniFile): Long {
            if (isTarget(file) && fault != Fault.PARTIAL_WRITE &&
                streams.firstOrNull()?.let { it.closeSucceeded && it.written.size() == replacement.size } == true &&
                injected.compareAndSet(false, true)
            ) {
                postCloseHooks++
                check(target.readBytes().contentEquals(replacement)) { "Post-close hook must see the real replacement" }
                check(TranslationBusy.active.value == 1) { "The public operation must still be busy" }
                when (fault) {
                    Fault.CORRUPT_AFTER_CLOSE -> {
                        val corrupted = target.readBytes()
                        val index = corrupted.size / 2
                        corrupted[index] = (corrupted[index].toInt() xor 1).toByte()
                        target.writeBytes(corrupted)
                        corruptedBytes = target.readBytes()
                        check(target.length() == replacement.size.toLong())
                    }
                    Fault.CANCEL_AFTER_CLOSE -> {
                        val job = checkNotNull(cancellationJob)
                        cancelledJobWasActive = job.isActive
                        check(cancelledJobWasActive)
                        job.cancel(CancellationException("Synthetic child cancellation after original replacement"))
                    }
                    Fault.PARTIAL_WRITE -> error("Partial write faults do not use a length hook")
                }
            }
            return file.length()
        }
    }

    /** Keep recursive directory lookups wrapped; URI reads still reach actual private file bytes. */
    private class FaultingUniFile(private val delegate: UniFile, private val faults: WriteFaults) : UniFile(null) {
        private fun wrap(file: UniFile?): UniFile? = file?.let { FaultingUniFile(it, faults) }
        override fun createFile(name: String): UniFile? = wrap(delegate.createFile(name))
        override fun createDirectory(name: String): UniFile? = wrap(delegate.createDirectory(name))
        override fun getUri(): Uri = delegate.uri
        override fun getName(): String? = delegate.name
        override fun getType(): String? = delegate.type
        override fun getFilePath(): String? = delegate.filePath
        override fun isDirectory(): Boolean = delegate.isDirectory
        override fun isFile(): Boolean = delegate.isFile
        override fun lastModified(): Long = delegate.lastModified()
        override fun length(): Long = faults.length(delegate)
        override fun canRead(): Boolean = delegate.canRead()
        override fun canWrite(): Boolean = delegate.canWrite()
        override fun delete(): Boolean = delegate.delete()
        override fun exists(): Boolean = delegate.exists()
        override fun listFiles(): Array<UniFile>? = delegate.listFiles()?.map {
            requireNotNull(wrap(it))
        }?.toTypedArray()
        override fun listFiles(filter: FilenameFilter): Array<UniFile>? =
            delegate.listFiles(filter)?.map { requireNotNull(wrap(it)) }?.toTypedArray()
        override fun findFile(name: String): UniFile? = wrap(delegate.findFile(name))
        override fun renameTo(name: String): Boolean = delegate.renameTo(name)
        override fun openOutputStream(): OutputStream = faults.output(delegate)
        override fun openOutputStream(append: Boolean): OutputStream = faults.output(delegate, append)
        override fun openInputStream(): InputStream = delegate.openInputStream()
        override fun createRandomAccessFile(mode: String): UniRandomAccessFile = delegate.createRandomAccessFile(mode)
    }

    private companion object {
        const val SIZE = 32
        const val PAGE = "001.png"
    }
}
