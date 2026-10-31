package app.granger.core.library

/**
 * What a Project Gutenberg pack is called (Plan GB Decision 2). Kiwix titles every one "Project Gutenberg Library";
 * its ZIM `Description` names the subject ("Music and books on music"), so the pack reads "Music and books on music ·
 * Project Gutenberg" and its collection is called by the subject alone. The catalog generator's `gutenberg_title`
 * follows the same rule.
 */
object GutenbergNames {
    const val PROVIDER = "Project Gutenberg"

    /** The two everything-packs, whose descriptions are about the producer, not a subject. */
    private val EVERYTHING = mapOf(
        "gutenberg_en_all" to "Every English book",
        "gutenberg_mul_all" to "Every book, all languages",
    )

    /** Whether [name] (a ZIM `Name`) is a Gutenberg pack's, when the catalog can't say (Decision 1). */
    fun isGutenberg(name: String): Boolean = name.startsWith("gutenberg_")

    /** The pack's subject, first letter capitalised; blank when its description is. */
    fun subject(name: String, description: String): String {
        val subject = EVERYTHING[name] ?: description.trim()
        return subject.replaceFirstChar { it.uppercaseChar() }
    }

    /** "{Subject} · Project Gutenberg", or "Project Gutenberg" with no subject. */
    fun packTitle(name: String, description: String): String =
        subject(name, description).takeIf { it.isNotEmpty() }?.let { "$it · $PROVIDER" } ?: PROVIDER
}

val Pack.isGutenberg: Boolean get() = bookSource == BookOrigin.GUTENBERG
