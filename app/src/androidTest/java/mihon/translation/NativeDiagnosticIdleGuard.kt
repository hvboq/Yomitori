package mihon.translation

import android.content.Context
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.TranslationBusy
import eu.kanade.tachiyomi.data.translation.TranslationEngineService
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.data.translation.TranslationStore
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Test-only pinned-cache observation. This never creates a service or invokes a Lazy getter. */
internal object NativeDiagnosticIdleGuard {
    /** Reads only already materialized singleton values; no get()/factory/restore operation is called. */
    fun requireIdle(context: Context): JSONObject {
        check(context.packageName == "app.yomitori.yakuyomi.audit") { "Isolated audit package required" }
        val registrar = Injekt.registrar
        // App.patchInjekt installs com.github.mihonapp:injekt:91edab2317, not the upstream registrar.
        // Its NOKEY singleton entries contain Lazy wrappers; reading .value could create a service.
        check(registrar.javaClass.name == "dev.mihon.injekt.PatchedDefaultRegister") {
            "Unsupported registrar for read-only singleton guard"
        }
        val values = registrar.javaClass.getDeclaredField("existingValues").apply { isAccessible = true }
            .get(registrar) as? ConcurrentHashMap<*, *> ?: error("Cannot read existing singleton values")
        val noKey = checkNotNull(
            registrar.javaClass.getDeclaredField("NOKEY").apply { isAccessible = true }.get(registrar),
        )
        val sentinelClass = Class.forName("kotlin.UNINITIALIZED_VALUE", false, registrar.javaClass.classLoader)
        val sentinel = checkNotNull(sentinelClass.getDeclaredField("INSTANCE").get(null))
        var initializedWrappers = 0
        var uninitializedWrappers = 0
        val existing = values.entries.toList().mapNotNull { (key, stored) ->
            checkNotNull(key)
            checkNotNull(stored)
            check(key.javaClass.name == "dev.mihon.injekt.PatchedDefaultRegister\$Instance") {
                "Unsupported singleton cache key shape"
            }
            val instanceKey = checkNotNull(
                key.javaClass.getDeclaredField("forKey").apply { isAccessible = true }.get(key),
            )
            if (instanceKey !== noKey) {
                // The pinned per-key route stores its actual result directly, without a Lazy wrapper.
                stored
            } else {
                check(stored.javaClass.name == "kotlin.SynchronizedLazyImpl") {
                    "Unsupported singleton wrapper shape"
                }
                // Pinned Kotlin 2.4.10 lazy() uses this volatile field and this exact sentinel.
                // A sentinel means no materialized object at this snapshot; no initializer is invoked.
                val materialized = stored.javaClass.getDeclaredField("_value").apply { isAccessible = true }
                    .get(stored)
                if (materialized === sentinel) {
                    uninitializedWrappers++
                    null
                } else {
                    initializedWrappers++
                    checkNotNull(materialized) { "Unknown singleton wrapper state" }.also {
                        check(it.javaClass != sentinelClass) { "Unrecognized singleton sentinel instance" }
                    }
                }
            }
        }
        val services = existing.filterIsInstance<TranslationEngineService>()
        val managers = existing.filterIsInstance<TranslationManager>()
        val downloads = existing.filterIsInstance<DownloadManager>()
        check(TranslationBusy.active.value == 0) { "Shared translation work must remain idle" }
        for (service in services) {
            check(!service.warm.value && !service.loading.value)
            val engine = TranslationEngineService::class.java.getDeclaredField("engine")
                .apply { isAccessible = true }.get(service)
            check(engine == null)
            val inFlight = TranslationEngineService::class.java.getDeclaredField("inFlight")
                .apply { isAccessible = true }.get(service) as AtomicInteger
            check(inFlight.get() == 0)
        }
        check(managers.all { it.queueState.value.isEmpty() })
        check(downloads.all { it.queueState.value.isEmpty() && !it.isRunning })
        val translation = context.getSharedPreferences("active_translations", Context.MODE_PRIVATE)
        val download = context.getSharedPreferences("active_downloads", Context.MODE_PRIVATE)
        // Active-store entries, not completion/history records. Refuse even unresolved potential work.
        val possibleTranslationEntries = translation.all.values.count { it is String }
        val possibleDownloadEntries = download.all.values.count { it is String }
        val pendingTranslate = TranslationStore.decodeMarks(
            translation.getStringSet("pending_translate", null),
            Long.MIN_VALUE,
        )
        val pendingNight = TranslationStore.decodeMarks(translation.getStringSet("pending_night", null), Long.MIN_VALUE)
        check(possibleTranslationEntries == 0 && possibleDownloadEntries == 0)
        check(pendingTranslate.isEmpty() && pendingNight.isEmpty())
        return JSONObject().put("existingServicePresent", services.isNotEmpty())
            .put("existingManagerPresent", managers.isNotEmpty())
            .put("existingDownloaderPresent", downloads.isNotEmpty()).put("sharedBusy", 0)
            .put("potentialSerializedActiveTranslationEntries", possibleTranslationEntries)
            .put("potentialSerializedActiveDownloadEntries", possibleDownloadEntries)
            .put("validPendingTranslateMarks", pendingTranslate.size).put("validPendingNightMarks", pendingNight.size)
            .put("initializedSingletonWrappersRead", initializedWrappers)
            .put("uninitializedSingletonWrappersSkipped", uninitializedWrappers)
            .put("singletonCacheObservationIsSnapshotOnly", true)
            .put("singletonLazyGettersCalled", 0)
            .put("singletonFactoriesCalled", 0)
    }
}
