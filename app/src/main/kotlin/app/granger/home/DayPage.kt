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

        /** The three lists' headings by language, as each Wikipedia names them (data, not UI): events, births, deaths. */
        private val SECTIONS = mapOf(
            "en" to listOf("Events", "Births", "Deaths"),
            "es" to listOf("Acontecimientos", "Nacimientos", "Fallecimientos"),
        )

        /** Spanish's month names, as its Wikipedia's day articles write them (lower case), whatever the JVM's data says. */
        private val SPANISH_MONTHS = listOf(
            "enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre",
        )

        /** The day articles' languages: an encyclopedia in another language is read as English's. */
        val LANGUAGES: Set<String> get() = SECTIONS.keys

        /** [lang]'s headings of the three lists, events first; English's for a language without day articles here. */
        fun sections(lang: String = "en"): List<String> = SECTIONS[lang] ?: SECTIONS.getValue("en")

        /**
         * The day article's title in [lang]'s Wikipedia (Plan L3): "October 22" in English; "22 de octubre" in Spanish,
         * the month in lower case and no leading zero. Locale.ENGLISH and a fixed Spanish list on purpose: these match
         * page titles (data, not UI). The feed's shown title is FeedDrawer.title.
         */
        fun title(day: LocalDate, lang: String = "en"): String = when (lang) {
            "es" -> "${day.dayOfMonth} de ${SPANISH_MONTHS[day.monthValue - 1]}"
            else -> "${day.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${day.dayOfMonth}"
        }

        /** The paths to try, in order, for the day article in a Wikipedia pack in [lang]. */
        fun paths(day: LocalDate, lang: String = "en"): List<String> {
            val underscored = title(day, lang).replace(' ', '_')
            return listOf(underscored, "A/$underscored", title(day, lang))
        }

        /** A line's year: "794 – …" in English, "1383: …" in Spanish. */
        private val YEAR = mapOf(
            "en" to Regex("^(.{1,24}?)\\s+[–-]\\s+(.+)$"),
            "es" to Regex("^(.{1,24}?):\\s+(.+)$"),
        )

        /**
         * [line] as its year and its text (Plan L3): split on " – " in English and ": " in Spanish, the year being what
         * comes before ("794", "4004 a. C."). A line with no year keeps its text, with a null year.
         */
        fun split(line: String, lang: String = "en"): Pair<String?, String> {
            val match = (YEAR[lang] ?: YEAR.getValue("en")).matchEntire(line) ?: return null to line
            val year = match.groupValues[1].trim()
            // A year has a digit: "El 22 de octubre de algún año…" isn't one.
            return if (year.any(Char::isDigit)) year to match.groupValues[2].trim() else null to line
        }

        /**
         * Reads the three lists out of the article's HTML in [lang]'s Wikipedia. A section starts at an `h2` named by
         * [sections] (by id or text) and runs to the next `h2`; its `li`s are the lines, references and edit links
         * stripped, long lines cut.
         */
        fun parse(html: String, lang: String = "en"): DayPage {
            val names = sections(lang)
            val doc = Jsoup.parse(html)
            doc.select("sup, .mw-editsection, .reference, style, script").remove()
            val lists = names.associateWith { mutableListOf<String>() }
            var current: String? = null
            for (element in doc.body().select("h2, li")) {
                if (element.tagName() == "h2") {
                    val id = element.id().ifBlank { element.selectFirst(".mw-headline")?.id().orEmpty() }
                    val name = element.text().trim()
                    current = names.firstOrNull { it.equals(id, ignoreCase = true) || it.equals(name, ignoreCase = true) }
                    continue
                }
                val section = current ?: continue
                // A nested list's items would repeat their parent's text; keep the leaves only.
                if (element.select("li").size > 1) continue
                val text = element.text().replace(Regex("\\s+"), " ").trim()
                if (text.length < 8) continue
                lists.getValue(section) += if (text.length <= MAX_LINE) text else text.take(MAX_LINE - 1).trimEnd() + "…"
            }
            return DayPage(lists.getValue(names[0]), lists.getValue(names[1]), lists.getValue(names[2]))
        }
    }
}
