package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import eu.kanade.tachiyomi.data.translation.WriteBackFormat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException

/** Actual private codec, independent integer white-paper oracle; no model, server or storage write. */
@RunWith(AndroidJUnit4::class)
class AlphaWriteBackTest {
    @Test
    fun jpegFallbackCompositesTransparencyOverWhiteWithoutMutatingCaller() = codec { translator ->
        val cases = JSONArray()
        for (density in intArrayOf(0, 160, 241, runtimeDensity()).distinct()) {
            withImage(alpha = true, density = density) { source ->
                val expectedBitmap = whiteFromStraightPixels(source)
                val expected = try {
                    compress(expectedBitmap, Bitmap.CompressFormat.JPEG)
                } finally {
                    expectedBitmap.recycle()
                }
                for (name in listOf("001.avif", "001.AVIF", "001.jpg", "001.other", null)) {
                    val encoded = encode(translator, name, source)
                    assertJpeg(encoded)
                    val matches = expected.contentEquals(encoded)
                    cases.put(
                        JSONObject().put("name", name ?: "no-name").put("density", density)
                            .put("exactWhiteJpeg", matches)
                            .put("expectedSha256", PartialPageStore.sha256(expected))
                            .put("actualSha256", PartialPageStore.sha256(encoded)),
                    )
                    evidence("jpeg-alpha", cases)
                    assertTrue("JPEG fallback must encode white-composited physical pixels", matches)
                    decoded(encoded) { image ->
                        val corner = image.getPixel(2, 2)
                        assertTrue("Transparent background must be white in opaque JPEG", isWhite(corner))
                    }
                }
            }
        }
    }

    @Test
    fun opaqueJpegFallbackKeepsLegacyBytesAndCallerMetadata() = codec { translator ->
        for (density in intArrayOf(0, 160, 241, runtimeDensity()).distinct()) {
            withImage(alpha = false, density = density) { source ->
                // An alpha channel flag alone must not cause another full-size image or a pixel change.
                assertTrue(source.hasAlpha())
                val expected = compress(source, Bitmap.CompressFormat.JPEG)
                for (name in listOf("001.avif", "001.jpg", null)) {
                    val actual = encode(translator, name, source)
                    assertJpeg(actual)
                    assertArrayEquals("Opaque JPEG must retain the existing quality and bytes", expected, actual)
                }
            }
        }
    }

