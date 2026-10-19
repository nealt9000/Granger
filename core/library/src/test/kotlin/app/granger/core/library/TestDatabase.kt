package app.granger.core.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider

fun inMemoryDatabase(): LibraryDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
        .addCallback(LibraryDatabase.SeedCallback)
        .allowMainThreadQueries()
        .build()

fun pack(id: String, title: String = id, status: PackStatus = PackStatus.OK) =
    Pack(id, "/packs/$id.zim", title, "", "eng", "2026-10-15", 1_000L, true, status, 0L)

fun book(ref: String, origin: BookOrigin = BookOrigin.GRANGER, title: String = ref, status: PackStatus = PackStatus.OK) = Book(
    id = BookIds.of(origin, ref), origin = origin, sourceRef = ref, filePath = "/books/$ref.epub", title = title,
    author = "", translator = "", date = "", language = "en", subjects = "", description = "", coverPath = null,
    sizeBytes = 1_000L, status = status, indexedAt = null, addedAt = 0L,
)

/** A phone music track; its album key is computed as the scanner does. */
fun musicTrack(
    ref: String,
    album: String = "Album",
    albumArtist: String = "Artist",
    folder: String = "Music",
    title: String = ref,
    disc: Int? = 1,
    trackNo: Int? = 1,
    date: String = "",
    coverPath: String? = null,
    durationMs: Long = 60_000L,
    audiobookId: String? = null,
    status: PackStatus = PackStatus.OK,
) = Track(
    id = "track:phone:$ref", origin = MediaOrigin.PHONE, uri = "content://media/$ref", folder = folder,
    title = title, album = album, albumKey = Albums.key(albumArtist, album, folder), albumArtists = albumArtist,
    artists = albumArtist, composers = "", genres = "", disc = disc, trackNo = trackNo, date = date, originalDate = "",
    releaseType = "", sortTitle = "", sortAlbum = "", sortArtist = "", sortAlbumArtist = "", durationMs = durationMs,
    rgTrackGain = null, rgTrackPeak = null, rgAlbumGain = null, rgAlbumPeak = null, coverPath = coverPath,
    audiobookId = audiobookId, status = status, addedAt = 0L,
)

fun phoneAudiobook(ref: String, title: String = ref) = Audiobook(
    id = "audiobook:phone:$ref", origin = MediaOrigin.PHONE, sourceRef = ref, title = title, author = "Author",
    narrator = "", description = "", coverPath = null, durationMs = 120_000L, status = PackStatus.OK, addedAt = 0L,
)
