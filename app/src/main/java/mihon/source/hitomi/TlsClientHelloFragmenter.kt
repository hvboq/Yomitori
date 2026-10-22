package mihon.source.hitomi

import java.io.ByteArrayOutputStream

/**
 * Aidoku TLSClientHello.swift behavior, reimplemented for the source transport (GPL-3.0).
 * Only TLS record framing changes. Handshake bytes, SNI and certificate verification remain intact.
 */
internal object TlsClientHelloFragmenter {
    private const val MAX_BYTES = 128 * 1024

    private data class Record(val start: Int, val length: Int, val type: Int, val offset: Int)
    private data class Hello(val records: List<Record>, val bytes: ByteArray)
    private data class Inspection(val hello: Hello? = null, val needsMore: Boolean = false)

    /** Input consists of complete TLS records. Invalid framing and exhausted bounds never request more. */
    fun needsMoreData(records: ByteArray): Boolean = inspect(records).needsMore

    fun split(record: ByteArray): Pair<ByteArray, ByteArray>? {
        val hello = inspect(record).hello ?: return null
        val name = serverName(hello.bytes) ?: return null

        // The hostname may already cross a record boundary. Reuse it without adding framing.
        hello.records.firstOrNull { it.offset > name.first && it.offset <= name.last }?.let {
            return record.copyOfRange(0, it.start) to record.copyOfRange(it.start, record.size)
        }
        val cut = name.first + (name.last - name.first + 1) / 2
        val containing = hello.records.firstOrNull { cut > it.offset && cut < it.offset + it.length } ?: return null
        val prefixLength = cut - containing.offset
        val suffixLength = containing.length - prefixLength
        val body = containing.start + 5
        fun header(length: Int) = byteArrayOf(
            22,
            record[containing.start + 1],
            record[containing.start + 2],
            (length ushr 8).toByte(),
            length.toByte(),
        )
        val first =
            record.copyOfRange(0, containing.start) + header(prefixLength) +
                record.copyOfRange(body, body + prefixLength)
        val second = header(suffixLength) + record.copyOfRange(body + prefixLength, record.size)
        return first to second
    }

    private fun inspect(input: ByteArray): Inspection {
        if (input.isEmpty() || input.size > MAX_BYTES) return Inspection()
        val records = mutableListOf<Record>()
        var position = 0
        var offset = 0
        while (position < input.size) {
            if (input.size - position < 5) return Inspection()
            val type = input[position].unsigned()
            if (type !in 20..24 || input[position + 1].unsigned() != 3 || input[position + 2].unsigned() !in 1..3) {
                return Inspection()
            }
            val length = input[position + 3].unsigned() * 256 + input[position + 4].unsigned()
            if (length > input.size - position - 5 || (type == 22 && length == 0)) return Inspection()
            records += Record(position, length, type, offset)
            offset += length
            position += 5 + length
        }
        if (records.first().type != 22) return Inspection()
        if (input[records.first().start + 5].unsigned() != 1) return Inspection()
        val output = ByteArrayOutputStream()
        val helloRecords = mutableListOf<Record>()
        var expectedLength = 0
        for (frame in records) {
            if (frame.type != 22) return Inspection()
            helloRecords += frame
            output.write(input, frame.start + 5, frame.length)
            if (output.size() < 4) continue
            if (expectedLength == 0) {
                val header = output.toByteArray()
                expectedLength = header[1].unsigned() * 65536 + header[2].unsigned() * 256 + header[3].unsigned() + 4
                // The smallest ClientHello body is 41 bytes; an SNI-bearing one is longer.
                if (expectedLength < 45 || expectedLength > MAX_BYTES - 5) return Inspection()
            }
            if (output.size() >= expectedLength) {
                return Inspection(Hello(helloRecords, output.toByteArray().copyOf(expectedLength)))
            }
        }
        if (!validPartialBody(output.toByteArray(), expectedLength)) return Inspection()
        return Inspection(needsMore = input.size < MAX_BYTES)
    }

    /** Reject already visible invalid vector lengths instead of asking the relay to wait for more. */
    private fun validPartialBody(bytes: ByteArray, expected: Int): Boolean {
        if (bytes.size > 4 && bytes[4].unsigned() != 3) return false
        if (bytes.size > 5 && bytes[5].unsigned() !in 1..3) return false
        if (bytes.size <= 38) return true
        val session = bytes[38].unsigned()
        if (session > 32) return false
        var position = 39 + session
        if (position + 2 > expected) return false
        if (position + 2 > bytes.size) return true
        val ciphers = bytes[position].unsigned() * 256 + bytes[position + 1].unsigned()
        if (ciphers < 2 || ciphers % 2 != 0) return false
        position += 2 + ciphers
        if (position + 1 > expected) return false
        if (position + 1 > bytes.size) return true
        val compression = bytes[position].unsigned()
        if (compression == 0) return false
        position += 1 + compression
        if (position > expected) return false
        if (position == expected) return true // Extensions may be absent in older ClientHello messages.
        if (position + 2 > expected) return false
        if (position + 2 > bytes.size) return true
        val extensions = bytes[position].unsigned() * 256 + bytes[position + 1].unsigned()
        return position + 2 + extensions == expected
    }

    private fun serverName(hello: ByteArray): IntRange? {
        val cursor = Cursor(hello, 4, hello.size)
        val major = cursor.byte()
        val minor = cursor.byte()
        if (major != 3 || minor == null || minor !in 1..3 || !cursor.skip(32)) return null
        val sessionLength = cursor.byte() ?: return null
        if (sessionLength > 32 || !cursor.skip(sessionLength)) return null
        val cipherLength = cursor.short() ?: return null
        if (cipherLength < 2 || cipherLength % 2 != 0 || !cursor.skip(cipherLength)) return null
        val compressionLength = cursor.byte() ?: return null
        if (compressionLength < 1 || !cursor.skip(compressionLength)) return null
        val extensionsLength = cursor.short() ?: return null
        if (extensionsLength != cursor.remaining) return null
        val seenExtensions = HashSet<Int>()
        var name: IntRange? = null
        while (cursor.remaining > 0) {
            val type = cursor.short() ?: return null
            val length = cursor.short() ?: return null
            if (length > cursor.remaining || !seenExtensions.add(type)) return null
            val end = cursor.position + length
            if (type == 0) {
                val names = Cursor(hello, cursor.position, end)
                val listLength = names.short() ?: return null
                if (listLength == 0 || listLength != names.remaining) return null
                val seenNames = HashSet<Int>()
                while (names.remaining > 0) {
                    val nameType = names.byte() ?: return null
                    val nameLength = names.short() ?: return null
                    if (nameLength == 0 || nameLength > names.remaining || !seenNames.add(nameType)) return null
                    if (nameType == 0) {
                        if (nameLength < 2) return null
                        val range = names.position until names.position + nameLength
                        if (range.any { hello[it].unsigned() !in 33..126 }) return null
                        name = range
                    }
                    if (!names.skip(nameLength)) return null
                }
            }
            if (!cursor.skip(length)) return null
        }
        return name
    }

    private class Cursor(private val bytes: ByteArray, var position: Int, private val end: Int) {
        val remaining get() = end - position
        fun byte(): Int? = if (remaining < 1) null else bytes[position++].unsigned()
        fun short(): Int? {
            if (remaining < 2) return null
            return bytes[position++].unsigned() * 256 + bytes[position++].unsigned()
        }
        fun skip(count: Int): Boolean {
            if (count < 0 || count > remaining) return false
            position += count
            return true
        }
    }

    private fun Byte.unsigned() = toInt() and 255
}
