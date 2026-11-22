@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.granger.shared

import app.granger.core.library.PackStatus
import app.granger.core.media.AudioSource
import app.granger.core.media.ChapterBytes
import app.granger.core.media.ChapterReader
import app.granger.core.media.FileTags
import app.granger.core.media.Fingerprint
import app.granger.core.media.FoundFile
import app.granger.core.media.MediaDisk
import app.granger.core.media.OpenedFile
import app.granger.core.media.PhoneFiles
import app.granger.core.media.PhoneMediaScan
import app.granger.core.media.TagReadGuard
import app.granger.core.player.PlayerSettingsKeys
import app.granger.core.player.ReplayGainMode
import app.granger.core.taglib.RawTags
import app.granger.core.text.NaturalOrder
import app.granger.music.MusicViewModel
import app.granger.player.PlayerFocus
import app.granger.player.PlayerViewModel
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.open
import platform.posix.pread

/**
 * One file's tags as Swift read them (Plan I6 Task 6): AVAsset's metadata, or TagLib's for an MP3, both turned into
 * TagLib's PropertyMap keys (TITLE, ARTIST, REPLAYGAIN_TRACK_GAIN…) so the shared [app.granger.core.media.TagParser]
 * reads them as it reads Android's. The cover crosses as `NSData`.
 */
class NativeTags(
    val properties: Map<String, List<String>>,
    val durationMs: Long,
    val sampleRate: Int,
    val bitrateKbps: Int,
    val channels: Int,
    val cover: NSData?,
) {
    fun raw(): RawTags = RawTags(properties, durationMs, sampleRate, bitrateKbps, channels, cover?.toByteArray()?.takeIf { it.isNotEmpty() })
}

/** A song in the Music library that can play here: on the phone and not protected (its asset URL isn't nil). */
class LibrarySong(val id: String, val url: String, val title: String, val addedMs: Long, val durationMs: Long, val audiobook: Boolean)

/** What the Music library holds: the songs that can play, and how many can't (protected, or only in the cloud). */
class LibrarySongs(val playable: List<LibrarySong>, val leftOut: Int)

/** A folder picked in Files (a security-scoped bookmark Swift resolved and holds open), read in place. */
class PickedFolder(val path: String, val name: String, val audiobooks: Boolean)

/** What Swift does for the music sources: AVAsset, TagLib and MPMediaLibrary. Every call may block; never on the main thread. */
interface MusicNatives {
    /** The tags of the file at [path]; null when they can't be parsed (the file is then named from its file name). */
    fun fileTags(path: String): NativeTags?

    /** Whether the phone can play [url] (a path, or a Music library URL): AVAudioFile opens it. */
    fun canPlay(url: String): Boolean

    /** Music library access is allowed. */
    fun libraryAllowed(): Boolean

    /** The Music library's songs; null when it can't be read. */
    fun librarySongs(): LibrarySongs?

    /** A Music library song's tags, from its item. */
    fun libraryTags(id: String): NativeTags?
}

/** Songs and audiobooks that play, after a scan (Android's ScanSummary). */
class ScanCounts(val songs: Int, val audiobooks: Int)

/**
 * Music on the iPhone (Plan I6 Task 6, call 2), wired as Android's GrangerApp wires its phone scan: three sources feed
 * the shared [PhoneMediaScan] and the library's [app.granger.core.library.RoomMediaLibrary].
 * - Files imported into Documents/Music ([musicDir]), shown in the Files app as Books and Packs are.
 * - Folders picked in Files, read in place through security-scoped bookmarks ([setFolders]; Swift holds them).
 * - The Music library's songs that aren't protected or cloud-only ([MusicNatives.librarySongs]); this needs its
 *   permission, as Android's media store needs the audio permission.
 * Covers go to [coversDir] (Application Support, not shown in Files). Music's screens get the shared
 * [MusicViewModel] and [PlayerViewModel] over [voice]'s hub.
 */
