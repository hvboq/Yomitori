// This test-only package permits delegation through UniFile's package-private constructor.
package com.hippo.unifile

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.PageTranslator
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual manifest transactions with a controlled reader/writer interleaving; no engine or server. */
@RunWith(AndroidJUnit4::class)
class ManifestReadWriteRaceTest {
    @Test(timeout = 30_000)
    fun checkpointCannotDisappearBetweenListingAndFileCheck() = runRace(PauseBefore.FILE_CHECK)

    @Test(timeout = 30_000)
    fun checkpointCannotDisappearBetweenFileCheckAndStreamOpen() = runRace(PauseBefore.STREAM_OPEN)

    private fun runRace(pauseBefore: PauseBefore) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") {
            "Manifest race regressions require the isolated audit installation"
        }
        val runId = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "manifest-read-write-$runId").apply { check(mkdir()) }
        check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
        val checkpointName = CompletionManifestCodec.CHECKPOINT_PREFIX + UUID.randomUUID()
        val sourceBytes = mapOf(PAGE to byteArrayOf(1, 2, 3), NEXT_PAGE to byteArrayOf(4, 5, 6))
        sourceBytes.forEach { (name, bytes) -> File(directory, name).writeBytes(bytes) }
        val previous = CompletionManifestCodec.encode(setOf(PAGE))
        File(directory, CompletionManifestCodec.PRIMARY).writeBytes(previous)
        val oldCheckpoint = File(directory, checkpointName).apply { writeBytes(previous) }
        val plainChapter = requireNotNull(UniFile.fromFile(directory))
        val gate = ReadGate(checkpointName, pauseBefore)
        val readerChapter = PausingUniFile(plainChapter, gate)
        val readerTranslator = PageTranslator(context)
        val writerTranslator = PageTranslator(context)
        val readerOutcome = AtomicReference<Result<Set<String>>>()
        val writerOutcome = AtomicReference<Result<Unit>>()
        val writerAttempted = CountDownLatch(1)
        val writerFinished = CountDownLatch(1)
        val reader = Thread({
            readerOutcome.set(runCatching { readDone(readerTranslator, readerChapter) })
        }, "manifest-race-reader-$runId")
        val writer = Thread({
            writerAttempted.countDown()
            try {
                writerOutcome.set(runCatching { writeDone(writerTranslator, plainChapter, setOf(PAGE, NEXT_PAGE)) })
            } finally {
                writerFinished.countDown()
            }
        }, "manifest-race-writer-$runId")
        var writerBlockedAtCommit = false
        try {
            reader.start()
            assertTrue("Reader must reach the listed checkpoint", gate.paused.await(5, TimeUnit.SECONDS))
            writer.start()
            assertTrue("Writer must attempt the actual commit", writerAttempted.await(5, TimeUnit.SECONDS))
            // An unlocked baseline finishes the writer and deletes this stale checkpoint. A fixed
            // writer blocks on the snapshot monitor. Both are observable scheduling fences; the
            // test never waits for a writer that cannot progress while the reader owns that monitor.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (writerFinished.count != 0L) {
                if (writer.state == Thread.State.BLOCKED &&
                    writer.stackTrace.firstOrNull()?.let {
                        it.className == PageTranslator::class.java.name && it.methodName == "writeManifest"
                    } == true
                ) {
                    writerBlockedAtCommit = true
                    break
                }
                check(System.nanoTime() < deadline) { "Writer neither committed nor blocked at the snapshot monitor" }
                Thread.sleep(5)
            }
            gate.release.countDown()
            reader.join(5_000)
            writer.join(5_000)
            assertFalse("Reader must terminate", reader.isAlive)
            assertFalse("Writer must terminate", writer.isAlive)
            val read = checkNotNull(readerOutcome.get())
            val write = checkNotNull(writerOutcome.get())
            val diagnostic = JSONObject()
                .put("case", pauseBefore.name)
                .put("readerGateHits", gate.hits.get())
                .put("writerBlockedAtCommit", writerBlockedAtCommit)
                .put("writerFailureClass", write.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("readerFailureClass", read.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("readerCompletedPageCount", read.getOrNull()?.size ?: JSONObject.NULL)
                .put("oldCheckpointExistsAfterCommit", oldCheckpoint.exists())
            println("MANIFEST_READ_WRITE_RACE=$diagnostic")
            assertNull("Actual manifest commit must succeed: $diagnostic", write.exceptionOrNull())
            assertEquals("Exactly one checkpoint read is fenced", 1, gate.hits.get())
            assertFalse("Actual commit must retire the checkpoint held by the reader", oldCheckpoint.exists())
            sourceBytes.forEach { (name, bytes) ->
                assertArrayEquals(
                    "Manifest concurrency must preserve source bytes",
                    bytes,
                    File(directory, name).readBytes(),
                )
            }
            assertEquals(setOf(PAGE, NEXT_PAGE), readDone(readerTranslator, plainChapter))
            assertNull(
                "A concurrent commit must not invalidate an authoritative read: $diagnostic",
                read.exceptionOrNull(),
            )
            assertEquals(
                "The reader owns the complete old snapshot until its read ends",
                setOf(PAGE),
                read.getOrThrow(),
            )
            assertTrue("Writer must wait for the reader's snapshot transaction: $diagnostic", writerBlockedAtCommit)
        } finally {
            gate.release.countDown()
            reader.join(5_000)
            if (writer.state != Thread.State.NEW) writer.join(5_000)
            check(!reader.isAlive && !writer.isAlive) { "Cannot delete a fixture still used by its worker threads" }
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(directory.deleteRecursively()) { "Cannot remove the private manifest-race fixture" }
            check(!directory.exists())
        }
    }

    private enum class PauseBefore { FILE_CHECK, STREAM_OPEN }

    private class ReadGate(private val checkpoint: String, private val pauseBefore: PauseBefore) {
        val paused = CountDownLatch(1)
        val release = CountDownLatch(1)
        val hits = AtomicInteger()
        private val fileChecks = AtomicInteger()
        fun beforeFileCheck(name: String?) {
            // The first isFile call includes this checkpoint in the manifest snapshot. Fence the
            // second call, made by readManifestBytes, before it can observe a concurrent deletion.
            if (name == checkpoint && fileChecks.incrementAndGet() == 2 && pauseBefore == PauseBefore.FILE_CHECK) {
                pause()
            }
        }
        fun beforeUri(name: String?) {
            if (name == checkpoint && pauseBefore == PauseBefore.STREAM_OPEN) pause()
        }
        private fun pause() {
            check(hits.incrementAndGet() == 1) { "Reader checkpoint was fenced more than once" }
            paused.countDown()
            check(release.await(15, TimeUnit.SECONDS)) { "Reader scheduling fence timed out" }
        }
    }

    /** Only the owned reader's checkpoint is paused; all bytes and writes use real RawFile I/O. */
    private class PausingUniFile(private val delegate: UniFile, private val gate: ReadGate) : UniFile(null) {
        private fun wrap(file: UniFile?): UniFile? = file?.let { PausingUniFile(it, gate) }
        override fun createFile(name: String): UniFile? = wrap(delegate.createFile(name))
        override fun createDirectory(name: String): UniFile? = wrap(delegate.createDirectory(name))
        override fun getUri(): Uri {
            gate.beforeUri(delegate.name)
            return delegate.uri
        }
        override fun getName(): String? = delegate.name
        override fun getType(): String? = delegate.type
        override fun getFilePath(): String? = delegate.filePath
        override fun isDirectory(): Boolean = delegate.isDirectory
        override fun isFile(): Boolean {
            gate.beforeFileCheck(delegate.name)
            return delegate.isFile
        }
        override fun lastModified(): Long = delegate.lastModified()
        override fun length(): Long = delegate.length()
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
        override fun openOutputStream(): OutputStream = delegate.openOutputStream()
        override fun openOutputStream(append: Boolean): OutputStream = delegate.openOutputStream(append)
        override fun openInputStream(): InputStream = delegate.openInputStream()
        override fun createRandomAccessFile(mode: String): UniRandomAccessFile = delegate.createRandomAccessFile(mode)
    }

    private companion object {
        const val PAGE = "001.png"
        const val NEXT_PAGE = "002.png"

        @Suppress("UNCHECKED_CAST")
        fun readDone(translator: PageTranslator, chapter: UniFile): Set<String> = invoke(
            PageTranslator::class.java.getDeclaredMethod("readDonePages", UniFile::class.java).apply {
                isAccessible =
                    true
            },
            translator,
            chapter,
        ) as Set<String>
        fun writeDone(translator: PageTranslator, chapter: UniFile, pages: Set<String>) {
            invoke(
                PageTranslator::class.java.getDeclaredMethod("writeManifest", UniFile::class.java, Set::class.java)
                    .apply { isAccessible = true },
                translator,
                chapter,
                pages,
            )
        }
        fun invoke(method: java.lang.reflect.Method, target: Any, vararg args: Any): Any? = try {
            method.invoke(target, *args)
        } catch (error: InvocationTargetException) {
            throw checkNotNull(error.cause)
        }
    }
}
