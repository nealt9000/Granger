package app.granger.sources

import androidx.annotation.StringRes
import app.granger.R
import app.granger.UiText
import app.granger.core.catalog.BookEntry
import app.granger.core.catalog.BookSet
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.Shelves
import app.granger.core.library.GutenbergNames
import app.granger.formatBytes

/**
 * Get more → Books by kind (Plan KB2 Task 4.1, Decision 4): the catalog's `shelf` groups every book source, Granger's
 * own and Kiwix's, into Literature, Children's books, Manuals & references and Learning. A book pack with no shelf (an
 * older catalog, a new kind) goes under More books.
 */
object BookShelves {
    /** The shelves in order; [OTHER] last, shown only when something is on it. */
    const val OTHER = ""
    val ORDER: List<String> = Shelves.ORDER + OTHER

    /** Whether [item] is offered under Books: a Granger book, a Project Gutenberg pack or any other book pack. */
    fun isBookItem(item: CatalogItem): Boolean = when (item) {
        is BookEntry -> true
        is PackEntry -> isGutenberg(item) || item.isBookPack
        else -> false
    }

    /**
     * [pack]'s shelf: the catalog's, else Literature for a Gutenberg pack, else [OTHER]. African Storybook's sets
     * (Phase 3) arrive with `shelf: "children"` and land on Children's books with no change here.
     */
    fun shelfOf(pack: PackEntry): String = pack.shelf.takeIf { it in Shelves.ORDER }
        ?: if (isGutenberg(pack)) Shelves.LITERATURE else OTHER

    /** A book set's shelf (Plan KB2 Task 3.2): the catalog's, else [OTHER]. */
    fun shelfOf(set: BookSet): String = set.shelf.takeIf { it in Shelves.ORDER } ?: OTHER

    /**
     * A book set's row: "693 books · 1.2 GB · African Storybook Initiative", and "· 3 on this phone" once some are. No
     * "From Kiwix": Granger publishes these books.
     */
    fun setMeta(set: BookSet, onPhone: Int): UiText = KiwixList.dotted(
        listOfNotNull(
            UiText.Plural(R.plurals.common_books, set.books.size, set.books.size),
            UiText.Raw(formatBytes(set.books.sumOf { it.sizeBytes })),
            set.provider.takeIf { it.isNotBlank() }?.let(UiText::Raw),
            if (onPhone > 0) UiText.Plural(R.plurals.sources_on_this_phone, onPhone, onPhone) else null,
        ),
    )

    @StringRes fun heading(shelf: String): Int = when (shelf) {
        Shelves.LITERATURE -> R.string.sources_shelf_literature
        Shelves.CHILDREN -> R.string.sources_shelf_children
        Shelves.MANUALS -> R.string.sources_shelf_manuals
        Shelves.LEARNING -> R.string.sources_shelf_learning
        else -> R.string.sources_shelf_other
    }

    /**
     * Who made a Kiwix book pack: the catalog's `provider` (Plan KB2, from book_packs.json), else, for a catalog without
     * it, Project Gutenberg, or its family when that names a site ("irp.fas.org") or a maker ("prunelle" → "Prunelle");
     * null for a family that is only an id ("zimgit-knots").
     */
    fun provider(pack: PackEntry): String? = when {
        pack.provider.isNotBlank() -> pack.provider
        isGutenberg(pack) -> GutenbergNames.PROVIDER
        pack.family.contains('.') -> pack.family
        pack.family.isNotBlank() && pack.family.all { it.isLetter() } -> pack.family.replaceFirstChar { it.uppercase() }
        else -> null
    }

    /** "From Kiwix · Project Gutenberg", or "From Kiwix" alone (Task 4.2's pattern). */
    fun fromKiwix(pack: PackEntry): UiText =
        provider(pack)?.let { UiText.Res(R.string.library_from_kiwix_by, it) } ?: UiText.Res(R.string.library_from_kiwix)

    /** A book pack's one meta line: "65 MB · From Kiwix · irp.fas.org". */
    fun packMeta(pack: PackEntry, sizeBytes: Long = pack.sizeBytes): UiText =
        KiwixList.dotted(listOf(UiText.Raw(formatBytes(sizeBytes)), fromKiwix(pack)))

    /** A Granger book's: "Jane Austen · 1.2 MB". */
    fun bookMeta(book: BookEntry): UiText =
        KiwixList.dotted(listOfNotNull(book.author.takeIf { it.isNotBlank() }?.let(UiText::Raw), UiText.Raw(formatBytes(book.sizeBytes))))

    /** Project Gutenberg's folded row: "41 subjects · From Kiwix", and "· 2 on this phone" when some are. */
    fun gutenbergMeta(subjects: Int, onPhone: Int): UiText = KiwixList.dotted(
        listOfNotNull(
            UiText.Plural(R.plurals.sources_subjects, subjects, subjects),
            UiText.Res(R.string.library_from_kiwix),
            if (onPhone > 0) UiText.Plural(R.plurals.sources_on_this_phone, onPhone, onPhone) else null,
        ),
    )

    /** A Granger set's row: "437 books · 1.2 GB", then where it stands once any of it is on the phone ("3 to update"). */
    fun presetMeta(row: PresetRow): UiText {
        val untouched = row.onPhone == 0 && row.downloading == 0 && row.updates == 0
        return if (untouched) PresetText.summary(row) else KiwixList.dotted(listOf(PresetText.summary(row), PresetText.status(row)))
    }

    /** A Granger set's name for its row: the catalog's title up to its colon ("Granger Classics: all books"). */
    fun presetName(row: PresetRow): String = row.title.substringBefore(':').trim().ifEmpty { row.title }
}
