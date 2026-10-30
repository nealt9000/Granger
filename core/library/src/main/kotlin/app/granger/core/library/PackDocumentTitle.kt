package app.granger.core.library

import java.net.URLDecoder

/**
 * What a pack document (a PDF opened from a Kiwix pack, Plan P2) is called on the reader's bar, the rail and Continue.
 * The PDF's own title is often junk: whatever the tool that made it saw, such as `file://C:\WINDOWS\Desktop\water.htm`,
 * `Microsoft Word - water.doc` or `Untitled`. So each name is tried in turn, and the first that reads as a title wins.
 */
object PackDocumentTitle {
    /** Schemes a title never starts with; "Water: a guide" isn't one, so a bare word before a colon is kept. */
    private val URL = Regex("^(file|https?|ftp|mailto|about|data|urn):|^www\\.|://", RegexOption.IGNORE_CASE)

    /** A Windows drive, as in `C:\` or `C:/`. */
    private val DRIVE = Regex("^[A-Za-z]:[\\\\/]")

    /** A name that ends as a file does: a document, a page or a scratch file. */
    private val FILE_NAME = Regex(
        "\\.(pdf|docx?|dotx?|rtf|txt|html?|xhtml|wpd|wps|ps|eps|indd|pub|qxd|odt|pages|xlsx?|pptx?|tmp|ai|psd)$",
        RegexOption.IGNORE_CASE,
    )

    /** What tools and pages put where a title should be. */
    private val SAYS_NOTHING = setOf(
        "untitled", "untitled document", "no title", "title", "document", "new document", "pdf", "pdf document",
        "powerpoint presentation", "slide 1", "here", "click here", "click", "download", "download pdf", "link",
        "open", "view", "view pdf", "read", "read more", "more", "file", "print",
    )

    /** A link's "(PDF)", "[PDF, 2 MB]" or "(1.2 MB)" after the name. */
    private val SIZE_OR_KIND = Regex("\\s*[(\\[][^()\\[\\]]*(pdf|[kmg]i?b)[^()\\[\\]]*[)\\]]\\s*$", RegexOption.IGNORE_CASE)

    private val SPACES = Regex("\\s+")

    /**
     * The first of [listedTitle] (the document's title in the pack's own list of them, [NautilusDatabase]),
     * [entryTitle] (the pack entry's own title), [pdfTitle] (the PDF's metadata Title), [linkText] (the words of the
     * link that was tapped in the article) that [reads as a title][isTitle], tidied; failing them all, the file name
     * in [path], cleaned ([fromFileName]).
     */
    fun choose(entryTitle: String, pdfTitle: String, linkText: String, path: String, listedTitle: String = ""): String =
        listOf(listedTitle, entryTitle, pdfTitle, linkText).map(::tidy).firstOrNull { isTitle(it, path) } ?: fromFileName(path)

    /**
     * False for a blank name, the entry's [path], anything that looks like a path, URL or file name (a scheme, a
     * drive letter, a slash or backslash, a document extension), what tools write in place of a title ("Untitled",
     * "Microsoft Word - …"), a link's "Click here" or "Download", and anything without two letters in it.
     */
    fun isTitle(text: String, path: String = ""): Boolean {
        val name = tidy(text)
        if (name.isEmpty() || name == path || name == path.substringAfterLast('/')) return false
        if ('/' in name || '\\' in name) return false
        if (URL.containsMatchIn(name) || DRIVE.containsMatchIn(name) || FILE_NAME.containsMatchIn(name)) return false
        if (name.startsWith("Microsoft ", ignoreCase = true) && " - " in name) return false
        if (name.lowercase().trimEnd('.', ':', '!') in SAYS_NOTHING) return false
        return name.count { it.isLetter() } >= 2
    }

    /**
     * The last part of [path], %-escapes decoded, without its extension, with `_` and `-` turned into spaces:
     * `docs/Water_storage-guide.pdf` is "Water storage guide". "PDF" if nothing is left.
     */
    fun fromFileName(path: String): String {
        val last = path.trimEnd('/').substringAfterLast('/')
        val decoded = try { URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { last }
        val bare = decoded.substringBeforeLast('.').ifEmpty { decoded }
        return bare.replace('_', ' ').replace('-', ' ').replace(SPACES, " ").trim().ifEmpty { "PDF" }
    }

    /** [text] on one line, single-spaced, without a link's trailing "(PDF, 2 MB)". */
    private fun tidy(text: String): String {
        val oneLine = text.replace(SPACES, " ").trim()
        return oneLine.replace(SIZE_OR_KIND, "").trim().ifEmpty { oneLine }
    }
}
