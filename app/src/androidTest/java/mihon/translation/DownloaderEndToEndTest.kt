package mihon.translation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real public queue/start -> Downloader HTTP -> SAF publication/cache -> downloaded reader.
 * The bounded source serves neutral PNGs. This is neither a live Hitomi nor a Worker/UI test.
 */
@RunWith(AndroidJUnit4::class)
class DownloaderEndToEndTest {
    @Test(timeout = 60_000)
    fun metadataOnlyCanonicalPublishesLooseAliasThroughActualDownloaderAndReader(): Unit = runBlocking {
        runCase(
            "metadataOnlyCanonicalPublishesLooseAliasThroughActualDownloaderAndReader",
            archive = false,
        )
    }

    @Test(timeout = 60_000)
    fun metadataOnlyCanonicalPublishesCbzAliasThroughActualDownloaderAndReader(): Unit = runBlocking {
        runCase(
            "metadataOnlyCanonicalPublishesCbzAliasThroughActualDownloaderAndReader",
            archive = true,
        )
    }

    private suspend fun runCase(method: String, archive: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(instrumentation.targetContext.packageName == "app.yomitori.yakuyomi.audit") {
            "The isolated audit installation is required"
        }
        check(InstrumentationRegistry.getArguments().getString("downloaderEndToEnd") == "true") {
            "Select this bounded downloader regression explicitly with downloaderEndToEnd=true"
        }
        NativeDiagnosticIdleGuard.requireIdle(instrumentation.targetContext)
        val fixture = DownloaderEndToEndFixture(instrumentation.targetContext, archive)
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
        // Emit success only after every private job, dependency lease and file has been cleaned up.
        fixture.report(method)
    }
}
