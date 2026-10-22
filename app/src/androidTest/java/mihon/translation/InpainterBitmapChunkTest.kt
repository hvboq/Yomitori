package mihon.translation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.InpainterConfig
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextRegion
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InpainterBitmapChunkTest {
    @Test
    fun compositeMatchesPreviousRowLoopAcrossChunkBoundaries() = withInpainter { inpainter ->
        val composite = Inpainter::class.java.getDeclaredMethod(
            "compositePixels",
            Bitmap::class.java,
            IntArray::class.java,
            Bitmap::class.java,
        ).apply { isAccessible = true }
        for (height in listOf(1, 63, 64, 65, 1600)) {
            val width = 73
            val sourcePixels = IntArray(width * height) { Color.rgb(it % 256, (it * 7) % 256, (it * 13) % 256) }
            val replacementPixels = IntArray(sourcePixels.size) {
                Color.rgb(
                    (it * 19) % 256,
                    (it * 3) % 256,
                    255 - it % 256,
                )
            }
            val original = Bitmap.createBitmap(sourcePixels, width, height, Bitmap.Config.ARGB_8888)
            val reconstructed = Bitmap.createBitmap(replacementPixels, width, height, Bitmap.Config.ARGB_8888)
            try {
                for (kind in 0..2) {
                    val mask = IntArray(sourcePixels.size) { i ->
                        when (kind) {
                            0 -> Color.BLACK
                            1 -> Color.WHITE
                            else -> Color.rgb(0, 0, (i * 31 + i / width * 17) % 256)
                        }
                    }
                    val expected = original.copy(Bitmap.Config.ARGB_8888, true)
                    val actual = original.copy(Bitmap.Config.ARGB_8888, true)
                    try {
                        val row = IntArray(width)
                        val replacement = IntArray(width)
                        for (y in 0 until height) {
                            expected.getPixels(row, 0, width, 0, y, width, 1)
                            reconstructed.getPixels(replacement, 0, width, 0, y, width, 1)
                            for (x in 0 until width) if ((mask[y * width + x] and 255) > 127) row[x] = replacement[x]
                            expected.setPixels(row, 0, width, 0, y, width, 1)
                        }
                        composite.invoke(inpainter, actual, mask, reconstructed)
                        assertArrayEquals("height=$height mask=$kind", expected.pixels(), actual.pixels())
                        assertArrayEquals(sourcePixels, original.pixels())
                        assertArrayEquals(replacementPixels, reconstructed.pixels())
                    } finally {
                        expected.recycle()
                        actual.recycle()
                    }
                }
            } finally {
                original.recycle()
                reconstructed.recycle()
            }
        }
    }

    @Test
    fun segmentationChunksMatchRowReadAndDilationIncludingPartialFinalChunk() = withInpainter { inpainter ->
        for (height in listOf(1, 63, 64, 65, 1600)) {
            val width = 73
            // Sparse and irregular ink includes pixels immediately before/after each chunk boundary.
            val source = IntArray(width * height) { i ->
                val y = i / width
                val x = i % width
                Color.rgb(0, 0, if ((x * 13 + y * 7) % 41 == 0 || (x == 36 && y % 64 in 0..1)) 255 else 127)
            }
            val segmentation = Bitmap.createBitmap(source, width, height, Bitmap.Config.ARGB_8888)
            val page = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val regions = listOf(
                    TextRegion(
                        listOf(
                            TextLine(
                                listOf(
                                    Pt(0f, 0f),
                                    Pt(width.toFloat(), 0f),
                                    Pt(width.toFloat(), height.toFloat()),
                                    Pt(0f, height.toFloat()),
                                ),
                                1f,
                            ),
                        ),
                        "h",
                    ),
                )
                val row = IntArray(width)
                val binary = BooleanArray(source.size)
                for (y in 0 until height) {
                    segmentation.getPixels(row, 0, width, 0, y, width, 1)
                    for (x in 0 until width) binary[y * width + x] = (row[x] and 255) > 127
                }
                val expected = IntArray(source.size) { i ->
                    val x = i % width
                    val y = i / width
                    val on = (maxOf(0, y - 1)..minOf(height - 1, y + 1)).any { yy ->
                        (maxOf(0, x - 1)..minOf(width - 1, x + 1)).any { xx -> binary[yy * width + xx] }
                    }
                    if (on) Color.WHITE else Color.BLACK
                }
                val actual = inpainter.buildMask(page, regions, segmentation)
                try {
                    assertArrayEquals("height=$height", expected, actual.pixels())
                    assertArrayEquals(source, segmentation.pixels())
                } finally {
                    actual.recycle()
                }
            } finally {
                page.recycle()
                segmentation.recycle()
            }
        }
    }

    private fun Bitmap.pixels() = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun withInpainter(block: (Inpainter) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "inpainter-chunk-test-${System.nanoTime()}")
        check(directory.mkdir())
        try {
            // These tests use only Bitmap operations; the tiny valid model satisfies construction.
            val param = File(directory, "relu.param").apply {
                writeText("7767517\n2 2\nInput input 0 1 in0\nReLU relu 1 1 in0 out0 0=0\n")
            }
            File(directory, "relu.bin").writeBytes(byteArrayOf())
            Inpainter(param.path, InpainterConfig(method = "boxfill", maskDilate = 2f, bboxPad = 0)).use(block)
        } finally {
            directory.deleteRecursively()
        }
    }
}
