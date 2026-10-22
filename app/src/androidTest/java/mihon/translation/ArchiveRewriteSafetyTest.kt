package mihon.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.TranslationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

@RunWith(AndroidJUnit4::class)
class ArchiveRewriteSafetyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun realManagerPreservesArchiveBackupMaterialsAndExistingTemporaryDirectory() = runBlocking<Unit> {
        val directory = File(context.cacheDir, "archive-android-${System.nanoTime()}").apply { mkdirs() }
        try {
            val archive = File(directory, "chapter.cbz")
            val originalPage = byteArrayOf(1, 2, 3, 4)
            zip(archive, mapOf("001.jpg" to originalPage))
            val originalArchive = archive.readBytes()
            val unrelated = File(directory, "chapter.cbz.yakutmp").apply { mkdirs() }
            File(unrelated, "keep.txt").writeText("user data")
            val result = rewrite(archive) { extracted ->
                val path = File(checkNotNull(extracted.uri.path))
                assertArrayEquals(originalPage, File(path, "001.jpg").readBytes())
                File(path, ".yakuyomi").mkdir()
                File(path, ".yakuyomi/001.orig.jpg").writeBytes(originalPage)
                File(path, "001.jpg").writeBytes(byteArrayOf(9, 8))
            }
            assertTrue(result)
            assertEquals("user data", File(unrelated, "keep.txt").readText())
            val backup = directory.listFiles()!!.single { it.name.startsWith("chapter.cbz.yomitori-original-") }
            assertArrayEquals(originalArchive, backup.readBytes())
            ZipFile(archive).use { packed ->
                assertArrayEquals(
                    originalPage,
                    packed.getInputStream(packed.getEntry(".yakuyomi/001.orig.jpg")).use {
                        it.readBytes()
                    },
                )
                assertArrayEquals(
                    byteArrayOf(9, 8),
                    packed.getInputStream(packed.getEntry("001.jpg")).use {
                        it.readBytes()
                    },
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun unsupportedNestedArchiveFailsBeforeProcessingAndKeepsOriginalBytes() = runBlocking<Unit> {
        val directory = File(context.cacheDir, "archive-nested-${System.nanoTime()}").apply { mkdirs() }
        try {
            val archive = File(directory, "chapter.cbz")
            zip(archive, mapOf("a/001.jpg" to byteArrayOf(1), "b/001.jpg" to byteArrayOf(2)))
            val original = archive.readBytes()
            try {
                rewrite(archive) { fail("Unsupported nested archive must not reach processing") }
                fail("Nested archive must fail explicitly")
            } catch (_: IllegalArgumentException) {
                assertArrayEquals(original, archive.readBytes())
                assertEquals(listOf("chapter.cbz"), directory.list()!!.toList())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private suspend fun rewrite(archive: File, process: (UniFile) -> Unit): Boolean {
        val manager = TranslationManager(context)
        val resultClass = TranslationManager::class.java.declaredClasses.single { it.simpleName == "ProcessResult" }
        val resultConstructor = resultClass.getDeclaredConstructor(
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        )
            .apply { isAccessible = true }
        val callback: suspend (UniFile) -> Any = { directory ->
            process(directory)
            resultConstructor.newInstance(true, true)
        }
        val method = TranslationManager::class.java.declaredMethods.single { it.name == "processArchiveInPlace" }
            .apply { isAccessible = true }
        return suspendCoroutineUninterceptedOrReturn { continuation ->
            try {
                method.invoke(manager, UniFile.fromFile(archive), { _: Int, _: Int ->
                }, { false }, callback, continuation)
            } catch (error: InvocationTargetException) {
                throw checkNotNull(error.cause)
            }
        }
    }

    private fun zip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(bytes)
                output.closeEntry()
            }
        }
    }
}
