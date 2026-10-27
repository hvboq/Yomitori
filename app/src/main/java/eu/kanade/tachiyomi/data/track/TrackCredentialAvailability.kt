package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.BuildConfig

/** Availability is a build capability, independent of the user's saved login and tracking records. */
internal class TrackCredentialAvailability(credentials: Map<Long, Pair<String, String>>) {
    private val unavailableIds = credentials
        .filterValues { (clientId, clientSecret) -> clientId.isBlank() || clientSecret.isBlank() }
        .keys

    fun isAvailable(trackerId: Long): Boolean = trackerId !in unavailableIds

    companion object {
        val current = TrackCredentialAvailability(
            mapOf(
                TrackerManager.BANGUMI to (BuildConfig.BANGUMI_CLIENT_ID to BuildConfig.BANGUMI_CLIENT_SECRET),
                TrackerManager.HIKKA to (BuildConfig.HIKKA_CLIENT_ID to BuildConfig.HIKKA_CLIENT_SECRET),
                TrackerManager.KITSU to (BuildConfig.KITSU_CLIENT_ID to BuildConfig.KITSU_CLIENT_SECRET),
                TrackerManager.SHIKIMORI to (BuildConfig.SHIKIMORI_CLIENT_ID to BuildConfig.SHIKIMORI_CLIENT_SECRET),
            ),
        )
    }
}
