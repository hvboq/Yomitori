package mihon.source.hitomi

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cafe.adriel.voyager.androidx.AndroidScreenLifecycleOwner
import cafe.adriel.voyager.core.lifecycle.ScreenLifecycleStore
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceScreen
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
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
 */
@Suppress("DEPRECATION", "RestrictedApi")
@RunWith(AndroidJUnit4::class)
class HitomiBrowseScreenIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var editorDiagnosticReport: JSONObject? = null
    private var editorDiagnosticViewModel: BrowseSourceViewModel? = null
    private val neutralEditorTexts = mutableSetOf("")

    @Test
    fun toolbarRemoteSuggestionReplacesCurrentTokenAndAccessibilityImeSubmits() = verifyBrowse(floating = false)

    @Test
    fun floatingRemoteSuggestionStaysExpandedAndAccessibilityImeSubmits() = verifyBrowse(floating = true)

    private fun verifyBrowse(floating: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveHitomiBrowse") == "true")
        // Guard before reading DI, preferences, or the default database.
        assertEquals("Live browse audit requires the isolated target", AUDIT_PACKAGE, context.packageName)
        assertTrue("Accessibility IME action requires Android 11+", Build.VERSION.SDK_INT >= 30)
        val sources = Injekt.get<SourcePreferences>()
        val library = Injekt.get<LibraryPreferences>()
        val base = Injekt.get<BasePreferences>()
        val originalRepository = Injekt.get<SourceRepository>()
        val saved = SavedPreferences()
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
        editorDiagnosticReport = report
        var completed = false
        var failure: Throwable? = null
        var factoryLease: RemoteMangaFactoryLease? = null
        var recordingSource: RecordingSource? = null
        var browseJob: Job? = null
        try {
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
            val recorder = RecordingSource(source).also { recordingSource = it }
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
            cleanup("automationFlagsRestored") {
                automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
            }
            report.put("sourceCalls", recordingSource?.report() ?: JSONArray())
                .put("sourceSearchBoundaryObserved", (recordingSource?.startedCount() ?: 0) > 0)
                .put("passed", completed && cleanupFailures.isEmpty())
            cleanup("reportWritten") {
                val name = if (floating) "floating" else "toolbar"
                val output = File(
                    context.getExternalFilesDir(null),
                    "hitomi-browse-screen/${UUID.randomUUID()}/$name.json",
                )
                check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
                output.writeText(report.toString(2))
                println("HITOMI_BROWSE_SCREEN_REPORT=${output.absolutePath}")
                println(report.toString(2))
            }
            if (cleanupFailures.isNotEmpty()) {
                val cleanupError = AssertionError("Live browse audit cleanup failed")
                cleanupFailures.forEach(cleanupError::addSuppressed)
                if (failure != null) failure.addSuppressed(cleanupError) else throw cleanupError
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
    private fun openEditor() {
        recordEditorState("openEditor-before")
        val deadline = SystemClock.elapsedRealtime() + 15_000
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
        return if (neutralEditorTexts.any { value in it }) {
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
    private class RecordingSource(private val actual: HitomiSource) : Source by actual {
        private val monitor = Any()
        private val calls = mutableListOf<SearchCall>()
        private val responses = mutableMapOf<Int, SearchResponse>()
        private var inFlight = 0

        override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
            val call = synchronized(monitor) {
                SearchCall(calls.size + 1, page, query, snapshotFilters(filters)).also {
                    calls += it
                    inFlight++
                }
            }
            try {
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
                throw error
            } finally {
                synchronized(monitor) { inFlight-- }
            }
        }

        fun startedCount(): Int = synchronized(monitor) { calls.size }
        fun callsAfter(count: Int): List<SearchCall> = synchronized(monitor) { calls.drop(count) }
        fun completed(sequence: Int): Boolean = synchronized(monitor) { sequence in responses }
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
