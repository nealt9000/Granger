package app.granger.core.library

import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/** An album artist found by [MediaIndex.search]: [albumCount] of their present albums, and one of their covers. */
data class ArtistHit(val name: String, val albumCount: Int, val coverPath: String?)

/** [MediaIndex.search]'s groups, each best first. */
data class MediaResults(
    val artists: List<ArtistHit> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val songs: List<Track> = emptyList(),
    val audiobooks: List<Audiobook> = emptyList(),
) {
    val isEmpty: Boolean get() = artists.isEmpty() && albums.isEmpty() && songs.isEmpty() && audiobooks.isEmpty()
}

/** An album or audiobook with the text Research and Auto need about it. */
data class MediaWork(
    val id: String,                 // "album:<albumKey>" or the audiobook id
    val isAlbum: Boolean,
    val title: String,
    val creator: String,            // album artist or author
    val composers: List<String>, val narrators: List<String>, val genres: List<String>,
    val year: String,
    val partTitles: List<String>,   // songs in album order, or chapters
    val description: String,
    val origin: MediaOrigin,
    val coverPath: String?,
    val partIds: List<String> = emptyList(), // beside partTitles: the track ids, or "chapter:<ordinal>" for an audiobook
)

/**
 * An in-memory index over the present media rows (MISSING ones are left out), rebuilt on [compute] whenever the rows
 * change. Shared by Search, Research and Android Auto's search.
 *
 * Matching: text is folded and split into words ([MediaText]). Every query word must equal a word of the item, except
 * the last, which may be a prefix of one ("the plan" finds *The Planets*). Score: per query word, the best-weighted
 * field it matched (title 3; album, artist, album artist, composer, author, narrator 2; track or chapter titles and
 * genres 1; description 0.5), plus 2 when the query's words, in order, appear among the title's words (punctuation aside). Ties by title, then id.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaIndex(media: MediaLibrary, scope: CoroutineScope, private val compute: CoroutineDispatcher = Dispatchers.Default) {
    /** The last snapshot built; a failed build or a failed library flow falls back to it (or to an empty one). */
    @Volatile private var last: Snapshot? = null

    /** Never left null by a failure: callers of [current] never hang. */
    private val snapshot: StateFlow<Snapshot?> =
        combine(media.tracks, media.audiobooks, media.allChapters, media.albumNotes) { tracks, books, chapters, notes ->
            Inputs(tracks, books, chapters, notes)
        }
            .mapLatest { inputs ->
                try {
                    build(inputs)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    warn(e)
                    last ?: EMPTY
                }
            }
            .onEach { last = it }
            .catch { e ->
                warn(e)
                emit(last ?: EMPTY)
            }
            .flowOn(compute)
            .stateIn(scope, SharingStarted.Eagerly, null)

    private suspend fun current(): Snapshot = snapshot.filterNotNull().first()

    suspend fun search(query: String, artists: Int = 3, albums: Int = 5, songs: Int = 8, audiobooks: Int = 5): MediaResults {
        val words = MediaText.words(query)
        if (words.isEmpty()) return MediaResults()
        val phrase = words.joinToString(" ")
        val s = current()
        // Scanning every document is the caller's cost otherwise, and Search calls from the main thread.
        return withContext(compute) { MediaResults(
            artists = rank(s.artists, words, prefixLast = true, phrase, artists),
            albums = rank(s.albums, words, prefixLast = true, phrase, albums),
            songs = rank(s.songs, words, prefixLast = true, phrase, songs),
            audiobooks = rank(s.audiobooks, words, prefixLast = true, phrase, audiobooks),
        ) }
    }

    /** Albums and audiobooks whose text holds every word of [words] as whole words (Research's gate), best first. */
    suspend fun works(words: List<String>, limit: Int): List<MediaWork> {
        val folded = words.flatMap(MediaText::words)
        if (folded.isEmpty() || limit <= 0) return emptyList()
        val s = current()
        val phrase = folded.joinToString(" ")
        return withContext(compute) { rank(s.works, folded, prefixLast = false, phrase, limit) }
    }

    /** The present album ("album:<albumKey>") or audiobook (its id) with this id; null when it's gone or MISSING. */
    suspend fun work(id: String): MediaWork? = current().workById[id]

    /** The album holding track [trackId], for a song's address that doesn't name its album. */
    suspend fun workWithPart(trackId: String): MediaWork? = current().works.firstOrNull { trackId in it.value.partIds }?.value

    private class Inputs(val tracks: List<Track>, val books: List<Audiobook>, val chapters: List<AudiobookChapter>, val notes: List<AlbumNote>)

    /** A field's words, stored once, folded. */
    private class Field(val words: Set<String>, val weight: Double) {
        fun matches(word: String, prefix: Boolean) = word in words || (prefix && words.any { it.startsWith(word) })
    }

    private class Doc<T>(val id: String, val title: String, val fields: List<Field>, val value: T) {
        val titleWords = MediaText.words(title).joinToString(" ")
        val sortTitle = title.lowercase(Locale.ROOT)
    }

    private class Snapshot(
        val artists: List<Doc<ArtistHit>>,
        val albums: List<Doc<AlbumSummary>>,
        val songs: List<Doc<Track>>,
        val audiobooks: List<Doc<Audiobook>>,
        val works: List<Doc<MediaWork>>,
        val workById: Map<String, MediaWork>,
    )

    private fun <T> rank(docs: List<Doc<T>>, words: List<String>, prefixLast: Boolean, phrase: String, limit: Int): List<T> {
        if (limit <= 0) return emptyList()
        return docs.mapNotNull { doc -> score(doc, words, prefixLast, phrase)?.let { doc to it } }
            .sortedWith(compareByDescending<Pair<Doc<T>, Double>> { it.second }.thenBy { it.first.sortTitle }.thenBy { it.first.id })
            .take(limit)
            .map { it.first.value }
    }

    private fun score(doc: Doc<*>, words: List<String>, prefixLast: Boolean, phrase: String): Double? {
        var total = 0.0
        for ((i, word) in words.withIndex()) {
            val prefix = prefixLast && i == words.lastIndex
            var best = 0.0
            for (f in doc.fields) if (f.weight > best && f.matches(word, prefix)) best = f.weight
            if (best == 0.0) return null
            total += best
        }
        if (phrase.isNotEmpty() && phrase in doc.titleWords) total += PHRASE
        return total
    }

    private fun build(inputs: Inputs): Snapshot {
        val music = inputs.tracks.filter { it.audiobookId == null }
        val present = Albums.present(music)
        val byAlbum = present.groupBy { it.albumKey }.mapValues { Albums.ordered(it.value) }
        val notes = inputs.notes.associate { it.albumKey to it.description }

        val songs = present.map { t ->
            Doc(t.id, t.title, listOf(
                field(TITLE, t.title),
                field(NAME, listOf(Albums.title(t)) + MediaNames.trackArtistsOf(t) + MediaNames.albumArtistsOf(t) + MediaNames.split(t.composers)),
                field(PART, MediaNames.split(t.genres)),
            ), t)
        }

        val albums = mutableListOf<Doc<AlbumSummary>>()
        val works = mutableListOf<Doc<MediaWork>>()
        for (a in MediaNames.albums(music)) {
            val tracks = byAlbum[a.key].orEmpty()
            if (tracks.isEmpty()) continue
            val composers = tracks.flatMap { MediaNames.split(it.composers) }.distinctBy(MediaNames::nameKey)
            val genres = tracks.flatMap { MediaNames.split(it.genres) }.distinctBy(MediaNames::nameKey)
            val description = notes[a.key].orEmpty()
            val fields = listOf(
                field(TITLE, a.title),
                field(NAME, tracks.flatMap { MediaNames.albumArtistsOf(it) + MediaNames.trackArtistsOf(it) } + composers),
                field(PART, tracks.map { it.title } + genres),
                field(ABOUT, description),
            )
            val id = "$ALBUM${a.key}"
            albums += Doc(id, a.title, fields, a)
            works += Doc(id, a.title, fields, MediaWork(
                id = id, isAlbum = true, title = a.title, creator = a.albumArtist, composers = composers, narrators = emptyList(),
                genres = genres, year = a.year, partTitles = tracks.map { it.title }, description = description,
                origin = tracks.first().origin, coverPath = a.coverPath, partIds = tracks.map { it.id },
            ))
        }

        class Artist(val name: String) { val albums = HashSet<String>(); var cover: String? = null }
        val artists = LinkedHashMap<String, Artist>()
        for (tracks in byAlbum.values) for (t in tracks) for (name in MediaNames.albumArtistsOf(t)) {
            if (name == Albums.UNKNOWN_ARTIST) continue
            val artist = artists.getOrPut(MediaNames.nameKey(name)) { Artist(name) }
            artist.albums += t.albumKey
            if (artist.cover == null) artist.cover = t.coverPath
        }
        val artistDocs = artists.map { (key, a) ->
            Doc("$ARTIST$key", a.name, listOf(field(TITLE, a.name)), ArtistHit(a.name, a.albums.size, a.cover))
        }

        val chapters = inputs.chapters.groupBy { it.audiobookId }
        val books = mutableListOf<Doc<Audiobook>>()
        for (b in inputs.books) {
            if (b.status == PackStatus.MISSING) continue
            val sorted = chapters[b.id].orEmpty().sortedBy { it.ordinal }
            val parts = sorted.map { it.title }
            val fields = listOf(
                field(TITLE, b.title),
                field(NAME, MediaNames.split(b.author) + MediaNames.split(b.narrator)),
                field(PART, parts),
                field(ABOUT, b.description),
            )
            books += Doc(b.id, b.title, fields, b)
            works += Doc(b.id, b.title, fields, MediaWork(
                id = b.id, isAlbum = false, title = b.title, creator = b.author, composers = emptyList(),
                narrators = MediaNames.split(b.narrator), genres = emptyList(), year = "", partTitles = parts,
                description = b.description, origin = b.origin, coverPath = b.coverPath,
                partIds = sorted.map { "$CHAPTER${it.ordinal}" },
            ))
        }

        return Snapshot(artistDocs, albums, songs, books, works, works.associate { it.id to it.value })
    }

    private companion object {
        const val ALBUM = "album:"
        const val CHAPTER = "chapter:"
        const val ARTIST = "artist:"
        const val TITLE = 3.0
        const val NAME = 2.0
        const val PART = 1.0
        const val ABOUT = 0.5
        const val PHRASE = 2.0
        val EMPTY = Snapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())

        fun warn(e: Throwable) {
            runCatching { android.util.Log.w("Granger", "Media index build failed", e) }
        }
        fun field(weight: Double, text: String) = Field(MediaText.words(text).toSet(), weight)
        fun field(weight: Double, texts: List<String>) = Field(texts.flatMapTo(HashSet()) { MediaText.words(it) }, weight)
    }
}
