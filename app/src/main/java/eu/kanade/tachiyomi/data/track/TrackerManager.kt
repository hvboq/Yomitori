package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.hikka.Hikka
import eu.kanade.tachiyomi.data.track.kavita.Kavita
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.komga.Komga
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBaka
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.data.track.suwayomi.Suwayomi
import kotlinx.coroutines.flow.combine

class TrackerManager {

    companion object {
        const val ANILIST = 2L
        const val KITSU = 3L
        const val SHIKIMORI = 4L
        const val BANGUMI = 5L
        const val KAVITA = 8L
        const val HIKKA = 10L
        const val MANGABAKA = 11L
    }

    val myAnimeList = MyAnimeList(1L)
    val aniList = Anilist(ANILIST)
    val kitsu = Kitsu(KITSU)
    val shikimori = Shikimori(SHIKIMORI)
    val bangumi = Bangumi(BANGUMI)
    val komga = Komga(6L)
    val mangaUpdates = MangaUpdates(7L)
    val kavita = Kavita(KAVITA)
    val suwayomi = Suwayomi(9L)
    val hikka = Hikka(HIKKA)
    val mangaBaka = MangaBaka(MANGABAKA)

    private val allTrackers = listOf(
        myAnimeList,
        aniList,
        kitsu,
        shikimori,
        bangumi,
        komga,
        mangaUpdates,
        kavita,
        suwayomi,
        hikka,
        mangaBaka,
    )

    // Keep the full registry for saved records and backup validation, even when login is unavailable.
    val trackers = allTrackers.filter { isAvailable(it.id) }

    fun isAvailable(id: Long): Boolean = TrackCredentialAvailability.current.isAvailable(id)

    fun loggedInTrackers() = trackers.filter { it.isLoggedIn }

    fun loggedInTrackersFlow() = combine(trackers.map { it.isLoggedInFlow }) {
        it.mapIndexedNotNull { index, isLoggedIn ->
            if (isLoggedIn) trackers[index] else null
        }
    }

    fun get(id: Long) = allTrackers.find { it.id == id }

    fun getAll(ids: Set<Long>) = allTrackers.filter { it.id in ids }
}
