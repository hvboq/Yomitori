package mihon.translation

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.Constants
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Real MainActivity settings rows, observed and clicked through Android accessibility.
 * No replacement composition, direct toggle callback, server request, or database fixture.
 * This verifies exposed role/state/actions, not TalkBack speech or physical touch behavior.
 */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class SettingsSwitchAccessibilityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun localSourceSwitchExposesStateAndAccessibilityClickUpdatesPreference() {
        verifySwitch(translation = false)
    }

    @Test
    fun liveTranslationSwitchExposesStateAndAccessibilityClickUpdatesPreference() {
        verifySwitch(translation = true)
    }

    private fun verifySwitch(translation: Boolean) {
        // Never toggle settings underneath user download, translation, or night-read work.
        assertTrue("Download queue must be empty", Injekt.get<DownloadManager>().queueState.value.isEmpty())
        assertTrue("Translation queue must be empty", Injekt.get<TranslationManager>().queueState.value.isEmpty())
        assertEquals("Translation work must be idle", 0, TranslationBusy.active.value)
        val base = Injekt.get<BasePreferences>()
        val sources = Injekt.get<SourcePreferences>()
        val preferences = Injekt.get<TranslationPreferences>()
        val preference = if (translation) preferences.liveTranslate else sources.showLocalSource
        val label = context.stringResource(
            if (translation) MR.strings.pref_translation_live else MR.strings.pref_show_local_source,
        )
        val saved = SavedSettings()
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        val report = JSONObject()
            .put("screen", if (translation) "translation" else "browse")
            .put("actualMainActivitySettings", true)
            .put("clickMethod", "accessibility-action-click")
            .put("talkBackSpeechVerified", false)
            .put("passed", false)
        val observations = JSONArray()
        report.put("accessibilityObservations", observations)
        var completed = false
        try {
            saved.set(base.shownOnboardingFlow, true)
            saved.preserve(base.incognitoMode) // Fresh MainActivity resets this setting.
            saved.set(Injekt.get<LibraryPreferences>().autoClearChapterCache, false)
            // Launching settings must not prewarm the user's configured models on its own.
            saved.set(preferences.liveTranslate, false)
            saved.set(preference, false)
            if (translation) {
                saved.set(preferences.translationMasterEnabled, true)
                saved.set(preferences.quickstartShown, true)
                saved.set(preferences.privacyAcknowledged, true)
                saved.set(preferences.showAdvanced, false)
            }
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val intent = Intent(context, MainActivity::class.java)
                .setAction(Constants.SHORTCUT_SOURCES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use {
                openSettings()
                clickLabel(
                    context.stringResource(
                        if (translation) MR.strings.pref_category_translation else MR.strings.browse,
                    ),
                    scroll = true,
                )
                scrollToLabel(label)
                assertSwitch(label, preference, expected = false, observations = observations)
                repeat(2) { index ->
                    withActionNode(label) { node ->
                        assertTrue("Switch rejected accessibility ACTION_CLICK", node.performAction(CLICK))
                    }
                    val expected = index == 0
                    waitUntil("Accessibility click did not update the actual preference") {
                        preference.get() == expected
                    }
                    assertSwitch(label, preference, expected, observations)
                }
                report.put("observedCheckedValues", JSONArray(listOf(false, true, false)))
                    .put("switchRoleCheckableCheckedAndClickVerified", true)
                    .put("twoClicksUpdatedActualPreference", true)
            }
            completed = true
        } finally {
            try {
                // The real live-translation callback may have started native warmup. Wait for
                // shutdown before restoring settings; no inference or translation API is used.
                if (translation) {
                    runBlocking { withTimeout(90_000) { Injekt.get<TranslationEngineService>().shutdown() } }
                }
            } catch (failure: Throwable) {
                completed = false
                throw failure
            } finally {
                try {
                    saved.restoreAndVerify()
                    report.put("preferencesRestoredIncludingPresence", true).put("passed", completed)
                } finally {
                    automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags }
                    val name = if (translation) "translation" else "browse"
                    val output = File(context.getExternalFilesDir(null), "settings-switch-accessibility/$name.json")
                    check(output.parentFile!!.isDirectory || output.parentFile!!.mkdirs())
                    output.writeText(report.toString(2))
                    println("SETTINGS_SWITCH_ACCESSIBILITY_REPORT=${output.absolutePath}")
                    println(report.toString(2))
                }
            }
        }
    }

    private fun openSettings() {
        clickLabel(context.stringResource(MR.strings.label_more))
        waitUntil("Actual More tab did not open") {
            findActionNode(context.stringResource(MR.strings.pref_incognito_mode))?.also { it.recycle() } != null
        }
        clickLabel(context.stringResource(MR.strings.label_settings), scroll = true)
        waitUntil("Actual settings main screen did not open") {
            findActionNode(context.stringResource(MR.strings.pref_category_appearance))
                ?.also { it.recycle() } != null
        }
    }

    private fun assertSwitch(
        label: String,
        preference: Preference<Boolean>,
        expected: Boolean,
        observations: JSONArray,
    ) {
        waitUntil("Accessibility checked state did not match the actual setting") {
            val node = findActionNode(label) ?: return@waitUntil false
            try {
                node.isChecked == expected && preference.get() == expected
            } finally {
                node.recycle()
            }
        }
        withActionNode(label) { node ->
            val expectedRole = context.getString(androidx.compose.ui.R.string.switch_role)
            val observation = describeNode(node)
                .put("expectedChecked", expected)
                .put("preferenceChecked", preference.get())
                .put("expectedRoleDescription", expectedRole)
                .put("syntheticRoleLinkVerified", false)
            observations.put(observation)
            println("SETTINGS_SWITCH_NODE=$observation")
            assertTrue("Settings row must expose checkable semantics", node.isCheckable)
            assertEquals("Settings row checked state differs from preference", preference.get(), node.isChecked)
            assertEquals(expected, node.isChecked)
            assertTrue("Settings row must be enabled", node.isEnabled)
            assertTrue("Settings row must remain clickable", node.isClickable)
            assertTrue("Settings row must expose ACTION_CLICK", node.actionList.any { it.id == CLICK })
            assertLinkedSwitchRole(node, expectedRole, observation)
            println("SETTINGS_SWITCH_ROLE_LINK=$observation")
        }
    }

    private fun assertLinkedSwitchRole(row: AccessibilityNodeInfo, expectedRole: String, observation: JSONObject) {
        // Compose UI 1.12.0-rc01 deliberately appends a synthetic role leaf to a merging row.
        // Its accessibility delegate puts the localized switch_role description on that leaf;
        // the owning row retains checked state and ACTION_CLICK. Requiring the role on the
        // parent or a legacy android.widget.Switch class would reject this standard contract.
        val roleChildren = mutableListOf<AccessibilityNodeInfo>()
        try {
            for (index in 0 until row.childCount) {
                val child = row.getChild(index) ?: continue
                if (AccessibilityNodeInfoCompat.wrap(child).roleDescription != null) {
                    roleChildren += child
                } else {
                    child.recycle()
                }
            }
            observation.put("directRoleLeafCount", roleChildren.size)
            assertEquals("Settings row must own exactly one direct role leaf", 1, roleChildren.size)
            val roleLeaf = roleChildren.single()
            assertEquals(
                "Direct role leaf must expose the localized Switch role",
                expectedRole,
                AccessibilityNodeInfoCompat.wrap(roleLeaf).roleDescription?.toString(),
            )
            assertEquals("Synthetic role must be a leaf", 0, roleLeaf.childCount)
            val parent = checkNotNull(roleLeaf.parent) { "Switch role leaf lost its owning settings row" }
            try {
                assertEquals("Switch role leaf must belong to the same actionable row", row, parent)
            } finally {
                parent.recycle()
            }
            assertEquals("Switch role leaf must share the row's window", row.windowId, roleLeaf.windowId)
            assertEquals("Switch role leaf must share the row's package", row.packageName, roleLeaf.packageName)
            val rowBounds = Rect().also(row::getBoundsInScreen)
            val roleBounds = Rect().also(roleLeaf::getBoundsInScreen)
            assertFalse("Settings row must have visible bounds", rowBounds.isEmpty)
            assertEquals("Switch role leaf must use its owning row's bounds", rowBounds, roleBounds)
            assertTrue("Switch role leaf must be visible", roleLeaf.isVisibleToUser)
            assertTrue("Switch role leaf must be enabled", roleLeaf.isEnabled)
            assertFalse("Role leaf must not expose a second checkable control", roleLeaf.isCheckable)
            assertFalse("Role leaf must not expose a second click target", roleLeaf.isClickable)
            assertFalse("Role leaf must not expose a second ACTION_CLICK", roleLeaf.actionList.any { it.id == CLICK })
            assertTrue("Role leaf must not replace the row's label", roleLeaf.text.isNullOrEmpty())
            assertTrue("Role leaf must not replace the row's description", roleLeaf.contentDescription.isNullOrEmpty())
            observation.put("roleCarrier", "direct-synthetic-leaf")
                .put("syntheticRoleLinkVerified", true)
        } finally {
            roleChildren.forEach(AccessibilityNodeInfo::recycle)
        }
    }

    private fun describeNode(node: AccessibilityNodeInfo, depth: Int = 0): JSONObject {
        val bounds = Rect().also(node::getBoundsInScreen)
        val description = JSONObject()
            .put("className", node.className?.toString() ?: JSONObject.NULL)
            .put(
                "roleDescription",
                AccessibilityNodeInfoCompat.wrap(node).roleDescription?.toString() ?: JSONObject.NULL,
            )
            .put("checkable", node.isCheckable)
            .put("checked", node.isChecked)
            .put("clickable", node.isClickable)
            .put("enabled", node.isEnabled)
            .put("visible", node.isVisibleToUser)
            .put("hasClickAction", node.actionList.any { it.id == CLICK })
            .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
        // Only this selected neutral settings row is inspected; no surrounding text or values.
        if (depth < 3) {
            val children = JSONArray()
            for (index in 0 until minOf(node.childCount, 8)) {
                val child = node.getChild(index) ?: continue
                try {
                    children.put(describeNode(child, depth + 1))
                } finally {
                    child.recycle()
                }
            }
            description.put("children", children)
        }
        return description
    }

    private fun clickLabel(label: String, scroll: Boolean = false) {
        if (scroll) scrollToLabel(label)
        withActionNode(label) { node ->
            assertTrue("Settings navigation rejected accessibility click", node.performAction(CLICK))
        }
    }

    private fun scrollToLabel(label: String) {
        waitUntil("Settings list did not expose $label", timeoutMs = 20_000) {
            findActionNode(label)?.let {
                it.recycle()
                return@waitUntil true
            }
            findNode { node ->
                node.isScrollable && node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD }
            }?.let { scrollable ->
                try {
                    scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                } finally {
                    scrollable.recycle()
                }
                SystemClock.sleep(200)
            }
            false
        }
    }

    private fun withActionNode(label: String, action: (AccessibilityNodeInfo) -> Unit) {
        var node: AccessibilityNodeInfo? = null
        waitUntil("Missing accessible settings action: $label") { findActionNode(label).also { node = it } != null }
        try {
            action(checkNotNull(node))
        } finally {
            node?.recycle()
        }
    }

    private fun findActionNode(label: String): AccessibilityNodeInfo? {
        var node = findNode {
            listOfNotNull(it.text, it.contentDescription).any { value ->
                value.toString().lineSequence().any { line -> line == label || line.startsWith("$label, ") }
            }
        }
        while (node != null) {
            val current = node
            if (current.isEnabled && current.isClickable && current.actionList.any { it.id == CLICK }) return current
            node = current.parent
            current.recycle()
        }
        return null
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

    private class SavedSettings {
        private val restores = linkedMapOf<String, () -> Unit>()
        private val verifies = mutableListOf<() -> Unit>()

        fun <T> preserve(preference: Preference<T>) {
            if (preference.key() in restores) return
            val existed = preference.isSet()
            val value = preference.get()
            restores[preference.key()] = { if (existed) preference.set(value) else preference.delete() }
            verifies += {
                check(preference.isSet() == existed && preference.get() == value) {
                    "Settings preference was not restored: ${preference.key()}"
                }
            }
        }

        fun <T> set(preference: Preference<T>, value: T) {
            preserve(preference)
            preference.set(value)
        }

        fun restoreAndVerify() {
            val failures = mutableListOf<Throwable>()
            fun attempt(action: () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    failures += failure
                }
            }
            restores.values.toList().asReversed().forEach { restore -> attempt(restore) }
            attempt {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                check(PreferenceManager.getDefaultSharedPreferences(context).edit().commit())
            }
            verifies.forEach { verify -> attempt(verify) }
            if (failures.isNotEmpty()) {
                throw AssertionError("Settings accessibility test cleanup failed").apply {
                    failures.forEach(::addSuppressed)
                }
            }
        }
    }

    private companion object {
        const val CLICK = AccessibilityNodeInfo.ACTION_CLICK
    }
}
