package mihon.source.hitomi

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.androidx.AndroidScreenLifecycleOwner
import cafe.adriel.voyager.core.lifecycle.ScreenLifecycleStore
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mihon.translation.OnlineReaderSettings
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.source.SourceSearchPagingSource
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.interactor.GetRemoteManga
import tachiyomi.domain.source.repository.SourcePagingSource
import tachiyomi.domain.source.repository.SourceRepository
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.fullType
import uy.kohesive.injekt.api.get
import java.io.File
import java.lang.reflect.Type
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Opt in with -e liveHitomiBrowse true. These tests retain MainActivity's real composition,
 * navigate from SourcesTab, and use production Hitomi transport and remote suggestions.
 * Accessibility text/selection edits and ACTION_IME_ENTER are intentional: this covers the
 * app's editor action, not physical keyboard keys or Samsung/Gboard composing sequences.
 * Requires an isolated .audit target. A test-only GetRemoteManga factory supplies a delegated
 * repository whose Hitomi search uses production SourceSearchPagingSource and a recording
 * Source delegate. The real screen's SourceManager still returns the actual HitomiSource, so
 * suggestions retain the production path. Inputs are observed at Source.getSearchManga before
 * forwarding unchanged to the real source/transport. This is not an HTTP wire observation.
 * Actual returned titles are kept in memory solely to match the real result list; no gallery
 * titles, IDs, URLs, or images are written to the report. No ViewModel action or fake result is used.
 *
 * The two actualSamsung methods additionally require actualSamsungHitomiIme=true. They open a
 * fresh empty audit editor, then wait at most 180 seconds across every externally driven phase.
 * They never set text, choose the suggestion, or synthesize the final editor action. An external
 * operator must touch the real Samsung IME and the app recommendation after each READY phase.
 * The selected Samsung IME and Source boundary are checked; actual key-touch provenance must be
 * supplied by the operator separately. After the Source response, the same protected Activity waits
 * for an operator acknowledgement of restoring English. Successful runs retain three flat files:
 * progress.json, result.json and the host-supplied operator-restore.json. The acknowledgement is
 * external evidence; this test does not inspect a keyboard language label or the host image itself.
 */
