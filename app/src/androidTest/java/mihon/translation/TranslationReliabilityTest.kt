package mihon.translation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.Detector
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.Inpainter
import li.joye.yakuyomi.engine.InpainterConfig
import li.joye.yakuyomi.engine.LlmModels
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.ModelSource
import li.joye.yakuyomi.engine.Ocr
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.Pipeline
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.Translator
import li.joye.yakuyomi.engine.TranslatorConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class TranslationReliabilityTest {
    private fun response(content: String, finish: String = "stop", refusal: String? = null): String = JSONObject().put(
        "choices",
        JSONArray().put(
            JSONObject().put("finish_reason", finish).put(
                "message",
                JSONObject().put("content", content).put("refusal", refusal ?: JSONObject.NULL),
            ),
        ),
    ).toString()

    private fun translator(server: OneResponseServer) = LlmTranslator(
        "",
        TranslatorConfig(
            provider = "custom",
            apiBase = server.url,
            model = "fixture",
            sampleSource = "",
            sampleTarget = "",
        ),
    )

    @Test
    fun rejectsMissingWrongDuplicateTruncatedAndRefusedResponses() = runBlocking<Unit> {
        val bad = listOf(
            response("<|1|>하나"),
            response("<|1|>하나\n<|99|>둘"),
            response("<|1|>하나\n<|1|>둘"),
            response("<|1|>하나\n<|2|>둘", "length"),
            response("<|1|>하나\n<|2|>둘", refusal = "refused"),
        )
        for (body in bad) {
            OneResponseServer { it.reply(body) }.use { server ->
                val result = translator(server).translateDetailed(listOf("一", "二"))
                assertNotNull(body, result.error)
                assertEquals(listOf("一", "二"), result.translations)
            }
        }
    }

    @Test
    fun preservesMultilineAndSameLineTranslationsOverHttp() = runBlocking<Unit> {
        OneResponseServer { it.reply(response("<|2|>둘 <|1|>하나\n계속")) }.use { server ->
            val result = translator(server).translateDetailed(listOf("一", "二"))
            assertNull(result.error)
            assertEquals(listOf("하나\n계속", "둘"), result.translations)
        }
    }

    @Test
    fun limitsDeclaredAndUnknownLengthResponseBodies() = runBlocking<Unit> {
        for (declared in listOf(true, false)) {
            OneResponseServer { socket -> socket.reply("x".repeat(1024 * 1024 + 1), declared) }.use { server ->
                val result = translator(server).translateDetailed(listOf("一"))
                assertTrue(result.error.orEmpty(), result.error.orEmpty().contains("exceeds 1 MiB"))
            }
        }
    }

    @Test
    fun cancellationClosesSocketBeforeHeadersAndDuringBodyRead() = runBlocking<Unit> {
        for (bodyStarted in listOf(false, true)) {
            val disconnected = CountDownLatch(1)
            OneResponseServer { socket ->
                if (bodyStarted) {
                    socket.getOutputStream().write(
                        "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n{\"choices\":".toByteArray(),
                    )
                    socket.getOutputStream().flush()
                }
                if (socket.getInputStream().read() == -1) disconnected.countDown()
            }.use { server ->
                val work = async { translator(server).translateDetailed(listOf("一")) }
                withTimeout(5000) { while (server.received.count != 0L) delay(10) }
                // Allow the callback to start reading the deliberately incomplete body.
                delay(100)
                withTimeout(2000) { work.cancelAndJoin() }
                assertTrue("Cancelled HTTP socket remained open", disconnected.await(2, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun modelDiscoverySupportsEmptyKeysAndKeepsGeminiKeysOutOfUrls() = runBlocking<Unit> {
        OneResponseServer("GET") { it.reply("{\"data\":[{\"id\":\"local-model\"}]}") }.use { server ->
            assertEquals(listOf("local-model"), LlmModels.list(server.url, ModelSource.OPENAI, "").map { it.id })
            assertFalse(server.requestHeaders.containsKey("authorization"))
        }
        OneResponseServer("GET") {
            val model = "{\"name\":\"models/test-model\",\"supportedGenerationMethods\":[\"generateContent\"]}"
            it.reply("{\"models\":[$model]}")
        }.use { server ->
            val models = LlmModels.list(server.url, ModelSource.GEMINI, "test-only-secret")
            assertEquals(listOf("test-model"), models.map { it.id })
            assertEquals("test-only-secret", server.requestHeaders["x-goog-api-key"])
            assertFalse(server.requestLine.contains("test-only-secret"))
            assertFalse(server.requestLine.contains("key="))
        }
    }

    @Test
    fun modelDiscoveryDoesNotFollowRedirectsOrExposeReflectedCredentials() = runBlocking<Unit> {
        OneResponseServer("GET") { it.reply("{\"data\":[]}") }.use { destination ->
            OneResponseServer("GET") { socket ->
                socket.getOutputStream().apply {
                    val headers = "HTTP/1.1 302 Found\r\nLocation: ${destination.url}\r\n" +
                        "Content-Length: 0\r\nConnection: close\r\n\r\n"
                    write(headers.toByteArray())
                    flush()
                }
            }.use { origin ->
                assertTrue(LlmModels.list(origin.url, ModelSource.OPENAI, "test-only-secret").isEmpty())
                assertEquals("HTTP 302", LlmModels.lastError)
                assertEquals(1L, destination.received.count)
            }
        }
        OneResponseServer("GET") { socket ->
            socket.getOutputStream().apply {
                val body = "credential reflected: test-only-secret".toByteArray()
                val headers = "HTTP/1.1 401 Unauthorized\r\nContent-Length: ${body.size}\r\n" +
                    "Connection: close\r\n\r\n"
                write(headers.toByteArray())
                write(body)
                flush()
            }
        }.use { server ->
            assertTrue(LlmModels.list(server.url, ModelSource.OPENAI, "test-only-secret").isEmpty())
            assertEquals("HTTP 401", LlmModels.lastError)
        }
    }

    @Test
    fun nativePipelineRejectsIncompleteResultsKeepsExcludedPixelsAndReportsAotFailure() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        Injekt.get<TranslationEngineService>().shutdown() // Avoid retaining a second set of native models.
        val models = File(context.filesDir, "models")
        val alphabet = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
        val page = Bitmap.createBitmap(1200, 1200, Bitmap.Config.ARGB_8888).apply {
            Canvas(this).apply {
                drawColor(Color.WHITE)
                // A neutral gradient remains easy to OCR but is deliberately not a verified flat
                // background. The released-AOT test below must actually reach native inference.
                drawRect(
                    0f,
                    0f,
                    1200f,
                    1200f,
                    Paint().apply {
                        shader = LinearGradient(
                            0f,
                            0f,
                            1200f,
                            0f,
                            Color.WHITE,
                            Color.rgb(215, 215, 215),
                            Shader.TileMode.CLAMP,
                        )
                    },
                )
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 72f
                }
                drawText("こんにちは。", 150f, 250f, paint)
                drawText("よろしくお願いします。", 150f, 950f, paint)
            }
        }
        val original = IntArray(page.width * page.height).also {
            page.getPixels(it, 0, page.width, 0, 0, page.width, page.height)
        }
        var mode = "missing"
        var regionCount = 0
        val fake = object : Translator {
            override suspend fun translate(queries: List<String>): List<String> {
                regionCount = queries.size
                return when (mode) {
                    "missing" -> queries.dropLast(1)
                    "mixed" -> queries.mapIndexed { i, text -> if (i == 0) "안녕하세요." else text }
                    else -> queries.map { "안녕하세요." }
                }
            }
        }
        val detector = Detector(File(models, "dbnet_detect.ncnn.param").path)
        val ocr = Ocr(File(models, "ocr_48px_ctc.ncnn.param").path, alphabet)
        val inpainter = Inpainter(File(models, "mit_aot_fixed512.ncnn.param").path, InpainterConfig(tileSize = 512))
        Pipeline(
            detector,
            ocr,
            fake,
            inpainter,
            EngineConfig(render = RenderConfig(orientation = TextOrientation.HORIZONTAL)),
        ).use { pipeline ->
            try {
                val missing = pipeline.translatePage(page)
                assertTrue(missing.toString(), missing is PageResult.Failed)
                assertTrue("Fixture must contain distinct regions", regionCount >= 2)
                mode = "mixed"
                val mixed = pipeline.translatePage(page)
                assertTrue(mixed.toString(), mixed is PageResult.Translated)
                mixed as PageResult.Translated
                try {
                    assertFalse(requireNotNull(mixed.analysis?.progress).hasPending)
                    assertEquals(1, mixed.analysis!!.regions.count { !it.preserveOriginal })
                    assertTrue(mixed.analysis!!.regions.any { it.preserveOriginal })
                    val kept = mixed.analysis!!.regions.single { !it.preserveOriginal }
                    // Compare the opposite half of the image, containing the excluded original text.
                    val y = if (kept.y0 < 600) 600 else 0
                    val before = IntArray(1200 * 600)
                    val after = IntArray(before.size)
                    page.getPixels(before, 0, 1200, 0, y, 1200, 600)
                    mixed.page.getPixels(after, 0, 1200, 0, y, 1200, 600)
                    assertArrayEquals("Excluded lettering pixels changed", before, after)
                    assertFalse(page.sameAs(mixed.page))
                } finally {
                    mixed.page.recycle()
                    mixed.analysis?.mask?.recycle()
                }
                // JNI explicitly returns -1 for a released (zero) handle; this exercises a real native error.
                inpainter.close()
                mode = "all"
                val failed = pipeline.translatePage(page)
                assertTrue(failed.toString(), failed is PageResult.Failed)
                assertTrue((failed as PageResult.Failed).reason, failed.reason.contains("rc=-1"))
                // A native OCR failure must not become OCR-empty/Skipped (which would mark the page done).
                ocr.close()
                val ocrFailed = pipeline.translatePage(page)
                assertTrue(ocrFailed.toString(), ocrFailed is PageResult.Failed)
                assertTrue((ocrFailed as PageResult.Failed).reason, ocrFailed.reason.startsWith("ocr:"))
                val after = IntArray(original.size).also {
                    page.getPixels(it, 0, page.width, 0, 0, page.width, page.height)
                }
                assertArrayEquals(original, after)
            } finally {
                page.recycle()
            }
        }
    }
}

/** One local HTTP request; no external endpoint or API key is used. */
private class OneResponseServer(expectedMethod: String = "POST", respond: (Socket) -> Unit) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val received = CountDownLatch(1)
    val url = "http://127.0.0.1:${server.localPort}/v1/chat/completions"

    @Volatile var requestLine = ""

    @Volatile var requestHeaders: Map<String, String> = emptyMap()
    private val worker = thread(isDaemon = true, name = "translation-reliability-http") {
        try {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream()
                fun line(): String {
                    val bytes = java.io.ByteArrayOutputStream()
                    while (true) {
                        val byte = input.read()
                        check(byte >= 0)
                        if (byte == 10) return bytes.toString("UTF-8").trimEnd('\r')
                        bytes.write(byte)
                    }
                }
                requestLine = line()
                check(requestLine.startsWith("$expectedMethod "))
                var length = 0
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val header = line()
                    if (header.isEmpty()) break
                    headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    if (header.startsWith("Content-Length:", ignoreCase = true)) {
                        length =
                            header.substringAfter(':').trim().toInt()
                    }
                }
                requestHeaders = headers
                java.io.DataInputStream(input).readFully(ByteArray(length))
                received.countDown()
                respond(socket)
            }
        } catch (_: java.io.IOException) {
            // Expected when the bounded reader or cancellation closes the socket early.
        }
    }

    override fun close() {
        server.close()
        worker.join(6000)
    }
}

private fun Socket.reply(body: String, declared: Boolean = true) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val length = if (declared) "Content-Length: ${bytes.size}\r\n" else ""
    getOutputStream().apply {
        write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n${length}Connection: close\r\n\r\n".toByteArray())
        write(bytes)
        flush()
    }
}