class IosMusic(
    private val library: IosLibrary,
    private val voice: IosVoice,
    private val natives: MusicNatives,
    val musicDir: String,
    private val coversDir: String,
    guardDir: String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val folders = MutableStateFlow<List<PickedFolder>>(emptyList())

    /** The Music library songs left out at the last scan (protected or cloud-only), for Media settings' line. */
    private val _leftOut = MutableStateFlow(0)
    val leftOut: StateFlow<Int> = _leftOut

    /** The app's one: which side the one-item views show while the voice has music under it. */
    val focus = PlayerFocus()

    private val librarySource = LibrarySource(natives) { _leftOut.value = it }

    private val scanner = PhoneMediaScan(
        sources = listOf(FolderTree(musicDir, MUSIC_LABEL, audiobooks = false), PickedFolders { folders.value }, librarySource),
        files = IosPhoneFiles(natives),
        media = library.media,
        playback = library.database.playbackDao(),
        coversDir = coversDir,
        hasPermission = { natives.libraryAllowed() },
        tagGuard = FileTagGuard(guardDir),
    )

    /** The picked folders Swift resolved (and holds open). A scan follows when they change. */
    fun setFolders(list: List<PickedFolder>) {
        folders.value = list
    }

    private val scanLock = kotlinx.coroutines.sync.Mutex()
    private var scans = 0

    /**
     * Scans the three sources now: what plays afterwards. Throws when it fails. A scan already running listed its files
     * before this was asked (an import just copied, a folder just picked), so it's joined and then run once more.
     */
    suspend fun scan(): ScanCounts {
        val busy = scanLock.withLock { (scans > 0).also { scans++ } }
        try {
            scanner.scan()
            if (busy) scanner.scan()
        } finally {
            scanLock.withLock { scans-- }
        }
        return counts()
    }

    /** Scans in the background, a failure only logged (start, foreground, the Music library changing). */
    fun scanSoon() {
        scope.launch { runCatching { scan() }.onFailure { library.logLine("Music scan failed: ${it.message}") } }
    }

    suspend fun counts(): ScanCounts = ScanCounts(
        songs = library.media.tracks.first().count { it.status == PackStatus.OK },
        audiobooks = library.media.audiobooks.first().count { it.status == PackStatus.OK },
    )

    /** Whether the phone holds an audiobook: Media settings then show the skip settings (Plan U2 Decision 7). */
    val hasAudiobooks: Flow<Boolean> = library.media.audiobooks.map { all -> all.any { it.status == PackStatus.OK } }

    /** Music's tabs and pages for [collectionId], or all music (null). */
    fun music(collectionId: String?): OwnedViewModel<MusicViewModel> = OwnedViewModel {
        MusicViewModel(collectionId, library.media, library.shelves, library.playlists, voice.hub, viewPrefs = library.shelfChoices)
    }

    /** The music player screen's model, with Add to playlist. */
    fun player(): OwnedViewModel<PlayerViewModel> = OwnedViewModel {
        val prefs = voice.playerPrefs
        PlayerViewModel(
            voice.hub,
            chapters = { library.media.chapters(it) },
            bookmarks = { library.database.playbackDao().observeBookmarks(it) },
            skipBackSeconds = { prefs.getInt(PlayerSettingsKeys.SKIP_BACK_S, DEFAULT_SKIP_S) },
            skipForwardSeconds = { prefs.getInt(PlayerSettingsKeys.SKIP_FORWARD_S, DEFAULT_SKIP_S) },
            audiobooks = hasAudiobooks,
            focus = focus,
            playlists = library.playlists,
        )
    }

    // ---- Media settings: the player's settings, in the hub's prefs (Android's PlayerSettingsKeys) ----

    fun replayGain(): ReplayGainMode =
        runCatching { ReplayGainMode.valueOf(voice.playerPrefs.getString(PlayerSettingsKeys.REPLAYGAIN_MODE) ?: "AUTO") }.getOrDefault(ReplayGainMode.AUTO)

    fun setReplayGain(mode: ReplayGainMode) = voice.playerPrefs.putString(PlayerSettingsKeys.REPLAYGAIN_MODE, mode.name)

    fun skipBackS(): Int = voice.playerPrefs.getInt(PlayerSettingsKeys.SKIP_BACK_S, DEFAULT_SKIP_S)
    fun skipForwardS(): Int = voice.playerPrefs.getInt(PlayerSettingsKeys.SKIP_FORWARD_S, DEFAULT_SKIP_S)
    fun setSkipBack(seconds: Int) = voice.playerPrefs.putLong(PlayerSettingsKeys.SKIP_BACK_S, seconds.toLong())
    fun setSkipForward(seconds: Int) = voice.playerPrefs.putLong(PlayerSettingsKeys.SKIP_FORWARD_S, seconds.toLong())

    companion object {
        /** Android's MediaSettings.SKIP_CHOICES and default. */
        val SKIP_CHOICES = listOf(10, 15, 30, 60)
        const val DEFAULT_SKIP_S = 30

        /** The folder imported files are filed under ("Music/…"), as the Files app names it. */
        const val MUSIC_LABEL = "Music"

        /** Music library songs' folder in Music's Folders tab. */
        const val LIBRARY_LABEL = "Music library"
    }
}

