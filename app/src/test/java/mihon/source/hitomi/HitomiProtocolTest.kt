package mihon.source.hitomi

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class HitomiProtocolTest {
    @Test
    fun `unsigned IDs retain high bits and truncated records fail`() {
        HitomiProtocol.ids(byteArrayOf(-1, -1, -1, -1, 0, 0, 0, 7)) shouldBe listOf(4294967295L, 7L)
        shouldThrow<IllegalArgumentException> { HitomiProtocol.ids(byteArrayOf(1, 2, 3)) }
    }

    @Test
    fun `range ignored by a mirror is sliced at the requested offset`() {
        val bytes = ByteArray(12) { it.toByte() }
        HitomiProtocol.range(bytes, 200, null, 4, 7, false).toList() shouldBe bytes.slice(4..7)
        shouldThrow<IllegalArgumentException> { HitomiProtocol.range(bytes, 200, null, 8, 15, false) }
        HitomiProtocol.range(bytes, 200, null, 8, 15, true).toList() shouldBe bytes.slice(8..11)
    }

    @Test
    fun `partial response must match both offset and declared length`() {
        val bytes = ByteArray(4)
        HitomiProtocol.range(bytes, 206, "bytes 8-11/12", 8, 15, true).size shouldBe 4
        shouldThrow<IllegalArgumentException> { HitomiProtocol.range(bytes, 206, "bytes 0-3/12", 8, 11, false) }
        shouldThrow<IllegalArgumentException> { HitomiProtocol.range(bytes, 206, "bytes 8-12/13", 8, 12, false) }
        shouldThrow<IllegalArgumentException> { HitomiProtocol.range(bytes, 206, "bytes 8-11/*", 8, 15, true) }
    }

    @Test
    fun `end of index requires a valid unsatisfied range`() {
        HitomiProtocol.range(byteArrayOf(), 416, "bytes */8", 8, 11, true).size shouldBe 0
        shouldThrow<IllegalArgumentException> { HitomiProtocol.range(byteArrayOf(), 416, "bytes */100", 8, 11, true) }
        shouldThrow<IllegalArgumentException> { HitomiProtocol.range(byteArrayOf(), 416, null, 8, 11, true) }
    }

    @Test
    fun `search record count must match payload`() {
        HitomiProtocol.searchIds(ByteBuffer.allocate(12).putInt(2).putInt(31).putInt(8).array()) shouldBe
            listOf(31L, 8L)
        shouldThrow<IllegalArgumentException> {
            HitomiProtocol.searchIds(ByteBuffer.allocate(8).putInt(2).putInt(31).array())
        }
    }

    @Test
    fun `routing uses rotated hash suffix and rejects path injection`() {
        val routing = HitomiProtocol.routing("var o = 0; switch(g) { case 3243: o = 1; break; } b: '1234/'")
        routing.imageUrl("000abc", "avif") shouldBe "https://a2.gold-usergeneratedcontent.net/1234/3243/000abc.avif"
        routing.imageUrl("000000", "webp") shouldBe "https://w1.gold-usergeneratedcontent.net/1234/0/000000.webp"
        shouldThrow<IllegalArgumentException> { HitomiProtocol.routing("b: '../other/'") }
        shouldThrow<IllegalArgumentException> { routing.imageUrl("../../file", "avif") }
        shouldThrow<IllegalArgumentException> { routing.imageUrl("000abc", "html") }
    }

    @Test
    fun `current reversed routing selects thumbnail and format specific page hosts`() {
        val routing = HitomiProtocol.routing("var o = 1; switch(g) { case 1662: o = 0; break; } b: '1792656001/'")
        routing.thumbnailUrl("0007e6") shouldBe
            "https://atn.gold-usergeneratedcontent.net/avifbigtn/6/7e/0007e6.avif"
        routing.thumbnailUrl("000050") shouldBe
            "https://btn.gold-usergeneratedcontent.net/avifbigtn/0/05/000050.avif"
        routing.thumbnailUrl("000050", "webp", "small") shouldBe
            "https://btn.gold-usergeneratedcontent.net/webpsmalltn/0/05/000050.webp"
        routing.imageUrl("0007e6", "webp") shouldBe
            "https://w1.gold-usergeneratedcontent.net/1792656001/1662/0007e6.webp"
        routing.imageUrl("000050", "webp") shouldBe
            "https://w2.gold-usergeneratedcontent.net/1792656001/5/000050.webp"
        routing.imageUrl("000050", "avif") shouldBe
            "https://a2.gold-usergeneratedcontent.net/1792656001/5/000050.avif"
        shouldThrow<IllegalArgumentException> { routing.thumbnailUrl("../bad") }
        shouldThrow<IllegalArgumentException> { routing.thumbnailUrl("000050", "html") }
        shouldThrow<IllegalArgumentException> { routing.thumbnailUrl("000050", size = "../big") }
        shouldThrow<IllegalArgumentException> { HitomiProtocol.routing("var o = 2; b: '1234/'") }
        shouldThrow<IllegalArgumentException> { HitomiProtocol.routing("case 4096: b: '1234/'") }
    }

    @Test
    fun `index node bounds prevent oversized allocation and invalid addresses`() {
        val valid = ByteBuffer.allocate(164).putInt(1).putInt(4).putInt(0x12345678).putInt(1).putLong(42).putInt(8)
        repeat(17) { valid.putLong(0) }
        val node = HitomiProtocol.node(valid.array())
        node.values.single() shouldBe HitomiProtocol.Value(42, 8)
        node.children.size shouldBe 17
        shouldThrow<IllegalArgumentException> {
            HitomiProtocol.node(ByteBuffer.allocate(4).putInt(Int.MAX_VALUE).array())
        }
        val badAddress = valid.array().copyOf().also { it[16] = -1 }
        shouldThrow<IllegalArgumentException> { HitomiProtocol.node(badAddress) }
    }

    @Test
    fun `gallery extraction never executes a script suffix`() {
        HitomiProtocol.galleryJson("var galleryinfo = {\"id\":42};\n") shouldBe "{\"id\":42}"
        shouldThrow<IllegalArgumentException> { HitomiProtocol.galleryJson("var galleryinfo = {}; alert('x');") }
    }

    @Test
    fun `gallery links are restricted to Hitomi and positive IDs`() {
        HitomiSource.galleryId("42") shouldBe 42L
        HitomiSource.galleryId("/g/42") shouldBe 42L
        HitomiSource.galleryId("https://hitomi.la/reader/42.html#3") shouldBe 42L
        HitomiSource.galleryId("https://hitomi.la/manga/example-42.html") shouldBe 42L
        HitomiSource.galleryId("https://hitomi.la.example.org/reader/42.html") shouldBe null
        HitomiSource.galleryId("-1") shouldBe null
    }
}
