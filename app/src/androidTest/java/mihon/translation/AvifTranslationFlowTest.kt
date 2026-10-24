package mihon.translation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.OriginalMaterial
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.TranslationStatsStore
import eu.kanade.tachiyomi.data.translation.TranslationStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextRegion
import li.joye.yakuyomi.engine.TranslationContext
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.TranslatorConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Real platform AVIF decode and public page storage, with a scripted engine and private files. */
@RunWith(AndroidJUnit4::class)
class AvifTranslationFlowTest {
    @Test(timeout = 30_000)
    fun singleAndChapterAvifTranslationPreserveExactSourcesAndRestoreBothCases(): Unit = runBlocking {
        withFixture { fixture ->
            val directory = fixture.chapterDirectory()
            val chapter = requireNotNull(UniFile.fromFile(directory))
            val names = listOf("001.avif", "002.AVIF")
            names.forEach { File(directory, it).writeBytes(fixture.avif) }
            File(directory, "note.txt").writeBytes(NOTE)
            val nested = File(directory, "nested").apply { check(mkdir()) }
            File(nested, "003.avif").writeBytes(fixture.avif)
            assertFalse(fixture.translator.isChapterTranslated(chapter))

            assertEquals(
                "An AVIF page shown by the reader must also be eligible for single-page translation",
                PageTranslator.SinglePageResult.TRANSLATED,
                fixture.translator.translateSinglePage(chapter, names.first(), METHOD),
            )
            assertEquals(1, fixture.engine.calls)
            assertFalse("The uppercase AVIF page is still pending", fixture.translator.isChapterTranslated(chapter))
            val firstOutput = File(directory, names.first()).readBytes()
            val progress = mutableListOf<Pair<Int, Int>>()
            assertEquals(
                1,
                fixture.translator.translateChapter(chapter, METHOD, onProgress = { done, total ->
                    progress += done to total
                }),
            )
            assertEquals(listOf(1 to 2, 2 to 2), progress)
            assertEquals(2, fixture.engine.calls)
            assertArrayEquals(
                "Completed pages must not be written again",
                firstOutput,
                File(directory, names.first()).readBytes(),
            )
            assertTrue(fixture.translator.isChapterTranslated(chapter))
            assertEquals(names.toSet(), fixture.translator.displayablePages(chapter))
            for (name in names) assertTranslatedPage(fixture, directory, chapter, name)
            fixture.engine.assertReturnedBitmapsRecycled()
            val completed = snapshot(directory)
            assertEquals(0, fixture.translator.translateChapter(chapter, METHOD))
            assertEquals("A complete retry must perform no inference", 2, fixture.engine.calls)
            assertSnapshotEquals(completed, snapshot(directory))

            assertEquals(2, fixture.translator.reRenderChapter(chapter, PageTranslator.ORIGINAL_METHOD))
            for (name in names) {
                assertArrayEquals(
                    "AVIF recovery must copy the exact source bytes",
                    fixture.avif,
                    File(directory, name).readBytes(),
                )
                assertArrayEquals(
                    fixture.avif,
                    File(directory, ".yakuyomi/${OriginalMaterial.exactName(name)}").readBytes(),
                )
            }
            assertArrayEquals(NOTE, File(directory, "note.txt").readBytes())
            assertArrayEquals(
                "Nested pages are not chapter pages",
                fixture.avif,
                File(nested, "003.avif").readBytes(),
            )
            assertEquals("Restoration must not invoke the engine", 2, fixture.engine.calls)
        }
    }

