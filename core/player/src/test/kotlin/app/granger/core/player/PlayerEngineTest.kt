package app.granger.core.player

import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.FakeMediaSource
import androidx.media3.test.utils.FakeTimeline
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper.run
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.granger.core.library.AudioBookmark
import app.granger.core.library.Audiobook
import app.granger.core.library.AudiobookChapter
import app.granger.core.library.AudiobookState
import app.granger.core.library.LibraryDatabase
import app.granger.core.library.MediaOrigin
import app.granger.core.library.PackStatus
import app.granger.core.library.RoomMediaLibrary
import app.granger.core.library.Track
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The engine on a TestExoPlayer: FakeClock time, fake renderers (Robolectric has no codecs), and either the real
 * fixtures from testdata/audio (`file://`, read by the real extractors) or long synthetic files (`fake://<ms>/<name>`,
 * a FakeMediaSource) where a test needs more than the fixtures' 2.5–3 s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlayerEngineTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Direct executors: every DAO call completes on the calling (main) thread, so a save is in the db at once.
    private val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()
    private val media = RoomMediaLibrary(db)
    private val playback = db.playbackDao()
    private val prefs = context.getSharedPreferences("player-test", Context.MODE_PRIVATE)
    private val clock = FakeClock(1_000_000_000L, true)
    private val focus = FakeFocusRequester()
    private val engines = mutableListOf<PlayerEngine>()

    @After fun tearDown() {
        engines.forEach { it.release(save = false) }
        db.close()
    }

    // ---- fixtures and helpers ----

    private fun fixture(name: String): String =
        Uri.fromFile(File(javaClass.classLoader!!.getResource(name)!!.toURI())).toString()

    private fun track(ref: String, uri: String, durationMs: Long, audiobookId: String? = null, album: String = "Album") = Track(
        id = "track:phone:$ref", origin = MediaOrigin.PHONE, uri = uri, folder = "Music", title = ref, album = album,
        albumKey = "artist|${album.lowercase()}", albumArtists = "Artist", artists = "Artist", composers = "", genres = "",
        disc = 1, trackNo = 1, date = "", originalDate = "", releaseType = "", sortTitle = "", sortAlbum = "",
        sortArtist = "", sortAlbumArtist = "", durationMs = durationMs, rgTrackGain = null, rgTrackPeak = null,
        rgAlbumGain = null, rgAlbumPeak = null, coverPath = null, audiobookId = audiobookId, status = PackStatus.OK,
        addedAt = 0L,
    )

    /** Five real fixture files as music tracks; returns their ids in order. */
    private fun musicFixtures(): List<String> {
        val tracks = listOf(
            track("g1", fixture("gapless-1.mp3"), 2_500),
            track("g2", fixture("gapless-2.mp3"), 2_500),
            track("tagged", fixture("tagged.mp3"), 2_500),
            track("untagged", fixture("untagged.mp3"), 2_500),
            track("chapters", fixture("chapters.mp3"), 3_000),
        )
        runBlocking { db.mediaDao().upsertTracks(tracks) }
        return tracks.map { it.id }
    }

    /** A book of synthetic files, one chapter per file. */
    private fun fakeBook(ref: String, vararg fileMs: Long): Audiobook {
        val id = "audiobook:phone:$ref"
        val files = fileMs.mapIndexed { i, ms -> track("$ref-$i", "fake://$ms/$ref-$i", ms, audiobookId = id) }
        val chapters = files.mapIndexed { i, t -> AudiobookChapter(id, i, "Chapter ${i + 1}", t.id, 0L, t.durationMs) }
        return saveBook(id, ref, files, chapters)
    }

    /** chapters.mp3 as a one-file book with its three 1 s chapters. */
    private fun chapterBook(): Audiobook {
        val id = "audiobook:phone:chapters"
        val file = track("chapters-file", fixture("chapters.mp3"), 3_000, audiobookId = id)
        val chapters = listOf("One", "Two", "Three").mapIndexed { i, title ->
            AudiobookChapter(id, i, title, file.id, i * 1_000L, 1_000L)
        }
        return saveBook(id, "chapters", listOf(file), chapters)
    }

    private fun saveBook(id: String, title: String, files: List<Track>, chapters: List<AudiobookChapter>): Audiobook {
        val book = Audiobook(id, MediaOrigin.PHONE, title, title, "Author", "", "", null, files.sumOf { it.durationMs }, PackStatus.OK, 0L)
        runBlocking {
            db.mediaDao().upsertTracks(files)
            db.mediaDao().upsertAudiobooks(listOf(book))
            db.mediaDao().insertChapters(chapters)
        }
        return book
    }

    private fun newEngine(replayGain: ReplayGainProcessor? = null): PlayerEngine {
        val player = TestExoPlayerBuilder(context).setClock(clock).setMediaSourceFactory(TestSources(context)).build()
        return PlayerEngine(
            player = player, media = media, playback = playback, prefs = prefs, focus = FocusPolicy(focus),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), writer = Dispatchers.Unconfined,
            random = Random(7), replayGain = replayGain,
        ).also { engines += it }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /**
     * A position read on the app thread can trail the playback thread by one 10 ms work step (ExoPlayer's estimate at
     * the moment of a pause or stop), so saved positions are compared within that.
     */
    private fun assertNear(actual: Long, expected: Long) =
        assertThat(actual).isIn(com.google.common.collect.Range.closed(expected - 20, expected + 20))

    private fun bookState(id: String) = runBlocking { playback.audiobookState(id) }

    private val ExoPlayer.mediaIds get() = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }

    /** The item ids in the order ExoPlayer will play them (its shuffle order when shuffle is on). */
    private val ExoPlayer.playedIds: List<String>
        get() {
            val t = currentTimeline
            val out = mutableListOf<String>()
            var i = t.getFirstWindowIndex(shuffleModeEnabled)
            while (i != C.INDEX_UNSET) {
                out += getMediaItemAt(i).mediaId
                i = t.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
            }
            return out
        }

    // ---- the plan's tests ----

    @Test fun audiobookPositionSavedOnPauseAndEveryFiveSeconds() {
        val book = fakeBook("long", 60_000)
        val engine = newEngine()
        engine.playAudiobook(book.id)
        idle()

        run(engine.player).untilPosition(0, 7_000)
        val first = bookState(book.id)!!.positionMs
        assertThat(first).isAtLeast(4_000L)          // a tick save at about 5 s
        assertThat(first).isAtMost(7_000L)

        run(engine.player).untilPosition(0, 12_000)
        assertThat(bookState(book.id)!!.positionMs).isAtLeast(7_000L)   // the next one, about 5 s later

        engine.playPause()
        idle()
        assertThat(engine.player.playWhenReady).isFalse()
        assertThat(bookState(book.id)!!.positionMs).isEqualTo(engine.player.currentPosition)
    }

    @Test fun restoresAudiobookAfterServiceRecreated() {
        val book = fakeBook("hobbit", 30_000, 30_000)
        val first = newEngine()
        first.playAudiobook(book.id)
        idle()
        run(first.player).untilPosition(1, 8_000)
        first.release(save = false)                  // killed: no pause, no stop save
        val saved = bookState(book.id)!!
        assertThat(saved.trackId).isEqualTo("track:phone:hobbit-1")
        assertThat(8_000L - saved.positionMs).isAtMost(5_000L)

        val second = newEngine()
        second.restore()
        idle()
        run(second.player).untilState(Player.STATE_READY)
        val now = second.ui.value.now as NowPlaying.Book
        assertThat(now.book.id).isEqualTo(book.id)
        assertThat(now.chapter.ordinal).isEqualTo(1)
        assertThat(second.player.playWhenReady).isFalse()
        assertThat(second.player.currentMediaItemIndex).isEqualTo(1)
        assertThat(second.player.currentPosition).isEqualTo(saved.positionMs)

        clock.advanceTime(120_000)                  // two minutes away: auto-rewind 3 s on resume
        second.playPause()
        idle()
        assertThat(second.player.playWhenReady).isTrue()
        assertThat(second.player.currentPosition).isEqualTo(saved.positionMs - 3_000)
    }

    @Test fun switchingToMusicAndBackLosesNoAudiobookPosition() {
        val ids = musicFixtures()
        val book = fakeBook("dune", 60_000)
        val engine = newEngine()
        engine.playAudiobook(book.id)
        idle()
        run(engine.player).untilPosition(0, 12_345)

        engine.playTracks(ids, 0, false, PlaySource("album:x", "X"))
        idle()
        assertNear(bookState(book.id)!!.positionMs, 12_345L)
        assertThat(engine.ui.value.now).isInstanceOf(NowPlaying.Music::class.java)
        run(engine.player).untilPosition(1, 500)

        engine.playAudiobook(book.id)
        idle()
        assertThat(engine.player.currentMediaItemIndex).isEqualTo(0)
        assertThat(engine.player.currentPosition).isEqualTo(bookState(book.id)!!.positionMs)   // away under 10 s: no rewind
        assertThat(runBlocking { playback.musicState() }!!.index).isEqualTo(1)
    }

    @Test fun transientFocusLossPausesAndResumesWithRewind() {
        val book = fakeBook("call", 60_000)
        val engine = newEngine()
        engine.playAudiobook(book.id)
        idle()
        run(engine.player).untilPosition(0, 20_000)

        focus.change(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertThat(engine.player.playWhenReady).isFalse()
        val paused = bookState(book.id)!!.positionMs
        assertNear(paused, 20_000L)

        clock.advanceTime(60_000)
        focus.change(AudioManager.AUDIOFOCUS_GAIN)
        assertThat(engine.player.playWhenReady).isTrue()
        assertThat(engine.player.currentPosition).isEqualTo(paused - 3_000)
    }

    @Test fun permanentFocusLossStaysPaused() {
        val book = fakeBook("other", 60_000)
        val engine = newEngine()
        engine.playAudiobook(book.id)
        idle()
        run(engine.player).untilPosition(0, 5_000)

        focus.change(AudioManager.AUDIOFOCUS_LOSS)
        assertThat(engine.player.playWhenReady).isFalse()
        assertThat(focus.held).isFalse()
        focus.change(AudioManager.AUDIOFOCUS_GAIN)
        idle()
        assertThat(engine.player.playWhenReady).isFalse()
        assertNear(bookState(book.id)!!.positionMs, 5_000L)
    }

    @Test fun queueIsOnePlaylist() {
        val ids = musicFixtures()
        val engine = newEngine()
        engine.playTracks(ids, 2, false, PlaySource("songs", "Songs"))
        idle()
        assertThat(engine.player.mediaItemCount).isEqualTo(ids.size)
        assertThat(engine.player.mediaIds).isEqualTo(ids)
        assertThat(engine.player.currentMediaItemIndex).isEqualTo(2)
        assertThat(engine.queue.value.map { it.id }).isEqualTo(ids)

        run(engine.player).untilStartOfMediaItem(3)   // advances inside the one playlist
        idle()
        assertThat(engine.player.mediaItemCount).isEqualTo(ids.size)
        assertThat((engine.ui.value.now as NowPlaying.Music).track.id).isEqualTo(ids[3])
    }

    @Test fun sleepTimerPausesAtEndOfChapter() {
        val book = chapterBook()
        val engine = newEngine()
        engine.playAudiobook(book.id)
        idle()
        run(engine.player).untilState(Player.STATE_READY)
        engine.startSleepTimer(SleepSetting.EndOfChapter)
        idle()
        assertThat(engine.ui.value.sleepRemainingMs).isNotNull()

        run(engine.player).untilPlayWhenReadyIs(false)
        assertThat(engine.player.currentPosition).isIn(com.google.common.collect.Range.closed(1_000L, 1_300L))
        assertThat(engine.player.volume).isEqualTo(1f)
        assertThat(engine.ui.value.sleepRemainingMs).isNull()
        assertThat(bookState(book.id)!!.positionMs).isEqualTo(engine.player.currentPosition)
    }

    @Test fun recentPlayWrittenOnStart() {
        val ids = musicFixtures()
        val book = fakeBook("recent", 10_000)
        val engine = newEngine()
        engine.playTracks(ids, 0, false, PlaySource("album:artist|album", "Album"))
        idle()
        engine.playAudiobook(book.id)
        idle()
        val plays = runBlocking { playback.observeRecentPlays(10).first() }
        assertThat(plays.map { it.key to it.title }).containsExactly(book.id to "recent", "album:artist|album" to "Album")
        plays.forEach { assertThat(it.playedAt).isIn(com.google.common.collect.Range.closed(1_000_000_000L, clock.currentTimeMillis())) }
    }

    // ---- pins for this design ----

    /** A save that comes before the restored seek has landed writes nothing, so it can't put 0 over the real position. */
    @Test fun noSaveBeforeTheRestoreSeekCompletes() {
        val book = fakeBook("guard", 60_000)
        val first = newEngine()
        first.playAudiobook(book.id)
        idle()
        run(first.player).untilPosition(0, 9_000)
        first.playPause()
        idle()
        first.release(save = false)
        val saved = bookState(book.id)!!
        assertNear(saved.positionMs, 9_000L)

        clock.advanceTime(5_000)
        val second = newEngine()
        second.restore()
        second.release(save = true)                 // destroyed before the restore is ready: STOP must not write
        idle()
        assertThat(bookState(book.id)).isEqualTo(saved)
    }

    @Test fun nextUnderRepeatOneAdvancesButAutoAdvanceRepeats() {
        val ids = musicFixtures()
        val engine = newEngine()
        engine.playTracks(ids, 0, false, PlaySource("songs", "Songs"))
        idle()
        engine.cycleRepeat(); engine.cycleRepeat()   // off → all → one
        idle()
        assertThat((engine.ui.value.now as NowPlaying.Music).repeat).isEqualTo(2)
        assertThat(engine.player.repeatMode).isEqualTo(Player.REPEAT_MODE_ONE)

        engine.next()
        idle()
        assertThat(engine.player.currentMediaItemIndex).isEqualTo(1)

        run(engine.player).untilPositionDiscontinuityWithReason(Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        assertThat(engine.player.currentMediaItemIndex).isEqualTo(1)
    }

    @Test fun exoPlayerShuffleOrderMatchesTheQueue() {
        val ids = musicFixtures()
        val engine = newEngine()
        engine.playTracks(ids, 3, true, PlaySource("songs", "Songs"))
        idle()
        assertThat(engine.player.shuffleModeEnabled).isTrue()
        assertThat(engine.player.mediaIds).isEqualTo(ids)      // items stay in original order
        assertThat(engine.player.playedIds).isEqualTo(engine.queue.value.map { it.id })
        assertThat(engine.player.playedIds.first()).isEqualTo(ids[3])

        engine.setShuffle(false)
        idle()
        assertThat(engine.player.playedIds).isEqualTo(ids)
        assertThat(engine.player.currentMediaItemIndex).isEqualTo(3)
    }

    @Test fun queueEditsDoNotRebuildThePlaylist() {
        val ids = musicFixtures()
        val extra = listOf(track("x", fixture("tagged.flac"), 2_500), track("y", fixture("tagged.ogg"), 2_500))
        runBlocking { db.mediaDao().upsertTracks(extra) }
        val engine = newEngine()
        engine.playTracks(ids, 0, true, PlaySource("songs", "Songs"))
        idle()
        run(engine.player).untilPosition(0, 1_000)
        // Paused for the edits: idling the looper while the run helper holds the playback thread would let the fake
        // clock race ahead and trip ExoPlayer's stuck-player check.
        engine.playPause(); idle()
        val current = engine.player.currentMediaItem!!.mediaId
        val position = engine.player.currentPosition
        val transitions = mutableListOf<Int>()
        engine.player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { transitions += reason }
        })

        engine.moveInQueue(1, 3); idle()
        engine.removeFromQueue(2); idle()
        engine.playNext(listOf(extra[0].id)); idle()
        engine.addToQueue(listOf(extra[1].id)); idle()

        assertThat(transitions).isEmpty()
        assertThat(engine.player.currentMediaItem!!.mediaId).isEqualTo(current)
        assertThat(engine.player.currentPosition).isEqualTo(position)
        assertThat(engine.player.playedIds).isEqualTo(engine.queue.value.map { it.id })
        assertThat(engine.queue.value.map { it.id }[1]).isEqualTo(extra[0].id)
        assertThat(engine.queue.value.last().id).isEqualTo(extra[1].id)
        val saved = runBlocking { playback.musicState() }!!
        assertThat(MusicQueue.fromState(saved, Random(1)).playOrder.map { saved.queue.split("\n")[it] })
            .isEqualTo(engine.queue.value.map { it.id })
    }

    @Test fun bookmarkAndChapterSeekUseTheChapterMap() {
        val book = chapterBook()
        val engine = newEngine()
        engine.playAudiobook(book.id)
        idle()
        run(engine.player).untilState(Player.STATE_READY)
        engine.seekToChapter(2)
        idle()
        assertThat(engine.player.currentPosition).isEqualTo(2_000L)
        assertThat((engine.ui.value.now as NowPlaying.Book).chapter.title).isEqualTo("Three")
        assertThat(engine.ui.value.positionMs).isEqualTo(0L)       // chapter-relative
        assertThat(engine.address()).isEqualTo("${book.id}@2/0")

        val id = runBlocking { engine.addBookmark("Here") }
        val mark = runBlocking { playback.observeBookmarks(book.id).first() }.single()
        assertThat(mark.id).isEqualTo(id)
        assertThat(mark.positionMs).isEqualTo(2_000L)

        engine.seekToChapter(0)
        idle()
        engine.playAudiobook(book.id, AudioBookmark(mark.id, book.id, mark.trackId, 2_000L, "Here", "", 0L))
        idle()
        assertThat(engine.player.currentPosition).isEqualTo(2_000L)
    }

    // ---- finished books (Task 11) ----

    @Test fun finishedBookResumesFromTheStartAndIsUnfinishedOncePlaying() {
        val book = fakeBook("done", 30_000, 30_000)
        runBlocking {
            playback.upsertAudiobookState(AudiobookState(book.id, "track:phone:done-1", 30_000L, 1.5f, false, 0, finished = true, updatedAt = 5L))
        }
        val engine = newEngine()
        engine.playAudiobook(book.id)
        idle()
        run(engine.player).untilState(Player.STATE_READY)
        idle()
        assertThat(engine.player.currentMediaItemIndex).isEqualTo(0)
        assertThat(engine.player.currentPosition).isAtMost(20L)
        val saved = bookState(book.id)!!
        assertThat(saved.finished).isFalse()
        assertThat(saved.trackId).isEqualTo("track:phone:done-0")
        assertThat(saved.speed).isEqualTo(1.5f)                  // its own settings are kept
    }

    @Test fun finishedMarkOnTheLoadedBookSurvivesItsNextSave() {
        val book = fakeBook("marked", 3_000, 3_000)
        val engine = newEngine()
        engine.playAudiobook(book.id)
        idle()
        run(engine.player).untilPosition(0, 1_000)

        // The book page writes the row, then tells the hub; the engine's next save keeps the mark.
        runBlocking { playback.upsertAudiobookState(bookState(book.id)!!.copy(finished = true)) }
        engine.setFinished(book.id, true)
        idle()
        engine.playPause()
        idle()
        assertThat(bookState(book.id)!!.finished).isTrue()

        // Played to the end it's finished; marked unfinished there, the loaded book goes back to its start.
        engine.setFinished(book.id, false)
        engine.playPause()
        idle()
        run(engine.player).untilState(Player.STATE_ENDED)
        idle()
        assertThat(bookState(book.id)!!.finished).isTrue()
        engine.setFinished(book.id, false)
        idle()
        assertThat(engine.player.currentMediaItemIndex).isEqualTo(0)
        assertThat(engine.player.currentPosition).isEqualTo(0L)
        val saved = bookState(book.id)!!
        assertThat(saved.finished).isFalse()
        assertThat(saved.trackId).isEqualTo("track:phone:marked-0")

        // Another book's mark leaves the loaded one alone.
        engine.setFinished("audiobook:phone:other", true)
        idle()
        assertThat(bookState(book.id)!!.finished).isFalse()
    }

    /** Restored (not played), a finished book stays finished through its next saves; playing it un-finishes it. */
    @Test fun restoredFinishedBookStaysFinishedUntilPlayed() {
        val ids = musicFixtures()
        val book = fakeBook("restored-done", 30_000, 30_000)
        runBlocking {
            playback.upsertAudiobookState(AudiobookState(book.id, "track:phone:restored-done-1", 30_000L, 1f, false, 0, finished = true, updatedAt = 5L))
        }
        prefs.edit().putString(PlayerSettingsKeys.LAST_MODE, "audiobook").putString(PlayerSettingsKeys.LAST_AUDIOBOOK, book.id).commit()

        val first = newEngine()
        first.restore()
        idle()
        run(first.player).untilState(Player.STATE_READY)
        idle()
        first.saveNow()                                  // swiped away
        assertThat(bookState(book.id)!!.finished).isTrue()

        // Music started from the restored book: the STOP save keeps the mark.
        first.playTracks(ids, 0, false, PlaySource("songs", "Songs"))
        idle()
        assertThat(bookState(book.id)!!.finished).isTrue()
        first.release(save = false)

        // Restored again and played: no longer finished.
        prefs.edit().putString(PlayerSettingsKeys.LAST_MODE, "audiobook").commit()
        val second = newEngine()
        second.restore()
        idle()
        run(second.player).untilState(Player.STATE_READY)
        idle()
        second.playPause()
        idle()
        run(second.player).untilPosition(0, 1_000)
        second.playPause()
        idle()
        val saved = bookState(book.id)!!
        assertThat(saved.finished).isFalse()
        assertThat(saved.trackId).isEqualTo("track:phone:restored-done-0")
    }

    @Test fun replayGainModeChangeAppliesToThePlayingQueue() {
        val ids = musicFixtures()
        runBlocking { db.mediaDao().upsertTracks(listOf(media.track(ids[0])!!.copy(rgTrackGain = -6.0206f, rgTrackPeak = null))) }
        val rg = ReplayGainProcessor()
        val engine = newEngine(rg)
        engine.playTracks(ids, 0, false, PlaySource("songs", "Songs"))
        idle()
        assertThat(rg.gainOf(ids[0])).isWithin(0.01f).of(0.5f)

        prefs.edit().putString(PlayerSettingsKeys.REPLAYGAIN_MODE, ReplayGainMode.OFF.name).commit()
        idle()
        assertThat(rg.gainOf(ids[0])).isEqualTo(1f)

        prefs.edit().putString(PlayerSettingsKeys.REPLAYGAIN_MODE, ReplayGainMode.TRACK.name).commit()
        idle()
        assertThat(rg.gainOf(ids[0])).isWithin(0.01f).of(0.5f)
    }
}

