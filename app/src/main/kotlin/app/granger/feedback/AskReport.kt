package app.granger.feedback

import android.content.Intent
import app.granger.BuildConfig

/**
 * Report this answer (Play's AI-Generated Content policy, release checklist A): a flag on an Ask reply or best guess,
 * raised in the app and sent through Send feedback's mail route. Unlike Send feedback it carries the chat's words,
 * and the sheet says so before the mail app opens.
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

    /** The email, for [FeedbackMail.toMailApps]. */
    fun intent(): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_EMAIL, arrayOf(BuildConfig.FEEDBACK_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject())
        putExtra(Intent.EXTRA_TEXT, body())
    }
}