    @Test(timeout = 30_000)
    fun cbzWithCompletedJpegAndPendingAvifIsIncompleteUntilAvifIsStoredAndCanRestore(): Unit = runBlocking {
        withFixture { fixture ->
            val directory = fixture.chapterDirectory()
            val archive = File(directory, "chapter.cbz")
            val jpeg = encoded(Color.WHITE, Bitmap.CompressFormat.JPEG)
            zip(
                archive,
                linkedMapOf(
                    "001.jpg" to jpeg,
                    "002.avif" to fixture.avif,
                    ".yakuyomi/001.orig.jpg" to jpeg,
                    CompletionManifestCodec.PRIMARY to "001.jpg\n".toByteArray(),
                    "note.txt" to NOTE,
                ),
            )
            val originalArchive = archive.readBytes()
            val archiveFile = requireNotNull(UniFile.fromFile(archive))
            assertFalse(
                "A completed JPEG must not hide an untranslated AVIF from archive coverage",
                fixture.translator.isArchiveTranslated(archiveFile),
            )
            assertTrue(
                rewrite(fixture.context, archive) { chapter ->
                    assertFalse(fixture.translator.isChapterTranslated(chapter))
                    assertEquals(1, fixture.translator.translateChapter(chapter, METHOD))
                    assertTrue(fixture.translator.isChapterTranslated(chapter))
                },
            )
            assertEquals("Only the pending AVIF needs inference", 1, fixture.engine.calls)
            fixture.engine.assertReturnedBitmapsRecycled()
            assertTrue(fixture.translator.isArchiveTranslated(archiveFile))
            val translatedArchive = archive.readBytes()
            assertFalse(originalArchive.contentEquals(translatedArchive))
            assertPackedBytes(archive, ".yakuyomi/002.orig.avif", fixture.avif)
            assertPackedBytes(archive, "001.jpg", jpeg)
            assertPackedBytes(archive, "note.txt", NOTE)
            assertGreenJpeg(packedBytes(archive, "002.avif"))
            assertTrue(
                rewrite(fixture.context, archive) { chapter ->
                    assertEquals(2, fixture.translator.reRenderChapter(chapter, PageTranslator.ORIGINAL_METHOD))
                },
            )
            assertPackedBytes(archive, "002.avif", fixture.avif)
            assertPackedBytes(archive, ".yakuyomi/002.orig.avif", fixture.avif)
            assertPackedBytes(archive, "001.jpg", jpeg)
            assertPackedBytes(archive, "note.txt", NOTE)
            val backups = directory.listFiles().orEmpty().filter {
                it.name.startsWith("chapter.cbz.yomitori-original-") && it.name.endsWith(".bak")
            }
            assertEquals(2, backups.size)
            assertEquals(
                setOf(sha(originalArchive), sha(translatedArchive)),
                backups.map { sha(it.readBytes()) }.toSet(),
            )
            assertEquals("Archive recovery must not invoke the engine", 1, fixture.engine.calls)
        }
    }

