package mihon.source.hitomi

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.dnsoverhttps.DnsOverHttps
import okio.Buffer
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Public DoH is limited to Hitomi. Private translation endpoints retain their original resolver. */
internal object HitomiDohDns : Dns {
    private val resolvers: List<Dns> by lazy {
        val transport = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .cache(null) // No unbounded DNS map or HTTP cache is retained by this resolver.
            .addInterceptor { chain -> boundedResponse(chain.proceed(chain.request())) }
            .build()
        listOf("1.1.1.1" to byteArrayOf(1, 1, 1, 1), "1.0.0.1" to byteArrayOf(1, 0, 0, 1)).map { (host, bytes) ->
            DnsOverHttps.Builder()
                .client(transport)
                // IP SAN verification remains enabled; no DNS or resolver-host SNI is needed.
                .url("https://$host/dns-query".toHttpUrl())
                .bootstrapDnsHosts(InetAddress.getByAddress(bytes))
                .build()
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (!allows(hostname)) throw UnknownHostException("Host is outside the Hitomi DNS scope")
        for (resolver in resolvers) {
            try {
                val addresses = resolver.lookup(hostname)
                if (addresses.isNotEmpty() && addresses.all(::isPublicAddress)) return addresses
            } catch (_: UnknownHostException) {
                // Retry only the second pinned DoH endpoint; never the system resolver.
            }
        }
        throw UnknownHostException("Hitomi public DoH lookup failed")
    }

    /** Target hosts always use DoH; all other hosts use [fallback], with no public-DNS leakage. */
    fun forSource(fallback: Dns): Dns = scopedResolver(this, fallback)

    internal fun scopedResolver(hitomi: Dns, fallback: Dns): Dns = Dns { hostname ->
        if (allows(hostname)) hitomi.lookup(hostname) else fallback.lookup(hostname)
    }

    internal fun allows(hostname: String): Boolean {
        val host = hostname.lowercase()
        if (host.length > 253 || host.any { it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '.' }) return false
        if (host.split('.').any {
                it.isEmpty() || it.length > 63 || it.startsWith('-') || it.endsWith('-')
            }
        ) {
            return false
        }
        return DOMAINS.any { host == it || host.endsWith(".$it") }
    }

    internal fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        val bytes = address.address.map { it.toInt() and 255 }
        if (bytes.size == 4) {
            val (a, b, c) = bytes
            return !(
                a == 0 || a == 10 || a == 127 || a >= 224 ||
                    (a == 100 && b in 64..127) || (a == 169 && b == 254) ||
                    (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                    (a == 192 && b == 0 && c in listOf(0, 2)) ||
                    (a == 198 && b in 18..19) || (a == 198 && b == 51 && c == 100) ||
                    (a == 203 && b == 0 && c == 113)
                )
        }
        // Require global unicast 2000::/3; this also rejects ULA, mapped, NAT64 and scoped IPv6.
        return bytes.size == 16 && bytes[0] in 0x20..0x3F &&
            !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0D && bytes[3] == 0xB8)
    }

    internal fun boundedResponse(response: Response): Response = response.use {
        val body = response.body
        if (body.contentLength() > MAX_DNS_BYTES) throw IOException("DNS response exceeds 64 KiB")
        val buffer = Buffer()
        val source = body.source()
        while (true) {
            val count = source.read(buffer, minOf(8192L, MAX_DNS_BYTES + 1 - buffer.size))
            if (count == -1L) break
            if (buffer.size > MAX_DNS_BYTES) throw IOException("DNS response exceeds 64 KiB")
        }
        response.newBuilder().body(buffer.readByteArray().toResponseBody(body.contentType())).build()
    }

    private val DOMAINS = listOf("hitomi.la", "gold-usergeneratedcontent.net")
    private const val MAX_DNS_BYTES = 64L * 1024L
}
