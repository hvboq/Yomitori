package mihon.translation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.OcrProgressCodec
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import eu.kanade.tachiyomi.data.translation.StoredTranslationContext
import eu.kanade.tachiyomi.data.translation.TranslationEngineConfig
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationStatsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import li.joye.yakuyomi.engine.OcrLineProgress
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionProgress
import li.joye.yakuyomi.engine.RegionState
import li.joye.yakuyomi.engine.TranslationContext
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.TranslatorConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Real journal/service integration with scripted engines, not native OCR or external translation. */
@RunWith(AndroidJUnit4::class)
class StoredPageContextIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun manualLegacyCompletedPageWithoutOriginalNeverPromotesItsDisplayToSource() = runBlocking {
        withSourceProtectionFixture { fixture ->
            check(fixture.materials.mkdir())
            File(fixture.materials, "unrelated.txt").writeText("neutral unrelated material")
            fixture.page.writeBytes(encoded(Color.BLACK))
            val completion = CompletionManifestCodec.encode(setOf(PAGE))
            for (name in listOf(
                CompletionManifestCodec.PRIMARY,
                CompletionManifestCodec.PREVIOUS,
                CompletionManifestCodec.CHECKPOINT_PREFIX + UUID.randomUUID(),
            )) {
                File(fixture.directory, name).writeBytes(completion)
            }
            val calls = AtomicInteger()
            fixture.select(object : TranslationEngine {
                override val translationContext = semantic("Korean")
                override suspend fun translatePage(page: Bitmap): PageResult {
                    calls.incrementAndGet()
                    return PageResult.Translated(image(Color.GREEN), stats(0))
                }
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
                    calls.incrementAndGet()
                    error("Legacy completion has no resumable journal")
                }
                override fun warmUp() = Unit
                override fun close() = Unit
            })
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            val before = protectedFileHashes(fixture.directory)
            val original = File(fixture.materials, "001.orig.png")
            assertFalse(original.exists())
            val actual = fixture.translate()
            val after = protectedFileHashes(fixture.directory)
            assertEquals(
                "calls=${calls.get()}, protectedFilesUnchanged=${before == after}, promotedOriginal=${original.exists()}",
                PageTranslator.SinglePageResult.NO_ORIGINAL,
                actual,
            )
            assertEquals("A completed display is not an OCR source", 0, calls.get())
            assertEquals("Display, all completion files and unrelated materials must remain unchanged", before, after)
            assertFalse("No replacement original may be invented from translated pixels", original.exists())
        }
    }

    @Test
    fun manualLegacyCompletedPageWithOriginalUsesPreservedSourceAndCanTranslateAgain() = runBlocking {
        withSourceProtectionFixture { fixture ->
            check(fixture.materials.mkdir())
            val original = File(fixture.materials, "001.orig.png").apply { writeBytes(fixture.initialBytes) }
            fixture.page.writeBytes(encoded(Color.BLACK))
            File(fixture.directory, CompletionManifestCodec.PRIMARY)
                .writeBytes(CompletionManifestCodec.encode(setOf(PAGE)))
            val sourceBitmap = requireNotNull(BitmapFactory.decodeFile(original.path))
            val displayBitmap = requireNotNull(BitmapFactory.decodeFile(fixture.page.path))
            val sourceHash: String
            val displayHash: String
            try {
                sourceHash = pixelHash(sourceBitmap)
                displayHash = pixelHash(displayBitmap)
            } finally {
                sourceBitmap.recycle()
                displayBitmap.recycle()
            }
            assertFalse("The source and old display must have different pixels", sourceHash == displayHash)
            val calls = AtomicInteger()
            fixture.select(object : TranslationEngine {
                override val translationContext = semantic("Korean")
                override suspend fun translatePage(page: Bitmap): PageResult {
                    calls.incrementAndGet()
                    assertEquals("Inference must read the preserved original", sourceHash, pixelHash(page))
                    assertFalse(
                        "The previous translated display must not be recognized",
                        displayHash == pixelHash(page),
                    )
                    return PageResult.Translated(image(Color.GREEN), stats(0))
                }
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
                    error("A legacy page must start from its preserved source")
                override fun warmUp() = Unit
                override fun close() = Unit
            })
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertEquals(1, calls.get())
            assertArrayEquals(fixture.initialBytes, original.readBytes())
            val translated = requireNotNull(BitmapFactory.decodeFile(fixture.page.path))
            try {
                assertEquals(Color.GREEN, translated.getPixel(0, 0))
            } finally {
                translated.recycle()
            }
            assertTrue(fixture.translator.isChapterTranslated(fixture.chapter))
        }
    }

    @Test
    fun manualCompletedJournalMissingOrCorruptOriginalFailsBeforeInference() = runBlocking {
        for (missing in listOf(true, false)) {
            withSourceProtectionFixture { fixture ->
                fixture.select(object : TranslationEngine {
                    override val translationContext = semantic("Korean")
                    override suspend fun translatePage(page: Bitmap): PageResult = result("한국어", false)
                    override fun warmUp() = Unit
                    override fun close() = Unit
                })
                assertEquals(PageTranslator.SinglePageResult.PARTIAL, fixture.translate())
                fixture.selectSameContextCompletion()
                assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
                val complete = requireNotNull(fixture.store.load(PAGE))
                assertTrue(complete.complete)
                val original = File(fixture.materials, complete.original.name)
                if (missing) check(original.delete()) else original.writeBytes(byteArrayOf(1, 2, 3))
                val calls = AtomicInteger()
                fixture.select(object : TranslationEngine {
                    override val translationContext = semantic("Korean")
                    override suspend fun translatePage(page: Bitmap): PageResult {
                        calls.incrementAndGet()
                        return result("실행되면 안 됨", true)
                    }
                    override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
                        calls.incrementAndGet()
                        return result("실행되면 안 됨", true)
                    }
                    override fun warmUp() = Unit
                    override fun close() = Unit
                })
                val before = protectedFileHashes(fixture.directory)
                assertEquals(PageTranslator.SinglePageResult.NOT_TRANSLATED, fixture.translate())
                assertEquals("A missing or corrupt journal source must fail before inference", 0, calls.get())
                assertEquals(before, protectedFileHashes(fixture.directory))
                assertFalse(fixture.translator.isChapterTranslated(fixture.chapter))
                assertEquals(!missing, original.exists())
            }
        }
    }

    @Test
    fun orphanExactAndLegacyOriginalsWithDifferentDisplayFailBeforeInference() = runBlocking {
        for (legacy in listOf(false, true)) {
            withSourceProtectionFixture { fixture ->
                check(fixture.materials.mkdir())
                val original = File(fixture.materials, if (legacy) "001.orig.webp" else "001.orig.png")
                original.writeBytes(if (legacy) encodedWebp(Color.WHITE) else fixture.initialBytes)
                fixture.page.writeBytes(encoded(Color.BLACK))
                File(fixture.directory, "002.png").writeBytes(encoded(Color.BLUE))
                File(fixture.materials, "unrelated.txt").writeText("neutral unrelated material")
                assertEquals(setOf(original.name, "unrelated.txt"), fixture.materials.list()!!.toSet())
                assertEquals(setOf(PAGE, "002.png", ".yakuyomi"), fixture.directory.list()!!.toSet())
                val calls = AtomicInteger()
                fixture.select(object : TranslationEngine {
                    override val translationContext = semantic("Korean")
                    override suspend fun translatePage(page: Bitmap): PageResult {
                        calls.incrementAndGet()
                        return PageResult.Translated(image(Color.GREEN), stats(0))
                    }
                    override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
                        calls.incrementAndGet()
                        error("An orphan original has no pending journal")
                    }
                    override fun warmUp() = Unit
                    override fun close() = Unit
                })
                val before = protectedFileHashes(fixture.directory)
                val actual = fixture.translate()
                val after = protectedFileHashes(fixture.directory)
                assertEquals(
                    "legacy=$legacy, calls=${calls.get()}, protectedFilesUnchanged=${before == after}",
                    "SOURCE_AMBIGUOUS",
                    actual.name,
                )
                assertEquals("Unproven displayed pixels must never reach inference", 0, calls.get())
                assertEquals("Source, current display and unrelated files must stay byte-identical", before, after)
                assertFalse(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            }
        }
    }

    @Test
    fun orphanExactOriginalIdenticalToDisplayCanTranslateWithoutReplacingSource() = runBlocking {
        withSourceProtectionFixture { fixture ->
            check(fixture.materials.mkdir())
            val original = File(fixture.materials, "001.orig.png").apply { writeBytes(fixture.initialBytes) }
            File(fixture.materials, "unrelated.txt").writeText("neutral unrelated material")
            val unrelated = File(fixture.materials, "unrelated.txt").readBytes()
            assertArrayEquals(original.readBytes(), fixture.page.readBytes())
            val sourceHash = decodedPixelHash(original)
            val calls = AtomicInteger()
            fixture.select(object : TranslationEngine {
                override val translationContext = semantic("Korean")
                override suspend fun translatePage(page: Bitmap): PageResult {
                    calls.incrementAndGet()
                    assertEquals(sourceHash, pixelHash(page))
                    return PageResult.Translated(image(Color.GREEN), stats(0))
                }
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
                    error("A matching orphan source requires fresh inference")
                override fun warmUp() = Unit
                override fun close() = Unit
            })
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertEquals(1, calls.get())
            assertArrayEquals(fixture.initialBytes, original.readBytes())
            assertArrayEquals(unrelated, File(fixture.materials, "unrelated.txt").readBytes())
            assertSolidPage(fixture.page, Color.GREEN)
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
        }
    }

    @Test
    fun exactOriginalWithoutOptionalMaterialsCanRestoreThenTranslateFresh() = runBlocking {
        withSourceProtectionFixture { fixture ->
            check(fixture.materials.mkdir())
            val original = File(fixture.materials, "001.orig.png").apply { writeBytes(fixture.initialBytes) }
            fixture.page.writeBytes(encoded(Color.BLACK))
            File(fixture.directory, "002.png").writeBytes(encoded(Color.BLUE))
            File(fixture.materials, "unrelated.txt").writeText("neutral unrelated material")
            val sourceHash = decodedPixelHash(original)
            val calls = AtomicInteger()
            fixture.select(object : TranslationEngine {
                override val translationContext = semantic("Korean")
                override suspend fun translatePage(page: Bitmap): PageResult {
                    calls.incrementAndGet()
                    assertEquals(
                        "The restored preserved source must be freshly recognized",
                        sourceHash,
                        pixelHash(page),
                    )
                    return PageResult.Translated(image(Color.GREEN), stats(0))
                }
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
                    error("An original-only restore has no pending OCR progress")
                override fun warmUp() = Unit
                override fun close() = Unit
            })
            assertEquals(setOf("001.orig.png", "unrelated.txt"), fixture.materials.list()!!.toSet())
            val before = protectedFileHashes(fixture.directory)
            assertTrue(fixture.translator.reRenderPage(fixture.chapter, PAGE, "original"))
            assertEquals("Restoring an original must not invoke inference", 0, calls.get())
            assertArrayEquals(fixture.initialBytes, original.readBytes())
            assertArrayEquals(original.readBytes(), fixture.page.readBytes())
            val afterRestore = protectedFileHashes(fixture.directory)
            for ((name, hash) in before) {
                if (name != PAGE) assertEquals(name, hash, afterRestore[name])
            }
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertEquals(1, calls.get())
            assertArrayEquals(fixture.initialBytes, original.readBytes())
            for ((name, hash) in before) {
                if (name != PAGE) assertEquals(name, hash, protectedFileHashes(fixture.directory)[name])
            }
            assertSolidPage(fixture.page, Color.GREEN)
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
        }
    }

    @Test
    fun legacyWebpOriginalWithoutOptionalMaterialsRestoresPngThenTranslatesFresh() = runBlocking {
        withSourceProtectionFixture { fixture ->
            check(fixture.materials.mkdir())
            val sourceBytes = encodedWebp(Color.WHITE)
            val original = File(fixture.materials, "001.orig.webp").apply { writeBytes(sourceBytes) }
            fixture.page.writeBytes(encoded(Color.BLACK))
            File(fixture.directory, "002.png").writeBytes(encoded(Color.BLUE))
            File(fixture.materials, "unrelated.txt").writeText("neutral unrelated material")
            val sourceHash = decodedPixelHash(original)
            val calls = AtomicInteger()
            fixture.select(object : TranslationEngine {
                override val translationContext = semantic("Korean")
                override suspend fun translatePage(page: Bitmap): PageResult {
                    calls.incrementAndGet()
                    assertEquals("Fresh inference must retain the legacy source pixels", sourceHash, pixelHash(page))
                    return PageResult.Translated(image(Color.GREEN), stats(0))
                }
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
                    error("A legacy source-only restore has no pending OCR progress")
                override fun warmUp() = Unit
                override fun close() = Unit
            })
            assertEquals(setOf("001.orig.webp", "unrelated.txt"), fixture.materials.list()!!.toSet())
            val before = protectedFileHashes(fixture.directory)
            assertTrue(fixture.translator.reRenderPage(fixture.chapter, PAGE, "original"))
            assertEquals("Restoring legacy pixels must not invoke inference", 0, calls.get())
            assertArrayEquals(sourceBytes, original.readBytes())
            val restoredBytes = fixture.page.readBytes()
            assertArrayEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
                restoredBytes.copyOfRange(0, 8),
            )
            assertEquals(
                "A PNG page must decode to every preserved legacy pixel",
                sourceHash,
                decodedPixelHash(fixture.page),
            )
            assertFalse(File(fixture.materials, "001.orig.png").exists())
            for ((name, hash) in before) {
                if (name != PAGE) assertEquals(name, hash, protectedFileHashes(fixture.directory)[name])
            }
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertEquals(1, calls.get())
            assertArrayEquals("The legacy source must not be re-encoded or deleted", sourceBytes, original.readBytes())
            assertFalse(
                "Fresh translation must not promote a PNG copy over the legacy source",
                File(fixture.materials, "001.orig.png").exists(),
            )
            for ((name, hash) in before) {
                if (name != PAGE) assertEquals(name, hash, protectedFileHashes(fixture.directory)[name])
            }
            assertSolidPage(fixture.page, Color.GREEN)
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
        }
    }

    @Test
    fun originalRestoreMissingCorruptOrReservedDirectoryFailsWithoutChangingFiles() = runBlocking {
        for (invalid in listOf("missing", "corrupt-exact", "corrupt-legacy", "directory-exact", "directory-legacy")) {
            withSourceProtectionFixture { fixture ->
                check(fixture.materials.mkdir())
                fixture.page.writeBytes(encoded(Color.BLACK))
                File(fixture.directory, "002.png").writeBytes(encoded(Color.BLUE))
                File(fixture.materials, "unrelated.txt").writeText("neutral unrelated material")
                val original = File(
                    fixture.materials,
                    if (invalid.endsWith("legacy")) "001.orig.webp" else "001.orig.png",
                )
                when {
                    invalid.startsWith("corrupt") -> original.writeBytes(byteArrayOf(1, 2, 3))
                    invalid.startsWith("directory") -> {
                        check(original.mkdir())
                        File(original, "unowned.txt").writeText("preserve this reserved-name directory")
                    }
                }
                val calls = AtomicInteger()
                fixture.select(object : TranslationEngine {
                    override val translationContext = semantic("Korean")
                    override suspend fun translatePage(page: Bitmap): PageResult {
                        calls.incrementAndGet()
                        error("An original restore must never run inference")
                    }
                    override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
                        calls.incrementAndGet()
                        error("An original restore must never resume inference")
                    }
                    override fun warmUp() = Unit
                    override fun close() = Unit
                })
                val before = protectedFileHashes(fixture.directory)
                assertFalse(invalid, fixture.translator.reRenderPage(fixture.chapter, PAGE, "original"))
                assertEquals(invalid, 0, calls.get())
                assertEquals(invalid, before, protectedFileHashes(fixture.directory))
                assertEquals(invalid, invalid.startsWith("directory"), original.isDirectory)
                assertEquals(invalid, invalid != "missing", original.exists())
                assertFalse(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            }
        }
    }

    private fun decodedPixelHash(file: File): String {
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
        return try {
            assertEquals(SIZE, bitmap.width)
            assertEquals(SIZE, bitmap.height)
            pixelHash(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun assertSolidPage(file: File, color: Int) {
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
        try {
            assertEquals(SIZE, bitmap.width)
            assertEquals(SIZE, bitmap.height)
            val pixels = IntArray(SIZE * SIZE)
            bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            assertTrue("Every output pixel must match the scripted fresh result", pixels.all { it == color })
        } finally {
            bitmap.recycle()
        }
    }

    private fun encodedWebp(color: Int): ByteArray {
        val bitmap = image(color)
        return try {
            ByteArrayOutputStream().use {
                @Suppress("DEPRECATION")
                check(bitmap.compress(Bitmap.CompressFormat.WEBP, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun protectedFileHashes(directory: File): Map<String, String> = directory.walkTopDown()
        .filter { it.isFile && it.name != ".yakuyomi_errors.txt" }
        .associate { it.relativeTo(directory).invariantSeparatorsPath to PartialPageStore.sha256(it.readBytes()) }

    private fun pixelHash(bitmap: Bitmap): String {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val bytes = ByteBuffer.allocate(pixels.size * 4)
        for (pixel in pixels) bytes.putInt(pixel)
        return PartialPageStore.sha256(bytes.array())
    }

    /** Own in-memory translation preferences and private UUID files; no global preference is changed. */
    private suspend fun withSourceProtectionFixture(block: suspend (Fixture) -> Unit) {
        check(context.packageName == "app.yomitori.yakuyomi.audit") {
            "Source protection regression requires the isolated audit installation"
        }
        assertTrue("PageTranslator requires the prepared model set", TranslationEngineConfig.modelsResolvable(context))
        val directory = File(context.cacheDir, "source-protection-${UUID.randomUUID()}").apply { check(mkdir()) }
        check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
        var service: TranslationEngineService? = null
        try {
            val page = File(directory, PAGE)
            page.writeBytes(encoded(Color.WHITE))
            val preferences = TranslationPreferences(InMemoryPreferenceStore())
            val activeService = TranslationEngineService(context)
            service = activeService
            field("translationPreferences").set(activeService, preferences)
            val signature = TranslationEngineService::class.java.getDeclaredMethod(
                "configSignature",
                String::class.java,
                TranslationPreferences::class.java,
            ).apply { isAccessible = true }.invoke(activeService, "boxfill", preferences.snapshot())
            field("builtSignature").set(activeService, signature)
            field("warmedUp").setBoolean(activeService, true)
            val translator = PageTranslator(context).also {
                PageTranslator::class.java.getDeclaredField("translationPreferences").apply { isAccessible = true }
                    .set(it, preferences)
                PageTranslator::class.java.getDeclaredField("engineService").apply { isAccessible = true }
                    .set(it, activeService)
            }
            block(Fixture(directory, page, page.readBytes(), activeService, translator))
        } finally {
            try {
                service?.shutdown()
            } finally {
                check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                check(directory.deleteRecursively())
            }
        }
    }

    /** Chapter accounting uses only a UUID-owned statistics file, including on assertion failure. */
    private suspend fun withChapterDiagnosticFixture(block: suspend (Fixture) -> Unit) {
        val target = context
        val statsName = "chapter-diagnostics-stats-${UUID.randomUUID()}"
        val statistics = target.getSharedPreferences(statsName, Context.MODE_PRIVATE)
        val privateStatsContext = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                check(name == "translation_stats") { "Unexpected chapter fixture preference" }
                return target.getSharedPreferences(statsName, mode)
            }
        }
        try {
            withSourceProtectionFixture { fixture ->
                PageTranslator::class.java.getDeclaredField("statsStore").apply { isAccessible = true }
                    .set(fixture.translator, TranslationStatsStore(privateStatsContext))
                block(fixture)
            }
        } finally {
            // A synchronous edit drains preceding apply() writes before deleting our own file.
            check(statistics.edit().clear().commit()) { "Cannot finish owned chapter statistics writes" }
            check(target.deleteSharedPreferences(statsName)) { "Cannot delete owned chapter statistics" }
            val preferencesDirectory = File(target.applicationInfo.dataDir, "shared_prefs")
            assertFalse(File(preferencesDirectory, "$statsName.xml").exists())
            assertFalse(File(preferencesDirectory, "$statsName.xml.bak").exists())
        }
    }

    @Test
    fun languageRestartFailurePreservesPriorOutputThenReplacesAllTargetsAndResumes() = runBlocking {
        withPending { fixture, pending ->
            val previousOutput = fixture.page.readBytes()
            val previousMaterials = fixture.materials.listFiles()!!.associate { it.name to it.readBytes() }
            var fullCalls = 0
            var resumeCalls = 0
            val english = object : TranslationEngine {
                override val translationContext = semantic("English")
                override suspend fun translatePage(page: Bitmap): PageResult {
                    assertEquals("Inference must use the preserved source", Color.WHITE, page.getPixel(0, 0))
                    fullCalls++
                    return if (fullCalls ==
                        1
                    ) {
                        PageResult.Failed("scripted restart failure")
                    } else {
                        result("English", false)
                    }
                }
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
                    resumeCalls++
                    assertEquals("English 1", analysis.progress!!.regions[0].translatedText)
                    assertTrue(analysis.progress!!.regions.none { it.translatedText.contains("한국어") })
                    return result("English", true)
                }
                override fun warmUp() = Unit
                override fun close() = Unit
            }
            fixture.select(english)
            assertEquals(PageTranslator.SinglePageResult.NOT_TRANSLATED, fixture.translate())
            assertEquals(1, fullCalls)
            assertEquals(0, resumeCalls)
            assertArrayEquals(previousOutput, fixture.page.readBytes())
            assertEquals(pending, fixture.store.load(PAGE))
            assertEquals(previousMaterials.keys, fixture.materials.listFiles()!!.map { it.name }.toSet())
            previousMaterials.forEach { (name, bytes) ->
                assertArrayEquals(name, bytes, File(fixture.materials, name).readBytes())
            }

            assertEquals(PageTranslator.SinglePageResult.PARTIAL, fixture.translate())
            val replacement = requireNotNull(fixture.store.load(PAGE))
            assertEquals(pending.revision, replacement.baseRevision)
            assertEquals(pending.original, replacement.original)
            assertArrayEquals(previousOutput, fixture.store.readOutput(pending))
            val progress = OcrProgressCodec.decode(replacement.progressPayload, SIZE, SIZE)
            assertEquals("English 1", progress.regions[0].translatedText)
            assertTrue(progress.regions.none { it.translatedText.contains("한국어") })
            assertEquals(english.translationContext, StoredTranslationContext.decode(replacement.safeContextPayload!!))
            assertEquals(2, fullCalls)
            assertEquals(0, resumeCalls)
            assertFalse(fixture.translator.isPageTranslated(fixture.chapter, PAGE))

            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertEquals(2, fullCalls)
            assertEquals(1, resumeCalls)
            val completed = requireNotNull(fixture.store.load(PAGE))
            assertTrue(completed.complete)
            assertEquals(replacement.safeContextPayload, completed.safeContextPayload)
            val finalProgress = OcrProgressCodec.decode(completed.progressPayload, SIZE, SIZE)
            assertEquals(listOf("English 1", "English 2"), finalProgress.regions.map { it.translatedText })
            assertTrue(fixture.translator.isChapterTranslated(fixture.chapter))
            fixture.assertOriginalPreserved()
        }
    }

    @Test
    fun changedContextNoTextResultClosesPreviousJournalWithoutRetainingOldTargets() = runBlocking {
        withPending { fixture, pending ->
            var calls = 0
            val english = object : TranslationEngine {
                override val translationContext = semantic("English")
                override suspend fun translatePage(page: Bitmap): PageResult {
                    calls++
                    assertEquals(Color.WHITE, page.getPixel(0, 0))
                    val progress = PageProgress(SIZE, SIZE, emptyList(), noTextDetected = true)
                    return PageResult.Skipped(
                        "No text detected",
                        stats(0),
                        PageAnalysis(image(Color.BLACK), emptyList(), progress),
                    )
                }
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
                    error("Old-language progress must not be passed to resume")
                override fun warmUp() = Unit
                override fun close() = Unit
            }
            fixture.select(english)
            assertEquals("SKIPPED", fixture.translate().name)
            assertEquals(1, calls)
            val completed = requireNotNull(fixture.store.load(PAGE))
            assertEquals(pending.revision, completed.baseRevision)
            assertTrue(completed.complete)
            val progress = OcrProgressCodec.decode(completed.progressPayload, SIZE, SIZE)
            assertTrue(progress.noTextDetected)
            assertTrue(progress.regions.isEmpty())
            assertFalse(progress.hasPending)
            assertEquals(english.translationContext, StoredTranslationContext.decode(completed.safeContextPayload!!))
            assertTrue(fixture.translator.isChapterTranslated(fixture.chapter))
            fixture.assertOriginalPreserved()
        }
    }

    @Test
    fun completedJournalChangedContextNoTextStoresFreshFinalStateAndPreservesPriorOutput() = runBlocking {
        withCompleted { fixture, completed ->
            assertFreshSkippedCompletes(fixture, completed, "English", noText = true)
        }
    }

    @Test
    fun completedJournalSameContextAllKeptStoresFreshFinalStateWithoutResuming() = runBlocking {
        withCompleted { fixture, completed ->
            assertFreshSkippedCompletes(fixture, completed, "Korean", noText = false)
        }
    }

    @Test
    fun legacyCompletedPageNoTextPreservesPreviousOutputAsFirstJournalBaseline() = runBlocking {
        withFixture { fixture ->
            check(fixture.materials.mkdir())
            File(fixture.materials, "001.orig.png").writeBytes(fixture.initialBytes)
            fixture.page.writeBytes(encoded(Color.BLACK))
            File(fixture.directory, CompletionManifestCodec.PRIMARY)
                .writeBytes(CompletionManifestCodec.encode(setOf(PAGE)))
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            assertEquals(null, fixture.store.load(PAGE))
            assertFreshSkippedCompletes(fixture, null, "English", noText = true)
        }
    }

    @Test
    fun successfulSameContextResumeDeletesItsOnlyPendingDiagnostic() = runBlocking {
        withPending { fixture, pending ->
            assertTrue(fixture.errors.readText().startsWith("$PAGE\tOCR_PENDING:"))
            fixture.selectSameContextCompletion()
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            val completed = requireNotNull(fixture.store.load(PAGE))
            assertTrue(completed.complete)
            assertEquals(pending.revision, completed.baseRevision)
            assertFalse("A resolved page must not retain its old OCR_PENDING error", fixture.errors.exists())
            fixture.assertOriginalPreserved()
        }
    }

    @Test
    fun successfulResumePreservesOtherPageNightAndMaterialsDiagnostics() = runBlocking {
        withPending { fixture, _ ->
            val kept = listOf(
                "002.png\tOCR_PENDING: another page still needs recognition",
                "001.png.extra\tfailed unrelated page with a shared prefix",
                "(night)\t$PAGE\tnight generation failed",
                "(materials)\toptional materials warning",
            )
            fixture.errors.writeText((kept + fixture.errors.readLines()).joinToString("\n", postfix = "\n"))
            fixture.selectSameContextCompletion()
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertEquals(kept, fixture.errors.readLines())
            assertTrue(fixture.store.load(PAGE)!!.complete)
            fixture.assertOriginalPreserved()
        }
    }

    @Test
    fun pendingAndFailedResumeRetainCurrentDiagnosticAndOtherOwners() = runBlocking {
        withPending { fixture, pending ->
            val kept = listOf("002.png\tother page failed", "(night)\t$PAGE\tnight failed")
            fixture.errors.writeText((kept + fixture.errors.readLines()).joinToString("\n"))
            var calls = 0
            fixture.select(object : TranslationEngine {
                override val translationContext = semantic("Korean")
                override suspend fun translatePage(page: Bitmap): PageResult = error("Expected pending resume")
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
                    if (++calls == 1) result("한국어", false) else PageResult.Failed("current scripted failure")
                override fun warmUp() = Unit
                override fun close() = Unit
            })
            assertEquals(PageTranslator.SinglePageResult.PARTIAL, fixture.translate())
            val stillPending = requireNotNull(fixture.store.load(PAGE))
            assertFalse(stillPending.complete)
            assertEquals(pending.revision, stillPending.baseRevision)
            assertEquals(kept, fixture.errors.readLines().filterNot { it.startsWith("$PAGE\t") })
            assertTrue(fixture.errors.readLines().single { it.startsWith("$PAGE\t") }.contains("OCR_PENDING:"))
            val output = fixture.page.readBytes()

            assertEquals(PageTranslator.SinglePageResult.NOT_TRANSLATED, fixture.translate())
            assertEquals(2, calls)
            assertEquals(stillPending, fixture.store.load(PAGE))
            assertArrayEquals(output, fixture.page.readBytes())
            assertEquals(kept + "$PAGE\tcurrent scripted failure", fixture.errors.readLines())
            fixture.assertOriginalPreserved()
        }
    }

    @Test
    fun failedManualRetryOfCompletedPageRetainsFailureDespiteCompletedJournal() = runBlocking {
        withCompleted { fixture, completed ->
            val output = fixture.page.readBytes()
            val kept = listOf("002.png\tother page failed", "(materials)\told optional warning")
            fixture.errors.writeText((kept + "$PAGE\tprevious failure").joinToString("\n"))
            fixture.select(object : TranslationEngine {
                override val translationContext = semantic("Korean")
                override suspend fun translatePage(page: Bitmap): PageResult = PageResult.Failed("manual retry failed")
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
                    error("Completed pages must not resume pending analysis")
                override fun warmUp() = Unit
                override fun close() = Unit
            })
            assertEquals(PageTranslator.SinglePageResult.NOT_TRANSLATED, fixture.translate())
            assertEquals(completed, fixture.store.load(PAGE))
            assertArrayEquals(output, fixture.page.readBytes())
            assertEquals(kept + "$PAGE\tmanual retry failed", fixture.errors.readLines())
            assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            fixture.assertOriginalPreserved()
        }
    }

    @Test
    fun chapterSuccessClearsItsDiagnosticAndPreservesOtherPageNightAndMaterialsOwners() = runBlocking {
        withChapterDiagnosticFixture { fixture ->
            val kept = listOf(
                "002.png\tother page failure",
                "001.png.extra\tunrelated diagnostic with a shared prefix",
                "(night)\t$PAGE\tnight generation failure",
                "(materials)\toptional materials warning",
            )
            fixture.errors.writeText((kept + "$PAGE\tprevious page failure").joinToString("\n", postfix = "\n"))
            val calls = AtomicInteger()
            val returned = mutableListOf<PageResult.Translated>()
            fixture.select(object : TranslationEngine {
                override val translationContext = semantic("Korean")
                override suspend fun translatePage(page: Bitmap): PageResult {
                    calls.incrementAndGet()
                    assertEquals(Color.WHITE, page.getPixel(0, 0))
                    return (result("한국어", true) as PageResult.Translated).also { returned += it }
                }
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
                    error("A fresh chapter page must not resume saved analysis")
                override fun warmUp() = Unit
                override fun close() = Unit
            })
            val progress = mutableListOf<Pair<Int, Int>>()
            assertEquals(
                1,
                fixture.translator.translateChapter(
                    fixture.chapter,
                    "boxfill",
                    onProgress = { done, total -> progress += done to total },
                    nightMaterials = false,
                ),
            )
            assertEquals(1, calls.get())
            assertEquals(listOf(0 to 1, 1 to 1), progress)
            assertTrue(fixture.translator.isChapterTranslated(fixture.chapter))
            assertEquals("Fresh completed output uses the legacy completion path", null, fixture.store.load(PAGE))
            assertEquals(
                setOf(PAGE),
                CompletionManifestCodec.decode(File(fixture.directory, CompletionManifestCodec.PRIMARY).readBytes()),
            )
            fixture.assertOriginalPreserved()
            assertSolidPage(fixture.page, Color.BLACK)
            assertTrue(returned.single().page.isRecycled)
            assertTrue(requireNotNull(returned.single().analysis).mask.isRecycled)
            assertEquals(
                "Chapter success must preserve diagnostics owned by other pages, night and materials",
                kept,
                fixture.errors.takeIf { it.isFile }?.readLines().orEmpty(),
            )
        }
    }

    @Test
    fun chapterFailureReplacesOnlyItsDiagnosticAndPreservesOtherOwners() = runBlocking {
        withChapterDiagnosticFixture { fixture ->
            val kept = listOf(
                "002.png\tother page failure",
                "001.png.extra\tunrelated diagnostic with a shared prefix",
                "(night)\t$PAGE\tnight generation failure",
                "(materials)\toptional materials warning",
            )
            fixture.errors.writeText((kept + "$PAGE\tprevious page failure").joinToString("\n", postfix = "\n"))
            val calls = AtomicInteger()
            fixture.select(object : TranslationEngine {
                override val translationContext = semantic("Korean")
                override suspend fun translatePage(page: Bitmap): PageResult {
                    calls.incrementAndGet()
                    return PageResult.Failed("current chapter page failure")
                }
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
                    error("A fresh chapter page must not resume saved analysis")
                override fun warmUp() = Unit
                override fun close() = Unit
            })
            val progress = mutableListOf<Pair<Int, Int>>()
            assertEquals(
                0,
                fixture.translator.translateChapter(
                    fixture.chapter,
                    "boxfill",
                    onProgress = { done, total -> progress += done to total },
                    nightMaterials = false,
                ),
            )
            assertEquals(1, calls.get())
            assertEquals(listOf(0 to 1, 1 to 1), progress)
            assertFalse(fixture.translator.isChapterTranslated(fixture.chapter))
            assertArrayEquals(fixture.initialBytes, fixture.page.readBytes())
            assertFalse(File(fixture.materials, "001.orig.png").exists())
            assertEquals(
                "Chapter failure must replace only its own page diagnostic",
                kept + "$PAGE\tcurrent chapter page failure",
                fixture.errors.takeIf { it.isFile }?.readLines().orEmpty(),
            )
        }
    }

    @Test
    fun chapterFreshNoTextCompletionClearsOnlyItsOwnDiagnosticAndPreservesSource() = runBlocking {
        withChapterDiagnosticFixture { fixture ->
            val kept = listOf(
                "002.png\tother page failure",
                "(night)\t$PAGE\tnight generation failure",
                "(materials)\toptional materials warning",
            )
            fixture.errors.writeText((kept + "$PAGE\tprevious page failure").joinToString("\n", postfix = "\n"))
            val calls = AtomicInteger()
            val masks = mutableListOf<Bitmap>()
            fixture.select(object : TranslationEngine {
                override val translationContext = semantic("Korean")
                override suspend fun translatePage(page: Bitmap): PageResult {
                    calls.incrementAndGet()
                    assertEquals(Color.WHITE, page.getPixel(0, 0))
                    val progress = PageProgress(SIZE, SIZE, emptyList(), noTextDetected = true)
                    return PageResult.Skipped(
                        "No text detected",
                        PageStats(0, 0, 0, 0, 0, 0, 0, 0),
                        PageAnalysis(image(Color.WHITE).also { masks += it }, emptyList(), progress),
                    )
                }
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
                    error("A fresh chapter page must not resume saved analysis")
                override fun warmUp() = Unit
                override fun close() = Unit
            })
            val progress = mutableListOf<Pair<Int, Int>>()
            assertEquals(
                0,
                fixture.translator.translateChapter(
                    fixture.chapter,
                    "boxfill",
                    onProgress = { done, total -> progress += done to total },
                    nightMaterials = false,
                ),
            )
            assertEquals(1, calls.get())
            assertEquals(listOf(0 to 1, 1 to 1), progress)
            assertTrue(fixture.translator.isChapterTranslated(fixture.chapter))
            assertEquals(
                setOf(PAGE),
                CompletionManifestCodec.decode(File(fixture.directory, CompletionManifestCodec.PRIMARY).readBytes()),
            )
            assertArrayEquals(fixture.initialBytes, fixture.page.readBytes())
            assertFalse(
                "Unchanged no-text completion must not invent an original copy",
                File(fixture.materials, "001.orig.png").exists(),
            )
            assertTrue(masks.single().isRecycled)
            assertEquals(
                "Fresh no-text chapter completion must clear only its own diagnostic",
                kept,
                fixture.errors.takeIf { it.isFile }?.readLines().orEmpty(),
            )
        }
    }

    @Test
    fun chapterStoppedBeforeInferencePreservesUnattemptedPageDiagnostics() = runBlocking {
        withChapterDiagnosticFixture { fixture ->
            val second = File(fixture.directory, "002.png").apply { writeBytes(encoded(Color.BLUE)) }
            val kept = listOf(
                "$PAGE\tunattempted first page failure",
                "002.png\tunattempted second page failure",
                "(night)\t$PAGE\tnight generation failure",
                "(materials)\toptional materials warning",
            )
            val diagnosticBytes = kept.joinToString("\n", postfix = "\n").toByteArray()
            fixture.errors.writeBytes(diagnosticBytes)
            val sourceHashes = protectedFileHashes(fixture.directory)
            val calls = AtomicInteger()
            fixture.select(object : TranslationEngine {
                override val translationContext = semantic("Korean")
                override suspend fun translatePage(page: Bitmap): PageResult {
                    calls.incrementAndGet()
                    error("A stopped chapter must not start page inference")
                }
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
                    calls.incrementAndGet()
                    error("A stopped chapter must not resume page inference")
                }
                override fun warmUp() = Unit
                override fun close() = Unit
            })
            val progress = mutableListOf<Pair<Int, Int>>()
            assertEquals(
                0,
                fixture.translator.translateChapter(
                    fixture.chapter,
                    "boxfill",
                    onProgress = { done, total -> progress += done to total },
                    shouldStop = { true },
                    nightMaterials = false,
                ),
            )
            assertEquals(0, calls.get())
            assertEquals(listOf(0 to 2), progress)
            assertFalse(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
            assertFalse(fixture.translator.isPageTranslated(fixture.chapter, second.name))
            assertEquals(sourceHashes, protectedFileHashes(fixture.directory))
            assertArrayEquals(
                "A cooperative stop before inference must preserve unattempted diagnostics",
                diagnosticBytes,
                fixture.errors.takeIf { it.isFile }?.readBytes() ?: byteArrayOf(),
            )
        }
    }

    @Test
    fun chapterCompletionCannotClearLaterManualFailureRecordedByPageDoneCallback() = runBlocking {
        withChapterDiagnosticFixture { fixture ->
            val calls = AtomicInteger()
            fixture.select(object : TranslationEngine {
                override val translationContext = semantic("Korean")
                override suspend fun translatePage(page: Bitmap): PageResult = when (calls.incrementAndGet()) {
                    1 -> result("한국어", true)
                    2 -> PageResult.Failed("newer manual page failure")
                    else -> error("Unexpected chapter or manual inference")
                }
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
                    error("A completed page requires a fresh manual attempt")
                override fun warmUp() = Unit
                override fun close() = Unit
            })
            var callbackCalls = 0
            var committedManifest: ByteArray? = null
            var committedFiles: Map<String, String>? = null
            assertEquals(
                1,
                fixture.translator.translateChapter(
                    fixture.chapter,
                    "boxfill",
                    nightMaterials = false,
                    onPageDone = { pageName ->
                        callbackCalls++
                        assertEquals(PAGE, pageName)
                        assertEquals(null, fixture.store.load(PAGE))
                        committedManifest = File(fixture.directory, CompletionManifestCodec.PRIMARY).readBytes().also {
                            assertEquals(setOf(PAGE), CompletionManifestCodec.decode(it))
                        }
                        committedFiles = protectedFileHashes(fixture.directory)
                        // The public callback runs after the chapter page is durable, outside its
                        // page/manifest locks. Complete a later manual attempt before returning.
                        val manual = runBlocking { withTimeout(5_000) { fixture.translate() } }
                        assertEquals(PageTranslator.SinglePageResult.NOT_TRANSLATED, manual)
                        assertEquals(null, fixture.store.load(PAGE))
                        assertArrayEquals(
                            requireNotNull(committedManifest),
                            File(fixture.directory, CompletionManifestCodec.PRIMARY).readBytes(),
                        )
                        assertEquals(committedFiles, protectedFileHashes(fixture.directory))
                        assertEquals(listOf("$PAGE\tnewer manual page failure"), fixture.errors.readLines())
                    },
                ),
            )
            assertEquals(1, callbackCalls)
            assertEquals(2, calls.get())
            assertEquals(null, fixture.store.load(PAGE))
            assertArrayEquals(
                requireNotNull(committedManifest),
                File(fixture.directory, CompletionManifestCodec.PRIMARY).readBytes(),
            )
            assertEquals(committedFiles, protectedFileHashes(fixture.directory))
            assertTrue(fixture.translator.isChapterTranslated(fixture.chapter))
            fixture.assertOriginalPreserved()
            assertEquals(
                "Chapter completion must not clear a newer manual failure recorded after page commit",
                listOf("$PAGE\tnewer manual page failure"),
                fixture.errors.takeIf { it.isFile }?.readLines().orEmpty(),
            )
        }
    }

    @Test
    fun diagnosticDirectoryCannotTurnCommittedResumeIntoFailureOrBeDeleted() = runBlocking {
        withPending { fixture, _ ->
            check(fixture.errors.delete())
            check(fixture.errors.mkdir())
            val sentinel = File(fixture.errors, "unowned.txt").apply { writeText("preserve this directory") }
            fixture.selectSameContextCompletion()
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertTrue(fixture.store.load(PAGE)!!.complete)
            assertTrue(fixture.errors.isDirectory)
            assertEquals("preserve this directory", sentinel.readText())
            fixture.assertOriginalPreserved()
        }
    }

    @Test
    fun lateResumeSuccessCannotClearConcurrentCurrentFailureDiagnostic() = runBlocking {
        withPending { fixture, pending ->
            coroutineScope {
                val firstEntered = CompletableDeferred<Unit>()
                val secondEntered = CompletableDeferred<Unit>()
                val allowSuccess = CompletableDeferred<Unit>()
                val allowFailure = CompletableDeferred<Unit>()
                val failureReturned = CompletableDeferred<Unit>()
                val calls = AtomicInteger()
                fixture.select(object : TranslationEngine {
                    override val translationContext = semantic("Korean")
                    override suspend fun translatePage(page: Bitmap): PageResult = error("Expected pending resumes")
                    override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
                        when (calls.incrementAndGet()) {
                            1 -> {
                                firstEntered.complete(Unit)
                                allowSuccess.await()
                                result("한국어", true)
                            }
                            2 -> {
                                secondEntered.complete(Unit)
                                allowFailure.await()
                                failureReturned.complete(Unit)
                                PageResult.Failed("concurrent current failure")
                            }
                            else -> error("Unexpected inference")
                        }
                    override fun warmUp() = Unit
                    override fun close() = Unit
                })
                val manifestMutex = PageTranslator::class.java.getDeclaredField("manifestMutex").apply {
                    isAccessible = true
                }.get(null) as Mutex
                val owner = Any()
                manifestMutex.lock(owner)
                var ownsManifestLock = true
                try {
                    val success = async(Dispatchers.Default) { fixture.translate() }
                    withTimeout(5_000) { firstEntered.await() }
                    val failure = async(Dispatchers.Default) { fixture.translate() }
                    withTimeout(5_000) { secondEntered.await() }
                    allowSuccess.complete(Unit)
                    // The successful output is durable, but that attempt still owns the page lock
                    // while awaiting the manifest. Let another already-running inference fail.
                    withTimeout(5_000) {
                        while (!runCatching { fixture.store.currentStateInfo(PAGE)?.complete == true }
                                .getOrDefault(false)
                        ) {
                            delay(10)
                        }
                    }
                    allowFailure.complete(Unit)
                    withTimeout(5_000) { failureReturned.await() }
                    assertEquals(null, withTimeoutOrNull(200) { failure.await() })
                    manifestMutex.unlock(owner)
                    ownsManifestLock = false
                    assertEquals(PageTranslator.SinglePageResult.TRANSLATED, withTimeout(5_000) { success.await() })
                    assertEquals(PageTranslator.SinglePageResult.NOT_TRANSLATED, withTimeout(5_000) { failure.await() })
                    val completed = requireNotNull(fixture.store.load(PAGE))
                    assertTrue(completed.complete)
                    assertEquals(pending.revision, completed.baseRevision)
                    assertEquals(listOf("$PAGE\tconcurrent current failure"), fixture.errors.readLines())
                    fixture.assertOriginalPreserved()
                } finally {
                    allowSuccess.complete(Unit)
                    allowFailure.complete(Unit)
                    if (ownsManifestLock) manifestMutex.unlock(owner)
                }
            }
        }
    }

    @Test
    fun cancelledNightDiagnosticWaitsForManifestLockAndPreservesOtherOwners() = runBlocking {
        withFixture { fixture ->
            coroutineScope {
                val kept = listOf("$PAGE\tOCR_PENDING: translation pending", "(materials)\toptional warning")
                val initial = kept + "(night)\t002.png\tprevious night failure"
                fixture.errors.writeText(initial.joinToString("\n"))
                val manifestMutex = PageTranslator::class.java.getDeclaredField("manifestMutex").apply {
                    isAccessible = true
                }.get(null) as Mutex
                val owner = Any()
                manifestMutex.lock(owner)
                var ownsManifestLock = true
                val cleanupEntered = CompletableDeferred<Unit>()
                val writer = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        cleanupEntered.complete(Unit)
                        // Exercise the actual private writer without opening native night models.
                        fixture.writeNightErrorsForTest(listOf("002.png\tcollected before cancellation"))
                    }
                }
                try {
                    writer.cancel()
                    withTimeout(5_000) { cleanupEntered.await() }
                    // A cancellable lock would return without recording anything at this point.
                    assertEquals(
                        null,
                        withTimeoutOrNull(200) {
                            writer.join()
                            true
                        },
                    )
                    assertEquals(initial, fixture.errors.readLines())
                    manifestMutex.unlock(owner)
                    ownsManifestLock = false
                    withTimeout(5_000) { writer.join() }
                    assertTrue(writer.isCancelled)
                    assertEquals(
                        kept + "(night)\t002.png\tcollected before cancellation",
                        fixture.errors.readLines(),
                    )
                } finally {
                    writer.cancel()
                    if (ownsManifestLock) manifestMutex.unlock(owner)
                }
            }
        }
    }

    private suspend fun assertFreshSkippedCompletes(
        fixture: Fixture,
        previous: PartialPageStore.State?,
        language: String,
        noText: Boolean,
    ) {
        val previousOutput = fixture.page.readBytes()
        val previousRecord = previous?.let {
            File(fixture.materials, it.outputSnapshotName.removeSuffix(".output") + ".record")
        }
        val previousRecordBytes = previousRecord?.readBytes()
        var fullCalls = 0
        var resumeCalls = 0
        val freshProgress = if (noText) {
            PageProgress(SIZE, SIZE, emptyList(), noTextDetected = true)
        } else {
            PageProgress(
                SIZE,
                SIZE,
                listOf(
                    RegionProgress(
                        0,
                        RegionState.INTENTIONALLY_KEPT,
                        listOf(
                            OcrLineProgress(
                                listOf(Pt(0f, 0f), Pt(16f, 0f), Pt(16f, 16f), Pt(0f, 16f)),
                                1f,
                                "h",
                                "fresh deliberately kept source",
                            ),
                        ),
                        "h",
                    ),
                ),
            )
        }
        val freshEngine = object : TranslationEngine {
            override val translationContext = semantic(language)
            override suspend fun translatePage(page: Bitmap): PageResult {
                fullCalls++
                assertEquals("Fresh inference must use the preserved source", Color.WHITE, page.getPixel(0, 0))
                return PageResult.Skipped(
                    if (noText) "No text detected" else "All regions intentionally kept",
                    stats(0),
                    PageAnalysis(image(Color.BLACK), freshProgress.toTextRegions(), freshProgress),
                )
            }
            override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
                resumeCalls++
                error("A completed page requires fresh inference, never pending resume")
            }
            override fun warmUp() = Unit
            override fun close() = Unit
        }
        fixture.select(freshEngine)
        // A kept result must replace the old target/context without claiming a new translation.
        assertEquals("SKIPPED", fixture.translate().name)
        assertEquals(1, fullCalls)
        assertEquals(0, resumeCalls)
        val replacement = requireNotNull(fixture.store.load(PAGE))
        assertTrue(replacement.complete)
        assertEquals(previous?.revision, replacement.baseRevision)
        assertEquals((previous?.generation ?: 0L) + 1L, replacement.generation)
        assertEquals(freshProgress, OcrProgressCodec.decode(replacement.progressPayload, SIZE, SIZE))
        assertEquals(freshEngine.translationContext, StoredTranslationContext.decode(replacement.safeContextPayload!!))
        assertFalse(fixture.page.readBytes().contentEquals(previousOutput))
        assertArrayEquals(fixture.page.readBytes(), fixture.store.readOutput(replacement))
        val visible = requireNotNull(BitmapFactory.decodeFile(fixture.page.path))
        try {
            val pixels = IntArray(SIZE * SIZE)
            visible.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            assertTrue("Fresh kept/no-text result must display source pixels", pixels.all { it == Color.WHITE })
        } finally {
            visible.recycle()
        }
        if (previous != null) {
            assertEquals(previous.original, replacement.original)
            assertEquals(previous.initialBaseline, replacement.initialBaseline)
            assertArrayEquals(previousOutput, fixture.store.readOutput(previous))
            assertArrayEquals(previousRecordBytes, requireNotNull(previousRecord).readBytes())
            assertEquals(
                semantic("Korean"),
                StoredTranslationContext.decode(previous.safeContextPayload!!),
            )
            assertTrue(
                OcrProgressCodec.decode(previous.progressPayload, SIZE, SIZE).regions.all {
                    it.translatedText.startsWith("한국어")
                },
            )
        } else {
            assertEquals(PartialPageStore.OriginalProvenance.EXACT_BYTES, replacement.original.provenance)
            assertArrayEquals(previousOutput, fixture.store.readInitialBaseline(replacement))
        }
        assertTrue(fixture.translator.isPageTranslated(fixture.chapter, PAGE))
        assertTrue(fixture.translator.isChapterTranslated(fixture.chapter))
        fixture.assertOriginalPreserved()
    }

    private suspend fun withCompleted(block: suspend (Fixture, PartialPageStore.State) -> Unit) {
        withPending { fixture, pending ->
            var resumeCalls = 0
            fixture.select(object : TranslationEngine {
                override val translationContext = semantic("Korean")
                override suspend fun translatePage(page: Bitmap): PageResult = error("Expected same-context resume")
                override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
                    resumeCalls++
                    return result("한국어", true)
                }
                override fun warmUp() = Unit
                override fun close() = Unit
            })
            assertEquals(PageTranslator.SinglePageResult.TRANSLATED, fixture.translate())
            assertEquals(1, resumeCalls)
            val completed = requireNotNull(fixture.store.load(PAGE))
            assertTrue(completed.complete)
            assertEquals(pending.revision, completed.baseRevision)
            block(fixture, completed)
        }
    }

    private suspend fun withPending(block: suspend (Fixture, PartialPageStore.State) -> Unit) {
        withFixture { fixture ->
            fixture.select(object : TranslationEngine {
                override val translationContext = semantic("Korean")
                override suspend fun translatePage(page: Bitmap): PageResult = result("한국어", false)
                override fun warmUp() = Unit
                override fun close() = Unit
            })
            assertEquals(PageTranslator.SinglePageResult.PARTIAL, fixture.translate())
            val pending = requireNotNull(fixture.store.load(PAGE))
            assertEquals(semantic("Korean"), StoredTranslationContext.decode(pending.safeContextPayload!!))
            block(fixture, pending)
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        assertTrue("PageTranslator requires the prepared model set", TranslationEngineConfig.modelsResolvable(context))
        val directory = File(context.cacheDir, "stored-context-${System.nanoTime()}").apply { check(mkdir()) }
        val source = image(Color.WHITE)
        val page = File(directory, PAGE)
        try {
            page.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            source.recycle()
        }
        val initialBytes = page.readBytes()
        val service = TranslationEngineService(context)
        val signature = TranslationEngineService::class.java.getDeclaredMethod(
            "configSignature",
            String::class.java,
            TranslationPreferences::class.java,
        ).apply { isAccessible = true }.invoke(service, "boxfill", Injekt.get<TranslationPreferences>().snapshot())
        field("builtSignature").set(service, signature)
        field("warmedUp").setBoolean(service, true)
        val translator = PageTranslator(context).also {
            PageTranslator::class.java.getDeclaredField("engineService").apply { isAccessible = true }.set(it, service)
        }
        val fixture = Fixture(directory, page, initialBytes, service, translator)
        try {
            block(fixture)
        } finally {
            service.shutdown()
            directory.deleteRecursively()
        }
    }

    private class Fixture(
        val directory: File,
        val page: File,
        val initialBytes: ByteArray,
        val service: TranslationEngineService,
        val translator: PageTranslator,
    ) {
        val chapter = requireNotNull(UniFile.fromFile(directory))
        val materials get() = File(directory, ".yakuyomi")
        val errors get() = File(directory, ".yakuyomi_errors.txt")
        val store get() = PartialPageStore.forUniFile(
            InstrumentationRegistry.getInstrumentation().targetContext,
            chapter,
            requireNotNull(UniFile.fromFile(materials)),
        )
        fun select(engine: TranslationEngine) {
            field("engine").set(service, engine)
        }
        fun selectSameContextCompletion() = select(object : TranslationEngine {
            override val translationContext = semantic("Korean")
            override suspend fun translatePage(page: Bitmap): PageResult = error("Expected same-context resume")
            override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult = result("한국어", true)
            override fun warmUp() = Unit
            override fun close() = Unit
        })
        suspend fun writeNightErrorsForTest(lines: List<String>) =
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                val writer = PageTranslator::class.java.getDeclaredMethod(
                    "writeNightErrors",
                    UniFile::class.java,
                    List::class.java,
                    Continuation::class.java,
                ).apply { isAccessible = true }
                try {
                    writer.invoke(translator, chapter, lines, continuation)
                } catch (failure: InvocationTargetException) {
                    throw failure.targetException
                }
            }
        suspend fun translate() = translator.translateSinglePage(chapter, PAGE, "boxfill")
        fun assertOriginalPreserved() = assertArrayEquals(initialBytes, File(materials, "001.orig.png").readBytes())
    }

    companion object {
        private const val PAGE = "001.png"
        private const val SIZE = 32
        private fun field(name: String) = TranslationEngineService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        private fun semantic(
            language: String,
        ) = TranslationContext.fromTranslator(TranslatorConfig(toLangName = language))
        private fun image(
            color: Int,
        ) = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
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
        private fun stats(kept: Int) = PageStats(2, 2, kept, 0, 0, 0, 0, 0)
        private fun result(language: String, complete: Boolean): PageResult {
            val progress = PageProgress(
                SIZE,
                SIZE,
                (0..1).map { id ->
                    val resolved = id == 0 || complete
                    RegionProgress(
                        id,
                        if (resolved) RegionState.TRANSLATED else RegionState.OCR_PENDING,
                        listOf(
                            OcrLineProgress(
                                listOf(Pt(0f, 0f), Pt(16f, 0f), Pt(16f, 16f), Pt(0f, 16f)),
                                1f,
                                "h",
                                if (resolved) "source ${id + 1}" else "",
                            ),
                        ),
                        "h",
                        translatedText = if (resolved) "$language ${id + 1}" else "",
                    )
                },
            )
            val analysis = PageAnalysis(image(Color.WHITE), progress.toTextRegions(), progress)
            val output = image(if (complete) Color.BLACK else Color.BLUE)
            return if (complete) {
                PageResult.Translated(
                    output,
                    stats(2),
                    analysis,
                )
            } else {
                PageResult.Partial(output, stats(1), analysis)
            }
        }
    }
}
