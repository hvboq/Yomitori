@file:OptIn(li.joye.yakuyomi.engine.InternalEngineApi::class)

package mihon.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.crash.TraceLog
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.AnalysisRgb
import li.joye.yakuyomi.engine.CharSegmenter
import li.joye.yakuyomi.engine.CsegPost
import li.joye.yakuyomi.engine.CsegSegmenter
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineTrace
import li.joye.yakuyomi.engine.NcnnFlavor
import li.joye.yakuyomi.engine.NightReadRenderer
import li.joye.yakuyomi.engine.UnionCharSegmenter
import li.joye.yakuyomi.engine.YoloSegPost
import li.joye.yakuyomi.engine.YoloSegSegmenter
import li.joye.yakuyomi.nightread.NightTier
import mihon.core.migration.Migrator
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Separate opt-in real model forwards in the idle, trace-disabled audit package only.
 * No download, model resolver/copy, translator, preferences write, reader UI or global hook changes.
 * Missing opt-in is SKIP; selected missing/mismatched weights fail. Optional model files are allowed.
 * Synthetic paired parity and real nonempty render output are not segmentation accuracy,
 * real-page quality, peak memory or performance evidence.
 */
@RunWith(AndroidJUnit4::class)
class NightReadAlphaNativeTest {
    @Test(timeout = 240_000)
    fun nativeCsegPairedAlphaAndCanvasWhiteInputsAgree() {
        enabled("cseg")
        nativeSession(setOf("cartoonseg")) { models ->
            owned(CsegSegmenter(models.param("cartoonseg"), models.bin("cartoonseg"), FLAVOR)) {
                pairedForward(it, cseg = true)
            }
        }
    }

    @Test(timeout = 240_000)
    fun nativeYoloPairedAlphaAndCanvasWhiteInputsAgree() {
        enabled("yolo")
        nativeSession(setOf("manga_seg_s")) { models ->
            owned(YoloSegSegmenter(models.param("manga_seg_s"), models.bin("manga_seg_s"), FLAVOR)) {
                pairedForward(it, cseg = false)
            }
        }
    }

