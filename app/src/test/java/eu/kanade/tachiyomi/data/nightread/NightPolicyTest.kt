package eu.kanade.tachiyomi.data.nightread

import io.kotest.matchers.shouldBe
import li.joye.yakuyomi.engine.NightReadRenderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/** Pure protocol tests. Actual URI/provider and page mutation coverage is in Android tests. */
class NightPolicyTest {
    private val page = "neutral.png"
    private val standard = NightPages.levelName(page, NightLevel.STANDARD)
    private val more = NightPages.levelName(page, NightLevel.MORE)

    @Test
    fun `complete pair binds actual analysis policy and exact generation`() {
        val io = MemoryIo()
        io.publish("a".repeat(32), moreDistinct = true)
        val stdProof = checkNotNull(NightPolicy.parse(io.proofs.keys.first()))
        stdProof.policy shouldBe NightReadRenderer.INPUT_RGB_POLICY_ID
        stdProof.pageKey shouldBe NightSourceRevision.pageKey(page)
        NightPolicy.recipe(io.names, page, NightLevel.STANDARD)?.imageName shouldBe standard
        NightPolicy.recipe(io.names, page, NightLevel.MORE)?.imageName shouldBe more
        NightPolicy.reusable(io.names, page, io) shouldBe true
        io.imageOpens shouldBe 0
        io.proofs.values.forEach { (it.size <= 512) shouldBe true }
    }

    @Test
    fun `more same is explicit and a leftover distinct sibling rejects both tiers`() {
        val io = MemoryIo()
        io.publish("b".repeat(32), moreDistinct = false)
        NightPolicy.recipe(io.names, page, NightLevel.MORE)?.imageName shouldBe standard
        val result = checkNotNull(NightPolicy.openCurrent(io.names, page, NightLevel.MORE, io))
        result.first shouldBe "${"b".repeat(32)}:$standard"
        result.second.close()
        io.names += more
        NightLevel.entries.forEach { NightPolicy.recipe(io.names, page, it) shouldBe null }
    }

    @Test
    fun `missing mixed conflicting wrong-policy and wrong-version pairs fail before IO`() {
        val first = NightPolicy.names(page, "1".repeat(32), moreDistinct = true)
        val second = NightPolicy.names(page, "2".repeat(32), moreDistinct = true)
        val policy = checkNotNull(NightPolicy.parse(first.first())).policy
        val images = setOf(standard, more)
        val invalid = listOf(
            images,
            images + first.first(),
            images + first.last(),
            images + first.first() + second.last(),
            images + first + second,
            images + first.map { it.replace(".$policy.", ".wrong-policy.") },
            images +
                first.map {
                    it.replace(".rules${NightPages.RULES_VERSION}.", ".rules${NightPages.RULES_VERSION + 1}.")
                },
            images + first + second.map { it.replace(".$policy.", ".wrong-policy.") },
        )
        invalid.forEach { names ->
            val io = MemoryIo()
            NightLevel.entries.forEach { NightPolicy.openCurrent(names, page, it, io) shouldBe null }
            io.witnessReads shouldBe 0
            io.proofReads shouldBe 0
            io.imageOpens shouldBe 0
        }
    }

    @Test
    fun `malformed and oversized proof bytes fail without opening any image`() {
        listOf(ByteArray(0), ByteArray(513), "unsealed-policy".toByteArray()).forEach { invalid ->
            val io = MemoryIo()
            io.publish("c".repeat(32), moreDistinct = true)
            io.proofs[io.proofs.keys.first()] = invalid
            NightPolicy.openCurrent(io.names, page, NightLevel.MORE, io) shouldBe null
            io.imageOpens shouldBe 0
        }
        val io = MemoryIo()
        io.publish("c".repeat(32), moreDistinct = true)
        val first = io.proofs.keys.first()
        io.proofs[first] = io.proofs.getValue(first).copyOf().apply { this[lastIndex - 1] = '0'.code.toByte() }
        NightPolicy.openCurrent(io.names, page, NightLevel.STANDARD, io) shouldBe null
        io.imageOpens shouldBe 0
    }

