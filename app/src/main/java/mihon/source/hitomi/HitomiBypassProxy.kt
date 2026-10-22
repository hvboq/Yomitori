package mihon.source.hitomi

import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One loopback listener per app process; recreating a source never creates another listener. */
internal object HitomiBypassProxy {
    private val relay by lazy { HitomiSocksProxy() }
    val proxy: Proxy get() = relay.proxy
    val fragmentedConnections: Long get() = relay.fragmentedConnections
}

/**
 * Bounded SOCKS5 CONNECT relay. It does not terminate TLS or change certificates/SNI/HTTP bytes.
 * Only the first ClientHello's TLS record framing may be split; all other bytes pass unchanged.
 */
internal class HitomiSocksProxy(
    private val resolver: Dns = HitomiDohDns,
    private val connector: (InetAddress, Int) -> Socket = { address, port ->
        Socket(Proxy.NO_PROXY).apply {
            try {
                connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            } catch (error: Throwable) {
                close()
                throw error
            }
        }
    },
    private val fragmenter: (ByteArray) -> Pair<ByteArray, ByteArray>? = TlsClientHelloFragmenter::split,
    private val needsMoreData: (ByteArray) -> Boolean = TlsClientHelloFragmenter::needsMoreData,
    private val splitDelayMillis: Long = 20,
    maxConnections: Int = 16,
    private val handshakeTimeoutMillis: Int = 15_000,
    private val idleTimeoutMillis: Int = 30_000,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val fragmentationCount = AtomicLong()
    private val connections = ConcurrentHashMap.newKeySet<Connection>()
    private val admission = Semaphore(maxConnections)
    private val workers = pool("hitomi-socks", maxConnections)
    private val returns = pool("hitomi-return", maxConnections)
    private val listener = ServerSocket().apply {
        bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), 0), maxConnections)
    }
    val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", listener.localPort))
    internal val activeConnectionCount: Int get() = connections.size
    internal val fragmentedConnections: Long get() = fragmentationCount.get()
    private val acceptThread = Thread(::accept, "hitomi-socks-accept").apply {
        isDaemon = true
        start()
    }

    private fun accept() {
        while (!closed.get()) {
            val client = try {
                listener.accept()
            } catch (_: IOException) {
                break
            }
            if (!admission.tryAcquire()) {
                client.close()
                continue
            }
            val connection = Connection(client)
            connections.add(connection)
            if (closed.get()) {
                finish(connection)
                continue
            }
            try {
                workers.execute { serve(connection) }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                finish(connection) // No queue: slow clients cannot grow memory or worker count.
            }
        }
    }

    private fun serve(connection: Connection) {
        val client = connection.client
        var upstreamCompleted = false
        try {
            client.soTimeout = handshakeTimeoutMillis
            client.tcpNoDelay = true
            val transportInput = client.getInputStream()
            val input = DataInputStream(HandshakeInputStream(client, transportInput, handshakeTimeoutMillis))
            val output = client.getOutputStream()
            if (input.readUnsignedByte() != 5) return
            val methodCount = input.readUnsignedByte()
            val methods = ByteArray(methodCount).also(input::readFully)
            val acceptsNoAuth = methods.any { it == 0.toByte() }
            output.write(byteArrayOf(5, if (acceptsNoAuth) 0 else 0xFF.toByte()))
            output.flush()
            if (!acceptsNoAuth) return
            val version = input.readUnsignedByte()
            val command = input.readUnsignedByte()
            val reserved = input.readUnsignedByte()
            val addressType = input.readUnsignedByte()
            if (version != 5 || reserved != 0 || command != 1) {
                reply(client, 7)
                return
            }
            if (addressType != 3) {
                reply(client, 8) // Domain names are mandatory so IP literals cannot bypass scope checks.
                return
            }
            val name = ByteArray(input.readUnsignedByte()).also(input::readFully)
            val hostname = name.toString(Charsets.US_ASCII)
            val port = input.readUnsignedShort()
            if (port != 443 || !HitomiDohDns.allows(hostname)) {
                reply(client, 2)
                return
            }
            val remote = try {
                connect(hostname, port)
            } catch (_: IOException) {
                reply(client, 4)
                return
            }
            if (!connection.attach(remote)) return
            remote.tcpNoDelay = true
            remote.soTimeout = minOf(idleTimeoutMillis, READ_TICK_MS)
            reply(client, 0)
            // DNS/connect has its own bounds; TLS gets a new, non-renewable read budget.
            val firstFlight = readFirstFlight(
                DataInputStream(HandshakeInputStream(client, transportInput, handshakeTimeoutMillis)),
            )
            val fragments = fragmenter(firstFlight)
            val upstream = remote.getOutputStream()
            if (fragments == null) {
                upstream.write(firstFlight)
                upstream.flush()
            } else {
                upstream.write(fragments.first)
                upstream.flush()
                if (splitDelayMillis > 0) Thread.sleep(splitDelayMillis)
                upstream.write(fragments.second)
                upstream.flush()
                fragmentationCount.incrementAndGet()
            }
            client.soTimeout = minOf(idleTimeoutMillis, READ_TICK_MS)
            connection.touch()
            returns.execute {
                try {
                    pump(remote.getInputStream(), client.getOutputStream(), connection)
                    client.shutdownOutput()
                    if (connection.directionCompleted()) finish(connection)
                } catch (_: IOException) {
                    // A real I/O error closes both directions; a clean EOF only half-closes its peer.
                    finish(connection)
                }
            }
            pump(transportInput, upstream, connection)
            remote.shutdownOutput()
            upstreamCompleted = true
            if (connection.directionCompleted()) finish(connection)
        } catch (_: IOException) {
            // No request host, credentials, TLS content or server response is logged.
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Process shutdown or a saturated return pool: close rather than queue a socket.
        } finally {
            if (!upstreamCompleted) finish(connection)
        }
    }

    private fun connect(hostname: String, port: Int): Socket {
        var failure: IOException? = null
        val addresses = resolver.lookup(hostname).sortedBy { it.address.size }.take(MAX_ADDRESSES)
        for (address in addresses) {
            if (closed.get()) throw IOException("Hitomi relay is closed")
            try {
                return connector(address, port)
            } catch (error: IOException) {
                failure = error
            }
        }
        throw failure ?: IOException("Hitomi DNS returned no usable addresses")
    }

    /** A quiet upload direction must not time out while a large image is still downloading. */
    private fun pump(input: InputStream, output: OutputStream, connection: Connection) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val size = try {
                input.read(buffer)
            } catch (timeout: SocketTimeoutException) {
                if (connection.idleNanos() >= TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis.toLong())) throw timeout
                continue
            }
            if (size < 0) return
            if (size == 0) continue
            connection.touch()
            output.write(buffer, 0, size)
            connection.touch()
        }
    }

    private fun readFirstFlight(input: DataInputStream): ByteArray {
        val flight = ByteArrayOutputStream()
        do {
            val header = ByteArray(5).also(input::readFully)
            if (header[0] != 22.toByte() || header[1] != 3.toByte()) throw IOException("Expected TLS ClientHello")
            val size = ((header[3].toInt() and 255) shl 8) or (header[4].toInt() and 255)
            if (size !in 1..MAX_RECORD_BYTES || flight.size() + size + 5 > MAX_CLIENT_HELLO_BYTES) {
                throw IOException("TLS ClientHello exceeds relay limits")
            }
            flight.write(header)
            flight.write(ByteArray(size).also(input::readFully))
        } while (needsMoreData(flight.toByteArray()))
        return flight.toByteArray()
    }

    private fun reply(client: Socket, status: Int) {
        client.getOutputStream().apply {
            write(byteArrayOf(5, status.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
            flush()
        }
    }

    private fun finish(connection: Connection) {
        if (!connection.claimCompletion()) return
        connection.close()
        connections.remove(connection)
        admission.release()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listener.close()
        connections.toList().forEach(::finish)
        workers.shutdownNow()
        returns.shutdownNow()
    }

    /** A socket's SO_TIMEOUT alone restarts after each byte and cannot bound a trickling handshake. */
    private class HandshakeInputStream(
        private val socket: Socket,
        private val input: InputStream,
        timeoutMillis: Int,
    ) : InputStream() {
        private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis.toLong())

        init {
            require(timeoutMillis > 0)
        }

        private fun remainingMillis(): Int {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw SocketTimeoutException("Hitomi handshake deadline exceeded")
            // Round up: zero disables the socket timeout rather than expiring it.
            return ((remaining + 999_999) / 1_000_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        override fun read(): Int {
            socket.soTimeout = remainingMillis()
            return input.read().also { remainingMillis() }
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            socket.soTimeout = remainingMillis()
            return input.read(bytes, offset, length).also { remainingMillis() }
        }
    }

    private class Connection(val client: Socket) : AutoCloseable {
        private var remote: Socket? = null
        private var closed = false
        private val finished = AtomicBoolean(false)
        private val completedDirections = AtomicInteger()
        private val lastActivity = AtomicLong(System.nanoTime())

        fun claimCompletion(): Boolean = finished.compareAndSet(false, true)
        fun directionCompleted(): Boolean = completedDirections.incrementAndGet() == 2
        fun touch() {
            lastActivity.set(System.nanoTime())
        }
        fun idleNanos(): Long = System.nanoTime() - lastActivity.get()

        @Synchronized fun attach(socket: Socket): Boolean {
            if (closed) {
                socket.close()
                return false
            }
            remote = socket
            return true
        }

        @Synchronized override fun close() {
            if (closed) return
            closed = true
            runCatching { client.close() }
            runCatching { remote?.close() }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val MAX_ADDRESSES = 6
        private const val MAX_RECORD_BYTES = 18_432
        private const val MAX_CLIENT_HELLO_BYTES = 128 * 1024
        private const val READ_TICK_MS = 1_000

        private fun pool(name: String, size: Int): ThreadPoolExecutor {
            require(size in 1..16)
            val sequence = AtomicInteger()
            return ThreadPoolExecutor(0, size, 30, TimeUnit.SECONDS, SynchronousQueue<Runnable>()) { task ->
                Thread(task, "$name-${sequence.incrementAndGet()}").apply { isDaemon = true }
            }
        }
    }
}
