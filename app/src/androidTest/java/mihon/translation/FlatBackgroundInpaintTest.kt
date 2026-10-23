package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineTrace
import li.joye.yakuyomi.engine.Grouping
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.InpainterConfig
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextRegion
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Actual DBNet/AOT models and removal pixels; no translation server or source artwork. */
@RunWith(AndroidJUnit4::class)
class FlatBackgroundInpaintTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun model(name: String) = File(context.filesDir, "models/$name.ncnn.param").path

    @Test
    fun detectedWhiteBubblesStayWhiteAndPersistedSegmentationReRendersWithoutAot() = runBlocking<Unit> {
        Injekt.get<TranslationEngineService>().shutdown()
        val page = neutralPage()
        val before = page.pixels()
        val output = File(context.getExternalFilesDir(null), "flat-inpaint-white").apply { mkdirs() }
        try {
            Detector(model("dbnet_detect")).use { detector ->
                val detection = detector.detect(page)
                val tightBefore = detection.textMask.pixels()
                try {
                    val regions = Grouping.group(detection.lines)
                    assertEquals("Use the same four-region fixture that exposed the release artifact", 4, regions.size)
                    Inpainter(model("mit_aot_fixed512"), InpainterConfig(method = "aot")).use { inpainter ->
                        val removal = inpainter.buildMask(page, regions, detection.textMask)
                        try {
                            val (cleaned, calls) = traceInpaint { inpainter.inpaint(page, regions, detection.textMask) }
                            try {
                                assertEquals("A confidently flat page must not enter AOT", 0, calls)
                                assertTrue(regions.all { !it.onArt })
                                assertWhiteRemovalAndUntouchedContext(before, removal.pixels(), cleaned)
                                assertEquals(
                                    "Monochrome input must not acquire reconstruction chroma",
                                    0,
                                    chroma(cleaned),
                                )
                                save(page, File(output, "source.png"))
                                save(cleaned, File(output, "cleaned.png"))
                                val maskFile = File(output, "segmentation.png")
                                save(detection.textMask, maskFile)
                                val persisted = checkNotNull(BitmapFactory.decodeFile(maskFile.path))
                                try {
                                    // Older materials record onArt=true. Classification must be
                                    // recomputed from the immutable original and saved tight mask.
                                    regions.forEach { it.onArt = true }
                                    val (again, repeatCalls) = traceInpaint {
                                        inpainter.inpaint(page, regions, persisted)
                                    }
                                    try {
                                        assertEquals(0, repeatCalls)
                                        assertTrue(regions.all { !it.onArt })
                                        assertArrayEquals(cleaned.pixels(), again.pixels())
                                    } finally {
                                        again.recycle()
                                    }
                                } finally {
                                    persisted.recycle()
                                }
                                File(output, "result.json").writeText(
                                    JSONObject().put("regions", regions.size).put("aotCalls", calls)
                                        .put("introducedChromaPixels", chroma(cleaned))
                                        .put("savedSegmentationRepeatMatches", true).toString(2),
                                )
                            } finally {
                                cleaned.recycle()
                            }
                        } finally {
                            removal.recycle()
                        }
                    }
                    assertArrayEquals(tightBefore, detection.textMask.pixels())
                    assertArrayEquals(before, page.pixels())
                } finally {
                    detection.textMask.recycle()
                }
            }
        } finally {
            page.recycle()
        }
    }

    @Test
    fun horizontalGlyphsWithPaddedBoundsAtPageEdgeUseTheirSupportedWhiteBackground() = runBlocking<Unit> {
        check(context.packageName == "app.yomitori.yakuyomi.audit")
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS)
        Injekt.get<TranslationEngineService>().shutdown()
        val page = neutralAsset("flat-near-left-source.png", SOURCE_SHA)
        var segmentationToRecycle: Bitmap? = null
        try {
            val segmentation = neutralAsset("flat-near-left-segmentation.png", SEGMENTATION_SHA).also {
                segmentationToRecycle = it
            }
            val output = File(context.getExternalFilesDir(null), "flat-inpaint-left-margin-${UUID.randomUUID()}")
            check(output.mkdir())
            println("FLAT_LEFT_MARGIN_REPORT=${File(output, "result.json").absolutePath}")
            assertEquals(1000, page.width)
            assertEquals(160, page.height)
            assertEquals(page.width, segmentation.width)
            assertEquals(page.height, segmentation.height)
            val before = page.pixels()
            val tightBefore = segmentation.pixels()
            // Every frozen seg pixel is inside the original bbox+16 allow rectangle. A direct
            // radius12 square expansion is therefore an independent oracle for the public mask.
            val expectedMask = referenceDilation(tightBefore, page.width, page.height, 12)
            val active = expectedMask.indices.filter { (expectedMask[it] and 255) > 127 }
            assertArrayEquals(
                intArrayOf(9, 50, 486, 128),
                intArrayOf(
                    active.minOf { it % page.width },
                    active.minOf { it / page.width },
                    active.maxOf { it % page.width } + 1,
                    active.maxOf { it / page.width } + 1,
                ),
            )
            val firstRegion = frozenHorizontalRegion()
            Inpainter(model("mit_aot_fixed512"), InpainterConfig(method = "aot")).use { inpainter ->
                val removal = inpainter.buildMask(page, listOf(firstRegion), segmentation)
                try {
                    assertArrayEquals(
                        "The existing dilation/allow contract must not change",
                        expectedMask,
                        removal.pixels(),
                    )
                    val (cleaned, calls) = traceInpaint { inpainter.inpaint(page, listOf(firstRegion), segmentation) }
                    try {
                        save(page, File(output, "source.png"))
                        save(removal, File(output, "removal.png"))
                        save(cleaned, File(output, "cleaned.png"))
                        val savedSeg = File(output, "segmentation.png")
                        save(segmentation, savedSeg)
                        val persisted = checkNotNull(BitmapFactory.decodeFile(savedSeg.path))
                        try {
                            assertArrayEquals(tightBefore, persisted.pixels())
                            val savedRegion = frozenHorizontalRegion() // Saved onArt=true is only a stale hint.
                            val (again, repeatCalls) = traceInpaint {
                                inpainter.inpaint(page, listOf(savedRegion), persisted)
                            }
                            try {
                                save(again, File(output, "saved-seg-cleaned.png"))
                                File(output, "result.json").writeText(
                                    JSONObject().put("sourceSha256", SOURCE_SHA)
                                        .put("segmentationSha256", SEGMENTATION_SHA)
                                        .put("aotCalls", calls).put("repeatAotCalls", repeatCalls)
                                        .put("onArt", firstRegion.onArt).put("repeatOnArt", savedRegion.onArt)
                                        .put("expectedRemovalPixels", active.size).toString(2),
                                )
                                assertArrayEquals("Original pixels are borrowed", before, page.pixels())
                                assertArrayEquals("Segmentation is borrowed", tightBefore, segmentation.pixels())
                                assertArrayEquals("Saved segmentation is borrowed", tightBefore, persisted.pixels())
                                assertEquals("Supported white source must avoid reconstruction", 0, calls)
                                assertEquals("Saved segmentation must use the same flat route", 0, repeatCalls)
                                assertFalse(firstRegion.onArt)
                                assertFalse(savedRegion.onArt)
                                assertWhiteRemovalAndUntouchedContext(before, expectedMask, cleaned)
                                assertArrayEquals("Saved-seg re-render must match", cleaned.pixels(), again.pixels())
                                assertEquals(0, chroma(cleaned))
                            } finally {
                                again.recycle()
                            }
                        } finally {
                            persisted.recycle()
                        }
                    } finally {
                        cleaned.recycle()
                    }
                } finally {
                    removal.recycle()
                }
            }
        } finally {
            page.recycle()
            segmentationToRecycle?.recycle()
        }
    }

    @Test
    fun mixedWhiteBubbleAndColoredTextureKeepSeparateRoutesAndProtectedPixels() = runBlocking<Unit> {
        Injekt.get<TranslationEngineService>().shutdown()
        val page = Bitmap.createBitmap(768, 512, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            // Almost equal luminance, clearly different RGB: luminance-only std cannot detect it.
            for (y in 100 until 340) {
                for (x in 400 until 755) {
                    setPixel(x, y, if ((x / 8 + y / 8) % 2 == 0) Color.rgb(128, 128, 128) else Color.rgb(143, 120, 128))
                }
            }
        }
        val flat = region(60f, 170f, 335f, 235f)
        val art = region(435f, 170f, 720f, 235f)
        val protected = region(560f, 205f, 650f, 245f).apply { preserveOriginal = true }
        val mask = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLACK)
        }
        val paint = Paint().apply { color = Color.BLACK }
        Canvas(page).apply {
            drawRect(105f, 185f, 290f, 215f, paint)
            drawRect(475f, 185f, 680f, 225f, paint)
        }
        paint.color = Color.WHITE
        Canvas(mask).apply {
            drawRect(105f, 185f, 290f, 215f, paint)
            drawRect(475f, 185f, 680f, 225f, paint)
        }
        val before = page.pixels()
        val tightBefore = mask.pixels()
        try {
            Inpainter(model("mit_aot_fixed512"), InpainterConfig(method = "aot", tileSize = 256)).use { inpainter ->
                val removal = inpainter.buildMask(page, listOf(flat, art, protected), mask)
                try {
                    val pixels = removal.pixels()
                    val (cleaned, calls) = traceInpaint { inpainter.inpaint(page, listOf(flat, art, protected), mask) }
                    try {
                        assertFalse("White balloon should be filled from its own background", flat.onArt)
                        assertTrue("RGB texture must keep reconstruction despite similar luminance", art.onArt)
                        assertEquals("One whole-page AOT pass remains for uncertain art", 1, calls)
                        var flatPixels = 0
                        val after = cleaned.pixels()
                        for (y in 0 until page.height) {
                            for (x in 0 until page.width) {
                                val i = y * page.width + x
                                if ((pixels[i] and 255) <= 127) {
                                    if (before[i] != after[i]) {
                                        assertEquals("Outside removal mask changed at $x,$y", before[i], after[i])
                                    }
                                } else if (x < 380) {
                                    flatPixels++
                                    assertEquals("Flat region was contaminated by AOT", Color.WHITE, after[i])
                                }
                            }
                        }
                        assertTrue(flatPixels > 1000)
                        for (y in 205 until 245) {
                            for (x in 560 until 650) {
                                val i = y * page.width + x
                                assertEquals("Protected lettering changed", before[i], after[i])
                            }
                        }
                        val output = File(context.getExternalFilesDir(null), "flat-inpaint-mixed").apply { mkdirs() }
                        save(page, File(output, "source.png"))
                        save(cleaned, File(output, "cleaned.png"))
                        File(output, "result.json").writeText(
                            JSONObject().put("flatPixels", flatPixels).put("aotCalls", calls)
                                .put("flatOnArt", flat.onArt).put("texturedOnArt", art.onArt)
                                .put("protectedUnchanged", true).toString(2),
                        )
                    } finally {
                        cleaned.recycle()
                    }
                } finally {
                    removal.recycle()
                }
            }
            assertArrayEquals(before, page.pixels())
            assertArrayEquals(tightBefore, mask.pixels())
        } finally {
            mask.recycle()
            page.recycle()
        }
    }

    @Test
    fun clippedOrCompletelyMaskedBackgroundCannotDefaultToWhite() = runBlocking<Unit> {
        Injekt.get<TranslationEngineService>().shutdown()
        val page = Bitmap.createBitmap(512, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val mask = Bitmap.createBitmap(512, 320, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        val clipped = region(0f, 60f, 150f, 130f)
        val covered = region(280f, 100f, 440f, 180f)
        val paint = Paint().apply { color = Color.BLACK }
        Canvas(page).apply {
            drawRect(0f, 75f, 120f, 100f, paint)
            drawRect(240f, 60f, 480f, 220f, paint)
        }
        paint.color = Color.WHITE
        Canvas(mask).apply {
            drawRect(0f, 75f, 120f, 100f, paint)
            drawRect(240f, 60f, 480f, 220f, paint)
        }
        val before = page.pixels()
        try {
            Inpainter(model("mit_aot_fixed512"), InpainterConfig(method = "aot", tileSize = 256)).use { inpainter ->
                val (cleaned, calls) = traceInpaint { inpainter.inpaint(page, listOf(clipped, covered), mask) }
                try {
                    assertTrue("Missing spatial support must stay uncertain", clipped.onArt)
                    assertTrue("No unmasked background is not evidence of white", covered.onArt)
                    assertEquals(1, calls)
                } finally {
                    cleaned.recycle()
                }
            }
            assertArrayEquals(before, page.pixels())
        } finally {
            page.recycle()
            mask.recycle()
        }
    }

    private suspend fun traceInpaint(block: suspend () -> Bitmap): Pair<Bitmap, Int> {
        val previous = EngineTrace.sink
        val calls = AtomicInteger()
        return try {
            EngineTrace.sink = { message ->
                if (message.startsWith("ncnn.inpaint.call ")) calls.incrementAndGet()
                previous?.invoke(message)
            }
            block() to calls.get()
        } finally {
            EngineTrace.sink = previous
        }
    }

    private fun neutralAsset(name: String, sha256: String): Bitmap {
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets
            .open("native-quality/$name").use { it.readBytes() }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        assertEquals("Frozen neutral asset changed", sha256, actual)
        return checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    private fun referenceDilation(segmentation: IntArray, width: Int, height: Int, radius: Int): IntArray {
        val expected = IntArray(segmentation.size) { Color.BLACK }
        for (index in segmentation.indices) {
            if ((segmentation[index] and 255) <= 127) continue
            val x = index % width
            val y = index / width
            for (yy in maxOf(0, y - radius)..minOf(height - 1, y + radius)) {
                for (xx in maxOf(0, x - radius)..minOf(width - 1, x + radius)) {
                    expected[yy * width + xx] = Color.WHITE
                }
            }
        }
        return expected
    }

    private fun frozenHorizontalRegion() = TextRegion(
        listOf(
            TextLine(
                listOf(
                    Pt(19.086676f, 61.9297f),
                    Pt(470.29443f, 63.02486f),
                    Pt(470.17395f, 112.65691f),
                    Pt(18.96621f, 111.56175f),
                ),
                1f,
            ).apply {
                direction = "h"
                text = "私はここにいます"
            },
        ),
        "h",
        0f,
        244.63031f,
        87.293304f,
        451.32822f,
        50.727215f,
    ).apply {
        translatedText = "저는 여기 있어요"
        onArt = true
    }

    private fun assertWhiteRemovalAndUntouchedContext(before: IntArray, mask: IntArray, cleaned: Bitmap) {
        val expected = before.clone()
        var replaced = 0
        for (i in mask.indices) {
            if ((mask[i] and 255) > 127) {
                replaced++
                expected[i] = Color.WHITE
            }
        }
        assertTrue("Detector must produce a nonempty removal mask", replaced > 1000)
        assertArrayEquals("Only removal pixels may change, and all must become white", expected, cleaned.pixels())
    }

    private fun chroma(bitmap: Bitmap) = bitmap.pixels().count { pixel ->
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)
        maxOf(r, g, b) - minOf(r, g, b) > 8
    }

    private fun Bitmap.pixels() = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
    private fun save(bitmap: Bitmap, file: File) = file.outputStream().use {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }

    private fun region(x0: Float, y0: Float, x1: Float, y1: Float) = TextRegion(
        listOf(TextLine(listOf(Pt(x0, y0), Pt(x1, y0), Pt(x1, y1), Pt(x0, y1)), 1f)),
        "h",
    )

    private fun neutralPage() = Bitmap.createBitmap(1200, 1600, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        canvas.drawColor(Color.LTGRAY)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        fun bubble(left: Float, top: Float, right: Float, bottom: Float) {
            paint.color = Color.DKGRAY
            canvas.drawRect(left - 8, top - 8, right + 8, bottom + 8, paint)
            paint.color = Color.WHITE
            canvas.drawRoundRect(RectF(left, top, right, bottom), 70f, 70f, paint)
            paint.color = Color.BLACK
        }
        paint.textSize = 60f
        bubble(70f, 120f, 1130f, 650f)
        canvas.drawText("こんにちは。", 200f, 340f, paint)
        canvas.drawText("今日もよろしくお願いします。", 150f, 470f, paint)
        bubble(70f, 850f, 1130f, 1450f)
        canvas.drawText("明日の会議は午後三時です。", 180f, 1080f, paint)
        canvas.drawText("少し待ってください。", 240f, 1210f, paint)
    }

    private companion object {
        const val SOURCE_SHA = "00a1c4e367d9ca33e50944bbefaa039dd1ab39ffd3b9460c944d262cbed05e2d"
        const val SEGMENTATION_SHA = "c7e1a07b0c1a7eda47a904ab77f7b480f42fb96efc8854b97aa7aaba27759b24"
    }
}
