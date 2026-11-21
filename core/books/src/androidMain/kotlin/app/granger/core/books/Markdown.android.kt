package app.granger.core.books

import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities

internal actual fun platformMarkdown(): MarkdownChapters = CommonmarkChapters

/**
 * Markdown through commonmark (pictures dropped, HTML escaped, links sanitised), cut into chapters at the shallowest
 * heading level used twice or more, each chapter's XHTML through jsoup, as before Plan I5.
 */
internal object CommonmarkChapters : MarkdownChapters {
    override fun chapters(markdown: String): List<Pair<String, String>> {
        val doc = Parser.builder().build().parse(markdown)
        doc.accept(object : AbstractVisitor() {
            override fun visit(image: Image) = image.unlink()
        })
        val counts = HashMap<Int, Int>()
        var n: Node? = doc.firstChild
        while (n != null) {
            if (n is Heading) counts.merge(n.level, 1, Int::plus)
            n = n.next
        }
        val level = counts.filterValues { it >= 2 }.keys.minOrNull()

        val html = HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true).build().render(doc)
        val nodes = Jsoup.parseBodyFragment(html).body().children()
        val result = ArrayList<Pair<String, String>>()
        var title: String? = null
        val current = ArrayList<Element>()
        fun flush() {
            if (current.isEmpty()) return
            val t = title ?: current.firstOrNull { it.tagName().matches(Regex("h[1-6]")) }?.text()?.takeIf { it.isNotBlank() }
            result.add((t ?: "Part ${result.size + 1}") to xhtml(current))
            current.clear()
            title = null
        }
        for (el in nodes) {
            if (level != null && el.tagName() == "h$level") {
                flush()
                title = el.text().takeIf { it.isNotBlank() }
            }
            current.add(el)
        }
        flush()
        return result
    }

    private fun xhtml(elements: List<Element>): String {
        val doc: Document = Jsoup.parseBodyFragment(elements.joinToString("") { it.outerHtml() })
        doc.outputSettings().syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml)
        return doc.body().html()
    }
}
