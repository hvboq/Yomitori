package eu.kanade.presentation.more.settings.screen.data

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import eu.kanade.tachiyomi.data.backup.BackupFileValidator
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RestoreBackupViewModelTest {
    @Test
    fun `blocking validator leaves construction and UI heartbeat responsive and guards restore until ready`() =
        runBlocking {
            val ui = Executors.newSingleThreadExecutor { Thread(it, "backup-validation-ui") }.asCoroutineDispatcher()
            val io = Executors.newSingleThreadExecutor { Thread(it, "backup-validation-io") }.asCoroutineDispatcher()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val validationThread = AtomicReference<Thread>()
            val uiThread = AtomicReference<Thread>()
            val restores = mutableListOf<Pair<String, RestoreOptions>>()
            val store = ViewModelStore()
            Dispatchers.setMain(ui)
            val creation = async(ui) {
                uiThread.set(Thread.currentThread())
                RestoreBackupViewModel(
                    context = mockk<Context>(),
                    uri = BACKUP_URI,
                    validationDispatcher = io,
                    validateBackup = { uri ->
                        assertEquals(BACKUP_URI, uri)
                        validationThread.set(Thread.currentThread())
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Validation gate was never released" }
                        validResult()
                    },
                    restoreBackup = { uri, options -> restores += uri to options },
                ).also { store.put("restore", it) }
            }

            try {
                // A synchronous init cannot return while this validator's gate remains closed.
                val viewModel = withTimeout(5_000) { creation.await() }
                assertTrue(entered.await(5, TimeUnit.SECONDS), "Validator did not enter its IO gate")
                assertTrue(validationThread.get() !== uiThread.get(), "File validation ran on the UI thread")
                withTimeout(5_000) {
                    withContext(ui) {
                        assertTrue(viewModel.state.value.isValidating)
                        assertFalse(viewModel.state.value.canRestore)
                        viewModel.startRestore()
                        assertTrue(restores.isEmpty(), "Restore started before validation completed")
                        viewModel.toggle({ options, enabled -> options.copy(libraryEntries = enabled) }, false)
                    }
                }

                release.countDown()
                val ready = withTimeout(5_000) { viewModel.state.first { !it.isValidating } }
                assertTrue(ready.canRestore)
                assertEquals(null, ready.error)
                assertFalse(ready.options.libraryEntries, "Validation reset selected restore options")
                withContext(ui) {
                    viewModel.startRestore()
                    assertEquals(listOf(BACKUP_URI to ready.options), restores)
                    viewModel.toggle({ _, _ -> RestoreOptions(false, false, false, false, false) }, false)
                    viewModel.startRestore()
                    assertEquals(1, restores.size, "An empty selection started a restore")
                }
            } finally {
                release.countDown()
                try {
                    withTimeout(5_000) { creation.await() }
                    withContext(ui) { store.clear() }
                } finally {
                    Dispatchers.resetMain()
                    io.close()
                    ui.close()
                }
            }
        }

    @Test
    fun `clearing view model during blocking validation suppresses late success and error`() = runBlocking {
        assertClearedValidationDoesNotPublish(failAfterRelease = false)
        assertClearedValidationDoesNotPublish(failAfterRelease = true)
    }

    private suspend fun CoroutineScope.assertClearedValidationDoesNotPublish(failAfterRelease: Boolean) {
        val ui = Executors.newSingleThreadExecutor { Thread(it, "backup-validation-ui") }.asCoroutineDispatcher()
        val io = Executors.newSingleThreadExecutor { Thread(it, "backup-validation-io") }.asCoroutineDispatcher()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val store = ViewModelStore()
        Dispatchers.setMain(ui)
        val creation = async(ui) {
            RestoreBackupViewModel(
                context = mockk<Context>(),
                uri = BACKUP_URI,
                validationDispatcher = io,
                validateBackup = {
                    entered.countDown()
                    try {
                        check(release.await(10, TimeUnit.SECONDS)) { "Validation gate was never released" }
                        if (failAfterRelease) throw IOException("Late invalid backup fixture")
                        validResult()
                    } finally {
                        finished.countDown()
                    }
                },
                restoreBackup = { _, _ -> error("A cleared view model started a restore") },
            ).also { store.put("restore", it) }
        }

        try {
            val viewModel = withTimeout(5_000) { creation.await() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val validationJob = viewModel.viewModelScope.coroutineContext[Job]!!.children.single()
            withContext(ui) { store.clear() }
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            withTimeout(5_000) { validationJob.join() }
            assertTrue(validationJob.isCancelled)
            assertEquals(RestoreBackupViewModel.State(), viewModel.state.value)
            withContext(ui) { viewModel.startRestore() }
        } finally {
            release.countDown()
            try {
                withTimeout(5_000) { creation.await() }
                withContext(ui) { store.clear() }
            } finally {
                Dispatchers.resetMain()
                io.close()
                ui.close()
            }
        }
    }

    @Test
    fun `invalid backup finishes loading with an error and cannot enqueue restore`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = ViewModelStore()
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = RestoreBackupViewModel(
                context = mockk<Context>(),
                uri = BACKUP_URI,
                validationDispatcher = dispatcher,
                validateBackup = { throw IOException("Invalid gzip fixture") },
                restoreBackup = { _, _ -> error("Invalid backup was enqueued") },
            ).also { store.put("restore", it) }
            assertTrue(viewModel.state.value.isValidating)
            runCurrent()
            val state = viewModel.state.value
            assertFalse(state.isValidating)
            assertFalse(state.canRestore)
            assertEquals(InvalidRestore(BACKUP_URI, "Invalid gzip fixture"), state.error)
            viewModel.startRestore()
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `missing components finish loading with the warning and preserve restoration eligibility`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = ViewModelStore()
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = RestoreBackupViewModel(
                context = mockk<Context>(),
                uri = BACKUP_URI,
                validationDispatcher = dispatcher,
                validateBackup = { BackupFileValidator.Results(listOf("Fixture Source"), listOf("Fixture Tracker")) },
                restoreBackup = { _, _ -> },
            ).also { store.put("restore", it) }
            runCurrent()
            val state = viewModel.state.value
            assertFalse(state.isValidating)
            assertTrue(state.canRestore)
            assertEquals(
                MissingRestoreComponents(BACKUP_URI, listOf("Fixture Source"), listOf("Fixture Tracker")),
                state.error,
            )
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `validator cancellation is propagated without invalid backup state`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = ViewModelStore()
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = RestoreBackupViewModel(
                context = mockk<Context>(),
                uri = BACKUP_URI,
                validationDispatcher = dispatcher,
                validateBackup = { throw CancellationException("Validation cancelled") },
                restoreBackup = { _, _ -> error("Cancelled backup was enqueued") },
            ).also { store.put("restore", it) }
            val validationJob = viewModel.viewModelScope.coroutineContext[Job]!!.children.single()
            runCurrent()
            assertTrue(validationJob.isCancelled)
            assertEquals(RestoreBackupViewModel.State(), viewModel.state.value)
            viewModel.startRestore()
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    companion object {
        private const val BACKUP_URI = "content://fixture/neutral-backup.tachibk"

        private fun validResult() = BackupFileValidator.Results(emptyList(), emptyList())
    }
}
