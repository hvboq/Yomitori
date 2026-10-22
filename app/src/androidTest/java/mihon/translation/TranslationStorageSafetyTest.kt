package mihon.translation

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.translation.PageMaterials
import eu.kanade.tachiyomi.data.translation.PageTranslator
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import li.joye.yakuyomi.engine.PageAnalysis
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.Pt
import li.joye.yakuyomi.engine.TextLine
import li.joye.yakuyomi.engine.TextRegion
import li.joye.yakuyomi.engine.TranslationEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class TranslationStorageSafetyTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun withPage(block: suspend (File, Bitmap, Bitmap, PageTranslator) -> Unit) {
        val dir = File(context.cacheDir, "storage-safety-${System.nanoTime()}").apply { mkdirs() }
        val original = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val output = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK) }
        File(dir, "001.png").outputStream().use { assertTrue(original.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        try {
            block(dir, original, output, PageTranslator(context))
        } finally {
            original.recycle()
            output.recycle()
            dir.deleteRecursively()
        }
    }

    private suspend fun save(dir: File, original: Bitmap, output: Bitmap, translator: PageTranslator): Boolean =
        translator.persistLivePage(
            requireNotNull(UniFile.fromFile(dir)),
            requireNotNull(UniFile.fromFile(File(dir, "001.png"))),
            original,
            output,
            null,
            "boxfill",
        )

    @Test
    fun missingRecoveryMarkerPreventsReplacement() = runBlocking {
        withPage { dir, original, output, translator ->
            val bytes = File(dir, "001.png").readBytes()
            File(dir, ".yakuyomi/001.orig.png.wip").apply {
                mkdirs()
                File(this, "blocker").writeText("not a recovery marker")
            }
            assertFalse(save(dir, original, output, translator))
            assertArrayEquals(bytes, File(dir, "001.png").readBytes())
            assertEquals("not a recovery marker", File(dir, ".yakuyomi/001.orig.png.wip/blocker").readText())
        }
    }

    @Test
    fun manifestFailureRetainsOriginalAndRecoveryMarkerThenRetryRecovers() = runBlocking {
        withPage { dir, original, output, translator ->
            val bytes = File(dir, "001.png").readBytes()
            val blocker = File(dir, ".yakuyomi_translated").apply { mkdir() }
            assertFalse(save(dir, original, output, translator))
            assertFalse(translator.isPageTranslated(requireNotNull(UniFile.fromFile(dir)), "001.png"))
            assertArrayEquals(bytes, File(dir, ".yakuyomi/001.orig.png").readBytes())
            assertTrue(File(dir, ".yakuyomi/001.orig.png.wip").isFile)
            assertTrue(blocker.delete())
            val recover = PageTranslator::class.java.getDeclaredMethod(
                "recoverInterrupted",
                UniFile::class.java,
                UniFile::class.java,
                String::class.java,
            ).apply { isAccessible = true }
            assertEquals(
                true,
                recover.invoke(translator, UniFile.fromFile(dir), UniFile.fromFile(File(dir, "001.png")), "001.png"),
            )
            assertArrayEquals(bytes, File(dir, "001.png").readBytes())
            assertTrue(save(dir, original, output, translator))
            assertArrayEquals(bytes, File(dir, ".yakuyomi/001.orig.png").readBytes())
            assertFalse(File(dir, ".yakuyomi/001.orig.png.wip").exists())
        }
    }

    @Test
    fun repeatTranslationWithoutOptionalMaterialsPreservesOriginal() = runBlocking {
        withPage { dir, original, output, translator ->
            val bytes = File(dir, "001.png").readBytes()
            assertTrue(save(dir, original, output, translator))
            assertTrue(save(dir, original, output, translator))
            assertArrayEquals(bytes, File(dir, ".yakuyomi/001.orig.png").readBytes())
            assertTrue(
                dir.listFiles().orEmpty().any {
                    it.name.startsWith(".yakuyomi_translated.checkpoint-") && it.readText().contains("001.png")
                },
            )
            // A damaged primary manifest cannot forget previously committed pages.
            File(dir, ".yakuyomi_translated").writeText("")
            assertTrue(translator.isPageTranslated(requireNotNull(UniFile.fromFile(dir)), "001.png"))
        }
    }

    @Test
    fun reservedOriginalDirectoriesAndTheirContentsAreNeverDeleted() = runBlocking {
        for (reserved in listOf("001.orig.png.tmp", "001.orig.png", "001.orig.webp")) {
            withPage { dir, original, output, translator ->
                val bytes = File(dir, "001.png").readBytes()
                val sentinel = File(dir, ".yakuyomi/$reserved/keep.txt").apply {
                    parentFile!!.mkdirs()
                    writeText("user data")
                }
                val saved = save(dir, original, output, translator)
                if (reserved != "001.orig.webp") {
                    assertFalse(saved)
                    assertArrayEquals(bytes, File(dir, "001.png").readBytes())
                } else {
                    assertTrue(saved)
                }
                assertEquals("user data", sentinel.readText())
            }
        }
    }

    @Test
    fun damagedPrimaryAndAnotherFailedSaveCannotDestroyCheckpointOrInventCompletedPages() = runBlocking {
        withPage { dir, original, output, translator ->
            assertTrue(save(dir, original, output, translator))
            val chapter = requireNotNull(UniFile.fromFile(dir))
            val checkpoint = dir.listFiles()!!.single { it.name.startsWith(".yakuyomi_translated.checkpoint-") }
            val savedCheckpoint = checkpoint.readBytes()
            val primary = File(dir, ".yakuyomi_translated")
            // A partial/plain-looking primary must not override the verified checkpoint.
            primary.writeText("001.png\n002.png")
            File(dir, "002.png").outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(translator.isPageTranslated(chapter, "001.png"))
            assertFalse(translator.isPageTranslated(chapter, "002.png"))
            assertTrue(primary.delete())
            assertTrue(primary.mkdir()) // Actual subsequent primary write failure.
            assertFalse(
                translator.persistLivePage(
                    chapter,
                    requireNotNull(UniFile.fromFile(File(dir, "002.png"))),
                    original,
                    output,
                    null,
                    "boxfill",
                ),
            )
            assertArrayEquals(savedCheckpoint, checkpoint.readBytes())
            assertTrue(translator.isPageTranslated(chapter, "001.png"))
            assertFalse(translator.isPageTranslated(chapter, "002.png"))
            File(dir, ".yakuyomi_translated.checkpoint-${java.util.UUID.randomUUID()}").writeBytes(
                savedCheckpoint.copyOf(savedCheckpoint.size - 1),
            )
            assertTrue(translator.isPageTranslated(chapter, "001.png"))
            assertFalse(translator.isChapterTranslated(chapter))
        }
    }

    @Test
    fun legacyAndVerifiedArchiveManifestsRequireAllPagesAndRecoverFromCheckpoints() = runBlocking {
        withPage { dir, original, output, translator ->
            val chapter = requireNotNull(UniFile.fromFile(dir))
            File(dir, ".yakuyomi_translated").writeText("001.png")
            assertTrue(translator.isChapterTranslated(chapter)) // Existing plain-text format.
            assertTrue(save(dir, original, output, translator)) // Migrate it.
            val checkpoint = dir.listFiles()!!.single { it.name.startsWith(".yakuyomi_translated.checkpoint-") }
            fun archive(extension: String, entries: Map<String, ByteArray>): UniFile {
                val file = File(dir, "test.$extension")
                ZipOutputStream(file.outputStream()).use { zip ->
                    entries.forEach { (name, bytes) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
                return requireNotNull(UniFile.fromFile(file))
            }
            val pageBytes = File(dir, "001.png").readBytes()
            for (extension in listOf("zip", "cbz")) {
                val images = mapOf("001.png" to pageBytes, "002.png" to pageBytes)
                assertFalse(
                    translator.isArchiveTranslated(
                        archive(
                            extension,
                            images +
                                (".yakuyomi_translated" to "001.png".toByteArray()),
                        ),
                    ),
                )
                assertTrue(
                    translator.isArchiveTranslated(
                        archive(
                            extension,
                            images +
                                (".yakuyomi_translated" to "001.png\n002.png".toByteArray()),
                        ),
                    ),
                )
                assertTrue(
                    translator.isArchiveTranslated(
                        archive(
                            extension,
                            mapOf(
                                "001.png" to pageBytes,
                                checkpoint.name to checkpoint.readBytes(),
                                ".yakuyomi_translated" to "corrupt".toByteArray(),
                            ),
                        ),
                    ),
                )
                assertFalse(
                    translator.isArchiveTranslated(
                        archive(
                            extension,
                            images +
                                (checkpoint.name to checkpoint.readBytes()),
                        ),
                    ),
                )
                for (bad in listOf("a/001.png", "../001.png", "/001.png", "C:/001.png", "a\\001.png")) {
                    assertFalse(
                        translator.isArchiveTranslated(
                            archive(
                                extension,
                                mapOf(
                                    "001.png" to pageBytes,
                                    bad to pageBytes,
                                    ".yakuyomi_translated" to "001.png".toByteArray(),
                                ),
                            ),
                        ),
                    )
                }
                assertFalse(
                    translator.isArchiveTranslated(
                        archive(
                            extension,
                            mapOf(
                                "a/001.png" to pageBytes,
                                "b/001.png" to pageBytes,
                                ".yakuyomi_translated" to "001.png".toByteArray(),
                            ),
                        ),
                    ),
                )
            }
        }
    }

    @Test
    fun excludedRegionsSurviveMaterialPersistenceAndReRender() = runBlocking {
        withPage { dir, original, output, translator ->
            val line = TextLine(listOf(Pt(1f, 1f), Pt(10f, 1f), Pt(10f, 10f), Pt(1f, 10f)), 1f)
            val region = TextRegion(listOf(line), "h", 0f, 5f, 5f, 9f, 9f).apply {
                preserveOriginal = true
            }
            val mask = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try {
                val chapter = requireNotNull(UniFile.fromFile(dir))
                assertTrue(
                    translator.persistLivePage(
                        chapter,
                        requireNotNull(UniFile.fromFile(File(dir, "001.png"))),
                        original,
                        output,
                        PageAnalysis(mask, listOf(region)),
                        "boxfill",
                    ),
                )
                fun materials() = Json.decodeFromString<PageMaterials>(File(dir, ".yakuyomi/001.json").readText())
                assertTrue(materials().regions.single().preserveOriginal)
                val restore = PageTranslator::class.java.getDeclaredMethod(
                    "regionsFromMaterials",
                    PageMaterials::class.java,
                )
                    .apply { isAccessible = true }

                @Suppress("UNCHECKED_CAST")
                val restored = restore.invoke(translator, materials()) as List<TextRegion>
                assertTrue(restored.single().preserveOriginal)
                assertTrue(translator.reRenderPage(chapter, "001.png", PageTranslator.ORIGINAL_METHOD))
                assertTrue(materials().regions.single().preserveOriginal)
                // Existing materials written before this flag still load.
                val legacy = File(dir, ".yakuyomi/001.json").readText().replace(
                    ",\"preserveOriginal\":true",
                    "",
                )
                assertFalse(Json.decodeFromString<PageMaterials>(legacy).regions.single().preserveOriginal)
            } finally {
                mask.recycle()
            }
        }
    }

    @Test
    fun changedConfigurationWaitsForExistingCallsAndCancellationDoesNotReuseOldEngine() = runBlocking {
        val calls = AtomicInteger()
        val closes = AtomicInteger()
        val service = TranslationEngineService(context)
        val fake = object : TranslationEngine {
            override suspend fun translatePage(page: Bitmap): PageResult {
                calls.incrementAndGet()
                return PageResult.Failed("Old endpoint must not receive this request")
            }
            override fun warmUp() = Unit
            override fun close() {
                closes.incrementAndGet()
            }
        }
        fun field(name: String) = TranslationEngineService::class.java.getDeclaredField(name).apply {
            isAccessible =
                true
        }
        field("engine").set(service, fake)
        field("builtSignature").set(service, "previous-endpoint-and-key")
        field("warmedUp").setBoolean(service, true)
        val inFlight = field("inFlight").get(service) as AtomicInteger
        inFlight.set(1)
        val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        try {
            val request = async(start = CoroutineStart.UNDISPATCHED) { service.translatePage(bitmap, "boxfill") }
            assertTrue(request.isActive)
            request.cancelAndJoin()
            assertEquals(0, calls.get())
            assertEquals(0, closes.get())
            assertEquals(1, inFlight.get())
        } finally {
            inFlight.set(0)
            service.shutdown()
            bitmap.recycle()
        }
    }
}
