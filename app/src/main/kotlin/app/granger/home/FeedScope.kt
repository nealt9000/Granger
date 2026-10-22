package app.granger.home

import app.granger.core.catalog.Catalog
import app.granger.core.library.AlbumSummary
import app.granger.core.library.Audiobook
import app.granger.core.library.Book
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.core.library.Shelf

/**
 * What a feed draws from (Plan H2, Decision 3), parsed from `HomeFeed.scope`: `library`, `pack:<zimName>`,
 * `family:<family>`, `collection:<collectionId>`, `music`, `audiobooks`.
 */
sealed interface FeedScope {
    /** The stored form. */
    val text: String

    data object Library : FeedScope { override val text get() = "library" }
    data class Pack(val name: String) : FeedScope { override val text get() = "pack:$name" }
    data class Family(val family: String) : FeedScope { override val text get() = "family:$family" }
    data class Collection(val id: String) : FeedScope { override val text get() = "collection:$id" }
    data object Music : FeedScope { override val text get() = "music" }
    data object Audiobooks : FeedScope { override val text get() = "audiobooks" }

    companion object {
        /** Null for a scope this build doesn't know (a feed from a newer app, or a damaged row): the feed is hidden. */
        fun parse(text: String): FeedScope? {
            val t = text.trim()
            return when {
                t == "library" -> Library
                t == "music" -> Music
                t == "audiobooks" -> Audiobooks
                t.startsWith("pack:") -> t.removePrefix("pack:").takeIf { it.isNotBlank() }?.let(::Pack)
                t.startsWith("family:") -> t.removePrefix("family:").takeIf { it.isNotBlank() }?.let(::Family)
                t.startsWith("collection:") -> t.removePrefix("collection:").takeIf { it.isNotBlank() }?.let(::Collection)
                else -> null
            }
        }
    }
}

/** What's on the phone, for resolving scopes. */
class LibrarySnapshot(
    val packs: List<Pack>,
    val books: List<Book>,
    val shelves: List<Shelf>,
    val albums: List<AlbumSummary>,
    val audiobooks: List<Audiobook>,
    val catalog: Catalog?,
)

/** A scope resolved against the phone: the whole items a feed can draw from, and what to call it. */
data class Resolved(
    val name: String,
    val packs: List<Pack> = emptyList(),
    val books: List<Book> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val audiobooks: List<Audiobook> = emptyList(),
) {
    val isEmpty: Boolean get() = packs.isEmpty() && books.isEmpty() && albums.isEmpty() && audiobooks.isEmpty()
}

/** The scope's items on this phone, or null when it names nothing (the feed is hidden). Only whole items count. */
fun FeedScope.resolve(snapshot: LibrarySnapshot): Resolved? {
    val packs = snapshot.packs.filter { it.status == PackStatus.OK }
    val books = snapshot.books.filter { it.status == PackStatus.OK }
    val resolved = when (this) {
        FeedScope.Library -> Resolved("the library", packs, books)
        is FeedScope.Pack -> packs.firstOrNull { it.name == name }?.let { Resolved(it.title, packs = listOf(it)) }
        is FeedScope.Family -> packs.firstOrNull { familyOf(it, snapshot.catalog) == family }?.let { Resolved(it.title, packs = listOf(it)) }
        is FeedScope.Collection -> snapshot.shelves.asSequence().flatMap { it.allCollections }.firstOrNull { it.collection.id == id }
            ?.let { row ->
                Resolved(
                    row.collection.name,
                    packs = row.packs.filter { it.status == PackStatus.OK },
                    books = row.books.filter { it.status == PackStatus.OK },
                    albums = row.albums.filter { it.status == PackStatus.OK },
                    audiobooks = row.audiobooks.filter { it.status == PackStatus.OK },
                )
            }
        FeedScope.Music -> Resolved("Music", albums = snapshot.albums.filter { it.status == PackStatus.OK })
        FeedScope.Audiobooks -> Resolved("Audiobooks", audiobooks = snapshot.audiobooks.filter { it.status == PackStatus.OK })
    }
    return resolved?.takeUnless { it.isEmpty }
}

/**
 * A pack's catalog family: the cached catalog's entry by ZIM uuid, then by ZIM name; a pack the catalog doesn't know
 * answers by its name's first word (`wikipedia_en_all` → `wikipedia`), which is the family for every Kiwix pack.
 */
fun familyOf(pack: Pack, catalog: Catalog?): String {
    val entry = catalog?.packs?.firstOrNull { it.zimUuid == pack.id } ?: catalog?.packs?.firstOrNull { pack.name.isNotBlank() && it.zimName == pack.name }
    return entry?.family?.ifBlank { null } ?: pack.name.substringBefore('_')
}
