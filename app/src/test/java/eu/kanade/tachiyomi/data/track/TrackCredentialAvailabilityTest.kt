package eu.kanade.tachiyomi.data.track

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TrackCredentialAvailabilityTest {
    private val credentialTrackerIds = listOf(
        TrackerManager.KITSU,
        TrackerManager.SHIKIMORI,
        TrackerManager.BANGUMI,
        TrackerManager.HIKKA,
    )

    @Test
    fun `credential-free build excludes only the four optional providers in original order`() {
        val availability = TrackCredentialAvailability(credentialTrackerIds.associateWith { "" to "" })
        val originalIds = (1L..11L).toList()

        originalIds.filter(availability::isAvailable) shouldBe listOf(1L, 2L, 6L, 7L, 8L, 9L, 11L)
        originalIds shouldBe (1L..11L).toList()
        availability.isAvailable(99L) shouldBe true
    }

    @Test
    fun `each provider requires both nonblank application credentials`() {
        for (trackerId in credentialTrackerIds) {
            for (credentials in listOf("" to "fixture-secret", "fixture-id" to "", " \t" to "fixture-secret")) {
                TrackCredentialAvailability(mapOf(trackerId to credentials)).isAvailable(trackerId) shouldBe false
            }
            TrackCredentialAvailability(mapOf(trackerId to ("fixture-id" to "\r\n")))
                .isAvailable(trackerId) shouldBe false
            TrackCredentialAvailability(mapOf(trackerId to ("fixture-id" to "fixture-secret")))
                .isAvailable(trackerId) shouldBe true
        }
    }

    @Test
    fun `one configured provider does not enable other missing providers`() {
        val credentials = credentialTrackerIds.associateWith { "" to "" }.toMutableMap()
        credentials[TrackerManager.HIKKA] = "fixture-reference" to "fixture-secret"
        val availability = TrackCredentialAvailability(credentials)

        credentialTrackerIds.filter(availability::isAvailable) shouldBe listOf(TrackerManager.HIKKA)
    }
}
