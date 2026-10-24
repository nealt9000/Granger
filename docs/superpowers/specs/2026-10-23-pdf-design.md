# The PDF pass — design

2026-10-23 · Neal Thompson (decisions) · written up by Claude

Status: decided 2026-10-23 (§9); plan `docs/superpowers/plans/2026-10-23-plan-p2-pdf.md`. The v1 spec promised PDF import (§3, `:core:imports`); the library redesign (§2) and Plan 7 deferred it to "the PDF pass", and archive.org items, Kiwix packs and the voice spec have all waited on it since. Decisions taken 2026-10-23: all three surfaces in one pass, the text indexed for Search and Research, spec then plan then build.

## 1. Why

Many of the documents worth having when the internet is out are PDFs: field manuals, public-health guides, water and food-preparation booklets, scanned books. Granger turns them away in three places today:

- **Import:** a PDF picked in My books is refused ("isn't an EPUB, TXT or Markdown file").
- **Archive.org:** about 9 million public-domain texts have only a scan. Items with no EPUB and no text file say "PDF: coming soon"; items with OCR text give plain text, which loses the page.
- **Kiwix packs:** some packs are mostly PDFs (irp.fas.org military medicine, zimgit water, food preparation, knots, post-disaster, armypubs). Tapping a PDF link in the reader does nothing at all.

## 2. Goals, non-goals, anti-goals

**Goals**
- A PDF opens in the e-book reader. Pages are laid out as printed, you can pinch to zoom and swipe between pages, and it opens at your last page, with bookmarks, Contents (the PDF outline) and the rail/Continue/History entries an EPUB gets.
- Import DRM-free PDFs into My books; encrypted PDFs are refused with the same message as a DRM EPUB.
- Archive.org items with a PDF can be got as a PDF: PDF-only items get a Get button, and items that also have OCR text offer both.
- A PDF inside a Kiwix pack opens in the same reader when tapped, at its last page.
- The text layer of an imported or downloaded PDF is indexed: it is found by Search ("In books") and Research, the citation opens at the page, and Home's random cards can draw from it.
- A PDF with no text layer (a pure scan) still opens; it is marked "Not searchable" in My books and in the reader's info.

**Non-goals (this pass)**
- OCR. A scan with no text layer stays unsearchable; on archive.org the OCR text download remains for those.
- Highlights, notes and the selection menu (Copy · Highlight · Note · Define · Search · Research) on PDF pages. Readium's PDF navigator does not support decorations or selection (§4.1). Bookmarks work.
- In-book search inside an open PDF. The PDF opens at a Search or Research hit's page; it doesn't mark the words.
- Read-aloud of PDFs. The voice reads from Readium's HTML content service, which has no PDF form. A PDF `ReadSource` over the extracted text is a later item.
- Indexing PDFs inside ZIM packs. libkiwix's full-text index covers HTML only, and indexing hundreds of PDFs per pack on the phone is a separate job (§7).
- Dense (vector) retrieval for PDFs. R1's vectors are built on the Mac for catalog books. On-phone embedding of books you add is R2, and it should take PDF chunks then (§6.3).
- Editing, form filling, signing, annotation export.

**Anti-goals: failures even if it technically works**
- **A 200 MB scan downloading without warning.** The size is shown before Get, and anything over 100 MB asks first.
- **Losing your page.** Rotation, process death and reopening from the rail land on the same page.
- **A blank tap.** Tapping a PDF link in a pack always does something visible: it opens, or says why it can't.
- **Running out of memory on a big PDF in a pack.** Entries are streamed to a file, never read whole into a byte array.
- **Pretending a scan is searchable.** No PDF shows in "In books" results unless its text was really indexed.

## 3. The book model learns formats

No Book row records a format today; "it's an EPUB" is implied by the file and by calling `EpubInspector` again wherever needed.

