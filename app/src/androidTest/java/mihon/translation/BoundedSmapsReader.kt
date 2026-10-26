package mihon.translation

import java.io.InputStream
import java.util.concurrent.CancellationException

/** Self-smaps parser only. Mapping names select a fixed bucket and are never retained in a snapshot. */
internal object BoundedSmapsReader {
    const val BYTE_LIMIT = 8 * 1024 * 1024
    const val LINE_BYTE_LIMIT = 8192
    const val MAP_LIMIT = 32_768
    const val TIME_LIMIT_MS = 10_000L
    private const val READ_BYTES = 8192
    private const val NANOS_PER_MILLISECOND = 1_000_000L

    val METRICS = listOf("Size", "Rss", "Pss", "Private_Clean", "Private_Dirty", "Anonymous", "SwapPss")
    val BUCKETS = listOf(
        "allocatorNamed",
        "artNamed",
        "stackNamed",
        "unnamedAnonymous",
        "otherNamedAnonymous",
        "modelNamedFile",
        "otherFileNamed",
        "unknown",
    )
    private val header = Regex(
        "[0-9a-f]+-[0-9a-f]+\\s+[rwxps-]{4}\\s+[0-9a-f]+\\s+[0-9a-f]+:[0-9a-f]+\\s+\\d+(?:\\s+(.*))?",
    )
    private val whitespace = Regex("\\s+")

    /** Smaller limits are useful for deterministic parser regressions; callers cannot raise production bounds. */
    data class Limits(
        val byteLimit: Int = BYTE_LIMIT,
        val lineByteLimit: Int = LINE_BYTE_LIMIT,
        val mapLimit: Int = MAP_LIMIT,
        val timeLimitMs: Long = TIME_LIMIT_MS,
    ) {
        init {
            require(byteLimit in 1..BYTE_LIMIT)
            require(lineByteLimit in 1..LINE_BYTE_LIMIT)
            require(mapLimit in 1..MAP_LIMIT)
            require(timeLimitMs in 1..TIME_LIMIT_MS)
        }
    }

    data class Bucket(val maps: Int, val metricsKiB: List<Long>)

    data class Snapshot(
        val available: Boolean,
        val complete: Boolean,
        val bytesRead: Int,
        val mapsRead: Int,
        val buckets: Map<String, Bucket>?,
        val totalsKiB: List<Long>?,
        val unavailableClass: String? = null,
        val incompleteReason: String? = null,
    )

    private class SmapsObservationException(val reason: String) : Exception()

    /** Never turn a critical close failure into a suppressed detail of an ordinary parse/IO failure. */
    private inline fun <T> withOwnedStream(open: () -> InputStream, block: (InputStream) -> T): T {
        val input = open()
        var failure: Throwable? = null
        try {
            return block(input)
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            try {
                input.close()
            } catch (closing: Throwable) {
                val primary = failure ?: throw closing
                if (primary !== closing) {
                    val primaryCritical = primary is CancellationException || primary is Error
                    val closingCritical = closing is CancellationException || closing is Error
                    if (!primaryCritical && closingCritical) {
                        closing.addSuppressed(primary)
                        throw closing
                    }
                    primary.addSuppressed(closing)
                }
            }
        }
    }

    fun read(
        openStream: () -> InputStream,
        startedNanos: Long,
        nowNanos: () -> Long,
        bucketForMapping: (String) -> String,
        limits: Limits = Limits(),
    ): Snapshot {
        var opened = false
        var bytesRead = 0
        var maps = 0
        val totals = BUCKETS.associateWith { LongArray(METRICS.size) }
        val counts = BUCKETS.associateWith { 0 }.toMutableMap()
        var bucket: String? = null
        var current = LongArray(METRICS.size) { -1L }

        fun deadline() {
            if (nowNanos() - startedNanos > limits.timeLimitMs * NANOS_PER_MILLISECOND) {
                throw SmapsObservationException("time-limit")
            }
        }

        fun finishMap() {
            val name = bucket ?: return
            if (current.any { it < 0 }) throw SmapsObservationException("missing-required-metric")
            current.forEachIndexed { index, value ->
                totals.getValue(name)[index] = Math.addExact(totals.getValue(name)[index], value)
            }
            counts[name] = counts.getValue(name) + 1
        }

        fun line(value: String) {
            deadline()
            val match = header.matchEntire(value)
            if (match != null) {
                finishMap()
                if (++maps > limits.mapLimit) throw SmapsObservationException("map-limit")
                bucket = bucketForMapping(match.groupValues[1])
                // Invalid classifier output is an incomplete observation, never a new exported mapping name.
                check(bucket in BUCKETS)
                current = LongArray(METRICS.size) { -1L }
            } else {
                val index = METRICS.indexOf(value.substringBefore(':'))
                if (index >= 0) {
                    if (bucket == null || current[index] >= 0L) throw SmapsObservationException("malformed-map")
                    val fields = value.substringAfter(':').trim().split(whitespace)
                    val number = fields.firstOrNull()?.toLongOrNull()
                    if (fields.size != 2 || fields[1] != "kB" || number == null || number < 0L) {
                        throw SmapsObservationException("malformed-metric")
                    }
                    current[index] = number
                } else if (bucket == null && value.isNotBlank()) {
                    throw SmapsObservationException("missing-header")
                }
            }
        }

        return try {
            withOwnedStream(openStream) { input ->
                opened = true
                val block = ByteArray(READ_BYTES)
                val pendingLine = ByteArray(limits.lineByteLimit)
                var length = 0
                while (true) {
                    deadline()
                    // At the byte cap probe only one byte for EOF/overflow. Never consume a whole extra block.
                    val requested = minOf(block.size, limits.byteLimit - bytesRead + 1)
                    val count = input.read(block, 0, requested)
                    if (count > 0) bytesRead = Math.addExact(bytesRead, count)
                    deadline() // A slow read must not turn a late EOF/full line into a complete observation.
                    if (count < 0) break
                    if (count == 0) throw SmapsObservationException("incomplete-stream")
                    if (bytesRead > limits.byteLimit) throw SmapsObservationException("byte-limit")
                    for (index in 0 until count) {
                        val byte = block[index]
                        if (byte == 10.toByte()) {
                            line(String(pendingLine, 0, length, Charsets.UTF_8))
                            length = 0
                        } else {
                            if (length == pendingLine.size) throw SmapsObservationException("line-limit")
                            pendingLine[length++] = byte
                        }
                    }
                    deadline()
                }
                if (length != 0 || maps == 0) throw SmapsObservationException("incomplete-stream")
                finishMap()
                deadline()
            }
            // Stream close must also succeed before any totals become observable.
            deadline()
            val buckets = linkedMapOf<String, Bucket>()
            val all = LongArray(METRICS.size)
            for (name in BUCKETS) {
                deadline()
                val values = totals.getValue(name)
                values.forEachIndexed { index, value -> all[index] = Math.addExact(all[index], value) }
                buckets[name] = Bucket(counts.getValue(name), values.toList())
            }
            deadline()
            Snapshot(opened, true, bytesRead, maps, buckets, all.toList())
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Snapshot(
                opened,
                false,
                bytesRead,
                maps,
                buckets = null,
                totalsKiB = null,
                unavailableClass = error.javaClass.simpleName,
                incompleteReason = (error as? SmapsObservationException)?.reason,
            )
        }
    }
}
