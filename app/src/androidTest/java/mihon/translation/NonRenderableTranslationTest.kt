package mihon.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import li.joye.yakuyomi.engine.LlmTranslator
import li.joye.yakuyomi.engine.TranslatorConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Non-UI engine HTTP boundary tests. Only a bounded loopback server and synthetic text are used;
 * no native models, Injekt, preferences, user files, or external translation server are touched.
 * These assertions do not claim Pipeline/native inpainting or renderer coverage.
 */
@RunWith(AndroidJUnit4::class)
class NonRenderableTranslationTest {
    @Test
    fun rejectsHttpBatchWhenOneTargetContainsOnlyFormatOrControlCharacters() = runBlocking<Unit> {
        val queries = listOf("一つ目", "二つ目")
        for (invalid in listOf("\u200B", "\uFEFF", "\u200D", "\u202E\u2069", "\u0000\u0007", " \n\u00A0\u200B\t")) {
            TranslationTextServer("<|1|>정상 번역\n<|2|>$invalid", queries).use { server ->
                val result = translator(server).translateDetailed(queries)
                assertNotNull("An unusable target must reject the whole batch", result.error)
                assertEquals("Failure must retain every source query", queries, result.translations)
                assertNull(result.raw)
                assertNull(result.usage)
                server.assertCompletedOnce()
            }
        }
    }

    @Test
    fun rejectsPostprocessedFormatOnlyTargetAfterAnOtherwiseValidHttpResponse() = runBlocking<Unit> {
        val queries = listOf("一つ目", "二つ目")
        for (invalid in listOf("\u200B", "\uFEFF\u200D", " \u202E\u2069\u0001")) {
            TranslationTextServer("<|1|>첫 번역\n<|2|>둘째 번역", queries).use { server ->
                val processed = mutableListOf<String>()
                val result = translator(server) { target ->
                    processed += target
                    if (target == "둘째 번역") invalid else target
                }.translateDetailed(queries)
                assertEquals("The response must reach the real postprocessor", listOf("첫 번역", "둘째 번역"), processed)
                assertNotNull("Postprocessing must not turn a successful batch into an unusable target", result.error)
                assertEquals(queries, result.translations)
                assertNull(result.raw)
                assertNull(result.usage)
                server.assertCompletedOnce()
            }
        }
    }

    @Test
    fun retainsKoreanArabicAndEmojiJoinersExactlyAcrossHttpAndPostprocessing() = runBlocking<Unit> {
        val queries = listOf("一", "二", "三", "四")
        val targets = listOf("한\u200B글\uFEFF 번역", "می\u200Cروم", "\u2067مرحبا\u2069", "👩\u200D💻")
        val content = targets.mapIndexed { index, target -> "<|${index + 1}|>$target" }.joinToString("\n")
        TranslationTextServer(content, queries).use { server ->
            val processed = mutableListOf<String>()
            val result = translator(server) { target -> target.also { processed += it } }.translateDetailed(queries)
            assertNull(result.error)
            assertEquals(targets, processed)
            assertEquals(
                "Validation must not strip meaningful joiners or direction controls",
                targets,
                result.translations,
            )
            server.assertCompletedOnce()
        }
    }

    private fun translator(server: TranslationTextServer, postProcess: ((String) -> String)? = null) = LlmTranslator(
        "",
        TranslatorConfig(
            provider = "custom",
            apiBase = server.url,
            model = "non-renderable-fixture",
            fromLangName = "Japanese",
            toLangName = "Korean",
            sampleSource = "",
            sampleTarget = "",
        ),
        postProcess,
    )
}

/** One bounded text-only request; records no user data and owns every socket/thread it creates. */
private class TranslationTextServer(content: String, expectedQueries: List<String>) : AutoCloseable {
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val finished = CountDownLatch(1)
    private val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
    private var requests = 0
    val url = "http://127.0.0.1:${listener.localPort}/v1/chat/completions"
    private val worker = thread(name = "non-renderable-translation-http", isDaemon = true) {
        try {
            listener.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream().buffered()
                fun line(): String {
                    val bytes = ByteArrayOutputStream()
                    while (true) {
                        val byte = input.read()
                        check(byte >= 0) { "Unexpected request EOF" }
                        if (byte == 10) return bytes.toString("UTF-8").trimEnd('\r')
                        check(bytes.size() < 8192) { "Request header exceeds fixture limit" }
                        bytes.write(byte)
                    }
                }
                check(line() == "POST /v1/chat/completions HTTP/1.1")
                val headers = buildMap {
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        put(header.substringBefore(':').lowercase(), header.substringAfter(':').trim())
                    }
                }
                check("authorization" !in headers)
                val length = headers.getValue("content-length").toInt()
                check(length in 1..65536)
                val bytes = ByteArray(length)
                DataInputStream(input).readFully(bytes)
                val body = JSONObject(bytes.toString(Charsets.UTF_8))
                check(body.getString("model") == "non-renderable-fixture")
                check(!body.getBoolean("stream"))
                val messages = body.getJSONArray("messages")
                check(messages.length() == 2)
                val regions = JSONArray(messages.getJSONObject(1).getString("content"))
                check(regions.length() == expectedQueries.size)
                expectedQueries.forEachIndexed { index, source ->
                    check(regions.getJSONObject(index).getInt("id") == index + 1)
                    check(regions.getJSONObject(index).getString("text") == source)
                }
                check(!bytes.toString(Charsets.UTF_8).contains("image_url"))
                requests++
                val response = JSONObject().put(
                    "choices",
                    JSONArray().put(
                        JSONObject().put("finish_reason", "stop")
                            .put("message", JSONObject().put("content", content)),
                    ),
                ).toString().toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                            ).toByteArray(Charsets.UTF_8),
                    )
                    write(response)
                    flush()
                }
            }
        } catch (error: Throwable) {
            failure.set(error)
        } finally {
            finished.countDown()
        }
    }

    fun assertCompletedOnce() {
        assertTrue("Loopback server did not finish", finished.await(6, TimeUnit.SECONDS))
        assertNull("Loopback fixture failed", failure.get())
        assertEquals(1, requests)
    }

    override fun close() {
        listener.close()
        worker.join(6000)
        check(!worker.isAlive) { "Loopback fixture thread did not terminate" }
    }
}