    @Test(timeout = 30_000)
    fun unreadableAvifFailsExplicitlyWithoutInferenceReplacementOrCompletion(): Unit = runBlocking {
        withFixture { fixture ->
            val directory = fixture.chapterDirectory()
            val chapter = requireNotNull(UniFile.fromFile(directory))
            val unreadable = fixture.avif.copyOf(20)
            val page = File(directory, "001.avif").apply { writeBytes(unreadable) }
            val progress = mutableListOf<Pair<Int, Int>>()
            assertEquals(
                0,
                fixture.translator.translateChapter(chapter, METHOD, onProgress = { done, total ->
                    progress += done to total
                }),
            )
            assertEquals(
                "An unreadable AVIF is a failed page, not an omitted page",
                listOf(0 to 1, 1 to 1),
                progress,
            )
            assertArrayEquals(unreadable, page.readBytes())
            assertEquals(0, fixture.engine.calls)
            assertFalse(fixture.translator.isPageTranslated(chapter, page.name))
            assertFalse(fixture.translator.isChapterTranslated(chapter))
            val errors = File(directory, ".yakuyomi_errors.txt")
            assertTrue("Unreadable source must leave an explicit diagnostic", errors.isFile)
            assertTrue(errors.readText().contains("001.avif\tCannot decode original page"))
            assertFalse(File(directory, ".yakuyomi/001.orig.avif").exists())
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "app.yomitori.yakuyomi.audit") {
            "AVIF storage tests require the isolated audit installation"
        }
        // Finish observing the app's existing startup restore before replacing its shared model
        // storage reference. Calling ensureRestored here would itself persist user queue settings.
        val manager = Injekt.get<TranslationManager>()
        val restored = TranslationManager::class.java.getDeclaredField("restored").apply { isAccessible = true }
        withTimeout(10_000) { while (!restored.getBoolean(manager)) delay(20) }
        requireSharedIdle(target)
        val runId = UUID.randomUUID().toString()
        val directory = File(target.cacheDir, "avif-translation-$runId").apply { check(mkdir()) }
        check(directory.canonicalFile.parentFile == target.cacheDir.canonicalFile)
        val files = File(directory, "private-files").apply { check(mkdir()) }
        val statsName = "avif-translation-stats-$runId"
        val context = object : ContextWrapper(target) {
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                target.getSharedPreferences(statsName, mode)
        }
        val storageField = TranslationEngineConfig::class.java.getDeclaredField("storagePreferences").apply {
            isAccessible = true
        }
        val previousStorage = storageField.get(TranslationEngineConfig)
        val privateStorage = StoragePreferences(
            object : FolderProvider {
                override fun directory(): File = directory
                override fun path(): String = ""
            },
            InMemoryPreferenceStore(),
        )
        var fixture: Fixture? = null
        try {
            val avif = InstrumentationRegistry.getInstrumentation().context.assets
                .open("translation-formats/white-32.avif").use { it.readBytes() }
            assertEquals(300, avif.size)
            assertEquals("c03ca656b1ca74938cc7b270a34caa9e0147f2038ed1225464a4a2ed62c5594d", sha(avif))
            assertEquals("avif", avif.copyOfRange(8, 12).toString(Charsets.US_ASCII))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(avif, 0, avif.size, bounds)
            assertEquals("This regression requires actual platform AVIF decoding", "image/avif", bounds.outMimeType)
            assertWhiteAvif(avif)
            requireSharedIdle(target)
            storageField.set(TranslationEngineConfig, privateStorage)
            assertSame(privateStorage, storageField.get(TranslationEngineConfig))
            // These six owned bytes satisfy only the model-presence gate. The warmed fake engine
            // is injected before translation; no model parser, native model or server is used.
            val models = File(files, "models").apply { check(mkdir()) }
            for (base in listOf("dbnet_detect.ncnn", "ocr_48px_ctc.ncnn", "aot")) {
                File(models, "$base.param").writeBytes(byteArrayOf(0))
                File(models, "$base.bin").writeBytes(byteArrayOf(0))
            }
            assertTrue(TranslationEngineConfig.modelsResolvable(context))
            fixture = Fixture(context, directory, avif)
            block(fixture)
        } finally {
            try {
                withContext(NonCancellable) { fixture?.close() }
            } finally {
                try {
                    storageField.set(TranslationEngineConfig, previousStorage)
                    assertSame(previousStorage, storageField.get(TranslationEngineConfig))
                } finally {
                    check(directory.canonicalFile.parentFile == target.cacheDir.canonicalFile)
                    check(directory.deleteRecursively()) { "Cannot remove owned AVIF fixture" }
                    assertFalse(directory.exists())
                    // Statistics use apply(). Drain preceding writes before deleting our UUID
                    // preferences so a pending disk write cannot recreate them after cleanup.
                    check(target.getSharedPreferences(statsName, Context.MODE_PRIVATE).edit().clear().commit()) {
                        "Cannot finish owned AVIF statistics writes"
                    }
                    check(target.deleteSharedPreferences(statsName)) { "Cannot delete owned AVIF statistics" }
                    val statsDirectory = File(target.applicationInfo.dataDir, "shared_prefs")
                    assertFalse(File(statsDirectory, "$statsName.xml").exists())
                    assertFalse(File(statsDirectory, "$statsName.xml.bak").exists())
                    requireSharedIdle(target)
                }
            }
        }
    }

