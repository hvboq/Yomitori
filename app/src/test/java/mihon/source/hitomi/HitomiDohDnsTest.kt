package mihon.source.hitomi

import io.kotest.matchers.shouldBe
import okhttp3.Dns
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException

class HitomiDohDnsTest {
    @Test
    fun `resolver addresses reject private local reserved and IPv6 ULA targets`() {
        listOf(
            "0.0.0.0", "127.0.0.1", "10.0.0.1", "PRIVATE_VALUE_REMOVED", "169.254.1.1", "172.16.0.1",
            "192.168.1.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1", "224.0.0.1",
            "255.255.255.255", "::1", "fc00::1", "fd00::1", "fe80::1", "ff02::1", "2001:db8::1",
        ).forEach { HitomiDohDns.isPublicAddress(InetAddress.getByName(it)) shouldBe false }
        listOf("1.1.1.1", "8.8.8.8", "2606:4700:4700::1111")
            .forEach { HitomiDohDns.isPublicAddress(InetAddress.getByName(it)) shouldBe true }
    }

    @Test
    fun `DNS response bound covers declared and streaming bodies and exact limit`() {
        fun response(size: Int, declared: Boolean): Response {
            val buffer = Buffer().write(ByteArray(size))
            return Response.Builder().request(Request.Builder().url("https://cloudflare-dns.com/dns-query").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(object : ResponseBody() {
                    override fun contentType(): MediaType? = null
                    override fun contentLength() = if (declared) size.toLong() else -1L
                    override fun source(): BufferedSource = buffer
                }).build()
        }
        for (declared in listOf(true, false)) {
            HitomiDohDns.boundedResponse(response(65536, declared)).use { it.body.bytes().size shouldBe 65536 }
            assertThrows<IOException> { HitomiDohDns.boundedResponse(response(65537, declared)) }
        }
    }

    @Test
    fun `scope matches label boundaries and rejects malformed or similar domains`() {
        listOf("hitomi.la", "HITOMI.LA", "ltn.gold-usergeneratedcontent.net", "a1.gold-usergeneratedcontent.net")
            .forEach { HitomiDohDns.allows(it) shouldBe true }
        listOf(
            "hitomi.la.attacker.test", "evilhitomi.la", "gold-usergeneratedcontent.net.attacker.test",
            "127.0.0.1", "localhost", "private.tail123.ts.net", ".hitomi.la", "x..hitomi.la", "hitomi.la.",
        ).forEach { HitomiDohDns.allows(it) shouldBe false }
    }

    @Test
    fun `target hosts use public resolver while private hosts only use original DNS`() {
        val publicCalls = mutableListOf<String>()
        val privateCalls = mutableListOf<String>()
        val address = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val scoped = HitomiDohDns.scopedResolver(
            Dns {
                publicCalls.add(it)
                listOf(address)
            },
            Dns {
                privateCalls.add(it)
                listOf(address)
            },
        )
        scoped.lookup("a1.gold-usergeneratedcontent.net") shouldBe listOf(address)
        scoped.lookup("private.tail123.ts.net") shouldBe listOf(address)
        publicCalls shouldBe listOf("a1.gold-usergeneratedcontent.net")
        privateCalls shouldBe listOf("private.tail123.ts.net")
    }

    @Test
    fun `DoH failure is surfaced without falling back to a blocked system resolver`() {
        var systemCalls = 0
        val scoped = HitomiDohDns.scopedResolver(
            Dns { throw UnknownHostException("fixture failure") },
            Dns {
                systemCalls++
                emptyList()
            },
        )
        assertThrows<UnknownHostException> { scoped.lookup("hitomi.la") }
        systemCalls shouldBe 0
    }
}
