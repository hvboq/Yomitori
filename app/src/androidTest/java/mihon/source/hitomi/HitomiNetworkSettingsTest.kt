package mihon.source.hitomi

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.network.NetworkHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.Proxy

@RunWith(AndroidJUnit4::class)
class HitomiNetworkSettingsTest {
    @Test
    fun bypassDefaultsOnAndToggleChangesNewRequestsWithoutChangingGlobalClient() {
        val network = Injekt.get<NetworkHelper>()
        val globalClient = network.client
        val source = HitomiSource(network)
        val preferences = source.getSourcePreferences()
        val wasSet = preferences.contains(HitomiSource.PREF_DPI_BYPASS)
        val previous = source.dpiBypassEnabled
        try {
            preferences.edit().remove(HitomiSource.PREF_DPI_BYPASS).commit()
            assertTrue(source.dpiBypassEnabled)
            val bypass = source.client
            assertEquals(Proxy.Type.SOCKS, bypass.proxy?.type())
            preferences.edit().putBoolean(HitomiSource.PREF_DPI_BYPASS, false).commit()
            val direct = source.client
            assertEquals(globalClient.proxy, direct.proxy)
            assertTrue(direct !== bypass)
            preferences.edit().putBoolean(HitomiSource.PREF_DPI_BYPASS, true).commit()
            assertSame(bypass, source.client)
            assertSame(globalClient, network.client)
        } finally {
            preferences.edit().apply {
                if (wasSet) putBoolean(HitomiSource.PREF_DPI_BYPASS, previous) else remove(HitomiSource.PREF_DPI_BYPASS)
            }.commit()
        }
    }
}