    @Test
    fun pngAndWebpKeepTransparencyAndCallerPixels() = codec { translator ->
        withImage(alpha = true, density = 241) { source ->
            val png = encode(translator, "001.png", source)
            assertArrayEquals(PNG, png.copyOfRange(0, PNG.size))
            decoded(png) { image ->
                assertTrue(image.hasAlpha())
                assertArrayEquals("PNG preserves actual RGBA pixels", pixels(source), pixels(image))
            }
            val webp = encode(translator, "001.webp", source)
            assertEquals("RIFF", webp.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals("WEBP", webp.copyOfRange(8, 12).toString(Charsets.US_ASCII))
            decoded(webp) { image ->
                assertTrue(image.hasAlpha())
                val before = pixels(source)
                val after = pixels(image)
                assertTrue(before.any { Color.alpha(it) == 0 })
                assertTrue(before.any { Color.alpha(it) == 128 })
                for (index in before.indices) {
                    // WebP q92 may alter RGB; its separate alpha plane must remain exact.
                    assertEquals(Color.alpha(before[index]), Color.alpha(after[index]))
                }
            }
        }
    }

    private inline fun codec(block: (PageTranslator) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit required" }
        NativeDiagnosticIdleGuard.requireIdle(target)
        try {
            // encodePage/isOpaque do not read these service references or write statistics.
            block(PageTranslator(target))
        } finally {
            NativeDiagnosticIdleGuard.requireIdle(target)
        }
    }

    private fun encode(translator: PageTranslator, name: String?, source: Bitmap): ByteArray = try {
        PageTranslator::class.java.getDeclaredMethod("encodePage", String::class.java, Bitmap::class.java)
            .apply { isAccessible = true }.invoke(translator, name, source) as ByteArray
    } catch (failure: InvocationTargetException) {
        throw checkNotNull(failure.cause)
    }

    private inline fun withImage(alpha: Boolean, density: Int, block: (Bitmap) -> Unit) {
        val source = image(alpha).apply { this.density = density }
        val before = Guard(source)
        try {
            block(source)
        } finally {
            try {
                before.verify(source)
            } finally {
                source.recycle()
            }
        }
    }

    private class Guard(source: Bitmap) {
        private val argb = pixels(source)
        private val png = compress(source, Bitmap.CompressFormat.PNG)
        private val width = source.width
        private val height = source.height
        private val config = source.config
        private val density = source.density
        private val alpha = source.hasAlpha()
        private val premultiplied = source.isPremultiplied

        fun verify(source: Bitmap) {
            assertFalse("The codec borrows the caller bitmap", source.isRecycled)
            assertEquals(width, source.width)
            assertEquals(height, source.height)
            assertEquals(config, source.config)
            assertEquals(density, source.density)
            assertEquals(alpha, source.hasAlpha())
            assertEquals(premultiplied, source.isPremultiplied)
            assertArrayEquals(argb, pixels(source))
            assertArrayEquals(png, compress(source, Bitmap.CompressFormat.PNG))
        }
    }

    private fun evidence(stage: String, cases: JSONArray) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString(
                    "alphaWriteBackEvidence",
                    JSONObject().put("stage", stage).put("cases", cases)
                        .put("nativeApiCallsByTest", 0).put("modelDownloadsByTest", 0)
                        .put("networkCallsByTest", 0).put("imageFilesWrittenByTest", 0)
                        .put("settingsWritesByTest", 0).toString(),
                )
            },
        )
    }

    private companion object {
        const val WIDTH = 64
        const val HEIGHT = 48
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)

        fun runtimeDensity(): Int = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).let {
            try {
                it.density
            } finally {
                it.recycle()
            }
        }

        fun image(alpha: Boolean): Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
            eraseColor(if (alpha) Color.TRANSPARENT else Color.WHITE)
            val values = pixels(this)
            for (y in 12 until 36) for (x in 16 until 48) values[y * WIDTH + x] = Color.BLACK
            for (y in 4 until 10) {
                for (x in 40 until 56) {
                    values[y * WIDTH + x] = if (alpha) Color.argb(128, 255, 0, 0) else Color.RED
                }
            }
            setPixels(values, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            // Keep the metadata flag for the genuinely opaque JPEG control too.
            setHasAlpha(true)
        }

        /** Endpoint colors make this independent integer SrcOver oracle exact, without Canvas. */
        fun whiteFromStraightPixels(source: Bitmap): Bitmap {
            val values = pixels(source)
            for (index in values.indices) {
                val pixel = values[index]
                val alpha = Color.alpha(pixel)
                fun overWhite(channel: Int) = (channel * alpha + 255 * (255 - alpha) + 127) / 255
                values[index] =
                    Color.rgb(overWhite(Color.red(pixel)), overWhite(Color.green(pixel)), overWhite(Color.blue(pixel)))
            }
            return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).apply {
                density = source.density
                setPixels(values, 0, width, 0, 0, width, height)
                setHasAlpha(false)
            }
        }

        fun pixels(source: Bitmap): IntArray = IntArray(source.width * source.height).also {
            source.getPixels(it, 0, source.width, 0, 0, source.width, source.height)
        }

        fun compress(source: Bitmap, format: Bitmap.CompressFormat): ByteArray = ByteArrayOutputStream().use {
            check(source.compress(format, WriteBackFormat.QUALITY, it))
            it.toByteArray()
        }

        inline fun decoded(bytes: ByteArray, block: (Bitmap) -> Unit) {
            val bitmap = checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try {
                assertEquals(WIDTH, bitmap.width)
                assertEquals(HEIGHT, bitmap.height)
                block(bitmap)
            } finally {
                bitmap.recycle()
            }
        }

        fun assertJpeg(bytes: ByteArray) = assertArrayEquals(
            byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()),
            bytes.copyOfRange(0, 3),
        )

        fun isWhite(color: Int): Boolean =
            Color.red(color) >= 250 && Color.green(color) >= 250 && Color.blue(color) >= 250
    }
}
