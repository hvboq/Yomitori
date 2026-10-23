package mihon.translation

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.ui.capture.CaptureSaveResult
import eu.kanade.tachiyomi.ui.capture.CaptureSiteSetting
import eu.kanade.tachiyomi.ui.capture.CaptureViewModel
import eu.kanade.tachiyomi.ui.capture.autoTrimBounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Actual capture ViewModel entry with private UUID storage; no Activity, native inference or server. */
@RunWith(AndroidJUnit4::class)
class CaptureRecaptureEntryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test(timeout = 30_000)
    fun recapturingCompletedPageRegistersNewSourceAndRetainsOldSourceAndDisplay(): Unit = runBlocking {
        withFixture { fixture ->
            val materials = File(fixture.chapterDirectory, ".yakuyomi").apply { check(mkdir()) }
            val oldSource = encoded(Color.WHITE)
            val oldDisplay = encoded(Color.BLACK)
            File(materials, "001.orig.png").writeBytes(oldSource)
            File(fixture.chapterDirectory, PAGE).writeBytes(oldDisplay)
            val otherPage = File(fixture.chapterDirectory, "002.png").apply { writeBytes(encoded(Color.GREEN)) }
            val unrelated = File(materials, "unrelated.bin").apply { writeText("neutral unrelated material") }
            val otherBefore = otherPage.readBytes()
            val unrelatedBefore = unrelated.readBytes()
            val completed = CompletionManifestCodec.encode(setOf(PAGE, "002.png"))
            File(fixture.chapterDirectory, CompletionManifestCodec.PRIMARY).writeBytes(completed)
            File(fixture.chapterDirectory, CompletionManifestCodec.CHECKPOINT_PREFIX + UUID.randomUUID())
                .writeBytes(completed)
            val translator = PageTranslator(context)
            assertTrue(translator.isPageTranslated(fixture.chapter, PAGE))
            assertTrue(translator.isPageTranslated(fixture.chapter, "002.png"))
            val originalUri = requireNotNull(UniFile.fromFile(File(fixture.chapterDirectory, PAGE))).uri
            val capture = solidBitmap(Color.BLUE)
            val inputBefore = pixels(capture)
            try {
                val expected = encode(capture)
                val result = fixture.model.saveReCapture(capture, CAPTURE_URL, BOOK, CHAPTER, PAGE)

                assertTrue(
                    "The actual recapture entry must save successfully: $result",
                    result is CaptureSaveResult.Saved,
                )
                assertEquals(originalUri.toString(), (result as CaptureSaveResult.Saved).path)
                val display = File(fixture.chapterDirectory, PAGE).readBytes()
                assertArrayEquals("The capture bytes are the new visible page", expected, display)
                assertArrayEquals(
                    "A previous original must not remain the source of a newly captured page",
                    display,
                    File(materials, "001.orig.png").readBytes(),
                )
                assertFalse(
                    "Old checkpoints must not complete the new source",
                    translator.isPageTranslated(fixture.chapter, PAGE),
                )
                assertTrue(
                    "Another page's completion must survive",
                    translator.isPageTranslated(fixture.chapter, "002.png"),
                )
                assertArrayEquals(otherBefore, otherPage.readBytes())
                assertArrayEquals(unrelatedBefore, unrelated.readBytes())
                val archives = materials.walkTopDown().filter { it.isFile && it.name != "001.orig.png" }.toList()
                assertTrue(
                    "The exact previous OCR source must remain in the private history",
                    archives.any { it.readBytes().contentEquals(oldSource) },
                )
                assertTrue(
                    "The exact previous translated display must remain in the private history",
                    archives.any { it.readBytes().contentEquals(oldDisplay) },
                )
                assertFalse("The capture caller retains its bitmap", capture.isRecycled)
                assertArrayEquals("Capture encoding must not modify its borrowed input", inputBefore, pixels(capture))
                assertEquals(originalUri, fixture.chapter.findFile(PAGE)?.uri)
            } finally {
                if (!capture.isRecycled) capture.recycle()
            }
        }
    }

    @Test(timeout = 30_000)
    fun recapturingMissingPageFailsWithoutCreatingAnImageOrChangingStorage(): Unit = runBlocking {
        withFixture { fixture ->
            File(fixture.chapterDirectory, "002.png").writeBytes(encoded(Color.GREEN))
            val before = snapshot(fixture.directory)
            val capture = solidBitmap(Color.BLUE)
            val inputBefore = pixels(capture)
            try {
                val result = fixture.model.saveReCapture(capture, CAPTURE_URL, BOOK, CHAPTER, PAGE)

                assertTrue(
                    "Recapture must not become a new-page operation: $result",
                    result is CaptureSaveResult.Failed,
                )
                assertFalse(File(fixture.chapterDirectory, PAGE).exists())
                assertSnapshotEquals(before, snapshot(fixture.directory))
                assertFalse("Failed recapture must not recycle its borrowed bitmap", capture.isRecycled)
                assertArrayEquals(inputBefore, pixels(capture))
            } finally {
                if (!capture.isRecycled) capture.recycle()
            }
        }
    }

    @Test(timeout = 30_000)
    fun recaptureAppliesCropThenAutoTrimAndPreservesBorrowedBitmap(): Unit = runBlocking {
        withFixture { fixture ->
            File(fixture.chapterDirectory, PAGE).writeBytes(encoded(Color.BLUE))
            fixture.model.saveSiteSetting(
                CAPTURE_URL,
                CaptureSiteSetting(cropTop = 0.1f, cropBottom = 0.1f, autoTrim = true),
            )
            val capture = Bitmap.createBitmap(SIZE, 160, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
                for (y in 40 until 120) {
                    for (x in 0 until width) setPixel(x, y, if (x % 2 == 0) Color.BLACK else Color.LTGRAY)
                }
            }
            val inputBefore = pixels(capture)
            val cropped = Bitmap.createBitmap(capture, 0, 16, SIZE, 128)
            try {
                val (trimTop, trimBottom) = autoTrimBounds(cropped)
                assertTrue("The neutral fixture exercises top auto-trim", trimTop > 0)
                assertTrue("The neutral fixture exercises bottom auto-trim", trimBottom > 0)
                val expectedHeight = cropped.height - trimTop - trimBottom
                val expectedPixels = IntArray(SIZE * expectedHeight)
                capture.getPixels(expectedPixels, 0, SIZE, 0, 16 + trimTop, SIZE, expectedHeight)
                val result = fixture.model.saveReCapture(capture, CAPTURE_URL, BOOK, CHAPTER, PAGE)

                assertTrue(
                    "Crop and trim must still use the actual recapture entry: $result",
                    result is CaptureSaveResult.Saved,
                )
                val stored = requireNotNull(BitmapFactory.decodeFile(File(fixture.chapterDirectory, PAGE).path))
                try {
                    assertEquals(SIZE, stored.width)
                    assertEquals(expectedHeight, stored.height)
                    assertArrayEquals(
                        "Stored pixels must reflect crop followed by trim",
                        expectedPixels,
                        pixels(stored),
                    )
                } finally {
                    stored.recycle()
                }
                assertFalse("The caller owns the full input bitmap", capture.isRecycled)
                assertArrayEquals("The full uncropped input must retain every pixel", inputBefore, pixels(capture))
            } finally {
                if (cropped !== capture && !cropped.isRecycled) cropped.recycle()
                if (!capture.isRecycled) capture.recycle()
            }
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        check(context.packageName == "app.yomitori.yakuyomi.audit") {
            "Capture recapture entry regressions require the isolated audit installation"
        }
        val name = "capture-recapture-entry-${UUID.randomUUID()}"
        val directory = File(context.cacheDir, name).apply { check(mkdir()) }
        check(directory.canonicalFile == File(context.cacheDir.canonicalFile, name))
        val storagePreferences = StoragePreferences(
            object : FolderProvider {
                override fun directory(): File = directory
                override fun path(): String = Uri.fromFile(directory).toString()
            },
            InMemoryPreferenceStore(),
        )
        val storage = StorageManager(context, storagePreferences)
        val models = ViewModelStore()
        try {
            val application = context.applicationContext as Application
            val model = CaptureViewModel(application, storage, UiPreferences(InMemoryPreferenceStore()))
            models.put("private-capture", model)
            model.saveSiteSetting(CAPTURE_URL, CaptureSiteSetting(autoTrim = false))
            val local = requireNotNull(storage.getLocalSourceDirectory())
            val book = requireNotNull(local.createDirectory(BOOK))
            val chapter = requireNotNull(book.createDirectory(CHAPTER))
            val chapterDirectory = File(directory, "local/$BOOK/$CHAPTER")
            assertEquals(Uri.fromFile(chapterDirectory), chapter.uri)
            assertEquals(Uri.fromFile(directory).toString(), storagePreferences.baseStorageDirectory.get())
            block(Fixture(directory, chapterDirectory, chapter, model))
        } finally {
            try {
                instrumentation.runOnMainSync { models.clear() }
            } finally {
                try {
                    // This is the fixture's own manager and scope, never the application's singleton.
                    val scope = StorageManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
                        .get(storage) as CoroutineScope
                    scope.cancel()
                } finally {
                    check(directory.canonicalFile == File(context.cacheDir.canonicalFile, name))
                    check(directory.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                    check(directory.deleteRecursively())
                    check(!directory.exists())
                }
            }
        }
    }

    private fun solidBitmap(color: Int): Bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        .apply { eraseColor(color) }

    private fun encoded(color: Int): ByteArray = solidBitmap(color).let { bitmap ->
        try {
            encode(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use {
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        it.toByteArray()
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun snapshot(directory: File): Map<String, ByteArray> = directory.walkTopDown()
        .filter { it.isFile }
        .associate { it.relativeTo(directory).invariantSeparatorsPath to it.readBytes() }

    private fun assertSnapshotEquals(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
        assertEquals("Failed recapture must not create or remove any file", expected.keys, actual.keys)
        for ((name, bytes) in expected) {
            assertArrayEquals("Unexpected storage change: $name", bytes, actual.getValue(name))
        }
    }

    private data class Fixture(
        val directory: File,
        val chapterDirectory: File,
        val chapter: UniFile,
        val model: CaptureViewModel,
    )

    private companion object {
        const val BOOK = "NeutralCapture"
        const val CHAPTER = "01"
        const val PAGE = "001.png"
        const val SIZE = 64
        const val CAPTURE_URL = "https://capture.example.invalid/page"
    }
}
