package app.granger.core.library

/**
 * Which kind of book pack [this] is (Plan KB Decision 3), by the tool that made it: [BookOrigin.GUTENBERG] for
 * gutenberg2zim (or a `gutenberg_` name, for a row whose scraper isn't read yet), [BookOrigin.NAUTILUS] for
 * nautiluszim, [BookOrigin.KIWIX] for a pack with a verified book manifest or listed by its files' names
 * ([Pack.bookManifest], Plan KB2), null for a website. The pack decides from its own file (and its manifest), so it holds
 * for a sideloaded pack and with no catalog. Filing, the shelves, Browse and the collection screens ask this.
 */
val Pack.bookSource: BookOrigin?
    get() {
        val scraper = scraper.orEmpty()
        return when {
            scraper.startsWith(GUTENBERG_SCRAPER) || GutenbergNames.isGutenberg(name) -> BookOrigin.GUTENBERG
            scraper.startsWith(NAUTILUS_SCRAPER) -> BookOrigin.NAUTILUS
            bookManifest != null -> BookOrigin.KIWIX
            else -> null
        }
    }

/** A bundle of books: shown as a collection of its books, not as a pack ([bookSource]). */
val Pack.isBookPack: Boolean get() = bookSource != null

private const val GUTENBERG_SCRAPER = "gutenberg2zim"
private const val NAUTILUS_SCRAPER = "nautiluszim"
