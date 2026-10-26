package mihon.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException

/** Synthetic parser regressions only: no proc, models, native inference, network, settings or file writes. */
@RunWith(AndroidJUnit4::class)
class BoundedSmapsReaderTest {
    @Before
    fun requireAudit() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName == "app.yomitori.yakuyomi.audit")
    }

    @Test
    fun completeSnapshotsAreIndependentOfChunkAndUtf8Boundaries() {
        val first = mapping("[heap]", listOf(8, 6, 4, 1, 2, 3, 0))
        val second = mapping("/fixture/모델.bin", listOf(16, 10, 7, 3, 4, 5, 1), "2000-3000")
        val ignoredKernelFields = "VmFlags: rd wr mr mw me ac sd\n".repeat(350)
        val bytes = (first + ignoredKernelFields + second).toByteArray()
        for (chunk in listOf(1, 2, 3, 7, 63, 8191, 8192)) {
            val input = ChunkedInput(bytes, chunk)
            val result = read(input)
            assertTrue(result.available && result.complete)
            assertEquals(bytes.size, result.bytesRead)
            assertEquals(2, result.mapsRead)
            assertEquals(listOf(24L, 16L, 11L, 4L, 6L, 8L, 1L), result.totalsKiB)
            val buckets = checkNotNull(result.buckets)
            assertEquals(BoundedSmapsReader.BUCKETS.toSet(), buckets.keys)
            assertEquals(1, buckets.getValue("allocatorNamed").maps)
            assertEquals(1, buckets.getValue("otherFileNamed").maps)
            assertEquals(2, buckets.values.sumOf { it.maps })
            assertNull(result.unavailableClass)
            assertNull(result.incompleteReason)
            assertFalse(result.toString().contains("모델.bin"))
            assertEquals(1, input.closes)
            assertEquals(0, input.singleByteReads)
        }
    }

    @Test
    fun truncatedMissingDuplicateAndMalformedMapsNeverPublishTotals() {
        val valid = mapping()
        val corrupt = listOf(
            valid.dropLast(1) to "incomplete-stream",
            valid.replace("Rss: 6 kB\n", "") to "missing-required-metric",
            (valid + "Rss: 6 kB\n") to "malformed-map",
            valid.replace("Rss: 6 kB", "Rss: -1 kB") to "malformed-metric",
            valid.replace("Rss: 6 kB", "Rss: 6 KB") to "malformed-metric",
            "Rss: 6 kB\n" to "malformed-map",
            "not-a-mapping\n" to "missing-header",
            "" to "incomplete-stream",
        )
        for ((text, reason) in corrupt) {
            val input = ChunkedInput(text.toByteArray(), 3)
            incomplete(read(input), reason)
            assertEquals(1, input.closes)
        }
        assertTrue(read(ChunkedInput(valid.toByteArray(), 1)).complete)
    }

    @Test
    fun byteLineMapAndArithmeticLimitsCannotProducePartialSuccess() {
        val bytes = mapping().toByteArray()
        val exact = read(ChunkedInput(bytes), limits = BoundedSmapsReader.Limits(byteLimit = bytes.size))
        assertTrue(exact.complete)
        val capped = read(ChunkedInput(bytes), limits = BoundedSmapsReader.Limits(byteLimit = bytes.size - 1))
        incomplete(capped, "byte-limit")
        assertEquals(bytes.size, capped.bytesRead) // At most limit+1 physical bytes, never another 8KiB block.
        incomplete(
            read(ChunkedInput("x".repeat(33).toByteArray()), limits = BoundedSmapsReader.Limits(lineByteLimit = 32)),
            "line-limit",
        )
        assertTrue(read(ChunkedInput((mapping() + "x".repeat(8192) + "\n").toByteArray())).complete)
        incomplete(read(ChunkedInput((mapping() + "x".repeat(8193) + "\n").toByteArray())), "line-limit")
        assertTrue(read(ChunkedInput(bytes), limits = BoundedSmapsReader.Limits(mapLimit = 1)).complete)
        incomplete(
            read(
                ChunkedInput((mapping() + mapping(address = "2000-3000")).toByteArray()),
                limits = BoundedSmapsReader.Limits(mapLimit = 1),
            ),
            "map-limit",
        )
        val maximum = mapping().replace("Rss: 6 kB", "Rss: ${Long.MAX_VALUE} kB")
        for (name in listOf("[heap]", "/fixture/model.bin")) {
            val overflow = maximum + mapping(name = name, address = "2000-3000")
                .replace("Rss: 6 kB", "Rss: 1 kB")
            val result = read(ChunkedInput(overflow.toByteArray()))
            incomplete(result)
            assertEquals("ArithmeticException", result.unavailableClass)
        }
        assertRejected { BoundedSmapsReader.Limits(byteLimit = BoundedSmapsReader.BYTE_LIMIT + 1) }
        assertRejected { BoundedSmapsReader.Limits(lineByteLimit = BoundedSmapsReader.LINE_BYTE_LIMIT + 1) }
        assertRejected { BoundedSmapsReader.Limits(mapLimit = BoundedSmapsReader.MAP_LIMIT + 1) }
        assertRejected { BoundedSmapsReader.Limits(timeLimitMs = BoundedSmapsReader.TIME_LIMIT_MS + 1) }
    }

    @Test
    fun deadlineIncludesSlowOpenReadEofClassifierAndClose() {
        val bytes = mapping().toByteArray()
        for (boundary in listOf("open", "read", "eof", "classifier", "close")) {
            var time = 0L
            fun expire() {
                time = 11_000_000L
            }
            val input = ChunkedInput(bytes).apply {
                afterRead = { count ->
                    if ((boundary == "read" && count > 0) || (boundary == "eof" && count < 0)) expire()
                }
                onClose = { if (boundary == "close") expire() }
            }
            val result = BoundedSmapsReader.read(
                openStream = {
                    if (boundary == "open") expire()
                    input
                },
                startedNanos = 0L,
                nowNanos = { time },
                bucketForMapping = {
                    if (boundary == "classifier") expire()
                    bucket(it)
                },
                limits = BoundedSmapsReader.Limits(timeLimitMs = 10),
            )
            incomplete(result, "time-limit")
            assertEquals(1, input.closes)
        }
        assertEquals(10_000L, BoundedSmapsReader.TIME_LIMIT_MS)
    }

    @Test
    fun ioAndCloseFailureAreIncompleteButCancellationAndFatalErrorsEscape() {
        val bytes = mapping().toByteArray()
        val failedOpen = BoundedSmapsReader.read(
            { throw IOException("fixture-private-message") },
            0L,
            { 0L },
            ::bucket,
        )
        incomplete(failedOpen)
        assertFalse(failedOpen.available)
        assertEquals("IOException", failedOpen.unavailableClass)
        assertFalse(failedOpen.toString().contains("fixture-private-message"))
        val failedRead = ChunkedInput(bytes).apply { beforeRead = { throw IOException("fixture-private-message") } }
        val failedClose = ChunkedInput(bytes).apply { onClose = { throw IOException("fixture-private-message") } }
        for (input in listOf(failedRead, failedClose)) {
            val result = read(input)
            incomplete(result)
            assertEquals("IOException", result.unavailableClass)
            assertEquals(1, input.closes)
            assertFalse(result.toString().contains("fixture-private-message"))
        }
        for (failure in listOf(CancellationException("fixture-cancel"), AssertionError("fixture-fatal"))) {
            val input = ChunkedInput(bytes).apply { beforeRead = { throw failure } }
            var caught: Throwable? = null
            try {
                read(input)
            } catch (error: Throwable) {
                caught = error
            }
            assertSame(failure, caught)
            assertEquals(1, input.closes)
        }
    }

    @Test
    fun criticalCloseFailuresEscapeAfterSuccessfulIoOrMalformedBodies() {
        val valid = mapping().toByteArray()
        val malformed = mapping().replace("Rss: 6 kB\n", "").toByteArray()
        for (kind in listOf("cancel", "fatal")) {
            fun closing(): Throwable = if (kind == "cancel") {
                CancellationException("fixture-close-cancel")
            } else {
                AssertionError("fixture-close-fatal")
            }
            for (body in listOf("success", "io", "parse")) {
                val closeFailure = closing()
                val bodyFailure = IOException("fixture-body-io")
                val input = ChunkedInput(if (body == "parse") malformed else valid).apply {
                    if (body == "io") beforeRead = { throw bodyFailure }
                    onClose = { throw closeFailure }
                }
                assertSame(closeFailure, escaped(input))
                assertEquals(1, input.closes)
                if (body == "success") {
                    assertTrue(closeFailure.suppressed.isEmpty())
                } else {
                    assertEquals(1, closeFailure.suppressed.size)
                    if (body == "io") {
                        assertSame(bodyFailure, closeFailure.suppressed.single())
                    } else {
                        assertEquals("SmapsObservationException", closeFailure.suppressed.single().javaClass.simpleName)
                    }
                }
            }
        }
    }

    @Test
    fun bodyCriticalFailuresAndThrowableIdentityKeepTheirPrecedence() {
        val valid = mapping().toByteArray()
        for (kind in listOf("cancel", "fatal")) {
            for (closeCritical in listOf(false, true)) {
                val body = if (kind == "cancel") {
                    CancellationException("fixture-body")
                } else {
                    AssertionError("fixture-body")
                }
                val closeFailure = when {
                    !closeCritical -> IOException("fixture-close")
                    kind == "cancel" -> AssertionError("fixture-close")
                    else -> CancellationException("fixture-close")
                }
                val input = ChunkedInput(valid).apply {
                    beforeRead = { throw body }
                    onClose = { throw closeFailure }
                }
                assertSame(body, escaped(input))
                assertEquals(1, input.closes)
                assertEquals(1, body.suppressed.size)
                assertSame(closeFailure, body.suppressed.single())
            }
        }
        val bodyIo = IOException("fixture-body-io")
        val closeIo = IOException("fixture-close-io")
        val ordinary = ChunkedInput(valid).apply {
            beforeRead = { throw bodyIo }
            onClose = { throw closeIo }
        }
        val result = read(ordinary)
        incomplete(result)
        assertEquals("IOException", result.unavailableClass)
        assertEquals(1, ordinary.closes)
        assertSame(closeIo, bodyIo.suppressed.single())
        val parseAndCloseIo = ChunkedInput(mapping().replace("Rss: 6 kB\n", "").toByteArray()).apply {
            onClose = { throw IOException("fixture-close-io") }
        }
        val parseFailure = read(parseAndCloseIo)
        incomplete(parseFailure, "missing-required-metric")
        assertEquals("SmapsObservationException", parseFailure.unavailableClass)
        assertEquals(1, parseAndCloseIo.closes)
        val identicalFailures = listOf(
            IOException("fixture-same"),
            CancellationException("fixture-same"),
            AssertionError("fixture-same"),
        )
        for (same in identicalFailures) {
            val input = ChunkedInput(valid).apply {
                beforeRead = { throw same }
                onClose = { throw same }
            }
            if (same is IOException) {
                val observed = read(input)
                incomplete(observed)
                assertEquals("IOException", observed.unavailableClass)
            } else {
                assertSame(same, escaped(input))
            }
            assertEquals(1, input.closes)
            assertTrue(same.suppressed.isEmpty())
        }
    }

    private fun escaped(input: InputStream): Throwable {
        var snapshot: BoundedSmapsReader.Snapshot? = null
        var caught: Throwable? = null
        try {
            snapshot = read(input)
        } catch (error: Throwable) {
            caught = error
        }
        assertNull("Critical stream failures must not return an incomplete snapshot", snapshot)
        return checkNotNull(caught) { "CRITICAL_STREAM_FAILURE_MUST_ESCAPE" }
    }

    private fun read(
        input: InputStream,
        limits: BoundedSmapsReader.Limits = BoundedSmapsReader.Limits(),
    ) = BoundedSmapsReader.read({ input }, 0L, { 0L }, ::bucket, limits)

    private fun incomplete(result: BoundedSmapsReader.Snapshot, reason: String? = null) {
        assertFalse(result.complete)
        assertNull(result.buckets)
        assertNull(result.totalsKiB)
        if (reason != null) assertEquals(reason, result.incompleteReason)
    }

    private fun mapping(
        name: String = "[heap]",
        values: List<Int> = listOf(8, 6, 4, 1, 2, 3, 0),
        address: String = "1000-2000",
    ) = "$address rw-p 00000000 00:00 0 $name\n" +
        BoundedSmapsReader.METRICS.zip(values).joinToString("") { (key, value) -> "$key: $value kB\n" }

    private fun bucket(name: String) = if (name == "[heap]") "allocatorNamed" else "otherFileNamed"

    private fun assertRejected(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    private class ChunkedInput(bytes: ByteArray, private val chunk: Int = 8192) : ByteArrayInputStream(bytes) {
        var closes = 0
        var singleByteReads = 0
        var beforeRead: () -> Unit = {}
        var afterRead: (Int) -> Unit = {}
        var onClose: () -> Unit = {}

        override fun read(): Int {
            singleByteReads++
            throw AssertionError("SMAPS_BLOCK_READ_REQUIRED")
        }

        override fun read(destination: ByteArray, offset: Int, length: Int): Int {
            beforeRead()
            return super.read(destination, offset, minOf(chunk, length)).also(afterRead)
        }

        override fun close() {
            closes++
            onClose()
            super.close()
        }
    }
}
