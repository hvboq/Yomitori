package mihon.translation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import li.joye.yakuyomi.engine.EngineConfig
import li.joye.yakuyomi.engine.FailureKind
import li.joye.yakuyomi.engine.InpainterConfig
import li.joye.yakuyomi.engine.ModelSet
import li.joye.yakuyomi.engine.PageResult
import li.joye.yakuyomi.engine.Pipeline
import li.joye.yakuyomi.engine.ProcessingFailure
import li.joye.yakuyomi.engine.ProcessingStage
import li.joye.yakuyomi.engine.RenderConfig
import li.joye.yakuyomi.engine.TextOrientation
import li.joye.yakuyomi.engine.TranslatorConfig
import li.joye.yakuyomi.engine.Yakuyomi
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Opt-in real model construction/DBNet/OCR -> actual LLM -> Pipeline failure propagation.
 * The owned server always returns 503. No external server, Activity, user page or settings are used.
 * Model loading/OCR/retry/check overhead is included; this is not a performance measurement.
 */
@RunWith(AndroidJUnit4::class)
class TranslationFailureNativeTest {
    @Test
    fun nativePipelineRetainsHttpServerFailureAfterTheBoundedPageRetry(): Unit = runBlocking(Dispatchers.IO) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("translationFailureNative") == "true") {
            "Explicit translationFailureNative=true is mandatory; missing opt-in is not a pass or skip"
        }
        val context = instrumentation.targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "An isolated audit installation is mandatory" }
        check(Process.is64Bit() && "arm64-v8a" in Build.SUPPORTED_ABIS) { "An actual ARM64 process is mandatory" }
        val shared = Injekt.get<TranslationEngineService>()
        requireSharedIdle(shared)

        // The controller also checks the seven complete model hashes before and after this SDK.
        val modelDirectory = File(context.filesDir, "models").canonicalFile
        val names = listOf(
            "dbnet_detect.ncnn.param",
            "dbnet_detect.ncnn.bin",
            "ocr_48px_ctc.ncnn.param",
            "ocr_48px_ctc_mixed.ncnn.param",
            "ocr_48px_ctc.ncnn.bin",
            "mit_aot_fixed512.ncnn.param",
            "mit_aot_fixed512.ncnn.bin",
        )
        val files = names.map { name ->
            File(modelDirectory, name).also {
                check(it.isFile && it.length() > 0 && it.canonicalFile.parentFile == modelDirectory) {
                    "A required private native model is missing or outside its model directory: $name"
                }
            }
        }
        val beforeModels = files.map { it.name to (it.length() to it.lastModified()) }.toMap()
        val models = checkNotNull(ModelSet.resolve(files.map { it.name to it.absolutePath }))
        val alphabet = context.assets.open("models/alphabet-all-v5.txt").bufferedReader().use { it.readLines() }
        check(alphabet.size == 19_264 && listOf("。", "、", "「", "」").all { it in alphabet }) {
            "The expected OCR alphabet is unavailable"
        }
        val inputBytes = instrumentation.context.assets.open("native-quality/vertical-punctuation.png").use {
            it.readBytes()
        }
        assertEquals("The existing neutral CC0 fixture must remain exact", INPUT_SHA, sha256(inputBytes))
        val original = checkNotNull(BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size))
        var result: PageResult? = null
        try {
            assertEquals(1200, original.width)
            assertEquals(1600, original.height)
            val originalPixels = pixelSha256(original)
            NativeGatewayServer().use { server ->
                val config = EngineConfig(
                    inpainter = InpainterConfig(method = "boxfill"),
                    render = RenderConfig(orientation = TextOrientation.HORIZONTAL),
                    translator = TranslatorConfig(
                        provider = "custom",
                        apiBase = server.url,
                        model = "native-failure-fixture",
                        fromLangName = "Japanese",
                        toLangName = "Korean",
                        sampleSource = "",
                        sampleTarget = "",
                        thinking = false,
                    ),
                )
                withTimeout(180_000) {
                    Yakuyomi.create(models, alphabet, "", config).use { engine ->
                        assertTrue("The actual factory must return its production Pipeline", engine is Pipeline)
                        result = engine.translatePage(original)
                    }
                }
                assertTrue("The scripted HTTP failure must retain the source", result is PageResult.Failed)
                val failed = result as PageResult.Failed
                assertEquals(ProcessingFailure(ProcessingStage.TRANSLATE, FailureKind.HTTP_SERVER), failed.failure)
                assertEquals("translate: HTTP 503", failed.reason)
                assertFalse(failed.reason.contains(REFLECTED_VALUE))
                assertFalse(failed.failure.toString().contains(REFLECTED_VALUE))
                assertFalse("The caller's source bitmap must remain alive", original.isRecycled)
                assertEquals("A failed pipeline must not alter source pixels", originalPixels, pixelSha256(original))

                val requests = server.completedRequests()
                assertEquals("One page's transient response has one physical retry", 2, requests.size)
                assertArrayEquals("The retry must retain the entire page request", requests[0].body, requests[1].body)
                for (request in requests) {
                    assertFalse("No API key belongs in this fixture", request.authorizationPresent)
                    val payload = JSONObject(request.body.toString(Charsets.UTF_8))
                    assertEquals("native-failure-fixture", payload.getString("model"))
                    val messages = payload.getJSONArray("messages")
                    assertEquals("No example or image messages belong in the request", 2, messages.length())
                    assertTrue((0 until messages.length()).all { messages.getJSONObject(it).opt("content") is String })
                    val user = messages.getJSONObject(1)
                    assertEquals("user", user.getString("role"))
                    val regions = JSONArray(user.getString("content"))
                    assertEquals("Both real OCR balloons must be in each page request", 2, regions.length())
                    assertEquals(
                        setOf(1, 2),
                        (0 until regions.length()).map {
                            regions.getJSONObject(it).getInt("id")
                        }.toSet(),
                    )
                    assertEquals(
                        EXPECTED_SOURCES,
                        (0 until regions.length()).map {
                            regions.getJSONObject(it).getString("text")
                        }.toSet(),
                    )
                }
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString(
                            "translationFailureNativeEvidence",
                            JSONObject().put("schemaVersion", 1).put("inputSha256", INPUT_SHA)
                                .put("sourcePixelsPreserved", true).put("requiredPrivateModelFiles", 7)
                                .put("actualYakuyomiFactoryAndNativePipeline", true)
                                .put("nativeApiCallCountsObserved", false)
                                .put("logicalPageAttempts", 1).put("observedPhysicalPosts", 2)
                                .put("regionsInEachRequest", 2).put("retryBodyByteIdentical", true)
                                .put("failureStage", failed.failure!!.stage.name)
                                .put("failureKind", failed.failure!!.kind.name)
                                .put("externalServer", false).put("preferencesWritten", false)
                                .put("performanceMeasurement", false).toString(),
                        )
                    },
                )
            }
            assertEquals(beforeModels, files.map { it.name to (it.length() to it.lastModified()) }.toMap())
            requireSharedIdle(shared)
        } finally {
            releaseUnexpectedResult(result, original)
            original.recycle()
        }
    }

    private fun requireSharedIdle(service: TranslationEngineService) {
        check(!service.loading.value && !service.warm.value) { "An existing shared engine must remain untouched" }
        val engine = TranslationEngineService::class.java.getDeclaredField("engine").apply {
            isAccessible = true
        }.get(service)
        val inFlight = TranslationEngineService::class.java.getDeclaredField("inFlight").apply {
            isAccessible = true
        }.get(service) as AtomicInteger
        check(engine == null && inFlight.get() == 0) { "Refusing to close or compete with an active shared engine" }
    }

    private fun releaseUnexpectedResult(result: PageResult?, original: Bitmap) {
        when (result) {
            is PageResult.Translated -> {
                if (result.page !== original && !result.page.isRecycled) result.page.recycle()
                result.analysis?.mask?.let { if (!it.isRecycled) it.recycle() }
            }
            is PageResult.Partial -> {
                if (result.page !== original && !result.page.isRecycled) result.page.recycle()
                if (!result.analysis.mask.isRecycled) result.analysis.mask.recycle()
            }
            is PageResult.Skipped -> result.analysis?.mask?.let { if (!it.isRecycled) it.recycle() }
            else -> Unit
        }
    }

    private fun pixelSha256(bitmap: Bitmap): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val pixels = IntArray(bitmap.width)
        val bytes = ByteBuffer.allocate(bitmap.width * 4)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            bytes.clear()
            pixels.forEach { bytes.putInt(it) }
            digest.update(bytes.array())
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

    private data class GatewayRequest(val body: ByteArray, val authorizationPresent: Boolean)

    private class NativeGatewayServer : AutoCloseable {
        private val listener = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 180_000 }
        private val connections = CopyOnWriteArrayList<Socket>()
        private val requests = CopyOnWriteArrayList<GatewayRequest>()
        private val failure = AtomicReference<Throwable?>()
        private val closed = AtomicBoolean()
        val url = "http://127.0.0.1:${listener.localPort}/v1/chat/completions"
        private val worker = thread(isDaemon = true, name = "translation-failure-native-http") {
            try {
                repeat(2) {
                    listener.accept().use { socket ->
                        connections += socket
                        socket.soTimeout = 20_000
                        val input = socket.getInputStream()
                        check(input.readLineCapped() == "POST /v1/chat/completions HTTP/1.1")
                        val headers = mutableMapOf<String, String>()
                        var totalHeaderBytes = 0
                        while (true) {
                            val line = input.readLineCapped()
                            if (line.isEmpty()) break
                            totalHeaderBytes += line.length
                            check(totalHeaderBytes <= 16_384 && headers.size < 64) { "Bounded headers required" }
                            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val size = checkNotNull(headers["content-length"]?.toIntOrNull())
                        check(size in 1..65_536) { "Bounded page JSON required" }
                        val body = ByteArray(size).also { DataInputStream(input).readFully(it) }
                        requests += GatewayRequest(body, "authorization" in headers)
                        val response = "{\"error\":{\"message\":\"$REFLECTED_VALUE\"}}".toByteArray()
                        socket.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 503 Service Unavailable\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: ${response.size}\r\nConnection: close\r\n\r\n"
                                    ).toByteArray(),
                            )
                            write(response)
                            flush()
                        }
                    }
                }
            } catch (error: Throwable) {
                if (!closed.get()) failure.set(error)
            }
        }

        fun completedRequests(): List<GatewayRequest> {
            worker.join(5_000)
            check(!worker.isAlive) { "The bounded server did not finish" }
            failure.get()?.let { throw AssertionError("Owned loopback server failed", it) }
            return requests.toList()
        }

        override fun close() {
            closed.set(true)
            listener.close()
            connections.forEach { it.close() }
            worker.join(5_000)
            check(!worker.isAlive) { "Cannot close the owned loopback server" }
        }

        private fun InputStream.readLineCapped(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val value = read()
                check(value >= 0) { "Unexpected end of a loopback request" }
                if (value == 10) return bytes.toString("US-ASCII").trimEnd('\r')
                check(bytes.size() < 4_096) { "Bounded request lines required" }
                bytes.write(value)
            }
        }
    }

    private companion object {
        const val INPUT_SHA = "281ffb1cc92aa692bddf6ea1429ecbb32a3ea6cfc974c09329154abd628010f3"
        const val REFLECTED_VALUE = "native-gateway-reflected-value"
        val EXPECTED_SOURCES = setOf("少し待って、ください。", "「明日もよろしくお願いします。」")
    }
}
