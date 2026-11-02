package app.granger.feedback

import app.granger.core.library.Book
import app.granger.core.library.BookOrigin
import app.granger.formatBytes

/**
 * What Send feedback may include (Plan F1 part 2 Decision 2): the build and phone, the tier, free space and counts.
 * Never titles, searches, chats or places; the crash reports only when the user leaves them in.
 */
data class FeedbackFacts(
    val build: BuildFacts,
    /** "Library and Ask" or "Library only". */
    val tier: String,
    val freeBytes: Long?,
    val packs: Int,
    val books: Int,
    val crashes: List<CrashReport>,
)

/** The email Send feedback hands to the user's mail app, built from [FeedbackFacts] alone. */
object FeedbackReport {
    /**
     * The books counted on their own: the user's own and the ones got one by one (Classics, archive.org). A book pack's
     * books are counted with its pack, and the hidden rows behind a pack's PDF or an ebook's original pages not at all
     * (the Pixel 7 walkthrough: Pride and Prejudice and the knots pack read as "12 books").
     */
    fun bookCount(books: List<Book>): Int = books.count { it.origin in OWN_BOOKS }

    private val OWN_BOOKS = setOf(BookOrigin.GRANGER, BookOrigin.IMPORT, BookOrigin.ARCHIVE_ORG)

    fun subject(build: BuildFacts) = "Granger feedback · ${build.versionName} (${build.versionCode})"

    /** What the sheet lists before the mail app opens, one line each, in the order the email carries them. */
    fun included(facts: FeedbackFacts, withCrashes: Boolean): List<String> = buildList {
        add("Granger ${facts.build.versionName} (${facts.build.versionCode})")
        add("${facts.build.device}, Android ${facts.build.android}")
        add("This phone: ${facts.tier}")
        facts.freeBytes?.let { add("${formatBytes(it)} free") }
        add("${count(facts.packs, "pack")} and ${count(facts.books, "book")} (counts only, no titles)")
        if (withCrashes && facts.crashes.isNotEmpty()) add("${count(facts.crashes.size, "crash report")}, attached")
    }

    /** Space for the user's words first, then the facts under a rule. */
    fun body(facts: FeedbackFacts, withCrashes: Boolean): String =
        "\n\n\n—\n" + included(facts, withCrashes).joinToString("\n") + "\n"

    /** The attachment: the facts, then each crash report in full, newest first; null when there is nothing to attach. */
    fun attachment(facts: FeedbackFacts, withCrashes: Boolean, read: (CrashReport) -> String): String? {
        if (!withCrashes || facts.crashes.isEmpty()) return null
        return buildString {
            included(facts, withCrashes = true).forEach { append(it).append('\n') }
            for (crash in facts.crashes) {
                append("\n==========\n")
                append(read(crash).trimEnd()).append('\n')
            }
        }
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}
