package app.granger.core.catalog

/**
 * One installed group of books as Explore's subject screens list it under "In your books" (Plan KB2 Task 4.8).
 * [id] is a pack id for [Kind.BOOK_PACK], a book set id for [Kind.BOOK_SET], and a `collection` (`granger-classics`,
 * `clasicos-granger`) for [Kind.CLASSICS], whose title the app supplies. [bookIds] are the installed books of the group
 * that carry the subject (empty for a book pack, whose books live inside its file).
 */
data class SubjectCollection(
    val id: String,
    val title: String,
    val kind: Kind,
    val bookIds: List<String> = emptyList(),
) {
    enum class Kind { CLASSICS, BOOK_PACK, BOOK_SET }
}

/**
 * The installed book collections for each Explore subject id (Plan KB2 Task 4.8), for Explore's "In your books".
 *  - Classics / Clásicos books ([Catalog.books]) are grouped by their `collection`, under each subject one of its
 *    installed books carries.
 *  - Book packs (Gutenberg, Kiwix book libraries, manifest packs) by their own [PackEntry.subjects]; websites are left
 *    out even when tagged.
 *  - Book sets (African Storybook) by the set's [BookSet.subjects], when one of its books is installed.
 * [installedPackIds] and [installedBookIds] are catalog ids. A collection is listed under its main subject first, so
 * order within a subject follows catalog order. Subjects with nothing installed are absent.
 */
fun Catalog.bookCollectionsBySubject(
    installedPackIds: Set<String>,
    installedBookIds: Set<String>,
): Map<String, List<SubjectCollection>> {
    val out = LinkedHashMap<String, MutableList<SubjectCollection>>()
    fun add(subject: String, c: SubjectCollection) = out.getOrPut(subject) { mutableListOf() }.add(c)

    books.filter { it.id in installedBookIds }.groupBy { it.collection }.forEach { (collection, installed) ->
        installed.flatMap { it.subjects }.distinct().forEach { subject ->
            val ids = installed.filter { subject in it.subjects }.map { it.id }
            add(subject, SubjectCollection(collection, collection, SubjectCollection.Kind.CLASSICS, ids))
        }
    }
    packs.filter { it.id in installedPackIds && it.isBookPack }.forEach { pack ->
        pack.subjects.forEach { add(it, SubjectCollection(pack.id, pack.title, SubjectCollection.Kind.BOOK_PACK)) }
    }
    bookSets.forEach { set ->
        val ids = set.books.map { it.id }.filter { it in installedBookIds }
        if (ids.isNotEmpty()) set.subjects.forEach {
            add(it, SubjectCollection(set.id, set.title, SubjectCollection.Kind.BOOK_SET, ids))
        }
    }
    return out
}
