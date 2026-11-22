package app.granger.feedback

import app.granger.core.library.Book
import app.granger.core.library.BookOrigin
import app.granger.screens.bytesText

/**
 * Who made a report: the build and the phone, written at the top of each one (Plan F1 part 2 Decision 1). Shared since
 * Plan I7 Task 1: Android fills it from `BuildConfig` and `Build` (`BuildFacts.ofThisPhone()` in the app), the iPhone
 * from its bundle and `UIDevice`, with [osName] "iOS".
 */
data class BuildFacts(
    val versionName: String,
    val versionCode: Int,
    val device: String,
    /** "16 (API 36)" on Android, "18.2" on the iPhone. */
    val osVersion: String,
    val osName: String = "Android",
) {
    fun header(): String = "Granger $versionName ($versionCode) · $device · $osName $osVersion"

    /** Android adds `ofThisPhone()` here. */
    companion object
}

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

/**
 * The email Send feedback hands to the user's mail app, built from [FeedbackFacts] alone. Its words are English on
 * every phone: the developer reads them (the sheet lists the same lines).
 */
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
        add("${facts.build.device}, ${facts.build.osName} ${facts.build.osVersion}")
        add("This phone: ${facts.tier}")
        facts.freeBytes?.let { add("${bytesText(it)} free") }
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

/**
 * Report this answer (Play's AI-Generated Content policy, release checklist A): a flag on an Ask reply or best guess,
 * raised in the app and sent through Send feedback's mail route. Unlike Send feedback it carries the chat's words,
 * and the sheet says so before the mail app opens. Android adds `intent()`.
 */
data class AskReport(
    val reason: Reason,
    val note: String,
    val question: String,
    /** The reply as shown: the answer, the quoted medical answer, or the best guess. */
    val reply: String,
    val guess: Boolean,
    /** The cited sources' titles, numbered as on screen; none for a guess. */
    val sources: List<String>,
    val build: BuildFacts,
) {
    /** What's wrong, as the sheet offers it; [label] is the email's English, read by the developer. */
    enum class Reason(val label: String) {
        WRONG("Wrong or made up"),
        OFFENSIVE("Offensive"),
        UNSAFE("Unsafe or harmful"),
        OTHER("Something else"),
    }

    fun subject() = "Granger Ask report · ${build.versionName} (${build.versionCode})"

    /** The user's note first, then everything the sheet listed, under a rule. */
    fun body(): String = buildString {
        if (note.isNotBlank()) append(note.trim()).append("\n\n")
        append("—\n")
        append("Reason: ").append(reason.label).append('\n')
        append("Question: ").append(question.trim()).append("\n\n")
        append(if (guess) "Best guess:\n" else "Answer:\n").append(reply.trim()).append('\n')
        if (sources.isNotEmpty()) {
            append("\nSources:\n")
            sources.forEachIndexed { i, s -> append(i + 1).append(". ").append(s).append('\n') }
        }
        append('\n').append(build.header()).append('\n')
    }
}
