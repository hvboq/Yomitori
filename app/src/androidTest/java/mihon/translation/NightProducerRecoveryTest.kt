package mihon.translation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.nightread.NightGovernor
import eu.kanade.tachiyomi.data.nightread.NightLevel
import eu.kanade.tachiyomi.data.nightread.NightPages
import eu.kanade.tachiyomi.data.nightread.NightPolicy
import eu.kanade.tachiyomi.data.nightread.NightSourceRevision
import eu.kanade.tachiyomi.data.translation.CompletionManifestCodec
import eu.kanade.tachiyomi.data.translation.OriginalMaterial
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.PartialPageStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/** Actual public producer preflight; unique progress sentinel stops BEFORE model resolution. */
@RunWith(AndroidJUnit4::class)
class NightProducerRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun unfinishedPartialJournalIsRecoveredBeforePublicNightProducerReadsInputs() = fixture { f ->
        val old = f.prepared()
        assertNotNull(f.open(old))
        val output = png(Color.GREEN)
        val store = PartialPageStore.forUniFile(context, f.chapter, f.materials)
        var interrupted = false
        try {
            store.commit(
                PAGE,
                PartialPageStore.Original(
                    OriginalMaterial.exactName(PAGE),
                    PartialPageStore.sha256(f.original),
                    f.original.size.toLong(),
                ),
                4, 4, "{}", output, png(Color.BLACK), null, complete = false,
                checkCancellation = {
                    if (f.sourceFile.readBytes().contentEquals(output)) throw InterruptedWrite()
                },
            )
        } catch (_: InterruptedWrite) {
            interrupted = true
        }
        assertTrue(interrupted)
        val provisional = f.revision()
        assertArrayEquals(output, f.sourceFile.readBytes())
        f.stopAtProgress {
            assertArrayEquals(f.original, f.sourceFile.readBytes())
            assertTrue(f.revision().committed)
            assertNotEquals(provisional.id, f.revision().id)
            assertNull(store.load(PAGE, recover = false))
            assertNull(f.open(old))
        }
        val settled = f.revision()
        assertNull(store.load(PAGE))
        assertEquals(settled, f.revision())
        f.protectedOriginal()
    }

    @Test
    fun unfinishedLegacyWriteIsRestoredBeforePublicNightProducerReadsInputs() = fixture { f ->
        val old = f.prepared()
        f.replaceVisible(png(Color.GREEN))
        val provisional = f.revision()
        val marker = File(f.materialsFile, OriginalMaterial.inflightName(PAGE)).apply { check(createNewFile()) }
        f.stopAtProgress {
            assertArrayEquals(f.original, f.sourceFile.readBytes())
            assertFalse(marker.exists())
            assertTrue(f.revision().committed)
            assertNotEquals(provisional.id, f.revision().id)
            assertNull(f.open(old))
        }
        assertFalse(File(f.chapterFile, CompletionManifestCodec.PRIMARY).exists())
        f.protectedOriginal()
    }

    @Test
    fun completedManifestPreventsLeftoverLegacyMarkerFromRestoringSuccessfulOutput() = fixture { f ->
        val output = png(Color.GREEN)
        f.replaceVisible(output)
        val completedRevision = f.revision()
        val manifest = File(f.chapterFile, CompletionManifestCodec.PRIMARY).apply {
            writeBytes(CompletionManifestCodec.encode(setOf(PAGE)))
        }
        val manifestBefore = manifest.readBytes()
        val marker = File(f.materialsFile, OriginalMaterial.inflightName(PAGE)).apply { check(createNewFile()) }
        f.stopAtProgress {
            assertArrayEquals(output, f.sourceFile.readBytes())
            assertEquals(completedRevision, f.revision())
            assertTrue(marker.isFile)
            assertArrayEquals(manifestBefore, manifest.readBytes())
        }
        f.protectedOriginal()
    }

    private inner class Fixture(val chapterFile: File) {
        val original = png(Color.WHITE)
        val sourceFile = File(chapterFile, PAGE).apply { writeBytes(original) }
        val materialsFile = File(chapterFile, NightPages.DIR).apply { check(mkdir()) }
        private val originalFile = File(materialsFile, OriginalMaterial.exactName(PAGE)).apply { writeBytes(original) }
        private val unrelated = File(materialsFile, "unrelated.keep").apply { writeText("neutral-owned-file") }
        val chapter = checkNotNull(UniFile.fromFile(chapterFile))
        val source = checkNotNull(chapter.findFile(PAGE))
        val materials = checkNotNull(chapter.findFile(NightPages.DIR))
        private val index = NightPages.Index()

        init {
            NightSourceRevision.ensureCommitted(context, chapter, materials, PAGE)
            NightPages.currentNames(PAGE).forEach { File(materialsFile, it).writeBytes(png(Color.BLUE)) }
            val witness = NightPolicy.snapshot(chapter, materials, PAGE)
            NightPolicy.names(PAGE, NightSourceRevision.newId(), moreDistinct = true).forEach { name ->
                File(materialsFile, name).writeBytes(NightPolicy.encode(checkNotNull(NightPolicy.parse(name)), witness))
            }
        }

        fun prepared(): NightPages.Index.PageFiles? {
            index.refreshPrepared(chapter, listOf(PAGE to source))
            return index.cachedPage(chapter, PAGE)
        }

        fun open(entry: NightPages.Index.PageFiles?): ByteArray? = try {
            entry?.let { NightPolicy.openCurrent(it.files.keys, PAGE, NightLevel.MORE, it.io) }
                ?.second?.use { it.readBytes() }
        } catch (_: IOException) {
            null
        }

        fun revision(): NightSourceRevision.Revision = NightSourceRevision.read(
            checkNotNull(materials.findFile(NightSourceRevision.fileName(PAGE))),
        )

        fun replaceVisible(bytes: ByteArray) {
            NightSourceRevision.mutate(context, chapter, materials, PAGE) {
                sourceFile.writeBytes(bytes)
                assertArrayEquals(bytes, sourceFile.readBytes())
            }
        }

        fun stopAtProgress(assertPrepared: () -> Unit) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val governor = NightGovernor(
                scope = scope,
                busy = MutableStateFlow(false),
                pagesCap = { 1 },
                heapFree = { 256L * 1024 * 1024 },
                maxMemory = 256L * 1024 * 1024,
                thermalThrottled = { false },
                setNice = { _, _ -> },
                myTid = { 0 },
                now = { System.nanoTime() / 1_000_000 },
                log = {},
                gc = {},
            )
            val sentinel = StopBeforeModels()
            var stopped: StopBeforeModels? = null
            try {
                try {
                    runBlocking {
                        PageTranslator(context).renderNightChapter(
                            chapter,
                            governor,
                            onProgress = { _, total ->
                                assertEquals(1, total)
                                assertPrepared()
                                throw sentinel
                            },
                        )
                    }
                } catch (caught: StopBeforeModels) {
                    stopped = caught
                }
                assertSame(
                    "Must reach the actual public producer progress callback before loading models",
                    sentinel,
                    stopped,
                )
            } finally {
                scope.cancel()
            }
        }

        fun protectedOriginal() {
            assertArrayEquals(original, originalFile.readBytes())
            assertEquals("neutral-owned-file", unrelated.readText())
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val cache = context.cacheDir.canonicalFile
        val root = File(cache, "night-producer-recovery-${UUID.randomUUID()}")
        check(root.parentFile == cache && root.mkdir())
        var success = false
        try {
            val chapter = File(root, "chapter").apply { check(mkdir()) }
            block(Fixture(chapter))
            success = true
        } finally {
            check(root.canonicalFile.parentFile == cache && root.name.startsWith("night-producer-recovery-"))
            if (success) check(root.deleteRecursively())
        }
    }

    private class InterruptedWrite : Error()
    private class StopBeforeModels : Error()

    companion object {
        private const val PAGE = "target.png"
        private fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return try {
                ByteArrayOutputStream().use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
