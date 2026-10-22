package app.granger.player

import app.granger.core.library.AudioBookmark
import app.granger.core.library.Audiobook
import app.granger.core.library.AudiobookChapter
import app.granger.core.library.MediaOrigin
import app.granger.core.library.PackStatus
import app.granger.core.library.Track
import app.granger.core.player.NowPlaying
import app.granger.core.player.PlaySource
import app.granger.core.player.PlayerHub
import app.granger.core.player.PlayerMode
import app.granger.core.player.SavedModes
import app.granger.core.player.PlayerUi
import app.granger.core.player.SleepSetting
import app.granger.core.player.voice.VoiceProgress
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadSource
import app.granger.core.speech.VoiceId
import kotlinx.coroutines.flow.MutableStateFlow

/** Records every call by name; [ui] and [queue] are set by the test. */
class FakePlayerHub(initial: PlayerUi = PlayerUi.IDLE) : PlayerHub {
    override val ui = MutableStateFlow(initial)
    override val queue = MutableStateFlow<List<Track>>(emptyList())
    override val saved = MutableStateFlow(SavedModes(null, null, null))
    val calls = mutableListOf<String>()

    override fun activate(mode: PlayerMode) { calls += "activate:$mode" }

    /** Every [playTracks] call with its arguments. */
    data class Play(val trackIds: List<String>, val startIndex: Int, val shuffle: Boolean, val source: PlaySource)
    val plays = mutableListOf<Play>()

    /** The ids handed to [playNext] and [addToQueue], call by call. */
    val nexts = mutableListOf<List<String>>()
    val queued = mutableListOf<List<String>>()

    override fun playTracks(trackIds: List<String>, startIndex: Int, shuffle: Boolean, source: PlaySource) {
        calls += "playTracks"
        plays += Play(trackIds, startIndex, shuffle, source)
    }
    /** Every [playAudiobook] call: the book and where it was asked to start (null: its saved position). */
    val bookPlays = mutableListOf<Pair<String, AudioBookmark?>>()

    override fun playAudiobook(id: String, at: AudioBookmark?) {
        calls += "playAudiobook:$id@${at?.positionMs}"
        bookPlays += id to at
    }
    override val voiceProgress = MutableStateFlow<VoiceProgress?>(null)
    override fun readAloud(source: ReadSource, from: ReadAddress?, voice: VoiceId?) { calls += "readAloud:${source.key}@${from?.encode()}" }
    override fun stopVoice() { calls += "stopVoice" }
    override fun nextParagraph() { calls += "nextParagraph" }
    override fun previousParagraph() { calls += "previousParagraph" }
    override fun nextSentence() { calls += "nextSentence" }
    override fun previousSentence() { calls += "previousSentence" }
    override fun jumpTo(address: ReadAddress) { calls += "jumpTo:${address.encode()}" }
    override fun setFinished(audiobookId: String, finished: Boolean) { calls += "setFinished:$audiobookId=$finished" }
    override fun unload(audiobookId: String, trackIds: Set<String>) { calls += "unload:$audiobookId" }
    override fun playPause() { calls += "playPause" }
    override fun next() { calls += "next" }
    override fun previous() { calls += "previous" }
    override fun skipBack() { calls += "skipBack" }
    override fun skipForward() { calls += "skipForward" }
    override fun seekTo(positionMs: Long) { calls += "seekTo:$positionMs" }
    override fun seekToChapter(ordinal: Int) { calls += "seekToChapter:$ordinal" }
    override fun setShuffle(on: Boolean) { calls += "shuffle:$on" }
    override fun cycleRepeat() { calls += "cycleRepeat" }
    override fun moveInQueue(from: Int, to: Int) { calls += "move:$from>$to" }
    override fun removeFromQueue(i: Int) { calls += "remove:$i" }
    override fun playQueueItem(position: Int) { calls += "playQueueItem:$position" }
    override fun playNext(trackIds: List<String>) { calls += "playNext"; nexts += trackIds }
    override fun addToQueue(trackIds: List<String>) { calls += "addToQueue"; queued += trackIds }
    override fun setSpeed(speed: Float) { calls += "speed:$speed" }
    override fun setSkipSilence(on: Boolean) { calls += "skipSilence:$on" }
    override fun setVolumeBoost(mb: Int) { calls += "boost:$mb" }
    override fun startSleepTimer(setting: SleepSetting) { calls += "sleep:$setting" }
    override fun cancelSleepTimer() { calls += "cancelSleep" }
    override suspend fun addBookmark(title: String): Long { calls += "bookmark:$title"; return 1L }
}

fun track(id: String, title: String = id, artists: String = "Artist", album: String = "Album") = Track(
    id = "track:phone:$id", origin = MediaOrigin.PHONE, uri = "content://media/$id", folder = "Music",
    title = title, album = album, albumKey = album, albumArtists = artists, artists = artists, composers = "",
    genres = "", disc = null, trackNo = null, date = "", originalDate = "", releaseType = "", sortTitle = title,
    sortAlbum = album, sortArtist = artists, sortAlbumArtist = artists, durationMs = 200_000L, rgTrackGain = null,
    rgTrackPeak = null, rgAlbumGain = null, rgAlbumPeak = null, coverPath = null, audiobookId = null,
    status = PackStatus.OK, addedAt = 0L,
)

fun audiobook(id: String, title: String = id, author: String = "Author", durationMs: Long = 0L) = Audiobook(
    id = "audiobook:phone:$id", origin = MediaOrigin.PHONE, sourceRef = id, title = title, author = author,
    narrator = "", description = "", coverPath = null, durationMs = durationMs, status = PackStatus.OK, addedAt = 0L,
)

fun chapter(book: Audiobook, ordinal: Int, durationMs: Long, title: String = "Chapter ${ordinal + 1}") =
    AudiobookChapter(book.id, ordinal, title, trackId = "track:phone:${book.sourceRef}-f", startMs = 0L, durationMs = durationMs)

fun musicUi(t: Track = track("a", "Song A"), positionMs: Long = 50_000L) = PlayerUi(
    NowPlaying.Music(t, index = 0, queueSize = 3, shuffle = false, repeat = 0),
    playing = true, positionMs = positionMs, durationMs = t.durationMs, sleepRemainingMs = null,
)

fun voiceUi(title: String = "Rosetta Stone", section: String? = null, playing: Boolean = true, ended: Boolean = false) = PlayerUi(
    NowPlaying.Voice(
        "pack:wikipedia_en_top/A/Rosetta_Stone", title, section, "It was found in 1799.", paragraph = 1, speed = 1f,
        voiceId = VoiceId.Granger("vits-piper-en_US-ljspeech-medium"), ended = ended,
    ),
    playing = playing, positionMs = 12_000L, durationMs = 0L, sleepRemainingMs = null,
)

fun bookUi(book: Audiobook, chapter: AudiobookChapter, chapterCount: Int, positionMs: Long = 0L) = PlayerUi(
    NowPlaying.Book(book, chapter, chapterCount, speed = 1f, skipSilence = false, volumeBoostMb = 0),
    playing = false, positionMs = positionMs, durationMs = chapter.durationMs, sleepRemainingMs = null,
)
