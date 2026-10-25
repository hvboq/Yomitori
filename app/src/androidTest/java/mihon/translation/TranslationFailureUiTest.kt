package mihon.translation

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.translation.model.TranslationFailureCode
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Actual MainActivity tab and queue actions; inference is scripted but the failure uses real HTTP. */
@RunWith(AndroidJUnit4::class)
class TranslationFailureUiTest {
    @Test(timeout = 120_000)
    fun actualTranslationTabShowsLocalizedHttpCauseAndGroupRetryClearsIt(): Unit = runBlocking {
        withTranslationFailureFixture { f ->
            prepareFailure(f)
            withQueueActivity(f) {
                val cause = f.target.stringResource(MR.strings.translation_failure_server_auth)
                awaitLabel(cause)
                assertFalse("FAILURE_UI_PROVIDER_BODY_HIDDEN", allVisibleText().contains(FAILURE_CANARY))
                f.retryAndHold { clickLabel(f.target.stringResource(MR.strings.action_retry)) }
                assertEquals(
                    "FAILURE_UI_PUBLIC_RETRY_CLEARS_CAUSE",
                    null,
                    f.manager.queueState.value.single().failureCode,
                )
                awaitLabelAbsent(cause)
                f.assertOriginalAndPartialPreserved()
            }
        }
    }

    @Test(timeout = 120_000)
    fun legacyErrorRestoredWithoutACodeShowsGenericGuidanceInTheActualQueue(): Unit = runBlocking {
        withTranslationFailureFixture { f ->
            prepareFailure(f)
            f.restoreLegacyError()
            assertEquals("FAILURE_UI_LEGACY_CODE_ABSENT", null, f.store.restore().single().failureCode)
            withQueueActivity(f) {
                awaitLabel(f.target.stringResource(MR.strings.translation_failure_unknown))
                assertFalse(
                    "FAILURE_UI_LEGACY_DOES_NOT_GUESS_AUTH",
                    allVisibleText().contains(f.target.stringResource(MR.strings.translation_failure_server_auth)),
                )
                assertFalse("FAILURE_UI_LEGACY_BODY_HIDDEN", allVisibleText().contains(FAILURE_CANARY))
                f.assertOriginalAndPartialPreserved()
            }
        }
    }

    private suspend fun prepareFailure(f: TranslationFailureFixture) {
        FailureLoopbackServer(401).use { server ->
            f.installAndRun(FailureHttpEngine(server, FAILURE_QUERIES)) {
                f.manager.translate(f.manga, listOf(f.chapter), method = FAILURE_METHOD)
            }
            f.awaitError(TranslationFailureCode.SERVER_AUTH)
            server.assertRequests(FAILURE_QUERIES)
            f.joinDrain()
        }
    }

    private suspend fun withQueueActivity(f: TranslationFailureFixture, block: suspend () -> Unit) {
        val base = Injekt.get<BasePreferences>()
        f.settings.set(base.shownOnboardingFlow, true)
        f.settings.set(base.incognitoMode, base.incognitoMode.get())
        f.settings.set(Injekt.get<LibraryPreferences>().autoClearChapterCache, false)
        f.settings.set(f.preferences.quickstartShown, true)
        f.settings.set(f.preferences.privacyAcknowledged, true)
        val intent = Intent(f.target, MainActivity::class.java)
            .setAction(Constants.SHORTCUT_SOURCES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            // This is the actual public tab. No setContent, replacement Composable or navigator push.
            clickLabel(f.target.stringResource(MR.strings.label_translation))
            awaitLabel(f.manga.title)
            block()
        }
    }

    private fun awaitLabel(label: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (hasLabel(label)) return
            SystemClock.sleep(50)
        }
        error("Expected localized queue label was not exposed")
    }

    private fun awaitLabelAbsent(label: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!hasLabel(label)) return
            SystemClock.sleep(50)
        }
        error("Stale failure guidance remained after public retry")
    }

    private fun clickLabel(label: String) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
            if (root != null) {
                try {
                    if (root.packageName?.toString() == "app.yomitori.yakuyomi.audit") {
                        val found = find(root) {
                            it.text?.toString() == label || it.contentDescription?.toString() == label
                        }
                        if (found != null) {
                            try {
                                var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(found)
                                while (current != null) {
                                    val node = current
                                    if (node.isVisibleToUser && node.isEnabled && node.isClickable) {
                                        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                        node.recycle()
                                        assertTrue("Localized public queue action rejected ACTION_CLICK", clicked)
                                        return
                                    }
                                    current = node.parent
                                    node.recycle()
                                }
                            } finally {
                                found.recycle()
                            }
                        }
                    }
                } finally {
                    root.recycle()
                }
            }
            SystemClock.sleep(50)
        }
        error("Localized public queue action was not exposed")
    }

    private fun hasLabel(label: String): Boolean {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return false
        return try {
            if (root.packageName?.toString() != "app.yomitori.yakuyomi.audit") return false
            val found = find(root) { it.text?.toString() == label || it.contentDescription?.toString() == label }
            (found != null).also { found?.recycle() }
        } finally {
            root.recycle()
        }
    }

    private fun allVisibleText(): String {
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow ?: return ""
        return try {
            val result = StringBuilder()
            fun visit(node: AccessibilityNodeInfo) {
                if (node.isVisibleToUser) {
                    result.append(node.text).append('\n').append(node.contentDescription).append('\n')
                }
                for (index in 0 until node.childCount) {
                    node.getChild(index)?.let { child ->
                        try {
                            visit(child)
                        } finally {
                            child.recycle()
                        }
                    }
                }
            }
            visit(root)
            result.toString()
        } finally {
            root.recycle()
        }
    }

    private fun find(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (node.isVisibleToUser && predicate(node)) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                try {
                    find(child, predicate)?.let { return it }
                } finally {
                    child.recycle()
                }
            }
        }
        return null
    }
}
