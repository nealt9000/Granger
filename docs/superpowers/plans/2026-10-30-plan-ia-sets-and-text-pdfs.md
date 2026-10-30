# Plan IA: archive.org sets and text PDFs — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** two things the 2026-10-30 bug pass left open, so that most archive.org books arrive the way you'd want to read them.
- **Sets.** An item made of several parts (a comic run, a magazine's issues, a book in volumes) says so, and you can get one part or the whole set. The set lands in the Library as one group, in order.
- **Text PDFs become ebooks.** A PDF that was made on a computer (not scanned) is turned into a real EPUB on arrival: flowing text in Aa's typeface and size, chapters, no page headers or page numbers, paragraphs that run across pages, verse kept as verse, and Listen. The PDF stays for its original pages.

Neal, 2026-10-30, after the bug pass:
- "We need to make multi-part items clear, and provide ability to download individual or as a set."
- On the Odyssey (a born-digital PDF that now reads as fixed pages): "Does this apply to all books the app pulls from archive.org going forward, or are you meaning converting this one book? Let's follow up with a plan that solves for the majority of those scenarios."

**Out of this plan:**
- Scanned PDFs (an image of each page, with OCR text behind it). They stay as pages. Their OCR text is what archive.org's own EPUB is made from, which the bug pass found worse than the pages. Making scans into ebooks is a different job (layout analysis on OCR boxes).
- The user's own imported PDFs. See Decision 6: the converter is built so that adding them later is one switch.
- Audio items. They are already one group per item (an album or an audiobook).

**Tech stack:** unchanged (Kotlin 2.4.21, Compose Material 3, Room, PdfBox-Android 2.0.27, Readium 3.4, Robolectric 4.17, JUnit 4 + Truth).
- **Schema:** no Room migration expected; sets use the existing `collections` and `placements` tables. If Task 4 needs a part number on `books`, it is one migration, `library.db` N → N+1.
- **Build env:** `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`. A new worktree needs `git submodule update --init --recursive` and a copy of `local.properties`.
- **Phone:** the Pixel is on Wi-Fi as `adb-21121FDF6007JN-gtbanj._adb-tls-connect._tcp`. One app on the phone: install over `app.granger` only. The phone is shared with other sessions (GB, L1): say when you install.

**Roadmap:** the IA row in `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`.

## What the code looks like now (surveyed 2026-10-30, main `347f1541`)

- **Offers:** `ArchiveOffers.choose(item)` (`core/archive/.../ArchiveOffer.kt`) returns one `Offer.Download(file, kind, pdf?)`. The order is: uploaded EPUB → uploaded Text → uploaded comic (first by name) → uploaded PDF (`bestPdf` of originals) → archive.org's EPUB with the scan's PDF beside it → DjVuTXT with its PDF. `TextKind` is EPUB, TEXT, OCR_TEXT, PDF, COMIC. A multi-part item offers only its first part (bug pass, Decision noted in the code).
- **Downloads:** `ArchiveDownloads.spec()` (`app/.../archive/ArchiveDownloads.kt`).
  - Item id `archive-org:<identifier>`, plus `#pdf` for the PDF beside a text.
  - The file goes to `books/archive-org/<identifier>.<ext>`.
  - One sidecar per identifier, `<identifier>.json` (title, creator, date, subjects, description, language, kind). `fileBookRecord` reads it when the download is filed.
- **Filing:** `RoomBookshelf.addDownloaded()` (`core/library/.../Bookshelf.kt`).
  - A `.txt` is converted to `<name>.epub` beside it (`TextToEpub`).
  - A `.cbz` or `.cbr` is converted to `<name>.pdf` beside it (`ComicToPdf`).
  - The source is kept, and `refresh()` converts any source left unconverted.
  - `remove()` deletes the source with the book.
  - Book id `book:archive-org:<file name without extension>`.
  - Every archive.org book files into the app-made "Archive.org" collection (`Filing.BOOK_COLLECTIONS`).
- **Collections:** `collections` (`Entities.kt:80`) holds named groups within a media type, and `placements` maps a pack or book key to one collection (`userSet` when the user moved it).
- **PDF text:** `PdfText` (`core/books/.../PdfText.kt`) gives plain text chunks per page and has `hasTextLayer()` (200+ characters in the first 20 pages). It keeps no positions, fonts or sizes.
- **PDF outline:** `PdfInspector.outline(file)` gives `OutlineEntry(title, page, depth)`.
- **EPUB writing:** `TextToEpub.convert(text, title, markdown, out)` writes chapters from text or Markdown. There is nothing for images, verse or notes.
- **The item sheet** (`ArchiveItemSheet.kt`) shows Get · size, plus Get PDF · size when there is a PDF beside the text, and the item's status (`ItemStatus`). Audio items already have "Get the rest".
- **Large downloads:** `ConfirmLarge` asks before anything over `DownloadPrefs.LARGE_DOWNLOAD_BYTES` unless "Don't ask again" is set.
- **Natural order:** `ComicToPdf.PAGE_ORDER` (internal to `core/books`) sorts "p2" before "p10".

What real items look like (archive.org metadata, 2026-10-30):

| Item | Originals | Shape |
|---|---|---|
| `BatmanTheDarkKnightReturns03of041986` | 4 × Comic Book RAR, 53–62 MB, in a folder | a run of issues, numbered "01 (of 04)" |
| `national-geographic-magazine-complete-pdf` | 552 × Image Container PDF, about 3 GB | every issue from 1888, plus specials |
| `the-odyssey-homer-emily-wilson-z-library` | 1 × Text PDF, 2.5 MB, Times New Roman fonts | a born-digital book in verse, with an outline |
| `scientific-american-1881-10-15` (test fixture) | 1 × Text PDF from a scan | OCR text behind page images |

A born-digital PDF and a scan's Text PDF are told apart by their fonts:
- archive.org's and Tesseract's OCR layer is drawn in `GlyphLessFont` with text render mode 3 (invisible), over a page-sized image.
- A born-digital PDF draws real, embedded fonts (here `TimesNewRomanPSMT`, `…-BoldMT`, `…-ItalicMT`).

## Decisions

All settled with Neal, 2026-10-30 (D6, D7 and D9 were his calls on the draft).

1. **What a part is.** An item's parts are its originals of the kind `choose()` picked: every comic, every uploaded PDF, every uploaded EPUB or text.
   - Derived files never count. Neither do tiny extras: a part must be at least 10% of the median part's size, so a cover JPEG or a 3-page errata PDF is left out.
   - One part is today's single offer, unchanged.
   - `Offer.Download` gains `parts: List<Part>`, where `Part(file, number, title, sizeBytes)`.
2. **Part titles and order.** A part's title is its file name cleaned:
   - Drop the folder and the extension.
   - Drop scanner tags: parenthesised groups with "digital", "scan", "c2c" or a scanner group's name, matched by a small list plus "(…-…)" patterns.
   - Drop the item title when the name repeats it.
   - Keep the number and the "of n".
   - So "Batman The Dark Knight Returns/Batman - The Dark Knight Returns 02 (of 04) (1986) (digital) (Minutemen-InnerDemons).cbr" becomes "02 (of 04) (1986)", and "National Geographic 1889-02 001-3.pdf" becomes "1889-02 001-3".
   - Order is natural order on the full name. `PAGE_ORDER` moves to `core/text` as `NaturalOrder`, shared by `ComicToPdf` and the parts.
3. **Ids and files for parts.**
   - Part n of item `<id>` has item id `archive-org:<id>~<nn>` (zero-padded to the part count's width).
   - Its file is `books/archive-org/<id>~<nn>.<ext>`, and its book id is `book:archive-org:<id>~<nn>`.
   - `~` is not in archive.org's identifier alphabet, so no part id can collide with an item id.
   - `ArchiveDownloads.identifierOf` takes everything before `~` or `#`.
   - Each part has its own sidecar, `<id>~<nn>.json`, so its title is "Item title · part title".
   - **Legacy (changed while building, 2026-10-30):** a book already filed as `<id>` (Batman's first part from the bug pass) is left as it is, an ordinary Archive.org book; it isn't counted as part 1. Only Neal's phone has one, and Remove plus Get all is simpler than special-casing it in filing and status.
4. **A set in the Library (recommended).** Parts file into a collection named for the item ("Batman The Dark Knight Returns (1986)"), made on the first part and kept while any part is there.
   - The collection's `defaultKey` is `archive-org:<id>`, so filing is idempotent and a user's move (`userSet`) wins, as for every collection.
   - Within it, parts list in part order: the shelf's sort is by title, and the zero-padded numbers keep that right.
   - Alternative: no collection, and the parts sit flat in "Archive.org" with numbered titles. That is simpler, but a 552-issue magazine would swamp the shelf.
5. **The sheet for a set.**
   - The title line says "Comic · 4 parts · 234 MB" (or "PDF · 552 parts · 3.0 GB").
   - Two buttons: **Get all · 234 MB** (the large-download prompt applies to the total, so 3 GB asks) and **Choose parts**.
   - Choose parts opens a list: a filter field once there are more than 20 parts; each row has its part title, size and Get, Downloading, Retry or Open.
   - Get all queues every missing part as one download group, `archive-org:<id>`, so the Downloads page shows the set with Cancel all and Retry all (DL's groups).
   - Search rows can't know the part count before the item's metadata is fetched. They show it once the sheet has been opened (it is cached for the session), and nothing before.
6. **Text PDFs: what is converted (recommended).** An uploaded archive.org PDF is converted when it is born-digital:
   - it has a text layer;
   - at most 10% of its text is drawn in `GlyphLessFont` or render mode 3;
   - fewer than 30% of the sampled pages are covered by one page-sized image.
   - Scans, comics and image-only PDFs stay PDFs.
   - **Imports (Neal: agreed):** not by default, since you chose a PDF. A ⋯ "Read as ebook" on any text PDF converts it on request (Task 8).
7. **Keep the PDF beside the ebook, with a way to remove it (Neal, 2026-10-30).**
   - The EPUB is the book. The PDF stays on disk, and the reader's ⋯ menu offers **Original pages**, which opens the PDF at the same chapter.
   - The same menu and the book's details offer **Remove original pages · 2.5 MB**, which deletes the PDF and keeps the ebook. Original pages then goes from the menu.
   - The PDF's size is the cost of keeping it (the Odyssey's is 2.5 MB; a born-digital textbook can be 50 MB).
8. **A conversion that doesn't come out well stays a PDF.** The quality gate (Task 7) scores every conversion. A PDF that fails it is filed as a PDF exactly as today, with a log line saying why. A bad ebook is worse than good pages.
9. **Existing archive.org PDFs: converted on request (Neal: agreed).** Books already in the Library aren't converted behind your back, because their place, bookmarks and highlights are on PDF pages. An archive.org PDF that would convert gets ⋯ "Read as ebook" (the same action as Decision 6). Its place carries over by chapter, then by progression; its highlights don't.

## The converter (`PdfToEpub`, `core/books`)

It is written as stages, each testable alone, with PdfBox's `PDFTextStripper` subclassed to keep `TextPosition`s (x, y, width, font, size, and bold or italic from the font name and flags).

1. **Lines.** Group the page's positions into lines by baseline, in reading order. A page with two text columns (a gap running the page's height between two x clusters) is read column by column. A page with three or more columns, or tables, marks the book "complex", and the quality gate weighs that.
2. **Body style.** The most common font size (by characters) is the body. A line in a size at least 1.2× the body, or bold and short and alone, is a heading candidate. A line in a size at most 0.85× the body, in the bottom band, after a rule or a gap, is a footnote.
3. **Running heads, feet and page numbers.**
   - A line in the top or bottom 8% of the page whose text, with digits removed and case folded, repeats on at least 25% of pages is dropped. The same goes for alternating left and right page heads.
   - So is a line that is only a number (arabic or roman) in those bands.
4. **Blocks.** Consecutive lines form a block until there is a vertical gap above 1.5× the line spacing, an indent change, or a font change.
5. **Verse or prose, per block.**
   - Verse: at least 70% of the block's lines end before 80% of the text width, without a hyphen, and the lines don't start indented as paragraphs do. Each line stays a line (`<br/>`); a big indent is kept as an `em` margin.
   - Prose: lines are joined. A line ending in a hyphen joins the next without it when the next starts lowercase; otherwise the hyphen is kept ("well-\nknown" stays "well-known" when the joined word is hyphenated elsewhere in the book).
   - A new paragraph starts at a first-line indent, or after a short line ending in . ! ? : ” or ’.
   - Paragraphs continue across pages: a block that ends a page without a sentence end joins the first block of the next page.
6. **Chapters.**
   - From the outline when there is one. Each entry at the top used depth starts a chapter at its page, and splits mid-page at the line matching its title (fuzzy: case, punctuation and spacing ignored). Deeper entries become `h2` and `h3` inside the chapter.
   - Without an outline: heading candidates that start a page, or follow a gap of a third of a page, start chapters.
   - Failing both: one chapter per 20 pages, titled "Pages 1–20".
   - The EPUB's nav lists them all, nested.
7. **Pictures.** Images drawn on a page (`PDImageXObject`, at least 1/16 of the page's area) are written as JPEG or PNG at their drawn position between blocks, with the caption below when a short italic or smaller line follows. Page 1's full-page image becomes the cover.
8. **Notes.** Footnotes gather at the end of their chapter under "Notes", in order. The superscript markers stay as they are (no links in v1).
9. **Write.** XHTML per chapter with Granger's book CSS classes, so Aa's typeface and size apply. The title, author and language come from the sidecar, then the PDF's info. Output is through a `.incoming-` partial, like the other converters.

Every stage returns numbers for the gate:
- lines dropped as heads and feet;
- the share of text kept, which must be at least 92% of the stripper's raw character count less what was dropped;
- paragraphs per page;
- the median paragraph length;
- chapters found, and how (outline, headings or pages);
- whether any pages were complex.

## File structure

**Create:**
- `core/text/src/main/kotlin/app/granger/core/text/NaturalOrder.kt` (moved from `ComicToPdf.PAGE_ORDER`).
- `core/archive/src/main/kotlin/app/granger/core/archive/ArchiveParts.kt`, with `Part` and part titles.
- `core/books/src/main/kotlin/app/granger/core/books/PdfToEpub.kt`, the stages above. If it outgrows one file, the stages go in `pdftoepub/`: `Lines.kt`, `Blocks.kt`, `Chapters.kt`, `Writer.kt`.
- `core/books/src/main/kotlin/app/granger/core/books/PdfOrigin.kt`, which tells born-digital from scanned (Decision 6).
- `core/books/src/testFixtures/kotlin/app/granger/core/books/TextPdfFixtures.kt`: PDFs made with PdfBox for each case (running heads, hyphenation, verse, two columns, outline, footnotes, a figure, a scan-like `GlyphLessFont` page).
- `tools/pdf-eval/`: a corpus list (`corpus.tsv`: identifier, file, kind, why it's there) and a Robolectric eval test that converts every corpus PDF found under `$PDF_EVAL_DIR` and writes `report.html` (metrics plus the first pages of each EPUB, side by side with the PDF's page images).
- Tests: `ArchivePartsTest`, `NaturalOrderTest`, `PdfOriginTest`, `PdfToEpubTest` (one test per stage, using the fixtures), `BookshelfTest` additions, `ArchiveViewModelTest` additions, and `ArchiveItemSheetTest` for the parts list.

**Modify:**
- `ArchiveOffer.kt`: parts, and `TextKind.PDF_TEXT` for a born-digital PDF (an ebook made on the phone).
- `ArchiveDownloads.kt`: part ids, part sidecars, `identifierOf`.
- `ArchiveViewModel.kt`: Get all, get part n, statuses per part, the cached part count for search rows.
- `ArchiveItemSheet.kt`: the set line, Get all, the Choose parts list.
- `Bookshelf.kt`:
  - In `addDownloaded`, a `PDF_TEXT` download is converted to `<name>.epub` beside it and the EPUB is filed, falling back to the PDF if the gate fails.
  - In `refresh()`, an unconverted one is converted.
  - `remove()` deletes the PDF too.
- `Filing.kt`: the set collection.
- `GrangerApp.kt`: `fileBookRecord` uses part sidecars and places parts in their set.
- The ebook reader's ⋯ menu gets Original pages and Remove original pages (Decision 7); a PDF's gets Read as ebook (Decisions 6 and 9).
- Roadmap: the IA row.

## Tasks

Two parts, in order: sets first, since they are smaller and shippable alone, then text PDFs. Each task ends green (`./gradlew testDebugUnitTest` plus the three JVM modules) and committed.

### Part 1: sets

### Task 0: Corpus and fixtures

- [x] Save the metadata JSON of `BatmanTheDarkKnightReturns03of041986` and `national-geographic-magazine-complete-pdf` as `core/archive` test fixtures (trim NatGeo to 40 files, keeping the specials and a year boundary).
- [ ] (Moved to the start of part 2, 2026-10-30.) Find and list in `tools/pdf-eval/corpus.tsv` about 25 archive.org items, with kinds and sizes and nothing downloaded into the repo:
  - comic runs (CBR and CBZ, one with a RAR5 part);
  - magazine runs;
  - a book in volumes;
  - one item whose "parts" are chapters;
  - born-digital PDFs: a novel, verse (the Odyssey), a textbook with figures, a two-column paper, a footnoted history, a cookbook with tables, a children's picture book;
  - two scans' Text PDFs, as controls.
- [x] Commit: "Plan IA Task 0: archive.org sets and text PDFs to test against".

### Task 1: Parts and natural order (`core`)

- [x] Move `PAGE_ORDER` to `core/text` `NaturalOrder` and point `ComicToPdf` at it, with no change in behaviour (its test moves too).
- [x] `ArchiveParts.of(item, kind)` (Decisions 1 and 2), with cleaned titles. `choose()` fills `Offer.Download.parts`, with its file still the first part.
- [x] Tests: Batman gives 4 parts in order with titles "01 (of 04) (1986)"…; NatGeo (fixture) gives 40 parts in date order, specials last; a 3-page errata PDF beside a 300-page book is not a part; a single-file item has one part; derived EPUBs are never parts.
- [x] Commit.

### Task 2: Getting and filing parts

- [x] `ArchiveDownloads.spec(item, part)`, part item ids, files and sidecars (Decision 3), and `identifierOf` for `~` and `#`.
- [x] Get all queues the missing parts as group `archive-org:<id>`. Get part n queues one.
- [x] Filing: a part is placed in its set's collection (Decision 4), created on the first part. (A legacy `<id>` book stays as it is: Decision 3.)
- [x] `refresh()` files stray parts (`<id>~nn.*`) with their sidecars. `remove()` and Remove all take parts and their sources.
- [x] Tests (Robolectric, `BookshelfTest`, `ArchiveViewModelTest`):
  - two of four parts get, land in the set's collection, titled and ordered;
  - Get all skips the installed ones;
  - the legacy book counts as part 1;
  - removing the last part removes the empty set collection;
  - a user's move of the set survives a new part arriving.
- [x] Commit.

### Task 3: The sheet for a set

- [x] The set line, Get all · total (with the large prompt on the total), and Choose parts (Decision 5): a filter over 20 parts, and each row's status and action, from the downloads ledger and the shelf.
- [x] The search row shows "n parts" once known.
- [x] Tests: `ArchiveItemSheetTest` for the line, buttons and row states; the large prompt for NatGeo's total.
- [ ] Phone check (say on the channel first):
  - get Batman's parts 2 and 3 one at a time, then Get all (only part 4 is fetched);
  - the set in the Library, in order;
  - NatGeo: filter "1889" and get two issues;
  - Get all asks about 3 GB, then cancel.
- [ ] Commit, then a roadmap note: "IA part 1 done".

### Part 2: text PDFs become ebooks

### Task 4: Born-digital or scanned (`PdfOrigin`)

- [ ] `PdfOrigin.of(file)`: BORN_DIGITAL, SCAN or IMAGES (Decision 6), from the fonts, text render modes and image coverage of up to 12 pages spread through the file.
- [ ] Tests on fixtures: an embedded-font page is BORN_DIGITAL; a `GlyphLessFont` invisible text over an image is SCAN; a picture-only page is IMAGES. On the corpus (eval): every born-digital item is BORN_DIGITAL and both scan controls are SCAN.
- [ ] Commit.

### Task 5: Lines, heads and feet, blocks, verse and prose (converter stages 1–5)

- [ ] The `PDFTextStripper` subclass keeping positions; lines; body style; running heads, feet and page numbers dropped; blocks; verse kept; prose joined with hyphenation; paragraphs across pages; two columns read in order.
- [ ] One test per rule on `TextPdfFixtures`, including a verse page from the Odyssey's layout (short lines, a centred book heading) and a prose page with a running head, an alternating page head and a folio.
- [ ] Commit.

### Task 6: Chapters, pictures, notes, writing the EPUB (stages 6–9)

- [ ] Chapters from the outline with mid-page splits, the heading fallback, the pages fallback; figures with captions; the cover; notes at chapter ends; XHTML and nav; metadata from the sidecar.
- [ ] Tests:
  - outline chapters split at the right line;
  - nested contents;
  - a figure lands between the right paragraphs;
  - footnotes gather under Notes;
  - the EPUB passes `EpubInspector` and opens in Readium in the reader test;
  - Listen's `BookReadSource` reads it.
- [ ] Commit.

### Task 7: The quality gate and the eval

- [ ] Gate thresholds from the stage metrics (Decision 8), tuned on the corpus: a book passes when its text kept, paragraph lengths and chapters look like a book. A complex layout passes only with good numbers everywhere else.
- [ ] `tools/pdf-eval` writes the report. Neal reads it for a dozen books before anything ships: the Odyssey, the novel, the textbook, the two-column paper and the picture book at least.
- [ ] Target: at least 80% of the born-digital corpus passes and reads well in the report; every failure fails the gate (it is filed as a PDF), never a bad ebook.
- [ ] Commit with the report's numbers in the message.

### Task 8: Wiring it in

- [ ] `choose()` offers `PDF_TEXT` for an uploaded born-digital PDF. This can only be confirmed after the download, so the offer says "PDF, read as an ebook when it can be"; `PdfOrigin` decides at filing time.
- [ ] `addDownloaded` converts and files the EPUB (or the PDF on a gate failure). `refresh()` converts strays. `remove()` takes both.
- [ ] Original pages in the reader's ⋯ (Decision 7), opening the PDF at the chapter's first page; Remove original pages · size there and in the book's details, which deletes the PDF and keeps the ebook (the source no longer counts as unconverted on `refresh()`).
- [ ] Read as ebook on text PDFs already in the Library, archive.org's and imports alike (Decisions 6 and 9), with the gate deciding as on arrival.
- [ ] Tests: `BookshelfTest` (a born-digital PDF becomes an EPUB with the PDF beside it; a scan stays a PDF; a gate failure stays a PDF; Remove original pages deletes the PDF and a refresh doesn't convert again; Read as ebook on an import); `EbookReaderViewModelTest` (Original pages opens the PDF at the chapter's page).
- [ ] Commit.

### Task 9: Walkthrough on the Pixel (say on the channel first)

- [ ] Get the Odyssey again. It should read as an ebook in Aa's typeface, with books I–XXIV as chapters, verse lines kept, no running heads, Listen working, and Original pages opening the PDF.
- [ ] Get two more from the corpus (a textbook with figures, a novel) and one scan control, which stays pages.
- [ ] Time the conversion on the phone for the textbook; the bar is under 30 s, with the Library row showing "Preparing…" until it's done.
- [ ] Neal's walkthrough, then merge, push, and the roadmap row done.

## Risks

- **Verse versus prose** is the heart of the Odyssey case, and a ragged-right novel can look like verse. The rule needs most lines short and no paragraph indents; the eval's report is where a misjudgement shows.
- **Two columns, tables and sidebars** (cookbooks, textbooks) are where conversions go wrong. The gate keeps those as PDFs rather than guessing; the eval shows how many.
- **Conversion time and memory on the phone** for a 50 MB textbook. PdfBox runs with temp-file buffering (`loadPdf`), one page at a time, and the conversion runs on the existing book-filing path off the main thread. Task 9 times it.
- **Huge sets** (552 issues): the parts list must stay smooth (a lazy list, the filter), and Get all of 3 GB goes through the one download queue with the large prompt.
