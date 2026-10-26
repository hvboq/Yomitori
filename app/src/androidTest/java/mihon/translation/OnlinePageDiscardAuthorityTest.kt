package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.CapturedSourceStore
import eu.kanade.tachiyomi.data.translation.CapturedSourceUniFileIo
import eu.kanade.tachiyomi.data.translation.OcrProgressCodec
import eu.kanade.tachiyomi.data.translation.OriginalMaterial
import eu.kanade.tachiyomi.data.translation.PageProcessingSummaryCodec
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import li.joye.yakuyomi.engine.OcrLineProgress
import li.joye.yakuyomi.engine.PageProgress
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.RegionProgress
import li.joye.yakuyomi.engine.RegionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Actual storage authorities only: no engine inference, Activity, settings writes or HTTP. */
@RunWith(AndroidJUnit4::class)
class OnlinePageDiscardAuthorityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun rawAndFreshCompletedPagesRequireExactSourceAndCacheDisplayAuthority() = runBlocking {
        fixture { f ->
            f.readOnly(true)
            f.readOnly(false, displayHash = "1".repeat(64))
            f.readOnly(false, sourceHash = "0".repeat(64))
            f.readOnly(false, sourceBytes = f.source.size.toLong() + 1)
            f.completeFresh()
            val display = f.page.readBytes()
            val displayHash = sha(display)
            assertTrue(f.translator.isPageTranslated(f.chapter, PAGE))
            f.readOnly(false)
            f.readOnly(true, displayHash)
            // This optional explanation is deliberately absent from the deletion authority.
            File(f.materials, PageProcessingSummaryCodec.exactName(PAGE)).takeIf { it.exists() }?.let {
                assertTrue(it.delete())
            }
            f.readOnly(true, displayHash)
            f.page.writeBytes(png(Color.GREEN))
            f.readOnly(false, displayHash)
            f.page.writeBytes(display)
            val original = File(f.materials, OriginalMaterial.exactName(PAGE))
            val originalBytes = original.readBytes()
            original.writeBytes(png(Color.RED))
            f.readOnly(false, displayHash)
            original.writeBytes(originalBytes)
            f.readOnly(true, displayHash)
            assertTrue(original.delete())
            f.readOnly(false, displayHash)
        }
    }

    @Test
    fun completedJournalIsEvictableWhilePendingTornOrOrphanAuthoritiesRemain() = runBlocking {
        fixture { f ->
            var state = f.journal(complete = false)
            assertFalse(state.complete)
            f.readOnly(false)
            state = f.journal(complete = true, previous = state)
            assertTrue(state.complete)
            // The actual final commit is enough; no cache ledger or optional summary is needed.
            f.readOnly(true)
            val output = File(f.materials, state.outputSnapshotName)
            val mask = File(f.materials, state.rawMaskSnapshotName)
            val outputBytes = output.readBytes()
            val maskBytes = mask.readBytes()
            mask.writeBytes(png(Color.RED))
            f.readOnly(false)
            mask.writeBytes(maskBytes)
            f.readOnly(true)
            assertTrue(output.delete())
            f.readOnly(false)
            output.writeBytes(outputBytes)
            f.readOnly(true)
            val visible = f.page.readBytes()
            f.page.writeBytes(png(Color.RED))
            f.readOnly(false)
            f.page.writeBytes(visible)
            f.readOnly(true)

            val record = File(f.materials, state.outputSnapshotName.removeSuffix(".output") + ".record")
            val wip = File(f.materials, record.name.removeSuffix(".record") + ".wip")
            wip.writeText("neutral-torn-marker")
            f.readOnly(false)
            assertTrue(wip.delete())
            f.readOnly(true)
            // A complete older state must not make an uncommitted newer blob disposable.
            val prefix = "partial-${sha(PAGE.toByteArray())}-${UUID.randomUUID()}"
            val orphanOutput = File(f.materials, "$prefix.output")
            assertFalse(orphanOutput.exists())
            orphanOutput.writeBytes(outputBytes)
            f.readOnly(false)
            assertTrue(orphanOutput.delete())
            f.readOnly(true)
            // Lost final references must not revive the legacy completion overlay.
            checkNotNull(f.materials.listFiles()).filter { it.name.endsWith(".commit") }.forEach {
                assertTrue(it.delete())
            }
            f.readOnly(false)
        }
    }

    @Test
    fun activePageLockAndInterruptedCapturedSourceRetainAllAuthorityBytes() = runBlocking {
        fixture { f ->
            f.completeFresh()
            val displayHash = sha(f.page.readBytes())
            f.readOnly(true, displayHash)
            val lock = PageTranslator.Companion.javaClass.getDeclaredMethod("pageLock", UniFile::class.java)
                .apply { isAccessible = true }
                .invoke(PageTranslator.Companion, f.pageFile) as Mutex
            assertTrue(lock.tryLock())
            try {
                f.readOnly(false, displayHash)
            } finally {
                lock.unlock()
            }
            f.readOnly(true, displayHash)
            val rawIo = CapturedSourceUniFileIo(context, f.chapter, f.materialDir)
            val interruptedIo = object : CapturedSourceStore.Io by rawIo {
                override fun replacePage(pageName: String, bytes: ByteArray): Boolean = throw CaptureInterrupted()
            }
            val store = CapturedSourceStore(interruptedIo)
            try {
                store.replace(
                    PAGE,
                    png(Color.GREEN),
                    setOf(OriginalMaterial.exactName(PAGE)),
                    previousComplete = true,
                    setComplete = {},
                )
                error("Expected the owned capture to stop after durable preparation")
            } catch (_: CaptureInterrupted) {
                // A critical interruption intentionally leaves real pending capture authority.
            }
            assertTrue(store.hasPending(PAGE))
            f.readOnly(false, displayHash)
            assertTrue(store.hasPending(PAGE))
        }
    }

    private class CaptureInterrupted : Error("neutral-owned-capture-interruption")

    private inner class Fixture(val directory: File) {
        val source = png(Color.WHITE)
        val page = File(directory, PAGE).apply { writeBytes(source) }
        val chapter = checkNotNull(UniFile.fromFile(directory))
        val pageFile = checkNotNull(UniFile.fromFile(page))
        val translator = PageTranslator(context)
        val materials get() = File(directory, ".yakuyomi")
        val materialDir get() = checkNotNull(UniFile.fromFile(materials))

        suspend fun completeFresh() {
            val original = checkNotNull(BitmapFactory.decodeByteArray(source, 0, source.size))
            val output = image(Color.BLACK)
            try {
                assertTrue(translator.persistLivePage(chapter, pageFile, original, output, null, "boxfill"))
            } finally {
                original.recycle()
                output.recycle()
            }
        }

        fun journal(complete: Boolean, previous: PartialPageStore.State? = null): PartialPageStore.State {
            if (!materials.exists()) check(materials.mkdir())
            val original = File(materials, OriginalMaterial.exactName(PAGE))
            if (!original.exists()) original.writeBytes(source)
            val progress = PageProgress(
                SIZE,
                SIZE,
                (0..1).map { index ->
                    val resolved = index == 0 || complete
                    RegionProgress(
                        index,
                        if (resolved) RegionState.TRANSLATED else RegionState.OCR_PENDING,
                        listOf(
                            OcrLineProgress(
                                listOf(Pt(0f, 0f), Pt(16f, 0f), Pt(16f, 16f), Pt(0f, 16f)),
                                1f,
                                "h",
                                if (resolved) "neutral source" else "",
                            ),
                        ),
                        "h",
                        translatedText = if (resolved) "중립 번역" else "",
                    )
                },
            )
            return PartialPageStore.forUniFile(context, chapter, materialDir).commit(
                PAGE,
                PartialPageStore.Original(original.name, sha(source), source.size.toLong()),
                SIZE,
                SIZE,
                OcrProgressCodec.encode(progress),
                png(if (complete) Color.BLACK else Color.BLUE),
                png(Color.WHITE),
                previous?.revision,
                complete,
            )
        }

        fun readOnly(
            expected: Boolean,
            displayHash: String? = null,
            sourceHash: String = sha(source),
            sourceBytes: Long = source.size.toLong(),
        ) {
            val before = snapshot(directory)
            assertEquals(expected, translator.canDiscardOnlinePage(chapter, PAGE, sourceHash, sourceBytes, displayHash))
            assertEquals("An authority query must never recover, rewrite or delete files", before, snapshot(directory))
        }
    }

    private suspend fun fixture(block: suspend (Fixture) -> Unit) {
        check(context.packageName == "app.yomitori.yakuyomi.audit")
        val parent = context.cacheDir.canonicalFile
        val directory = File(parent, "online-discard-${UUID.randomUUID()}")
        check(directory.canonicalFile.parentFile == parent && !directory.exists() && directory.mkdir())
        var passed = false
        try {
            block(Fixture(directory))
            passed = true
        } finally {
            if (passed) {
                check(directory.canonicalFile.parentFile == parent)
                directory.walkBottomUp().forEach {
                    check(it.canonicalFile == it.absoluteFile && it.toPath().startsWith(directory.toPath()))
                    check(it.delete())
                }
            }
        }
    }

    private companion object {
        const val PAGE = "page.png"
        const val SIZE = 32
        fun sha(bytes: ByteArray) = PartialPageStore.sha256(bytes)
        fun image(color: Int) = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
            setHasAlpha(false)
        }
        fun png(color: Int): ByteArray = image(color).let { bitmap ->
            try {
                ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
        fun snapshot(directory: File) = directory.walkTopDown().associate {
            it.relativeTo(directory).invariantSeparatorsPath to
                if (it.isFile) "${it.length()}:${sha(it.readBytes())}" else "directory"
        }
    }
}
