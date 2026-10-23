package mihon.source.hitomi

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.decoder.ImageDecoder
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Proxy
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Opt in with -e hitomiDiagnostic true. Outputs transport facts only; never saves fetched artwork.
 * The optional WebP scan examines at most 25 galleries, not the complete site's availability.
 */
@RunWith(AndroidJUnit4::class)
class HitomiConnectivityDiagnosticTest {
    @Test
    fun compareDirectAndBypassForOneWorkWithoutRetainingContent() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("liveHitomiDpiCompare") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit installation is mandatory" }
        var selectedId = arguments.getString("liveHitomiDpiGalleryId")?.let { raw ->
            requireNotNull(raw.toLongOrNull()?.takeIf { it > 0 }) { "Invalid liveHitomiDpiGalleryId" }
        }
        val network = Injekt.get<NetworkHelper>()
        val globalClient = network.client
        val source = HitomiSource(network)
        val preferences = source.getSourcePreferences()
        val wasSet = preferences.contains(HitomiSource.PREF_DPI_BYPASS)
        val previous = source.dpiBypassEnabled
        val output = File(context.getExternalFilesDir(null), "hitomi-dpi-compare-${UUID.randomUUID()}")
        check(output.mkdir())
        val modes = JSONArray()
        val report = JSONObject().put("passed", false).put("modes", modes)
            .put("callerSelectedWork", selectedId != null).put("selectedWorkKeptOnlyInMemory", true)
            .put("imageBytesRetained", false).put("metadataBodyRetained", false).put("fullUrlsRetained", false)
            .put("rawErrorsRetained", false).put("nativeOcrOrInpaintInvoked", false)
            .put("nativeStaticImageDecodingEnabled", true).put("llmCalls", false).put("signedRelease", false)
            .put("sourceImageApiInvoked", false).put("publicSourcePageListInvoked", false)
            .put("apiScope", "Selected source client HTTP body, parsed by production HitomiProtocol")
            .put("modeOrder", JSONArray(listOf("bypass", "direct")))
            .put("directFailureIsProductFailure", false)
            .put("requestScope", "One metadata body, one cover and first page per mode; no gallery traversal")
            .put(
                "routingScope",
                "The same source shares gg.js caches across modes; routing/retry requests may be additional",
            )
            .put("counterScope", "Process-wide new fragmented TLS connections, not HTTP request count")
            .put("cacheControl", "Evict idle mode connections; API/cover no-cache,no-store; reader cacheless API")
            .put("cleanupScope", "Only this UUID directory contains result.json; host exports and removes it")
        val started = System.nanoTime()
        val fragmentedBefore = HitomiBypassProxy.fragmentedConnections
        val clients = mutableListOf<OkHttpClient>()
        var failure: Throwable? = null
        fun writeReport() = File(output, "result.json").writeText(report.toString(2))
        suspend fun <T> observe(
            parent: JSONObject,
            name: String,
            client: OkHttpClient,
            block: suspend (JSONObject, Long) -> T,
        ): T? {
            val row = JSONObject().put("stage", name).put("responseReceived", false)
            parent.getJSONArray("stages").put(row)
            val stageStart = System.nanoTime()
            val fragments = HitomiBypassProxy.fragmentedConnections
            return try {
                check(source.client === client) { "Source selected another client" }
                client.connectionPool.evictAll()
                withTimeout(DPI_STAGE_TIMEOUT_MS) {
                    block(row, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DPI_STAGE_TIMEOUT_MS))
                }.also { row.put("status", "passed") }
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                row.put("status", "timedOut").put("errorType", error.javaClass.simpleName)
                null
            } catch (error: CancellationException) {
                row.put("status", "cancelled").put("errorType", error.javaClass.simpleName)
                throw error
            } catch (error: Exception) {
                row.put("status", "failed").put("errorType", error.javaClass.simpleName)
                if (error is HttpException) row.put("httpStatus", error.code)
                null
            } catch (error: AssertionError) {
                row.put("status", "failed").put("errorType", error.javaClass.simpleName)
                null
            } finally {
                row.put("durationMs", (System.nanoTime() - stageStart) / 1_000_000)
                    .put("tlsFragmentedConnections", HitomiBypassProxy.fragmentedConnections - fragments)
                writeReport()
            }
        }
        try {
            check(preferences.edit().putBoolean(HitomiSource.PREF_DPI_BYPASS, true).commit())
            val bypass = source.client.also { clients += it }
            check(bypass.proxy == HitomiBypassProxy.proxy && bypass.proxy?.type() == Proxy.Type.SOCKS)
            check(preferences.edit().putBoolean(HitomiSource.PREF_DPI_BYPASS, false).commit())
            val direct = source.client.also { clients += it }
            check(direct !== bypass && direct.proxy == globalClient.proxy)
            check(direct.dns === bypass.dns)
            check(direct.sslSocketFactory === globalClient.sslSocketFactory)
            check(bypass.sslSocketFactory === globalClient.sslSocketFactory)
            check(direct.hostnameVerifier === globalClient.hostnameVerifier)
            check(bypass.hostnameVerifier === globalClient.hostnameVerifier)
            check(
                direct.interceptors.filterIsInstance<HitomiImageInterceptor>().single() ===
                    bypass.interceptors.filterIsInstance<HitomiImageInterceptor>().single(),
            )
            report.put("distinctModeClients", true).put("sharedImageRoutingInterceptor", true)
                .put("sharedScopedDoh", true).put("tlsConfigurationUnmodified", true)
            var fixed: DpiFirstPage? = null
            for ((enabled, client) in listOf(true to bypass, false to direct)) {
                check(preferences.edit().putBoolean(HitomiSource.PREF_DPI_BYPASS, enabled).commit())
                check(source.dpiBypassEnabled == enabled && source.client === client)
                val mode = JSONObject().put("mode", if (enabled) "bypass" else "direct")
                    .put("enabled", enabled).put("stages", JSONArray()).put("selectedClientIdentityVerified", true)
                modes.put(mode)
                val modeFragments = HitomiBypassProxy.fragmentedConnections
                if (selectedId == null) {
                    val ids = observe(mode, "selectOneFromLatestIndex", client) { row, _ ->
                        HitomiClient(client) { source.headers }.page("index-all.nozomi", 1).first.also {
                            row.put("resultCount", it.size)
                            check(it.isNotEmpty()) { "Latest index is empty" }
                        }
                    }
                    selectedId = ids?.firstOrNull()
                    check(selectedId != null) { "Cannot select one work for the paired comparison" }
                }
                val id = requireNotNull(selectedId)
                val found = observe(mode, "api", client) { row, deadline ->
                    val request = Request.Builder().url("${HitomiClient.INDEX}/galleries/$id.js")
                        .headers(source.headers).header("Cache-Control", "no-cache, no-store").build()
                    withContext(Dispatchers.IO) {
                        client.newCall(request).await().use { response ->
                            dpiFirstPage(response, row, deadline)
                        }
                    }
                }
                if (enabled) fixed = requireNotNull(found) { "Bypass metadata stage did not succeed" }
                val selected = requireNotNull(fixed)
                val descriptorMatches = found?.let { it == selected }
                val descriptorMismatch = descriptorMatches == false
                mode.put("metadataFirstPageMatchesSelection", descriptorMatches ?: JSONObject.NULL)
                    .put(
                        "metadataComparisonStatus",
                        when (descriptorMatches) {
                            true -> "matched"
                            false -> "mismatch"
                            null -> "unavailable"
                        },
                    )
                if (descriptorMismatch) {
                    // HTTP/JSON success is insufficient for a paired result if the selected page
                    // changed. The images below still use the original RAM descriptor.
                    val apiStages = mode.getJSONArray("stages")
                    apiStages.getJSONObject(apiStages.length() - 1)
                        .put("status", "inconclusive").put("reason", "firstPageDescriptorMismatch")
                        .put("httpAndMetadataParseSucceeded", true)
                }
                observe(mode, "cover", client) { row, deadline ->
                    val hash = selected.hash
                    val url = "https://atn.gold-usergeneratedcontent.net/avifbigtn/" +
                        "${hash.takeLast(1)}/${hash.takeLast(3).take(2)}/$hash.avif"
                    val request = Request.Builder().url(url).headers(source.headers)
                        .header("Cache-Control", "no-cache, no-store").build()
                    row.put("requestedHost", request.url.host)
                    withContext(Dispatchers.IO) {
                        client.newCall(request).await().use { response ->
                            row.put("responseReceived", true)
                                .put("cacheResponsePresent", response.cacheResponse != null)
                                .put("networkResponsePresent", response.networkResponse != null)
                            decode(response, row, deadline)
                        }
                    }
                }
                observe(mode, "firstPage", client) { row, deadline ->
                    val page = Page(0, "${selected.hash}.${selected.extension}")
                    page.imageUrl = source.getImageUrl(page)
                    val requested = requireNotNull(page.imageUrl).toHttpUrl()
                    check(requested.isHttps && requested.port == 443 && HitomiDohDns.allows(requested.host))
                    row.put("requestedHost", requested.host)
                    withContext(Dispatchers.IO) {
                        report.put("sourceImageApiInvoked", true)
                        source.getImage(page).use { response ->
                            row.put("responseReceived", true)
                                .put("cacheResponsePresent", response.cacheResponse != null)
                                .put("networkResponsePresent", response.networkResponse != null)
                            decode(response, row, deadline)
                        }
                    }
                }
                val stages = mode.getJSONArray("stages")
                val allPassed = descriptorMatches == true && (0 until stages.length()).all {
                    stages.getJSONObject(it).getString("status") == "passed"
                }
                mode.put("allStagesPassed", allPassed)
                    .put(
                        "outcome",
                        if (descriptorMismatch) {
                            "inconclusive"
                        } else if (allPassed) {
                            "passed"
                        } else {
                            "failed"
                        },
                    )
                    .put("tlsFragmentedConnections", HitomiBypassProxy.fragmentedConnections - modeFragments)
                if (enabled) {
                    check(allPassed) { "Bypass comparison stages did not all succeed" }
                    check(HitomiBypassProxy.fragmentedConnections > modeFragments) {
                        "Bypass TLS split was not observed"
                    }
                }
            }
            check(network.client === globalClient) { "Global source client changed" }
            report.put("completed", true).put("passed", true)
        } catch (error: Throwable) {
            failure = error
            report.put("errorType", error.javaClass.simpleName)
        } finally {
            try {
                check(
                    preferences.edit().apply {
                        if (wasSet) {
                            putBoolean(HitomiSource.PREF_DPI_BYPASS, previous)
                        } else {
                            remove(HitomiSource.PREF_DPI_BYPASS)
                        }
                    }.commit(),
                )
                check(
                    source.dpiBypassEnabled == previous && preferences.contains(HitomiSource.PREF_DPI_BYPASS) == wasSet,
                )
                report.put("bypassPreferenceRestored", true)
            } catch (cleanupError: Throwable) {
                report.put("bypassPreferenceRestored", false).put("cleanupErrorType", cleanupError.javaClass.simpleName)
                if (failure == null) failure = cleanupError
            }
            try {
                clients.forEach { it.connectionPool.evictAll() }
                report.put("idleModePoolsEvicted", true)
            } catch (cleanupError: Throwable) {
                report.put("idleModePoolsEvicted", false).put("poolCleanupErrorType", cleanupError.javaClass.simpleName)
                if (failure == null) failure = cleanupError
            }
            report.put("passed", failure == null).put("durationMs", (System.nanoTime() - started) / 1_000_000)
                .put("tlsFragmentedConnections", HitomiBypassProxy.fragmentedConnections - fragmentedBefore)
                .put("globalClientUnchanged", network.client === globalClient)
            writeReport()
            println("HITOMI_DPI_COMPARE_REPORT=${File(output, "result.json").absolutePath}")
        }
        failure?.let { error ->
            if (error is CancellationException) throw error
            throw AssertionError("Hitomi DPI comparison failed; inspect the sanitized UUID report")
        }
    }

    private data class DpiFirstPage(val hash: String, val extension: String)

    private suspend fun dpiFirstPage(response: Response, row: JSONObject, deadline: Long): DpiFirstPage {
        check(response.request.url.isHttps && response.request.url.port == 443)
        check(HitomiDohDns.allows(response.request.url.host))
        row.put("responseReceived", true).put("host", response.request.url.host).put("httpStatus", response.code)
            .put("cacheResponsePresent", response.cacheResponse != null)
            .put("networkResponsePresent", response.networkResponse != null)
        require(response.body.contentLength() <= DPI_MAX_API_BYTES) { "Metadata body exceeds the comparison limit" }
        val bytes = response.body.source().use { input ->
            input.timeout().deadlineNanoTime(minOf(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(30)))
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= DPI_MAX_API_BYTES) {
                    "Metadata body exceeds the comparison limit"
                }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        try {
            row.put("bodyBytes", bytes.size).put(
                "bodySha256",
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            )
            check(response.isSuccessful) { "Metadata response is not successful" }
            val gallery = Json.parseToJsonElement(HitomiProtocol.galleryJson(bytes.decodeToString())).jsonObject
            val file = gallery.getValue("files").jsonArray.first().jsonObject
            val hash = file.getValue("hash").jsonPrimitive.content
            check(hash.matches(Regex("[a-fA-F0-9]{3,128}"))) { "Invalid first image descriptor" }
            val extension = if (file["haswebp"]?.jsonPrimitive?.content == "1" &&
                file["hasavif"]?.jsonPrimitive?.content != "1"
            ) {
                "webp"
            } else {
                "avif"
            }
            row.put("firstPageDescriptorValid", true)
            return DpiFirstPage(hash, extension)
        } finally {
            bytes.fill(0)
        }
    }

    @Test
    fun suggestionsCoversAndReaderImagesUseProductionTransport() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("hitomiDiagnostic") == "true")
        fun intArgument(name: String, default: Int, range: IntRange): Int {
            val raw = arguments.getString(name) ?: return default
            return requireNotNull(raw.toIntOrNull()?.takeIf { it in range }) { "Invalid $name" }
        }
        val requireWebp = arguments.getString("hitomiRequireWebp")?.let {
            requireNotNull(it.toBooleanStrictOrNull()) { "Invalid hitomiRequireWebp" }
        } ?: false
        val scanLimit = intArgument("hitomiWebpScanGalleries", 1, 1..25)
        val scanTimeoutMs = intArgument("hitomiWebpScanTimeoutSeconds", 60, 5..180) * 1_000L
        val diagnosticTimeoutMs = intArgument("hitomiDiagnosticTimeoutSeconds", 240, 60..600) * 1_000L
        // A caller-selected gallery is kept only in memory and is never copied to the report.
        val selectedGallery = arguments.getString("hitomiWebpGalleryId")?.let { raw ->
            requireNotNull(raw.toLongOrNull()?.takeIf { it > 0 }) { "Invalid hitomiWebpGalleryId" }
        }
        val searchQuery = arguments.getString("hitomiWebpSearchQuery")?.let { raw ->
            require(raw.length in 1..256 && raw.isNotBlank() && raw.none(Char::isISOControl)) {
                "Invalid hitomiWebpSearchQuery: expected 1..256 non-control characters"
            }
            raw.trim()
        }
        val started = System.nanoTime()
        val deadline = started + TimeUnit.MILLISECONDS.toNanos(diagnosticTimeoutMs)
        val stages = JSONArray()
        val failed = mutableListOf<String>()
        val result = JSONObject().put("stages", stages).put("imageBytesRetained", false)
            .put("webpRequired", requireWebp).put("webpGalleryScanLimit", scanLimit)
            .put("webpScanTimeoutMs", scanTimeoutMs).put("diagnosticTimeoutMs", diagnosticTimeoutMs)
            .put("callerSelectedGallery", selectedGallery != null)
            .put("searchSampleEnabled", searchQuery != null).put("searchCandidates", 0)
            .put("animationPlaybackVerified", false)
        val source = HitomiSource(Injekt.get<NetworkHelper>())
        val preferences = source.getSourcePreferences()
        val wasSet = preferences.contains(HitomiSource.PREF_DPI_BYPASS)
        val previous = source.dpiBypassEnabled
        val fragmentedBefore = HitomiBypassProxy.fragmentedConnections
        preferences.edit().putBoolean(HitomiSource.PREF_DPI_BYPASS, true).commit()

        suspend fun <T> stage(name: String, host: String, block: suspend (JSONObject) -> T): T? {
            val row = JSONObject().put("stage", name).put("host", host)
            stages.put(row)
            val before = System.nanoTime()
            val fragments = HitomiBypassProxy.fragmentedConnections
            return try {
                block(row).also { row.put("status", "passed") }
            } catch (error: CancellationException) {
                row.put("status", "cancelled").put("errorType", error.javaClass.simpleName)
                throw error
            } catch (error: Throwable) {
                failed += name
                row.put("status", "failed").put("errorType", error.javaClass.simpleName)
                Regex("HTTP (\\d{3})").find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { row.put("httpStatus", it) }
                null
            } finally {
                row.put("durationMs", (System.nanoTime() - before) / 1_000_000)
                row.put("tlsFragmentedConnections", HitomiBypassProxy.fragmentedConnections - fragments)
            }
        }

        suspend fun suggestionStage(name: String, query: String, requireJapanese: Boolean) {
            stage(name, "tagindex.hitomi.la") { row ->
                val suggestions = try {
                    withTimeout(30_000) { source.getSearchSuggestions(query) }
                } catch (_: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    throw java.io.IOException("Suggestion stage timed out")
                }
                row.put("resultCount", suggestions.size)
                assertTrue("Suggestion list must be nonempty and bounded", suggestions.size in 1..20)
                if (requireJapanese) {
                    val found = suggestions.any { it.value == "language:japanese" }
                    row.put("japaneseLanguagePresent", found)
                    assertTrue("Japanese language suggestion missing", found)
                } else {
                    assertTrue(
                        "Suggestion index returned another namespace",
                        suggestions.all {
                            it.value.startsWith("${query.substringBefore(':')}:")
                        },
                    )
                }
            }
        }

        suspend fun webpStages(api: HitomiClient, popularIds: List<Long>, firstPages: List<Page>?) {
            val scan = stage("webpAvailability", "ltn.gold-usergeneratedcontent.net") { row ->
                var scannedGalleries = 0
                var scannedFiles = 0
                var webpFiles = 0
                var naturalFiles = 0
                var deadlineReached = false
                var stoppedAfterCandidates = false
                var scanFinished = false
                var searchCompleted = searchQuery == null
                var searchCandidates = 0
                var natural: WebpSample? = null
                val explicit = mutableListOf<WebpSample>()
                val webpFlagShapes = FlagShapeCounts()
                val avifFlagShapes = FlagShapeCounts()
                try {
                    try {
                        withTimeout(scanTimeoutMs) {
                            val ids = linkedSetOf<Long>()
                            selectedGallery?.let(ids::add)
                            if (searchQuery != null) {
                                val searched =
                                    stage("webpSearchSample", "ltn.gold-usergeneratedcontent.net") { searchRow ->
                                        api.search(searchQuery, "all", 1).first.also { candidates ->
                                            searchCandidates = candidates.size
                                            result.put("searchCandidates", searchCandidates)
                                            searchRow.put(
                                                "searchSampleEnabled",
                                                true,
                                            ).put("searchCandidates", searchCandidates)
                                        }
                                    } ?: throw java.io.IOException("WebP search sample failed")
                                searchCompleted = true
                                ids.addAll(searched)
                            }
                            popularIds.firstOrNull()?.let(ids::add)
                            if (scanLimit > ids.size) {
                                // Fetch index IDs only, rather than eagerly fetching another full listing's details.
                                val latestIds = api.page("index-all.nozomi", 1).first
                                repeat(maxOf(popularIds.size, latestIds.size)) { index ->
                                    popularIds.getOrNull(index)?.let(ids::add)
                                    latestIds.getOrNull(index)?.let(ids::add)
                                }
                            }
                            for (id in ids.take(scanLimit)) {
                                currentCoroutineContext().ensureActive()
                                if (scannedGalleries > 0) delay(250)
                                val files = api.gallery(id).getValue("files").jsonArray
                                scannedGalleries++
                                // Examine every file in this gallery; its first file cannot prove WebP absence.
                                for ((index, item) in files.withIndex()) {
                                    currentCoroutineContext().ensureActive()
                                    val file = item.jsonObject
                                    scannedFiles++
                                    // Measure key/type drift independently of the unchanged production condition below.
                                    webpFlagShapes.observe(file, "haswebp")
                                    avifFlagShapes.observe(file, "hasavif")
                                    if (file["haswebp"]?.jsonPrimitive?.content != "1") continue
                                    webpFiles++
                                    val hasAvif = file["hasavif"]?.jsonPrimitive?.content == "1"
                                    val sample =
                                        WebpSample(id, index, file.getValue("hash").jsonPrimitive.content, hasAvif)
                                    if (explicit.size < 2 &&
                                        explicit.none { it.hash == sample.hash }
                                    ) {
                                        explicit += sample
                                    }
                                    if (!hasAvif) {
                                        naturalFiles++
                                        if (natural == null) natural = sample
                                    }
                                }
                                if (natural != null && explicit.any { it.hash != natural?.hash }) {
                                    stoppedAfterCandidates = true
                                    break
                                }
                            }
                        }
                    } catch (_: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive()
                        if (!searchCompleted) throw java.io.IOException("WebP search sample timed out")
                        deadlineReached = true
                    }
                    scanFinished = true
                    WebpScan(natural, explicit.firstOrNull { it.hash != natural?.hash } ?: explicit.firstOrNull())
                } finally {
                    row.put("galleriesScanned", scannedGalleries).put("filesScanned", scannedFiles)
                        .put(
                            "flagShapes",
                            JSONObject().put(
                                "haswebp",
                                webpFlagShapes.report(),
                            ).put("hasavif", avifFlagShapes.report()),
                        )
                        .put("searchSampleEnabled", searchQuery != null).put("searchCandidates", searchCandidates)
                        .put("webpMetadataFiles", webpFiles).put("naturalWebpMetadataFiles", naturalFiles)
                        .put("foundWithinSample", webpFiles > 0).put("naturalFoundWithinSample", naturalFiles > 0)
                        .put(
                            "scanDeadlineReached",
                            deadlineReached,
                        ).put("stoppedAfterCandidates", stoppedAfterCandidates)
                        .put("availabilityScope", "boundedGalleryMetadataSample")
                        .put(
                            "outcome",
                            when {
                                !scanFinished -> "metadataScanFailed"
                                webpFiles > 0 -> "foundWithinSample"
                                else -> "notFoundWithinSample"
                            },
                        )
                }
            }
            fun unavailable(name: String, outcome: String) {
                stages.put(
                    JSONObject().put("stage", name).put("status", outcome)
                        .put("availabilityScope", "boundedGalleryMetadataSample"),
                )
            }
            var requestedImages = 0
            var verifiedImages = 0
            val natural = scan?.natural
            if (natural != null) {
                val page = stage("naturalWebpSelection", "ltn.gold-usergeneratedcontent.net") { row ->
                    val pages = if (natural.galleryId == popularIds.firstOrNull() && firstPages != null) {
                        firstPages
                    } else {
                        source.getPageList(SChapter.create().apply { url = "/reader/${natural.galleryId}.html" })
                    }
                    // Preserve the actual Page returned by production. No metadata flags are rewritten.
                    requireNotNull(
                        pages.firstOrNull {
                            it.index == natural.index && it.url == "${natural.hash}.webp"
                        },
                    ) {
                        "Production page list did not naturally select the metadata WebP sample"
                    }.also { row.put("selectedByProduction", true) }
                }
                if (page != null) {
                    stage("readerNaturalWebp", "gold-usergeneratedcontent.net") { row ->
                        row.put("selectedByProduction", true)
                        page.imageUrl = source.getImageUrl(page)
                        row.put("requestedHost", page.imageUrl!!.toHttpUrl().host)
                        withContext(Dispatchers.IO) {
                            requestedImages++
                            source.getImage(page).use { decode(it, row, deadline, requireWebp = true) }
                        }
                        verifiedImages++
                    }
                } else {
                    unavailable("readerNaturalWebp", "pageSelectionFailed")
                }
            } else {
                unavailable("readerNaturalWebp", if (scan == null) "metadataScanFailed" else "notFoundWithinSample")
            }
            val explicit = scan?.explicit
            if (explicit != null && explicit.hash != natural?.hash) {
                stage("readerExplicitWebp", "gold-usergeneratedcontent.net") { row ->
                    // haswebp=1 authorizes this format, but both flags=1 normally selects AVIF in production.
                    row.put("selectedByProduction", false).put("metadataAdvertisesWebp", true)
                        .put("metadataAdvertisesAvif", explicit.hasAvif)
                    val page = Page(explicit.index, "${explicit.hash}.webp")
                    page.imageUrl = source.getImageUrl(page)
                    row.put("requestedHost", page.imageUrl!!.toHttpUrl().host)
                    withContext(Dispatchers.IO) {
                        requestedImages++
                        source.getImage(page).use { decode(it, row, deadline, requireWebp = true) }
                    }
                    verifiedImages++
                }
            } else {
                unavailable(
                    "readerExplicitWebp",
                    when {
                        scan == null -> "metadataScanFailed"
                        explicit == null -> "notFoundWithinSample"
                        else -> "sameSampleAlreadyRequestedNaturally"
                    },
                )
            }
            stage("webpRequirement", "gold-usergeneratedcontent.net") { row ->
                val outcome = when {
                    verifiedImages > 0 -> "verifiedWithinSample"
                    scan == null -> "metadataScanFailed"
                    scan.natural != null || scan.explicit != null -> "sampleVerificationFailed"
                    else -> "notFoundWithinSample"
                }
                row.put("required", requireWebp).put("requestedImages", requestedImages)
                    .put("verifiedImages", verifiedImages).put("outcome", outcome)
                assertTrue("Required WebP diagnostic: $outcome", !requireWebp || verifiedImages > 0)
            }
        }

        try {
            withTimeout(diagnosticTimeoutMs) {
                source.client.connectionPool.evictAll()
                suggestionStage("suggestionsGlobal", "jap", true)
                suggestionStage("suggestionsLanguage", "language:ja", true)
                suggestionStage("suggestionsArtist", "artist:", false)
                for (namespace in listOf("group", "series", "character", "tag", "female", "male", "type")) {
                    suggestionStage("suggestions-$namespace", "$namespace:", false)
                }

                val listing = stage("popularListing", "ltn.gold-usergeneratedcontent.net") { row ->
                    source.getPopularManga(1).also {
                        row.put("resultCount", it.mangas.size)
                        assertTrue("Popular list is empty", it.mangas.isNotEmpty())
                    }
                }
                val api = HitomiClient(source.client) { source.headers }
                val routing = stage("routing", "ltn.gold-usergeneratedcontent.net") { api.routing() }
                var firstPages: List<Page>? = null
                if (listing != null) {
                    val covers = listing.mangas.mapNotNull { it.thumbnail_url }.distinct()
                    val grouped = covers.groupBy { url ->
                        val hash = url.toHttpUrl().pathSegments.last().substringBeforeLast('.')
                        routing?.thumbnailUrl(hash)?.toHttpUrl()?.host ?: url.toHttpUrl().host
                    }
                    val selected = (grouped.values.map { it.first() } + covers).distinct().take(2)
                    selected.firstOrNull { url ->
                        val hash = url.toHttpUrl().pathSegments.last().substringBeforeLast('.')
                        routing?.thumbnailUrl(hash)?.toHttpUrl()?.host != url.toHttpUrl().host
                    }?.let { legacyUrl ->
                        stage("legacyCoverRoute", legacyUrl.toHttpUrl().host) { row ->
                            // Isolate the old fixed-host behavior while retaining exactly the same DPI transport.
                            val legacyClient = source.client.newBuilder().apply {
                                interceptors().removeAll { it is HitomiImageInterceptor }
                            }.build()
                            val request = Request.Builder().url(legacyUrl).headers(source.headers).build()
                            withContext(Dispatchers.IO) {
                                legacyClient.newCall(request).await().use { response ->
                                    row.put("httpStatus", response.code)
                                }
                            }
                        }
                    }
                    stage("coverSelection", "gold-usergeneratedcontent.net") { row ->
                        row.put("resultCount", selected.size)
                        assertTrue("Two cover samples required", selected.size == 2)
                    }
                    selected.forEachIndexed { index, url ->
                        stage("cover${index + 1}", url.toHttpUrl().host) { row ->
                            val request = Request.Builder().url(url).headers(source.headers).build()
                            withContext(Dispatchers.IO) {
                                source.client.newCall(request).await().use { decode(it, row, deadline) }
                            }
                        }
                    }
                    val details = stage("details", "ltn.gold-usergeneratedcontent.net") {
                        source.getMangaUpdate(listing.mangas.first(), emptyList(), true, true).also {
                            assertTrue("Expected one gallery chapter", it.chapters.size == 1)
                        }
                    }
                    val pages = details?.let { update ->
                        stage("pageList", "ltn.gold-usergeneratedcontent.net") { row ->
                            source.getPageList(update.chapters.single()).also {
                                row.put("resultCount", it.size)
                                assertTrue("Page list is empty", it.isNotEmpty())
                            }
                        }
                    }
                    firstPages = pages
                    if (pages != null) {
                        val avif = pages.filter { it.url.endsWith(".avif") }
                        stage("avifSelection", "gold-usergeneratedcontent.net") { row ->
                            row.put("resultCount", avif.size)
                            assertTrue("Three AVIF samples required", avif.size >= 3)
                        }
                        if (avif.isNotEmpty()) {
                            listOf(avif.first(), avif[avif.size / 2], avif.last()).distinctBy { it.index }
                                .forEachIndexed { index, page ->
                                    stage("readerAvif${index + 1}", "gold-usergeneratedcontent.net") { row ->
                                        page.imageUrl = source.getImageUrl(page)
                                        row.put("requestedHost", page.imageUrl!!.toHttpUrl().host)
                                        withContext(Dispatchers.IO) {
                                            source.getImage(page).use { decode(it, row, deadline) }
                                        }
                                    }
                                }
                        }
                    }
                }
                webpStages(
                    api,
                    listing?.mangas?.mapNotNull { HitomiSource.galleryId(it.url) }.orEmpty(),
                    firstPages,
                )
                val fragmented = HitomiBypassProxy.fragmentedConnections - fragmentedBefore
                assertTrue("Production transport did not fragment TLS", fragmented > 0)
                result.put("completed", true)
                assertTrue("Failed diagnostic stages: ${failed.joinToString()}", failed.isEmpty())
                result.put("passed", true)
            }
        } catch (error: Throwable) {
            result.put("passed", false).put("errorType", error.javaClass.simpleName)
            throw error
        } finally {
            preferences.edit().apply {
                if (wasSet) putBoolean(HitomiSource.PREF_DPI_BYPASS, previous) else remove(HitomiSource.PREF_DPI_BYPASS)
            }.commit()
            result.put("durationMs", (System.nanoTime() - started) / 1_000_000)
                .put("tlsFragmentedConnections", HitomiBypassProxy.fragmentedConnections - fragmentedBefore)
                .put(
                    "bypassPreferenceRestored",
                    source.dpiBypassEnabled == previous &&
                        preferences.contains(HitomiSource.PREF_DPI_BYPASS) == wasSet,
                )
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.getExternalFilesDir(null), "hitomi-connectivity-diagnostic.json").writeText(result.toString(2))
        }
    }

    private data class WebpSample(val galleryId: Long, val index: Int, val hash: String, val hasAvif: Boolean)

    private data class WebpScan(val natural: WebpSample?, val explicit: WebpSample?)

    /** Only fixed shape labels and counts leave this object, never flag values or other metadata keys. */
    private class FlagShapeCounts {
        private var keyPresent = 0
        private val counts = linkedMapOf(
            "absent" to 0, "jsonNull" to 0,
            "numeric0" to 0, "numeric1" to 0, "numericOther" to 0,
            "booleanFalse" to 0, "booleanTrue" to 0,
            "string0" to 0, "string1" to 0, "stringFalse" to 0, "stringTrue" to 0, "stringOther" to 0,
            "nonPrimitive" to 0,
        )

        fun observe(file: JsonObject, key: String) {
            if (key in file.keys) keyPresent++
            val value = file[key]
            val shape = when {
                value == null -> "absent"
                value === JsonNull -> "jsonNull"
                value !is JsonPrimitive -> "nonPrimitive"
                value.isString -> when (value.content) {
                    "0" -> "string0"
                    "1" -> "string1"
                    "false" -> "stringFalse"
                    "true" -> "stringTrue"
                    else -> "stringOther"
                }
                else -> when (value.content) {
                    "0" -> "numeric0"
                    "1" -> "numeric1"
                    "false" -> "booleanFalse"
                    "true" -> "booleanTrue"
                    else -> "numericOther"
                }
            }
            counts[shape] = counts.getValue(shape) + 1
        }

        fun report(): JSONObject = JSONObject().put("keyPresent", keyPresent)
            .put("keyAbsent", counts.getValue("absent")).put("shapes", JSONObject(counts))
    }

    private suspend fun decode(response: Response, row: JSONObject, deadline: Long, requireWebp: Boolean = false) {
        row.put("host", response.request.url.host).put("httpStatus", response.code)
        assertTrue("Image HTTP ${response.code}", response.isSuccessful)
        require(response.body.contentLength() <= MAX_IMAGE_BYTES) { "Diagnostic image body too large" }
        val bytes = response.body.source().use { input ->
            // Also bound a stalled body read after getImage()/await() has returned the response.
            input.timeout().deadlineNanoTime(minOf(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(30)))
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size().toLong() + count <= MAX_IMAGE_BYTES) { "Diagnostic image body too large" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        try {
            row.put("bodyBytes", bytes.size).put(
                "bodySha256",
                MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                    (it.toInt() and 0xff).toString(16).padStart(2, '0')
                },
            )
            val format = when {
                bytes.matchesAscii(0, "RIFF") && bytes.matchesAscii(8, "WEBP") -> "webp"
                bytes.matchesAscii(4, "ftyp") && (8..minOf(bytes.size - 4, 64)).any {
                    bytes.matchesAscii(it, "avif") || bytes.matchesAscii(it, "avis")
                } -> "avif"
                else -> "other"
            }
            row.put("format", format)
            assertTrue("WebP request did not return a RIFF/WEBP bitstream", !requireWebp || format == "webp")
            if (format == "webp") inspectWebp(bytes, row)
            bytes.inputStream().use { input ->
                val decoder =
                    requireNotNull(ImageDecoder.newInstance(input, false, null)) {
                        "Image decoder initialization failed"
                    }
                try {
                    row.put("width", decoder.width).put("height", decoder.height)
                    assertTrue("Image dimensions invalid", decoder.width in 1..100_000 && decoder.height in 1..100_000)
                    var sampleSize = 1
                    while (decoder.width.toLong() / sampleSize * (decoder.height / sampleSize) >
                        MAX_STATIC_DECODE_PIXELS
                    ) {
                        sampleSize *= 2
                    }
                    val bitmap = requireNotNull(decoder.decode(sampleSize = sampleSize)) { "Image decode failed" }
                    try {
                        assertTrue("Decoded bitmap is empty", bitmap.width > 0 && bitmap.height > 0)
                        row.put("decodedWidth", bitmap.width).put("decodedHeight", bitmap.height)
                            .put("decodeSampleSize", sampleSize).put("decodeScope", "singleStaticBitmap")
                            .put("animationPlaybackVerified", false)
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    decoder.recycle()
                }
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun inspectWebp(bytes: ByteArray, row: JSONObject) {
        require(bytes.size >= 12) { "Truncated WebP header" }
        val declaredSize = bytes.uint32Le(4) + 8
        require(declaredSize in 12..bytes.size.toLong()) { "Invalid WebP RIFF size" }
        var offset = 12L
        var animationFlag = false
        var animationChunk = false
        var animationFrames = 0
        while (offset + 8 <= declaredSize) {
            val start = offset.toInt()
            val size = bytes.uint32Le(start + 4)
            val end = offset + 8 + size
            require(end <= declaredSize) { "Truncated WebP chunk" }
            when {
                bytes.matchesAscii(start, "VP8X") -> {
                    require(size >= 10) { "Truncated WebP VP8X chunk" }
                    animationFlag = bytes[start + 8].toInt() and 0x02 != 0
                }
                bytes.matchesAscii(start, "ANIM") -> animationChunk = true
                bytes.matchesAscii(start, "ANMF") -> animationFrames++
            }
            offset = end + (size and 1)
        }
        require(offset == declaredSize) { "Invalid WebP chunk padding" }
        val animated = animationFlag || animationChunk || animationFrames > 0
        row.put("webpRiffMagic", true).put("webpVp8xAnimationFlag", animationFlag)
            .put("webpAnimChunk", animationChunk).put("webpAnimationFrames", animationFrames)
            .put("animatedWebp", animated).put("animationPlaybackVerified", false)
        assertTrue(
            "WebP animation markers inconsistent",
            !animated || (animationFlag && animationChunk && animationFrames > 0),
        )
    }

    private fun ByteArray.matchesAscii(offset: Int, value: String): Boolean =
        offset >= 0 && offset.toLong() + value.length <= size &&
            value.indices.all { this[offset + it].toInt() and 0xff == value[it].code }

    private fun ByteArray.uint32Le(offset: Int): Long {
        require(offset >= 0 && offset.toLong() + 4 <= size) { "Truncated image integer" }
        return (0..3).fold(0L) { value, index -> value or ((this[offset + index].toLong() and 0xff) shl (index * 8)) }
    }

    private companion object {
        const val DPI_STAGE_TIMEOUT_MS = 45_000L
        const val DPI_MAX_API_BYTES = 2 * 1_024 * 1_024L
        const val MAX_IMAGE_BYTES = 32 * 1_024 * 1_024
        const val MAX_STATIC_DECODE_PIXELS = 4_000_000L
    }
}
