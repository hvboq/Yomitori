package eu.kanade.tachiyomi.data.translation

import eu.kanade.tachiyomi.data.translation.TranslationManager.Entry
import eu.kanade.tachiyomi.data.translation.model.TranslationItem.Status
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

class TranslationEnqueueRetryTest {
    private val manga = Manga.create().copy(id = 1L)

    private fun chapter(id: Long) = Chapter.create().copy(id = id, mangaId = manga.id)

    @Test
    fun `chapter list retry clears failed status and progress without replacing or moving the entry`() {
        val before = Entry(manga, chapter(1))
        val failed = Entry(manga, chapter(2), Status.ERROR, done = 3, total = 5, method = "boxfill", paused = true)
        val after = Entry(manga, chapter(3))
        val entries = mutableListOf(before, failed, after)

        TranslationManager.enqueueTranslationsAtEnd(entries, manga, listOf(failed.chapter), "auto_whole")

        entries shouldBe listOf(before, failed, after)
        (entries[1] === failed) shouldBe true
        failed.status shouldBe Status.QUEUE
        failed.done shouldBe 0
        failed.total shouldBe 0
        failed.method shouldBe "auto_whole"
        failed.paused shouldBe false
    }

    @Test
    fun `repeated retry taps keep one queued entry and do not overwrite its queued method`() {
        val failed = Entry(manga, chapter(1), Status.ERROR, method = "boxfill")
        val entries = mutableListOf(failed)

        TranslationManager.enqueueTranslationsAtEnd(entries, manga, listOf(failed.chapter), "auto_whole")
        repeat(3) {
            TranslationManager.enqueueTranslationsAtEnd(entries, manga, listOf(failed.chapter), "boxfill")
        }

        entries shouldBe listOf(failed)
        failed.status shouldBe Status.QUEUE
        failed.method shouldBe "auto_whole"
    }

    @Test
    fun `existing running and queued entries keep status method and progress while explicit enqueue resumes them`() {
        val running = Entry(manga, chapter(1), Status.TRANSLATING, done = 2, total = 5, method = "boxfill")
        val queued = Entry(manga, chapter(2), Status.QUEUE, done = 1, total = 4, method = "boxfill", paused = true)
        val entries = mutableListOf(running, queued)

        TranslationManager.enqueueTranslationsAtEnd(
            entries,
            manga,
            listOf(running.chapter, queued.chapter),
            "auto_whole",
        )

        entries shouldBe listOf(running, queued)
        running.status shouldBe Status.TRANSLATING
        running.method shouldBe "boxfill"
        running.done shouldBe 2
        running.total shouldBe 5
        queued.status shouldBe Status.QUEUE
        queued.method shouldBe "boxfill"
        queued.done shouldBe 1
        queued.total shouldBe 4
        queued.paused shouldBe false
    }

    @Test
    fun `unrelated errors and night work remain untouched while repeated new chapter ids enqueue once`() {
        val unrelated = Entry(manga, chapter(1), Status.ERROR, done = 2, total = 3, method = "boxfill", paused = true)
        val night = Entry(manga, chapter(2), Status.ERROR, done = 1, total = 2, nightRender = true, paused = true)
        val entries = mutableListOf(unrelated, night)

        TranslationManager.enqueueTranslationsAtEnd(entries, manga, listOf(night.chapter, night.chapter), "auto_whole")

        entries.size shouldBe 3
        unrelated.status shouldBe Status.ERROR
        unrelated.done shouldBe 2
        unrelated.total shouldBe 3
        unrelated.paused shouldBe true
        night.status shouldBe Status.ERROR
        night.done shouldBe 1
        night.total shouldBe 2
        night.paused shouldBe true
        entries.last().chapter shouldBe night.chapter
        entries.last().nightRender shouldBe false
        entries.last().status shouldBe Status.QUEUE
        entries.last().method shouldBe "auto_whole"
    }
}
