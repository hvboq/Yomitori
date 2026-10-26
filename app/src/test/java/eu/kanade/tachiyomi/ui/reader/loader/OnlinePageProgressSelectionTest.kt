package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter

/** Actual production acceptance predicate; no Android calls, scheduler, inference or copied guard. */
class OnlinePageProgressSelectionTest {
    @Test
    fun `previous terminal cannot complete a new manual or automatic selection`() {
        val selection = selection(101)
        val results = listOf(
            PageTranslator.SinglePageResult.TRANSLATED,
            PageTranslator.SinglePageResult.SKIPPED,
            PageTranslator.SinglePageResult.PARTIAL,
            PageTranslator.SinglePageResult.NOT_TRANSLATED,
        )
        for (result in results) {
            val stage = if (result == PageTranslator.SinglePageResult.NOT_TRANSLATED) {
                OnlineTranslatedPageLoader.Stage.FAILED
            } else {
                OnlineTranslatedPageLoader.Stage.FINISHED
            }
            val old = OnlineTranslatedPageLoader.Progress(selection.page, stage, result, retryVersion = 11)
            old.accepts(selection, retryVersion = 11) shouldBe true
            old.accepts(selection, retryVersion = 12) shouldBe false
            old.accepts(selection, retryVersion = 0) shouldBe false
            old.copy(retryVersion = 12).accepts(selection, retryVersion = 12) shouldBe true
            val automatic = old.copy(retryVersion = 0)
            automatic.accepts(selection, retryVersion = 0) shouldBe true
            automatic.accepts(selection, retryVersion = 12) shouldBe false
        }
    }

    @Test
    fun `same index in another chapter is rejected during the chapter handoff`() {
        val old = selection(201)
        val next = selection(202)
        old.page.index shouldBe next.page.index
        val terminal = OnlineTranslatedPageLoader.Progress(
            old.page,
            OnlineTranslatedPageLoader.Stage.FINISHED,
            PageTranslator.SinglePageResult.TRANSLATED,
        )
        terminal.accepts(old, retryVersion = 0) shouldBe true
        // The selected page already belongs to B while the previous observer and current
        // chapter still belong to A. Index-only matching accepted this exact handoff window.
        terminal.matchesSelection(next.page, 0, old.loader, old.chapter, old.chapter) shouldBe false
        terminal.accepts(next, retryVersion = 0) shouldBe false
        terminal.copy(page = next.page).accepts(next, retryVersion = 0) shouldBe true
    }

    @Test
    fun `a new page object with the same chapter and index cannot reuse an old selection`() {
        val selection = selection(301)
        val replacement = ReaderPage(selection.page.index).apply { chapter = selection.chapter }
        val old = OnlineTranslatedPageLoader.Progress(
            selection.page,
            OnlineTranslatedPageLoader.Stage.TRANSLATING,
            retryVersion = 7,
        )
        old.accepts(selection, retryVersion = 7) shouldBe true
        old.matchesSelection(replacement, 7, selection.loader, selection.chapter, selection.chapter) shouldBe false
        old.copy(page = replacement)
            .matchesSelection(replacement, 7, selection.loader, selection.chapter, selection.chapter) shouldBe true
    }

    @Test
    fun `chapter loader replacement rejects the previous observer owner`() {
        val selection = selection(401)
        val progress = OnlineTranslatedPageLoader.Progress(
            selection.page,
            OnlineTranslatedPageLoader.Stage.WAITING_IMAGE,
            retryVersion = 9,
        )
        progress.accepts(selection, retryVersion = 9) shouldBe true
        val replacementLoader = NeutralLoader()
        selection.chapter.pageLoader = replacementLoader
        progress.accepts(selection, retryVersion = 9) shouldBe false
        progress.matchesSelection(selection.page, 9, replacementLoader, selection.chapter, selection.chapter) shouldBe
            true
    }

    @Test
    fun `only the exact active and current chapter may accept progress`() {
        val selection = selection(501)
        val other = selection(502)
        val progress = OnlineTranslatedPageLoader.Progress(
            selection.page,
            OnlineTranslatedPageLoader.Stage.CAPTURING,
            retryVersion = 3,
        )
        progress.accepts(selection, retryVersion = 3) shouldBe true
        progress.matchesSelection(selection.page, 3, selection.loader, selection.chapter, other.chapter) shouldBe false
        progress.matchesSelection(selection.page, 3, selection.loader, other.chapter, other.chapter) shouldBe false
        progress.matchesSelection(selection.page, 3, selection.loader, null, null) shouldBe false
        progress.matchesSelection(selection.page, 3, selection.loader, selection.chapter, null) shouldBe false
    }

    private fun OnlineTranslatedPageLoader.Progress.accepts(selection: Selection, retryVersion: Long) =
        matchesSelection(selection.page, retryVersion, selection.loader, selection.chapter, selection.chapter)

    private fun selection(chapterId: Long): Selection {
        val loader = NeutralLoader()
        val chapter = ReaderChapter(Chapter.create().copy(id = chapterId, mangaId = 50)).apply { pageLoader = loader }
        val page = ReaderPage(0).apply { this.chapter = chapter }
        return Selection(chapter, page, loader)
    }

    private data class Selection(val chapter: ReaderChapter, val page: ReaderPage, val loader: PageLoader)

    private class NeutralLoader : PageLoader() {
        override var isLocal: Boolean = false
        override suspend fun getPages(): List<ReaderPage> = emptyList()
    }
}