// ---- the sources ----

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "m4b", "aac", "flac", "ogg", "oga", "opus", "wav", "aiff", "aif", "aifc", "caf", "alac")
private val COVER_NAMES = listOf("folder.jpg", "cover.jpg")
private const val MAX_DEPTH = 32

/**
 * A folder's audio files, walked recursively as Android's FolderSource walks a picked tree: hidden entries skipped,
 * names in natural order, a `folder.jpg` or `cover.jpg` beside the files as their cover. [label] starts each file's
 * folder ("Music/Rock/"). A folder that isn't there lists empty (its items go MISSING); one that can't be read throws,
 * so the scan treats it as unknown.
 */
internal class FolderTree(private val root: String, private val label: String, private val audiobooks: Boolean) : AudioSource {
    private val prefix = MediaDisk.fileUri(root) + "/"

    override fun owns(uri: String): Boolean = uri.startsWith(prefix)

    override suspend fun list(): List<FoundFile> = withContext(Dispatchers.IO) {
        if (!MediaDisk.isDirectory(root)) return@withContext emptyList()
        buildList { walk(root, "$label/", 0) }
    }

    private fun MutableList<FoundFile>.walk(dir: String, folder: String, depth: Int) {
        if (depth > MAX_DEPTH) return
        val children = (MediaDisk.list(dir) ?: throw kotlinx.io.IOException("Can't list $dir"))
            .filterNot { MediaDisk.nameOf(it).startsWith('.') }
        val cover = COVER_NAMES.firstNotNullOfOrNull { name -> children.firstOrNull { MediaDisk.nameOf(it).equals(name, ignoreCase = true) } }
            ?.let(MediaDisk::fileUri)
        for (child in children.sortedWith(compareBy(NaturalOrder) { MediaDisk.nameOf(it) })) {
            val name = MediaDisk.nameOf(child)
            if (MediaDisk.isDirectory(child) && !MediaDisk.isLink(child)) {
                walk(child, "$folder$name/", depth + 1)
            } else if (name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS && MediaDisk.isFile(child)) {
                add(FoundFile(
                    uri = MediaDisk.fileUri(child), displayName = name, folder = folder,
                    sizeBytes = MediaDisk.length(child), modifiedMs = MediaDisk.lastModified(child), durationMs = 0L,
                    systemAudiobook = false, inAudiobookFolder = audiobooks, folderCoverUri = cover,
                ))
            }
        }
    }
}

private fun MediaDisk.nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

/** The picked folders, each a [parts] piece of its own (one that can't be read leaves only its rows alone). */
internal class PickedFolders(private val folders: () -> List<PickedFolder>) : AudioSource {
    private fun trees() = folders().map { FolderTree(it.path, it.name, it.audiobooks) }

    override suspend fun list(): List<FoundFile> = parts().flatMap { it.list() }

    /** Any file outside Documents/Music and the Music library: a folder removed from the list still owns its rows, so they go MISSING. */
    override fun owns(uri: String): Boolean = trees().any { it.owns(uri) }

    override suspend fun parts(): List<AudioSource> = trees()
}

/** The Music library's songs that can play, as Android's media store source lists the phone's music. */
internal class LibrarySource(private val natives: MusicNatives, private val leftOut: (Int) -> Unit) : AudioSource {
    override val needsAudioPermission: Boolean get() = true

    override fun owns(uri: String): Boolean = uri.startsWith(SCHEME)

    override suspend fun list(): List<FoundFile> {
        val songs = natives.librarySongs() ?: throw IllegalStateException("The Music library can't be read")
        leftOut(songs.leftOut)
        return songs.playable.map {
            // A name with no dot in it, so TagParser's fallback (a file name's stem) keeps the whole title.
            FoundFile(
                uri = it.url, displayName = it.title.replace('.', ' '), folder = "${IosMusic.LIBRARY_LABEL}/", sizeBytes = 0L,
                modifiedMs = it.addedMs, durationMs = it.durationMs, systemAudiobook = it.audiobook, inAudiobookFolder = false,
            )
        }
    }

    companion object {
        const val SCHEME = "ipod-library:"

        /** A library URL's persistent id (`ipod-library://item/item.m4a?id=123`). */
        fun idOf(uri: String): String = uri.substringAfter("id=", "").substringBefore('&')
    }
}

/**
 * The iPhone's files for the shared scanner. A file is fingerprinted from its two ends (posix `pread`), its tags read by
 * Swift and its chapters by the shared [ChapterReader]. A file the phone can't play (Ogg Vorbis: iOS has no decoder for
 * it) is left out, as one that can't be opened. A Music library song can't be read as bytes: its lasting id stands in
 * for the fingerprint, and its tags come from its item.
 */
