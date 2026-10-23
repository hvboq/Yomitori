package mihon.source.hitomi

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An explicitly opted-in, isolated audit runner observes the already-open signed release editor.
 * It does not launch an Activity, configure the release, access DI/database/models, set text, or
 * submit a search. The operator prepares one of the fixed neutral queries in the real release UI.
 * Only public accessibility selection/click actions cross the package boundary. This proves
 * accessibility editing, not physical keyboard input, Source calls, or HTTP payloads.
 *
 * Required: signedReleaseSearchProbe=true, probeExpectedQuery, probeSelectionStart/End.
 * Optional pair: probeCandidate=language:japanese and probeExpectedResult. Candidate selection
 * is restricted to the two fixed caret/replacement cases below. A single neutral JSON report is
 * printed to instrumentation stdout; no UI tree, screenshot, user text, or report file is saved.
 */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class SignedReleaseSearchSelectionTest {
    @Test
    fun observeAndSetAlreadyOpenReleaseSelection() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("signedReleaseSearchProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "Signed release probe requires the isolated audit target",
            instrumentation.targetContext.packageName == AUDIT_PACKAGE,
        )
        val request = parseRequest(arguments)
        val report = JSONObject()
            .put("targetPackage", AUDIT_PACKAGE)
            .put("observedPackage", RELEASE_PACKAGE)
            .put("expectedQuery", request.query)
            .put("requestedSelectionStart", request.start)
            .put("requestedSelectionEnd", request.end)
            .put("candidate", request.candidate ?: JSONObject.NULL)
            .put("expectedResult", request.expectedResult ?: JSONObject.NULL)
            .put("editingMethod", "public-accessibility-set-selection")
            .put("candidateMethod", "nearest-clickable-ancestor-action-click")
            .put("activityStarted", false)
            .put("releasePreferencesOrDatabaseAccessed", false)
            .put("textSetByProbe", false)
            .put("searchSubmittedByProbe", false)
            .put("physicalKeyboardVerified", false)
            .put("sourceOrHttpBoundaryObserved", false)
            .put("candidateObserved", false)
            .put("passed", false)
        val observations = JSONArray().also { report.put("observations", it) }
        val automation = instrumentation.uiAutomation
        val originalFlags = automation.serviceInfo.flags
        var failure: Throwable? = null
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            awaitInitialReleaseWindow(automation, report)
            withEditor(automation) { editor ->
                recordEditor(observations, "before-selection", editor, request.query)
                assertNeutralQuery(editor, request.query)
                val alreadySelected = editor.textSelectionStart == request.start &&
                    editor.textSelectionEnd == request.end
                report.put("alreadySelected", alreadySelected)
                    .put("selectionActionAttempted", !alreadySelected)
                    .put("selectionActionAccepted", JSONObject.NULL)
                if (!alreadySelected) {
                    assertTrue(
                        "Release editor does not expose ACTION_SET_SELECTION",
                        editor.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION },
                    )
                    val accepted = editor.performAction(
                        AccessibilityNodeInfo.ACTION_SET_SELECTION,
                        Bundle().apply {
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, request.start)
                            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, request.end)
                        },
                    )
                    report.put("selectionActionAccepted", accepted)
                    assertTrue("Release editor rejected ACTION_SET_SELECTION", accepted)
                }
            }
            withEditor(automation) { editor ->
                recordEditor(observations, "selection-action-returned", editor, request.query)
            }
            awaitEditor(automation, "Release editor did not expose the requested selection") { editor ->
                assertNeutralQuery(editor, request.query)
                recordEditor(observations, "selection-readback", editor, request.query)
                editor.textSelectionStart == request.start && editor.textSelectionEnd == request.end
            }
            report.put("directSelectionReadbackVerified", true)

            if (request.candidate != null) {
                clickCandidate(automation, request, observations, report)
                awaitEditor(automation, "Release suggestion did not replace the neutral current token") { editor ->
                    recordEditor(observations, "replacement-readback", editor, request.expectedResult!!)
                    editor.text?.toString() == request.expectedResult &&
                        editor.textSelectionStart == request.expectedResultCaret &&
                        editor.textSelectionEnd == request.expectedResultCaret
                }
                report.put("replacementAndCaretVerified", true)
            }
            report.put("passed", true)
        } catch (error: Throwable) {
            failure = error
            report.put("failureClass", error.javaClass.name)
            // Diagnostics are limited to the release editor's offsets and fixed neutral matches.
            // If another app took focus, do not inspect it or attempt any further input.
            try {
                withEditor(automation) { recordEditor(observations, "failure-observation", it, request.query) }
            } catch (diagnosticError: Throwable) {
                report.put("failureObservationClass", diagnosticError.javaClass.name)
            }
            throw error
        } finally {
            try {
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
                report.put("automationFlagsRestored", true)
            } catch (restoreError: Throwable) {
                report.put("automationFlagsRestored", false).put("passed", false)
                    .put("flagsRestoreFailureClass", restoreError.javaClass.name)
                if (failure != null) failure.addSuppressed(restoreError) else throw restoreError
            } finally {
                instrumentation.sendStatus(
                    2,
                    Bundle().apply {
                        putString("stream", "SIGNED_RELEASE_SEARCH_SELECTION_REPORT=$report\n")
                    },
                )
            }
        }
    }

    private fun parseRequest(arguments: Bundle): Request {
        val query = arguments.getString("probeExpectedQuery")
            ?: throw AssertionError("An explicit fixed neutral probeExpectedQuery is required")
        assertTrue("probeExpectedQuery must be one of the fixed neutral inputs", query in CASES)
        val fixedCase = CASES.getValue(query)
        val start = arguments.getString("probeSelectionStart")?.toIntOrNull()
            ?: throw AssertionError("An explicit integer probeSelectionStart is required")
        val end = arguments.getString("probeSelectionEnd")?.toIntOrNull()
            ?: throw AssertionError("An explicit integer probeSelectionEnd is required")
        assertTrue(
            "Selection offsets must be ordered and inside the fixed query",
            start in 0..end && end <= query.length,
        )
        val candidate = arguments.getString("probeCandidate")
        val result = arguments.getString("probeExpectedResult")
        assertTrue("Candidate and expected result must be supplied together", (candidate == null) == (result == null))
        if (candidate != null) {
            assertTrue("Only the fixed Japanese candidate is permitted", candidate == JAPANESE)
            assertTrue(
                "Candidate click requires the fixed current-token caret",
                start == fixedCase.caret && end == start,
            )
            assertTrue("Expected result must match the fixed neutral replacement", result == fixedCase.result)
        }
        return Request(query, start, end, candidate, result, fixedCase.resultCaret)
    }

    private fun clickCandidate(
        automation: UiAutomation,
        request: Request,
        observations: JSONArray,
        report: JSONObject,
    ) {
        val started = SystemClock.elapsedRealtime()
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            // Revalidate both the foreground and the actual caret before any click.
            withEditor(automation) { editor ->
                assertNeutralQuery(editor, request.query)
                assertTrue(
                    "Release selection changed before candidate click",
                    editor.textSelectionStart == request.start && editor.textSelectionEnd == request.end,
                )
            }
            val candidate = withReleaseRoot(automation) { root ->
                findNodes(root) {
                    !it.isEditable && it.isVisibleToUser && it.isEnabled &&
                        it.className?.toString() == "android.widget.TextView" && it.text?.toString() == JAPANESE
                }.also { nodes ->
                    if (nodes.size > 1) {
                        nodes.forEach(AccessibilityNodeInfo::recycle)
                        throw AssertionError("Japanese recommendation must be unambiguous in the release window")
                    }
                }.singleOrNull()
            }
            if (candidate != null) {
                report.put("candidateObserved", true)
                    .put("candidateWaitMs", SystemClock.elapsedRealtime() - started)
                try {
                    withEditor(automation) { editor ->
                        assertNeutralQuery(editor, request.query)
                        assertTrue(
                            "Release selection changed immediately before candidate click",
                            editor.textSelectionStart == request.start && editor.textSelectionEnd == request.end,
                        )
                        recordEditor(observations, "before-candidate-click", editor, request.query)
                    }
                    assertTrue("Recommendation disappeared before click", candidate.refresh())
                    assertTrue("Recommendation changed before click", candidate.text?.toString() == JAPANESE)
                    clickNearestAncestor(candidate, report)
                    return
                } finally {
                    candidate.recycle()
                }
            }
            SystemClock.sleep(100)
        }
        report.put("candidateWaitMs", SystemClock.elapsedRealtime() - started)
        throw AssertionError("Exact Japanese recommendation did not appear within 45 seconds")
    }

    private fun clickNearestAncestor(label: AccessibilityNodeInfo, report: JSONObject) {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(label)
        try {
            repeat(12) { depth ->
                val node = current ?: throw AssertionError("Recommendation has no clickable ancestor")
                assertTrue(
                    "Recommendation ancestor left the release package",
                    node.packageName?.toString() == RELEASE_PACKAGE,
                )
                if (node.isClickable && node.isVisibleToUser && node.isEnabled &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                ) {
                    val accepted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    report.put("candidateClickableAncestorDepth", depth).put("candidateActionAccepted", accepted)
                    assertTrue("Recommendation rejected ACTION_CLICK", accepted)
                    return
                }
                current = node.parent
                node.recycle()
            }
            throw AssertionError("Recommendation clickable ancestor exceeded the bounded traversal")
        } finally {
            current?.recycle()
        }
    }

    private fun awaitEditor(
        automation: UiAutomation,
        message: String,
        condition: (AccessibilityNodeInfo) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (withEditor(automation, condition)) return
            SystemClock.sleep(100)
        }
        throw AssertionError(message)
    }

    private fun <T> withEditor(automation: UiAutomation, action: (AccessibilityNodeInfo) -> T): T =
        withReleaseRoot(automation) { root ->
            val editors = findNodes(root) {
                it.isEditable && it.isFocused && it.isVisibleToUser && it.isEnabled &&
                    it.className?.toString() == "android.widget.EditText"
            }
            try {
                assertTrue("Release must expose exactly one visible focused EditText", editors.size == 1)
                action(editors.single())
            } finally {
                editors.forEach(AccessibilityNodeInfo::recycle)
            }
        }

    /** Window retrieval can initially be empty while the new service flags reach accessibility. */
    private fun awaitInitialReleaseWindow(automation: UiAutomation, report: JSONObject) {
        val observations = JSONArray().also { report.put("initialWindowObservations", it) }
        report.put("initialWindowReady", false)
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
            val windows = automation.windows
            var ownForeground = 0
            var otherForeground = 0
            var unavailableForeground = 0
            val states = JSONArray()
            try {
                for (window in windows) {
                    if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
                        (!window.isActive && !window.isFocused)
                    ) {
                        continue
                    }
                    val state = JSONObject().put("active", window.isActive).put("focused", window.isFocused)
                    states.put(state)
                    val root = window.root
                    try {
                        val owner = root?.packageName?.toString()
                        when {
                            owner == RELEASE_PACKAGE -> {
                                ownForeground++
                                state.put("owner", "own")
                            }
                            owner != null -> {
                                otherForeground++
                                state.put("owner", "other")
                            }
                            else -> {
                                unavailableForeground++
                                state.put("owner", "unavailable")
                            }
                        }
                    } finally {
                        root?.recycle()
                    }
                }
            } finally {
                windows.forEach(AccessibilityWindowInfo::recycle)
            }
            observations.put(
                JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - started)
                    .put("ownForegroundApplicationWindows", ownForeground)
                    .put("otherForegroundApplicationWindows", otherForeground)
                    .put("unavailableForegroundApplicationRoots", unavailableForeground)
                    .put("foregroundApplicationStates", states),
            )
            assertTrue("Another application is explicitly in the foreground", otherForeground == 0)
            assertTrue("Release has multiple foreground application windows", ownForeground <= 1)
            if (ownForeground == 1 && unavailableForeground == 0) {
                report.put("initialWindowReady", true)
                    .put("initialWindowWaitMs", SystemClock.elapsedRealtime() - started)
                return
            }
            // Only an empty application-window list or unavailable root metadata is retried.
            // All later foreground/editor guards remain immediate and strict.
            SystemClock.sleep(100)
        }
        report.put("initialWindowWaitMs", SystemClock.elapsedRealtime() - started)
        throw AssertionError("Release application window was not available within 10 seconds")
    }

    /** Inspect only application-window package metadata; never obtain IME/unrelated UI text. */
    private fun <T> withReleaseRoot(automation: UiAutomation, action: (AccessibilityNodeInfo) -> T): T {
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        val windows = automation.windows
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            var unrelatedForeground = false
            for (window in windows) {
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
                    (!window.isActive && !window.isFocused)
                ) {
                    continue
                }
                val root = window.root ?: continue
                if (root.packageName?.toString() == RELEASE_PACKAGE) {
                    roots += root
                } else {
                    unrelatedForeground = true
                    root.recycle()
                }
            }
            assertTrue(
                "Signed release must be the sole foreground application window",
                !unrelatedForeground && roots.size == 1,
            )
            assertTrue("Release window became stale", roots.single().refresh())
            return action(roots.single())
        } finally {
            roots.forEach(AccessibilityNodeInfo::recycle)
            windows.forEach(AccessibilityWindowInfo::recycle)
        }
    }

    private fun findNodes(
        root: AccessibilityNodeInfo,
        matches: (AccessibilityNodeInfo) -> Boolean,
    ): List<AccessibilityNodeInfo> {
        val found = mutableListOf<AccessibilityNodeInfo>()
        var visited = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            assertTrue("Release accessibility tree exceeded traversal limits", depth <= 100 && ++visited <= 10_000)
            if (node.packageName?.toString() != RELEASE_PACKAGE || !node.refresh()) return
            if (matches(node)) found += AccessibilityNodeInfo.obtain(node)
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }
        try {
            visit(root, 0)
            return found
        } catch (error: Throwable) {
            found.forEach(AccessibilityNodeInfo::recycle)
            throw error
        }
    }

    private fun assertNeutralQuery(editor: AccessibilityNodeInfo, expected: String) {
        // Never pass actual editor text to assertEquals: its failure output could disclose user text.
        assertTrue("Release editor differs from the prepared fixed neutral query", editor.text?.toString() == expected)
    }

    private fun recordEditor(observations: JSONArray, stage: String, editor: AccessibilityNodeInfo, expected: String) {
        val text = editor.text?.toString()
        val safeText = text?.takeIf { it in SAFE_TEXTS }
        val bounds = Rect().also(editor::getBoundsInScreen)
        observations.put(
            JSONObject().put("stage", stage).put("elapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("queryMatchesExpected", text == expected).put("neutralQuery", safeText ?: JSONObject.NULL)
                .put("selectionStart", editor.textSelectionStart).put("selectionEnd", editor.textSelectionEnd)
                .put("focused", editor.isFocused).put("visible", editor.isVisibleToUser)
                .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))),
        )
    }

    private data class Request(
        val query: String,
        val start: Int,
        val end: Int,
        val candidate: String?,
        val expectedResult: String?,
        val expectedResultCaret: Int,
    )

    private data class FixedCase(val caret: Int, val result: String, val resultCaret: Int)

    private companion object {
        const val AUDIT_PACKAGE = "app.yomitori.yakuyomi.audit"
        const val RELEASE_PACKAGE = "app.yomitori.yakuyomi"
        const val JAPANESE = "language:japanese"
        val CASES = mapOf(
            "jap type:manga" to FixedCase(3, "$JAPANESE type:manga", JAPANESE.length + 1),
            "type:manga -jap tag:full_color" to FixedCase(
                "type:manga -jap".length,
                "type:manga -$JAPANESE tag:full_color",
                "type:manga -$JAPANESE ".length,
            ),
        )
        val SAFE_TEXTS = CASES.keys + CASES.values.map { it.result }
    }
}
