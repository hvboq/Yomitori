package mihon.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.Grouping
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.InpainterConfig
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.OcrConfig
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.Pipeline
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.Renderer
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TextRegion
import li.joye.yakuyomi.engine.TranslationEngine
import li.joye.yakuyomi.engine.Translator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.service.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Local native models only; no translation server, preference changes or source artwork. */
@RunWith(AndroidJUnit4::class)
class OcrFailureSafetyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun model(name: String) = File(context.filesDir, "models/$name.ncnn.param").path
    private fun alphabet() = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }

    @Test
    fun capacityFailureKeepsChapterOriginalUnmarkedAndRetryable() = runBlocking<Unit> {
        Injekt.get<TranslationEngineService>().shutdown()
        val page = Bitmap.createBitmap(1200, 1200, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                drawColor(Color.WHITE)
                drawText(
                    "こんにちは。",
                    150f,
                    250f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.BLACK
                        textSize = 72f
                    },
                )
            }
        }
        val dir = File(context.cacheDir, "ocr-capacity-${System.nanoTime()}").apply { mkdirs() }
        try {
            // Real model loaded with ordinary 48px preprocessing: a thin detected quad alone
            // exceeds the PE capacity. It must throw before allocating its 24,000px warped strip.
            Ocr(model("ocr_48px_ctc"), alphabet(), OcrConfig(stripPad = 0)).use { ocr ->
                val thin = line(100f, 100f, 1100f, 102f, "")
                try {
                    ocr.recognize(page, listOf(thin))
                    fail("An oversized OCR strip must not be silently read as blank")
                } catch (expected: IllegalArgumentException) {
                    assertTrue(expected.message.orEmpty().contains("exceeds model capacity"))
                }
                assertEquals("", thin.text)
            }
            val file = File(dir, "001.png")
            file.outputStream().use { assertTrue(page.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val originalBytes = file.readBytes()
            val chapter = requireNotNull(UniFile.fromFile(dir))
            var engineCalls = 0
            val noTranslation = object : Translator {
                override suspend fun translate(queries: List<String>): List<String> =
                    error("Capacity failure must occur before translation")
            }
            Detector(model("dbnet_detect")).use { detector ->
                // Deterministically reproduce the same capacity condition on a native-detected
                // ordinary text line, without relying on a detector producing an extreme quad.
                Ocr(model("ocr_48px_ctc"), alphabet(), OcrConfig(textHeight = 10_000)).use { ocr ->
                    Inpainter(model("mit_aot_fixed512"), InpainterConfig(method = "boxfill")).use { inpainter ->
                        val pipeline = Pipeline(detector, ocr, noTranslation, inpainter)
                        val engine = object : TranslationEngine {
                            override suspend fun translatePage(page: Bitmap): PageResult {
                                engineCalls++
                                return pipeline.translatePage(page).also { result ->
                                    assertTrue(result.toString(), result is PageResult.Failed)
                                    val reason = (result as PageResult.Failed).reason
                                    assertTrue(reason, reason.startsWith("ocr:"))
                                    assertTrue(reason, reason.contains("exceeds model capacity"))
                                }
                            }
                            override fun warmUp() = Unit
                            override fun close() = Unit // Native resources belong to the enclosing use blocks.
                        }
                        val service = isolatedService(engine)
                        try {
                            val translator = PageTranslator(context)
                            PageTranslator::class.java.getDeclaredField("engineService").apply {
                                isAccessible = true
                            }.set(translator, service)
                            repeat(2) { attempt ->
                                assertEquals(0, translator.translateChapter(chapter, "boxfill", nightMaterials = false))
                                assertEquals("Failure must stay pending for the next attempt", attempt + 1, engineCalls)
                                assertFalse(translator.isPageTranslated(chapter, "001.png"))
                                assertArrayEquals(originalBytes, file.readBytes())
                            }
                        } finally {
                            service.shutdown()
                        }
                    }
                }
            }
        } finally {
            page.recycle()
            dir.deleteRecursively()
        }
    }

    @Test
    fun incompleteGroupedOcrProtectsBothLinesThroughMaskInpaintAndRender() = runBlocking<Unit> {
        Injekt.get<TranslationEngineService>().shutdown()
        val lines = listOf(
            line(40f, 40f, 240f, 80f, "読める"),
            line(40f, 85f, 240f, 125f, ""),
            line(350f, 350f, 550f, 390f, "別の台詞"),
        )
        val regions = Grouping.group(lines)
        assertEquals(2, regions.size)
        // Exercise the exact candidate selector used by Pipeline without introducing fake native
        // outputs or depending on a model confidence threshold to produce a particular blank line.
        val select = Class.forName("li.joye.yakuyomi.engine.PageProgressKt")
            .getDeclaredMethod("selectOcrRegions", List::class.java, Set::class.java).apply { isAccessible = true }
        fun selection(input: List<TextRegion>): Any = select.invoke(null, input, emptySet<TextLine>())
        fun selected(result: Any, getter: String): Any = result.javaClass.getDeclaredMethod(getter).apply {
            isAccessible = true
        }.invoke(result)

        @Suppress("UNCHECKED_CAST")
        val candidates = selected(selection(regions), "getReady") as List<TextRegion>
        assertEquals(listOf("別の台詞"), candidates.map { it.sourceText })
        candidates.single().translatedText = "안녕"
        val protected = regions.single { it.preserveOriginal }
        assertEquals(2, protected.lines.size)
        // Pending is explicit even with no independent complete region; it cannot become done.
        val partialSelection = selection(listOf(protected))
        assertEquals(emptyList<TextRegion>(), selected(partialSelection, "getReady"))
        assertEquals(setOf(protected), selected(partialSelection, "getPending"))
        val blankRegions = Grouping.group(listOf(line(40f, 40f, 240f, 80f, "")))
        val blankSelection = selection(blankRegions)

        @Suppress("UNCHECKED_CAST")
        val blankCandidates = selected(blankSelection, "getReady") as List<TextRegion>
        assertTrue(blankCandidates.isEmpty())
        assertEquals("All-blank OCR must remain pending", blankRegions.toSet(), selected(blankSelection, "getPending"))
        val page = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).apply {
                val ink = Paint().apply { color = Color.BLACK }
                drawRect(60f, 50f, 210f, 65f, ink)
                drawRect(60f, 95f, 210f, 110f, ink)
                drawRect(370f, 360f, 520f, 375f, ink)
            }
        }
        val mask = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        try {
            Inpainter(model("mit_aot_fixed512"), InpainterConfig(method = "boxfill")).use { inpainter ->
                val removal = inpainter.buildMask(page, regions, mask)
                try {
                    assertEquals(Color.BLACK, removal.getPixel(100, 100))
                    assertEquals(Color.WHITE, removal.getPixel(400, 370))
                } finally {
                    removal.recycle()
                }
                val cleaned = inpainter.inpaint(page, regions, mask)
                try {
                    val rendered = Renderer.render(
                        cleaned,
                        regions,
                        RenderConfig(orientation = TextOrientation.HORIZONTAL),
                    )
                    try {
                        val before = IntArray(200 * 85)
                        val after = IntArray(before.size)
                        page.getPixels(before, 0, 200, 40, 40, 200, 85)
                        rendered.getPixels(after, 0, 200, 40, 40, 200, 85)
                        assertArrayEquals("Recognized and unread lines must both survive", before, after)
                        assertFalse("The independent complete region should still be translated", page.sameAs(rendered))
                    } finally {
                        rendered.recycle()
                    }
                } finally {
                    cleaned.recycle()
                }
            }
        } finally {
            page.recycle()
            mask.recycle()
        }
    }

    /** Private, test-owned service: no singleton injection or preference mutation. */
    private fun isolatedService(engine: TranslationEngine): TranslationEngineService {
        val service = TranslationEngineService(context)
        val preferences = Injekt.get<TranslationPreferences>().snapshot()
        val signature = TranslationEngineService::class.java.getDeclaredMethod(
            "configSignature",
            String::class.java,
            TranslationPreferences::class.java,
        ).apply { isAccessible = true }.invoke(service, "boxfill", preferences)
        fun field(name: String) = TranslationEngineService::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }
        field("engine").set(service, engine)
        field("builtSignature").set(service, signature)
        field("warmedUp").setBoolean(service, true)
        return service
    }

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float, text: String) = TextLine(
        listOf(Pt(x0, y0), Pt(x1, y0), Pt(x1, y1), Pt(x0, y1)),
        1f,
    ).apply { this.text = text }
}
