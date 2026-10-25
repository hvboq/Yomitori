package mihon.translation

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Base64
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.webview.WebViewCaptureBackend
import eu.kanade.presentation.webview.WebViewCaptureObservation
import eu.kanade.presentation.webview.webViewCaptureObserver
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.ui.capture.CAPTURE_META_FILE
import eu.kanade.tachiyomi.ui.capture.CaptureReviewViewModel
import eu.kanade.tachiyomi.ui.capture.CaptureScreen
import eu.kanade.tachiyomi.ui.capture.CaptureViewModel
import eu.kanade.tachiyomi.ui.capture.MANGA_META_FILE
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.more.MoreTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.testprovider.SafAliasDocumentsProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.storage.FolderProvider
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.storage.service.StoragePreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Actual More menu navigation and CaptureScreen controls, using only UUID-owned cache storage.
 * The second case captures an on-screen neutral data page through the real PixelCopy path.
 * WebView.scrollTo changes the owned page; this is not a physical swipe or delayed-callback race test.
 * Review Save is deliberately never invoked: that action can insert manga into the application DB.
 */
@RunWith(AndroidJUnit4::class)
class CaptureScreenUiTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val compose = createEmptyComposeRule()
    private var activeReport: Pair<File, JSONObject>? = null

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(OwnPrewarmGate()).around(compose)

    @Test
    fun moreMenuOpensCaptureControlsWithoutLoadingContent() = withCase(
        "moreMenuOpensCaptureControlsWithoutLoadingContent",
    ) { fixture, evidence ->
        withHost(fixture, MoreEntry) { activity ->
            stage(evidence, "more-menu")
            compose.onNode(hasScrollToIndexAction())
                .performScrollToNode(hasText(context.stringResource(MR.strings.capture_manga)))
            compose.onNodeWithText(context.stringResource(MR.strings.capture_manga))
                .assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription(context.stringResource(MR.strings.capture_browse))
                .assertIsDisplayed()
            compose.onNodeWithContentDescription(context.stringResource(MR.strings.capture_new_manga))
                .assertIsDisplayed()
            waitUntil("Capture WebView was not attached") { onMain { findWebView(activity.window.decorView) != null } }
            onMain {
                val view = checkNotNull(findWebView(activity.window.decorView))
                assertTrue(
                    "The empty public entry must not load external content",
                    view.url.isNullOrBlank() || view.url == "about:blank",
                )
                assertFalse(fixture.model.continuous.value.running)
            }
            assertTrue("Public navigation must not save any capture", fixture.captureFiles().isEmpty())
            assertTrue("Public navigation must not request screenshots", fixture.observations.isEmpty())
            evidence.put("publicMoreMenu", true).put("captureControls", true).put("externalLoads", 0)
            stage(evidence, "public-entry-verified")
        }
    }

    @Test
    fun continuousCapturePersistsRealPixelsAndStopsThroughUi() = withCase(
        "continuousCapturePersistsRealPixelsAndStopsThroughUi",
    ) { fixture, evidence ->
        withHost(fixture, CaptureScreen(initialUrl = NEUTRAL_PAGE)) { activity ->
            stage(evidence, "neutral-page")
            waitUntil("Neutral WebView did not finish layout") {
                onMain {
                    findWebView(activity.window.decorView)?.let {
                        it.width > 0 && it.height > 0 && it.progress == 100 &&
                            it.contentHeight * it.scale > it.height + 10
                    } == true
                }
            }
            compose.onNodeWithContentDescription(context.stringResource(MR.strings.capture_new_manga)).performClick()
            compose.onNode(hasText(context.stringResource(MR.strings.capture_book_name)) and hasSetTextAction())
                .performTextReplacement(fixture.book)
            compose.onNodeWithText(context.stringResource(MR.strings.action_ok)).performClick()
            compose.onNodeWithContentDescription(context.stringResource(MR.strings.capture_new_chapter)).performClick()
            compose.onNode(hasText(context.stringResource(MR.strings.capture_chapter_name)) and hasSetTextAction())
                .performTextReplacement(fixture.chapter)
            compose.onNodeWithText(context.stringResource(MR.strings.action_ok)).performClick()
            onMain {
                assertEquals(fixture.book, fixture.model.bookName)
                assertEquals(fixture.chapter, fixture.model.chapterName)
            }
            compose.onNodeWithContentDescription(context.stringResource(MR.strings.capture_continuous_start))
                .assertIsDisplayed().performClick()
            waitUntil("Continuous Start did not start the actual model") { fixture.model.continuous.value.running }
            fixture.captureJob = onMain { fixture.currentCaptureJob() }
            assertNotNull("Start must own an actual continuous job", fixture.captureJob)
            stage(evidence, "continuous-start")
            waitUntil("The first real capture was not persisted", 25_000) {
                fixture.model.continuous.value.count == 1 && fixture.pngFiles().size == 1
            }
            verifyPng(fixture.pngFiles().single(), evidence, darkOnLeft = true)
            stage(evidence, "first-png", 1)
            onMain {
                val view = checkNotNull(findWebView(activity.window.decorView))
                view.scrollTo(0, view.height)
            }
            waitUntil("The owned page did not scroll") {
                onMain { checkNotNull(findWebView(activity.window.decorView)).scrollY > 0 }
            }
            waitUntil("The changed neutral page was not persisted", 25_000) {
                fixture.model.continuous.value.count == 2 && fixture.pngFiles().size == 2
            }
            val job = checkNotNull(fixture.captureJob)
            compose.onNodeWithContentDescription(context.stringResource(MR.strings.capture_continuous_stop))
                .assertIsDisplayed().performClick()
            runBlocking { withTimeout(10_000) { job.join() } }
            assertFalse("Stop must end the actual continuous job", job.isActive)
            assertFalse("Stop must clear the running state", fixture.model.continuous.value.running)
            stage(evidence, "continuous-stopped", 2)
            compose.onNodeWithText(context.stringResource(MR.strings.capture_review_continue))
                .assertIsDisplayed()
            waitUntil("Review did not load both persisted pages") {
                !fixture.review.state.value.loading && fixture.review.state.value.pages.size == 2
            }
            fixture.pngFiles().forEachIndexed { index, file ->
                verifyPng(file, evidence, darkOnLeft = index == 0)
                compose.onNodeWithContentDescription(file.name).assertIsDisplayed()
            }
            val pages = JSONObject(File(fixture.chapterDirectory, CAPTURE_META_FILE).readText()).getJSONObject("pages")
            assertEquals(
                "Both saved pages must have actual metadata",
                setOf("001", "002"),
                pages.keys().asSequence().toSet(),
            )
            pages.keys().forEach { key ->
                assertTrue(
                    "Metadata must describe only the owned neutral page",
                    pages.getJSONObject(key).getString("url") == NEUTRAL_PAGE,
                )
            }
            val afterStop = fixture.snapshot()
            val stableUntil = SystemClock.elapsedRealtime() + 3_000
            while (SystemClock.elapsedRealtime() < stableUntil) {
                SystemClock.sleep(500)
                assertEquals(
                    "Files must remain unchanged after UI Stop and job completion",
                    afterStop,
                    fixture.snapshot(),
                )
            }
            assertEquals(2, fixture.model.continuous.value.count)
            val successes = fixture.observations.count {
                it.backend == WebViewCaptureBackend.PIXEL_COPY && it.pixelCopyResult == PixelCopy.SUCCESS &&
                    it.bitmapAvailable
            }
            assertTrue("Actual PixelCopy.SUCCESS must be observed independently of saved PNGs", successes > 0)
            assertTrue(fixture.observations.all { it.width > 0 && it.height > 0 })
            evidence.put("pixelCopySuccess", successes)
                .put("drawFallback", fixture.observations.count { it.backend == WebViewCaptureBackend.DRAW_FALLBACK })
                .put("pngCount", 2).put("metaPages", 2).put("postStopStableMs", 3_000)
                .put("continuousJobCompleted", true).put("reviewThumbnails", 2)
            val hashes = JSONArray()
            afterStop.forEach { (name, hash) -> hashes.put(JSONObject().put("file", name).put("sha256", hash)) }
            evidence.put("savedFiles", hashes)
            compose.onNodeWithText(context.stringResource(MR.strings.capture_review_continue)).performClick()
            compose.onNodeWithContentDescription(context.stringResource(MR.strings.capture_continuous_start))
                .assertIsDisplayed()
            assertEquals("Returning from review must not change stored captures", afterStop, fixture.snapshot())
            stage(evidence, "review-and-stop-verified", 2)
        }
    }

    @Test
    fun stopDuringProviderWriteDefersReviewUntilCommittedPageIsAccounted() = withCase(
        "stopDuringProviderWriteDefersReviewUntilCommittedPageIsAccounted",
        useSafFixture = true,
    ) { fixture, evidence ->
        withHost(fixture, CaptureScreen(initialUrl = NEUTRAL_PAGE)) { activity ->
            stage(evidence, "neutral-saf-page")
            waitUntil("Neutral WebView did not finish layout") {
                onMain {
                    findWebView(activity.window.decorView)?.let {
                        it.width > 0 && it.height > 0 && it.progress == 100 &&
                            it.contentHeight * it.scale > it.height + 10
                    } == true
                }
            }
            compose.onNodeWithContentDescription(context.stringResource(MR.strings.capture_new_manga)).performClick()
            compose.onNode(hasText(context.stringResource(MR.strings.capture_book_name)) and hasSetTextAction())
                .performTextReplacement(fixture.book)
            compose.onNodeWithText(context.stringResource(MR.strings.action_ok)).performClick()
            compose.onNodeWithContentDescription(context.stringResource(MR.strings.capture_new_chapter)).performClick()
            compose.onNode(hasText(context.stringResource(MR.strings.capture_chapter_name)) and hasSetTextAction())
                .performTextReplacement(fixture.chapter)
            compose.onNodeWithText(context.stringResource(MR.strings.action_ok)).performClick()
            onMain {
                assertEquals(fixture.book, fixture.model.bookName)
                assertEquals(fixture.chapter, fixture.model.chapterName)
            }
            val gate = fixture.gateFirstWrite()
            var boundaryFailure: Throwable? = null
            try {
                compose.onNodeWithContentDescription(context.stringResource(MR.strings.capture_continuous_start))
                    .assertIsDisplayed().performClick()
                waitUntil("Continuous Start did not start the actual model") { fixture.model.continuous.value.running }
                fixture.captureJob = onMain { fixture.currentCaptureJob() }
                val originalJob = checkNotNull(fixture.captureJob)
                waitUntil("The actual fresh SAF write did not enter its owned gate", 25_000) {
                    gate.enteredCount == 1
                }
                assertEquals(0, gate.exitedCount)
                assertFalse(gate.timedOut)
                assertFalse(gate.interrupted)
                fixture.assertGatedWriteObserved()
                evidence.put("actualSafWrite", true).put("stopWhileWriteBlocked", true)
                    .put("gateEntered", gate.enteredCount)
                stage(evidence, "provider-write-blocked")
                compose.onNodeWithContentDescription(context.stringResource(MR.strings.capture_continuous_stop))
                    .assertIsDisplayed().performClick()
                compose.waitForIdle()
                // This title is present even while Review is loading or its page list is empty.
                val prematureReview = compose.onAllNodesWithText(
                    context.stringResource(MR.strings.capture_review_title),
                ).fetchSemanticsNodes().isNotEmpty()
                evidence.put("prematureReviewWhenBlocked", prematureReview)
                assertEquals("The provider must still be blocked at the Review assertion", 0, gate.exitedCount)
                assertFalse("Review must not open before the active provider write settles", prematureReview)
                evidence.put("reviewWasHiddenUntilWriteSettled", true)
                stage(evidence, "review-deferred-until-write")
                gate.release()
                runBlocking { withTimeout(10_000) { originalJob.join() } }
                assertTrue("The original capture job must complete before review", originalJob.isCompleted)
                assertFalse(fixture.model.continuous.value.running)
                assertEquals(1, gate.exitedCount)
                assertFalse(gate.timedOut)
                assertFalse(gate.interrupted)
                waitUntil("Review did not include the completed in-flight page") {
                    !fixture.review.state.value.loading && fixture.review.state.value.pages.size == 1
                }
                compose.onNodeWithText(context.stringResource(MR.strings.capture_review_title)).assertIsDisplayed()
                compose.onNodeWithText(context.stringResource(MR.strings.capture_review_continue)).assertIsDisplayed()
                val page = fixture.pngFiles().single()
                assertEquals("001.png", page.name)
                verifyPng(page, evidence, darkOnLeft = true)
                compose.onNodeWithContentDescription(page.name).assertIsDisplayed()
                assertEquals(listOf(1), fixture.model.sessionPages)
                assertEquals(1, fixture.model.continuous.value.count)
                assertEquals(1, fixture.review.state.value.sessionPageCount)
                val pages = JSONObject(File(fixture.chapterDirectory, CAPTURE_META_FILE).readText())
                    .getJSONObject("pages")
                assertEquals(setOf("001"), pages.keys().asSequence().toSet())
                assertEquals(NEUTRAL_PAGE, pages.getJSONObject("001").getString("url"))
                val afterStop = fixture.snapshot()
                val callbacksAfterStop = fixture.observations.size
                val stableUntil = SystemClock.elapsedRealtime() + 3_000
                while (SystemClock.elapsedRealtime() < stableUntil) {
                    compose.waitForIdle()
                    SystemClock.sleep(250)
                    assertEquals("A settled UI Stop must not write another page", afterStop, fixture.snapshot())
                    assertEquals(
                        "A settled UI Stop must not request further captures",
                        callbacksAfterStop,
                        fixture.observations.size,
                    )
                }
                assertTrue(
                    "The UI boundary test must observe real PixelCopy success",
                    fixture.observations.any {
                        it.backend == WebViewCaptureBackend.PIXEL_COPY && it.pixelCopyResult == PixelCopy.SUCCESS &&
                            it.bitmapAvailable
                    },
                )
                evidence.put("gateExited", gate.exitedCount).put("pngCount", 1).put("metaPages", 1)
                    .put("reviewThumbnails", 1).put("sessionPages", JSONArray(fixture.model.sessionPages))
                    .put("continuousJobCompleted", true).put("postStopStableMs", 3_000)
                    .put("callbacksStableAfterStop", true)
                val hashes = JSONArray()
                afterStop.forEach { (name, hash) -> hashes.put(JSONObject().put("file", name).put("sha256", hash)) }
                evidence.put("savedFiles", hashes)
                compose.onNodeWithText(context.stringResource(MR.strings.capture_review_continue)).performClick()
                compose.onNodeWithContentDescription(context.stringResource(MR.strings.capture_continuous_start))
                    .assertIsDisplayed()
                assertEquals(afterStop, fixture.snapshot())
                stage(evidence, "saf-stop-review-verified", 1)
            } catch (caught: Throwable) {
                boundaryFailure = caught
                throw caught
            } finally {
                // Even a premature-Review assertion must release and settle the original IO worker.
                gate.release()
                runCatching {
                    fixture.captureJob?.let { job -> runBlocking { withTimeout(10_000) { job.join() } } }
                }.onFailure { cleanupFailure ->
                    if (boundaryFailure == null) throw cleanupFailure
                    boundaryFailure!!.addSuppressed(cleanupFailure)
                }
                evidence.put("gateExited", gate.exitedCount).put("gateTimedOut", gate.timedOut)
                    .put("gateInterrupted", gate.interrupted)
            }
        }
    }

    private fun withCase(
        name: String,
        useSafFixture: Boolean = false,
        block: (Fixture, JSONObject) -> Unit,
    ) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit captureScreenUi=true is required", arguments.getString("captureScreenUi") == "true")
        val runId = UUID.fromString(checkNotNull(arguments.getString("captureScreenUiRunId"))).toString()
        val reportDirectory = File(context.cacheDir, "capture-screen-ui-$runId")
        check(reportDirectory.mkdir() || reportDirectory.isDirectory)
        check(reportDirectory.canonicalFile == File(context.cacheDir.canonicalFile, reportDirectory.name))
        val reportFile = File(reportDirectory, "$name.json")
        check(!reportFile.exists()) { "Use a fresh captureScreenUiRunId" }
        val evidence = JSONObject().put("runId", runId).put("test", name).put("passed", false)
        activeReport = reportFile to evidence
        var failure: Throwable? = null
        var fixture: Fixture? = null
        var safSession: SafAliasDocumentsProvider.Session? = null
        try {
            if (useSafFixture) safSession = SafAliasDocumentsProvider.createSession(context)
            fixture = Fixture(safSession)
            block(fixture, evidence)
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            runCatching {
                fixture?.let { owned ->
                    val continuous = owned.model.continuous.value
                    evidence.put("continuousCount", continuous.count)
                        .put("continuousRunning", continuous.running)
                        .put("sessionPages", JSONArray(onMain { owned.model.sessionPages }))
                        .put("stopReason", continuous.stopReason?.name ?: JSONObject.NULL)
                        .put("stopDetail", continuous.stopDetail?.name ?: JSONObject.NULL)
                        .put("bookNonempty", onMain { owned.model.bookName.isNotBlank() })
                        .put("chapterNonempty", onMain { owned.model.chapterName.isNotBlank() })
                    val files = owned.captureFiles()
                    evidence.put("ownedFileCount", files.size)
                    evidence.put(
                        "ownedFiles",
                        JSONArray().apply {
                            files.take(8).forEach { file ->
                                put(
                                    JSONObject().put("path", owned.relativePath(file))
                                        .put("bytes", file.length()),
                                )
                            }
                        },
                    )
                    evidence.put("captureCallbacks", owned.observations.size)
                        .put(
                            "pixelCopySuccess",
                            owned.observations.count {
                                it.backend == WebViewCaptureBackend.PIXEL_COPY &&
                                    it.pixelCopyResult == PixelCopy.SUCCESS && it.bitmapAvailable
                            },
                        )
                        .put(
                            "drawFallback",
                            owned.observations.count { it.backend == WebViewCaptureBackend.DRAW_FALLBACK },
                        )
                }
            }.onFailure { caught ->
                if (failure == null) failure = caught else failure!!.addSuppressed(caught)
            }
            try {
                fixture?.close(retainFiles = failure != null)
                if (fixture == null && safSession != null) {
                    safSession.close(retainOwnedFiles = true)
                }
            } catch (caught: Throwable) {
                if (failure == null) failure = caught else failure!!.addSuppressed(caught)
            }
            if (failure != null) {
                evidence.put(
                    "retainedFixture",
                    fixture?.directoryName ?: safSession?.directory?.name ?: JSONObject.NULL,
                )
            }
        }
        failure?.let { throw it }
    }

    private fun sealReport(failure: Throwable?) {
        val (file, evidence) = activeReport ?: return
        evidence.put("passed", failure == null)
            .put("failureClass", failure?.javaClass?.simpleName ?: JSONObject.NULL)
        val bytes = evidence.toString().toByteArray(Charsets.UTF_8)
        check(bytes.size < 16_384)
        file.outputStream().use { it.write(bytes) }
        val summary = JSONObject().put("runId", evidence.getString("runId"))
            .put("test", evidence.getString("test")).put("bytes", bytes.size)
            .put("sha256", sha256(bytes)).put("passed", failure == null)
            .put("pixelCopySuccess", evidence.optInt("pixelCopySuccess"))
        instrumentation.sendStatus(2, Bundle().apply { putString("CAPTURE_SCREEN_UI", summary.toString()) })
        println("CAPTURE_SCREEN_UI $summary")
        activeReport = null
    }

    private fun stage(evidence: JSONObject, stage: String, count: Int = 0) {
        val value = JSONObject().put("runId", evidence.getString("runId"))
            .put("test", evidence.getString("test")).put("stage", stage).put("count", count)
        instrumentation.sendStatus(2, Bundle().apply { putString("CAPTURE_SCREEN_UI_STAGE", value.toString()) })
    }

    private fun withHost(fixture: Fixture, screen: Screen, block: (MainActivity) -> Unit) {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            lateinit var host: MainActivity
            scenario.onActivity { activity ->
                host = activity
                activity.ready = true
                activity.setContent {
                    TachiyomiTheme {
                        Navigator(screen) { navigator ->
                            // Voyager provides its own owner; override it inside its content lambda.
                            CompositionLocalProvider(LocalViewModelStoreOwner provides fixture) {
                                navigator.lastItem.Content()
                            }
                        }
                    }
                }
            }
            compose.waitForIdle()
            block(host)
        }
    }

    private inner class Fixture(
        private val safSession: SafAliasDocumentsProvider.Session? = null,
    ) : ViewModelStoreOwner {
        val directoryName = safSession?.directory?.name ?: "capture-screen-owned-${UUID.randomUUID()}"
        private val directory = safSession?.directory ?: File(context.cacheDir, directoryName).apply { check(mkdir()) }
        private val storageUri = safSession?.let { session ->
            val authority = context.packageManager.getProviderInfo(
                ComponentName(context, SafAliasDocumentsProvider::class.java),
                0,
            ).authority
            DocumentsContract.buildTreeDocumentUri(authority, "${session.id}|root-a")
        } ?: Uri.fromFile(directory)
        val book = "neutral-${UUID.randomUUID()}"
        val chapter = "chapter-1"
        val chapterDirectory = File(directory, "local/$book/$chapter")
        override val viewModelStore = ViewModelStore()
        private val storage = StorageManager(
            context,
            StoragePreferences(
                object : FolderProvider {
                    override fun directory(): File = directory
                    override fun path(): String = storageUri.toString()
                },
                InMemoryPreferenceStore(),
            ),
        )
        val model = onMain {
            CaptureViewModel(
                context.applicationContext as Application,
                storage,
                UiPreferences(InMemoryPreferenceStore()),
            )
        }
        val review = onMain {
            CaptureReviewViewModel(storageManager = storage, context = context.applicationContext as Application)
        }
        val observations = CopyOnWriteArrayList<WebViewCaptureObservation>()
        var captureJob: Job? = null
        private var writeGate: SafAliasDocumentsProvider.WriteGate? = null
        private val oldObserver = onMain {
            val old = webViewCaptureObserver
            webViewCaptureObserver = { observations.add(it) }
            viewModelStore.put(CAPTURE_MODEL_KEY, model)
            viewModelStore.put("capture-review", review)
            old
        }

        fun pngFiles(): List<File> = chapterDirectory.listFiles().orEmpty()
            .filter { it.name.matches(Regex("[0-9]{3}\\.png")) }.sortedBy { it.name }

        fun captureFiles(): List<File> = directory.walkTopDown().filter { it.isFile }.toList()

        fun relativePath(file: File): String = file.relativeTo(directory).invariantSeparatorsPath

        fun snapshot(): Map<String, String> = captureFiles().associate {
            it.relativeTo(directory).invariantSeparatorsPath to sha256(it.readBytes())
        }

        fun currentCaptureJob(): Job? = CaptureViewModel::class.java.getDeclaredField("continuousJob")
            .apply { isAccessible = true }.get(model) as Job?

        fun gateFirstWrite(): SafAliasDocumentsProvider.WriteGate {
            val session = checkNotNull(safSession)
            check(storageUri.scheme == "content")
            return session.gateNextWrite(File(chapterDirectory, "001.png")).also { writeGate = it }
        }

        fun assertGatedWriteObserved() {
            val session = checkNotNull(safSession)
            val gate = checkNotNull(writeGate)
            assertTrue(File(chapterDirectory, "001.png").isFile)
            assertTrue(session.writeModes.any { it.first == gate.documentId && it.second == "wt" })
            assertTrue(session.createRequests.any { it.first == "image/png" && it.second == "001" })
        }

        fun close(retainFiles: Boolean) {
            var failure: Throwable? = null
            fun cleanup(block: () -> Unit) {
                try {
                    block()
                } catch (caught: Throwable) {
                    if (failure == null) failure = caught else failure!!.addSuppressed(caught)
                }
            }
            val job = captureJob ?: onMain { currentCaptureJob() }
            val modelJobs = onMain {
                listOfNotNull(model.viewModelScope.coroutineContext[Job], review.viewModelScope.coroutineContext[Job])
            }
            cleanup { writeGate?.release() }
            cleanup { onMain { viewModelStore.clear() } }
            cleanup { if (job != null) runBlocking { withTimeout(10_000) { job.join() } } }
            cleanup { runBlocking { withTimeout(10_000) { modelJobs.forEach { it.join() } } } }
            cleanup { onMain { webViewCaptureObserver = oldObserver } }
            cleanup {
                val storageScope = StorageManager::class.java.getDeclaredField("scope").apply { isAccessible = true }
                    .get(storage) as CoroutineScope
                storageScope.cancel()
                runBlocking { withTimeout(5_000) { storageScope.coroutineContext[Job]?.join() } }
            }
            // A timed-out worker must retain its files for diagnosis rather than race deletion.
            if (failure != null) throw checkNotNull(failure)
            // Only this UUID fixture's recognized files and empty directories may be deleted.
            check(directory.canonicalFile == File(context.cacheDir.canonicalFile, directoryName))
            val allowedDirectories =
                listOfNotNull(
                    chapterDirectory,
                    File(directory, "local/$book"),
                    File(directory, "local"),
                    safSession?.chapter,
                    directory,
                )
            allowedDirectories.forEach {
                check(it.canonicalFile == File(directory.canonicalFile, it.relativeTo(directory).path))
                check(it.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath()))
            }
            val files = captureFiles()
            check(files.size <= 8)
            files.forEach { file ->
                val parent = file.parentFile
                val allowed = (
                    parent == chapterDirectory &&
                        (file.name.matches(Regex("[0-9]{3}\\.png")) || file.name == CAPTURE_META_FILE)
                    ) ||
                    (parent == File(directory, "local/$book") && file.name == MANGA_META_FILE)
                check(allowed && file.canonicalFile == File(parent!!.canonicalFile, file.name))
            }
            val observedDirectories = directory.walkTopDown().filter { it.isDirectory }.toSet()
            check(observedDirectories.all { it in allowedDirectories })
            if (safSession != null) {
                safSession.close(retainOwnedFiles = retainFiles)
                return
            }
            if (retainFiles) return
            files.forEach { check(it.delete()) }
            allowedDirectories.forEach {
                if (it.exists()) check(it.delete())
            }
            check(!directory.exists())
        }
    }

    private fun verifyPng(file: File, evidence: JSONObject, darkOnLeft: Boolean) {
        val bitmap = checkNotNull(BitmapFactory.decodeFile(file.path)) { "Persisted PNG must decode" }
        try {
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
            var dark = 0
            var light = 0
            for (y in 0 until bitmap.height step 8) {
                for (x in 0 until bitmap.width step 8) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = pixel shr 16 and 255
                    val g = pixel shr 8 and 255
                    val b = pixel and 255
                    if (abs(r - 24) < 12 && abs(g - 24) < 12 && abs(b - 24) < 12) dark++
                    if (abs(r - 232) < 12 && abs(g - 232) < 12 && abs(b - 232) < 12) light++
                }
            }
            assertTrue("Saved real pixels must contain the neutral page's dark region", dark > 100)
            assertTrue("Saved real pixels must contain the neutral page's light region", light > 100)
            val left = bitmap.getPixel(bitmap.width / 4, bitmap.height / 2) and 255
            val right = bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 2) and 255
            assertTrue(
                "Persisted pixels must match the displayed neutral page before/after scrolling",
                if (darkOnLeft) left < 48 && right > 208 else left > 208 && right < 48,
            )
            evidence.put("decodedWidth", bitmap.width).put("decodedHeight", bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun waitUntil(message: String, timeout: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (true) {
            // Compose's test clock must advance the clean grabber's two withFrameNanos waits.
            // Sleeping the instrumentation thread alone leaves that real UI capture pending.
            compose.waitForIdle()
            if (condition()) return
            check(SystemClock.elapsedRealtime() < deadline) { message }
            SystemClock.sleep(100)
        }
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        instrumentation.runOnMainSync { result.set(runCatching(block)) }
        return result.get().getOrThrow()
    }

    private inner class OwnPrewarmGate : TestRule {
        override fun apply(base: Statement, description: Description): Statement = object : Statement() {
            override fun evaluate() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                assertEquals("Use the isolated audit package", "app.yomitori.yakuyomi.audit", context.packageName)
                val manager = Injekt.get<TranslationManager>()
                val downloads = Injekt.get<DownloadManager>()
                val service = Injekt.get<TranslationEngineService>()
                runBlocking { withTimeout(10_000) { manager.ensureRestored() } }
                assertTrue(manager.queueState.value.isEmpty())
                assertTrue(downloads.queueState.value.isEmpty() && !downloads.isRunning)
                assertEquals(0, TranslationBusy.active.value)
                assertFalse(service.warm.value)
                assertFalse(service.loading.value)
                awaitBackground()
                val settings = OnlineReaderSettings(context)
                var failure: Throwable? = null
                try {
                    val liveTranslate = Injekt.get<TranslationPreferences>().liveTranslate
                    if (liveTranslate.get()) settings.set(liveTranslate, false)
                    base.evaluate()
                } catch (caught: Throwable) {
                    failure = caught
                } finally {
                    fun cleanup(block: () -> Unit) {
                        try {
                            block()
                        } catch (caught: Throwable) {
                            if (failure == null) failure = caught else failure!!.addSuppressed(caught)
                        }
                    }
                    cleanup {
                        awaitBackground()
                        assertFalse(service.warm.value)
                        assertFalse(service.loading.value)
                        assertEquals(0, TranslationBusy.active.value)
                        assertTrue(manager.queueState.value.isEmpty())
                        assertTrue(downloads.queueState.value.isEmpty() && !downloads.isRunning)
                    }
                    cleanup { settings.close() }
                    cleanup { sealReport(failure) }
                }
                failure?.let { throw it }
            }
        }

        private fun awaitBackground() = runBlocking {
            withTimeout(10_000) {
                withContext(Dispatchers.Main.immediate) {
                    ProcessLifecycleOwner.get().lifecycle.currentStateFlow.first {
                        !it.isAtLeast(Lifecycle.State.STARTED)
                    }
                }
            }
        }
    }

    private object MoreEntry : Screen() {
        @Composable
        override fun Content() = MoreTab.Content()
    }

    companion object {
        private const val CAPTURE_MODEL_KEY =
            "androidx.lifecycle.ViewModelProvider.DefaultKey:eu.kanade.tachiyomi.ui.capture.CaptureViewModel"

        private val NEUTRAL_PAGE = "data:text/html;base64," + Base64.encodeToString(
            (
                "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                    "<style>html,body{margin:0;padding:0}section{height:100vh;width:100%;" +
                    "background:linear-gradient(90deg,#181818 0 50%,#e8e8e8 50% 100%)}" +
                    "section+section{background:linear-gradient(90deg,#e8e8e8 0 50%,#181818 50% 100%)}</style>" +
                    "</head><body><section></section><section></section></body></html>"
                ).toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP,
        )

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
