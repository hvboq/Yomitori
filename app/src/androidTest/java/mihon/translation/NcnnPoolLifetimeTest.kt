package mihon.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import li.joye.yakuyomi.engine.InternalEngineApi
import li.joye.yakuyomi.engine.NcnnBackend
import li.joye.yakuyomi.engine.NcnnFlavor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Local, weightless models exercise native pool ownership without server/model downloads. */
@RunWith(AndroidJUnit4::class)
@OptIn(InternalEngineApi::class)
class NcnnPoolLifetimeTest {
    @Test
    fun repeatedAndConcurrentForwardsPreserveCopiedOutputs() = withReluNet { handle ->
        fun forward(seed: Int): Pair<FloatArray, FloatArray> {
            val width = 8 + seed % 9
            val height = 7 + seed % 5
            val input = FloatArray(width * height * 3) { ((it + seed) % 33 - 16).toFloat() }
            val expected = FloatArray(input.size) { input[it].coerceAtLeast(0f) }
            val output = FloatArray(input.size)
            assertEquals(
                0,
                NcnnBackend.extract(
                    handle,
                    input,
                    width,
                    height,
                    3,
                    arrayOf("out0"),
                    arrayOf(output),
                    NcnnFlavor(numThreads = 1, serialize = false),
                ),
            )
            assertArrayEquals(expected, output, 0f)
            return expected to output
        }

        val retained = (0 until 16).map(::forward)
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until 4).map { worker ->
                executor.submit(
                    Callable {
                        check(start.await(10, TimeUnit.SECONDS))
                        (0 until 32).map { forward(worker * 32 + it) }
                    },
                )
            }
            start.countDown()
            val parallelResults = futures.flatMap { it.get(60, TimeUnit.SECONDS) }
            // JNI arrays must remain valid after every forward's private allocator has died and
            // subsequent/concurrent calls have reused native memory.
            (retained + parallelResults).forEach { (expected, actual) -> assertArrayEquals(expected, actual, 0f) }
        } finally {
            executor.shutdown()
            // Do not release the shared Net while a native forward is still executing.
            while (!executor.awaitTermination(1, TimeUnit.SECONDS)) { /* bounded tiny forwards */ }
        }
    }

    @Test
    fun aotRejectsInvalidBuffersBeforeNativeModelAccess() = withReluNet { handle ->
        assertEquals(-2, NcnnBackend.inpaintAot(handle, FloatArray(0), FloatArray(0), 0, FloatArray(0)))
        assertEquals(-2, NcnnBackend.inpaintAot(handle, FloatArray(11), FloatArray(4), 2, FloatArray(12)))
        assertEquals(-2, NcnnBackend.inpaintAot(handle, FloatArray(12), FloatArray(3), 2, FloatArray(12)))
        assertEquals(-2, NcnnBackend.inpaintAot(handle, FloatArray(12), FloatArray(4), 2, FloatArray(11)))
        // Correct buffers but a model without AOT's second input must fail rather than copy empty output.
        assertEquals(-3, NcnnBackend.inpaintAot(handle, FloatArray(12), FloatArray(4), 2, FloatArray(12)))
    }

    private fun withReluNet(block: (Long) -> Unit) {
        assertTrue("NCNN library unavailable", NcnnBackend.available)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "ncnn-pool-test-${System.nanoTime()}")
        check(directory.mkdir())
        var handle = 0L
        try {
            val param = File(directory, "relu.param").apply {
                writeText("7767517\n2 2\nInput input 0 1 in0\nReLU relu 1 1 in0 out0 0=0\n")
            }
            val bin = File(directory, "relu.bin").apply { writeBytes(byteArrayOf()) }
            handle = NcnnBackend.createNetEx(param.path, bin.path, 1, false, false)
            assertTrue("Could not create weightless test model", handle != 0L)
            block(handle)
        } finally {
            if (handle != 0L) NcnnBackend.releaseNet(handle)
            directory.deleteRecursively()
        }
    }
}