- **Schema 13:** `books.format TEXT NOT NULL DEFAULT 'EPUB'` (`EPUB` | `PDF`), and `books.searchable INTEGER NOT NULL DEFAULT 1` (0 for a PDF with no text layer). `MIGRATION_12_13`, `schemas/13.json`. No open worktree has passed 12 (r1, r4, catalog-vectors are at 12; trained-voice is at 11).
- **`BookFormat`** in `:core:library`; `Book.format`. Every place that opens, inspects or re-extracts a book dispatches on it: `Bookshelf.addDownloaded`/`import`/`refresh`/`register`/`strays`, `BookIndex.index`/`chunkTexts`, `ReadiumBookOpener`, the reader.
- **`BookMeta`** replaces `EpubMeta` as what `register` takes (title, authors, date, language, subjects, description, cover bytes + extension). `EpubInspector` returns it unchanged; `PdfInspector` fills it from the document info dictionary (Title, Author, Subject, Keywords, CreationDate), falling back to the file name for the title, as TXT does.
- **Covers:** page 1 rendered to a PNG, about 600 px tall, at import or registration. It is rendered with `android.graphics.pdf.PdfRenderer` (platform, API 21+) so the cover doesn't need the reader's engine.
- **Files:** imports keep `.pdf` (`${sha12}-${title}.pdf`). Archive.org downloads land at `books/archive-org/<id>.pdf`; `strays()` accepts `.pdf`.

## 4. Reading

### 4.1 The engine: Readium's PDFium adapter

`org.readium.kotlin-toolkit:readium-adapter-pdfium:3.4.0` (BSD-3), matching the Readium already in use. It brings `PdfiumAndroid` 1.9.8 and `AndroidPdfViewer` 3.2.8 (`com.github.marain87`, Apache-2.0, both GPL-3-compatible), hosted on **JitPack**, which becomes the third repository in `settings.gradle.kts`, restricted to that group with `content { includeGroup("com.github.marain87") }`. Checked 2026-10-23: the arm64 `.so` files have 16 KB-aligned LOAD segments, so Play's 16 KB page-size requirement is met.

Why this and not Jetpack's `androidx.pdf` (Compose, Google Maven, minSdk 28, beta):

| | Readium PDFium | androidx.pdf |
| --- | --- | --- |
| Fits the existing reader | Yes: `Publication`, `Locator` (page = `position`), `positions()`, `tableOfContents`, bookmarks, `OpenBooks`, `StartLocator`, rail and Continue entries unchanged | No: a second reader screen, its own place and bookmark model |
| Selection, Define, Research from a selection | No | Yes, on API 35 or SDK extension 13 (the Pixel 6 has it) |
| Maturity | Stable API; native binding is a community fork, last pushed 2026-05 | Google-maintained, still beta |
| Repository | Adds JitPack | Google Maven (already used) |

**Chosen: Readium PDFium.** One reader keeps the place, rail, bookmark, Contents and deep-link work shared. The loss of selection on PDFs is the price. If Neal wants Define and Research from a PDF selection in this pass, switch to `androidx.pdf` and accept a second reader screen. Neal chose PDFium (§9).

### 4.2 The reader shell

- `ReadiumBookOpener` passes `pdfFactory = PdfiumDocumentFactory(context)`; `withBookCss` already skips non-HTML.
- `NavigatorHost`, `OpenBooks`, `ReadiumFragmentFactory` and `NavigatorStandIn` generalise from `EpubNavigatorFactory`/`EpubNavigatorFragment` to a `BookNavigator` with two implementations (EPUB, PDF), so rotation and process-death restore work for both. The reader's chrome (top bar, Contents, bookmarks, the progress slider, middle-tap to show controls) is shared.
- **Hidden for PDFs:** Text settings (fonts, size, alignment), the selection menu, highlights/notes lists, in-book search, Read aloud. The overflow keeps Contents, Bookmarks and About.
- **PDF settings** (one small sheet): scroll vertically or page horizontally, fit width or fit page, and the Day/Sepia/Night theme. Night inverts page colours, using `PDFView.Configurator.nightMode(true)` through `PdfiumEngineProvider.Listener.onConfigurePdfView` (`PdfiumPreferences` has fit, scroll, axis and spacing but no theme); check on the Pixel 6 that an inversion is legible. Settings are global, as text settings are.
- **Places:** `BookPosition.locatorJson` holds the PDF locator (`position` = page, `totalProgression`), and `chapterTitle` is the outline entry for the page, else "Page N". The rail and Continue read "Page 12 of 340 · 4%".
- `StartLocator.choose` gets a PDF branch: a `BookTarget` with `href = "page/12"` maps to the locator for position 12. The quote is ignored (§2).

