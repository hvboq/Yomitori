package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.WriteBackFormat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Public chapter recovery with private files only; no native engine, models, preferences or server. */
@RunWith(AndroidJUnit4::class)
class OriginalChapterRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test(timeout = 30_000)
    fun chapterOriginalOnlyRecoveryRestoresMixedFormatsWithoutCreatingCompletion() = runBlocking {
        withChapter { fixture ->
            val before = snapshot(fixture.directory)
            val progress = mutableListOf<Pair<Int, Int>>()
            val processed = fixture.translator.reRenderChapter(
                fixture.chapter,
                PageTranslator.ORIGINAL_METHOD,
                onProgress = { done, total -> progress += done to total },
            )

            assertEquals("Every valid preserved original is eligible without optional OCR JSON", 3, processed)
            assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
            assertRecovered(fixture)
            assertOnlyPagesChanged(fixture, before, RECOVERABLE)
        }
    }

    @Test(timeout = 30_000)
    fun chapterOriginalOnlyRecoveryStopsAtPageBoundaryAndCanRetryDeterministically() = runBlocking {
        withChapter { fixture ->
            val before = snapshot(fixture.directory)
            val interruptedProgress = mutableListOf<Pair<Int, Int>>()
            var stop = false
            val interrupted = fixture.translator.reRenderChapter(
                fixture.chapter,
                PageTranslator.ORIGINAL_METHOD,
                onProgress = { done, total ->
                    interruptedProgress += done to total
                    if (done == 1) stop = true
                },
                shouldStop = { stop },
            )

            assertEquals("A cooperative stop takes effect before the next page", 1, interrupted)
            assertEquals(listOf(0 to 3, 1 to 3), interruptedProgress)
            assertArrayEquals(fixture.sources.getValue("001.png"), File(fixture.directory, "001.png").readBytes())
            assertOnlyPagesChanged(fixture, before, setOf("001.png"))

            val resumedProgress = mutableListOf<Pair<Int, Int>>()
            val resumed = fixture.translator.reRenderChapter(
                fixture.chapter,
                PageTranslator.ORIGINAL_METHOD,
                onProgress = { done, total -> resumedProgress += done to total },
            )
            assertEquals("Already restored originals remain safe to process on retry", 3, resumed)
            assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), resumedProgress)
            assertRecovered(fixture)
            assertOnlyPagesChanged(fixture, before, RECOVERABLE)

            val recovered = snapshot(fixture.directory)
            val repeatedProgress = mutableListOf<Pair<Int, Int>>()
            assertEquals(
                3,
                fixture.translator.reRenderChapter(
                    fixture.chapter,
                    PageTranslator.ORIGINAL_METHOD,
                    onProgress = { done, total -> repeatedProgress += done to total },
                ),
            )
            assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), repeatedProgress)
            assertSnapshotEquals("Repeated recovery must be byte deterministic", recovered, snapshot(fixture.directory))
        }
    }

    private suspend fun withChapter(block: suspend (Fixture) -> Unit) {
        check(context.packageName == "app.yomitori.yakuyomi.audit") {
            "Original chapter recovery requires the isolated audit installation"
        }
        val name = "original-chapter-recovery-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, name)
        check(directory.mkdir())
        check(directory.canonicalFile == File(context.cacheDir.canonicalFile, name))
        try {
            val materials = File(directory, ".yakuyomi").apply { check(mkdir()) }
            val sources = linkedMapOf(
                "001.png" to encoded(Color.WHITE, Bitmap.CompressFormat.PNG),
                "002.webp" to encodedWebp(Color.LTGRAY),
                "003.jpg" to encodedWebp(Color.WHITE),
            )
            File(materials, "001.orig.png").writeBytes(sources.getValue("001.png"))
            File(materials, "002.orig.webp").writeBytes(sources.getValue("002.webp"))
            File(materials, "003.orig.webp").writeBytes(sources.getValue("003.jpg"))
            File(directory, "001.png").writeBytes(encoded(Color.BLACK, Bitmap.CompressFormat.PNG))
            File(directory, "002.webp").writeBytes(encodedWebp(Color.BLACK))
            File(directory, "003.jpg").writeBytes(encoded(Color.BLACK, Bitmap.CompressFormat.JPEG))
            File(directory, "004.png").writeBytes(encoded(Color.BLUE, Bitmap.CompressFormat.PNG))
            File(directory, "chapter-metadata.json").writeText("{\"fixture\":\"neutral chapter metadata\"}")
            File(materials, "unrelated.txt").writeText("neutral unrelated material")
            File(materials, "orphan.orig.png").writeBytes(encoded(Color.GREEN, Bitmap.CompressFormat.PNG))
            File(materials, "orphan.json").writeText("{\"fixture\":\"no corresponding page\"}")
            File(directory, "nested").apply { check(mkdir()) }
                .resolve("005.png").writeBytes(encoded(Color.BLUE, Bitmap.CompressFormat.PNG))
            val chapter = requireNotNull(UniFile.fromFile(directory))
            val translator = PageTranslator(context)
            for (page in RECOVERABLE + "004.png") {
                assertFalse(
                    "No optional OCR material was prepared: $page",
                    translator.hasReRenderMaterials(chapter, page),
                )
                assertFalse("No completed page was prepared: $page", translator.isPageTranslated(chapter, page))
            }
            block(Fixture(directory, chapter, translator, sources))
        } finally {
            check(directory.canonicalFile == File(context.cacheDir.canonicalFile, name))
            check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
            check(directory.deleteRecursively())
            check(!directory.exists())
        }
    }

    private fun assertRecovered(fixture: Fixture) {
        val png = File(fixture.directory, "001.png").readBytes()
        val webp = File(fixture.directory, "002.webp").readBytes()
        val jpeg = File(fixture.directory, "003.jpg").readBytes()
        assertArrayEquals(
            "Exact PNG source must be copied without re-encoding",
            fixture.sources.getValue("001.png"),
            png,
        )
        assertArrayEquals(
            "Exact WebP source must be copied without re-encoding",
            fixture.sources.getValue("002.webp"),
            webp,
        )
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a),
            png.copyOfRange(0, 8),
        )
        assertEquals("RIFF", webp.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WEBP", webp.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertArrayEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte()), jpeg.copyOfRange(0, 3))
        assertArrayEquals(
            "A legacy WebP source for a JPEG page must be encoded as JPEG",
            legacyJpeg(fixture.sources.getValue("003.jpg")),
            jpeg,
        )
        assertSolidPage(File(fixture.directory, "001.png"), Color.WHITE)
        assertSolidPage(File(fixture.directory, "002.webp"), Color.LTGRAY)
        assertSolidPage(File(fixture.directory, "003.jpg"), Color.WHITE)
        for (page in RECOVERABLE + "004.png") {
            assertFalse(
                "Restoration does not invent completed translation state: $page",
                fixture.translator.isPageTranslated(fixture.chapter, page),
            )
            assertFalse(
                "Restoration does not invent OCR materials: $page",
                fixture.translator.hasReRenderMaterials(fixture.chapter, page),
            )
        }
        assertFalse(File(fixture.directory, ".yakuyomi/003.orig.jpg").exists())
    }

    private fun assertOnlyPagesChanged(fixture: Fixture, before: Map<String, ByteArray>, allowed: Set<String>) {
        val after = snapshot(fixture.directory)
        assertEquals("Recovery must not create a manifest, journal, OCR JSON or another file", before.keys, after.keys)
        for ((name, bytes) in before) {
            if (name !in allowed) {
                assertArrayEquals("Preserved source or unrelated file changed: $name", bytes, after.getValue(name))
            }
        }
        assertFalse("Original-less pages must be excluded", "004.png" in allowed)
        assertArrayEquals(before.getValue("004.png"), after.getValue("004.png"))
    }

    private fun assertSnapshotEquals(
        message: String,
        expected: Map<String, ByteArray>,
        actual: Map<String, ByteArray>,
    ) {
        assertEquals(message, expected.keys, actual.keys)
        for ((name, bytes) in expected) assertArrayEquals("$message: $name", bytes, actual.getValue(name))
    }

    private fun snapshot(directory: File): Map<String, ByteArray> = directory.walkTopDown()
        .filter { it.isFile }
        .associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes() }

    private fun assertSolidPage(file: File, color: Int) {
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
        try {
            assertEquals(SIZE, bitmap.width)
            assertEquals(SIZE, bitmap.height)
            val pixels = IntArray(SIZE * SIZE)
            bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            assertTrue(
                "Every restored pixel must come from the preserved source: ${file.name}",
                pixels.all { it == color },
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun legacyJpeg(source: ByteArray): ByteArray {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(source, 0, source.size))
        return try {
            ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, WriteBackFormat.QUALITY, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun encodedWebp(color: Int): ByteArray = encoded(color, Bitmap.CompressFormat.WEBP)

    private fun encoded(color: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return try {
            ByteArrayOutputStream().use {
                check(bitmap.compress(format, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private data class Fixture(
        val directory: File,
        val chapter: UniFile,
        val translator: PageTranslator,
        val sources: Map<String, ByteArray>,
    )

    private companion object {
        const val SIZE = 32
        val RECOVERABLE = setOf("001.png", "002.webp", "003.jpg")
    }
}