    private suspend fun requireSharedIdle(context: Context) {
        val shared = Injekt.get<TranslationEngineService>()
        check(!shared.loading.value && !shared.warm.value) { "Shared native engine must be idle" }
        check(TranslationBusy.active.value == 0) { "No unrelated translation or rendering may be active" }
        check(Injekt.get<TranslationManager>().queueState.value.isEmpty()) { "Shared translation queue must be empty" }
        val downloads = Injekt.get<DownloadManager>()
        check(downloads.queueState.value.isEmpty() && !downloads.isRunning) { "Shared downloads must remain idle" }
        val inFlight = serviceField("inFlight").get(shared) as AtomicInteger
        check(serviceField("engine").get(shared) == null && inFlight.get() == 0) {
            "Shared engine must remain absent while model storage is isolated"
        }
        val persisted = TranslationStore(context)
        check(persisted.restore().isEmpty()) { "Persisted translation queue must be empty" }
        val marks = persisted.restorePendingMarks(Long.MIN_VALUE)
        check(marks.translate.isEmpty() && marks.night.isEmpty()) { "No pending automatic translation may remain" }
        for (tag in listOf("Translator", "TranslatorHeartbeat", "Downloader")) {
            val work = WorkManager.getInstance(context).getWorkInfosByTag(tag).get(5, TimeUnit.SECONDS)
            check(work.none { !it.state.isFinished }) { "Unfinished background work: $tag" }
        }
    }

    private class Fixture(val context: Context, val directory: File, val avif: ByteArray) {
        val engine = ScriptedEngine()
        private val preferences = TranslationPreferences(InMemoryPreferenceStore())
        private val service = TranslationEngineService(context).also {
            serviceField("translationPreferences").set(it, preferences)
            val signature = TranslationEngineService::class.java.getDeclaredMethod(
                "configSignature",
                String::class.java,
                TranslationPreferences::class.java,
            ).apply { isAccessible = true }.invoke(it, METHOD, preferences.snapshot())
            serviceField("builtSignature").set(it, signature)
            serviceField("warmedUp").setBoolean(it, true)
            serviceField("engine").set(it, engine)
        }
        val translator = PageTranslator(context).also {
            pageField("translationPreferences").set(it, preferences)
            pageField("engineService").set(it, service)
            pageField("statsStore").set(it, TranslationStatsStore(context))
        }
        fun chapterDirectory(): File = File(directory, "chapter").apply { check(mkdir()) }
        suspend fun close() {
            try {
                service.shutdown()
                assertTrue("Owned scripted engine must close", engine.closed)
            } finally {
                engine.recycleRemaining()
            }
        }
    }

    private class ScriptedEngine : TranslationEngine {
        override val translationContext = TranslationContext.fromTranslator(TranslatorConfig(toLangName = "Korean"))
        var calls = 0
        var closed = false
        private val returned = mutableListOf<Bitmap>()
        override suspend fun translatePage(page: Bitmap): PageResult {
            calls++
            assertEquals(SIZE, page.width)
            assertEquals(SIZE, page.height)
            assertEquals("The source must be decoded from AVIF before inference", Color.WHITE, page.getPixel(0, 0))
            val quad = listOf(Pt(0f, 0f), Pt(16f, 0f), Pt(16f, 16f), Pt(0f, 16f))
            val line = TextLine(quad, 1f).apply { text = "neutral source" }
            val region = TextRegion(listOf(line), "h", 0f, 8f, 8f, 16f, 16f).apply {
                translatedText = "중립"
            }
            return PageResult.Translated(
                owned(Color.GREEN),
                PageStats(1, 1, 1, 0, 0, 0, 0, 0),
                PageAnalysis(owned(Color.BLACK), listOf(region)),
            )
        }
        override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
            error("No partial progress was prepared")
        override fun warmUp() = Unit
        override fun close() {
            closed = true
        }
        fun assertReturnedBitmapsRecycled() {
            assertEquals(calls * 2, returned.size)
            assertTrue("Translator must recycle all returned outputs and masks", returned.all { it.isRecycled })
        }
        fun recycleRemaining() = returned.filterNot { it.isRecycled }.forEach { it.recycle() }
        private fun owned(color: Int): Bitmap = image(color).also { returned += it }
    }