    @Test(timeout = 300_000)
    fun nativeObjectNightRenderTiersSharesCheckpointsAndPairedOutputs() {
        assumeTrue(
            "Explicit complete native renderer requires nightReadAlphaNativeRenderer=true",
            InstrumentationRegistry.getArguments().getString("nightReadAlphaNativeRenderer") == "true",
        )
        // This test specifically exercises the shipped two-segmenter union. Either missing role fails.
        nativeSession(setOf("dbnet_detect", "cartoonseg", "manga_seg_s")) { models ->
            owned(Detector(models.param("dbnet_detect"), flavor = FLAVOR)) { detector ->
                owned(CsegSegmenter(models.param("cartoonseg"), models.bin("cartoonseg"), FLAVOR)) { cseg ->
                    owned(YoloSegSegmenter(models.param("manga_seg_s"), models.bin("manga_seg_s"), FLAVOR)) { yolo ->
                        UnionCharSegmenter(listOf(yolo, cseg)).use { segmenter ->
                            pairedPages { alpha, opaque ->
                                fun render(page: Bitmap): List<IntArray?> {
                                    var checkpoints = 0
                                    var beforeRender = 0
                                    val out = ArrayList<IntArray?>()
                                    val delivered = ArrayList<Bitmap>()
                                    assertTrue(
                                        NightReadRenderer.renderTiers(
                                            page,
                                            detector,
                                            segmenter,
                                            beforeInference = {
                                                checkpoints++
                                                requireTraceOff()
                                            },
                                            beforeRender = {
                                                beforeRender++
                                                true
                                            },
                                            tiers = listOf(NightTier.L2, NightTier.L3),
                                        ) { _, bitmap ->
                                            bitmap?.let {
                                                assertFalse(it.isRecycled)
                                                delivered += it
                                            }
                                            out += bitmap?.pixels()
                                        },
                                    )
                                    assertEquals(2, checkpoints)
                                    assertEquals(1, beforeRender)
                                    assertEquals(2, out.size)
                                    assertNotNull("The opaque control must actually render", out.first())
                                    assertTrue(
                                        "Opaque control must preserve observable structure",
                                        checkNotNull(out.first()).distinct().size > 1,
                                    )
                                    assertTrue(delivered.all { it.isRecycled })
                                    return out
                                }
                                render(opaque).zip(render(alpha)).forEach { (expected, actual) ->
                                    if (expected ==
                                        null
                                    ) {
                                        assertEquals(null, actual)
                                    } else {
                                        assertArrayEquals(expected, actual)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun enabled(model: String) {
        val selected = InstrumentationRegistry.getArguments().getString("nightReadAlphaNativeModels")
            ?.split(',')?.map { it.trim() }.orEmpty()
        assumeTrue("Explicit native test requires nightReadAlphaNativeModels=$model", model in selected)
    }

    private fun nativeSession(required: Set<String>, block: (File) -> Unit) {
        Executors.newSingleThreadExecutor { task -> Thread(task, "night-alpha-native") }
            .asCoroutineDispatcher().use { dispatcher ->
                runBlocking(dispatcher) {
                    Migrator.await()
                    val context = InstrumentationRegistry.getInstrumentation().targetContext
                    check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit package required" }
                    check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS) { "Real ARM64 process required" }
                    requireTraceOff()
                    NativeDiagnosticIdleGuard.requireIdle(context)
                    val models = File(context.filesDir.canonicalFile, "models")
                    requirePlain(models, context.filesDir.canonicalFile, directory = true)
                    val pins = PINS.filter { it.base in required }
                    check(pins.size == required.size * 2)
                    val before = fingerprints(models, pins)
                    try {
                        // No native constructor runs before isolation, trace, idle and role pin checks.
                        block(models)
                    } finally {
                        assertEquals(before, fingerprints(models, pins))
                        requireTraceOff()
                        NativeDiagnosticIdleGuard.requireIdle(context)
                    }
                }
            }
    }

    private fun pairedForward(segmenter: CharSegmenter, cseg: Boolean) = pairedPages { alpha, opaque ->
        // A non-vacuous input control: raw black transparent RGB differs, while the actual white
        // analysis tensor equals Canvas. An all-false native mask alone would not establish this.
        AnalysisRgb.acquire(alpha).use { normalized ->
            AnalysisRgb.acquire(opaque).use { borrowed ->
                assertSame(opaque, borrowed.bitmap)
                val raw = tensor(alpha, cseg)
                val reference = tensor(borrowed.bitmap, cseg)
                assertFalse("Fixture must distinguish the old raw-RGB path", raw.contentEquals(reference))
                assertArrayEquals(reference, tensor(normalized.bitmap, cseg), 0f)
            }
        }
        val reference = segmenter.segment(opaque)
        val actual = segmenter.segment(alpha)
        assertEquals(alpha.width * alpha.height, reference.size)
        assertEquals(alpha.width * alpha.height, actual.size)
        assertArrayEquals(reference, actual)
    }

    private fun tensor(bitmap: Bitmap, cseg: Boolean): FloatArray = if (cseg) {
        CsegPost.preprocess(bitmap.pixels(), bitmap.width, bitmap.height).chw
    } else {
        YoloSegPost.preprocess(bitmap.pixels(), bitmap.width, bitmap.height).chw
    }

    private fun pairedPages(block: (Bitmap, Bitmap) -> Unit) {
        val alpha = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888).apply { density = 240 }
        val opaque = Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888).apply { density = alpha.density }
        try {
            val paint = Paint().apply {
                color = Color.argb(128, 16, 80, 144)
                isAntiAlias = false
            }
            Canvas(alpha).apply {
                drawRect(8f, 8f, 88f, 120f, paint)
                paint.color = Color.BLACK
                drawRect(16f, 24f, 32f, 80f, paint)
                paint.color = Color.WHITE
                drawRect(48f, 24f, 80f, 104f, paint)
            }
            Canvas(opaque).apply {
                drawColor(Color.WHITE)
                drawBitmap(alpha, 0f, 0f, null)
            }
            // Intentionally keep opaque PNG-style alpha metadata; never mutate caller metadata.
            opaque.setHasAlpha(true)
            val alphaBefore = Snapshot(alpha)
            val opaqueBefore = Snapshot(opaque)
            try {
                block(alpha, opaque)
            } finally {
                alphaBefore.assertPreserved(alpha)
                opaqueBefore.assertPreserved(opaque)
            }
        } finally {
            alpha.recycle()
            opaque.recycle()
        }
    }

    private fun <T : AutoCloseable> owned(owner: T, block: (T) -> Unit) {
        val field = owner.javaClass.getDeclaredField(if (owner is Detector) "ncnnHandle" else "handle")
            .apply { isAccessible = true }
        try {
            check(field.getLong(owner) != 0L) { "Real model must be open" }
            block(owner)
        } finally {
            owner.close()
            assertEquals(0L, field.getLong(owner))
            owner.close()
            assertEquals(0L, field.getLong(owner))
        }
    }

    private class Snapshot(bitmap: Bitmap) {
        private val pixels = bitmap.pixels()
        private val png = bitmap.png()
        private val alpha = bitmap.hasAlpha()
        private val density = bitmap.density
        private val config = bitmap.config

        fun assertPreserved(bitmap: Bitmap) {
            assertFalse(bitmap.isRecycled)
            assertEquals(alpha, bitmap.hasAlpha())
            assertEquals(density, bitmap.density)
            assertEquals(config, bitmap.config)
            assertArrayEquals(pixels, bitmap.pixels())
            assertArrayEquals(png, bitmap.png())
        }
    }

    private fun requireTraceOff() {
        check(!TraceLog.isEnabled && EngineTrace.sink == null) { "Disable diagnostic trace before this isolated test" }
    }

    private fun fingerprints(directory: File, pins: List<Pin>): Map<String, String> = pins.associate { pin ->
        val file = File(directory, pin.name)
        requirePlain(file, directory, directory = false)
        check(file.length() == pin.bytes) { "Required model size mismatch: ${pin.name}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= pin.bytes)
                digest.update(buffer, 0, count)
            }
            check(total == pin.bytes && file.length() == pin.bytes)
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        check(sha == pin.sha) { "Required model SHA mismatch: ${pin.name}" }
        pin.name to sha
    }

    private fun requirePlain(file: File, parent: File, directory: Boolean) {
        check(file.canonicalFile == file.absoluteFile && file.parentFile == parent.canonicalFile) {
            "Noncanonical model path"
        }
        val status = Os.lstat(file.path)
        check(if (directory) OsConstants.S_ISDIR(status.st_mode) else OsConstants.S_ISREG(status.st_mode))
        if (!directory) check(status.st_nlink == 1L)
    }

    private fun File.param(base: String) = File(this, "$base.ncnn.param").absolutePath
    private fun File.bin(base: String) = File(this, "$base.ncnn.bin").absolutePath

    private data class Pin(val base: String, val suffix: String, val bytes: Long, val sha: String) {
        val name get() = "$base.ncnn.$suffix"
    }

    companion object {
        private val FLAVOR = NcnnFlavor(numThreads = 1, serialize = true)

        // Individual roles copied from engine/models.json; unrelated optional files are permitted.
        private val PINS = listOf(
            Pin("dbnet_detect", "param", 13392, "9e6db2f8c6b0662ab00eb2100b3373d3c984a235eaac0e61c0b2a484ee1ff7b5"),
            Pin("dbnet_detect", "bin", 153010556, "f57bdbede7764a534c56e88be0269602259a7fcd47e54e8b7d954fd0fcc55c3d"),
            Pin("cartoonseg", "param", 37237, "e259a57af1dba1149d07fe5f1d096548a78126a695f6708e8217e6a7284b6266"),
            Pin("cartoonseg", "bin", 126418212, "f3bf313fafb21477ffb6aefbbfbafb3d8b96815eff4da153fd3bf2a01cc2d83f"),
            Pin("manga_seg_s", "param", 25188, "b0ed82b88d574b2a36d15cbcb39959091258d341c6d3528199bef06a8bea16cc"),
            Pin("manga_seg_s", "bin", 20426200, "680ddd6886588fbe128b77e0cebc9036534eb865b226466c0e0e8677a622d71a"),
        )

        private fun Bitmap.pixels(): IntArray = IntArray(width * height).also {
            getPixels(it, 0, width, 0, 0, width, height)
        }

        private fun Bitmap.png(): ByteArray = ByteArrayOutputStream().use {
            check(compress(Bitmap.CompressFormat.PNG, 100, it))
            it.toByteArray()
        }
    }
}
