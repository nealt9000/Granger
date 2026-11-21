package app.granger.core.books

import java.io.File

/*
 * Android's callers hand the book rules a java.io.File, as they did before the rules were shared (Plan I5): these
 * pass its path on. Callers outside this package import them by name (app.granger.core.books.inspect, …).
 */

fun EpubInspector.inspect(file: File, fallbackTitle: String = file.nameWithoutExtension): Inspection = inspect(file.path, fallbackTitle)

fun PdfInspector.inspect(file: File, fallbackTitle: String = file.nameWithoutExtension): Inspection = inspect(file.path, fallbackTitle)

fun PdfInspector.outline(file: File): List<OutlineEntry> = outline(file.path)

fun PdfText.chunks(file: File, href: String): List<TextChunk> = chunks(file.path, href)

fun PdfText.all(file: File): Sequence<TextChunk> = all(file.path)

fun PdfText.hasTextLayer(file: File): Boolean = hasTextLayer(file.path)

fun PdfText.open(file: File): PdfText.Reader? = open(file.path)

fun PdfLinks.read(file: File): List<PdfLink> = read(file.path)

fun PdfOrigin.Companion.of(file: File): PdfOrigin = of(file.path)

fun XhtmlText.all(file: File, spine: List<String>): Sequence<TextChunk> = all(file.path, spine)

fun ComicToPdf.convert(archive: File, out: File): Boolean = convert(archive.path, out.path)

fun PdfToEpub.convert(
    pdf: File, out: File, title: String = "", author: String = "", language: String = "",
    trace: ((String, Long) -> Unit)? = null,
): PdfToEpub.Report = convert(pdf.path, out.path, title, author, language, trace)
