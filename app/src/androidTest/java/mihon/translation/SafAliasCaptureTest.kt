package mihon.translation

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.OriginalMaterial
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.TranslationContext
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.TranslatorConfig
import mihon.testprovider.SafAliasDocumentsProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import java.io.ByteArrayOutputStream
import java.io.File

/** Actual target ContentResolver/DocumentsProvider, synthetic trees; no picker, Activity or native. */
@RunWith(AndroidJUnit4::class)
class SafAliasCaptureTest {
    @Test(timeout = 30_000)
    fun directAndTreeAliasesShareBytesAndLegacyPersistWorksBeforeCapture(): Unit = runBlocking {
        withFixture { fixture ->
            fixture.assertAliases()
            val source = image(Color.WHITE)
            val output = image(Color.GREEN)
            try {
                assertTrue(fixture.persistLegacy(source, output))
            } finally {
                source.recycle()
                output.recycle()
            }
            assertTrue(fixture.translator.isPageTranslated(fixture.chapterB, PAGE))
            assertArrayEquals(fixture.initial, fixture.original.readBytes())
            assertTrue(fixture.session.writeModes.any { it.first == fixture.pageId && it.second == "wt" })
        }
    }

    @Test(timeout = 30_000)
    fun failedRecaptureThroughAnotherTreeRejectsLegacyPixels(): Unit = runBlocking {
        withFixture { fixture ->
            fixture.assertAliases()
            fixture.addBasenameCollision()
            val before = fixture.snapshot()
            fixture.rejectCaptureThroughB()
            fixture.assertSnapshot(before)
            val source = image(Color.WHITE)
            val output = image(Color.GREEN)
            try {
                assertFalse(
                    "Failed recapture through tree B must invalidate already computed tree A pixels",
                    fixture.persistLegacy(source, output),
                )
            } finally {
                source.recycle()
                output.recycle()
            }
            fixture.assertSnapshot(before)
            assertFalse(fixture.translator.isPageTranslated(fixture.chapterA, PAGE))
        }
    }

    @Test(timeout = 30_000)
    fun failedRecaptureThroughAnotherTreeInvalidatesHeldInference(): Unit = runBlocking {
        withFixture { fixture ->
            fixture.assertAliases()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val engine = fixture.select(HeldEngine(entered, release))
            coroutineScope {
                val operation = async(Dispatchers.Default) {
                    fixture.translator.translateSinglePage(fixture.chapterA, PAGE, METHOD)
                }
                try {
                    withTimeout(5_000) { entered.await() }
                    fixture.addBasenameCollision()
                    fixture.rejectCaptureThroughB()
                    assertArrayEquals(fixture.initial, fixture.session.page.readBytes())
                    assertArrayEquals(fixture.initial, fixture.original.readBytes())
                    release.complete(Unit)
                    assertEquals(
                        "A failed alias recapture must discard a result prepared before its fence",
                        PageTranslator.SinglePageResult.NOT_TRANSLATED,
                        withTimeout(5_000) { operation.await() },
                    )
                    assertEquals(1, engine.freshCalls)
                    assertTrue("Discarded scripted result must be recycled", requireNotNull(engine.output).isRecycled)
                    assertArrayEquals(fixture.initial, fixture.session.page.readBytes())
                    assertArrayEquals(fixture.initial, fixture.original.readBytes())
                    assertFalse(fixture.translator.isPageTranslated(fixture.chapterA, PAGE))
                    assertFalse(fixture.translator.isPageTranslated(fixture.chapterB, PAGE))
                } finally {
                    release.complete(Unit)
                    withContext(NonCancellable) { withTimeout(5_000) { operation.join() } }
                }
            }
        }
    }