    @Test
    fun `proof revocation before open propagates failure without opening pixels`() {
        val io = MemoryIo()
        io.publish("d".repeat(32), moreDistinct = true)
        io.proofs.remove(io.proofs.keys.first())
        assertThrows<IOException> { NightPolicy.openCurrent(io.names, page, NightLevel.MORE, io) }
        io.imageOpens shouldBe 0
    }

    @Test
    fun `proof revocation after image open closes the untransferred stream`() {
        val io = MemoryIo()
        io.publish("e".repeat(32), moreDistinct = true)
        io.onOpen = { io.proofs.clear() }
        assertThrows<IOException> { NightPolicy.openCurrent(io.names, page, NightLevel.MORE, io) }
        io.imageOpens shouldBe 1
        io.imageCloses shouldBe 1
        io.imageBytesRead shouldBe 0
    }

    @Test
    fun `source revision changes across image open close the obsolete stream`() {
        val io = MemoryIo()
        io.publish("f".repeat(32), moreDistinct = true)
        io.onOpen = { io.currentWitness = NightPolicy.Witness("2".repeat(64)) }
        NightPolicy.openCurrent(io.names, page, NightLevel.MORE, io) shouldBe null
        io.imageOpens shouldBe 1
        io.imageCloses shouldBe 1
    }

    @Test
    fun `fresh revision after byte-identical rollback cannot revive old proof`() {
        val io = MemoryIo()
        io.publish("3".repeat(32), moreDistinct = true)
        val originalMetadataAndBytes = io.imageBytes.copyOf()
        // The real adapter's witness includes the persisted UUID, separately from metadata.
        io.currentWitness = NightPolicy.Witness("9".repeat(64))
        io.imageBytes = originalMetadataAndBytes
        NightPolicy.reusable(io.names, page, io) shouldBe false
        NightPolicy.openCurrent(io.names, page, NightLevel.STANDARD, io) shouldBe null
        io.imageOpens shouldBe 0
    }

    @Test
    fun `regenerated same filename has a different served token`() {
        val io = MemoryIo()
        io.publish("4".repeat(32), moreDistinct = true)
        val first = checkNotNull(NightPolicy.openCurrent(io.names, page, NightLevel.MORE, io))
        first.second.close()
        io.publish("5".repeat(32), moreDistinct = true)
        val second = checkNotNull(NightPolicy.openCurrent(io.names, page, NightLevel.MORE, io))
        second.second.close()
        first.first.substringAfter(':') shouldBe second.first.substringAfter(':')
        (first.first != second.first) shouldBe true
    }

    @Test
    fun `validated open transfers stream ownership without reading or hashing image bytes`() {
        val io = MemoryIo()
        io.publish("6".repeat(32), moreDistinct = true)
        repeat(12) {
            val result = checkNotNull(NightPolicy.openCurrent(io.names, page, NightLevel.STANDARD, io))
            io.imageBytesRead shouldBe 0
            io.imageCloses shouldBe it
            result.second.close()
        }
        io.imageOpens shouldBe 12
        io.imageCloses shouldBe 12
        io.imageBytesRead shouldBe 0
    }

    @Test
    fun `failed second witness closes image and keeps original exception`() {
        val io = MemoryIo()
        io.publish("7".repeat(32), moreDistinct = true)
        val denied = IOException("neutral-provider-denied")
        io.onOpen = { io.witnessFailure = denied }
        val actual = assertThrows<IOException> { NightPolicy.openCurrent(io.names, page, NightLevel.MORE, io) }
        (actual === denied) shouldBe true
        io.imageCloses shouldBe 1
    }

