package mihon.translation

import android.content.Context
import android.os.SystemClock
import android.util.Xml
import android.view.accessibility.AccessibilityNodeInfo
import androidx.preference.PreferenceManager
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Response
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.source.service.SourceManager
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Inspect/click a real Compose action by localized label; never fall back to a ViewModel callback. */
@Suppress("DEPRECATION")
internal fun checkOnlineReaderAction(
    packageName: String,
    expectedLabel: String,
    forbiddenLabel: String,
    click: Boolean,
    beforeClick: () -> Unit,
) {
    fun find(node: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && matches(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                find(child, matches)?.let { return it }
            } finally {
                child.recycle()
            }
        }
        return null
    }

    fun exactLabel(node: AccessibilityNodeInfo, label: String) =
        node.text?.toString() == label || node.contentDescription?.toString() == label

    fun clickClickableParent(node: AccessibilityNodeInfo): Boolean {
        var candidate: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
        try {
            while (candidate != null) {
                val current = candidate
                if (current.isEnabled && current.isClickable && current.isVisibleToUser) {
                    return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                candidate = current.parent
                current.recycle()
            }
            return false
        } finally {
            candidate?.recycle()
        }
    }

    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val deadline = SystemClock.elapsedRealtime() + 10000
    var nextScroll = 0L
    while (SystemClock.elapsedRealtime() < deadline) {
        val root = instrumentation.uiAutomation.rootInActiveWindow
        if (root != null) {
            try {
                if (root.packageName?.toString() == packageName) {
                    val expected = find(root) { exactLabel(it, expectedLabel) }
                    if (expected != null) {
                        try {
                            val forbidden = find(root) { exactLabel(it, forbiddenLabel) }
                            try {
                                check(forbidden == null) { "Start and stop actions must be mutually exclusive" }
                            } finally {
                                forbidden?.recycle()
                            }
                            beforeClick()
                            if (click) {
                                check(clickClickableParent(expected)) { "Visible reader action was not clickable" }
                            }
                            return
                        } finally {
                            expected.recycle()
                        }
                    }
                    if (SystemClock.elapsedRealtime() >= nextScroll) {
                        val scrollable = find(root) { it.isEnabled && it.isScrollable }
                        try {
                            scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        } finally {
                            scrollable?.recycle()
                        }
                        nextScroll = SystemClock.elapsedRealtime() + 700
                    }
                }
            } finally {
                root.recycle()
            }
        }
        SystemClock.sleep(100)
    }
    error("Reader dialog did not expose the expected action: $expectedLabel")
}

/** Test-only source: no Hitomi requests and no replacement of the configured translation server. */
internal class OnlineReaderFixtureServer(private val pages: List<ByteArray>) : Closeable {
    private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newFixedThreadPool(4) { task ->
        Thread(task, "online-reader-fixture-http").apply { isDaemon = true }
    }
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val releaseImages = CountDownLatch(1)
    val heldImageRequest = CountDownLatch(1)
    val requests = CopyOnWriteArrayList<String>()
    val failPageLists: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    var holdImages = false

    @Volatile
    private var closed = false

    @Volatile
    var failure: Throwable? = null
        private set

    val baseUrl = "http://127.0.0.1:${socket.localPort}"
    val source = object : HttpSource() {
        override val name = "Online reader synthetic fixture ${System.nanoTime()}"
        override val lang = "ja"
        override val supportsLatest = false
        override val baseUrl = this@OnlineReaderFixtureServer.baseUrl

        override fun pageListParse(response: Response): List<Page> {
            val paths = JSONArray(response.body.string())
            // Downloader legitimately reuses ChapterCache images by image URL. Distinct URLs per
            // page-list response force this test through its real HTTP download path as well.
            val fetch = System.nanoTime()
            return (0 until paths.length()).map { index ->
                val imageUrl = baseUrl + paths.getString(index) + "?fetch=$fetch"
                Page(index, imageUrl, imageUrl)
            }
        }
    }

    private val acceptor = Thread({
        try {
            while (!closed) {
                val client = socket.accept()
                clients += client
                workers.execute { serve(client) }
            }
        } catch (error: Exception) {
            if (!closed) failure = error
        }
    }, "online-reader-fixture-accept").apply {
        isDaemon = true
        start()
    }