@Suppress("DEPRECATION", "RestrictedApi")
@RunWith(AndroidJUnit4::class)
class HitomiBrowseScreenIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var editorDiagnosticReport: JSONObject? = null
    private var editorDiagnosticViewModel: BrowseSourceViewModel? = null
    private val neutralEditorTexts = mutableSetOf("")
    private var strictNeutralEditorText = false

    @Test
    fun toolbarRemoteSuggestionReplacesCurrentTokenAndAccessibilityImeSubmits() = verifyBrowse(floating = false)

    @Test
    fun floatingRemoteSuggestionStaysExpandedAndAccessibilityImeSubmits() = verifyBrowse(floating = true)

    @Test
    fun toolbarActualSamsungImeCommitsHangulAndSubmitsSourceQuery() =
        verifyBrowse(floating = false, actualSamsungIme = true)

    @Test
    fun floatingActualSamsungImeCommitsHangulAndSubmitsSourceQuery() =
        verifyBrowse(floating = true, actualSamsungIme = true)

    private fun verifyBrowse(floating: Boolean, actualSamsungIme: Boolean = false) {
        val arguments = InstrumentationRegistry.getArguments()
        if (actualSamsungIme) {
            assumeTrue(
                "Requires explicit actualSamsungHitomiIme=true",
                arguments.getString("actualSamsungHitomiIme") == "true",
            )
            check(arguments.getString("liveHitomiBrowse") == "true") {
                "Actual IME mode also requires liveHitomiBrowse=true"
            }
        } else {
            assumeTrue(arguments.getString("liveHitomiBrowse") == "true")
        }
        // Guard before reading DI, preferences, or the default database.
        assertEquals("Live browse audit requires the isolated target", AUDIT_PACKAGE, context.packageName)
        assertTrue("Accessibility IME action requires Android 11+", Build.VERSION.SDK_INT >= 30)
        if (actualSamsungIme) requireActualImeIdle()
        val sources = Injekt.get<SourcePreferences>()
        val library = Injekt.get<LibraryPreferences>()
        val base = Injekt.get<BasePreferences>()
        val originalRepository = Injekt.get<SourceRepository>()
        val saved = SavedPreferences()
        val prewarmSettings = if (actualSamsungIme) OnlineReaderSettings(context) else null
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        val report = JSONObject()
            .put("floating", floating)
            .put("actualMainActivityAndSourcesTab", true)
            .put("remoteSuggestions", true)
            .put("editingMethod", "accessibility-set-text-and-selection")
            .put("submissionMethod", "accessibility-ime-enter")
            .put("physicalKeyboardOrCompositionVerified", false)
            .put("forcedResponseReorderingVerified", false)
            .put("sourceSearchBoundaryObserved", false)
            .put("httpWirePayloadObserved", false)
            .put("directSearchOracleUsed", false)
            .put("passed", false)
            .put("editorObservations", JSONArray())
        if (actualSamsungIme) {
            strictNeutralEditorText = true
            neutralEditorTexts += ACTUAL_IME_NEUTRAL_TEXTS
            report.put("editingMethod", "external-operator-Samsung-IME-key-touches")
                .put("submissionMethod", "external-operator-Samsung-IME-search-key")
                .put("externalOperatorKeyTouchProvenanceVerifiedByTest", false)
                .put("textSetByActualImeMode", false).put("suggestionClickedByActualImeMode", false)
                .put("editorActionSynthesizedByActualImeMode", false)
                .put("expectedExactSourceQuery", ACTUAL_IME_QUERY)
                .put("sourceQueryWhitespaceNormalizedByTest", false)
                .put("imeCompositionRangeDirectlyObserved", false)
                .put("fixtureRetainsOnlySanitizedReports", true)
                .put("productionNetworkAndImageCachesMayBePopulated", true)
                .put("operatorLanguageRestoreAcknowledgementRequired", true)
                .put("operatorLanguageLabelVerifiedByTest", false)
                .put("operatorKeyGridImageDigestVerifiedByTest", false)
                .put(
                    "successfulFlatOutputFiles",
                    JSONArray(listOf("progress.json", "result.json", OPERATOR_RESTORE_ACK)),
                )
                .put("interactiveDeadlineMilliseconds", ACTUAL_IME_TIMEOUT_MS)
        }
        editorDiagnosticReport = report
        var completed = false
        var failure: Throwable? = null
        var factoryLease: RemoteMangaFactoryLease? = null
        var recordingSource: RecordingSource? = null
        var browseJob: Job? = null
        var externalProgress: ActualImeProgress? = null
        try {
            if (actualSamsungIme) {
                prewarmSettings!!.set(Injekt.get<TranslationPreferences>().liveTranslate, false)
                saved.set(library.autoClearChapterCache, false)
                requireActualImeIdle()
            }
            saved.set(sources.showNsfwSource, true)
            saved.set(sources.enabledLanguages, sources.enabledLanguages.get() + "all")
            saved.set(sources.disabledSources, sources.disabledSources.get() - HitomiSource.ID.toString())
            saved.set(library.floatingSearchBar, floating)
            saved.preserve(sources.lastUsedSource)
            // This covers source browsing after onboarding, not the separate first-run flow.
            saved.set(base.shownOnboardingFlow, true)
            // MainActivity resets incognito on a fresh launch; restore both value and presence.
            saved.preserve(base.incognitoMode)
            saved.set(sources.sourceDisplayMode, LibraryDisplayMode.List)
            saved.set(sources.hideInLibraryItems, false)
            saved.set(sources.browseFilterFavorite, TriState.DISABLED)
            saved.set(sources.browseFilterRead, TriState.DISABLED)
            saved.set(sources.browseFilterFetched, TriState.DISABLED)
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val source = runBlocking {
                withTimeout(30_000) {
                    val manager = Injekt.get<SourceManager>()
                    manager.isInitialized.first { it }
                    manager.get(HitomiSource.ID) as HitomiSource
                }
            }
            val recorder = RecordingSource(
                source,
                strictAllowedQueries = if (actualSamsungIme) setOf(ACTUAL_IME_QUERY) else null,
                strictExpectedFilters = if (actualSamsungIme) snapshotFilters(source.getFilterList()) else null,
            ).also { recordingSource = it }
            val recordingRepository = object : SourceRepository by originalRepository {
                override fun search(sourceId: Long, query: String, filterList: FilterList): SourcePagingSource {
                    if (sourceId != HitomiSource.ID) return originalRepository.search(sourceId, query, filterList)
                    // Identical production construction, with only the Source call recorded.
                    return SourceSearchPagingSource(recorder, query, filterList)
                }
            }
            val lease = RemoteMangaFactoryLease(originalRepository, recordingRepository)
            factoryLease = lease
            lease.install()
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Constants.SHORTCUT_SOURCES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                // This is the real SourcesTab row, not a pushed screen or fixture composition.
                withNode("Hitomi source row", { it.text?.toString()?.lineSequence()?.firstOrNull() == "Hitomi" }) {
                    touch(it)
                }
                lateinit var viewModel: BrowseSourceViewModel
                waitUntil("Real Hitomi BrowseSourceScreen did not open") {
                    var observed: BrowseSourceViewModel? = null
                    scenario.onActivity { observed = existingBrowseModel(it) }
                    observed?.also { viewModel = it } != null
                }
                browseJob = checkNotNull(viewModel.viewModelScope.coroutineContext[Job])
                editorDiagnosticViewModel = viewModel
                assertSame("Actual screen source must retain the production Hitomi subtype", source, viewModel.source)
                val actualInteractor = BrowseSourceViewModel::class.java.getDeclaredField("getRemoteManga")
                    .apply { isAccessible = true }.get(viewModel) as GetRemoteManga
                assertSame(
                    "Recording must be installed before the real screen VM is created",
                    recordingRepository,
                    repositoryOf(actualInteractor),
                )
                assertSame(
                    "The global SourceRepository registration must remain untouched",
                    originalRepository,
                    Injekt.get<SourceRepository>(),
                )
                val initialListing = viewModel.state.value.listing
                assertTrue(
                    "SourcesTab must open a normal remote listing",
                    initialListing is BrowseSourceViewModel.Listing.Latest ||
                        initialListing is BrowseSourceViewModel.Listing.Popular,
                )
                report.put("initialListing", initialListing.javaClass.simpleName)
                    .put("realSourceIdentityPreserved", true)
                    .put("realScreenUsesRecordingRepository", true)
                if (actualSamsungIme) {
                    val progress = ActualImeProgress(floating).also { externalProgress = it }
                    report.put("actualImeOutputDirectory", progress.directory.absolutePath)
                    verifyActualSamsungIme(
                        viewModel,
                        recorder,
                        snapshotFilters(source.getFilterList()),
                        progress,
                        report,
                    )
                    return@use
                }
                openEditor()
                setText("jap")
                awaitSuggestion(JAPANESE)
                assertEquals(
                    "Typing and remote suggestions must not submit",
                    initialListing,
                    viewModel.state.value.listing,
                )
                assertEquals("Typing must not reach Source.getSearchManga", 0, recorder.startedCount())
                report.put("remoteJapaneseSuggestionFromJap", true)
                if (floating) verifySuggestionScrollDoesNotCollapse()

                val cases = JSONArray().also { report.put("cases", it) }
                verifySearchSubmission(
                    floating = floating,
                    viewModel = viewModel,
                    recorder = recorder,
                    expectedFilters = snapshotFilters(source.getFilterList()),
                    input = "jap type:manga",
                    caret = 3,
                    expectedQuery = "$JAPANESE type:manga",
                    expectedCaret = JAPANESE.length + 1,
                    label = "positive-current-token-with-suffix",
                    report = cases,
                    supersedeRequests = true,
                )
                verifySearchSubmission(
                    floating = floating,
                    viewModel = viewModel,
                    recorder = recorder,
                    expectedFilters = snapshotFilters(source.getFilterList()),
                    input = "type:manga -jap tag:full_color",
                    caret = "type:manga -jap".length,
                    expectedQuery = "type:manga -$JAPANESE tag:full_color",
                    expectedCaret = "type:manga -$JAPANESE ".length,
                    label = "excluded-current-token-with-prefix-and-suffix",
                    report = cases,
                    supersedeRequests = false,
                )
            }
            completed = true
        } catch (error: Throwable) {
            failure = error
            report.put("failureClass", error.javaClass.name)
            throw error
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(label: String, action: () -> Unit) {
                try {
                    action()
                    report.put(label, true)
                } catch (error: Throwable) {
                    cleanupFailures += error
                    report.put(label, false).put("${label}FailureClass", error.javaClass.name)
                }
            }
            // ActivityScenario is already closed. Do not restore DI/preferences under late paging work.
            cleanup("latePagingResponsesDrained") {
                runBlocking { withTimeout(30_000) { browseJob?.cancelAndJoin() } }
                recordingSource?.awaitDrained()
            }
            cleanup("interactorFactoryRestoredExactly") { factoryLease?.restoreAndVerify() }
            cleanup("sourceRepositoryIdentityPreserved") {
                assertSame(originalRepository, Injekt.get<SourceRepository>())
            }
            cleanup("preferencesRestoredIncludingPresence") { saved.restoreAndVerify() }
            if (actualSamsungIme) {
                cleanup("actualImeQueueAndEngineRemainIdle") {
                    awaitAuditBackground()
                    requireActualImeIdle()
                }
                cleanup("prewarmPreferenceRestoredIncludingPresence") { prewarmSettings!!.close() }
            }
            cleanup("automationFlagsRestored") {
                automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
            }
            if (actualSamsungIme) {
                cleanup("actualImeProgressFinalized") {
                    externalProgress?.finish(completed && cleanupFailures.isEmpty(), recordingSource)
                }
            }
            report.put("sourceCalls", recordingSource?.report() ?: JSONArray())
                .put("sourceSearchBoundaryObserved", (recordingSource?.startedCount() ?: 0) > 0)
                .put("passed", completed && cleanupFailures.isEmpty())
            cleanup("reportWritten") {
                val name = if (floating) "floating" else "toolbar"
                val output = externalProgress?.let { File(it.directory, "result.json") } ?: File(
                    context.getExternalFilesDir(null),
                    "hitomi-browse-screen/${UUID.randomUUID()}/$name.json",
                )
                check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
                output.writeText(report.toString(2))
                println("HITOMI_BROWSE_SCREEN_REPORT=${output.absolutePath}")
                println(report.toString(2))
            }
            if (actualSamsungIme && cleanupFailures.isNotEmpty()) {
                cleanup("failedActualImeProgressUpdated") { externalProgress?.finish(false, recordingSource) }
            }
            if (cleanupFailures.isNotEmpty()) {
                val cleanupError = AssertionError("Live browse audit cleanup failed")
                cleanupFailures.forEach(cleanupError::addSuppressed)
                if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
            }
        }
    }

    /** Actual keys/recommendation are supplied by the operator, never by this waiting mode. */
    private fun verifyActualSamsungIme(
        viewModel: BrowseSourceViewModel,
        recorder: RecordingSource,
        expectedFilters: List<FilterSnapshot>,
        progress: ActualImeProgress,
        report: JSONObject,
    ) {
        openEditor() // Opens/focuses the empty app field only; no text or editor action is sent.
        val initialListing = viewModel.state.value.listing
        fun requireNotSubmitted() {
            assertEquals("No Source search is allowed before the external IME Search key", 0, recorder.startedCount())
            assertTrue("Editing must retain the initial listing", viewModel.state.value.listing == initialListing)
        }
        val initial = checkNotNull(actualImeEditor()) { "A fresh visible audit editor is required" }
        assertTrue("Never overwrite a pre-existing query: the fresh editor must be empty", initial.safeText == "")
        assertTrue("The fresh editor must have focus", initial.focused)
        assertTrue("The fresh VM editor must be empty", viewModel.state.value.toolbarQuery.orEmpty().isEmpty())
        requireNotSubmitted()
        val emptyCaretAtZero = initial.start == 0 && initial.end == 0
        val emptyCaretUnreported = initial.start == -1 && initial.end == -1
        report.put("initialEmptySelectionStart", initial.start).put("initialEmptySelectionEnd", initial.end)
            .put("initialEmptyCaretReported", emptyCaretAtZero)
            .put("initialEmptyCaretAtZeroVerified", emptyCaretAtZero)
            .put("initialEmptySelectionWasNormalized", false)
        // Compose omits AccessibilityNodeInfo.setTextSelection for empty iterable text. Android's
        // unchanged -1/-1 sentinel is an unreported cursor, never evidence of caret position zero.
        assertTrue(
            "Empty editor selection must be reported zero or the paired unreported sentinel",
            emptyCaretAtZero || emptyCaretUnreported,
        )
        val imePackage = selectedSamsungImePackage()
        waitUntil("Selected Samsung IME window must be visible", timeoutMs = 10_000) {
            samsungImeWindowVisible(imePackage)
        }
        report.put("selectedSamsungImePackageVerified", true)
            .put("visibleSamsungImeWindowBeforeExternalInput", true)
            .put("keyboardSettingsOrSubtypeChangedByTest", false)
            .put("freshEditorWasEmpty", true)
            .put("sourceCallCountMayIncludePaging", true)
        progress.beginWaiting()

        fun awaitExternal(phase: String, condition: (ActualImeEditor?) -> Boolean) {
            progress.observe(phase, actualImeEditor(), recorder)
            while (SystemClock.elapsedRealtime() < progress.deadline) {
                assertTrue("Selected IME changed during the operator phase", selectedSamsungImePackage() == imePackage)
                val editor = actualImeEditor()
                progress.observe(phase, editor, recorder)
                if (condition(editor)) return
                SystemClock.sleep(75)
            }
            throw AssertionError("External Samsung IME phase exceeded the shared 180-second deadline: $phase")
        }

        awaitExternal("READY_FOR_JAP") { editor ->
            requireNotSubmitted()
            editor?.safeText == "jap" && editor.focused && editor.start == 3 && editor.end == 3 &&
                viewModel.state.value.toolbarQuery == "jap" &&
                findNode { isSuggestionLabel(it, JAPANESE) }?.also { it.recycle() } != null
        }
        report.put("externalJapAndRemoteSuggestionObserved", true)

        awaitExternal("READY_FOR_RECOMMENDATION") { editor ->
            requireNotSubmitted()
            editor?.safeText == "$JAPANESE " && editor.focused && editor.start == JAPANESE.length + 1 &&
                editor.end == JAPANESE.length + 1 && viewModel.state.value.toolbarQuery == "$JAPANESE "
        }
        report.put("externalSuggestionSelectionAndCaretVerified", true)
            .put("suggestionSelectionDidNotSubmitSourceSearch", true)

        awaitExternal("READY_FOR_HANGUL") { editor ->
            requireNotSubmitted()
            editor?.safeText == ACTUAL_IME_QUERY && editor.focused && editor.start == ACTUAL_IME_QUERY.length &&
                editor.end == ACTUAL_IME_QUERY.length && viewModel.state.value.toolbarQuery == ACTUAL_IME_QUERY
        }
        report.put("finalHangulEditorAndCaretBeforeSubmitVerified", true)
            .put("noSourceSearchBeforeExternalImeSearch", true)

        awaitExternal("READY_FOR_IME_SEARCH") { recorder.startedCount() > 0 }
        val first = recorder.callsAfter(0).first()
        assertEquals("The external editor must start Source paging at page one", 1, first.page)
        assertTrue("Source must receive the complete exact Hangul query", first.query == ACTUAL_IME_QUERY)
        assertTrue("Source must receive the unchanged ordered filters", first.filters == expectedFilters)
        assertTrue(
            "The actual VM must submit the same complete Hangul query",
            (viewModel.state.value.listing as? BrowseSourceViewModel.Listing.Search)?.query == ACTUAL_IME_QUERY,
        )
        awaitExternal("WAITING_FOR_SOURCE_RESPONSE") { recorder.completed(first.sequence) }
        val response = recorder.response(first.sequence)
        assertEquals("Actual source call must succeed; only its error class is retained", "success", response.outcome)
        recorder.callsAfter(0).forEach {
            assertTrue("Every paging call must retain the neutral exact query", it.query == ACTUAL_IME_QUERY)
            assertTrue("Every paging call must retain the ordered filter state", it.filters == expectedFilters)
            assertTrue("Every Source page must be positive", it.page > 0)
        }
        report.put("sourceSearchBoundaryObserved", true)
            .put("sourcePage", first.page).put("sourceQuery", ACTUAL_IME_QUERY)
            .put("sourceFilters", filtersReport(first.filters))
            .put("sourceResponseMangaCount", response.titles.size)
            .put("emptyResponseAllowedForNeutralHangulQuery", true)
            .put("sourceBoundaryPayloadVerified", true)
            .put("lastHangulPresentAtActualSourceBoundary", true)
            .put("actualResultTitleOrImageRecorded", false)
        progress.observe("SOURCE_BOUNDARY_VERIFIED", actualImeEditor(), recorder)

        // Preserve the last-composing-syllable/search boundary above before asking the operator
        // to switch languages. Keep MainActivity and all temporary preferences alive until ack.
        fun requireRestoreState(editor: ActualImeEditor?) {
            assertTrue(
                "Restoration must retain the exact neutral editor and caret",
                editor?.safeText == ACTUAL_IME_QUERY && editor.focused &&
                    editor.start == ACTUAL_IME_QUERY.length && editor.end == ACTUAL_IME_QUERY.length,
            )
            assertTrue(
                "Restoration must retain the exact VM editor and search listing",
                viewModel.state.value.toolbarQuery == ACTUAL_IME_QUERY &&
                    (viewModel.state.value.listing as? BrowseSourceViewModel.Listing.Search)?.query == ACTUAL_IME_QUERY,
            )
            recorder.callsAfter(0).forEach {
                assertTrue("Restoration must not change a Source query", it.query == ACTUAL_IME_QUERY)
                assertTrue("Restoration must not change ordered Source filters", it.filters == expectedFilters)
                assertTrue("Restoration must retain positive Source pages", it.page > 0)
            }
        }
        progress.requireNoRestoreAcknowledgement()
        report.put("sourceBoundaryVerifiedBeforeRestorationWasRequested", true)
        progress.observe("REOPENING_NEUTRAL_EDITOR_FOR_IME_RESTORE", actualImeEditor(), recorder)
        openEditor(progress.deadline) // Focus only; never clears/replaces the already-neutral query.
        awaitExternal("WAITING_FOR_SAMSUNG_IME_RESTORE_WINDOW") { editor ->
            requireRestoreState(editor)
            samsungImeWindowVisible(imePackage)
        }
        progress.requireNoRestoreAcknowledgement()
        awaitExternal("READY_FOR_ORIGINAL_IME_LANGUAGE_RESTORE") { editor ->
            requireRestoreState(editor)
            val acknowledgement = progress.readRestoreAcknowledgementIfPresent() ?: return@awaitExternal false
            report.put("operatorLanguageRestoreAcknowledgement", acknowledgement)
                .put("operatorAcknowledgementSchemaAndNeutralFieldsVerified", true)
                .put("operatorLanguageRestorationEvidenceOrigin", "host-observed-key-grid-and-language-label")
            true
        }
        requireRestoreState(actualImeEditor())
        report.put("neutralEditorAndSourcePayloadPreservedAcrossOperatorRestore", true)
        progress.observe("ORIGINAL_IME_LANGUAGE_RESTORE_ACKNOWLEDGED", actualImeEditor(), recorder)
    }

    private fun requireActualImeIdle() {
        val manager = Injekt.get<TranslationManager>()
        val downloads = Injekt.get<DownloadManager>()
        val service = Injekt.get<TranslationEngineService>()
        runBlocking { withTimeout(10_000) { manager.ensureRestored() } }
        assertTrue("Actual IME audit requires an empty translation queue", manager.queueState.value.isEmpty())
        assertTrue(
            "Actual IME audit requires idle downloads",
            downloads.queueState.value.isEmpty() && !downloads.isRunning,
        )
        assertEquals("Actual IME audit must not overlap translation", 0, TranslationBusy.active.value)
        assertFalse("Actual IME audit must not overlap native warmup", service.loading.value)
        assertFalse("Actual IME audit must start and remain cold", service.warm.value)
    }

    private fun awaitAuditBackground() = runBlocking {
        withTimeout(10_000) {
            withContext(Dispatchers.Main.immediate) {
                ProcessLifecycleOwner.get().lifecycle.currentStateFlow.first {
                    !it.isAtLeast(Lifecycle.State.STARTED)
                }
            }
        }
    }

    private fun selectedSamsungImePackage(): String {
        val value = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        val component = value?.let(ComponentName::unflattenFromString)
        assertTrue(
            "Select a Samsung IME before starting this opted-in mode",
            component != null && component.packageName in SAMSUNG_IME_PACKAGES,
        )
        return checkNotNull(component).packageName
    }

    /** Package/type metadata only; no IME text, learned suggestions or unrelated application text. */
    private fun samsungImeWindowVisible(expectedPackage: String? = null): Boolean {
        var visible = false
        val windows = instrumentation.uiAutomation.windows
        try {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val root = window.root ?: continue
                try {
                    val packageName = root.packageName?.toString()
                    val packageMatches = if (expectedPackage == null) {
                        packageName != null && packageName in SAMSUNG_IME_PACKAGES
                    } else {
                        packageName == expectedPackage
                    }
                    if (packageMatches && root.isVisibleToUser) visible = true
                } finally {
                    root.recycle()
                }
            }
        } finally {
            windows.forEach { it.recycle() }
        }
        return visible
    }

    private data class ActualImeEditor(
        val safeText: String?,
        val length: Int,
        val start: Int,
        val end: Int,
        val focused: Boolean,
        val boundsInScreen: List<Int>,
    )

    private fun actualImeEditor(): ActualImeEditor? {
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        var ownForeground = 0
        var otherForeground = false
        val windows = automation.windows
        try {
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
                    (!window.isActive && !window.isFocused)
                ) {
                    continue
                }
                val root = window.root ?: continue
                try {
                    if (root.packageName?.toString() == AUDIT_PACKAGE) ownForeground++ else otherForeground = true
                } finally {
                    root.recycle()
                }
            }
        } finally {
            windows.forEach { it.recycle() }
        }
        assertTrue(
            "Only the audit application may be foreground during external input",
            !otherForeground && ownForeground == 1,
        )
        val node = findNode { it.isEditable && it.isVisibleToUser && it.isEnabled } ?: return null
        try {
            val text = node.text?.toString().orEmpty()
            val bounds = Rect().also(node::getBoundsInScreen)
            return ActualImeEditor(
                safeText = text.takeIf { it in ACTUAL_IME_NEUTRAL_TEXTS },
                length = text.length,
                start = node.textSelectionStart,
                end = node.textSelectionEnd,
                focused = node.isFocused,
                boundsInScreen = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
            )
        } finally {
            node.recycle()
        }
    }

    /** Only the known neutral app recommendation is inspected; no result rows or IME contents. */
    private fun japaneseSuggestionBounds(): List<Int>? {
        val node = findNode { it.isVisibleToUser && isSuggestionLabel(it, JAPANESE) } ?: return null
        try {
            val bounds = Rect().also(node::getBoundsInScreen)
            return if (bounds.isEmpty) null else listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)
        } finally {
            node.recycle()
        }
    }

    /** Successful runs retain two test reports plus one validated host acknowledgement. */
    private inner class ActualImeProgress(private val floating: Boolean) {
        val directory: File
        private val atomic: AtomicFile
        private var started = 0L
        var deadline = 0L
            private set
        private var lastSignature: String? = null
        private var lastWrite = 0L
        private var lastPhase: String? = null

        init {
            val parent = checkNotNull(context.getExternalFilesDir(null)).canonicalFile
            directory = File(parent, "hitomi-actual-samsung-ime-${UUID.randomUUID()}")
            check(directory.mkdir() && directory.canonicalFile.parentFile == parent)
            atomic = AtomicFile(File(directory, "progress.json"))
            requireNoRestoreAcknowledgement()
            println("ACTUAL_SAMSUNG_HITOMI_IME_DIRECTORY=${directory.absolutePath}")
            observe("SETUP", null, null)
        }

        fun beginWaiting() {
            check(started == 0L)
            started = SystemClock.elapsedRealtime()
            deadline = started + ACTUAL_IME_TIMEOUT_MS
        }

        fun observe(phase: String, editor: ActualImeEditor?, recorder: RecordingSource?, passed: Boolean? = null) {
            val now = SystemClock.elapsedRealtime()
            val count = recorder?.startedCount() ?: 0
            val completed = recorder?.completedCount() ?: 0
            val focusedEditorBounds = editor?.takeIf { it.focused }?.boundsInScreen
            val suggestionBounds = japaneseSuggestionBounds()
            val imeVisible = samsungImeWindowVisible()
            val signature = "$phase|${editor?.safeText}|${editor?.length}|${editor?.start}|${editor?.end}|" +
                "$count|$completed|$passed|$focusedEditorBounds|$suggestionBounds|$imeVisible"
            if (signature == lastSignature && now - lastWrite < 1_000) return
            val value = JSONObject().put("phase", phase).put("floating", floating)
                .put("expectedFinalQuery", ACTUAL_IME_QUERY)
                .put("neutralEditorText", editor?.safeText ?: JSONObject.NULL)
                .put("unexpectedEditorTextRedacted", editor != null && editor.safeText == null)
                .put("editorLength", editor?.length ?: JSONObject.NULL)
                .put("selectionStart", editor?.start ?: JSONObject.NULL)
                .put("selectionEnd", editor?.end ?: JSONObject.NULL)
                .put("focusedEditorBounds", focusedEditorBounds?.let { JSONArray(it) } ?: JSONObject.NULL)
                .put("japaneseSuggestionBounds", suggestionBounds?.let { JSONArray(it) } ?: JSONObject.NULL)
                .put("samsungImeWindowVisible", imeVisible)
                .put("operatorRestoreAcknowledgementFile", OPERATOR_RESTORE_ACK)
                .put("operatorLanguageLabelVerifiedByTest", false)
                .put("sourceCallsStarted", count).put("sourceCallsCompleted", completed)
                .put("elapsedWaitingMs", if (started == 0L) 0 else now - started)
                .put(
                    "remainingWaitingMs",
                    if (started ==
                        0L
                    ) {
                        ACTUAL_IME_TIMEOUT_MS
                    } else {
                        (deadline - now).coerceAtLeast(0)
                    },
                )
                .put("passed", passed ?: JSONObject.NULL)
            val stream = atomic.startWrite()
            try {
                stream.write(value.toString(2).toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (error: Throwable) {
                atomic.failWrite(stream)
                throw error
            }
            lastSignature = signature
            lastWrite = now
            if (lastPhase != phase) {
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString(
                            "stream",
                            "ACTUAL_SAMSUNG_HITOMI_IME_READY=" + JSONObject()
                                .put("phase", phase).put("floating", floating)
                                .put("progress", File(directory, "progress.json").absolutePath),
                        )
                    },
                )
                lastPhase = phase
            }
        }

        fun finish(passed: Boolean, recorder: RecordingSource?) {
            observe(if (passed) "PASSED" else "FAILED", null, recorder, passed)
        }

        private fun restoreAcknowledgementFile(): File {
            check(directory.isDirectory && directory.canonicalFile == directory.absoluteFile) {
                "Operator acknowledgement directory must remain the owned canonical directory"
            }
            return File(directory, OPERATOR_RESTORE_ACK).also { file ->
                check(file.canonicalFile == file.absoluteFile && file.canonicalFile.parentFile == directory) {
                    "Operator acknowledgement must remain a direct canonical child"
                }
            }
        }

        fun requireNoRestoreAcknowledgement() {
            check(!restoreAcknowledgementFile().exists()) {
                "Operator acknowledgement must not predate the restore phase"
            }
        }

        fun readRestoreAcknowledgementIfPresent(): JSONObject? {
            val file = restoreAcknowledgementFile()
            if (!file.exists()) return null
            try {
                check(file.isFile && file.length() in 1..MAX_OPERATOR_ACK_BYTES.toLong())
                check(directory.list()?.toSet() == setOf("progress.json", OPERATOR_RESTORE_ACK))
                val bytes = ByteArray(MAX_OPERATOR_ACK_BYTES + 1)
                val size = file.inputStream().use { input ->
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = input.read(bytes, offset, bytes.size - offset)
                        if (count < 0) break
                        offset += count
                    }
                    offset
                }
                check(size in 1..MAX_OPERATOR_ACK_BYTES)
                val text = String(bytes, 0, size, Charsets.UTF_8)
                Json.parseToJsonElement(text) // Strict JSON syntax, unlike Android's lenient tokener.
                val tokenizer = JSONTokener(text)
                val value = tokenizer.nextValue() as? JSONObject ?: error("Expected acknowledgement object")
                check(tokenizer.nextClean() == '\u0000')
                check(value.keys().asSequence().toSet() == OPERATOR_ACK_KEYS)
                check(value.opt("schema") == 1)
                check(value.opt("originalLanguage") == "English")
                check(value.opt("observedRestoredLanguage") == "English")
                check(value.opt("actualSourceBoundaryVerifiedBeforeLanguageSwitch") == true)
                val digest = value.opt("keyGridSha256") as? String ?: error("Expected key-grid digest")
                check(digest.matches(Regex("[a-fA-F0-9]{64}")))
                // All allowed keys/values have no colons; another colon would be a duplicate key
                // or nested content hidden by the object's last-value-wins parsing.
                check(text.count { it == ':' } == OPERATOR_ACK_KEYS.size)
                check(SystemClock.elapsedRealtime() < deadline)
                // Rebuild only validated neutral fields; never retain an unexpected raw ack.
                return JSONObject().put("schema", 1).put("originalLanguage", "English")
                    .put("observedRestoredLanguage", "English").put("keyGridSha256", digest.lowercase())
                    .put("actualSourceBoundaryVerifiedBeforeLanguageSwitch", true)
            } catch (_: Exception) {
                throw AssertionError("Operator restore acknowledgement failed neutral schema validation")
            }
        }
    }

    private fun verifySearchSubmission(
        floating: Boolean,
        viewModel: BrowseSourceViewModel,
        recorder: RecordingSource,
        expectedFilters: List<FilterSnapshot>,
        input: String,
        caret: Int,
        expectedQuery: String,
        expectedCaret: Int,
        label: String,
        report: JSONArray,
        supersedeRequests: Boolean,
    ) {
        val observation = JSONObject().put("case", label).put("sourceBoundaryPayloadVerified", false)
            .put("actualSearchResultRendered", false)
        report.put(observation)
        val previousListing = viewModel.state.value.listing
        val beforeCalls = recorder.startedCount()
        openEditor()
        if (supersedeRequests) {
            // Live latency is uncontrolled: this checks final usability, not forced response ordering.
            setText("artist:na type:manga")
            select("artist:na".length)
            SystemClock.sleep(300)
            setText("language:ko type:manga")
            select("language:ko".length)
            SystemClock.sleep(300)
        }
        setText(input)
        select(caret)
        awaitSuggestion(JAPANESE)
        withNode("Japanese suggestion row", { isSuggestionLabel(it, JAPANESE) }) { touch(it) }
        assertEditor(expectedQuery, expectedCaret)
        waitUntil("Suggestion did not update the actual screen query") {
            viewModel.state.value.toolbarQuery == expectedQuery
        }
        assertEquals(
            "Choosing a suggestion must retain the previous listing",
            previousListing,
            viewModel.state.value.listing,
        )
        assertAbsent("Chosen suggestion list must close") { isSuggestionLabel(it, JAPANESE) }
        if (floating) {
            SystemClock.sleep(3_250)
            assertEditor(expectedQuery, expectedCaret)
            observation.put("nonemptyEditorSurvivedIdleCollapseDeadline", true)
        }
        val previousQuery = (previousListing as? BrowseSourceViewModel.Listing.Search)?.query
        // A previous listing may legitimately auto-prefetch while the next query is edited.
        // Such a call is not a submission of the current editor. The first case has no previous
        // search, so it still requires zero Source.getSearchManga calls before the first submit.
        recorder.callsAfter(beforeCalls).forEach {
            assertEquals("Selection unexpectedly submitted a different source query", previousQuery, it.query)
        }
        observation.put("selectionDidNotSubmitSourceSearch", true)
            .put("previousListingCallsWhileEditing", recorder.startedCount() - beforeCalls)
            .put("currentTokenAndCaretPreserved", true)
        val submitBoundary = recorder.startedCount()
        withNode("Real search editor", { it.isEditable }) { node ->
            if (!node.isFocused) {
                assertTrue("Editor must accept focus", node.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
            }
            assertTrue(
                "Real Compose editor did not expose its Search IME action",
                node.actionList.any {
                    it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                },
            )
            assertTrue(
                "Editor rejected the accessibility IME action",
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id),
            )
        }
        waitUntil("Editor action did not submit the actual screen query") {
            (viewModel.state.value.listing as? BrowseSourceViewModel.Listing.Search)?.query == expectedQuery
        }
        waitUntil("Actual PagingSource did not call Source.getSearchManga", timeoutMs = 90_000) {
            recorder.callsAfter(submitBoundary).any { it.query == expectedQuery }
        }
        val firstCall = recorder.callsAfter(submitBoundary).first { it.query == expectedQuery }
        assertEquals("The first source request must start at page 1", 1, firstCall.page)
        assertEquals("Source must receive the exact completed query", expectedQuery, firstCall.query)
        assertEquals("Source must receive the entire ordered filter state", expectedFilters, firstCall.filters)
        observation.put("sourceBoundaryPayloadVerified", true)
            .put("sourcePage", firstCall.page).put("sourceQuery", firstCall.query)
            .put("sourceFilters", filtersReport(firstCall.filters))
        waitUntil("The real Hitomi source did not finish its first search response", timeoutMs = 90_000) {
            recorder.completed(firstCall.sequence)
        }
        val response = recorder.response(firstCall.sequence)
        assertEquals(
            "Actual source call failed; see only its class in the sourceCalls report",
            "success",
            response.outcome,
        )
        observation.put("sourceResponseMangaCount", response.titles.size)
        assertTrue("Live query must return results to prove the actual list renders", response.titles.isNotEmpty())
        waitUntil("Actual browse list did not render this source response", timeoutMs = 90_000) {
            findNode { node ->
                !node.isEditable && node.text?.toString()?.lineSequence()?.any { it in response.titles } == true
            }?.also { it.recycle() } != null
        }
        // Any auto-prefetch calls must carry the same query and full filter state. No fixed call count
        // is asserted: production paging can legitimately fetch another page to fill the viewport.
        val callsAfterSubmit = recorder.callsAfter(submitBoundary)
        callsAfterSubmit.forEach {
            assertTrue(
                "Unrelated source query appeared during submission",
                it.query == expectedQuery || it.query == previousQuery,
            )
        }
        callsAfterSubmit.filter { it.query == expectedQuery }.forEach {
            assertEquals(expectedQuery, it.query)
            assertEquals(expectedFilters, it.filters)
            assertTrue("Source page must be positive", it.page > 0)
        }
        observation.put("actualSearchResultRendered", true).put("directOracleSearchRequests", 0)
    }
    private fun openEditor(sharedDeadline: Long? = null) {
        recordEditorState("openEditor-before")
        val deadline = minOf(SystemClock.elapsedRealtime() + 15_000, sharedDeadline ?: Long.MAX_VALUE)
        try {
            val existing = findNode { it.isEditable }
            if (existing != null) {
                existing.recycle()
            } else {
                val search = context.stringResource(MR.strings.action_search)
                var control: AccessibilityNodeInfo? = null
                waitUntil(
                    "Missing Search control",
                    timeoutMs = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0),
                ) {
                    findNode { it.contentDescription?.toString() == search }.also { control = it } != null
                }
                try {
                    touch(checkNotNull(control))
                } finally {
                    control?.recycle()
                }
            }
            // The keyboard inset can move the floating field after the first focus. Keep no
            // node or coordinates from an earlier observation, and never touch it twice merely
            // because it already existed when opening the editor.
            val prepared = awaitStableEditor(deadline, requireFocus = false)
            val neededFocusTouch = !prepared.isFocused
            try {
                if (neededFocusTouch) touch(prepared)
            } finally {
                prepared.recycle()
            }
            if (neededFocusTouch) {
                awaitStableEditor(deadline, requireFocus = true).recycle()
            }
            recordEditorState("openEditor-after")
        } catch (error: Throwable) {
            recordEditorState("openEditor-failed")
            throw error
        }
    }

    /** The returned node comes from the latest poll, after stable geometry and focus observations. */
    private fun awaitStableEditor(deadline: Long, requireFocus: Boolean): AccessibilityNodeInfo {
        var stableKey: String? = null
        var stableSince = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            val node = findNode {
                it.isEditable && it.isVisibleToUser && it.isEnabled && (!requireFocus || it.isFocused)
            }
            if (node == null) {
                stableKey = null
            } else {
                val bounds = Rect().also(node::getBoundsInScreen)
                val key = "${node.windowId}:${bounds.flattenToString()}:${node.isFocused}"
                val now = SystemClock.elapsedRealtime()
                if (bounds.isEmpty) {
                    stableKey = null
                } else if (key != stableKey) {
                    stableKey = key
                    stableSince = now
                } else if (now - stableSince >= 250) {
                    return node
                }
                node.recycle()
            }
            SystemClock.sleep(75)
        }
        throw AssertionError("Source editor was not visible, enabled, and stable for 250ms; requireFocus=$requireFocus")
    }

    private fun verifySuggestionScrollDoesNotCollapse() {
        setText("language:")
        var scrolled = false
        waitUntil("Remote language suggestions did not provide a scrollable list", timeoutMs = 45_000) {
            val label = findNode { it.text?.toString()?.startsWith("language:") == true && !it.isEditable }
                ?: return@waitUntil false
            var current: AccessibilityNodeInfo? = label
            try {
                while (current != null) {
                    val candidate = current
                    if (candidate.isScrollable && candidate.actionList.any {
                            it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        }
                    ) {
                        scrolled = candidate.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        return@waitUntil scrolled
                    }
                    current = candidate.parent
                    candidate.recycle()
                }
                false
            } finally {
                current?.recycle()
            }
        }
        assertTrue(scrolled)
        SystemClock.sleep(500)
        assertEditor("language:", "language:".length)
    }

    /** Observe the owner already populated by normal navigation; never create a test ViewModel. */
    private fun existingBrowseModel(activity: MainActivity): BrowseSourceViewModel? {
        val navigator = MainActivity::class.java.getDeclaredField("navigator").apply { isAccessible = true }
            .get(activity) as? Navigator ?: return null
        val screen = navigator.lastItem as? BrowseSourceScreen ?: return null
        val owner = runCatching {
            ScreenLifecycleStore.get<AndroidScreenLifecycleOwner>(screen) {
                error("Actual BrowseSourceScreen has not created its lifecycle owner")
            }
        }.getOrNull() ?: return null
        val key = "androidx.lifecycle.ViewModelProvider.DefaultKey:${BrowseSourceViewModel::class.java.name}"
        return owner.viewModelStore[key] as? BrowseSourceViewModel
    }

    private fun setText(text: String) {
        neutralEditorTexts += text
        recordEditorState("setText-before", expectedText = text, expectedCaret = text.length)
        try {
            withNode("Source search editor", { it.isEditable }) { node ->
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
                recordEditorState(
                    "setText-action-returned",
                    expectedText = text,
                    expectedCaret = text.length,
                    actionAccepted = accepted,
                )
                assertTrue("Editor rejected text edit", accepted)
            }
            assertEditor(text, text.length)
        } catch (error: Throwable) {
            recordEditorState("setText-failed", expectedText = text, expectedCaret = text.length)
            throw error
        }
    }

    private fun select(caret: Int) {
        recordEditorState("select-before", expectedCaret = caret)
        try {
            withNode("Source search editor", { it.isEditable }) { node ->
                val arguments = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret)
                }
                val accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)
                recordEditorState("select-action-returned", expectedCaret = caret, actionAccepted = accepted)
                assertTrue("Editor rejected caret edit", accepted)
            }
            waitUntil("Editor caret did not move") {
                findNode { it.isEditable && it.textSelectionStart == caret && it.textSelectionEnd == caret }
                    ?.also { it.recycle() } != null
            }
            recordEditorState("select-asserted", expectedCaret = caret)
        } catch (error: Throwable) {
            recordEditorState("select-failed", expectedCaret = caret)
            throw error
        }
    }

    private fun awaitSuggestion(label: String) {
        waitUntil("Remote Japanese recommendation did not appear", timeoutMs = 45_000) {
            findNode { isSuggestionLabel(it, label) }?.also { it.recycle() } != null
        }
    }

    private fun isSuggestionLabel(node: AccessibilityNodeInfo, label: String): Boolean {
        if (node.isEditable) return false
        val text = node.text?.toString() ?: return false
        // A merged Compose TextButton may expose its result count after the tag label.
        return text == label || text.startsWith("$label\n") || text.startsWith("$label, ") || text.startsWith("$label ")
    }

    private fun assertEditor(text: String, caret: Int) {
        neutralEditorTexts += text
        try {
            waitUntil("Real editor text/caret differs from expected token replacement") {
                findNode {
                    it.isEditable && it.text?.toString() == text &&
                        it.textSelectionStart == caret && it.textSelectionEnd == caret
                }?.also { it.recycle() } != null
            }
            recordEditorState("assertEditor-passed", expectedText = text, expectedCaret = caret)
        } catch (error: Throwable) {
            recordEditorState("assertEditor-failed", expectedText = text, expectedCaret = caret)
            throw error
        }
    }

    /** Observe only our neutral editor, never titles, result rows, or the rest of the UI tree. */
    private fun recordEditorState(
        stage: String,
        expectedText: String? = null,
        expectedCaret: Int? = null,
        actionAccepted: Boolean? = null,
    ) {
        val report = editorDiagnosticReport ?: return
        val observation = JSONObject().put("stage", stage).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("expectedText", expectedText ?: JSONObject.NULL)
            .put("expectedCaret", expectedCaret ?: JSONObject.NULL)
            .put("actionAccepted", actionAccepted ?: JSONObject.NULL)
        try {
            val editors = editorNodesReport()
            val state = editorDiagnosticViewModel?.state?.value
            observation.put("editors", editors).put("editableNodeCount", editors.length())
                .put("vmToolbarQuery", neutralEditorText(state?.toolbarQuery))
                .put("vmListing", state?.listing?.javaClass?.simpleName ?: JSONObject.NULL)
                // These values are Compose remember locals, not fields exposed by the VM.
                .put("rememberedSelectionDirectlyObserved", false)
                .put("rememberedExpandedStateDirectlyObserved", false)
            if (report.optBoolean("floating")) {
                var visibleEditor = false
                for (index in 0 until editors.length()) {
                    if (editors.getJSONObject(index).optBoolean("visible")) visibleEditor = true
                }
                observation.put("floatingExpandedInferredFromVisibleEditor", visibleEditor)
            }
        } catch (error: Throwable) {
            // Observation must not replace the original product or fixture failure.
            observation.put("observationFailureClass", error.javaClass.name)
        }
        report.getJSONArray("editorObservations").put(observation)
    }

    private fun neutralEditorText(value: String?): Any {
        if (value == null) return JSONObject.NULL
        // Permit only values/fragments generated by this fixture. An unexpected editor or VM
        // value is useful as a length diagnostic, without recording a possible private query.
        val permitted = if (strictNeutralEditorText) {
            value in ACTUAL_IME_NEUTRAL_TEXTS
        } else {
            neutralEditorTexts.any {
                value in
                    it
            }
        }
        return if (permitted) {
            value
        } else {
            "[redacted-unexpected-text,length=${value.length}]"
        }
    }

    private fun editorNodesReport(): JSONArray {
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        val output = JSONArray()
        val seen = hashSetOf<String>()
        fun visit(node: AccessibilityNodeInfo) {
            if (!node.refresh()) return
            if (node.isEditable && node.packageName?.toString() == context.packageName) {
                val bounds = Rect().also(node::getBoundsInScreen)
                val identity = "${node.windowId}:${bounds.flattenToString()}:${node.className}:" +
                    "${node.textSelectionStart}:${node.textSelectionEnd}:${node.isFocused}:${node.isVisibleToUser}"
                if (seen.add(identity)) {
                    val text = node.text?.toString()
                    output.put(
                        JSONObject().put("class", node.className?.toString() ?: JSONObject.NULL)
                            .put("text", neutralEditorText(text)).put("textLength", text?.length ?: JSONObject.NULL)
                            .put("selectionStart", node.textSelectionStart).put("selectionEnd", node.textSelectionEnd)
                            .put("focused", node.isFocused).put("accessibilityFocused", node.isAccessibilityFocused)
                            .put("enabled", node.isEnabled).put("visible", node.isVisibleToUser)
                            .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                            .put("windowId", node.windowId),
                    )
                }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    visit(child)
                } finally {
                    child.recycle()
                }
            }
        }
        val roots = mutableListOf<AccessibilityNodeInfo>()
        automation.rootInActiveWindow?.let(roots::add)
        for (window in automation.windows) {
            window.root?.let(roots::add)
            window.recycle()
        }
        try {
            roots.filter { it.packageName?.toString() == context.packageName }.forEach(::visit)
        } finally {
            roots.forEach(AccessibilityNodeInfo::recycle)
        }
        return output
    }

    private fun assertAbsent(message: String, matches: (AccessibilityNodeInfo) -> Boolean) {
        waitUntil(message) { findNode(matches)?.also { it.recycle() } == null }
    }

    private fun withNode(
        label: String,
        matches: (AccessibilityNodeInfo) -> Boolean,
        action: (AccessibilityNodeInfo) -> Unit,
    ) {
        var node: AccessibilityNodeInfo? = null
        waitUntil("Missing $label") { findNode(matches).also { node = it } != null }
        try {
            action(checkNotNull(node))
        } finally {
            node?.recycle()
        }
    }

    /** A real touch reaches Compose even when a labelled wrapper lacks a semantic click action. */
    private fun touch(node: AccessibilityNodeInfo) {
        val bounds = Rect().also(node::getBoundsInScreen)
        assertFalse("Control has no visible touch bounds", bounds.isEmpty)
        assertTrue("Control is not enabled", node.isEnabled)
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                action,
                bounds.exactCenterX(),
                bounds.exactCenterY(),
                0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try {
                assertTrue("Platform rejected UI touch", instrumentation.uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(40)
        }
    }

    private fun findNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val automation = instrumentation.uiAutomation
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (!node.refresh()) return null
            if (node.isVisibleToUser && matches(node)) return AccessibilityNodeInfo.obtain(node)
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    visit(child)?.let { return it }
                } finally {
                    child.recycle()
                }
            }
            return null
        }
        val roots = mutableListOf<AccessibilityNodeInfo>()
        automation.rootInActiveWindow?.let(roots::add)
        for (window in automation.windows) {
            window.root?.let(roots::add)
            window.recycle()
        }
        try {
            for (root in roots) {
                if (root.packageName?.toString() == context.packageName) visit(root)?.let { return it }
            }
            return null
        } finally {
            roots.forEach(AccessibilityNodeInfo::recycle)
        }
    }

    private fun waitUntil(message: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(75)
        }
        throw AssertionError(message)
    }

    /** Only one factory entry changes; the production repository singleton is never replaced. */
    private class RemoteMangaFactoryLease(
        private val originalRepository: SourceRepository,
        private val recordingRepository: SourceRepository,
    ) {
        private val registrar = Injekt.registrar
        private val key = fullType<GetRemoteManga>().type

        // Injekt 1.16.1 has no factory getter. Snapshot just this typed entry, never the registry.
        @Suppress("UNCHECKED_CAST")
        private val factories = registrar.javaClass.getDeclaredField("factories").apply { isAccessible = true }
            .get(registrar) as ConcurrentHashMap<Type, () -> Any>
        private val original = checkNotNull(factories[key]) { "Production GetRemoteManga factory is missing" }
        private val replacement: () -> GetRemoteManga = { GetRemoteManga(recordingRepository) }
        private var installed = false

        fun install() {
            assertSame(
                "Production interactor repository differs before the audit",
                originalRepository,
                repositoryOf(Injekt.get<GetRemoteManga>()),
            )
            // This is the single factory entry used by addFactory in pinned Injekt 1.16.1.
            // CAS retains factory (not singleton) behavior without a check/put ownership race.
            assertTrue(
                "GetRemoteManga factory changed before audit installation",
                factories.replace(key, original, replacement),
            )
            installed = true
            assertSame("Recording factory identity differs from the installed entry", replacement, factories[key])
            assertSame(
                "Recording factory does not create the expected interactor",
                recordingRepository,
                repositoryOf(Injekt.get<GetRemoteManga>()),
            )
            assertSame(originalRepository, Injekt.get<SourceRepository>())
        }

        fun restoreAndVerify() {
            if (!installed) return
            // Fail closed if another owner changed this entry; never overwrite their registration.
            assertTrue(
                "Cannot restore a factory entry changed by another owner",
                factories.replace(key, replacement, original),
            )
            installed = false
            assertSame("Original factory closure identity was not restored", original, factories[key])
            assertSame(
                "Original factory no longer uses the original repository",
                originalRepository,
                repositoryOf(Injekt.get<GetRemoteManga>()),
            )
            assertSame(originalRepository, Injekt.get<SourceRepository>())
        }
    }

    private data class FilterSnapshot(
        val className: String,
        val name: String,
        val state: String,
        val values: List<String>,
        val children: List<FilterSnapshot>,
    )

    private data class SearchCall(
        val sequence: Int,
        val page: Int,
        val query: String,
        val filters: List<FilterSnapshot>,
    )

    private data class SearchResponse(
        val outcome: String,
        val titles: Set<String> = emptySet(),
        val errorClass: String? = null,
    )

    /** Keeps gallery titles only in process memory. Reports contain neutral input and counts only. */
    private class RecordingSource(
        private val actual: HitomiSource,
        private val strictAllowedQueries: Set<String>? = null,
        private val strictExpectedFilters: List<FilterSnapshot>? = null,
    ) : Source by actual {
        private val monitor = Any()
        private val calls = mutableListOf<SearchCall>()
        private val responses = mutableMapOf<Int, SearchResponse>()
        private var inFlight = 0

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            val copiedFilters = snapshotFilters(filters)
            val permittedQuery = strictAllowedQueries == null || query in strictAllowedQueries
            val permittedFilters = strictExpectedFilters == null || copiedFilters == strictExpectedFilters
            val call = synchronized(monitor) {
                SearchCall(
                    calls.size + 1,
                    page,
                    if (permittedQuery) query else "[redacted-unexpected-query]",
                    if (permittedFilters) copiedFilters else emptyList(),
                ).also {
                    calls += it
                    inFlight++
                }
            }
            try {
                // Reject unexpected operator input before forwarding it or exposing it in a report.
                // The ordinary accessibility-driven fixture retains its original unrestricted path.
                check(permittedQuery) { "External IME query is outside the neutral allowlist" }
                check(permittedFilters) { "External IME ordered filters differ from the captured neutral defaults" }
                // Forward the same arguments to the actual source. No fake network or page result.
                return actual.getSearchManga(page, query, filters).also { pageResult ->
                    synchronized(monitor) {
                        responses[call.sequence] = SearchResponse("success", pageResult.mangas.map { it.title }.toSet())
                    }
                }
            } catch (error: Throwable) {
                synchronized(monitor) {
                    val outcome = if (error is CancellationException) "cancelled" else "failure"
                    responses[call.sequence] = SearchResponse(outcome, errorClass = error.javaClass.name)
                }
                if (strictAllowedQueries != null && error !is CancellationException) {
                    // A network/parser exception can contain a full URL or private metadata. The
                    // response record retains its class; no raw message/cause escapes this mode.
                    throw IllegalStateException("Neutral Source call failed; inspect the sanitized response class")
                }
                throw error
            } finally {
                synchronized(monitor) { inFlight-- }
            }
        }

        fun startedCount(): Int = synchronized(monitor) { calls.size }
        fun callsAfter(count: Int): List<SearchCall> = synchronized(monitor) { calls.drop(count) }
        fun completed(sequence: Int): Boolean = synchronized(monitor) { sequence in responses }
        fun completedCount(): Int = synchronized(monitor) { responses.size }
        fun response(sequence: Int): SearchResponse = synchronized(monitor) {
            checkNotNull(responses[sequence]) { "Source response has not completed" }
        }

        fun awaitDrained() {
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (SystemClock.elapsedRealtime() < deadline) {
                if (synchronized(monitor) { inFlight == 0 }) return
                SystemClock.sleep(75)
            }
            throw AssertionError("Actual source calls did not drain before DI and preference restoration")
        }

        fun report(): JSONArray = synchronized(monitor) {
            JSONArray().also { output ->
                calls.forEach { call ->
                    val response = responses[call.sequence]
                    output.put(
                        JSONObject()
                            .put("sequence", call.sequence).put("page", call.page).put("query", call.query)
                            .put("filters", filtersReport(call.filters))
                            .put("outcome", response?.outcome ?: "running")
                            .put("mangaCount", response?.titles?.size ?: JSONObject.NULL)
                            .put("errorClass", response?.errorClass ?: JSONObject.NULL),
                    )
                }
            }
        }
    }

    private class SavedPreferences {
        private val restores = linkedMapOf<String, () -> Unit>()
        private val verifies = mutableListOf<() -> Unit>()

        fun <T> preserve(preference: Preference<T>) {
            if (preference.key() in restores) return
            val existed = preference.isSet()
            val value = preference.get()
            restores[preference.key()] = { if (existed) preference.set(value) else preference.delete() }
            verifies += {
                assertTrue("Preference presence was not restored: ${preference.key()}", preference.isSet() == existed)
                assertTrue("Preference value was not restored: ${preference.key()}", preference.get() == value)
            }
        }

        fun <T> set(preference: Preference<T>, value: T) {
            preserve(preference)
            preference.set(value)
        }

        fun restoreAndVerify() {
            val failures = mutableListOf<Throwable>()
            (restores.values.toList().asReversed() + verifies).forEach { operation ->
                try {
                    operation()
                } catch (error: Throwable) {
                    failures += error
                }
            }
            if (failures.isNotEmpty()) {
                throw AssertionError("Limited browse preferences were not restored").also { aggregate ->
                    failures.forEach(aggregate::addSuppressed)
                }
            }
        }
    }

    private companion object {
        const val JAPANESE = "language:japanese"
        const val AUDIT_PACKAGE = "app.yomitori.yakuyomi.audit"
        const val ACTUAL_IME_QUERY = "language:japanese 한글"
        const val ACTUAL_IME_TIMEOUT_MS = 180_000L
        const val OPERATOR_RESTORE_ACK = "operator-restore.json"
        const val MAX_OPERATOR_ACK_BYTES = 4_096
        val OPERATOR_ACK_KEYS = setOf(
            "schema",
            "originalLanguage",
            "observedRestoredLanguage",
            "keyGridSha256",
            "actualSourceBoundaryVerifiedBeforeLanguageSwitch",
        )
        val SAMSUNG_IME_PACKAGES = setOf("com.samsung.android.honeyboard", "com.sec.android.inputmethod")
        val ACTUAL_IME_NEUTRAL_TEXTS = setOf(
            "",
            "j",
            "ja",
            "jap",
            JAPANESE,
            "$JAPANESE ",
            "$JAPANESE ㅎ",
            "$JAPANESE 하",
            "$JAPANESE 한",
            "$JAPANESE 한ㄱ",
            "$JAPANESE 한그",
            ACTUAL_IME_QUERY,
        )

        private fun repositoryOf(interactor: GetRemoteManga): SourceRepository = GetRemoteManga::class.java
            .getDeclaredField("repository").apply { isAccessible = true }.get(interactor) as SourceRepository

        /** Copy mutable Filter objects before forwarding, including order, subclass, and all state. */
        private fun snapshotFilters(filters: List<Filter<*>>): List<FilterSnapshot> = filters.map { filter ->
            val children = if (filter is Filter.Group<*>) {
                snapshotFilters(
                    filter.state.map {
                        check(it is Filter<*>) { "Unsupported non-filter Group state in source audit" }
                        it
                    },
                )
            } else {
                emptyList()
            }
            val state = if (filter is Filter.Group<*>) "group" else snapshotScalar(filter.state)
            val values = when (filter) {
                is Filter.Select<*> -> filter.values.map(::snapshotScalar)
                is Filter.Sort -> filter.values.map(::snapshotScalar)
                else -> emptyList()
            }
            FilterSnapshot(filter.javaClass.name, filter.name, state, values, children)
        }

        private fun snapshotScalar(value: Any?): String = when (value) {
            null -> "null"
            is String -> "String:${JSONObject.quote(value)}"
            is Boolean -> "Boolean:$value"
            is Int -> "Int:$value"
            is Long -> "Long:$value"
            is Filter.Sort.Selection -> "Sort.Selection:index=${value.index},ascending=${value.ascending}"
            else -> error("Unsupported filter state class: ${value.javaClass.name}")
        }

        private fun filtersReport(filters: List<FilterSnapshot>): JSONArray = JSONArray().also { output ->
            filters.forEach { filter ->
                output.put(
                    JSONObject().put("class", filter.className).put("name", filter.name)
                        .put("state", filter.state).put("values", JSONArray(filter.values))
                        .put("children", filtersReport(filter.children)),
                )
            }
        }
    }
}
