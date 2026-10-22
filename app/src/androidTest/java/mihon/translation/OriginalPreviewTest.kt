package mihon.translation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import java.io.File

@RunWith(AndroidJUnit4::class)
class OriginalPreviewTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun originalComparisonWithoutOptionalMaterialsLeavesPageAndCompletionUntouched() = runBlocking {
        val directory = File(context.cacheDir, "original-preview-${System.nanoTime()}").apply { mkdirs() }
        val file = File(directory, "001.png")
        val original = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val translated = Bitmap.createBitmap(80, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try {
            file.outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val originalBytes = file.readBytes()
            val chapterDir = requireNotNull(UniFile.fromFile(directory))
            val translator = PageTranslator(context)
            assertTrue(
                translator.persistLivePage(chapterDir, UniFile.fromFile(file)!!, original, translated, null, "boxfill"),
            )
            assertFalse(File(directory, ".yakuyomi/001.json").exists())
            val before = directory.walkTopDown().filter { it.isFile }.associate {
                it.relativeTo(directory).path to
                    it.readBytes()
            }
            val saved = requireNotNull(translator.originalForPreview(chapterDir, "001.png"))
            val page = ReaderPage(0, stream = { file.inputStream() })
            page.showOriginalPreview { saved.openInputStream() }
            assertTrue(page.isOriginalPreview)
            assertArrayEquals(originalBytes, page.displayStream!!().use { it.readBytes() })
            // A completion refresh must not discard the user's temporary comparison.
            page.reload()
            assertArrayEquals(originalBytes, page.displayStream!!().use { it.readBytes() })
            page.clearOriginalPreview()
            assertFalse(page.isOriginalPreview)
            assertArrayEquals(file.readBytes(), page.displayStream!!().use { it.readBytes() })
            assertTrue(translator.isPageTranslated(chapterDir, "001.png"))
            val after = directory.walkTopDown().filter { it.isFile }.associate {
                it.relativeTo(directory).path to
                    it.readBytes()
            }
            assertEquals(before.keys, after.keys)
            before.forEach { (name, bytes) -> assertArrayEquals("Comparison changed $name", bytes, after[name]) }
        } finally {
            original.recycle()
            translated.recycle()
        }
    }

    @Test
    fun missingCorruptOrDirectoryOriginalDoesNotReplaceTheReadableTranslation() {
        val directory = File(context.cacheDir, "bad-preview-${System.nanoTime()}").apply { mkdirs() }
        val materials = File(directory, ".yakuyomi").apply { mkdirs() }
        val saved = File(materials, "001.orig.png")
        val chapter = requireNotNull(UniFile.fromFile(directory))
        val translator = PageTranslator(context)
        assertNull(translator.originalForPreview(chapter, "001.png"))
        saved.writeText("truncated image")
        assertNull(translator.originalForPreview(chapter, "001.png"))
        assertTrue(saved.delete())
        assertTrue(saved.mkdir())
        File(saved, "keep.txt").writeText("keep")
        assertNull(translator.originalForPreview(chapter, "001.png"))
        assertNull(translator.originalForPreview(chapter, "../001.png"))
        assertEquals("keep", File(saved, "keep.txt").readText())
    }

    @Test
    fun splitPageSharesTheParentComparisonAndReturnsToLatestTranslatedStream() {
        val parent = ReaderPage(0, stream = { byteArrayOf(1).inputStream() }).apply {
            chapter = ReaderChapter(Chapter.create())
        }
        val secondHalf = InsertPage(parent)
        secondHalf.showOriginalPreview { byteArrayOf(2).inputStream() }
        assertTrue(parent.isOriginalPreview)
        assertTrue(secondHalf.isOriginalPreview)
        assertEquals(2, secondHalf.displayStream!!().use { it.read() })
        parent.stream = { byteArrayOf(3).inputStream() }
        secondHalf.clearOriginalPreview()
        assertFalse(parent.isOriginalPreview)
        assertEquals(3, secondHalf.displayStream!!().use { it.read() })
    }
}
