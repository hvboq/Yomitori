package mihon.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.loader.DirectoryPageLoader
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.L2RDoublePagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.L2RPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LDoublePagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.R2LPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.pager.VerticalPagerViewer
import eu.kanade.tachiyomi.ui.reader.viewer.webtoon.WebtoonViewer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.source.local.LocalSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File

/** Viewer/navigation smoke only: six distinct local pages, no OCR, translation server, or manga network. */
@RunWith(AndroidJUnit4::class)
class LocalReaderModesTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun allExplicitReadingModesNavigateLocalPagesAndRestoreUnfinishedPosition() = runBlocking<Unit> {
        val sources = Injekt.get<SourceManager>()
        withTimeout(20000) { sources.isInitialized.first { it } }
        assertTrue(sources.get(LocalSource.ID) is LocalSource)
        Injekt.get<GetFavorites>().await()
        val chapters = Injekt.get<ChapterRepository>()
        val mangas = Injekt.get<MangaRepository>()
        val createdChapters = mutableListOf<Long>()
        val root = File(context.filesDir, "local-reader-modes-${System.nanoTime()}").apply { check(mkdirs()) }
        try {
            OnlineReaderSettings(context).use { settings ->
                configure(settings, root)
                waitFor("fixture storage") {
                    Injekt.get<StorageManager>().getLocalSourceDirectory()?.uri == Uri.fromFile(File(root, "local"))
                }
                val images = (1..PAGE_COUNT).map(::sample)
                // DEFAULT must be resolved by reader preferences and is not a concrete viewer.
                for (mode in ReadingMode.entries.filter { it != ReadingMode.DEFAULT }) {
                    val title = "Reader mode ${mode.name} ${System.nanoTime()}"
                    val directory = File(root, "local/$title/Chapter").apply { check(mkdirs()) }
                    images.forEachIndexed { index, bytes ->
                        File(directory, "%03d.png".format(index + 1)).writeBytes(bytes)
                    }
                    val manga = mangas.insertNetworkManga(
                        listOf(
                            Manga.create().copy(
                                source = LocalSource.ID,
                                url = title,
                                title = title,
                                initialized = true,
                            ),
                        ),
                    ).single()
                    // Fix portrait orientation for reproducibility; double-page viewers are explicit
                    // modes and must work in portrait too. No screenshot-dependent device geometry.
                    mangas.update(
                        MangaUpdate(
                            manga.id,
                            viewerFlags = (mode.flagValue or ReaderOrientation.LOCKED_PORTRAIT.flagValue).toLong(),
                        ),
                    )
                    val chapter = chapters.addAll(
                        listOf(Chapter.create().copy(mangaId = manga.id, url = "$title/Chapter", name = "Chapter")),
                    ).single()
                    createdChapters += chapter.id
                    val intent = ReaderActivity.newIntent(context, manga.id, chapter.id)
                    ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                        val activity = ready(scenario, mode)
                        assertViewer(mode, checkNotNull(activity.viewModel.state.value.viewer))
                        waitFor("$mode first page") { activity.viewModel.state.value.currentPage == 1 }
                        assertPage(activity, directory, images, 0)
                        // In double-page mode, cover=1 and the next spread=2+3; page 3 is the
                        // representative page in both directions and is unfinished in every mode.
                        move(scenario, activity, 2)
                        waitFor("$mode third page") { activity.viewModel.state.value.currentPage == 3 }
                        assertPage(activity, directory, images, 2)
                        withTimeout(10000) {
                            while (chapters.getChapterById(chapter.id)?.let {
                                    it.lastPageRead == 2L && !it.read
                                } != true
                            ) {
                                delay(100)
                            }
                        }
                    }
                    ActivityScenario.launch<ReaderActivity>(intent).use { scenario ->
                        val activity = ready(scenario, mode)
                        assertViewer(mode, checkNotNull(activity.viewModel.state.value.viewer))
                        waitFor("$mode restored third page") { activity.viewModel.state.value.currentPage == 3 }
                        assertPage(activity, directory, images, 2)
                        move(scenario, activity, PAGE_COUNT - 1)
                        waitFor("$mode last page") { activity.viewModel.state.value.currentPage == PAGE_COUNT }
                        assertPage(activity, directory, images, PAGE_COUNT - 1)
                    }
                    // Completed chapters intentionally reopen at the beginning; only unfinished
                    // position persistence is asserted above. None of these operations may edit pages.
                    images.forEachIndexed { index, bytes ->
                        assertArrayEquals(bytes, File(directory, "%03d.png".format(index + 1)).readBytes())
                    }
                }
            }
        } finally {
            if (createdChapters.isNotEmpty()) chapters.removeChaptersWithIds(createdChapters)
            root.deleteRecursively() // This invocation's private synthetic root only.
        }
    }

    private fun configure(settings: OnlineReaderSettings, root: File) {
        val reader = Injekt.get<ReaderPreferences>()
        val translation = Injekt.get<TranslationPreferences>()
        settings.set(Injekt.get<StoragePreferences>().baseStorageDirectory, Uri.fromFile(root).toString())
        settings.set(Injekt.get<BasePreferences>().incognitoMode, false)
        settings.set(Injekt.get<BasePreferences>().downloadedOnly, false)
        settings.set(translation.translationMasterEnabled, false)
        settings.set(translation.liveTranslate, false)
        settings.set(translation.nightReadEnabled, false)
        settings.set(reader.nightReadMode, false)
        settings.set(reader.pageTransitions, false)
        settings.set(reader.landscapeZoom, false)
        settings.set(reader.dualPageSplitPaged, false)
        settings.set(reader.dualPageSplitWebtoon, false)
        settings.set(reader.dualPageRotateToFit, false)
        settings.set(reader.dualPageRotateToFitWebtoon, false)
        settings.set(reader.cropBorders, false)
        settings.set(reader.cropBordersWebtoon, false)
        settings.set(reader.webtoonSidePadding, 0)
        settings.set(reader.doublePageFillMode, 0)
        settings.set(reader.doublePageAlign, 0)
        settings.set(reader.showNavigationOverlayNewUser, false)
        settings.set(reader.showNavigationOverlayOnStart, false)
    }

    private fun ready(scenario: ActivityScenario<ReaderActivity>, mode: ReadingMode): ReaderActivity {
        lateinit var activity: ReaderActivity
        waitFor("$mode ready") {
            // Reacquire after an orientation-triggered activity recreation.
            scenario.onActivity { activity = it }
            val current = activity.viewModel.state.value.viewerChapters?.currChapter
            current?.pageLoader is DirectoryPageLoader && current.pages?.size == PAGE_COUNT &&
                current.pages.orEmpty().all { it.status == Page.State.Ready } &&
                activity.viewModel.state.value.viewer != null
        }
        return activity
    }

    private fun move(scenario: ActivityScenario<ReaderActivity>, activity: ReaderActivity, index: Int) {
        val page = activity.viewModel.state.value.viewerChapters!!.currChapter.pages!![index]
        scenario.onActivity { it.viewModel.state.value.viewer!!.moveToPage(page) }
    }

    private fun assertPage(activity: ReaderActivity, directory: File, images: List<ByteArray>, index: Int) {
        val pages = activity.viewModel.state.value.viewerChapters!!.currChapter.pages!!
        assertEquals(PAGE_COUNT, pages.size)
        val page = pages[index]
        assertEquals(Page.State.Ready, page.status)
        assertEquals(Uri.fromFile(File(directory, "%03d.png".format(index + 1))), page.uri)
        assertArrayEquals(images[index], page.displayStream!!().use { it.readBytes() })
    }

    private fun assertViewer(mode: ReadingMode, viewer: Viewer) {
        val matches = when (mode) {
            ReadingMode.LEFT_TO_RIGHT -> viewer is L2RPagerViewer
            ReadingMode.RIGHT_TO_LEFT -> viewer is R2LPagerViewer
            ReadingMode.VERTICAL -> viewer is VerticalPagerViewer
            ReadingMode.WEBTOON -> viewer is WebtoonViewer && viewer.isContinuous
            ReadingMode.CONTINUOUS_VERTICAL -> viewer is WebtoonViewer && !viewer.isContinuous
            ReadingMode.DOUBLE_PAGE_RTL -> viewer is R2LDoublePagerViewer
            ReadingMode.DOUBLE_PAGE_LTR -> viewer is L2RDoublePagerViewer
            ReadingMode.DEFAULT -> false
        }
        assertTrue("$mode selected ${viewer.javaClass.simpleName}", matches)
    }

    private fun sample(number: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(600, 1800, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).apply {
                drawColor(Color.rgb(220 - number * 12, 230, 240))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 90f
                }
                drawText("Page $number", 70f, 350f, paint)
                drawRect(60f, 700f, 540f, 800f + number * 50f, paint)
            }
            return ByteArrayOutputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                it.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun waitFor(label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (!predicate()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out: $label" }
            SystemClock.sleep(100)
        }
    }

    private companion object {
        const val PAGE_COUNT = 6
    }
}