/** Long synthetic files for `fake://<ms>/<name>`; everything else goes to the real extractors. */
private class TestSources(context: Context) : MediaSource.Factory {
    private val real = DefaultMediaSourceFactory(context)
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider) = apply { real.setDrmSessionManagerProvider(provider) }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy) = apply { real.setLoadErrorHandlingPolicy(policy) }
    override fun getSupportedTypes(): IntArray = real.supportedTypes
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val uri = mediaItem.localConfiguration!!.uri
        if (uri.scheme != "fake") return real.createMediaSource(mediaItem)
        val window = FakeTimeline.TimelineWindowDefinition.Builder()
            .setDurationUs(uri.host!!.toLong() * 1_000)
            .setWindowPositionInFirstPeriodUs(0)
            .setMediaItem(mediaItem)
            .build()
        return FakeMediaSource(FakeTimeline(window), AUDIO)
    }


    companion object {
        val AUDIO: Format = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setChannelCount(2).setSampleRate(44_100).build()
    }
}

/** Grants focus and lets the test deliver focus changes. */
class FakeFocusRequester : FocusRequester {
    var held = false
        private set
    private var onChange: ((Int) -> Unit)? = null

    override fun request(speech: Boolean, onChange: (Int) -> Unit): Boolean {
        this.onChange = onChange
        held = true
        return true
    }

    override fun abandon() {
        held = false
        onChange = null
    }

    fun change(focusChange: Int) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS) held = false
        onChange?.invoke(focusChange)
    }
}
