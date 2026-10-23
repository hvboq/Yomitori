package mihon.source.hitomi

import io.kotest.matchers.shouldBe
import okhttp3.Dns
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class HitomiBypassProxyTest {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val record = byteArrayOf(22, 3, 3, 0, 4, 1, 2, 3, 4)

    private fun relay(
        connector: (InetAddress, Int) -> Socket,
        splitter: (ByteArray) -> Pair<ByteArray, ByteArray>? = { null },
        more: (ByteArray) -> Boolean = { false },
        capacity: Int = 2,
        idleTimeoutMillis: Int = 2000,
        handshakeTimeoutMillis: Int = 1000,
    ) = HitomiSocksProxy(
        resolver = Dns { listOf(loopback) },
        connector = connector,
        fragmenter = splitter,
        needsMoreData = more,
        splitDelayMillis = 0,
        maxConnections = capacity,
        handshakeTimeoutMillis = handshakeTimeoutMillis,
        idleTimeoutMillis = idleTimeoutMillis,
    )

    private fun client(relay: HitomiSocksProxy) = Socket(Proxy.NO_PROXY).apply {
        connect(relay.proxy.address() as InetSocketAddress, 2000)
        soTimeout = 3000
    }

    private fun connect(socket: Socket, host: String = "a1.gold-usergeneratedcontent.net", port: Int = 443): Int {
        val input = DataInputStream(socket.getInputStream())
        val output = socket.getOutputStream()
        output.write(byteArrayOf(5, 1, 0))
        output.flush()
        ByteArray(2).also(input::readFully).toList() shouldBe listOf<Byte>(5, 0)
        val bytes = host.toByteArray(Charsets.US_ASCII)
        output.write(
            byteArrayOf(5, 1, 0, 3, bytes.size.toByte()) + bytes + byteArrayOf((port shr 8).toByte(), port.toByte()),
        )
        output.flush()
        return ByteArray(10).also(input::readFully)[1].toInt() and 255
    }

    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        condition() shouldBe true
    }

    private fun trickle(sockets: List<Socket>): AutoCloseable {
        val running = AtomicBoolean(true)
        val worker = thread(isDaemon = true) {
            while (running.get()) {
                sockets.forEach { socket ->
                    try {
                        socket.getOutputStream().write(0)
                        socket.getOutputStream().flush()
                    } catch (_: IOException) {
                        // The expected deadline closes these sockets while the sender is still active.
                    }
                }
                try {
                    Thread.sleep(25)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        return AutoCloseable {
            running.set(false)
            worker.interrupt()
            worker.join(1000)
        }
    }

    private fun assertClosed(socket: Socket) {
        try {
            socket.getInputStream().read() shouldBe -1
        } catch (_: SocketException) {
            // Closing with unread trickled bytes may send RST instead of EOF on some platforms.
            // SocketTimeoutException is deliberately not accepted as proof of closure.
        }
    }

    private class BlockedWriteSocket(private val allowedWrites: Int) : Socket() {
        val writeBlocked = CountDownLatch(1)
        val socketClosed = CountDownLatch(1)
        private val releaseWrite = CountDownLatch(1)
        private val fixtureClosed = AtomicBoolean()
        private val writes = AtomicInteger()
        private val input = ByteArrayInputStream(byteArrayOf())
        private val output = object : OutputStream() {
            override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (writes.incrementAndGet() <= allowedWrites) return
                writeBlocked.countDown()
                try {
                    releaseWrite.await()
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Fixture write interrupted", error)
                }
                throw IOException("Fixture socket closed")
            }
        }

        override fun getInputStream(): InputStream = input
        override fun getOutputStream(): OutputStream = output
        override fun setTcpNoDelay(on: Boolean) = Unit
        override fun setSoTimeout(timeout: Int) = Unit
        override fun shutdownOutput() = Unit
        override fun close() {
            if (!fixtureClosed.compareAndSet(false, true)) return
            socketClosed.countDown()
            releaseWrite.countDown()
        }
    }

    private fun assertBlockedWriteExpires(allowFirstTlsWrite: Boolean) {
        val remote = BlockedWriteSocket(if (allowFirstTlsWrite) 1 else 0)
        try {
            relay({ _, _ -> remote }, capacity = 1, idleTimeoutMillis = 300).use { proxy ->
                client(proxy).use { socket ->
                    connect(socket) shouldBe 0
                    socket.getOutputStream().apply {
                        write(if (allowFirstTlsWrite) record + byteArrayOf(9, 8, 7) else record)
                        flush()
                    }
                    remote.writeBlocked.await(3, TimeUnit.SECONDS) shouldBe true
                    if (allowFirstTlsWrite) {
                        // The return pump has clean EOF, so no input timeout remains to close the stalled upload.
                        socket.getInputStream().read() shouldBe -1
                    }
                    remote.socketClosed.await(3, TimeUnit.SECONDS) shouldBe true
                    eventually { proxy.activeConnectionCount == 0 }
                    val replacementDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
                    while (true) {
                        try {
                            client(proxy).use { replacement -> connect(replacement, port = 80) shouldBe 2 }
                            break
                        } catch (error: IOException) {
                            // Admission can be released just before the sole synchronous worker finishes unwinding.
                            if (System.nanoTime() >= replacementDeadline) {
                                throw AssertionError(
                                    "Replacement SOCKS request did not acquire the released permit",
                                    error,
                                )
                            }
                            Thread.sleep(10)
                        }
                    }
                }
            }
        } finally {
            // A red baseline must still unblock the fake write and release its daemon worker.
            remote.close()
        }
    }

    @Test
    fun `blocked upload after clean return EOF expires and its sole permit can serve another request`() {
        assertBlockedWriteExpires(allowFirstTlsWrite = true)
    }

    @Test
    fun `blocked first TLS write expires and its sole permit can serve another request`() {
        assertBlockedWriteExpires(allowFirstTlsWrite = false)
    }

    @Test
    fun `fully idle established session expires and its sole permit can serve another request`() {
        ServerSocket(0, 2, loopback).use { server ->
            val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            thread(isDaemon = true) {
                try {
                    repeat(2) { index ->
                        server.accept().use { remote ->
                            remote.soTimeout = 3000
                            DataInputStream(remote.getInputStream()).readFully(ByteArray(record.size))
                            remote.getOutputStream().write(42 + index)
                            remote.getOutputStream().flush()
                            // Neither side half-closes the first connection: only the idle timer can end it.
                            remote.getInputStream().read() shouldBe -1
                        }
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    done.countDown()
                }
            }
            relay({ _, _ -> Socket(loopback, server.localPort) }, capacity = 1, idleTimeoutMillis = 300).use { proxy ->
                client(proxy).use { first ->
                    connect(first) shouldBe 0
                    first.getOutputStream().write(record)
                    first.getInputStream().read() shouldBe 42
                    first.getInputStream().read() shouldBe -1
                    eventually { proxy.activeConnectionCount == 0 }
                    client(proxy).use { second ->
                        connect(second) shouldBe 0
                        second.getOutputStream().write(record)
                        second.getInputStream().read() shouldBe 43
                    }
                }
                done.await(3, TimeUnit.SECONDS) shouldBe true
                eventually { proxy.activeConnectionCount == 0 }
                failure.get()?.let { throw AssertionError("Remote fixture failed", it) }
            }
        }
    }

    @Test
    fun `sixteen trickling SOCKS handshakes stay capped then expire without renewing the deadline`() {
        relay(
            { _, _ -> throw IOException("SOCKS greeting must not reach a remote") },
            capacity = 16,
            handshakeTimeoutMillis = 1000,
        ).use { proxy ->
            val clients = (0 until 16).map { client(proxy) }
            try {
                clients.forEach { it.getOutputStream().write(byteArrayOf(5, 255.toByte())) }
                eventually { proxy.activeConnectionCount == 16 }
                trickle(clients).use {
                    client(proxy).use { extra -> extra.getInputStream().read() shouldBe -1 }
                    // Without a total deadline the ongoing 25 ms trickle defeats SO_TIMEOUT indefinitely.
                    clients.forEach(::assertClosed)
                    eventually { proxy.activeConnectionCount == 0 }
                }
                client(proxy).use { replacement -> connect(replacement, port = 80) shouldBe 2 }
            } finally {
                clients.forEach { it.close() }
            }
        }
    }

    @Test
    fun `trickling first TLS payload expires and releases both sockets and admission`() {
        ServerSocket(0, 1, loopback).use { server ->
            val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            thread(isDaemon = true) {
                try {
                    server.accept().use { remote ->
                        remote.soTimeout = 3000
                        remote.getInputStream().read() shouldBe -1
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    done.countDown()
                }
            }
            relay(
                { _, _ -> Socket(loopback, server.localPort) },
                capacity = 1,
                handshakeTimeoutMillis = 600,
            ).use { proxy ->
                client(proxy).use { socket ->
                    connect(socket) shouldBe 0
                    socket.getOutputStream().write(byteArrayOf(22, 3, 3, 0x40, 0))
                    trickle(listOf(socket)).use {
                        assertClosed(socket)
                        done.await(3, TimeUnit.SECONDS) shouldBe true
                        eventually { proxy.activeConnectionCount == 0 }
                    }
                }
                failure.get()?.let { throw AssertionError("Remote fixture failed", it) }
                client(proxy).use { replacement -> connect(replacement, port = 80) shouldBe 2 }
            }
        }
    }

    @Test
    fun `scope violations and non HTTPS ports are refused before connecting`() {
        var attempts = 0
        relay({ _, _ ->
            attempts++
            throw IOException("must not connect")
        }).use { proxy ->
            for ((host, port) in listOf(
                "hitomi.la" to 80,
                "hitomi.la.attacker.test" to 443,
                "private.tail123.ts.net" to 443,
            )) {
                client(proxy).use { socket ->
                    connect(socket, host, port) shouldBe 2
                    socket.getInputStream().read() shouldBe -1
                }
            }
            eventually { proxy.activeConnectionCount == 0 }
            attempts shouldBe 0
        }
    }

    @Test
    fun `connect failure returns SOCKS failure and releases client`() {
        relay({ _, _ -> throw IOException("fixture connection failure") }).use { proxy ->
            client(proxy).use { socket ->
                connect(socket) shouldBe 4
                socket.getInputStream().read() shouldBe -1
            }
            eventually { proxy.activeConnectionCount == 0 }
        }
    }

    @Test
    fun `fragmented first flight and later bytes relay unchanged in both directions`() {
        ServerSocket(0, 1, loopback).use { server ->
            val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val tail = byteArrayOf(9, 8, 7)
            val worker = thread(isDaemon = true) {
                try {
                    server.accept().use { remote ->
                        remote.soTimeout = 3000
                        val received = ByteArray(record.size + tail.size).also {
                            DataInputStream(remote.getInputStream()).readFully(it)
                        }
                        received.toList() shouldBe (record + tail).toList()
                        remote.getOutputStream().write(byteArrayOf(4, 5, 6))
                        remote.getOutputStream().flush()
                        remote.getInputStream().read() shouldBe -1
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    done.countDown()
                }
            }
            relay(
                connector = { _, port ->
                    port shouldBe 443
                    Socket(loopback, server.localPort)
                },
                splitter = { it.copyOfRange(0, 6) to it.copyOfRange(6, it.size) },
            ).use { proxy ->
                client(proxy).use { socket ->
                    connect(socket) shouldBe 0
                    socket.getOutputStream().write(record + tail)
                    socket.getOutputStream().flush()
                    ByteArray(3).also { DataInputStream(socket.getInputStream()).readFully(it) }.toList() shouldBe
                        listOf<Byte>(4, 5, 6)
                    proxy.fragmentedConnections shouldBe 1L
                }
                done.await(3, TimeUnit.SECONDS) shouldBe true
                eventually { proxy.activeConnectionCount == 0 }
                failure.get()?.let { throw AssertionError("Remote fixture failed", it) }
            }
            worker.join(1000)
        }
    }

    @Test
    fun `client half close preserves the remote response before releasing both sides`() {
        ServerSocket(0, 1, loopback).use { server ->
            val disconnected = CountDownLatch(1)
            thread(isDaemon = true) {
                server.accept().use { remote ->
                    remote.soTimeout = 3000
                    DataInputStream(remote.getInputStream()).readFully(ByteArray(record.size))
                    if (remote.getInputStream().read() == -1) {
                        // A server may wait for request EOF before returning its final response.
                        remote.getOutputStream().write(byteArrayOf(7, 8, 9))
                        remote.getOutputStream().flush()
                        disconnected.countDown()
                    }
                }
            }
            relay({ _, _ -> Socket(loopback, server.localPort) }).use { proxy ->
                client(proxy).use { socket ->
                    connect(socket) shouldBe 0
                    socket.getOutputStream().write(record)
                    socket.getOutputStream().flush()
                    socket.shutdownOutput()
                    ByteArray(3).also { DataInputStream(socket.getInputStream()).readFully(it) }.toList() shouldBe
                        listOf<Byte>(7, 8, 9)
                    socket.getInputStream().read() shouldBe -1
                    disconnected.await(3, TimeUnit.SECONDS) shouldBe true
                }
                eventually { proxy.activeConnectionCount == 0 }
            }
        }
    }

    @Test
    fun `saturated relay rejects additional clients and close releases stalled handshake`() {
        relay({ _, _ -> throw IOException("not reached") }, capacity = 1).use { proxy ->
            client(proxy).use { first ->
                eventually { proxy.activeConnectionCount == 1 }
                client(proxy).use { second -> second.getInputStream().read() shouldBe -1 }
                proxy.close()
                first.getInputStream().read() shouldBe -1
                eventually { proxy.activeConnectionCount == 0 }
            }
        }
    }

    @Test
    fun `continuous download survives idle timeout on the quiet upload direction`() {
        ServerSocket(0, 1, loopback).use { server ->
            val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            thread(isDaemon = true) {
                try {
                    server.accept().use { remote ->
                        remote.soTimeout = 3000
                        DataInputStream(remote.getInputStream()).readFully(ByteArray(record.size))
                        repeat(12) { index ->
                            remote.getOutputStream().write(index)
                            remote.getOutputStream().flush()
                            Thread.sleep(100) // Total transfer exceeds the relay's 600 ms idle limit.
                        }
                        remote.shutdownOutput()
                        remote.getInputStream().read() shouldBe -1
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    done.countDown()
                }
            }
            relay({ _, _ -> Socket(loopback, server.localPort) }, idleTimeoutMillis = 600).use { proxy ->
                client(proxy).use { socket ->
                    connect(socket) shouldBe 0
                    socket.getOutputStream().write(record)
                    socket.getOutputStream().flush()
                    ByteArray(12).also { DataInputStream(socket.getInputStream()).readFully(it) }.toList() shouldBe
                        (0 until 12).map { it.toByte() }
                    socket.getInputStream().read() shouldBe -1
                    socket.shutdownOutput()
                }
                done.await(3, TimeUnit.SECONDS) shouldBe true
                eventually { proxy.activeConnectionCount == 0 }
                failure.get()?.let { throw AssertionError("Remote fixture failed", it) }
            }
        }
    }

    @Test
    fun `multi record first flight is collected only while parser requests more`() {
        ServerSocket(0, 1, loopback).use { server ->
            val done = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            thread(isDaemon = true) {
                try {
                    server.accept().use { remote ->
                        remote.soTimeout = 3000
                        ByteArray(record.size * 2).also {
                            DataInputStream(remote.getInputStream()).readFully(it)
                        }.toList() shouldBe (record + record).toList()
                        remote.getOutputStream().write(42)
                        remote.getOutputStream().flush()
                    }
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    done.countDown()
                }
            }
            relay(
                connector = { _, _ -> Socket(loopback, server.localPort) },
                more = { it.size < record.size * 2 },
            ).use { proxy ->
                client(proxy).use { socket ->
                    connect(socket) shouldBe 0
                    socket.getOutputStream().write(record + record)
                    socket.getOutputStream().flush()
                    socket.getInputStream().read() shouldBe 42
                }
                done.await(3, TimeUnit.SECONDS) shouldBe true
                eventually { proxy.activeConnectionCount == 0 }
                failure.get()?.let { throw AssertionError("Remote fixture failed", it) }
            }
        }
    }

    @Test
    fun `literal IP SOCKS requests cannot bypass hostname scope`() {
        relay({ _, _ -> throw IOException("must not connect") }).use { proxy ->
            client(proxy).use { socket ->
                val input = DataInputStream(socket.getInputStream())
                socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                input.readFully(ByteArray(2))
                socket.getOutputStream().write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1, 1, 0xBB.toByte()))
                ByteArray(10).also(input::readFully)[1] shouldBe 8.toByte()
            }
            eventually { proxy.activeConnectionCount == 0 }
        }
    }

    @Test
    fun `incomplete first TLS record is bounded and remote socket closes`() {
        ServerSocket(0, 1, loopback).use { server ->
            val disconnected = CountDownLatch(1)
            thread(isDaemon = true) {
                server.accept().use { remote ->
                    remote.soTimeout = 3000
                    if (remote.getInputStream().read() == -1) disconnected.countDown()
                }
            }
            relay({ _, _ -> Socket(loopback, server.localPort) }).use { proxy ->
                client(proxy).use { socket ->
                    connect(socket) shouldBe 0
                    socket.getOutputStream().write(record.copyOf(6))
                    socket.getOutputStream().flush()
                    socket.getInputStream().read() shouldBe -1
                    disconnected.await(3, TimeUnit.SECONDS) shouldBe true
                }
                eventually { proxy.activeConnectionCount == 0 }
            }
        }
    }
}
