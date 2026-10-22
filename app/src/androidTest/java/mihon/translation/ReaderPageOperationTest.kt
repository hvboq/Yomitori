package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.OcrProgressCodec
import eu.kanade.tachiyomi.data.translation.OriginalMaterial
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.loader.TranslatingPageLoader
import eu.kanade.tachiyomi.ui.reader.loader.refreshAfterPageOperation
import eu.kanade.tachiyomi.ui.reader.model.InsertPage
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.OcrLineProgress
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionProgress
import li.joye.yakuyomi.engine.RegionState
import mihon.source.hitomi.HitomiSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Exercises the actual translating/download loaders and the flows consumed by both reader holders. */
@RunWith(AndroidJUnit4::class)
class ReaderPageOperationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun successfulManualOperationLeavesQueueAndDecodesTheSavedResult() = runBlocking {
        withPage { directory, page ->
            val file = File(directory, "001.png")
            page.showOriginalPreview { File(directory, ".yakuyomi/001.orig.png").inputStream() }
            writeImage(file, Color.BLACK)
            File(directory, ".yakuyomi_translated").writeText("001.png")
            page.clearOriginalPreview() // The VM clears comparison only after a successful translation.
            refreshAfterPageOperation(page)
            assertReadable(page, Color.BLACK)
            assertFalse(page.isOriginalPreview)
            assertTrue(PageTranslator(context).isPageTranslated(UniFile.fromFile(directory)!!, "001.png"))
        }
    }

    @Test
    fun failedManualOperationLeavesQueueAndDecodesOriginalWithoutMarkingDone() = runBlocking {
        withPage { directory, page ->
            val original = File(directory, "001.png").readBytes()
            page.showOriginalPreview { File(directory, ".yakuyomi/001.orig.png").inputStream() }
            refreshAfterPageOperation(page)
            assertReadable(page, Color.WHITE)
            assertTrue(page.isOriginalPreview)
            assertArrayEquals(original, File(directory, "001.png").readBytes())
            assertFalse(PageTranslator(context).isPageTranslated(UniFile.fromFile(directory)!!, "001.png"))
        }
    }

    @Test
    fun cancelledManualOperationRestoresReadableOriginalWithoutReportingSuccess() = runBlocking {
        withPage { directory, page ->
            page.showOriginalPreview { File(directory, ".yakuyomi/001.orig.png").inputStream() }
            writeImage(File(directory, "001.png"), Color.BLACK)
            val savedResult = File(directory, "001.png").readBytes()
            coroutineScope {
                val operation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        refreshAfterPageOperation(page)
                    }
                }
                operation.cancelAndJoin()
                assertTrue(operation.isCancelled)
            }
            assertReadable(page, Color.WHITE)
            assertTrue(page.isOriginalPreview)
            assertArrayEquals(savedResult, File(directory, "001.png").readBytes())
            assertFalse(PageTranslator(context).isPageTranslated(UniFile.fromFile(directory)!!, "001.png"))
        }
    }

    @Test
    fun partialAndFinalQueueEventsReloadTheSameReaderPageUntilCompletion() = runBlocking {
        val manager = Injekt.get<TranslationManager>()

        @Suppress("UNCHECKED_CAST")
        val events = TranslationManager::class.java.getDeclaredField("_donePageEvents").apply {
            isAccessible = true
        }.get(manager) as MutableSharedFlow<Pair<Long, String>>
        val subscriptionsBefore = events.subscriptionCount.value
        val queueBefore = manager.queueState.value
        try {
            withPage { directory, page ->
                val loader = page.chapter.pageLoader as TranslatingPageLoader
                val chapterDir = checkNotNull(UniFile.fromFile(directory))
                val materialsDir = checkNotNull(UniFile.fromFile(File(directory, ".yakuyomi")))
                val originalBytes = File(directory, "001.png").readBytes()
                val original = PartialPageStore.Original(
                    OriginalMaterial.exactName("001.png"),
                    PartialPageStore.sha256(originalBytes),
                    originalBytes.size.toLong(),
                )
                val store = PartialPageStore.forUniFile(context, chapterDir, materialsDir)
                val translator = PageTranslator(context)

                @Suppress("UNCHECKED_CAST")
                val tracked = TranslatingPageLoader::class.java.getDeclaredField("tracked").apply {
                    isAccessible = true
                }.get(loader) as MutableMap<Int, ReaderPage>
                fun isTracked() = synchronized(tracked) { tracked[page.index] === page }

                // Exercise the real loader observer without activating a real translation queue.
                loader.retryPage(page)
                assertTrue(isTracked())
                TranslatingPageLoader::class.java.getDeclaredMethod("startQueueObserver").apply {
                    isAccessible = true
                }.invoke(loader)
                withTimeout(3000) { events.subscriptionCount.first { it > subscriptionsBefore } }

                // Typed OCR progress and actual 8x8 PNG persistence only; no native inference,
                // translation server, ReaderActivity or user input is represented by this fixture.
                val successful = RegionProgress(
                    id = 0,
                    state = RegionState.TRANSLATED,
                    lines = listOf(
                        OcrLineProgress(
                            listOf(Pt(0f, 0f), Pt(8f, 0f), Pt(8f, 3f), Pt(0f, 3f)),
                            1f,
                            "h",
                            "こんにちは",
                        ),
                    ),
                    direction = "h",
                    translatedText = "안녕하세요",
                )
                val pending = RegionProgress(
                    id = 1,
                    state = RegionState.OCR_PENDING,
                    lines = listOf(
                        OcrLineProgress(
                            listOf(Pt(0f, 4f), Pt(8f, 4f), Pt(8f, 8f), Pt(0f, 8f)),
                            1f,
                            "h",
                            "",
                        ),
                    ),
                    direction = "h",
                )
                val rawMask = imageBytes(Color.BLACK)
                val partial = store.commit(
                    pageName = "001.png",
                    original = original,
                    width = 8,
                    height = 8,
                    progressPayload = OcrProgressCodec.encode(PageProgress(8, 8, listOf(successful, pending))),
                    outputBytes = imageBytes(Color.GRAY),
                    rawMaskBytes = rawMask,
                    baseRevision = null,
                    complete = false,
                )
                assertFalse(translator.isPageTranslated(chapterDir, "001.png"))
                assertTrue(translator.hasDisplayableTranslation(chapterDir, "001.png"))
                val beforePartial = page.reloadFlow.value
                events.emit(requireNotNull(page.chapter.chapter.id) to "001.png")
                withTimeout(3000) { page.reloadFlow.first { it > beforePartial } }
                assertEquals("One partial event reloads this same page once", beforePartial + 1, page.reloadFlow.value)
                assertReadable(page, Color.GRAY)
                assertTrue("A partial page must stay registered for the final event", isTracked())

                val resolved = pending.copy(
                    state = RegionState.TRANSLATED,
                    lines = pending.lines.map { it.copy(text = "ありがとう") },
                    translatedText = "감사합니다",
                )
                store.commit(
                    pageName = "001.png",
                    original = original,
                    width = 8,
                    height = 8,
                    progressPayload = OcrProgressCodec.encode(PageProgress(8, 8, listOf(successful, resolved))),
                    outputBytes = imageBytes(Color.BLACK),
                    rawMaskBytes = rawMask,
                    baseRevision = partial.revision,
                    complete = true,
                )
                assertTrue(translator.isPageTranslated(chapterDir, "001.png"))
                assertTrue(translator.hasDisplayableTranslation(chapterDir, "001.png"))
                val beforeFinal = page.reloadFlow.value
                events.emit(requireNotNull(page.chapter.chapter.id) to "001.png")
                withTimeout(3000) { page.reloadFlow.first { it > beforeFinal } }
                assertEquals("The final event reloads the existing page again", beforeFinal + 1, page.reloadFlow.value)
                assertReadable(page, Color.BLACK)
                assertFalse("Only complete output can remove this page from the observer", isTracked())
                assertSame(loader, page.chapter.pageLoader)
                assertArrayEquals(originalBytes, File(directory, ".yakuyomi/${original.name}").readBytes())
                assertEquals(queueBefore, manager.queueState.value)
            }
        } finally {
            // withPage recycles the loader on both success and failure; wait for its collector to leave.
            withTimeout(3000) { events.subscriptionCount.first { it <= subscriptionsBefore } }
        }
    }

    @Test
    fun refreshMatchesPageUriAcrossMixedFormatsAndInsertedFiles() = runBlocking {
        withPage { directory, page ->
            val loader = page.chapter.pageLoader as TranslatingPageLoader
            File(directory, ".yakuyomi_translated").writeText("001.png")
            val otherFormat = File(directory, "000.gif").apply { writeBytes(byteArrayOf(1)) }
            page.uri = Uri.fromFile(otherFormat)
            loader.refreshSelected(page)
            assertEquals("The GIF must not inherit the PNG's completion by index", 0, page.reloadFlow.value)

            writeImage(File(directory, "000.png"), Color.WHITE)
            page.uri = Uri.fromFile(File(directory, "001.png"))
            loader.refreshSelected(page)
            assertTrue("An inserted earlier filename must not shift page identity", page.reloadFlow.value > 0)

            val outside = ReaderPage(1).apply {
                chapter = page.chapter
                uri = Uri.fromFile(File(directory.parentFile, "001.png"))
            }
            loader.refreshSelected(outside)
            assertEquals("A matching basename outside this chapter is not a page", 0, outside.reloadFlow.value)
        }
    }

    @Test
    fun delegateLoadingErrorIsPreservedInsteadOfBeingForcedReady() = runBlocking {
        val error = IOException("fixture image is unreadable")
        val delegate = object : PageLoader() {
            override var isLocal = true
            override suspend fun getPages(): List<ReaderPage> = emptyList()
            override fun retryPage(page: ReaderPage) {
                page.status = Page.State.Error(error)
            }
        }
        withPage(delegate) { _, page ->
            refreshAfterPageOperation(page)
            assertSame(error, (page.status as Page.State.Error).error)
            assertTrue(page.reloadFlow.value > 0)
        }
    }

    @Test
    fun translatedSplitPageRefreshesItsParentSoBothHalvesObserveTheChange() = runBlocking {
        withPage { directory, page ->
            File(directory, ".yakuyomi_translated").writeText("001.png")
            val secondHalf = InsertPage(page)
            val loader = page.chapter.pageLoader as TranslatingPageLoader
            loader.refreshSelected(secondHalf)
            assertTrue("Both halves observe the parent reload", page.reloadFlow.value > 0)
            assertEquals(0, secondHalf.reloadFlow.value)
        }
    }

    @Test
    fun thrownLoaderFailureBecomesDisplayErrorWithoutMaskingCancellation() = runBlocking {
        val error = IOException("fixture storage disappeared")
        val delegate = object : PageLoader() {
            override var isLocal = true
            override suspend fun getPages(): List<ReaderPage> = emptyList()
            override fun retryPage(page: ReaderPage): Unit = throw error
        }
        withPage(delegate) { _, page ->
            coroutineScope {
                val operation = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        refreshAfterPageOperation(page)
                    }
                }
                operation.cancelAndJoin()
                assertTrue(operation.isCancelled)
            }
            assertSame(error, (page.status as Page.State.Error).error)
        }
    }

    private suspend fun assertReadable(page: ReaderPage, color: Int) {
        // PagerPageHolder and WebtoonPageHolder both require Ready even when reload changes.
        withTimeout(3000) {
            combine(page.statusFlow, page.reloadFlow) { status, revision -> status to revision }
                .first { (status, revision) -> status == Page.State.Ready && revision > 0 }
        }
        page.displayStream!!.invoke().use { input ->
            val bitmap = checkNotNull(BitmapFactory.decodeStream(input))
            try {
                assertEquals(color, bitmap.getPixel(0, 0))
            } finally {
                bitmap.recycle()
            }
        }
    }

    private suspend fun withPage(
        overrideDelegate: PageLoader? = null,
        block: suspend (File, ReaderPage) -> Unit,
    ) {
        val directory = File(context.cacheDir, "reader-operation-${System.nanoTime()}").apply { mkdirs() }
        val chapter = ReaderChapter(Chapter.create().copy(id = -44, name = "Fixture"))
        val source = HitomiSource(Injekt.get())
        val manga = Manga.create().copy(source = source.id, title = "Reader operation fixture")
        val delegate = overrideDelegate ?: DownloadPageLoader(chapter, manga, source, Injekt.get(), Injekt.get())
        val loader = TranslatingPageLoader(delegate, chapter, manga, source, Injekt.get(), Injekt.get())
        chapter.pageLoader = loader
        val file = File(directory, "001.png")
        writeImage(file, Color.WHITE)
        val originalBytes = file.readBytes()
        val original = File(directory, ".yakuyomi/001.orig.png").apply {
            parentFile!!.mkdirs()
            writeBytes(originalBytes)
        }
        val page = ReaderPage(0, stream = { file.inputStream() }).apply {
            this.chapter = chapter
            uri = Uri.fromFile(file)
            status = Page.State.Queue
        }

        // Resolve this isolated fixture without registering a manga or changing user storage settings.
        fun field(name: String, value: Any) {
            TranslatingPageLoader::class.java.getDeclaredField(name).apply { isAccessible = true }.set(loader, value)
        }
        field("resolved", true)
        field("chapterDir", UniFile.fromFile(directory)!!)
        try {
            block(directory, page)
            assertArrayEquals(
                "Display refresh must never replace the original backup",
                originalBytes,
                original.readBytes(),
            )
        } finally {
            loader.recycle()
            directory.deleteRecursively()
        }
    }

    private fun writeImage(file: File, color: Int) {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try {
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun imageBytes(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try {
            return ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}