internal class IosPhoneFiles(private val natives: MusicNatives) : PhoneFiles {
    override fun <T> open(file: FoundFile, use: (OpenedFile) -> T): T? {
        if (file.uri.startsWith(LibrarySource.SCHEME)) {
            val id = LibrarySource.idOf(file.uri).takeIf { it.isNotEmpty() } ?: return null
            if (!natives.canPlay(file.uri)) return null
            return use(object : OpenedFile {
                override fun fingerprint() = "mpmedia-$id"
                override fun tags() = FileTags(natives.libraryTags(id)?.raw(), emptyList())
            })
        }
        val path = NSURL.URLWithString(file.uri)?.path ?: return null
        if (!MediaDisk.isFile(path) || !natives.canPlay(path)) return null
        val fd = open(path, O_RDONLY)
        if (fd < 0) return null
        try {
            val bytes = FdBytes(fd, MediaDisk.length(path))
            return use(object : OpenedFile {
                override fun fingerprint(): String {
                    val n = minOf(bytes.length, Fingerprint.CHUNK_BYTES.toLong()).toInt()
                    return Fingerprint.of(bytes.length, bytes.readFully(0, n), bytes.readFully(bytes.length - n, n))
                }

                override fun tags() = FileTags(natives.fileTags(path)?.raw(), ChapterReader.read(bytes))
            })
        } finally {
            close(fd)
        }
    }

    override fun readBytes(uri: String): ByteArray? = runCatching {
        val path = NSURL.URLWithString(uri)?.path ?: return null
        if (MediaDisk.length(path) > MAX_COVER_BYTES) return null
        val fd = open(path, O_RDONLY)
        if (fd < 0) return null
        try { FdBytes(fd, MediaDisk.length(path)).let { it.readFully(0, it.length.toInt()) } } finally { close(fd) }
    }.getOrNull()

    private companion object {
        const val MAX_COVER_BYTES = 16L shl 20
    }
}

/** Positional reads of an open file. */
private class FdBytes(private val fd: Int, override val length: Long) : ChapterBytes {
    override fun read(pos: Long, n: Int): ByteArray {
        val out = ByteArray(n)
        if (n == 0) return out
        var got = 0
        out.usePinned { pinned ->
            while (got < n) {
                val r = pread(fd, pinned.addressOf(got), (n - got).convert(), pos + got).toInt()
                if (r <= 0) break
                got += r
            }
        }
        return if (got == n) out else out.copyOf(got)
    }

    fun readFully(pos: Long, n: Int): ByteArray =
        read(pos, n).also { if (it.size != n) throw kotlinx.io.IOException("File shorter than its size") }
}

/**
 * Android's FileTagReadGuard on the iPhone (TagLib is native here too): the file being read is marked in [dir]; one
 * found marked at the next scan is a suspect, read once more; marked twice, its tags are never read again.
 */
internal class FileTagGuard(private val dir: String) : TagReadGuard {
    private val marker = MediaDisk.child(dir, "tags-reading")
    private val suspects = MediaDisk.child(dir, "tags-suspect")
    private val skips = MediaDisk.child(dir, "tags-skipped-2")
    private var reading: String? = null
    private var suspect: MutableSet<String> = HashSet()

    override fun skipped(): Set<String> = try {
        suspect = lines(suspects)
        if (MediaDisk.exists(marker)) {
            val died = MediaDisk.readText(marker)
            if (died.isNotEmpty()) {
                if (suspect.remove(died)) write(skips, lines(skips) + died) else suspect += died
                write(suspects, suspect)
            }
            MediaDisk.delete(marker)
        }
        lines(skips)
    } catch (e: Exception) {
        emptySet()
    }

    override fun begin(uri: String) {
        reading = uri
        // Synced before it takes its name, so a crash in the read that follows finds it.
        runCatching { MediaDisk.writeReplacing(dir, "tags-reading", uri.encodeToByteArray()) }
    }

    override fun end() {
        MediaDisk.delete(marker)
        val uri = reading ?: return
        reading = null
        if (suspect.remove(uri)) runCatching { write(suspects, suspect) }
    }

    private fun lines(path: String): MutableSet<String> =
        if (MediaDisk.exists(path)) MediaDisk.readText(path).lines().filterTo(HashSet()) { it.isNotEmpty() } else HashSet()

    private fun write(path: String, uris: Set<String>) {
        if (uris.isEmpty()) MediaDisk.delete(path) else MediaDisk.writeReplacing(dir, MediaDisk.nameOf(path), uris.joinToString("\n", postfix = "\n").encodeToByteArray())
    }
}
