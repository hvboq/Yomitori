package mihon.translation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.nightread.NightLevel
import eu.kanade.tachiyomi.data.nightread.NightPages
import eu.kanade.tachiyomi.data.translation.PageTranslator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.UUID

/** Private night-file ownership integration only; no renderer, native models, Activity or HTTP. */
@RunWith(AndroidJUnit4::class)
class NightCaptureOwnershipTest {
    @Test(timeout = 15_000)
    fun staleNightRoundCannotDeleteRecapturedGenerationTemporaryOrCommittedImages(): Unit = runBlocking {
        withFixture { fixture ->
            val originalPage = fixture.page.readBytes()
            val oldRound = fixture.round()
            fixture.write(oldRound, NightLevel.STANDARD, Color.BLUE)
            val oldTemporary = fixture.temporary.readBytes()
            val sibling = fixture.sibling.readBytes()

            fixture.translator.replaceCapturedSource(fixture.chapter, PAGE, originalPage)

            assertArrayEquals(originalPage, fixture.page.readBytes())
            assertFalse("Capture must retire the old night temporary", fixture.temporary.exists())
            val archives = fixture.archives()
            assertTrue(
                "Old temporary bytes must remain archived",
                archives.values.any {
                    it.contentEquals(oldTemporary)
                },
            )
            val committedRound = fixture.round()
            fixture.write(committedRound, NightLevel.STANDARD, Color.RED)
            fixture.write(committedRound, NightLevel.MORE, Color.MAGENTA)
            fixture.owned(committedRound) { assertEquals(true, committedRound.call("commit")) }
            val standard = File(fixture.materials, NightPages.levelName(PAGE, NightLevel.STANDARD))
            val more = File(fixture.materials, NightPages.levelName(PAGE, NightLevel.MORE))
            val committedStandard = standard.readBytes()
            val committedMore = more.readBytes()
            val newRound = fixture.round()
            fixture.write(newRound, NightLevel.STANDARD, Color.GREEN)
            val newerTemporary = fixture.temporary.readBytes()
            assertFalse(oldTemporary.contentEquals(newerTemporary))

            oldRound.call("discard")

            assertArrayEquals(newerTemporary, fixture.temporary.readBytes())
            assertArrayEquals(committedStandard, standard.readBytes())
            assertArrayEquals(committedMore, more.readBytes())
            assertArrayEquals(sibling, fixture.sibling.readBytes())
            fixture.assertArchivesPreserved(archives)
            assertArrayEquals(originalPage, fixture.page.readBytes())
            // A second stale cleanup must remain harmless after its internal ownership is cleared.
            oldRound.call("discard")
            assertArrayEquals(newerTemporary, fixture.temporary.readBytes())
            fixture.assertArchivesPreserved(archives)
        }
    }

    @Test(timeout = 15_000)
    fun busyNightDiscardPreservesFilesThenRemovesOnlyItsOwnTemporaryAfterUnlock(): Unit = runBlocking {
        withFixture { fixture ->
            val round = fixture.round()
            fixture.write(round, NightLevel.STANDARD, Color.BLUE)
            val ownTemporary = fixture.temporary.readBytes()
            val before = fixture.snapshot()
            val otherOwner = Any()
            fixture.lock.lock(otherOwner)
            try {
                assertTrue(fixture.lock.holdsLock(otherOwner))
                assertFalse(fixture.lock.holdsLock(round.instance))

                round.call("discard")

                fixture.assertExactSnapshot(before)
                assertArrayEquals(ownTemporary, fixture.temporary.readBytes())
                assertTrue("Discard must not unlock another owner", fixture.lock.holdsLock(otherOwner))
            } finally {
                fixture.lock.unlock(otherOwner)
            }

            round.call("discard")

            assertFalse("Uncontended same-generation cleanup must remove its temporary", fixture.temporary.exists())
            val remaining = before.filterKeys { it != ".yakuyomi/${fixture.temporary.name}" }
            fixture.assertExactSnapshot(remaining)
            round.call("discard")
            fixture.assertExactSnapshot(remaining)
        }
    }

