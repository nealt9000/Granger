package app.granger.core.books

import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The PDF-to-ebook eval (Plan IA Task 7): converts every PDF in `$PDF_EVAL_DIR` (the corpus in
 * `tools/pdf-eval/corpus.tsv`, downloaded outside the repo) into `$PDF_EVAL_OUT`, and writes `report.html` there: each
 * book's numbers, the gate's verdict, its contents, and the start of its first chapters. Skipped when the folder isn't
 * set, so it never runs in the ordinary test suite.
 *
 * `PDF_EVAL_DIR=… PDF_EVAL_OUT=… ./gradlew :core:books:testDebugUnitTest --tests '*PdfToEpubEval*'`
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfToEpubEval {
    @Test fun corpus() {
        val dir = System.getenv("PDF_EVAL_DIR")?.let(::File)
        val out = System.getenv("PDF_EVAL_OUT")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory && out != null)
        out!!.mkdirs()
        PdfFixtures.init(ApplicationProvider.getApplicationContext())
        val only = System.getenv("PDF_EVAL_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val rows = StringBuilder()
        val books = StringBuilder()
        val pdfs = dir!!.listFiles { f -> f.extension.equals("pdf", true) }.orEmpty().sortedBy { it.name }
            .filter { only.isEmpty() || only.any { o -> it.name.contains(o) } }
        for (pdf in pdfs) {
            val epub = File(out, pdf.nameWithoutExtension + ".epub").apply { delete() }
            if (System.getenv("PDF_EVAL_HEADINGS") != null) {
                loadPdf(pdf).use { doc ->
                    val scratch = kotlin.io.path.createTempDirectory().toFile()
                    val pages = app.granger.core.books.pdfepub.PdfPages.readAll(doc, scratch)
                    val layout = app.granger.core.books.pdfepub.PdfLayout.build(pages)
                    println("BODY ${pdf.name}: ${layout.bodySize}")
                    layout.items.filterIsInstance<app.granger.core.books.pdfepub.Heading>().take(40)
                        .forEach { println("HEADING p${it.page} ${it.size} y=${it.y} ${it.text.take(50)}") }
                    scratch.deleteRecursively()
                }
            }
            if (System.getenv("PDF_EVAL_OUTLINE") != null) {
                val outline = PdfInspector.outline(pdf)
                println("OUTLINE ${pdf.name}: ${outline.groupingBy { it.depth }.eachCount()} " + outline.take(30).joinToString(" | ") { "${it.depth}:${it.page}:${it.title.take(30)}" })
            }
            val start = System.currentTimeMillis()
            val r = try {
                PdfToEpub.convert(pdf, epub)
            } catch (e: Throwable) {
                println("EVAL ${pdf.name} crashed: $e")
                rows.append("<tr><td>${esc(pdf.name)}</td><td colspan=12>crashed: ${esc(e.toString())}</td></tr>")
                continue
            }
            val ms = System.currentTimeMillis() - start
            println("EVAL ${pdf.name}: ${r.origin} pages=${r.pages} kept=${"%.3f".format(r.keptShare)} paras=${r.paragraphs} median=${r.medianParagraphChars} verse=${r.verseLines} chapters=${r.chapters}/${r.chapterSource} pics=${r.pictures} notes=${r.notes} complex=${r.complexPages} ${ms}ms → ${r.why}")
            rows.append("<tr class=\"${if (r.passes) "pass" else "fail"}\"><td><a href=\"#${esc(pdf.nameWithoutExtension)}\">${esc(pdf.name)}</a></td>")
                .append("<td>${r.origin}</td><td>${r.pages}</td><td>${"%.1f".format(r.keptShare * 100)}%</td><td>${r.droppedChars}</td>")
                .append("<td>${r.paragraphs}</td><td>${r.medianParagraphChars}</td><td>${r.verseLines}</td><td>${r.chapters} (${r.chapterSource})</td>")
                .append("<td>${r.pictures}</td><td>${r.notes}</td><td>${r.complexPages}</td><td>${ms / 1000.0}s</td><td>${esc(r.why)}</td></tr>")
            books.append("<h2 id=\"${esc(pdf.nameWithoutExtension)}\">${esc(pdf.name)} — ${esc(r.why)}</h2>")
            if (epub.isFile) books.append(preview(epub))
        }
        File(out, "report.html").writeText(
            """<!doctype html><meta charset="utf-8"><title>PDF to ebook eval</title>
<style>body{font:14px/1.45 system-ui;margin:24px;max-width:1100px}table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:3px 6px;font-size:12px}
tr.pass td:last-child{color:#060}tr.fail td:last-child{color:#a00}.book{border:1px solid #ddd;padding:12px 20px;margin:8px 0 24px;font-family:Georgia,serif;max-height:900px;overflow:auto}
.verse p{margin:0}.nav{font-size:12px;columns:3}</style>
<h1>PDF to ebook eval</h1><table><tr><th>file</th><th>origin</th><th>pages</th><th>text kept</th><th>heads dropped</th><th>paragraphs</th><th>median chars</th><th>verse lines</th><th>chapters</th><th>pictures</th><th>notes</th><th>complex pages</th><th>time</th><th>gate</th></tr>$rows</table>$books""",
        )
    }

    /** The contents and the first 12,000 characters of chapter XHTML, in order. */
    private fun preview(epub: File): String = ZipFile(epub).use { zip ->
        val nav = zip.getEntry("OEBPS/nav.xhtml")?.let { zip.getInputStream(it).readBytes().decodeToString() }.orEmpty()
            .substringAfter("<ol>", "").substringBeforeLast("</ol>", "")
        val chapters = zip.entries().toList().map { it.name }.filter { Regex("OEBPS/c\\d+\\.xhtml").matches(it) }.sorted()
        val text = StringBuilder()
        for (name in chapters) {
            if (text.length > 12_000) break
            val body = zip.getInputStream(zip.getEntry(name)).readBytes().decodeToString().substringAfter("<body>").substringBefore("</body>")
            text.append("<div class=\"chapter\"><small>${esc(name)}</small>").append(body.take(4_000)).append("</div>")
        }
        "<div class=\"nav\"><ol>$nav</ol></div><div class=\"book\">${text.replace(Regex("<img [^>]*>"), "[picture]")}</div>"
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
