package mihon.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith

/** Actual ChapterCache entry -> public manager/start -> cached copy, publication and reader. */
@RunWith(AndroidJUnit4::class)
class CachedDownloaderDescriptorTest {
    @Test(timeout = 60_000)
    fun cachedPageClosesHeaderProbeBeforeSafRenameAndDownloadedReaderDecodesExactBytes(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "app.yomitori.yakuyomi.audit")
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("downloaderEndToEnd") == "true")
        check(arguments.getString("cachedDownloadDescriptor") == "true") {
            "Select this private-inode cached-download regression explicitly"
        }
        NativeDiagnosticIdleGuard.requireIdle(instrumentation.targetContext)
        val fixture = DownloaderEndToEndFixture(
            instrumentation.targetContext,
            archive = false,
            cachedFirstPage = true,
        )
        var failure: Throwable? = null
        try {
            fixture.prepare()
            fixture.downloadAndRead()
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            withContext(NonCancellable) {
                try {
                    fixture.close()
                } catch (cleanup: Throwable) {
                    if (failure == null) throw cleanup else failure.addSuppressed(cleanup)
                }
            }
        }
        fixture.report("cachedPageClosesHeaderProbeBeforeSafRenameAndDownloadedReaderDecodesExactBytes")
    }
}