    private fun serve(client: Socket) {
        try {
            client.use {
                it.soTimeout = 30000
                val input = it.getInputStream().bufferedReader(Charsets.US_ASCII)
                val request = checkNotNull(input.readLine()).split(' ')
                check(request.size >= 2 && request[0] == "GET")
                var headers = 0
                while (true) {
                    val line = checkNotNull(input.readLine())
                    if (line.isEmpty()) break
                    check(++headers <= 100 && line.length <= 8192)
                }
                val path = request[1].substringBefore('?')
                requests += path
                if (path in failPageLists) {
                    // An expected failure response exercises Downloader's real ERROR transition.
                    it.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n" +
                                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                                )
                                .toByteArray(Charsets.US_ASCII),
                        )
                        flush()
                    }
                    return
                }
                val imageIndex = Regex("/image/([1-9][0-9]*)\\.png").matchEntire(path)
                    ?.groupValues?.get(1)?.toIntOrNull()?.minus(1)
                    ?.takeIf { index -> index in pages.indices }
                val body: ByteArray
                val type: String
                if (path == "/chapter" || path == "/chapter-b") {
                    body = JSONArray(pages.indices.map { index -> "/image/${index + 1}.png" })
                        .toString().toByteArray()
                    type = "application/json"
                } else {
                    check(imageIndex != null) { "Unexpected fixture route" }
                    if (holdImages) {
                        heldImageRequest.countDown()
                        check(releaseImages.await(45, TimeUnit.SECONDS)) { "Download observation gate timed out" }
                    }
                    body = pages[imageIndex]
                    type = "image/png"
                }
                it.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                                "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                            ).toByteArray(Charsets.US_ASCII),
                    )
                    write(body)
                    flush()
                }
            }
        } catch (error: Exception) {
            if (!closed) failure = error
        } finally {
            clients -= client
        }
    }

    fun releaseDownload() = releaseImages.countDown()

    override fun close() {
        closed = true
        releaseDownload()
        socket.close()
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        acceptor.join(3000)
        check(workers.awaitTermination(3, TimeUnit.SECONDS)) { "Fixture HTTP workers did not stop" }
    }
}

/** Register exactly one test source; never replace or restore other sources' maps. */
internal class OnlineReaderSourceRegistration(manager: SourceManager, private val source: Source) : Closeable {
    @Suppress("UNCHECKED_CAST")
    private val map = manager.javaClass.getDeclaredField("sourcesMapFlow").let { field ->
        field.isAccessible = true
        field.get(manager) as MutableStateFlow<ConcurrentHashMap<Long, Source>>
    }

    init {
        map.update { old ->
            check(!old.containsKey(source.id)) { "Fixture source ID collision" }
            ConcurrentHashMap(old).apply { put(source.id, source) }
        }
        check(manager.get(source.id) === source)
    }

    override fun close() {
        map.update { old ->
            ConcurrentHashMap(old).apply { if (get(source.id) === source) remove(source.id) }
        }
        check(map.value[source.id] !== source)
    }
}

/** Presence-aware restoration plus a disk barrier, including integer and set preferences. */
internal class OnlineReaderSettings(private val context: Context) : Closeable {
    private val restores = linkedMapOf<String, () -> Unit>()
    private val verifies = mutableListOf<() -> Unit>()

    fun <T> set(preference: Preference<T>, value: T) {
        if (preference.key() !in restores) {
            val existed = preference.isSet()
            val previous = preference.get()
            restores[preference.key()] = { if (existed) preference.set(previous) else preference.delete() }
            verifies += { check(preference.isSet() == existed && preference.get() == previous) }
        }
        preference.set(value)
    }

    override fun close() {
        val failures = mutableListOf<Throwable>()
        fun attempt(label: String, block: () -> Unit) {
            try {
                block()
            } catch (_: Throwable) {
                failures += AssertionError(label) // Never expose preference values or credentials.
            }
        }
        restores.entries.toList().asReversed().forEach { (key, restore) -> attempt("Restore $key", restore) }
        val shared = PreferenceManager.getDefaultSharedPreferences(context)
        attempt("Preference disk barrier") { check(shared.edit().commit()) }
        verifies.forEachIndexed { index, verify -> attempt("Preference assertion $index", verify) }
        attempt("Persisted settings differ from restored values") {
            val expected = shared.all.filterKeys { it in restores }
            val actual = mutableMapOf<String, Any>()
            File(context.applicationInfo.dataDir, "shared_prefs/${context.packageName}_preferences.xml")
                .inputStream().use { input ->
                    val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
                    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG) {
                            val key = parser.getAttributeValue(null, "name")
                            if (key in restores) {
                                actual[checkNotNull(key)] = when (parser.name) {
                                    "string" -> parser.nextText()
                                    "boolean" -> parser.getAttributeValue(null, "value").toBooleanStrict()
                                    "int" -> parser.getAttributeValue(null, "value").toInt()
                                    "set" -> buildSet {
                                        val depth = parser.depth
                                        while (parser.next() != XmlPullParser.END_DOCUMENT) {
                                            if (parser.eventType == XmlPullParser.END_TAG && parser.depth == depth) {
                                                break
                                            }
                                            if (parser.eventType == XmlPullParser.START_TAG &&
                                                parser.name == "string"
                                            ) {
                                                add(parser.nextText())
                                            }
                                        }
                                    }
                                    else -> error("Unexpected preference type")
                                }
                            }
                        }
                        parser.next()
                    }
                }
            check(expected == actual)
        }
        if (failures.isNotEmpty()) {
            throw AssertionError("Online reader preference cleanup failed").apply { failures.forEach(::addSuppressed) }
        }
    }
}
