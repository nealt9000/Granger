package app.granger.core.player

import android.content.SharedPreferences
import app.granger.core.library.AudiobookState
import app.granger.core.library.MediaLibrary
import app.granger.core.library.MusicState
import app.granger.core.library.PlaybackDao
import app.granger.core.library.Track
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/** A saved queue rebuilt against the library: the queue and its tracks by id. */
internal class RestoredQueue(val queue: MusicQueue, val tracks: Map<String, Track>)

/**
 * Rebuilds [state]'s queue with its tracks, or null when none of them is left. A row the queue names that has gone
 * (rows aren't deleted, so this is a damaged state) is dropped, and the queue then starts over at its first track.
 */
internal suspend fun restoreQueue(state: MusicState, random: Random, track: suspend (String) -> Track?): RestoredQueue? {
    var q = MusicQueue.fromState(state, random)
    val found = q.ids.distinct().associateWith { track(it) }
    if (found.values.any { it == null }) {
        val ids = q.ids.filter { found[it] != null }
        if (ids.isEmpty()) return null
        q = MusicQueue(ids, 0, random)
    }
    if (q.ids.isEmpty()) return null
    @Suppress("UNCHECKED_CAST")
    return RestoredQueue(q, found.filterValues { it != null } as Map<String, Track>)
}

/**
 * The book the audiobook mode would resume: [PlayerSettingsKeys.LAST_AUDIOBOOK], else the most recently saved
 * [states] row; the first of them the library still holds with chapters.
 */
internal suspend fun lastAudiobookId(prefs: SharedPreferences, states: List<AudiobookState>, media: MediaLibrary): String? {
    val candidates = (listOfNotNull(prefs.getString(PlayerSettingsKeys.LAST_AUDIOBOOK, null)) +
        states.sortedByDescending { it.updatedAt }.map { it.audiobookId }).distinct()
    return candidates.firstOrNull { media.audiobook(it) != null && media.chapters(it).first().isNotEmpty() }
}

/**
 * Both modes' saved states as [SavedModes], read without touching the player (spec §8). It follows the db: every save
 * the engine writes (and the book page's finished mark) re-emits, as do the engine's last-mode prefs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SavedModesReader(
    private val media: MediaLibrary,
    private val playback: PlaybackDao,
    private val prefs: SharedPreferences,
) {
    // The last queue read, so a position-only save (every 15 s while music plays) doesn't re-read every track.
    private var cachedQueue: String? = null
    private var cachedTracks: Map<String, Track> = emptyMap()

    fun flow(): Flow<SavedModes> {
        val music = playback.observeMusicState().distinctUntilChanged().mapLatest { it?.let { s -> music(s) } }
        val book = combine(prefChanges(), playback.observeAudiobookStates()) { _, states -> states }
            .conflate()
            .mapLatest { states -> book(states) }
            .distinctUntilChanged()
        val lastMode = prefChanges().map {
            when (prefs.getString(PlayerSettingsKeys.LAST_MODE, null)) {
                MODE_MUSIC -> PlayerMode.MUSIC
                MODE_BOOK -> PlayerMode.AUDIOBOOK
                else -> null
            }
        }.distinctUntilChanged()
        return combine(music, book, lastMode) { m, b, last -> SavedModes(m, b, last) }.distinctUntilChanged()
    }

    private suspend fun music(state: MusicState): SavedMusic? {
        val tracks = if (state.queue == cachedQueue) {
            cachedTracks
        } else {
            val ids = if (state.queue.isEmpty()) emptyList() else state.queue.split("\n")
            ids.distinct().mapNotNull { media.track(it) }.associateBy { it.id }.also {
                cachedQueue = state.queue
                cachedTracks = it
            }
        }
        val restored = restoreQueue(state, Random(0)) { tracks[it] } ?: return null
        val q = restored.queue
        val order = q.playOrder
        return SavedMusic(
            queue = order.map { restored.tracks.getValue(q.ids[it]) },
            index = order.indexOf(q.index),
            positionMs = state.positionMs,
            shuffle = q.shuffle,
            repeat = q.repeat,
        )
    }

    private suspend fun book(states: List<AudiobookState>): SavedBook? {
        val id = lastAudiobookId(prefs, states, media) ?: return null
        val book = media.audiobook(id) ?: return null
        val chapters = media.chapters(id).first().sortedBy { it.ordinal }.ifEmpty { return null }
        val map = ChapterMap(chapters)
        val state = states.firstOrNull { it.audiobookId == id }
        val file = state?.let { map.files.indexOf(it.trackId) }?.takeIf { it >= 0 }
        val chapter = if (file == null) chapters.first() else map.chapterAt(file, state.positionMs)
        val inChapter = if (file == null) 0L else map.inChapter(file, state.positionMs)
        return SavedBook(
            book, chapters, chapter, inChapter,
            speed = state?.speed ?: 1f,
            skipSilence = state?.skipSilence ?: false,
            volumeBoostMb = state?.volumeBoostMb ?: 0,
            finished = state?.finished ?: false,
        )
    }

    /** Emits once now, then whenever the engine's last-mode or last-book pref changes. */
    private fun prefChanges(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == PlayerSettingsKeys.LAST_MODE || key == PlayerSettingsKeys.LAST_AUDIOBOOK) trySend(Unit)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate()

    internal companion object {
        // As the engine writes them.
        const val MODE_MUSIC = "music"
        const val MODE_BOOK = "audiobook"
    }
}
