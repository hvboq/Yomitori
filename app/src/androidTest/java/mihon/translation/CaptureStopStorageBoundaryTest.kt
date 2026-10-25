package mihon.translation

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.ui.capture.CaptureSiteSetting
import eu.kanade.tachiyomi.ui.capture.CaptureViewModel
import eu.kanade.tachiyomi.ui.capture.FrameGrabber
import eu.kanade.tachiyomi.ui.capture.readMangaMeta
import eu.kanade.tachiyomi.ui.capture.readMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import mihon.testprovider.SafAliasDocumentsProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real UUID-owned SAF IO with synthetic frames; no Activity, native inference or HTTP. */
@RunWith(AndroidJUnit4::class)
class CaptureStopStorageBoundaryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 45_000)
    fun stopDuringProviderWriteAccountsCommittedPageWhenOriginalLoopSettles(): Unit = runBlocking {
        withFixture { fixture ->
            val gate = fixture.gateFreshWrite()
            val frames = fixture.frames()
            val original = fixture.start(frames)
            fixture.awaitGate(gate, original)

            onMain { fixture.model.stopContinuous() }
            assertFalse("The gated IO must still be outstanding until explicitly released", original.isCompleted)
            assertEquals(0, gate.exitedCount)
            gate.release()
            fixture.join(original)

            fixture.assertCommittedPage(gate)
            frames.assertReleased()
            assertEquals(
                "A durable in-flight page must remain in the stopped capture session",
                listOf(1),
                onMain { fixture.model.sessionPages },
            )
            assertEquals("The durable page must be counted exactly once", 1, fixture.model.continuous.value.count)
            assertFalse(fixture.model.continuous.value.running)
            fixture.assertSettled(frames)
        }
    }

    @Test(timeout = 45_000)
    fun restartDuringUnsettledProviderWriteDoesNotReplaceOriginalLoop(): Unit = runBlocking {
        withFixture { fixture ->
            val gate = fixture.gateFreshWrite()
            val frames = fixture.frames()
            val original = fixture.start(frames)
            fixture.awaitGate(gate, original)
            val replacement = fixture.frames()

            val afterRestart = onMain {
                fixture.model.stopContinuous()
                fixture.startOnMain(replacement)
            }
            mainBarrier()
            assertFalse("The original provider write must remain unsettled", original.isCompleted)
            assertEquals(0, gate.exitedCount)
            assertSame("Restart must retain the unsettled original loop", original, afterRestart)
            assertEquals("Restart must not request another comparison frame", 0, replacement.compareCalls.get())
            assertEquals("Restart must not request another clean frame", 0, replacement.cleanCalls.get())

            gate.release()
            fixture.join(original)
            fixture.assertCommittedPage(gate)
            assertEquals(listOf(1), onMain { fixture.model.sessionPages })
            assertEquals(1, fixture.model.continuous.value.count)
            assertFalse(fixture.model.continuous.value.running)
            fixture.assertSettled(frames, replacement)
        }
    }

    @Test(timeout = 45_000)
    fun normallyReleasedProviderWriteIsAccountedBeforeStop(): Unit = runBlocking {
        withFixture { fixture ->
            val gate = fixture.gateFreshWrite()
            val frames = fixture.frames()
            val original = fixture.start(frames)
            fixture.awaitGate(gate, original)
            gate.release()
            withTimeout(10_000) {
                withContext(Dispatchers.Main.immediate) {
                    fixture.model.continuous.first { it.count == 1 }
                }
            }
            assertTrue("The ordinary loop remains live until the public Stop request", original.isActive)

            onMain { fixture.model.stopContinuous() }
            fixture.join(original)

            fixture.assertCommittedPage(gate)
            assertEquals(listOf(1), onMain { fixture.model.sessionPages })
            assertEquals(1, fixture.model.continuous.value.count)
            assertFalse(fixture.model.continuous.value.running)
            fixture.assertSettled(frames)
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        assertEquals("Isolated audit installation required", "app.yomitori.yakuyomi.audit", context.packageName)
        val session = SafAliasDocumentsProvider.createSession(context)
        val models = ViewModelStore()
        var fixture: Fixture? = null
        var storage: StorageManager? = null
        var safeToDelete = false
        var failure: Throwable? = null
        try {
            val authority = context.packageManager.getProviderInfo(
                ComponentName(context, SafAliasDocumentsProvider::class.java),
                0,
            ).authority
            val tree = DocumentsContract.buildTreeDocumentUri(authority, "${session.id}|root-a")
            val preferences = StoragePreferences(
                object : FolderProvider {
                    override fun directory(): File = session.directory
                    override fun path(): String = tree.toString()
                },
                InMemoryPreferenceStore(),
            )
            val privateStorage = StorageManager(context, preferences).also { storage = it }
            val local = checkNotNull(privateStorage.getLocalSourceDirectory())
            assertEquals("The fixture must exercise the real content tree", "content", local.uri.scheme)
            val manga = checkNotNull(local.createDirectory(BOOK))
            val chapter = checkNotNull(manga.createDirectory(CHAPTER))
            val previous = checkNotNull(chapter.createFile(PREVIOUS))
            val previousBytes = encodedPrevious()
            checkNotNull(context.contentResolver.openOutputStream(previous.uri, "wt")).use {
                it.write(previousBytes)
            }
            val model = onMain {
                CaptureViewModel(
                    context.applicationContext as Application,
                    privateStorage,
                    UiPreferences(InMemoryPreferenceStore()),
                ).also {
                    models.put("private-stop-boundary-capture", it)
                    it.bookName = BOOK
                    it.chapterName = CHAPTER
                    it.saveSiteSetting(CAPTURE_URL, CaptureSiteSetting(autoTrim = false))
                }
            }
            fixture = Fixture(session, manga, chapter, model, previousBytes)
            session.createRequests.clear()
            session.writeModes.clear()
            block(fixture)
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            suspend fun cleanup(action: suspend () -> Unit) {
                try {
                    action()
                } catch (caught: Throwable) {
                    if (failure == null) failure = caught else failure!!.addSuppressed(caught)
                }
            }
            // A failed assertion must never leave a Binder/IO thread gated while its files are removed.
            fixture?.releaseGates()
            var loopsStopped = fixture == null
            var modelStopped = fixture == null
            cleanup {
                fixture?.stopAndJoinLoops()
                loopsStopped = true
            }
            cleanup {
                val modelJob = fixture?.let { checkNotNull(it.model.viewModelScope.coroutineContext[Job]) }
                onMain { models.clear() }
                withTimeout(10_000) { modelJob?.join() }
                check(loopsStopped) { "Capture loops are still live; retain their fixture" }
                modelStopped = true
            }
            cleanup {
                storage?.let { privateStorage ->
                    val scope = StorageManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
                        .get(privateStorage) as CoroutineScope
                    scope.cancel()
                    withTimeout(5_000) { scope.coroutineContext[Job]?.join() }
                }
                safeToDelete = loopsStopped && modelStopped
            }
            cleanup {
                check(safeToDelete) { "Live work may still borrow frames; retain them" }
                fixture?.releaseLeftoverFrames()
            }
            cleanup {
                check(safeToDelete) { "Private storage or capture work did not stop; retain its owned fixture" }
                check(
                    session.directory.canonicalFile ==
                        File(context.cacheDir.canonicalFile, "saf-alias-${session.id}"),
                )
                session.close()
            }
        }
        failure?.let { throw it }
    }

    private inner class Fixture(
        val session: SafAliasDocumentsProvider.Session,
        val manga: UniFile,
        val chapter: UniFile,
        val model: CaptureViewModel,
        private val previousBytes: ByteArray,
    ) {
        private val jobs = CopyOnWriteArrayList<Job>()
        private val gates = CopyOnWriteArrayList<SafAliasDocumentsProvider.WriteGate>()
        private val frameTrackers = CopyOnWriteArrayList<Frames>()
        private val chapterFile = session.checked(File(session.directory, "local/$BOOK/$CHAPTER"))
        private val freshFile = session.checked(File(chapterFile, PAGE))

        fun gateFreshWrite(): SafAliasDocumentsProvider.WriteGate = session.gateNextWrite(freshFile).also {
            gates.add(it)
        }

        fun frames(): Frames = Frames().also { frameTrackers.add(it) }

        fun start(frames: Frames): Job = onMain { startOnMain(frames) }

        fun startOnMain(frames: Frames): Job {
            model.startContinuous(frames.compare, frames.clean, { CAPTURE_URL })
            return checkNotNull(currentJob()).also { if (!jobs.contains(it)) jobs.add(it) }
        }

        private fun currentJob(): Job? = CaptureViewModel::class.java.getDeclaredField("continuousJob")
            .apply { isAccessible = true }.get(model) as Job?

        suspend fun awaitGate(gate: SafAliasDocumentsProvider.WriteGate, original: Job) {
            assertTrue("The exact new document must reach the real provider write", gate.awaitEntered(10_000))
            assertEquals(1, gate.enteredCount)
            assertEquals(0, gate.exitedCount)
            assertFalse(original.isCompleted)
            assertTrue("The provider must have created the actual file before gating", freshFile.isFile)
            assertEquals(0L, freshFile.length())
            assertTrue(
                "The exact document must use the truncating ContentResolver path",
                session.writeModes.any { it.first == gate.documentId && it.second == "wt" },
            )
            assertEquals(session.documentId(freshFile), gate.documentId)
            assertEquals(0, model.continuous.value.count)
            assertTrue(onMain { model.sessionPages.isEmpty() })
        }

        suspend fun join(job: Job) {
            withTimeout(10_000) { job.join() }
            mainBarrier()
            assertTrue(job.isCompleted)
        }

        fun assertCommittedPage(gate: SafAliasDocumentsProvider.WriteGate) {
            assertEquals(1, gate.enteredCount)
            assertEquals(1, gate.exitedCount)
            assertFalse("The controlled gate must not time out", gate.timedOut)
            assertFalse("Stop must not interrupt the fixture IO thread", gate.interrupted)
            val saved = checkNotNull(chapter.findFile(PAGE)) { "The in-flight page must actually be stored" }
            assertEquals("content", saved.uri.scheme)
            assertEquals(gate.documentId, DocumentsContract.getDocumentId(saved.uri))
            val bytes = read(saved.uri)
            assertArrayEquals(freshFile.readBytes(), bytes)
            assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), bytes.copyOfRange(0, 8))
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                assertEquals(SIZE, bitmap.width)
                assertEquals(SIZE, bitmap.height)
                assertEquals(Color.BLACK, bitmap.getPixel(SIZE / 4, SIZE / 2))
                assertEquals(Color.WHITE, bitmap.getPixel(3 * SIZE / 4, SIZE / 2))
            } finally {
                bitmap.recycle()
            }
            assertEquals(CAPTURE_URL, readMeta(chapter)["001"])
            assertEquals(CAPTURE_URL, readMangaMeta(manga))
            assertPreviousUnchanged()
        }

        suspend fun assertSettled(vararg frames: Frames) {
            val before = snapshot()
            val calls = frames.map { it.compareCalls.get() to it.cleanCalls.get() }
            delay(700)
            mainBarrier()
            assertEquals("No owned file may change after the original loop settled", before, snapshot())
            assertEquals(
                "No frame may be requested after the original loop settled",
                calls,
                frames.map { it.compareCalls.get() to it.cleanCalls.get() },
            )
            frames.forEach { it.assertReleased() }
            assertPreviousUnchanged()
        }

        fun releaseGates() {
            gates.forEach { it.release() }
        }

        suspend fun stopAndJoinLoops() {
            releaseGates()
            onMain { model.stopContinuous() }
            jobs.forEach { it.cancel() }
            withTimeout(10_000) { jobs.forEach { it.join() } }
        }

        fun releaseLeftoverFrames() {
            frameTrackers.forEach { it.releaseLeftovers() }
        }

        private fun read(uri: Uri): ByteArray =
            checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }

        private fun assertPreviousUnchanged() {
            val previous = checkNotNull(chapter.findFile(PREVIOUS))
            assertArrayEquals(previousBytes, read(previous.uri))
            assertArrayEquals(previousBytes, File(chapterFile, PREVIOUS).readBytes())
        }

        private fun snapshot(): Map<String, String> = session.directory.walkTopDown().filter { it.isFile }.associate {
            check(session.checked(it) == it)
            it.relativeTo(session.directory).invariantSeparatorsPath to sha256(it.readBytes())
        }
    }

    private class Frames {
        val compareCalls = AtomicInteger()
        val cleanCalls = AtomicInteger()
        private val owned = CopyOnWriteArrayList<Bitmap>()
        val compare: FrameGrabber = { callback ->
            compareCalls.incrementAndGet()
            callback(fresh())
        }
        val clean: FrameGrabber = { callback ->
            cleanCalls.incrementAndGet()
            callback(fresh())
        }

        private fun fresh(): Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.setPixels(
                IntArray(SIZE * SIZE) { if (it % SIZE < SIZE / 2) Color.BLACK else Color.WHITE },
                0,
                SIZE,
                0,
                0,
                SIZE,
                SIZE,
            )
            owned.add(bitmap)
        }

        fun assertReleased() {
            assertTrue("Every delivered comparison/clean frame must be released", owned.all { it.isRecycled })
        }

        fun releaseLeftovers() {
            val leaked = owned.filterNot { it.isRecycled }
            leaked.forEach { it.recycle() }
            assertTrue("The completed loops must release their owned frames", leaked.isEmpty())
        }
    }

    private suspend fun mainBarrier() = withContext(Dispatchers.Main.immediate) { yield() }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return checkNotNull(result.get()).getOrThrow()
    }

    private fun encodedPrevious(): ByteArray {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        return try {
            ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val BOOK = "NeutralStopCapture"
        const val CHAPTER = "01"
        const val PAGE = "001.png"
        const val PREVIOUS = "existing.png"
        const val SIZE = 64
        const val CAPTURE_URL = "https://capture.example.invalid/stop-boundary"

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