    @Test(timeout = 30_000)
    fun pageMutexSerializesLegacyPersistenceAcrossTreeAliases(): Unit = runBlocking {
        withFixture { fixture ->
            fixture.assertAliases()
            val a = fixture.pageA
            val b = fixture.pageB
            assertNotEquals(
                "The fixture must distinguish the former raw-URI lock stripes",
                a.uri.hashCode().mod(32),
                b.uri.hashCode().mod(32),
            )
            // Private-lock reflection is intentional: observe the public API waiting on the
            // actual production mutex without timing sleeps or inventing provider write races.
            val lock = PageTranslator.Companion::class.java.getDeclaredMethod("pageLock", UniFile::class.java)
                .apply { isAccessible = true }.invoke(PageTranslator.Companion, a) as Mutex
            val owner = Any()
            val source = image(Color.WHITE)
            val output = image(Color.GREEN)
            coroutineScope {
                lock.lock(owner)
                val operation = async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.translator.persistLivePage(
                        fixture.chapterB,
                        b,
                        source,
                        output,
                        analysis = null,
                        methodRaw = METHOD,
                    )
                }
                try {
                    assertTrue(lock.holdsLock(owner))
                    assertFalse("Alias B must suspend at the page lock held through alias A", operation.isCompleted)
                    assertTrue(
                        "Suspended persistence must not open a provider write",
                        fixture.session.writeModes.isEmpty(),
                    )
                    assertArrayEquals(fixture.initial, fixture.session.page.readBytes())
                    lock.unlock(owner)
                    assertTrue(withTimeout(5_000) { operation.await() })
                    assertArrayEquals(fixture.initial, fixture.original.readBytes())
                    assertFalse(fixture.initial.contentEquals(fixture.read(a.uri)))
                    assertTrue(fixture.translator.isPageTranslated(fixture.chapterA, PAGE))
                } finally {
                    if (lock.holdsLock(owner)) lock.unlock(owner)
                    try {
                        withContext(NonCancellable) { withTimeout(5_000) { operation.join() } }
                    } finally {
                        source.recycle()
                        output.recycle()
                    }
                }
            }
        }
    }

    @Test(timeout = 30_000)
    fun captureUsesProviderTruncationAndVerifiesBytesThroughBothTrees(): Unit = runBlocking {
        withFixture { fixture ->
            val longer = fixture.initial + ByteArray(4096) { 71 }
            fixture.session.page.writeBytes(longer)
            fixture.original.writeBytes(longer)
            val replacement = encoded(Color.RED)
            assertTrue(replacement.size < longer.size)
            fixture.translator.replaceCapturedSource(fixture.chapterB, PAGE, replacement)
            assertArrayEquals(replacement, fixture.session.page.readBytes())
            assertArrayEquals(replacement, fixture.read(fixture.pageA.uri))
            assertArrayEquals(replacement, fixture.read(fixture.pageB.uri))
            assertArrayEquals(replacement, fixture.original.readBytes())
            assertTrue(fixture.session.writeModes.any { it.first == fixture.pageId && it.second == "wt" })
            assertTrue(fixture.pageId in fixture.session.reads)
            assertFalse(fixture.translator.isPageTranslated(fixture.chapterA, PAGE))
            assertFalse(fixture.translator.isPageTranslated(fixture.chapterB, PAGE))
        }
    }

    @Test(timeout = 30_000)
    fun providerFailureAfterTruncatingPageRollsBackOriginalAndDisplay(): Unit = runBlocking {
        withFixture { fixture ->
            fixture.session.failNextPageWrite.set(true)
            var rejected = false
            try {
                fixture.translator.replaceCapturedSource(fixture.chapterB, PAGE, encoded(Color.RED))
            } catch (_: java.io.IOException) {
                rejected = true
            } catch (_: IllegalStateException) {
                rejected = true
            }
            assertTrue("A real provider replacement failure must be reported", rejected)
            assertFalse(
                "The intentional one-shot provider fault must have been reached",
                fixture.session.failNextPageWrite.get(),
            )
            assertArrayEquals(fixture.initial, fixture.read(fixture.pageA.uri))
            assertArrayEquals(fixture.initial, fixture.read(fixture.pageB.uri))
            assertArrayEquals(fixture.initial, fixture.original.readBytes())
            assertTrue(
                "Replacement and rollback must both use wt",
                fixture.session.writeModes.count {
                    it.first == fixture.pageId && it.second == "wt"
                } >= 2,
            )
            assertFalse(fixture.translator.isPageTranslated(fixture.chapterA, PAGE))
            assertFalse(fixture.translator.isPageTranslated(fixture.chapterB, PAGE))
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation required" }
        val session = SafAliasDocumentsProvider.createSession(target)
        try {
            val privateFiles = File(session.directory, "private-files").apply { check(mkdir()) }
            val isolated = object : ContextWrapper(target) {
                override fun getFilesDir(): File = privateFiles
            }
            val storageField = TranslationEngineConfig::class.java.getDeclaredField("storagePreferences").apply {
                isAccessible = true
            }
            val priorStorage = storageField.get(TranslationEngineConfig)
            val memoryStorage = StoragePreferences(
                object : FolderProvider {
                    override fun directory(): File = session.directory
                    override fun path(): String = ""
                },
                InMemoryPreferenceStore(),
            )
            var fixture: Fixture? = null
            try {
                storageField.set(TranslationEngineConfig, memoryStorage)
                val models = File(privateFiles, "models").apply { check(mkdir()) }
                for (base in listOf("dbnet_detect.ncnn", "ocr_48px_ctc.ncnn", "aot")) {
                    File(models, "$base.param").writeBytes(byteArrayOf(0))
                    File(models, "$base.bin").writeBytes(byteArrayOf(0))
                }
                assertTrue(TranslationEngineConfig.modelsResolvable(isolated))
                fixture = Fixture(isolated, session)
                block(fixture)
            } finally {
                try {
                    withContext(NonCancellable) { fixture?.close() }
                } finally {
                    storageField.set(TranslationEngineConfig, priorStorage)
                    assertSame(priorStorage, storageField.get(TranslationEngineConfig))
                }
            }
        } finally {
            session.close()
        }
    }

    private class Fixture(val context: Context, val session: SafAliasDocumentsProvider.Session) {
        val initial = encoded(Color.WHITE)
        val materials = File(session.chapter, ".yakuyomi").apply { check(mkdir()) }
        val original = File(materials, OriginalMaterial.exactName(PAGE)).apply { writeBytes(initial) }
        val pageId = session.documentId(session.page)
        private val authority = context.packageManager.getProviderInfo(
            ComponentName(context, SafAliasDocumentsProvider::class.java),
            0,
        ).authority
        private val treeA = DocumentsContract.buildTreeDocumentUri(authority, "${session.id}|root-a")
        private val treeB = DocumentsContract.buildTreeDocumentUri(authority, "${session.id}|root-b")
        private val chapterId = session.documentId(session.chapter)
        val chapterA =
            requireNotNull(UniFile.fromUri(context, DocumentsContract.buildDocumentUriUsingTree(treeA, chapterId)))
        val chapterB =
            requireNotNull(UniFile.fromUri(context, DocumentsContract.buildDocumentUriUsingTree(treeB, chapterId)))
        val pageA get() = requireNotNull(chapterA.findFile(PAGE))
        val pageB get() = requireNotNull(chapterB.findFile(PAGE))
        private val preferences = TranslationPreferences(InMemoryPreferenceStore())
        private val service = TranslationEngineService(context).also {
            field("translationPreferences").set(it, preferences)
            val signature = TranslationEngineService::class.java.getDeclaredMethod(
                "configSignature",
                String::class.java,
                TranslationPreferences::class.java,
            ).apply { isAccessible = true }.invoke(it, METHOD, preferences.snapshot())
            field("builtSignature").set(it, signature)
            field("warmedUp").setBoolean(it, true)
        }
        val translator = PageTranslator(context).also {
            PageTranslator::class.java.getDeclaredField("translationPreferences").apply { isAccessible = true }
                .set(it, preferences)
            PageTranslator::class.java.getDeclaredField("engineService").apply { isAccessible = true }
                .set(it, service)
        }
        private var engine: HeldEngine? = null

        init {
            session.page.writeBytes(initial)
        }

        fun assertAliases() {
            val a = pageA
            val b = pageB
            val direct = DocumentsContract.buildDocumentUri(authority, pageId)
            assertTrue(DocumentsContract.isDocumentUri(context, a.uri))
            assertTrue(DocumentsContract.isTreeUri(a.uri))
            assertNotEquals(a.uri, b.uri)
            assertNotEquals(a.uri, direct)
            assertEquals(pageId, DocumentsContract.getDocumentId(a.uri))
            assertEquals(pageId, DocumentsContract.getDocumentId(b.uri))
            assertEquals(pageId, DocumentsContract.getDocumentId(direct))
            assertEquals(a.uri.authority, b.uri.authority)
            assertArrayEquals(initial, read(a.uri))
            assertArrayEquals(initial, read(b.uri))
            assertArrayEquals(initial, read(direct))
        }

        fun read(uri: Uri): ByteArray = requireNotNull(context.contentResolver.openInputStream(uri)).use {
            it.readBytes()
        }

        fun addBasenameCollision() = File(session.chapter, "target.jpg").writeBytes(initial)

        suspend fun rejectCaptureThroughB() {
            var reason: String? = null
            try {
                translator.replaceCapturedSource(chapterB, PAGE, encoded(Color.RED))
            } catch (failure: IllegalStateException) {
                reason = failure.message
            }
            assertEquals("Capture page shares materials with another image", reason)
        }

        suspend fun persistLegacy(source: Bitmap, output: Bitmap) = translator.persistLivePage(
            chapterA,
            pageA,
            source,
            output,
            analysis = null,
            methodRaw = METHOD,
        )

        fun select(value: HeldEngine): HeldEngine {
            check(engine == null)
            engine = value
            field("engine").set(service, value)
            return value
        }

        fun snapshot() = session.chapter.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(session.chapter).invariantSeparatorsPath to it.readBytes()
        }

        fun assertSnapshot(before: Map<String, ByteArray>) {
            val after = snapshot()
            assertEquals(before.keys, after.keys)
            for ((name, bytes) in before) assertArrayEquals(name, bytes, after[name])
        }

        suspend fun close() {
            try {
                service.shutdown()
                engine?.let { assertTrue("Owned scripted engine must close", it.closed) }
            } finally {
                engine?.output?.takeUnless { it.isRecycled }?.recycle()
            }
        }
    }

    private class HeldEngine(
        private val entered: CompletableDeferred<Unit>,
        private val release: CompletableDeferred<Unit>,
    ) : TranslationEngine {
        override val translationContext = TranslationContext.fromTranslator(TranslatorConfig(toLangName = "Korean"))
        var freshCalls = 0
        var closed = false
        var output: Bitmap? = null

        override suspend fun translatePage(page: Bitmap): PageResult {
            freshCalls++
            assertEquals(Color.WHITE, page.getPixel(0, 0))
            entered.complete(Unit)
            release.await()
            val bitmap = image(Color.GREEN).also { output = it }
            return PageResult.Translated(bitmap, PageStats(1, 1, 1, 0, 0, 0, 0, 0))
        }

        override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
            error("No prior OCR state exists in this fixture")
        override fun warmUp() = Unit
        override fun close() {
            closed = true
        }
    }

    companion object {
        private const val PAGE = "target.png"
        private const val METHOD = "boxfill"
        private fun field(name: String) = TranslationEngineService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        private fun image(color: Int) = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        private fun encoded(color: Int): ByteArray {
            val bitmap = image(color)
            return try {
                ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