    private suspend fun rewrite(context: Context, archive: File, process: suspend (UniFile) -> Unit): Boolean {
        val manager = TranslationManager(context)
        val result = TranslationManager::class.java.declaredClasses.single { it.simpleName == "ProcessResult" }
            .getDeclaredConstructor(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val callback: suspend (UniFile) -> Any = { chapter ->
            process(chapter)
            result.newInstance(true, true)
        }
        val method = TranslationManager::class.java.declaredMethods.single { it.name == "processArchiveInPlace" }
            .apply { isAccessible = true }
        try {
            return suspendCoroutineUninterceptedOrReturn { continuation ->
                try {
                    method.invoke(
                        manager,
                        UniFile.fromFile(archive),
                        { _: Int, _: Int -> },
                        { false },
                        callback,
                        continuation,
                    )
                } catch (error: InvocationTargetException) {
                    throw checkNotNull(error.cause)
                }
            }
        } finally {
            val scope = TranslationManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
                .get(manager) as CoroutineScope
            scope.cancel()
        }
    }

    private fun assertTranslatedPage(fixture: Fixture, directory: File, chapter: UniFile, name: String) {
        assertTrue(fixture.translator.isPageTranslated(chapter, name))
        assertArrayEquals(fixture.avif, File(directory, ".yakuyomi/${OriginalMaterial.exactName(name)}").readBytes())
        assertFalse(File(directory, ".yakuyomi/${OriginalMaterial.inflightName(name)}").exists())
        assertGreenJpeg(File(directory, name).readBytes())
    }

    private fun assertSnapshotEquals(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals(expected.keys, actual.keys)
        for ((name, bytes) in expected) {
            assertArrayEquals("Unexpected completed-retry change: $name", bytes, actual.getValue(name))
        }
    }

    private fun snapshot(directory: File): Map<String, ByteArray> = directory.walkTopDown()
        .filter { it.isFile }.associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes() }

    private fun assertWhiteAvif(bytes: ByteArray) = assertPixels(bytes) { pixel -> assertEquals(Color.WHITE, pixel) }

    private fun assertGreenJpeg(bytes: ByteArray) {
        // Android exposes AVIF decoding, but Bitmap.compress has no AVIF encoder. Preserve the
        // existing JPEG write-back fallback and recover the separately retained exact AVIF source.
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), bytes.copyOfRange(0, 3))
        assertPixels(bytes) { pixel ->
            assertTrue(Color.green(pixel) >= 250 && Color.red(pixel) <= 5 && Color.blue(pixel) <= 5)
        }
    }

    private fun assertPixels(bytes: ByteArray, assertion: (Int) -> Unit) {
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "Platform cannot decode fixture image"
        }
        try {
            assertEquals(SIZE, bitmap.width)
            assertEquals(SIZE, bitmap.height)
            val pixels = IntArray(SIZE * SIZE)
            bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            pixels.forEach(assertion)
        } finally {
            bitmap.recycle()
        }
    }

    private fun packedBytes(archive: File, name: String): ByteArray = ZipFile(archive).use { zip ->
        val entry = checkNotNull(zip.getEntry(name))
        zip.getInputStream(entry).use { it.readBytes() }.also {
            assertEquals(it.size.toLong(), entry.size)
            assertEquals(CRC32().apply { update(it) }.value, entry.crc)
        }
    }

    private fun assertPackedBytes(archive: File, name: String, expected: ByteArray) =
        assertArrayEquals(expected, packedBytes(archive, name))

    private fun zip(file: File, entries: Map<String, ByteArray>) = ZipOutputStream(file.outputStream()).use { output ->
        for ((name, bytes) in entries) {
            output.putNextEntry(ZipEntry(name))
            output.write(bytes)
            output.closeEntry()
        }
    }

    private companion object {
        const val SIZE = 32
        const val METHOD = "boxfill"
        val NOTE = "neutral archive note".toByteArray()
        fun sha(bytes: ByteArray): String = PartialPageStore.sha256(bytes)
        fun serviceField(name: String) = TranslationEngineService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        fun pageField(name: String) = PageTranslator::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun image(color: Int): Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
        fun encoded(color: Int, format: Bitmap.CompressFormat): ByteArray {
            val bitmap = image(color)
            return try {
                ByteArrayOutputStream().use {
                    check(bitmap.compress(format, 100, it))
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
