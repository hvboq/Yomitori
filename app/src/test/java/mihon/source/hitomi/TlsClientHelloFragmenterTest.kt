package mihon.source.hitomi

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TlsClientHelloFragmenterTest {
    private val hostname = "ltn.gold-usergeneratedcontent.net".toByteArray()

    @Test
    fun `TLS12 and TLS13 legacy ClientHello split preserves every handshake and trailing record byte`() {
        for (supportedVersions in listOf(byteArrayOf(2, 3, 3), byteArrayOf(2, 3, 4))) {
            val payload = hello(extension(43, supportedVersions) + sni())
            val trailing = record(byteArrayOf(1), type = 20)
            val original = record(payload) + trailing
            val (first, second) = requireNotNull(TlsClientHelloFragmenter.split(original))
            payloads(first + second).toList() shouldBe payloads(original).toList()
            second.takeLast(trailing.size) shouldBe trailing.toList()
            (first.size + second.size) shouldBe (original.size + 5)
            first.toString(Charsets.US_ASCII).contains(hostname.toString(Charsets.US_ASCII)) shouldBe false
            second.toString(Charsets.US_ASCII).contains(hostname.toString(Charsets.US_ASCII)) shouldBe false
            TlsClientHelloFragmenter.needsMoreData(original) shouldBe false
        }
    }

    @Test
    fun `ClientHello spanning records can wait for header body and reuse an SNI boundary`() {
        val payload = hello(sni())
        val nameStart = payload.size - hostname.size
        for (cut in listOf(1, 2, 3, 4, 20, 39, nameStart + 3, payload.size - 1)) {
            val prefix = record(payload.copyOfRange(0, cut))
            TlsClientHelloFragmenter.needsMoreData(prefix) shouldBe true
            TlsClientHelloFragmenter.split(prefix) shouldBe null
            val complete = prefix + record(payload.copyOfRange(cut, payload.size))
            TlsClientHelloFragmenter.needsMoreData(complete) shouldBe false
            val (first, second) = requireNotNull(TlsClientHelloFragmenter.split(complete))
            payloads(first + second).toList() shouldBe payload.toList()
            if (cut > nameStart && cut < payload.size) {
                first.toList() shouldBe prefix.toList()
                (first + second).toList() shouldBe complete.toList()
            }
        }
    }

    @Test
    fun `one byte TLS fragments preserve existing framing and reject visible malformed partial vectors`() {
        val payload = hello(sni())
        val input = payload.fold(byteArrayOf()) { bytes, value -> bytes + record(byteArrayOf(value)) }
        val split = requireNotNull(TlsClientHelloFragmenter.split(input))
        (split.first + split.second).toList() shouldBe input.toList()
        val malformed = listOf(
            payload.copyOf(6).apply { this[4] = 2 },
            payload.copyOf(39).apply { this[38] = 33 },
            payload.copyOf(41).apply { this[40] = 3 },
        )
        malformed.forEach { TlsClientHelloFragmenter.needsMoreData(record(it)) shouldBe false }
    }

    @Test
    fun `all truncated TCP inputs invalid records nonTLS and serverHello fail safely`() {
        val valid = record(hello(sni()))
        for (end in valid.indices) {
            TlsClientHelloFragmenter.split(valid.copyOf(end)) shouldBe null
            TlsClientHelloFragmenter.needsMoreData(valid.copyOf(end)) shouldBe false
        }
        val invalid = listOf(
            "GET / HTTP/1.1\r\n".toByteArray(),
            record(byteArrayOf()),
            record(hello(sni()).apply { this[0] = 2 }),
            valid.copyOf().apply { this[1] = 2 },
            valid.copyOf().apply { this[2] = 9 },
            valid.copyOf() + byteArrayOf(22),
        )
        invalid.forEach {
            TlsClientHelloFragmenter.split(it) shouldBe null
            TlsClientHelloFragmenter.needsMoreData(it) shouldBe false
        }
    }

    @Test
    fun `missing short duplicate and malformed SNI lists are rejected`() {
        val hostEntry = name(0, hostname)
        val invalidExtensions = listOf(
            byteArrayOf(),
            sni(listOf(0 to byteArrayOf())),
            sni(listOf(0 to byteArrayOf('x'.code.toByte()))),
            sni(listOf(0 to hostname, 0 to hostname)),
            sni() + sni(),
            extension(0, u16(hostEntry.size + 1) + hostEntry),
            extension(0, u16(hostEntry.size - 1) + hostEntry),
            extension(0, u16(3) + byteArrayOf(0, 0, 40)),
            sni(listOf(0 to byteArrayOf(65, 0, 66))),
        )
        invalidExtensions.forEach { TlsClientHelloFragmenter.split(record(hello(it))) shouldBe null }
        val mixedNames = record(hello(sni(listOf(1 to byteArrayOf(1, 2), 0 to hostname))))
        val split = requireNotNull(TlsClientHelloFragmenter.split(mixedNames))
        payloads(split.first + split.second).toList() shouldBe payloads(mixedNames).toList()
    }

    @Test
    fun `invalid vector lengths and duplicate extension IDs fail without changing bytes`() {
        val original = hello(sni())
        val invalid = listOf(
            original.copyOf().apply { this[38] = 33 }, // session ID exceeds the protocol limit
            original.copyOf().apply { this[40] = 3 }, // odd cipher suite vector
            original.copyOf().apply { this[43] = 0 }, // empty compression methods
            original.copyOf().apply { this[46] = 0 }, // extensions length does not cover the body
            hello(extension(43, byteArrayOf(2, 3, 4)) + extension(43, byteArrayOf(2, 3, 4)) + sni()),
        )
        invalid.forEach { TlsClientHelloFragmenter.split(record(it)) shouldBe null }
    }

    @Test
    fun `bounds allow maximum record length but refuse handshake and aggregate overflow`() {
        val payload = hello(sni())
        val maximumRecord = record(payload + ByteArray(65535 - payload.size))
        val split = requireNotNull(TlsClientHelloFragmenter.split(maximumRecord))
        payloads(split.first + split.second).toList() shouldBe payloads(maximumRecord).toList()
        val tooLarge = maximumRecord + record(ByteArray(65535))
        TlsClientHelloFragmenter.split(tooLarge) shouldBe null
        TlsClientHelloFragmenter.needsMoreData(tooLarge) shouldBe false
        val hugeHandshake = record(byteArrayOf(1, 127, -1, -1))
        TlsClientHelloFragmenter.split(hugeHandshake) shouldBe null
        TlsClientHelloFragmenter.needsMoreData(hugeHandshake) shouldBe false
    }

    @Test
    fun `interrupted handshake followed by nonhandshake record cannot request further input`() {
        val payload = hello(sni())
        val input = record(payload.copyOf(20)) + record(byteArrayOf(1), type = 20)
        TlsClientHelloFragmenter.needsMoreData(input) shouldBe false
        TlsClientHelloFragmenter.split(input) shouldBe null
    }

    @Test
    fun `every mutated byte remains bounded and any accepted split keeps original payloads`() {
        val original = record(hello(extension(43, byteArrayOf(2, 3, 4)) + sni()))
        for (index in original.indices) {
            for (value in listOf(0, 1, 127, 255)) {
                val modified = original.copyOf().apply { this[index] = value.toByte() }
                TlsClientHelloFragmenter.needsMoreData(modified)
                TlsClientHelloFragmenter.split(modified)?.let { (first, second) ->
                    payloads(first + second).toList() shouldBe payloads(modified).toList()
                }
            }
        }
    }

    private fun hello(extensions: ByteArray): ByteArray {
        val body = byteArrayOf(3, 3) + ByteArray(32) + byteArrayOf(0) + u16(2) +
            byteArrayOf(0x13, 1, 1, 0) + u16(extensions.size) + extensions
        return byteArrayOf(1, (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body
    }

    private fun sni(names: List<Pair<Int, ByteArray>> = listOf(0 to hostname)): ByteArray {
        val entries = names.fold(byteArrayOf()) { bytes, (type, value) -> bytes + name(type, value) }
        return extension(0, u16(entries.size) + entries)
    }

    private fun name(type: Int, value: ByteArray) = byteArrayOf(type.toByte()) + u16(value.size) + value
    private fun extension(type: Int, value: ByteArray) = u16(type) + u16(value.size) + value
    private fun record(payload: ByteArray, type: Int = 22) =
        byteArrayOf(type.toByte(), 3, 1) + u16(payload.size) + payload
    private fun u16(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())

    private fun payloads(records: ByteArray): ByteArray {
        var cursor = 0
        var payload = byteArrayOf()
        while (cursor < records.size) {
            val length = (records[cursor + 3].toInt() and 255) * 256 + (records[cursor + 4].toInt() and 255)
            payload += records.copyOfRange(cursor + 5, cursor + 5 + length)
            cursor += 5 + length
        }
        return payload
    }
}