    @Test
    fun `bounded record read accepts its limit and rejects one extra byte including zero reads`() {
        val bytes = ByteArray(512) { 42 }
        NightPolicy.readBounded(ByteArrayInputStream(bytes), 512).toList() shouldBe bytes.toList()
        assertThrows<IOException> { NightPolicy.readBounded(ByteArrayInputStream(ByteArray(513)), 512) }
        val input = object : InputStream() {
            private val content = ByteArrayInputStream(ByteArray(513))
            private var first = true
            override fun read(): Int = content.read()
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (first) {
                    first = false
                    return 0
                }
                return content.read(buffer, offset, length)
            }
        }
        assertThrows<IOException> { NightPolicy.readBounded(input, 512) }
    }

    @Test
    fun `auxiliary pending and committed records round trip without completion authority`() {
        val pending = NightSourceRevision.Revision("8".repeat(32), false, null)
        val committed = NightSourceRevision.Revision("9".repeat(32), true, "01234567-89ab-cdef-0123-456789abcdef")
        listOf(pending, committed).forEach { revision ->
            val encoded = NightSourceRevision.encode(revision)
            (encoded.size <= NightSourceRevision.MAX_BYTES) shouldBe true
            NightSourceRevision.decode(encoded) shouldBe revision
        }
        assertThrows<IOException> { NightSourceRevision.decode(ByteArray(NightSourceRevision.MAX_BYTES + 1)) }
        assertThrows<IOException> { NightSourceRevision.decode("committed".toByteArray()) }
        (pending.id != committed.id) shouldBe true
        pending.committed shouldBe false
    }

    @Test
    fun `critical body identity wins ordinary or critical rollback failures`() {
        val bodies =
            listOf<Throwable>(
                java.util.concurrent.CancellationException("neutral-cancel"),
                AssertionError("neutral-error"),
            )
        bodies.forEach { body ->
            val cleanup = IOException("neutral-rollback-io")
            val actual = assertThrows<Throwable> { NightSourceRevision.settleFailure(body) { throw cleanup } }
            (actual === body) shouldBe true
            body.suppressed.contains(cleanup) shouldBe true
        }
        val body = java.util.concurrent.CancellationException("neutral-body")
        val cleanup = AssertionError("neutral-cleanup")
        val actual = assertThrows<Throwable> { NightSourceRevision.settleFailure(body) { throw cleanup } }
        (actual === body) shouldBe true
        body.suppressed.contains(cleanup) shouldBe true
    }

    @Test
    fun `critical rollback escapes ordinary body while ordinary rollback is suppressed`() {
        listOf<Throwable>(
            java.util.concurrent.CancellationException("neutral-cleanup-cancel"),
            AssertionError("neutral-cleanup-error"),
        )
            .forEach { cleanup ->
                val body = IOException("neutral-body-io")
                val actual = assertThrows<Throwable> { NightSourceRevision.settleFailure(body) { throw cleanup } }
                (actual === cleanup) shouldBe true
                cleanup.suppressed.contains(body) shouldBe true
            }
        val body = IOException("neutral-body")
        val cleanup = IOException("neutral-cleanup")
        NightSourceRevision.settleFailure(body) { throw cleanup }
        body.suppressed.toList() shouldBe listOf(cleanup)
    }

    private inner class MemoryIo : NightPolicy.ReadIo {
        var currentWitness = NightPolicy.Witness("1".repeat(64))
        var witnessFailure: IOException? = null
        var imageBytes = byteArrayOf(2, 4, 6, 8)
        val names = linkedSetOf<String>()
        val proofs = linkedMapOf<String, ByteArray>()
        var witnessReads = 0
        var proofReads = 0
        var imageOpens = 0
        var imageCloses = 0
        var imageBytesRead = 0
        var onOpen: () -> Unit = {}

        fun publish(generation: String, moreDistinct: Boolean) {
            names.clear()
            names += standard
            if (moreDistinct) names += more
            proofs.clear()
            NightPolicy.names(page, generation, moreDistinct).forEach { name ->
                names += name
                proofs[name] = NightPolicy.encode(checkNotNull(NightPolicy.parse(name)), currentWitness)
            }
        }

        override fun witness(): NightPolicy.Witness {
            witnessReads++
            witnessFailure?.let { throw it }
            return currentWitness
        }

        override fun proof(name: String): ByteArray {
            proofReads++
            return proofs[name] ?: throw IOException("Revoked neutral proof")
        }

        override fun image(name: String): InputStream {
            name shouldBe if (name == standard) standard else more
            imageOpens++
            onOpen()
            return object : ByteArrayInputStream(imageBytes) {
                private var closed = false
                override fun read(): Int = super.read().also { if (it >= 0) imageBytesRead++ }
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = super.read(buffer, offset, length).also {
                    if (it > 0) imageBytesRead += it
                }
                override fun close() {
                    if (!closed) imageCloses++
                    closed = true
                    super.close()
                }
            }
        }
    }
}