    private suspend fun withFixture(block: suspend (Fixture) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName == "app.yomitori.yakuyomi.audit") {
            "Night capture ownership requires the isolated audit installation"
        }
        val directory = File(target.cacheDir, "night-capture-owner-${UUID.randomUUID()}").apply { check(mkdir()) }
        check(directory.canonicalFile.parentFile == target.cacheDir.canonicalFile)
        try {
            block(Fixture(directory, PageTranslator(target)))
        } finally {
            check(directory.canonicalFile.parentFile == target.cacheDir.canonicalFile)
            assertTrue("Cannot remove owned night ownership fixture", directory.deleteRecursively())
        }
    }

    private class Fixture(val directory: File, val translator: PageTranslator) {
        val page = File(directory, PAGE).apply { writeBytes(encoded(Color.WHITE)) }
        val materials = File(directory, ".yakuyomi").apply { check(mkdir()) }
        val chapter = requireNotNull(UniFile.fromFile(directory))
        private val materialDirectory = requireNotNull(UniFile.fromFile(materials))
        private val sourcePage = requireNotNull(UniFile.fromFile(page))
        val temporary = File(materials, NightPages.tmpName(NightPages.levelName(PAGE, NightLevel.STANDARD)))
        val sibling = File(materials, NightPages.tmpName("sibling.png.night.std.webp")).apply {
            writeBytes(encoded(Color.GRAY))
        }
        val lock = PageTranslator.Companion.javaClass.getDeclaredMethod("pageLock", UniFile::class.java)
            .apply { isAccessible = true }.invoke(PageTranslator.Companion, sourcePage) as Mutex

        fun round(): Round {
            val type = Class.forName("eu.kanade.tachiyomi.data.translation.PageTranslator\$NightRound")
            val constructor = type.getDeclaredConstructor(
                PageTranslator::class.java,
                UniFile::class.java,
                UniFile::class.java,
                UniFile::class.java,
                String::class.java,
                Bitmap.CompressFormat::class.java,
            ).apply { isAccessible = true }
            val instance = constructor.newInstance(
                translator,
                chapter,
                materialDirectory,
                sourcePage,
                PAGE,
                Bitmap.CompressFormat.WEBP_LOSSLESS,
            )
            val round = Round(instance)
            owned(round) {
                val source = PageTranslator::class.java.getDeclaredMethod(
                    "sourceToken",
                    UniFile::class.java,
                    UniFile::class.java,
                    String::class.java,
                ).apply { isAccessible = true }.invoke(translator, chapter, sourcePage, PAGE)
                type.getDeclaredMethod("bindSource", source.javaClass).apply { isAccessible = true }
                    .invoke(instance, source)
            }
            return round
        }

        fun write(round: Round, level: NightLevel, color: Int) {
            val bitmap = image(color)
            try {
                owned(round) { round.call("write", level, bitmap) }
            } finally {
                bitmap.recycle()
            }
        }

        fun owned(round: Round, block: () -> Unit) {
            check(lock.tryLock(round.instance)) { "Unexpected busy fixture page lock" }
            try {
                block()
            } finally {
                lock.unlock(round.instance)
            }
        }

        fun snapshot(): Map<String, ByteArray> = directory.walkTopDown().filter { it.isFile }.associate {
            it.relativeTo(directory).invariantSeparatorsPath to it.readBytes()
        }

        fun archives(): Map<String, ByteArray> = snapshot().filterKeys { it.startsWith(".yakuyomi/capture-") }

        fun assertArchivesPreserved(before: Map<String, ByteArray>) {
            assertTrue("Capture must leave private archive evidence", before.isNotEmpty())
            for ((name, bytes) in before) assertArrayEquals(name, bytes, File(directory, name).readBytes())
        }

        fun assertExactSnapshot(before: Map<String, ByteArray>) {
            assertEquals(before.keys, snapshot().keys)
            for ((name, bytes) in before) assertArrayEquals(name, bytes, File(directory, name).readBytes())
        }
    }

    private class Round(val instance: Any) {
        fun call(name: String, vararg arguments: Any): Any? {
            val parameters = arguments.map { it.javaClass }.toTypedArray()
            val method = instance.javaClass.getDeclaredMethod(name, *parameters).apply { isAccessible = true }
            return try {
                method.invoke(instance, *arguments)
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        }
    }

    companion object {
        private const val PAGE = "target.png"
        private const val SIZE = 32
        private fun image(color: Int) = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
        }
        private fun encoded(color: Int): ByteArray {
            val bitmap = image(color)
            return try {
                ByteArrayOutputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                    it.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