## 5. The three surfaces

### 5.1 Import

- `IMPORT_TYPES` gains `application/pdf`. The empty-state line reads "EPUB, PDF, TXT or Markdown".
- `Bookshelf.import`: a `%PDF` header goes to `PdfInspector` instead of returning `Unsupported`.
  - Encrypted (any `/Encrypt` dictionary, including owner-password-only): `Protected(DrmKind.PDF_ENCRYPTED)`. The message matches the DRM EPUB one: "is protected and can't be opened. Granger only opens DRM-free books."
  - Unparseable, or zero pages: `Damaged`.
  - Otherwise registered with `format = PDF`. `searchable` is decided when it's indexed (§6).
- `Unsupported`'s message becomes "isn't an EPUB, PDF, TXT or Markdown file".
- Dedupe by sha256, as now.

### 5.2 Archive.org

- `Offer.Download` gains `TextKind.PDF`. `choose()` picks the PDF after EPUB and text have been tried, and:
  - prefers `Text PDF` (it has archive.org's OCR text layer, so it is searchable) over `Additional Text PDF` over `Image Container PDF` over any other format containing `PDF`, then the smallest of the best format;
  - excludes `LCP Encrypted PDF` and private files (as `isUsable` already does);
  - applies no 20 MiB cap: that cap is for text files.
- `PdfOnly` goes away; `alsoPdf: Boolean` becomes `pdf: ArchiveFile?` on a text download. The sheet shows **Get** (the text, with its size) and **Get PDF · 84 MB** side by side, which also fixes the Plan 7 note that "PDF: coming soon" sat below the buttons. A PDF-only item shows one **Get · 84 MB**.
- Labels: "PDF" for PDF-only, "OCR text: may have errors · PDF" when both. `preview()` follows the same rules from search formats.
- **Over 100 MB:** Get asks first ("This PDF is 212 MB. Download it?" · Download · Cancel, with a "Don't ask again" box), unless the setting is off (§9). Over the free space it gives the existing NoSpace notice.
- `ArchiveDownloads.spec` names the file `<id>.pdf`; the sidecar's `kind` stays `TextKind.name`, so `PDF` reads back with `valueOf`. Older sidecars hold only `EPUB`/`TEXT`/`OCR_TEXT` and still parse.
- An item where the user gets both the text and the PDF becomes **two books** (two files, two `BookIds`), each filed under archive.org, with "(PDF)" after the PDF one's title. Joining them is not worth it in this pass.

### 5.3 PDFs inside Kiwix packs

- `ZimArchive` gains `size(path)` and `copyTo(path, file)`. `copyTo` streams the entry; where libzim's direct-access information is available (uncompressed blobs, the usual case for PDFs), it copies that byte range from the `.zim` file without decompressing it into memory. `read(path)` keeps its current behaviour for HTML.
- `ZimWebViewClient.shouldOverrideUrlLoading`: an in-pack link whose entry MIME is `application/pdf` (by `ZimResolver`, not the extension) returns true and calls `onOpenPdf(zimId, path)`. It does not call `onLeavingPage`.
- The reader copies the entry to `files/books/packs/<zimId>/<sha1(path)>.pdf` and registers it as a **pack document**: a `Book` row with `origin = PACK` (`BookOrigin` gains it, key `pack`), `sourceRef = "<zimId>/<path>"`, `format = PDF`, and the entry's title. So places, bookmarks, the rail, Continue, History and launch all work through `BookIds.isBook` unchanged. Pack documents are **not shown in My books** (the shelf query filters `origin = PACK`), are not indexed, and are removed with their file when the pack is removed. Opening one whose file is gone (storage cleared) copies it again from the pack; if the pack is gone too, it reads "Removed book" as any book does.
- If the copy fails (no space, unreadable) a snackbar says why, and the article stays put.
- Pack documents are not indexed (§2).

## 6. Text for Search and Research

### 6.1 Extraction

`PdfText` in `:core:books` mirrors `XhtmlText`'s contract: deterministic output keyed by location, so the contentless index can re-extract a chunk's text at query time.

- **Library:** PdfBox-Android (`com.tom-roush:pdfbox-android:2.0.27.0`, Apache-2.0, Maven Central), as the v1 spec chose. `PDFTextStripper` runs per page with sorting by position on. It is only used for inspection and text; PDFium renders.
- **Chunks:** `TextChunk(href = "page/<n>", ordinal, progression, text)`, with n starting at 1. A page's text is split into paragraphs on blank lines, then into the same 400–2000-character windows. Short pages merge with nothing: a chunk never spans pages, so a citation always names one page. Hyphenation at line ends is joined (`exam-\nple` → `example`), and running heads and footers that repeat on more than half the pages are dropped, so the scan junk Plan 7 noted doesn't fill the index.
- **Searchable:** fewer than 200 characters of text across the first 20 pages means no text layer: `searchable = 0`, nothing indexed, "Not searchable" shown.
- **Cost:** extraction runs in the existing index worker (`dao.notIndexed`), one book at a time, off the main thread. It is measured on the Pixel 6 with a 300-page Text PDF from archive.org; the bar is under 60 s, and it is resumable if killed.

### 6.2 Index, Search, Research

- `BookIndex.index` and `chunkTexts` dispatch on format. `BookChunk.href` holds `page/<n>`, so nothing in the table changes.
- `PassageAddress` already fits (`book:<origin>:<ref>/page/12@<progression>~<quote>`); progression is within the page.
- `LibraryPassageFinder` labels a PDF passage "In your library as PDF, page 12". Search's "In books" row shows "p. 12".
- `CitationOpener.book`, `SearchViewModel.openBook` and Home cards open at the page through the `StartLocator` PDF branch.
- Home random cards say "Page 12" for a PDF. This also fixes the existing "Chapter ${ordinal+1}" label, which is wrong for EPUBs too, because ordinal restarts in each chapter: it becomes the chapter title.

### 6.3 Dense retrieval

R1's vectors are keyed by `ChunkKey(href, ordinal)` and checked by a hash of the chunk text, so a PDF chunk fits as is. R2 (books embedded on the phone) should include `PdfText` chunks. This pass adds `PdfText` to the R1 chunker contract's version notes only if R1 has merged first. Otherwise it's left for R1's merge.

## 7. Later

- Read-aloud for PDFs (a `ReadSource` over `PdfText`, no on-page marking).
- Indexing pack PDFs: one opt-in per pack, a background job, the pack documents' rows indexed like any book.
- Selection on PDFs, via `androidx.pdf` or a newer Readium.
- OCR for scans (ML Kit is Play-only; Tesseract is large). Weigh it after Plan 10's flavours exist.
- Joining an archive.org text and PDF pair into one book.

## 8. Testing

- **JVM:** `PdfInspector` (metadata, encrypted → Protected, truncated → Damaged, zero pages), `PdfText` (deterministic chunks, page keys, hyphen join, repeated-header removal, scan → not searchable), `ArchiveOffer` (format preference, LCP and private excluded, both offers, preview), sidecar round-trip with `PDF`, `MIGRATION_12_13`, `PassageAddress` with `page/n`, `StartLocator` PDF branch, ZIM PDF MIME routing.
- **Fixtures** (in `testdata/`, small and made for the purpose, under 200 KB each): a 3-page text PDF with an outline and info dictionary, a 2-page image-only PDF, an encrypted (owner-password) PDF, a truncated PDF, and a ZIM entry fixture with `application/pdf` added to the test ZIM.
- **Device (Pixel 6):** import → open → rotate → kill → reopen at the page; Night theme legibility; archive.org Text PDF and Image Container PDF items, the 100 MB prompt; irp.fas.org pack PDF opens, back returns to the article; Search finds a phrase on page 12 and opens there; Research cites a PDF page; the indexing time for a 300-page scan.

## 9. Decisions (Neal, 2026-10-23)

1. **Engine: Readium PDFium.** One reader; no selection on PDF pages in this pass.
2. **The 100 MB prompt stays, with "Don't ask again".** Full-series comic packs on archive.org will easily pass it, and the library should be very personalizable, so the prompt warns once and can be turned off (Settings → Storage & downloads → "Ask before downloads over 100 MB", beside "Download on Wi-Fi only", on by default). No hiding on metered connections.
3. **Text and PDF of one archive.org item are two books** when the user downloads both.
4. **Plan P2**, after S1 on the roadmap.
