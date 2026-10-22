package mihon.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in transport comparison with a neutral, explicitly supplied synthetic request. */
@RunWith(AndroidJUnit4::class)
class TranslationTransportDiagnosticTest {
    @Test
    fun compareHttpVersionsAndRequestBodies() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("translationTransport") == "true")
        val endpoint = requireNotNull(args.getString("liveTranslationBase")).trimEnd('/') + "/chat/completions"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = requireNotNull(context.getExternalFilesDir(null))
        val full = File(folder, "translation-probe-input.json").readText()
        require(full.length < 32_000)
        val minimal = JSONObject().put("model", JSONObject(full).getString("model"))
            .put("stream", false).put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put(
                            "content",
                            "Translate the Japanese greeting こんにちは into Korean. Return only the translation.",
                        ),
                ),
            ).toString()
        val rows = JSONArray()
        for (http1 in listOf(false, true)) {
            val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).apply {
                    if (http1) protocols(listOf(Protocol.HTTP_1_1))
                }.build()
            try {
                for ((name, json) in listOf("minimal" to minimal, "page" to full)) {
                    val row = JSONObject().put("case", name).put("forceHttp1", http1)
                    rows.put(row)
                    val start = System.nanoTime()
                    try {
                        val request = Request.Builder().url(endpoint)
                            .post(json.toRequestBody("application/json".toMediaType())).build()
                        client.newCall(request).execute().use { response ->
                            row.put("status", response.code).put("protocol", response.protocol.toString())
                                .put("server", response.header("Server"))
                                .put("contentType", response.header("Content-Type"))
                            // Only this opt-in synthetic probe keeps a bounded response excerpt.
                            val source = response.body.source()
                            source.request(8193)
                            val text = source.buffer.readUtf8(minOf(source.buffer.size, 8192))
                                .replace(Regex("(?i)(Bearer\\s+|sk-)[A-Za-z0-9_.-]+"), "[redacted]")
                            row.put("response", text)
                        }
                    } catch (error: Exception) {
                        row.put("errorType", error.javaClass.simpleName)
                    } finally {
                        row.put("durationMs", (System.nanoTime() - start) / 1_000_000)
                        File(folder, "translation-transport-result.json").writeText(
                            JSONObject().put("endpoint", endpoint).put("cases", rows).toString(2),
                        )
                    }
                }
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
        assertTrue(
            "At least one page request must succeed",
            (0 until rows.length()).any {
                rows.getJSONObject(it).let { row -> row.getString("case") == "page" && row.optInt("status") == 200 }
            },
        )
    }
}
