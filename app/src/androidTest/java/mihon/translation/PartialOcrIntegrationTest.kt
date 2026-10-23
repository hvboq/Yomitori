package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Process
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
import eu.kanade.tachiyomi.data.translation.TranslationManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.Grouping
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.InpainterConfig
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrRecognizer
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.PageStats
import li.joye.yakuyomi.engine.Pipeline
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionProgress
import li.joye.yakuyomi.engine.RegionState
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TranslationContext
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.Translator
import li.joye.yakuyomi.engine.TranslatorConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Explicitly selected local native tests. Required private models must exist or the test fails.
 * Real detection/OCR/removal/rendering use neutral synthetic text; an OCR decorator forces only
 * the first read incomplete. The HTTP server is local and tests protocol, not translation quality.
 * No endpoint/key/preferences are changed and these tests do not claim reader-menu coverage.
 */
@RunWith(AndroidJUnit4::class)
class PartialOcrIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nativeFreshFormatOnlyTargetFailsTranslationAndPreservesOriginalPixels() = runBlocking<Unit> {
        requireNonRenderableNativeAudit()
        val requests = mutableListOf<List<String>>()
        val invalidTranslator = object : Translator {
            override suspend fun translate(queries: List<String>): List<String> {
                requests += queries.toList()
                return queries.mapIndexed { index, _ ->
                    if (index == queries.lastIndex) "\u200B\uFEFF\u200D\u202E\u0007" else "안녕하세요."
                }
            }
        }
        nativePipeline(force = Force.NONE, translator = invalidTranslator) { pipeline, ocr ->
            val original = fixture()
            val originalHash = digest(original)
            var result: PageResult? = null
            try {
                result = pipeline.translatePage(original)
                assertTrue(result.toString(), result is PageResult.Failed)
                val failed = result as PageResult.Failed
                assertTrue(failed.reason, failed.reason.startsWith("translate:"))
                assertTrue(failed.reason, failed.reason.contains("Incomplete translation:"))
                assertEquals(1, ocr.calls)
                assertEquals(1, requests.size)
                assertTrue("Real OCR must produce separate translation regions", requests.single().size >= 2)
                assertTrue(requests.single().all { it.isNotBlank() })
                assertFalse(original.isRecycled)
                assertEquals("An unusable target must not overwrite the input pixels", originalHash, digest(original))
            } finally {
                release(result)
                original.recycle()
            }
        }
    }

    @Test
    fun nativeFormatOnlyResumeAndCorruptSavedTargetRetainSuccessfulProgressAndBorrowedPixels() = runBlocking<Unit> {
        requireNonRenderableNativeAudit()
        val requests = mutableListOf<List<String>>()
        var invalid = false
        val translator = object : Translator {
            override suspend fun translate(queries: List<String>): List<String> {
                requests += queries.toList()
                return queries.map { if (invalid) " \u200B\uFEFF\u200D\u202E\u0001" else "안녕하세요. 오늘도 잘 부탁드립니다." }
            }
        }
        nativePipeline(translator = translator) { pipeline, ocr ->
            val original = fixture()
            val originalHash = digest(original)
            var first: PageResult.Partial? = null
            var failedResume: PageResult? = null
            var corruptResume: PageResult? = null
            var borrowedMask: Bitmap? = null
            try {
                val partial = partial(pipeline.translatePage(original))
                first = partial
                val before = requireNotNull(partial.analysis.progress)
                val completed = before.regions.filter { it.state == RegionState.TRANSLATED }
                val pending = before.regions.single { it.state == RegionState.OCR_PENDING }
                assertTrue("Fixture must preserve an earlier successful translation", completed.isNotEmpty())
                assertEquals(completed.map(::source), requests.single())
                val payload = OcrProgressCodec.encode(before)
                val originalMaskHash = digest(partial.analysis.mask)
                val mask = requireNotNull(partial.analysis.mask.copy(Bitmap.Config.ARGB_8888, false))
                borrowedMask = mask
                val borrowedMaskHash = digest(mask)
                val analysis = PageAnalysis(mask, before.toTextRegions(), before)
                fun liveProgress() = OcrProgressCodec.encode(
                    PageProgress.capture(
                        original.width,
                        original.height,
                        analysis.regions,
                        before.regions.map { it.state },
                    ),
                )
                val liveBefore = liveProgress()
                invalid = true
                failedResume = pipeline.resumePage(original, analysis)
                assertTrue(failedResume.toString(), failedResume is PageResult.Failed)
                val failed = failedResume as PageResult.Failed
                assertTrue(failed.reason, failed.reason.startsWith("translate:"))
                assertTrue(failed.reason, failed.reason.contains("Incomplete translation:"))
                assertEquals(2, ocr.calls)
                assertEquals(listOf(pending.lines.single { it.text.isBlank() }.quad), ocr.lastInputQuads)
                assertEquals(2, requests.size)
                assertEquals(1, requests.last().size)
                assertTrue(
                    "Previously successful groups must not re-enter translation",
                    requests.last().none { it in completed.map(::source) },
                )
                assertEquals(payload, OcrProgressCodec.encode(before))
                assertEquals(payload, OcrProgressCodec.encode(requireNotNull(partial.analysis.progress)))
                assertEquals(liveBefore, liveProgress())
                assertTrue(before.hasPending)
                assertEquals(completed, before.regions.filter { it.state == RegionState.TRANSLATED })
                assertEquals(originalHash, digest(original))
                assertEquals(originalMaskHash, digest(partial.analysis.mask))
                assertEquals(borrowedMaskHash, digest(mask))
                assertFalse(mask.isRecycled)

                // A persisted unusable success must fail before OCR, translation, or borrowed-mask copying.
                val corrupt = before.copy(
                    regions = before.regions.map { saved ->
                        if (saved.id == completed.first().id) saved.copy(translatedText = "\uFEFF\u200B") else saved
                    },
                )
                corruptResume = pipeline.resumePage(original, PageAnalysis(mask, analysis.regions, corrupt))
                assertTrue(corruptResume.toString(), corruptResume is PageResult.Failed)
                val corruptFailure = corruptResume as PageResult.Failed
                assertTrue(corruptFailure.reason, corruptFailure.reason.startsWith("resume:"))
                assertTrue(
                    corruptFailure.reason,
                    corruptFailure.reason.contains("Translated OCR progress contains unresolved or excluded text"),
                )
                assertEquals("Corrupt saved state must fail before recognition", 2, ocr.calls)
                assertEquals("Corrupt saved state must fail before translation", 2, requests.size)
                assertEquals(payload, OcrProgressCodec.encode(before))
                assertEquals(liveBefore, liveProgress())
                assertEquals(originalHash, digest(original))
                assertEquals(originalMaskHash, digest(partial.analysis.mask))
                assertEquals(borrowedMaskHash, digest(mask))
                assertFalse(mask.isRecycled)
            } finally {
                release(first)
                release(failedResume)
                release(corruptResume)
                borrowedMask?.recycle()
                original.recycle()
            }
        }
    }

    private fun requireNonRenderableNativeAudit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "app.yomitori.yakuyomi.audit") {
            "Non-renderable native regression requires the isolated audit installation"
        }
        check(InstrumentationRegistry.getArguments().getString("nonRenderableNativeAudit") == "true") {
            "Explicit nonRenderableNativeAudit=true is required before native models are accessed"
        }
    }

    @Test
    fun nativeResumeReadsOnlyMissingLineAndPreservesSuccessfulTargetsGeometryAndRawMask() = runBlocking<Unit> {
        FixtureTranslationServer().use { server ->
            nativePipeline(server = server) { pipeline, ocr ->
                val original = fixture()
                val originalHash = digest(original)
                var first: PageResult.Partial? = null
                var resumed: PageResult.Translated? = null
                var borrowedMask: Bitmap? = null
                try {
                    val partial = partial(pipeline.translatePage(original))
                    first = partial
                    val before = requireNotNull(partial.analysis.progress)
                    val completed = before.regions.filter { it.state == RegionState.TRANSLATED }
                    assertTrue(completed.isNotEmpty())
                    assertEquals(1, before.pendingRegionIds.size)
                    val pending = before.regions.single { it.state == RegionState.OCR_PENDING }
                    assertTrue(
                        "Forced group must also retain a successfully read line",
                        pending.lines.any {
                            it.text.isNotBlank()
                        },
                    )
                    assertEquals(1, pending.lines.count { it.text.isBlank() })
                    assertTrue(
                        "Raw DBNet mask must retain the pending lettering",
                        maskedPixels(partial.analysis.mask, pending) > 0,
                    )
                    val payload = OcrProgressCodec.encode(before)
                    val restored = OcrProgressCodec.decode(payload, original.width, original.height)
                    val mask = requireNotNull(partial.analysis.mask.copy(Bitmap.Config.ARGB_8888, false))
                    borrowedMask = mask
                    val maskHash = digest(mask)
                    val analysis = PageAnalysis(mask, restored.toTextRegions(), restored)
                    val translated = translated(pipeline.resumePage(original, analysis))
                    resumed = translated
                    val after = requireNotNull(translated.analysis?.progress)
                    assertFalse(after.hasPending)
                    completed.forEach {
                        assertEquals("Finalized target and geometry changed", it, after.regions[it.id])
                    }
                    assertEquals(before.regions.map { it.id }, after.regions.map { it.id })
                    assertEquals(2, ocr.calls)
                    assertEquals(listOf(requireNotNull(ocr.forcedQuad)), ocr.lastInputQuads)
                    assertEquals(2, server.requests.size)
                    assertEquals(completed.map(::source), texts(server.requests[0]))
                    assertEquals(listOf(source(after.regions[pending.id])), texts(server.requests[1]))
                    assertEquals("Original source pixels changed", originalHash, digest(original))
                    assertEquals("Borrowed mask was changed", maskHash, digest(mask))
                    assertFalse(mask.isRecycled)
                    assertNotSame(mask, requireNotNull(translated.analysis).mask)
                    assertEquals(maskHash, digest(requireNotNull(translated.analysis).mask))
                    assertEquals("Old immutable progress changed", payload, OcrProgressCodec.encode(before))
                    assertEquals(payload, OcrProgressCodec.encode(requireNotNull(partial.analysis.progress)))
                    assertNull(server.failure)
                } finally {
                    release(first)
                    release(resumed)
                    borrowedMask?.recycle()
                    original.recycle()
                }
            }
        }
    }

    @Test
    fun malformedAndCancelledResumeKeepPreviousProgressAndCanResumeAgain() = runBlocking<Unit> {
        FixtureTranslationServer().use { server ->
            nativePipeline(server = server) { pipeline, _ ->
                val original = fixture()
                val originalHash = digest(original)
                var first: PageResult.Partial? = null
                var finished: PageResult.Translated? = null
                val allowResponse = CountDownLatch(1)
                try {
                    val partial = partial(pipeline.translatePage(original))
                    first = partial
                    val before = requireNotNull(partial.analysis.progress)
                    val payload = OcrProgressCodec.encode(before)
                    val maskHash = digest(partial.analysis.mask)
                    server.malformedResponse = true
                    val failed = pipeline.resumePage(original, partial.analysis)
                    try {
                        assertTrue(failed.toString(), failed is PageResult.Failed)
                        assertTrue((failed as PageResult.Failed).reason.startsWith("translate:"))
                    } finally {
                        release(failed)
                    }
                    assertEquals(2, server.requests.size)
                    assertEquals(payload, OcrProgressCodec.encode(before))
                    assertEquals(maskHash, digest(partial.analysis.mask))
                    server.malformedResponse = false
                    val responseEntered = CountDownLatch(1)
                    server.expectClientDisconnect = setOf(3)
                    server.beforeResponse = { number ->
                        if (number == 3) {
                            responseEntered.countDown()
                            check(allowResponse.await(10, TimeUnit.SECONDS)) { "Cancellation gate not released" }
                        }
                    }
                    var cancelledOutput: PageResult? = null
                    val cancelled = async(Dispatchers.Default) {
                        pipeline.resumePage(original, partial.analysis).also { cancelledOutput = it }
                    }
                    try {
                        withTimeout(20_000) { while (responseEntered.count != 0L) delay(10) }
                        withTimeout(3_000) { cancelled.cancelAndJoin() }
                    } finally {
                        allowResponse.countDown()
                        cancelled.cancelAndJoin()
                        release(cancelledOutput)
                    }
                    withTimeout(5_000) { while (3 !in server.disconnectedRequests) delay(10) }
                    assertEquals(payload, OcrProgressCodec.encode(before))
                    assertEquals(maskHash, digest(partial.analysis.mask))
                    assertEquals(originalHash, digest(original))
                    assertFalse(partial.analysis.mask.isRecycled)
                    val translated = translated(pipeline.resumePage(original, partial.analysis))
                    finished = translated
                    val after = requireNotNull(translated.analysis?.progress)
                    assertFalse(after.hasPending)
                    before.regions.filter { it.state == RegionState.TRANSLATED }.forEach {
                        assertEquals(it, after.regions[it.id])
                    }
                    assertEquals(4, server.requests.size)
                    assertEquals(texts(server.requests[1]), texts(server.requests[2]))
                    assertEquals(texts(server.requests[2]), texts(server.requests[3]))
                    assertNull(server.failure)
                } finally {
                    allowResponse.countDown()
                    release(first)
                    release(finished)
                    original.recycle()
                }
            }
        }
    }

    @Test
    fun allBlankAndFilteredOnlyGroupsRemainPartialAndAllKeptResumeHasFinalProgress() = runBlocking<Unit> {
        var requests = 0
        val keep = object : Translator {
            override suspend fun translate(queries: List<String>): List<String> {
                requests++
                return queries
            }
        }
        for (force in listOf(Force.ALL, Force.ONE_LINE)) {
            requests = 0
            nativePipeline(force = force, translator = keep) { pipeline, _ ->
                val original = fixture()
                var first: PageResult.Partial? = null
                var finished: PageResult.Translated? = null
                try {
                    val partial = partial(pipeline.translatePage(original))
                    first = partial
                    val before = requireNotNull(partial.analysis.progress)
                    assertTrue(before.hasPending)
                    assertEquals(0, partial.stats.kept)
                    assertTrue(original.sameAs(partial.page))
                    assertNotSame(original, partial.page)
                    assertEquals(if (force == Force.ALL) 0 else 1, requests)
                    if (force == Force.ONE_LINE) {
                        assertTrue(before.regions.any { it.state == RegionState.INTENTIONALLY_KEPT })
                    }
                    val translated = translated(pipeline.resumePage(original, partial.analysis))
                    finished = translated
                    val after = requireNotNull(translated.analysis?.progress)
                    assertFalse(after.hasPending)
                    assertTrue(after.regions.all { it.state == RegionState.INTENTIONALLY_KEPT })
                    assertEquals(0, translated.stats.kept)
                    assertTrue(original.sameAs(translated.page))
                    assertEquals(if (force == Force.ALL) 1 else 2, requests)
                    val fresh = pipeline.translatePage(original)
                    try {
                        assertTrue("Fresh all-kept pages still use Skipped", fresh is PageResult.Skipped)
                        val finalAnalysis = requireNotNull((fresh as PageResult.Skipped).analysis)
                        assertFalse(finalAnalysis.mask.isRecycled)
                        assertFalse(requireNotNull(finalAnalysis.progress).hasPending)
                        assertTrue(finalAnalysis.progress!!.regions.all { it.state == RegionState.INTENTIONALLY_KEPT })
                    } finally {
                        release(fresh)
                    }
                } finally {
                    release(first)
                    release(finished)
                    original.recycle()
                }
            }
        }
    }

    @Test
    fun folderChapterResumeIgnoresOldCompletionCheckpointAndKeepsExactOriginal() = runBlocking<Unit> {
        storedLifecycle(singlePage = false)
    }

    @Test
    fun singlePagePartialSurvivesTranslatorRecreationAndResumesWithColdServiceRegistration() = runBlocking<Unit> {
        storedLifecycle(singlePage = true)
    }

    @Test
    fun nativeNoTextSkipReturnsEmptyFinalProgressAndCallerOwnedRawMask() = runBlocking<Unit> {
        FixtureTranslationServer().use { server ->
            nativePipeline(server = server) { pipeline, ocr ->
                val original = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                var skipped: PageResult? = null
                try {
                    val before = digest(original)
                    val result = pipeline.translatePage(original)
                    skipped = result
                    assertTrue(result.toString(), result is PageResult.Skipped)
                    val analysis = requireNotNull((result as PageResult.Skipped).analysis)
                    val progress = requireNotNull(analysis.progress)
                    assertTrue(progress.noTextDetected)
                    assertFalse(progress.hasPending)
                    assertTrue(progress.regions.isEmpty())
                    assertFalse(analysis.mask.isRecycled)
                    assertEquals(original.width, analysis.mask.width)
                    assertEquals(original.height, analysis.mask.height)
                    assertEquals(before, digest(original))
                    assertEquals(0, ocr.calls)
                    assertTrue(server.requests.isEmpty())
                } finally {
                    release(skipped)
                    original.recycle()
                }
            }
        }
    }

    @Test
    fun completedExactPageCanEnterPartialJournalAndResumeFromItsPreservedOriginal() = runBlocking<Unit> {
        storedLifecycle(singlePage = true, migrated = MigratedSource.EXACT)
    }

    @Test
    fun legacyLossyWebpPartialCanRestorePngAndResumeWithoutSourcePromotion() = runBlocking<Unit> {
        storedLifecycle(singlePage = true, migrated = MigratedSource.LEGACY_WEBP)
    }

    @Test
    fun cbzNativePartialJournalSurvivesRepackingAndResumesFromPreservedOriginal() = runBlocking<Unit> {
        FixtureTranslationServer().use { server ->
            nativePipeline(server = server) { pipeline, _ ->
                val directory = File(context.cacheDir, "partial-cbz-${System.nanoTime()}").apply { check(mkdirs()) }
                val archive = File(directory, "chapter.cbz")
                val bitmap = fixture()
                val originalPage = try {
                    ByteArrayOutputStream().use { bytes ->
                        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes))
                        bytes.toByteArray()
                    }
                } finally {
                    bitmap.recycle()
                }
                ZipOutputStream(archive.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("001.png"))
                    zip.write(originalPage)
                    zip.closeEntry()
                }
                val originalArchive = archive.readBytes()
                var initialProgress: PageProgress? = null
                var resumedProgress: PageProgress? = null
                val adapter = object : TranslationEngine {
                    override val translationContext get() = pipeline.translationContext
                    override suspend fun translatePage(page: Bitmap): PageResult = pipeline.translatePage(page).also {
                        initialProgress = (it as? PageResult.Partial)?.analysis?.progress
                    }
                    override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult =
                        pipeline.resumePage(original, analysis).also {
                            resumedProgress = (it as? PageResult.Translated)?.analysis?.progress
                        }
                    override fun warmUp() = Unit
                    override fun close() = Unit
                }
                val service = isolatedService(adapter)
                fun translator(): PageTranslator = PageTranslator(context).also {
                    PageTranslator::class.java.getDeclaredField("engineService").apply {
                        isAccessible = true
                    }.set(it, service)
                }
                val probe = translator()
                var pending: PartialPageStore.State? = null
                var finalState: PartialPageStore.State? = null
                try {
                    assertFalse(
                        "A partial archive is persisted but must not count as completely translated",
                        rewrite(archive) { extracted ->
                            val active = translator()
                            active.translateChapter(extracted, "boxfill", nightMaterials = false)
                            val store = PartialPageStore.forUniFile(
                                context,
                                extracted,
                                requireNotNull(extracted.findFile(".yakuyomi")),
                            )
                            pending = requireNotNull(store.load("001.png"))
                            assertFalse(requireNotNull(pending).complete)
                            assertTrue(active.hasPartialProgress(extracted))
                            active.hasPartialProgress(extracted) to active.isChapterTranslated(extracted)
                        },
                    )
                    assertFalse(probe.isArchiveTranslated(requireNotNull(UniFile.fromFile(archive))))
                    val partialArchive = archive.readBytes()
                    assertFalse(originalArchive.contentEquals(partialArchive))
                    ZipFile(archive).use { packed ->
                        assertArrayEquals(
                            originalPage,
                            packed.getInputStream(packed.getEntry(".yakuyomi/001.orig.png")).use {
                                it.readBytes()
                            },
                        )
                        assertTrue(packed.entries().asSequence().any { it.name.endsWith(".commit") })
                    }
                    assertEquals(1, server.requests.size)
                    assertTrue(
                        rewrite(archive) { extracted ->
                            val active = translator()
                            assertTrue(active.hasPartialProgress(extracted))
                            assertEquals(1, active.translateChapter(extracted, "boxfill", nightMaterials = false))
                            val store = PartialPageStore.forUniFile(
                                context,
                                extracted,
                                requireNotNull(extracted.findFile(".yakuyomi")),
                            )
                            finalState = requireNotNull(store.load("001.png"))
                            assertTrue(requireNotNull(finalState).complete)
                            true to active.isChapterTranslated(extracted)
                        },
                    )
                    assertTrue(probe.isArchiveTranslated(requireNotNull(UniFile.fromFile(archive))))
                    assertEquals(2, server.requests.size)
                    val before = requireNotNull(initialProgress)
                    val after = requireNotNull(resumedProgress)
                    assertFalse(after.hasPending)
                    before.regions.filter {
                        it.state == RegionState.TRANSLATED
                    }.forEach { assertEquals(it, after.regions[it.id]) }
                    assertEquals(requireNotNull(pending).original, requireNotNull(finalState).original)
                    assertEquals(requireNotNull(pending).rawMaskSha256, requireNotNull(finalState).rawMaskSha256)
                    ZipFile(archive).use { packed ->
                        assertArrayEquals(
                            originalPage,
                            packed.getInputStream(packed.getEntry(".yakuyomi/001.orig.png")).use {
                                it.readBytes()
                            },
                        )
                    }
                    val backups = directory.listFiles().orEmpty().filter {
                        it.name.startsWith("chapter.cbz.yomitori-original-")
                    }
                    assertEquals(2, backups.size)
                    assertTrue(backups.any { it.readBytes().contentEquals(originalArchive) })
                    assertTrue(backups.any { it.readBytes().contentEquals(partialArchive) })
                    assertNull(server.failure)
                } finally {
                    service.shutdown()
                    directory.deleteRecursively()
                }
            }
        }
    }

    @Test
    fun replacedPageCannotInheritSkippedCompletionFromAnOlderInference() = runBlocking<Unit> {
        assertTrue(
            "PageTranslator requires configured models even with an isolated test engine",
            TranslationEngineConfig.modelsResolvable(context),
        )
        val directory = File(context.cacheDir, "skip-source-race-${System.nanoTime()}").apply { check(mkdirs()) }
        fun imageBytes(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return try {
                ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
        val initialBytes = imageBytes(Color.WHITE)
        val replacementBytes = imageBytes(Color.BLUE)
        val page = File(directory, "001.png").apply { writeBytes(initialBytes) }
        val chapter = requireNotNull(UniFile.fromFile(directory))
        val entered = CompletableDeferred<Unit>()
        val allowResult = CompletableDeferred<Unit>()
        var calls = 0
        val fake = object : TranslationEngine {
            override val translationContext = TranslationContext.fromTranslator(TranslatorConfig())
            override suspend fun translatePage(page: Bitmap): PageResult {
                calls++
                if (calls == 1) {
                    assertEquals(Color.WHITE, page.getPixel(0, 0))
                    entered.complete(Unit)
                    allowResult.await()
                } else {
                    assertEquals(Color.BLUE, page.getPixel(0, 0))
                }
                return PageResult.Skipped("source-binding fixture", PageStats(0, 0, 0, 0, 0, 0, 0, 0))
            }
            override fun warmUp() = Unit
            override fun close() = Unit
        }
        val service = isolatedService(fake)
        val translator = PageTranslator(context).also {
            PageTranslator::class.java.getDeclaredField("engineService").apply { isAccessible = true }.set(it, service)
        }
        val first =
            async(Dispatchers.Default) { translator.translateChapter(chapter, "boxfill", nightMaterials = false) }
        try {
            withTimeout(10_000) { entered.await() }
            page.writeBytes(replacementBytes)
            allowResult.complete(Unit)
            withTimeout(10_000) { first.await() }
            assertEquals(1, calls)
            assertFalse(
                "An old Skipped result marked replacement content done",
                translator.isPageTranslated(chapter, "001.png"),
            )
            assertFalse(translator.isChapterTranslated(chapter))
            assertArrayEquals(replacementBytes, page.readBytes())
            // A new explicit attempt reads the replacement and can truthfully complete it.
            translator.translateChapter(chapter, "boxfill", nightMaterials = false)
            assertEquals(2, calls)
            assertTrue(translator.isPageTranslated(chapter, "001.png"))
            assertTrue(translator.isChapterTranslated(chapter))
            assertArrayEquals(replacementBytes, page.readBytes())
        } finally {
            allowResult.complete(Unit)
            first.cancelAndJoin()
            service.shutdown()
            directory.deleteRecursively()
        }
    }

    @Test
    fun legacyWebpOnlyOriginalSupportsManualFullTranslationWithoutInventingAnExactOriginal() = runBlocking<Unit> {
        assertTrue(
            "PageTranslator requires configured models even with an isolated test engine",
            TranslationEngineConfig.modelsResolvable(context),
        )
        val directory = File(context.cacheDir, "legacy-webp-retranslate-${System.nanoTime()}").apply { check(mkdirs()) }
        var service: TranslationEngineService? = null
        var generatedOutput: Bitmap? = null
        fun encoded(color: Int, format: Bitmap.CompressFormat): ByteArray {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return try {
                ByteArrayOutputStream().use {
                    check(bitmap.compress(format, 100, it))
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
        try {
            @Suppress("DEPRECATION")
            val webp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                Bitmap.CompressFormat.WEBP
            }
            val legacyBytes = encoded(Color.WHITE, webp)
            val page = File(directory, "001.png").apply { writeBytes(encoded(Color.BLUE, Bitmap.CompressFormat.PNG)) }
            val materials = File(directory, ".yakuyomi").apply { check(mkdir()) }
            val legacy = File(materials, "001.orig.webp").apply { writeBytes(legacyBytes) }
            File(directory, ".yakuyomi_translated").writeBytes(CompletionManifestCodec.encode(setOf("001.png")))
            assertFalse(File(materials, "001.json").exists())
            assertFalse(File(materials, "001.orig.png").exists())
            var calls = 0
            val fake = object : TranslationEngine {
                override val translationContext = TranslationContext.fromTranslator(TranslatorConfig())
                override suspend fun translatePage(page: Bitmap): PageResult {
                    calls++
                    assertEquals(
                        "Manual translation must use the preserved legacy source",
                        Color.WHITE,
                        page.getPixel(0, 0),
                    )
                    val output = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.BLACK)
                    }
                    generatedOutput = output
                    return PageResult.Translated(output, PageStats(0, 0, 0, 0, 0, 0, 0, 0))
                }
                override fun warmUp() = Unit
                override fun close() = Unit
            }
            val activeService = isolatedService(fake)
            service = activeService
            val translator = PageTranslator(context).also {
                PageTranslator::class.java.getDeclaredField("engineService").apply {
                    isAccessible = true
                }.set(it, activeService)
            }
            val chapter = requireNotNull(UniFile.fromFile(directory))
            assertTrue(translator.isPageTranslated(chapter, "001.png"))
            assertEquals(
                PageTranslator.SinglePageResult.TRANSLATED,
                translator.translateSinglePage(chapter, "001.png", "boxfill"),
            )
            assertEquals(1, calls)
            val output = requireNotNull(BitmapFactory.decodeFile(page.path))
            try {
                assertEquals(Color.BLACK, output.getPixel(0, 0))
                assertEquals(32, output.width)
                assertEquals(32, output.height)
            } finally {
                output.recycle()
            }
            assertArrayEquals("Legacy source bytes must survive manual translation", legacyBytes, legacy.readBytes())
            assertFalse(
                "A lossy/legacy source cannot be relabelled as an exact PNG original",
                File(materials, "001.orig.png").exists(),
            )
            assertTrue(translator.isPageTranslated(chapter, "001.png"))
            assertTrue(translator.isChapterTranslated(chapter))
        } finally {
            service?.shutdown()
            generatedOutput?.let { if (!it.isRecycled) it.recycle() }
            directory.deleteRecursively()
        }
    }

    @Test
    fun staleJsonRejectsRetranslationBeforeEngineAndPreservesOriginalAndCompletion() = runBlocking<Unit> {
        assertTrue(
            "PageTranslator requires configured models even with an isolated test engine",
            TranslationEngineConfig.modelsResolvable(context),
        )
        val directory = File(context.cacheDir, "stale-json-original-${System.nanoTime()}").apply { check(mkdirs()) }
        var service: TranslationEngineService? = null
        fun encoded(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return try {
                ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
        try {
            val page = File(directory, "001.png").apply { writeBytes(encoded(Color.BLUE)) }
            val materials = File(directory, ".yakuyomi").apply { check(mkdir()) }
            val original = File(materials, "001.orig.png").apply { writeBytes(encoded(Color.WHITE)) }
            val json = File(materials, "001.json").apply {
                writeText("""{"method":"boxfill","regions":[]}""")
            }
            val now = System.currentTimeMillis()
            check(json.setLastModified(now - 120_000))
            check(page.setLastModified(now - 60_000))
            assertTrue("Fixture must have old optional metadata", json.lastModified() in 1 until page.lastModified())
            val completed = CompletionManifestCodec.encode(setOf("001.png"))
            val manifest = File(directory, CompletionManifestCodec.PRIMARY).apply { writeBytes(completed) }
            val checkpoint = File(directory, CompletionManifestCodec.CHECKPOINT_PREFIX + UUID.randomUUID()).apply {
                writeBytes(completed)
            }
            val preserved = listOf(page, original, json, manifest, checkpoint).associateWith { it.readBytes() }
            val calls = AtomicInteger()
            val fake = object : TranslationEngine {
                override val translationContext = TranslationContext.fromTranslator(TranslatorConfig())
                override suspend fun translatePage(page: Bitmap): PageResult {
                    calls.incrementAndGet()
                    error("Ambiguous source ownership must be rejected before OCR or API work")
                }
                override fun warmUp() = Unit
                override fun close() = Unit
            }
            val activeService = isolatedService(fake)
            service = activeService
            val translator = PageTranslator(context).also {
                PageTranslator::class.java.getDeclaredField("engineService").apply {
                    isAccessible = true
                }.set(it, activeService)
            }
            val chapter = requireNotNull(UniFile.fromFile(directory))
            assertTrue(translator.isPageTranslated(chapter, "001.png"))

            assertEquals(
                PageTranslator.SinglePageResult.NOT_TRANSLATED,
                translator.translateSinglePage(chapter, "001.png", "boxfill"),
            )

            assertEquals("OCR/API must not run against an ambiguous completed page", 0, calls.get())
            preserved.forEach { (file, bytes) ->
                assertArrayEquals("Existing data changed: ${file.name}", bytes, file.readBytes())
            }
            assertTrue(translator.isPageTranslated(chapter, "001.png"))
            assertTrue(translator.isChapterTranslated(chapter))
            assertTrue(
                "Failure must be explicit without exposing source content",
                File(directory, ".yakuyomi_errors.txt").readText().contains("Cannot establish original ownership"),
            )
            assertFalse("Ownership rejection must not create partial progress", translator.hasPartialProgress(chapter))
        } finally {
            service?.shutdown()
            directory.deleteRecursively()
        }
    }

    /** Actual extraction/verification/backup/replacement; the callback is the test's API entry. */
    private suspend fun rewrite(archive: File, process: suspend (UniFile) -> Pair<Boolean, Boolean>): Boolean {
        val manager = TranslationManager(context)
        val resultClass = TranslationManager::class.java.declaredClasses.single { it.simpleName == "ProcessResult" }
        val constructor = resultClass.getDeclaredConstructor(
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
            .apply { isAccessible = true }
        val callback: suspend (UniFile) -> Any = { extracted ->
            val (swap, success) = process(extracted)
            constructor.newInstance(swap, success)
        }
        val method = TranslationManager::class.java.declaredMethods.single { it.name == "processArchiveInPlace" }
            .apply { isAccessible = true }
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                method.invoke(manager, UniFile.fromFile(archive), { _: Int, _: Int ->
                }, { false }, callback, continuation)
            } catch (failure: InvocationTargetException) {
                throw requireNotNull(failure.cause)
            }
        }
    }

    private enum class MigratedSource { EXACT, LEGACY_WEBP }

    internal suspend fun verifyLiveStoredLifecycle(
        translator: Translator,
        exportPending: Boolean = false,
        requestCount: () -> Int,
    ): JSONObject? {
        var exported: JSONObject? = null
        storedLifecycle(
            singlePage = true,
            externalTranslator = translator,
            externalRequestCount = requestCount,
            onFirstPending = if (exportPending) {
                { directory, pending, store, actualContext ->
                    exported = exportPendingForReleaseReader(directory, pending, store, actualContext)
                }
            } else {
                null
            },
        )
        return exported
    }

    private suspend fun storedLifecycle(
        singlePage: Boolean,
        migrated: MigratedSource? = null,
        externalTranslator: Translator? = null,
        externalRequestCount: (() -> Int)? = null,
        onFirstPending: ((File, PartialPageStore.State, PartialPageStore, TranslationContext) -> Unit)? = null,
    ) {
        FixtureTranslationServer().use { server ->
            val requestCount = externalRequestCount ?: { server.requests.size }
            nativePipeline(server = server, translator = externalTranslator) { pipeline, ocr ->
                val directory = File(context.cacheDir, "partial-ocr-${System.nanoTime()}").apply { check(mkdirs()) }
                val page = File(directory, "001.png")
                val source = fixture()
                try {
                    val format = if (migrated == MigratedSource.LEGACY_WEBP) {
                        @Suppress("DEPRECATION")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            Bitmap.CompressFormat.WEBP_LOSSY
                        } else {
                            Bitmap.CompressFormat.WEBP
                        }
                    } else {
                        Bitmap.CompressFormat.PNG
                    }
                    page.outputStream().use { check(source.compress(format, 90, it)) }
                } finally {
                    source.recycle()
                }
                val originalBytes = page.readBytes()
                val originalFile = File(
                    directory,
                    if (migrated == MigratedSource.LEGACY_WEBP) ".yakuyomi/001.orig.webp" else ".yakuyomi/001.orig.png",
                )
                if (migrated != null) {
                    check(originalFile.parentFile!!.mkdir())
                    originalFile.writeBytes(originalBytes)
                    val priorTranslation = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888).apply {
                        eraseColor(Color.BLUE)
                    }
                    try {
                        page.outputStream().use {
                            check(priorTranslation.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally {
                        priorTranslation.recycle()
                    }
                    File(directory, CompletionManifestCodec.PRIMARY)
                        .writeBytes(CompletionManifestCodec.encode(setOf("001.png")))
                }
                val previousOutput = page.readBytes()
                val chapter = requireNotNull(UniFile.fromFile(directory))
                var initialProgress: PageProgress? = null
                var finalProgress: PageProgress? = null
                var service: TranslationEngineService? = null
                var initialCalls = 0
                var resumeCalls = 0
                val adapter = object : TranslationEngine {
                    override val translationContext get() = pipeline.translationContext
                    private fun assertRegistered() {
                        val field = TranslationEngineService::class.java.getDeclaredField("inFlight").apply {
                            isAccessible = true
                        }
                        assertTrue((field.get(requireNotNull(service)) as AtomicInteger).get() > 0)
                    }
                    override suspend fun translatePage(page: Bitmap): PageResult {
                        assertRegistered()
                        initialCalls++
                        return pipeline.translatePage(page).also {
                            initialProgress = (it as? PageResult.Partial)?.analysis?.progress
                        }
                    }
                    override suspend fun resumePage(original: Bitmap, analysis: PageAnalysis): PageResult {
                        assertRegistered()
                        resumeCalls++
                        return pipeline.resumePage(original, analysis).also {
                            finalProgress = (it as? PageResult.Translated)?.analysis?.progress
                        }
                    }
                    override fun warmUp() = Unit
                    override fun close() = Unit // Test-owned native resources close after service detaches.
                }
                try {
                    val activeService = isolatedService(adapter)
                    service = activeService
                    fun translator(): PageTranslator = PageTranslator(context).also {
                        PageTranslator::class.java.getDeclaredField("engineService").apply { isAccessible = true }
                            .set(it, activeService)
                    }
                    val first = translator()
                    if (migrated != null) assertTrue(first.isPageTranslated(chapter, "001.png"))
                    if (singlePage) {
                        assertEquals(
                            PageTranslator.SinglePageResult.PARTIAL,
                            first.translateSinglePage(chapter, "001.png", "boxfill"),
                        )
                    } else {
                        first.translateChapter(chapter, "boxfill", nightMaterials = false)
                    }
                    assertEquals(1, initialCalls)
                    assertEquals(0, resumeCalls)
                    assertEquals(1, requestCount())
                    assertFalse(first.isPageTranslated(chapter, "001.png"))
                    assertFalse(first.isChapterTranslated(chapter))
                    assertTrue(first.hasDisplayableTranslation(chapter, "001.png"))
                    assertEquals(setOf("001.png"), first.displayablePages(chapter))
                    assertTrue(first.hasPartialProgress(chapter))
                    assertArrayEquals(originalBytes, originalFile.readBytes())
                    assertFalse(originalBytes.contentEquals(page.readBytes()))
                    val materials = requireNotNull(chapter.findFile(".yakuyomi"))
                    val store = PartialPageStore.forUniFile(context, chapter, materials)
                    val pending = requireNotNull(store.load("001.png"))
                    assertFalse(pending.complete)
                    assertTrue(
                        "The registered engine's actual context must be saved",
                        pending.safeContextPayload != null,
                    )
                    if (migrated != null) {
                        assertArrayEquals(previousOutput, store.readInitialBaseline(pending))
                        assertEquals(
                            if (migrated == MigratedSource.LEGACY_WEBP) {
                                PartialPageStore.OriginalProvenance.LEGACY_REENCODED
                            } else {
                                PartialPageStore.OriginalProvenance.EXACT_BYTES
                            },
                            pending.original.provenance,
                        )
                    } else {
                        assertNull(pending.initialBaseline)
                    }
                    val pendingProgress = OcrProgressCodec.decode(pending.progressPayload, 1200, 1600)
                    assertEquals(initialProgress, pendingProgress)
                    val rawMask = store.readRawMask(pending)
                    val maskHash = PartialPageStore.sha256(rawMask)
                    val actualContext = requireNotNull(pipeline.translationContext)
                    assertEquals(
                        actualContext,
                        StoredTranslationContext.decode(requireNotNull(pending.safeContextPayload)),
                    )
                    // Export only after the durable first pending has passed all normal checks.
                    // The native OCR decorator forced one first-pass blank; this is not a natural failure sample.
                    onFirstPending?.invoke(directory, pending, store, actualContext)
                    val restoreBeforeResume = !singlePage || migrated != null
                    if (restoreBeforeResume) {
                        assertTrue(first.reRenderPage(chapter, "001.png", "original"))
                        assertEquals(1, requestCount())
                        assertEquals(1, ocr.calls)
                        val restored = requireNotNull(store.load("001.png"))
                        assertFalse(restored.complete)
                        assertEquals(pending.progressPayload, restored.progressPayload)
                        assertEquals(pending.safeContextPayload, restored.safeContextPayload)
                        assertEquals(pending.initialBaseline, restored.initialBaseline)
                        assertEquals(maskHash, PartialPageStore.sha256(store.readRawMask(restored)))
                        assertArrayEquals(originalBytes, originalFile.readBytes())
                        if (migrated == MigratedSource.LEGACY_WEBP) {
                            assertTrue(
                                "The visible .png must remain PNG after legacy restoration",
                                page.readBytes()[0] == 0x89.toByte(),
                            )
                            val expectedImage = requireNotNull(
                                BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size),
                            )
                            val restoredImage = requireNotNull(BitmapFactory.decodeFile(page.path))
                            try {
                                assertEquals(digest(expectedImage), digest(restoredImage))
                            } finally {
                                expectedImage.recycle()
                                restoredImage.recycle()
                            }
                            assertFalse(File(directory, ".yakuyomi/001.orig.png").exists())
                        } else {
                            assertArrayEquals(originalBytes, page.readBytes())
                        }
                    }
                    // A valid old checkpoint must never override the explicit pending journal.
                    File(directory, ".yakuyomi_translated").writeText("001.png")
                    File(directory, ".yakuyomi_translated.checkpoint-${UUID.randomUUID()}").writeBytes(
                        CompletionManifestCodec.encode(setOf("001.png")),
                    )
                    val recreated = translator()
                    assertFalse(recreated.isPageTranslated(chapter, "001.png"))
                    assertFalse(recreated.isChapterTranslated(chapter))
                    TranslationEngineService::class.java.getDeclaredField("warmedUp").apply { isAccessible = true }
                        .setBoolean(activeService, false)
                    if (singlePage) {
                        assertEquals(
                            PageTranslator.SinglePageResult.TRANSLATED,
                            recreated.translateSinglePage(chapter, "001.png", "boxfill"),
                        )
                    } else {
                        assertEquals(1, recreated.translateChapter(chapter, "boxfill", nightMaterials = false))
                    }
                    assertEquals(1, initialCalls)
                    assertEquals(1, resumeCalls)
                    assertEquals(2, ocr.calls)
                    assertEquals(2, requestCount())
                    assertTrue(recreated.isPageTranslated(chapter, "001.png"))
                    assertTrue(recreated.isChapterTranslated(chapter))
                    assertFalse(recreated.hasPartialProgress(chapter))
                    val completed = requireNotNull(store.load("001.png"))
                    assertTrue(completed.complete)
                    assertEquals(pending.generation + (if (restoreBeforeResume) 2L else 1L), completed.generation)
                    assertEquals(pending.original, completed.original)
                    assertEquals(pending.initialBaseline, completed.initialBaseline)
                    assertEquals(pending.safeContextPayload, completed.safeContextPayload)
                    if (migrated != null) assertArrayEquals(previousOutput, store.readInitialBaseline(completed))
                    assertEquals(maskHash, PartialPageStore.sha256(store.readRawMask(completed)))
                    assertArrayEquals(originalBytes, originalFile.readBytes())
                    val after = requireNotNull(finalProgress)
                    assertFalse(after.hasPending)
                    pendingProgress.regions.filter { it.state == RegionState.TRANSLATED }.forEach {
                        assertEquals(it, after.regions[it.id])
                    }
                    assertEquals(after, OcrProgressCodec.decode(completed.progressPayload, 1200, 1600))
                    assertNull(server.failure)
                } finally {
                    service?.shutdown()
                    directory.deleteRecursively()
                }
            }
        }
    }

    /** Test-only opt-in export. The helper's original fixture is still removed by its normal finally. */
    private fun exportPendingForReleaseReader(
        sourceDirectory: File,
        pending: PartialPageStore.State,
        sourceStore: PartialPageStore,
        actualContext: TranslationContext,
    ): JSONObject {
        assertFalse(pending.complete)
        assertEquals(pending, sourceStore.load(pending.pageName, recover = false))
        val progress = OcrProgressCodec.decode(pending.progressPayload, pending.width, pending.height)
        assertTrue(progress.hasPending)
        assertTrue(progress.regions.any { it.state == RegionState.TRANSLATED })
        assertEquals(
            actualContext,
            StoredTranslationContext.decode(requireNotNull(pending.safeContextPayload)),
        )
        val external = requireNotNull(context.getExternalFilesDir(null))
        val exportParent = File(external, "release-partial-export").apply { check(isDirectory || mkdirs()) }
        val exportRoot = File(exportParent, "native-${UUID.randomUUID()}").apply { check(mkdir()) }
        val chapterDirectory = File(exportRoot, "chapter").apply { check(mkdir()) }
        check(exportRoot.canonicalPath.startsWith(exportParent.canonicalPath + File.separator))
        try {
            val sourcePrefix = sourceDirectory.canonicalPath + File.separator
            val destinationPrefix = chapterDirectory.canonicalPath + File.separator
            val sourceFiles = sourceDirectory.walkTopDown().filter { it.isFile }
                .sortedBy { it.relativeTo(sourceDirectory).invariantSeparatorsPath }.toList()
            assertTrue("The whole neutral chapter must contain files", sourceFiles.isNotEmpty())
            val fileMetadata = JSONArray()
            val sourceHashes = linkedMapOf<String, String>()
            for (sourceFile in sourceFiles) {
                check(sourceFile.canonicalPath.startsWith(sourcePrefix))
                val relative = sourceFile.relativeTo(sourceDirectory).invariantSeparatorsPath
                val destination = File(chapterDirectory, relative)
                check(destination.canonicalPath.startsWith(destinationPrefix))
                check(requireNotNull(destination.parentFile).let { it.isDirectory || it.mkdirs() })
                val bytes = sourceFile.readBytes()
                val expectedHash = PartialPageStore.sha256(bytes)
                destination.outputStream().use { it.write(bytes) }
                val readback = destination.readBytes()
                assertEquals("Copied file length must match", bytes.size, readback.size)
                assertEquals("Copied file SHA must match", expectedHash, PartialPageStore.sha256(readback))
                sourceHashes[relative] = expectedHash
                fileMetadata.put(
                    JSONObject().put("name", relative).put("length", bytes.size).put("sha256", expectedHash),
                )
            }
            val copiedNames = chapterDirectory.walkTopDown().filter { it.isFile }
                .map { it.relativeTo(chapterDirectory).invariantSeparatorsPath }.toSet()
            assertEquals("Export must include every chapter file", sourceHashes.keys, copiedNames)
            for ((relative, hash) in sourceHashes) {
                assertEquals(hash, PartialPageStore.sha256(File(sourceDirectory, relative).readBytes()))
                assertEquals(hash, PartialPageStore.sha256(File(chapterDirectory, relative).readBytes()))
            }
            val copiedChapter = requireNotNull(UniFile.fromFile(chapterDirectory))
            val copiedMaterials = requireNotNull(copiedChapter.findFile(".yakuyomi"))
            val copiedStore = PartialPageStore.forUniFile(context, copiedChapter, copiedMaterials)
            val copiedPending = requireNotNull(copiedStore.load(pending.pageName, recover = false))
            assertEquals("Copied durable record must match", pending, copiedPending)
            assertArrayEquals(sourceStore.readRawMask(pending), copiedStore.readRawMask(copiedPending))
            assertArrayEquals(sourceStore.readOutput(pending), copiedStore.readOutput(copiedPending))
            assertEquals(
                actualContext,
                StoredTranslationContext.decode(requireNotNull(copiedPending.safeContextPayload)),
            )
            val recordName = pending.outputSnapshotName.removeSuffix(".output") + ".record"
            val commitName = pending.outputSnapshotName.removeSuffix(".output") + ".commit"
            assertTrue("Preserved original must be copied", ".yakuyomi/${pending.original.name}" in copiedNames)
            assertTrue("Durable record must be copied", ".yakuyomi/$recordName" in copiedNames)
            assertTrue("Durable commit must be copied", ".yakuyomi/$commitName" in copiedNames)
            assertTrue(
                "Output snapshot must be copied",
                ".yakuyomi/${pending.outputSnapshotName}" in copiedNames,
            )
            assertTrue("Raw mask must be copied", ".yakuyomi/${pending.rawMaskSnapshotName}" in copiedNames)
            assertEquals(pending.outputSha256, sourceHashes[pending.pageName])
            assertEquals(pending.original.sha256, sourceHashes[".yakuyomi/${pending.original.name}"])
            assertEquals(pending.rawMaskSha256, sourceHashes[".yakuyomi/${pending.rawMaskSnapshotName}"])
            val metadata = JSONObject().put("schemaVersion", 1).put("exportVerified", true)
                .put("directory", chapterDirectory.path).put("fileCount", copiedNames.size).put("files", fileMetadata)
                .put("nativeOcr", true).put("externalServer", true).put("forcedFirstPassBlank", true)
                .put("actualReaderActivity", false).put("pageName", pending.pageName)
                .put("revision", pending.revision).put("generation", pending.generation).put("complete", false)
                .put("successfulRegionCount", progress.regions.count { it.state == RegionState.TRANSLATED })
                .put("pendingRegionCount", progress.regions.count { it.state == RegionState.OCR_PENDING })
                .put("semanticFingerprint", actualContext.semanticFingerprint)
                .put("contextSource", "registeredNativePipeline")
                .put("originalSha256", pending.original.sha256)
                .put("originalProvenance", pending.original.provenance.name)
                .put("outputSha256", pending.outputSha256).put("rawMaskSha256", pending.rawMaskSha256)
            val manifestFile = File(exportRoot, "manifest.json")
            val manifestBytes = metadata.toString(2).toByteArray(Charsets.UTF_8)
            manifestFile.writeBytes(manifestBytes)
            assertArrayEquals(manifestBytes, manifestFile.readBytes())
            return metadata
        } catch (failure: Throwable) {
            check(exportRoot.canonicalPath.startsWith(exportParent.canonicalPath + File.separator))
            exportRoot.deleteRecursively()
            throw failure
        }
    }

    private fun isolatedService(engine: TranslationEngine): TranslationEngineService {
        val service = TranslationEngineService(context)
        val signature = TranslationEngineService::class.java.getDeclaredMethod(
            "configSignature",
            String::class.java,
            TranslationPreferences::class.java,
        ).apply { isAccessible = true }.invoke(service, "boxfill", Injekt.get<TranslationPreferences>().snapshot())
        fun field(name: String) = TranslationEngineService::class.java.getDeclaredField(name).apply {
            isAccessible =
                true
        }
        field("engine").set(service, engine)
        field("builtSignature").set(service, signature)
        field("warmedUp").setBoolean(service, true)
        return service
    }

    private suspend fun nativePipeline(
        force: Force = Force.ONE_LINE,
        server: FixtureTranslationServer? = null,
        translator: Translator? = null,
        block: suspend (Pipeline, ForcedFirstOcr) -> Unit,
    ) {
        assertTrue("Native OCR requires an ARM64 process", Process.is64Bit())
        Injekt.get<TranslationEngineService>().shutdown()
        fun model(name: String): String {
            val param = File(context.filesDir, "models/$name.ncnn.param")
            val bin = File(context.filesDir, "models/$name.ncnn.bin")
            check(param.isFile && bin.isFile) { "Required private native model is missing: $name" }
            return param.path
        }
        val alphabet = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
        Detector(model("dbnet_detect")).use { detector ->
            Ocr(model("ocr_48px_ctc"), alphabet).use { nativeOcr ->
                Inpainter(model("mit_aot_fixed512"), InpainterConfig(method = "boxfill")).use { inpainter ->
                    val ocr = ForcedFirstOcr(nativeOcr, force)
                    val activeTranslator = translator ?: LlmTranslator(
                        "",
                        TranslatorConfig(
                            provider = "custom",
                            apiBase = requireNotNull(server).base + "/chat/completions",
                            model = "fixture-model",
                            fromLangName = "Japanese",
                            toLangName = "Korean",
                            sampleSource = "",
                            sampleTarget = "",
                        ),
                    )
                    val pipeline = Pipeline(
                        detector,
                        ocr,
                        activeTranslator,
                        inpainter,
                        EngineConfig(render = RenderConfig(orientation = TextOrientation.HORIZONTAL)),
                    )
                    pipeline.warmUp()
                    block(pipeline, ocr)
                }
            }
        }
    }

    private enum class Force { NONE, ONE_LINE, ALL }

    /** Deliberately controls only OCR availability; all recognized text comes from the real model. */
    private class ForcedFirstOcr(private val native: Ocr, private val force: Force) : OcrRecognizer {
        override val intentionalFilterContext get() = native.intentionalFilterContext
        var calls = 0
            private set
        var forcedQuad: List<Pt>? = null
            private set
        var lastInputQuads = emptyList<List<Pt>>()
            private set

        override suspend fun recognize(page: Bitmap, lines: List<TextLine>): Set<TextLine> {
            calls++
            lastInputQuads = lines.map { it.quad.toList() }
            val ignored = native.recognize(page, lines)
            check(ignored.isEmpty()) { "Neutral fixture must not trigger an ignore policy" }
            check(lines.all { it.text.isNotBlank() }) { "Native OCR could not read the neutral fixture" }
            if (calls == 1) {
                if (force == Force.ALL) {
                    lines.forEach { it.text = "" }
                } else if (force == Force.ONE_LINE) {
                    val groups = Grouping.group(lines)
                    check(groups.size >= 2) { "Fixture must have separate balloons" }
                    val group = groups.firstOrNull { it.lines.size >= 2 }
                        ?: error("Fixture must contain a multiline group")
                    val missing = group.lines.last()
                    forcedQuad = missing.quad.toList()
                    missing.text = ""
                }
            }
            return ignored
        }

        override fun warmUp() = native.warmUp()
        override fun close() = Unit // Enclosing use block owns the native model.
    }

    private fun partial(result: PageResult): PageResult.Partial {
        if (result is PageResult.Partial) return result
        release(result)
        error("Expected partial OCR, received ${result.javaClass.simpleName}: $result")
    }

    private fun translated(result: PageResult): PageResult.Translated {
        if (result is PageResult.Translated) return result
        release(result)
        error("Expected resolved OCR progress, received ${result.javaClass.simpleName}: $result")
    }

    private fun release(result: PageResult?) {
        if (result is PageResult.Skipped) {
            result.analysis?.mask?.recycle()
            return
        }
        val output = when (result) {
            is PageResult.Translated -> result.page to result.analysis?.mask
            is PageResult.Partial -> result.page to result.analysis.mask
            else -> null
        }
        output?.let { (page, mask) ->
            page.recycle()
            mask?.let { if (it !== page) it.recycle() }
        }
    }

    private fun source(region: RegionProgress): String = region.lines.joinToString("") { it.text }

    private fun texts(request: String): List<String> = JSONArray(request).let { regions ->
        (0 until regions.length()).map { regions.getJSONObject(it).getString("text") }
    }

    private fun maskedPixels(mask: Bitmap, region: RegionProgress): Int {
        val points = region.lines.flatMap { it.quad }
        val left = points.minOf { it.x }.toInt().coerceIn(0, mask.width - 1)
        val top = points.minOf { it.y }.toInt().coerceIn(0, mask.height - 1)
        val right = points.maxOf { it.x }.toInt().coerceIn(left + 1, mask.width)
        val bottom = points.maxOf { it.y }.toInt().coerceIn(top + 1, mask.height)
        val pixels = IntArray((right - left) * (bottom - top))
        mask.getPixels(pixels, 0, right - left, left, top, right - left, bottom - top)
        return pixels.count { (it and 0xff) > 127 }
    }

    private fun digest(bitmap: Bitmap): String {
        val md = MessageDigest.getInstance("SHA-256")
        val chunkRows = minOf(32, bitmap.height)
        val pixels = IntArray(bitmap.width * chunkRows)
        val bytes = ByteBuffer.allocate(pixels.size * 4)
        for (y in 0 until bitmap.height step chunkRows) {
            val rows = minOf(chunkRows, bitmap.height - y)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, rows)
            bytes.clear()
            for (i in 0 until bitmap.width * rows) bytes.putInt(pixels[i])
            md.update(bytes.array(), 0, bitmap.width * rows * 4)
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun fixture(): Bitmap = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888).apply {
        Canvas(this).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 48f
            }
            drawText("こんにちは。", 150f, 260f, paint)
            drawText("少し待ってください。", 150f, 320f, paint)
            drawText("明日はいい天気ですね。", 150f, 1050f, paint)
            drawText("よろしくお願いします。", 150f, 1110f, paint)
        }
    }
}
