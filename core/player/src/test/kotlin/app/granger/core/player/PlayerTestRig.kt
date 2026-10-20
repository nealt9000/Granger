package app.granger.core.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.test.utils.FakeClock
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.granger.core.library.Audiobook
import app.granger.core.library.AudiobookChapter
import app.granger.core.library.LibraryDatabase
import app.granger.core.library.MediaOrigin
import app.granger.core.library.PackStatus
import app.granger.core.library.RoomMediaLibrary
import app.granger.core.library.Track
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf

/**
 * The engine on a TestExoPlayer with an in-memory library, as in [PlayerEngineTest], for the session's tests.
 * Call [close] in `@After`.
 */
internal class PlayerTestRig {
    val context: Context = ApplicationProvider.getApplicationContext()
    val db: LibraryDatabase = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor { it.run() }
        .setTransactionExecutor { it.run() }
        .build()
    val media = RoomMediaLibrary(db)
    val playback = db.playbackDao()
    val prefs = context.getSharedPreferences("player-test", Context.MODE_PRIVATE)
    val clock = FakeClock(1_000_000_000L, true)
    private val engines = mutableListOf<PlayerEngine>()

    fun close() {
        engines.forEach { it.release(save = false) }
        db.close()
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    fun engine(): PlayerEngine {
        val player = TestExoPlayerBuilder(context).setClock(clock).setMediaSourceFactory(TestSources(context)).build()
        return PlayerEngine(
            player = player, media = media, playback = playback, prefs = prefs, focus = FocusPolicy(FakeFocusRequester()),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate), writer = Dispatchers.Unconfined,
            random = Random(7),
        ).also { engines += it }
    }

    fun fixture(name: String): String = Uri.fromFile(File(javaClass.classLoader!!.getResource(name)!!.toURI())).toString()

    fun track(ref: String, uri: String, durationMs: Long, audiobookId: String? = null) = Track(
        id = "track:phone:$ref", origin = MediaOrigin.PHONE, uri = uri, folder = "Music", title = ref, album = "Album",
        albumKey = "artist|album", albumArtists = "Artist", artists = "Artist", composers = "", genres = "",
        disc = 1, trackNo = 1, date = "", originalDate = "", releaseType = "", sortTitle = "", sortAlbum = "",
        sortArtist = "", sortAlbumArtist = "", durationMs = durationMs, rgTrackGain = null, rgTrackPeak = null,
        rgAlbumGain = null, rgAlbumPeak = null, coverPath = null, audiobookId = audiobookId, status = PackStatus.OK,
        addedAt = 0L,
    )

    /** Five synthetic 60 s songs; their ids in order. */
    fun songs(): List<String> {
        val tracks = (0 until 5).map { track("song$it", "fake://60000/song$it", 60_000) }
        runBlocking { db.mediaDao().upsertTracks(tracks) }
        return tracks.map { it.id }
    }

    /** chapters.mp3 as a one-file book with three 1 s chapters ("One", "Two", "Three"). */
    fun chapterBook(): Audiobook {
        val id = "audiobook:phone:chapters"
        val file = track("chapters-file", fixture("chapters.mp3"), 3_000, audiobookId = id)
        val chapters = listOf("One", "Two", "Three").mapIndexed { i, title -> AudiobookChapter(id, i, title, file.id, i * 1_000L, 1_000L) }
        return saveBook(id, "chapters", listOf(file), chapters)
    }

    /** A synthetic book with one chapter per file. */
    fun fakeBook(ref: String, vararg fileMs: Long): Audiobook {
        val id = "audiobook:phone:$ref"
        val files = fileMs.mapIndexed { i, ms -> track("$ref-$i", "fake://$ms/$ref-$i", ms, audiobookId = id) }
        val chapters = files.mapIndexed { i, t -> AudiobookChapter(id, i, "Chapter ${i + 1}", t.id, 0L, t.durationMs) }
        return saveBook(id, ref, files, chapters)
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
}
