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
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.Translator
import li.joye.yakuyomi.engine.TranslatorConfig
import org.json.JSONArray
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

    private suspend fun storedLifecycle(singlePage: Boolean) {
        FixtureTranslationServer().use { server ->
            nativePipeline(server = server) { pipeline, ocr ->
                val directory = File(context.cacheDir, "partial-ocr-${System.nanoTime()}").apply { check(mkdirs()) }
                val page = File(directory, "001.png")
                val source = fixture()
                try {
                    page.outputStream().use { check(source.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    source.recycle()
                }
                val originalBytes = page.readBytes()
                val chapter = requireNotNull(UniFile.fromFile(directory))
                var initialProgress: PageProgress? = null
                var finalProgress: PageProgress? = null
                var service: TranslationEngineService? = null
                var initialCalls = 0
                var resumeCalls = 0
                val adapter = object : TranslationEngine {
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
                    assertEquals(1, server.requests.size)
                    assertFalse(first.isPageTranslated(chapter, "001.png"))
                    assertFalse(first.isChapterTranslated(chapter))
                    assertTrue(first.hasDisplayableTranslation(chapter, "001.png"))
                    assertEquals(setOf("001.png"), first.displayablePages(chapter))
                    assertTrue(first.hasPartialProgress(chapter))
                    val originalFile = File(directory, ".yakuyomi/001.orig.png")
                    assertArrayEquals(originalBytes, originalFile.readBytes())
                    assertFalse(originalBytes.contentEquals(page.readBytes()))
                    val materials = requireNotNull(chapter.findFile(".yakuyomi"))
                    val store = PartialPageStore.forUniFile(context, chapter, materials)
                    val pending = requireNotNull(store.load("001.png"))
                    assertFalse(pending.complete)
                    val pendingProgress = OcrProgressCodec.decode(pending.progressPayload, 1200, 1600)
                    assertEquals(initialProgress, pendingProgress)
                    val rawMask = store.readRawMask(pending)
                    val maskHash = PartialPageStore.sha256(rawMask)
                    if (!singlePage) {
                        assertTrue(first.reRenderPage(chapter, "001.png", "original"))
                        assertEquals(1, server.requests.size)
                        assertEquals(1, ocr.calls)
                        val restored = requireNotNull(store.load("001.png"))
                        assertFalse(restored.complete)
                        assertEquals(pending.progressPayload, restored.progressPayload)
                        assertEquals(maskHash, PartialPageStore.sha256(store.readRawMask(restored)))
                        assertArrayEquals(originalBytes, originalFile.readBytes())
                        assertArrayEquals(originalBytes, page.readBytes())
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
                    assertEquals(2, server.requests.size)
                    assertTrue(recreated.isPageTranslated(chapter, "001.png"))
                    assertTrue(recreated.isChapterTranslated(chapter))
                    assertFalse(recreated.hasPartialProgress(chapter))
                    val completed = requireNotNull(store.load("001.png"))
                    assertTrue(completed.complete)
                    assertEquals(pending.generation + (if (singlePage) 1L else 2L), completed.generation)
                    assertEquals(pending.original, completed.original)
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

    private enum class Force { ONE_LINE, ALL }

    /** Deliberately controls only OCR availability; all recognized text comes from the real model. */
    private class ForcedFirstOcr(private val native: Ocr, private val force: Force) : OcrRecognizer {
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
                } else {
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
