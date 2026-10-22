package mihon.backup

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
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.storage.getUriCompat
import kotlinx.serialization.protobuf.ProtoBuf
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
import java.util.UUID
import java.util.zip.GZIPOutputStream

/**
 * Actual MainActivity backup option rows and accessibility clicks, restricted to the audit app.
 * Restore uses a neutral private FileProvider document and the real validator, not a SAF picker.
 * No backup/restore action is submitted; this does not verify WorkManager, notifications, or speech.
 */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class BackupCheckboxAccessibilityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var report: JSONObject
    private lateinit var reportFile: File
    private val observations = JSONArray()

    @Test
    fun createLibraryDependenciesExposeDisabledStateAndPreserveTheirValues() = runScreen("create-library") {
        val library = context.stringResource(MR.strings.manga)
        val chapters = context.stringResource(MR.strings.chapters)
        assertCheckbox(library, checked = true)
        assertCheckbox(chapters, checked = true)
        toggle(chapters, checked = false)
        toggle(library, checked = false)

        val dependencies = listOf(
            chapters to false,
            context.stringResource(MR.strings.track) to true,
            context.stringResource(MR.strings.history) to true,
            context.stringResource(MR.strings.non_library_settings) to true,
        )
        dependencies.forEach { (label, checked) ->
            assertCheckbox(label, checked, enabled = false)
            rejectDisabledClick(label)
            assertCheckbox(label, checked, enabled = false)
        }
        toggle(library, checked = true)
        dependencies.forEach { (label, checked) -> assertCheckbox(label, checked) }
        toggle(chapters, checked = true)
        report.put("libraryDependencyValuesPreserved", true)
    }

    @Test
    fun createPrivateSettingsDependencyAndEmptySelectionExposeDisabledActions() = runScreen("create-settings") {
        val appSettings = context.stringResource(MR.strings.app_settings)
        val sourceSettings = context.stringResource(MR.strings.source_settings)
        val privateSettings = context.stringResource(MR.strings.private_settings)
        assertCheckbox(privateSettings, checked = false)
        toggle(privateSettings, checked = true)
        toggle(appSettings, checked = false)
        assertCheckbox(privateSettings, checked = true)
        toggle(sourceSettings, checked = false)
        assertCheckbox(privateSettings, checked = true, enabled = false)
        rejectDisabledClick(privateSettings)
        toggle(sourceSettings, checked = true)
        assertCheckbox(privateSettings, checked = true)
        toggle(privateSettings, checked = false)
        toggle(sourceSettings, checked = false)
        toggle(context.stringResource(MR.strings.manga), checked = false)
        toggle(context.stringResource(MR.strings.categories), checked = false)
        toggle(context.stringResource(MR.strings.extensionStores), checked = false)
        assertCheckbox(privateSettings, checked = false, enabled = false)
        assertAction(context.stringResource(MR.strings.action_create), enabled = false)
        rejectDisabledClick(context.stringResource(MR.strings.action_create))
        toggle(appSettings, checked = true)
        assertCheckbox(privateSettings, checked = false)
        assertAction(context.stringResource(MR.strings.action_create), enabled = true)
        report.put("privateSettingValuePreserved", true).put("emptySelectionActionDisabled", true)
    }

    @Test
    fun restoreOptionsExposeCheckedStateAndEmptySelectionDisablesRestore() = runScreen("restore", restore = true) {
        val labels = RestoreOptions.options.map { context.stringResource(it.label) }
        labels.forEach { label ->
            assertCheckbox(label, checked = true)
            toggle(label, checked = false)
        }
        labels.forEach { label -> assertCheckbox(label, checked = false) }
        val action = context.stringResource(MR.strings.action_restore)
        assertAction(action, enabled = false)
        rejectDisabledClick(action)
        toggle(context.stringResource(MR.strings.app_settings), checked = true)
        assertAction(action, enabled = true)
        report.put("allRestoreOptionsToggledThroughAccessibility", true)
            .put("emptySelectionActionDisabled", true)
    }

    private fun runScreen(name: String, restore: Boolean = false, exercise: () -> Unit) {
        assumeTrue(
            "Requires explicit -e backupUiAudit true",
            InstrumentationRegistry.getArguments().getString("backupUiAudit") == "true",
        )
        // This guard precedes preference, database, or fixture access.
        assertEquals("Backup UI audit must never target a user app", AUDIT_PACKAGE, context.packageName)
        assertTrue("Download queue must be empty", Injekt.get<DownloadManager>().queueState.value.isEmpty())
        assertTrue("Translation queue must be empty", Injekt.get<TranslationManager>().queueState.value.isEmpty())
        assertEquals("Translation work must be idle", 0, TranslationBusy.active.value)

        val id = UUID.randomUUID().toString()
        reportFile = File(context.getExternalFilesDir(null), "backup-checkbox-accessibility/$name-$id.json")
        check(reportFile.parentFile!!.isDirectory || reportFile.parentFile!!.mkdirs())
        report = JSONObject()
            .put("case", name).put("targetPackage", context.packageName)
            .put("actualMainActivityScreen", true).put("safPickerVerified", false)
            .put("workManagerVerified", false).put("notificationsVerified", false)
            .put("talkBackSpeechVerified", false).put("passed", false)
            .put("observations", observations)
        val fixtureDirectory = File(context.cacheDir, "backup-checkbox-$id")
        val saved = SavedSettings()
        val automation = instrumentation.uiAutomation
        val previousFlags = automation.serviceInfo.flags
        var failure: Throwable? = null
        try {
            val base = Injekt.get<BasePreferences>()
            saved.set(base.shownOnboardingFlow, true)
            saved.preserve(base.incognitoMode)
            saved.set(Injekt.get<LibraryPreferences>().autoClearChapterCache, false)
            saved.set(Injekt.get<TranslationPreferences>().liveTranslate, false)
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            if (restore) {
                check(fixtureDirectory.mkdir())
                val file = File(fixtureDirectory, "neutral.tachibk")
                val backup = Backup(
                    backupManga = emptyList(),
                    backupCategories = listOf(BackupCategory("Checkbox audit $id", id = 1)),
                )
                GZIPOutputStream(file.outputStream()).use {
                    it.write(ProtoBuf.encodeToByteArray(Backup.serializer(), backup))
                }
                intent.setAction(Intent.ACTION_VIEW).setData(file.getUriCompat(context))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                report.put("restoreInput", "neutral-private-cache-fileprovider")
                    .put("realBackupValidator", true)
            } else {
                intent.setAction(Constants.SHORTCUT_SOURCES)
            }
            ActivityScenario.launch<MainActivity>(intent).use {
                if (!restore) openCreateBackup()
                exercise()
            }
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            fun cleanup(action: () -> Unit) {
                try {
                    action()
                } catch (caught: Throwable) {
                    val previous = failure
                    if (previous == null) failure = caught else previous.addSuppressed(caught)
                }
            }
            cleanup {
                saved.restoreAndVerify()
                report.put("preferencesRestoredIncludingPresence", true)
            }
            cleanup { automation.serviceInfo = automation.serviceInfo.apply { flags = previousFlags } }
            cleanup {
                check(!fixtureDirectory.exists() || fixtureDirectory.deleteRecursively())
                report.put("neutralFixtureRemoved", true)
            }
            report.put("passed", failure == null)
            cleanup { persistReport() }
        }
        failure?.let { throw it }
    }

    private fun openCreateBackup() {
        clickLabel(context.stringResource(MR.strings.label_more))
        clickLabel(context.stringResource(MR.strings.label_settings))
        clickLabel(context.stringResource(MR.strings.label_data_storage))
        clickLabel(context.stringResource(MR.strings.pref_create_backup))
    }

    private fun toggle(label: String, checked: Boolean) {
        withControl(label) { node ->
            assertTrue("Enabled checkbox rejected ACTION_CLICK: $label", node.performAction(CLICK))
        }
        waitUntil("Checkbox did not change to $checked: $label") {
            val node = findControl(label) ?: return@waitUntil false
            try {
                node.isCheckable && node.isChecked == checked
            } finally {
                node.recycle()
            }
        }
        assertCheckbox(label, checked)
    }

    private fun rejectDisabledClick(label: String) {
        withControl(label) { node ->
            observe(label, node)
            assertFalse("Disabled control must reject ACTION_CLICK: $label", node.performAction(CLICK))
        }
    }

    private fun assertCheckbox(label: String, checked: Boolean, enabled: Boolean = true) {
        withControl(label) { node ->
            // Persist the first actual node before any contract assertion, including on old builds.
            observe(label, node)
            assertTrue("Checkbox row must expose checkable state: $label", node.isCheckable)
            assertEquals("Unexpected checked value: $label", checked, node.isChecked)
            assertEquals("Unexpected enabled value: $label", enabled, node.isEnabled)
            assertEquals("Unexpected ACTION_CLICK availability: $label", enabled, node.hasClick())
            if (enabled) assertTrue("Enabled checkbox must be clickable: $label", node.isClickable)
            assertCheckboxRole(node)
            assertNoNestedActions(node)
        }
    }

    private fun assertAction(label: String, enabled: Boolean) {
        withControl(label) { node ->
            observe(label, node)
            assertEquals("Unexpected backup action enabled state", enabled, node.isEnabled)
            assertEquals("Unexpected backup ACTION_CLICK availability", enabled, node.hasClick())
        }
    }

    private fun assertCheckboxRole(row: AccessibilityNodeInfo) {
        var roleCount = if (row.className?.toString() == CHECKBOX_CLASS) 1 else 0
        for (index in 0 until row.childCount) {
            val child = row.getChild(index) ?: continue
            try {
                if (child.className?.toString() == CHECKBOX_CLASS) {
                    roleCount++
                    // Compose can expose the role on a direct synthetic leaf while the row
                    // retains checked state and the single action, as it does for Switch.
                    assertEquals("Checkbox role carrier must be a leaf", 0, child.childCount)
                    val parent = checkNotNull(child.parent)
                    try {
                        assertEquals("Role leaf must belong to this checkbox row", row, parent)
                    } finally {
                        parent.recycle()
                    }
                    assertEquals(row.windowId, child.windowId)
                    assertEquals(row.packageName, child.packageName)
                    assertEquals(bounds(row), bounds(child))
                }
            } finally {
                child.recycle()
            }
        }
        assertEquals("Checkbox row must expose exactly one Checkbox role", 1, roleCount)
    }

    private fun assertNoNestedActions(row: AccessibilityNodeInfo) {
        for (index in 0 until row.childCount) {
            val child = row.getChild(index) ?: continue
            try {
                assertFalse("Checkbox must not have a second checkable descendant", child.isCheckable)
                assertFalse("Checkbox must not have a second click target", child.isClickable)
                assertFalse("Checkbox must not have a second ACTION_CLICK", child.hasClick())
                assertNoNestedActions(child)
            } finally {
                child.recycle()
            }
        }
    }

    private fun observe(label: String, node: AccessibilityNodeInfo) {
        val observation = describe(node).put("label", label)
        observations.put(observation)
        println("BACKUP_CHECKBOX_NODE=$observation")
        persistReport()
    }

    private fun describe(node: AccessibilityNodeInfo, depth: Int = 0): JSONObject = JSONObject()
        .put("className", node.className?.toString())
        .put("roleDescription", AccessibilityNodeInfoCompat.wrap(node).roleDescription?.toString() ?: JSONObject.NULL)
        .put("enabled", node.isEnabled).put("checkable", node.isCheckable).put("checked", node.isChecked)
        .put("clickable", node.isClickable).put("visible", node.isVisibleToUser)
        .put("actions", JSONArray(node.actionList.map { it.id }))
        .put("bounds", bounds(node).flattenToString())
        .apply {
            if (depth < 2) {
                val children = JSONArray()
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try {
                        children.put(describe(child, depth + 1))
                    } finally {
                        child.recycle()
                    }
                }
                put("children", children)
            }
        }

    private fun persistReport() {
        reportFile.writeText(report.toString(2))
        println("BACKUP_CHECKBOX_ACCESSIBILITY_REPORT=${reportFile.absolutePath}")
    }

    private fun clickLabel(label: String) {
        withControl(label) { node ->
            assertTrue("Backup navigation rejected ACTION_CLICK: $label", node.performAction(CLICK))
        }
    }

    private fun withControl(label: String, action: (AccessibilityNodeInfo) -> Unit) {
        // Use the node whose complete viewport containment was observed, rather than
        // accepting the first partially visible label or reacquiring an unprepared row.
        val node = scrollToLabel(label)
        try {
            action(node)
        } finally {
            node.recycle()
        }
    }

    private fun scrollToLabel(label: String): AccessibilityNodeInfo {
        var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        var attempts = 0
        var prepared: AccessibilityNodeInfo? = null
        waitUntil("Actual backup screen did not fully expose $label", timeoutMs = 20_000) {
            var scrollable: AccessibilityNodeInfo? = null
            findControl(label)?.let { control ->
                val ancestor = nearestScrollableAncestor(control)
                val controlBounds = bounds(control)
                val viewport = ancestor?.let(::bounds)
                if (control.isVisibleToUser && !controlBounds.isEmpty &&
                    (viewport == null || viewport.contains(controlBounds))
                ) {
                    ancestor?.recycle()
                    prepared = control
                    return@waitUntil true
                }
                if (ancestor != null) {
                    val viewportBounds = checkNotNull(viewport)
                    // A role leaf is clipped to this viewport even when its owning row
                    // remains isVisibleToUser. Move the actual enclosing list until the
                    // whole row fits before comparing role/row bounds or clicking it.
                    direction = when {
                        controlBounds.bottom > viewportBounds.bottom -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        controlBounds.top < viewportBounds.top -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        else -> direction
                    }
                    scrollable = ancestor
                    println(
                        "BACKUP_CHECKBOX_SCROLL_PRECONDITION=" + JSONObject()
                            .put("label", label)
                            .put("rowBounds", controlBounds.flattenToString())
                            .put("viewportBounds", viewportBounds.flattenToString())
                            .put("scrollAction", direction),
                    )
                }
                control.recycle()
            }
            if (scrollable == null) {
                scrollable = findNode {
                    it.isScrollable && it.actionList.any { action -> action.id == direction }
                }
            }
            val moved = try {
                scrollable?.performAction(direction) == true
            } finally {
                scrollable?.recycle()
            }
            if (!moved || ++attempts % 8 == 0) {
                direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
            }
            SystemClock.sleep(150)
            false
        }
        return checkNotNull(prepared) { "Missing fully exposed backup control: $label" }
    }

    private fun nearestScrollableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var ancestor = node.parent
        while (ancestor != null) {
            val current = ancestor
            val hasScrollAction = current.actionList.any {
                it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                    it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }
            if (current.isScrollable || hasScrollAction) return current
            ancestor = current.parent
            current.recycle()
        }
        return null
    }

    private fun findControl(label: String): AccessibilityNodeInfo? {
        var node = findNode {
            listOfNotNull(it.text, it.contentDescription).any { value ->
                value.toString().lineSequence().any { line -> line == label || line.startsWith("$label, ") }
            }
        }
        while (node != null) {
            val current = node
            if (current.isCheckable || current.isClickable || current.hasClick() ||
                current.className?.toString() in listOf(CHECKBOX_CLASS, "android.widget.Button")
            ) {
                return current
            }
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
        private val restores = mutableListOf<() -> Unit>()
        private val verifies = mutableListOf<() -> Unit>()

        fun <T> preserve(preference: Preference<T>) {
            val existed = preference.isSet()
            val value = preference.get()
            restores += { if (existed) preference.set(value) else preference.delete() }
            verifies += {
                check(preference.isSet() == existed && preference.get() == value) {
                    "Backup checkbox preference was not restored: ${preference.key()}"
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
            restores.asReversed().forEach { attempt(it) }
            attempt {
                check(
                    PreferenceManager.getDefaultSharedPreferences(
                        InstrumentationRegistry.getInstrumentation().targetContext,
                    ).edit().commit(),
                )
            }
            verifies.forEach { attempt(it) }
            if (failures.isNotEmpty()) {
                throw AssertionError("Backup checkbox cleanup failed").apply { failures.forEach(::addSuppressed) }
            }
        }
    }

    private fun AccessibilityNodeInfo.hasClick() = actionList.any { it.id == CLICK }

    private fun bounds(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen)

    private companion object {
        const val AUDIT_PACKAGE = "app.yomitori.yakuyomi.audit"
        const val CHECKBOX_CLASS = "android.widget.CheckBox"
        const val CLICK = AccessibilityNodeInfo.ACTION_CLICK
    }
}
