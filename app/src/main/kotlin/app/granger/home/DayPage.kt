package app.granger.home

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import org.jsoup.Jsoup

/** The day article's lists (Plan H2, Decision 7): what happened, who was born and who died on this day. */
data class DayPage(val events: List<String>, val births: List<String>, val deaths: List<String>) {
    val isEmpty: Boolean get() = events.isEmpty() && births.isEmpty() && deaths.isEmpty()

    companion object {
        private const val MAX_LINE = 220
        private val SECTIONS = setOf("Events", "Births", "Deaths")

        /** "October 22", as Wikipedia titles the day article. */
        fun title(day: LocalDate): String = "${day.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${day.dayOfMonth}"

        /** The paths to try, in order, for the day article in a Wikipedia pack. */
        fun paths(day: LocalDate): List<String> {
            val underscored = title(day).replace(' ', '_')
            return listOf(underscored, "A/$underscored", title(day))
        }

        /**
         * Reads the three lists out of the article's HTML. A section starts at an `h2` named Events, Births or Deaths
         * (by id or text) and runs to the next `h2`; its `li`s are the lines, references and edit links stripped, long
         * lines cut.
         */
        fun parse(html: String): DayPage {
            val doc = Jsoup.parse(html)
            doc.select("sup, .mw-editsection, .reference, style, script").remove()
            val lists = SECTIONS.associateWith { mutableListOf<String>() }
            var current: String? = null
            for (element in doc.body().select("h2, li")) {
                if (element.tagName() == "h2") {
                    val id = element.id().ifBlank { element.selectFirst(".mw-headline")?.id().orEmpty() }
                    val name = element.text().trim()
                    current = SECTIONS.firstOrNull { it.equals(id, ignoreCase = true) || it.equals(name, ignoreCase = true) }
                    continue
                }
                val section = current ?: continue
                // A nested list's items would repeat their parent's text; keep the leaves only.
                if (element.select("li").size > 1) continue
                val text = element.text().replace(Regex("\\s+"), " ").trim()
                if (text.length < 8) continue
                lists.getValue(section) += if (text.length <= MAX_LINE) text else text.take(MAX_LINE - 1).trimEnd() + "…"
            }
            return DayPage(lists.getValue("Events"), lists.getValue("Births"), lists.getValue("Deaths"))
        }
    }
}
