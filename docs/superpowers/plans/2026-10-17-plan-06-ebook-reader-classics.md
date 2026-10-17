# Granger Plan 6: E-book Reader + Classics EPUBs: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Books read like books. One Readium-based e-book reader (paginated, text settings, Day/Sepia/Night, contents, bookmarks, highlights, notes, dictionary, search in book, selection actions) for every e-book. Granger Classics is re-released as one EPUB 3 per work. The user can import EPUB, TXT and Markdown into *My books*. Search covers e-book text.

**Architecture:**
- A new `:core:books` module has pure parsing code with no Readium in it:
  - EPUB inspection: metadata, cover, spine, and DRM detection.
  - Conversion of TXT and Markdown into a minimal EPUB 3, so the reader only ever opens EPUBs.
  - XHTML text chunking for the search index.
  - It fills the role the spec calls `:core:imports`.
- `:core:library` moves to schema v4 and gains:
  - `books`, `book_positions`, `book_marks` and `book_chunks` tables.
  - A contentless FTS4 table, `book_text`. This is FTS4, not the spec's FTS5, because Android's platform SQLite doesn't guarantee FTS5. Contentless keeps the index near a third of the text's size; snippets are re-read from the EPUB.
  - A `Bookshelf` façade for downloads, imports, positions and marks.
  - A `BookIndex` that indexes and searches e-book text.
- Books use the existing collection, rail-tab and history machinery:
  - A book's id is `book:<origin>:<ref>`.
  - Its rail tab is `(packId = book.id, path = "")`, so each book takes one spot.
  - It is filed by origin.
- `:app` hosts Readium's `EpubNavigatorFragment` in Compose behind a new `BookRoute`, with the same immersive and rail behaviour as the web-style reader.
- `tools/pack-builder` gains an EPUB writer. `tools/catalog-gen` publishes the books as a new `books` array in the format-1 catalog. Older apps ignore unknown keys, so this needs no format bump.
- Builder's call (spec §8): no single archive bundle of the whole set. The `granger-classics` preset queues the individual books.

**Tech Stack:**
- Existing: Kotlin 2.4.21, Compose BOM 2026.09.00, Room 2.8.5, Robolectric 4.17.
- New on Android:
  - Readium Kotlin Toolkit **3.4.0** (BSD-3): `org.readium.kotlin-toolkit:readium-shared`, `readium-streamer` and `readium-navigator`.
  - `androidx.fragment:fragment-compose` **1.9.0**.
  - jsoup **1.21.2** (MIT).
  - commonmark-java **0.30.0** (BSD-2).
- New in Python: Pillow **12.3.0** (covers). The build is checked with W3C epubcheck **5.2.1**, a Java jar that is downloaded and not committed.
- Test device: Pixel 6.

**Spec:** `docs/superpowers/specs/2026-10-16-library-redesign-design.md`, in particular:
- §6: the e-book reader.
- §8: Classics as e-books.
- §4: filing, *My books* and Search.
- §11: the book part of the data model.
- §13: the cap.

Base spec: `docs/superpowers/specs/2026-10-15-granger-v1-design.md` (import rules, never remove DRM). Roadmap: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`. Previous plan: `2026-10-16-plan-05-shell-library.md`, whose shell, rail, `ReadingSessions`, `Shelves` and `LaunchDecider` this plan extends.

## Global Constraints

- Android 10+ (`minSdk = 29`), `compileSdk = 37`, `targetSdk = 36`, Java 17. These are unchanged. Readium 3.4.0 needs API 24+, so it fits.
- **One reader for every e-book**: Classics, imports, and archive.org in Plan 7. Rendering uses Readium, never a hand-rolled WebView or OPF renderer.
- **Paginated only**: swipe, or tap the left or right edge, to turn the page. There is no continuous scroll in v1. A tap in the middle shows or hides the controls and the rail.
- **The user's settings win over the book's styles**: `publisherStyles = false` always.
- **Reader themes are exactly Day, Sepia and Night.** Night uses the app's colour tokens. Night is the default.
- **Selection menu order is exactly:** Copy · Highlight · Note · Define · Search · Inquire & Abstract.
  - Define is hidden when no dictionary is available.
  - Inquire & Abstract is hidden while `Features.INQUIRE = false`.
- **Reader bar order is exactly:** Contents · Text settings · Read-aloud · Bookmark. Read-aloud is behind `Features.READ_ALOUD = false`: a code path with a reserved slot, not missing code.
- **Highlight colours:** yellow, green, blue, pink.
- **The reader never touches the network.** The dictionary never needs one.
- **Never remove DRM.** Protected files (LCP, Adobe ADEPT, any non-font-obfuscation `encryption.xml`) are refused with a message. Nothing is copied.
- **Classics: one EPUB 3 per work.**
  - Released on a GitHub release tagged `classics-<YYYY-MM-DD>`.
  - Each book has its own catalog entry and sha256.
  - A preset with id `granger-classics` titled `"Granger Classics: all books"` queues them all.
  - The Classics ZIM entry leaves the catalog. An installed Classics ZIM keeps working and is labelled superseded.
- **Free cap:** downloaded books count like packs. Imports don't count, and imports stay Pro-only: Plan 10 adds that gate; this plan ships `Features.IMPORT = true`.
- User-facing names: "My books", "Granger Classics", "Contents", "Text settings", "Bookmark", "Bookmarks", "Highlights", "Highlight", "Note", "Define", "Search", "Inquire & Abstract", "Day", "Sepia", "Night", "Import books…".
- Gradle on this machine (5.7 GB RAM):
  - Always pass `-Pkotlin.compiler.execution.strategy=in-process`.
  - Run one build at a time, then `./gradlew --stop`.
  - The commands below write `./gradlew …` for short; add the flag.
- `connectedDebugAndroidTest` uninstalls the app and wipes Neal's packs. Do not run it without Neal's OK. This plan adds no new device tests; device checks are manual.
- Outward-facing steps need Neal's OK first: creating the GitHub release, pushing `catalog/` to `main`, and pushing any branch.
- Every commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Saved places after a Classics rebuild.** A later build can split chapters differently, so a stored locator or highlight may name a `cNNN.xhtml` that no longer exists. Expected behaviour:
   - The book opens near the same place, using its total progression, instead of failing or jumping to the start.
   - A highlight that can't be placed stays in the Highlights list, marked "Can't find this passage in the current edition".

   Tests: `staleHrefFallsBackToTotalProgression`, `unplaceableHighlightIsListedNotDrawn` (Tasks 8, 10).
2. **Importing something that isn't a readable e-book**: an LCP or Adobe-protected EPUB, a PDF renamed `.epub`, a zip with no OPF, an empty file. Each gets one clear message, and no file is left in `books/imports/`. The other files picked in the same import still import.

   Tests: `DrmCheckTest` (all kinds), `importRejectsProtectedAndLeavesNoFile`, `oneBadFileDoesNotStopTheOthers` (Tasks 4, 6, 11).
3. **Rotation and process death inside a book.** Rotating keeps the page. If Android kills the backgrounded app, reopening it lands on the same page. The Readium fragment, restored before the book reopens, must not crash the app.

   Tests: `startLocatorPrefersRouteTargetThenSavedThenStart` (Task 8). Manual: the rotation and `am kill` device check (Task 8).
4. **Indexing interrupted** by the app being killed partway through the 347 Classics. Expected behaviour:
   - Every book is readable at once; indexing never blocks reading.
   - The next start resumes with the books not yet indexed.
   - A re-indexed book never shows its hits twice.
   - A removed book's text never shows up in results.

   Tests: `indexingIsResumableAndIdempotent`, `removedBookNeverMatches`, `reindexedBookMatchesOnce`, `compactionRebuildsWhenOrphansDominate` (Task 6).
5. **Plain-text files from the wild**: a 5 MB TXT with no chapter headings, a Windows-1252 TXT with curly quotes, a UTF-16 file with a BOM, a Markdown file containing raw `<script>`, an EPUB with no `dc:title`. Expected behaviour:
   - Each opens as a paginated book, decoded correctly, split into readable chapters, with no script.
   - It is titled from its metadata, or from the file name when there is none.

   Tests: `TextToEpubTest`, `TextDecodingTest`, `EpubInspectorTest.missingTitleUsesFileName` (Task 4).

---

## File Structure

```
tools/pack-builder/
  epubpack.py             (new) EPUB 3 writer, display titles, CSS scrub, XHTML serialisation
  covers.py               (new) generated covers (Pillow)
  assets/flame-mark.png   (new) the mark, rasterised once from app/src/main/res/drawable/granger_mark.xml
  assets/fonts/           (new) one OFL serif font + OFL.txt, for covers
  chapters.py             page_href parameter; keep ids when unwrapping links; clean titles
  gutenberg.py            keep ids when unwrapping scheme links
  pack_builder.py         build-epubs, describe-epubs commands
  releases/granger_classics_books.json   (new, generated)
  tests/test_epubpack.py, tests/test_covers.py  (new); test_chapters.py, test_gutenberg.py extended
tools/catalog-gen/        books array, bookRelease curated kind, granger-classics preset; ZIM entry dropped
catalog/catalog.json(.sig)  regenerated, re-signed

core/books/               (new module, Android library, no Readium)
  DrmCheck.kt, EpubInspector.kt, TextDecoding.kt, TextToEpub.kt, XhtmlText.kt, Snippets.kt
  src/testFixtures/.../TestEpubs.kt (EPUB builder for tests, shared with :core:library)

core/catalog/.../Catalog.kt, CatalogParser.kt   BookEntry, books
core/downloads/.../DownloadRecord.kt, Downloads.kt, Volumes.kt   ItemKind.BOOK, target path, cap, sizes

core/library/.../
  Entities.kt        + Book, BookOrigin, BookPosition, BookMark, MarkKind, BookChunk
  Daos.kt            + BookDao
  LibraryDatabase.kt version 4, MIGRATION_3_4, createBookText()
  BookIds.kt         (new)
  Bookshelf.kt       (new) Bookshelf + RoomBookshelf, ImportResult
  BookIndex.kt       (new) indexing, search, compaction; BookHit
  Filing.kt          book filing by origin; superseded ZIMs
  Shelves.kt         CollectionRow.books, moveBook
  LibrarySearch.kt   books(query)

app/src/main/kotlin/app/granger/
  ebook/BookRoute + EbookReaderScreen.kt, EbookReaderViewModel.kt   (new)
  ebook/ReadiumBooks.kt         (new) BookOpener, BookSearcher over Readium; OpenBooks holder
  ebook/NavigatorHost.kt        (new) fragment host + ReadiumFragmentFactory
  ebook/StartLocator.kt         (new) pure start-place choice + repair
  ebook/ReadingPrefs.kt         (new) prefs model, store, → EpubPreferences
  ebook/ReaderControls.kt       (new) top bar, slider, reader bar
  ebook/ContentsPanel.kt, TextSettingsSheet.kt, BookSearchPanel.kt, HighlightSheet.kt  (new)
  ebook/SelectionMenu.kt        (new) action-mode callback
  ebook/Dictionary.kt           (new) dictionary choice + lookup
  library/…                     cover grid, books in collections, import, superseded ZIM
  library/ImportRunner.kt       (new)
  shell/…                       covers + book subtitles on the rail; BookRoute is a reading route
  search/…                      "In books" section
  launch/LaunchDecider.kt       books in the launch decision
  sources/…                     books in the Granger source
  Features.kt, MainActivity.kt (FragmentActivity), GrangerApp.kt, GrangerNavHost.kt
```

---

### Task 1: Pack builder writes one EPUB 3 per work

**Files:**
- Create: `tools/pack-builder/epubpack.py`, `tools/pack-builder/covers.py`, `tools/pack-builder/assets/flame-mark.png`, `tools/pack-builder/assets/fonts/<font>.ttf` + `OFL.txt`, `tools/pack-builder/tests/test_epubpack.py`, `tools/pack-builder/tests/test_covers.py`
- Modify: `chapters.py`, `gutenberg.py`, `requirements.txt` (`Pillow==12.3.0`), `tests/test_chapters.py`, `tests/test_gutenberg.py`

**Interfaces:**
- Consumes: `gutenberg.clean`, `chapters.split`, `zimpack.Work`, `manifest.WorkSpec`.
- Produces:
  - `chapters.split(body, split_at=None, max_bytes=150_000, page_href: Callable[[int], str] = page_name) -> list[Chapter]`. Cross-page links are written as `page_href(target) + "#" + fragment`. The ZIM build keeps the default.
  - `chapters.clean_title(title: str, work_title: str, author: str, index: int) -> str`. It splits on `" — "` and drops these segments:
    - segments matching `^by\s`, case-insensitive
    - `Contents` / `Table of Contents`
    - segments equal to the work title
    - literal markers like `<1>`, removed with `re.sub(r"<\d+>", "")`.

    If nothing is left, it returns `f"Part {index + 1}"`.
  - When a link is unwrapped (in `gutenberg.clean` and in `chapters._finish`), an `id` or `name` it carried moves to a `<span id="…">` in its place, so TOC links keep their targets.
  - `epubpack.display_title(work: Work) -> str`: `work.title`, or `f"{work.title} (tr. {work.translator})"` when there is no author and there is a translator.
  - `epubpack.scrub_css(css: str) -> str` removes:
    - every `color`, `background` and `background-*` declaration
    - every `font-family` declaration
    - every rule whose selector mentions a class in `chapters.PAGE_NUMBER`.

    It parses rule by rule with a regex and doesn't need a CSS library.
  - `epubpack.to_xhtml(html: str) -> str` parses with `lxml.html` and serialises with `method="xml"`. The output must parse with `lxml.etree.fromstring` inside a `<div>`.
  - `epubpack.book_id(slug: str) -> str` returns `f"gc-{slug}"`.
  - `@dataclass BookMeta(id, title, author, translator, published: int, section_title: str, language: str /* BCP 47, "en" */, source_url: str, date: str)`.
  - `epubpack.write_epub(out: Path, work: Work, meta: BookMeta, cover_jpeg: bytes) -> None` writes:
    - `mimetype`, stored uncompressed as the first zip entry
    - `META-INF/container.xml`
    - `OEBPS/content.opf`, EPUB 3, with:
      - `dc:identifier` = `urn:granger:<id>`
      - `dc:title` = the display title
      - `dc:creator` (role `aut`) when there is an author
      - `dc:contributor` (role `trl`) for the translator
      - `dc:date` = the published year
      - `dc:subject` = the section title
      - `dc:language`, `dc:publisher` = `Granger`, `dc:source`, `dc:rights` = `Public domain in the USA.`
      - `dcterms:modified`
      - `belongs-to-collection` = `Granger Classics`
      - the cover image with `properties="cover-image"`
    - `OEBPS/nav.xhtml` (TOC and landmarks)
    - `OEBPS/toc.ncx`
    - `OEBPS/cover.xhtml`
    - `OEBPS/c001.xhtml…`, using `page_href = lambda i: f"c{i+1:03d}.xhtml"`
    - `OEBPS/book.css`: `epubpack.BOOK_CSS` plus the scrubbed work styles.

    Chapter pages carry no in-text navigation. Elements with a `PAGE_NUMBER` class are removed.
  - `covers.cover_jpeg(title: str, author: str, size=(600, 900)) -> bytes`. It draws:
    - a near-black ground (`#0E0E0E`)
    - the flame mark, centred in the upper third
    - the title in warm off-white (`#ECE6DA`), wrapped and shrunk to fit at most 5 lines
    - the author below
    - a thin flame-orange (`#E8601C`) rule.

    JPEG, quality 85. Builder's call on the rest of the design.

- [ ] **Step 1: Failing tests:**
  - `test_epubpack.py`:
    - `test_two_korans_have_distinct_titles`: Rodwell gives `"The Koran (tr. J. M. Rodwell)"`, Sale gives `"The Koran (tr. George Sale)"`.
    - `test_scrub_css_drops_colours_fonts_and_pagenum` (input `body { background: initial; color: initial } a { color: blue } .pagenum { position: absolute } p { margin: 0 }` gives only the `p` rule).
    - `test_epub_layout`: write the `pg-modern.html` fixture as a work. The zip's first entry is `mimetype` (stored, content `application/epub+zip`). The OPF has the metadata above. `nav.xhtml` lists every chapter title. Every `cNNN.xhtml` parses as XML. No chapter contains `class="nav"` or a page-number element.
    - `test_cross_chapter_links_point_at_xhtml_files`.
  - `test_chapters.py`:
    - `test_clean_title_drops_byline_and_contents` (`"by William Shakespeare — Contents"` gives `"Part 1"`; `"ACT I — Scene II"` is unchanged).
    - `test_clean_title_strips_numeric_markers` (`"The Knight's Tale <1>"` gives `"The Knight's Tale"`).
    - `test_unwrapped_link_keeps_its_id`.
  - `test_gutenberg.py`: `test_unwrapped_scheme_link_keeps_its_id`.
  - `test_covers.py`: `test_cover_is_a_600x900_jpeg` and `test_long_title_fits` (a 200-character title still renders; open it with Pillow and check the size).
- [ ] **Step 2:** `cd tools/pack-builder && python -m pytest -q` gives the new tests FAILING; the existing ones still pass.
- [ ] **Step 3: Implement.** Rasterise the mark once: convert the `granger_mark.xml` path data to SVG and render it at 512 px with any tool. Commit the PNG, not the tool. Download one OFL serif font (for example EB Garamond) with its `OFL.txt`.
- [ ] **Step 4:** `python -m pytest -q` passes, including the ZIM tests, which are unchanged.
- [ ] **Step 5: Commit** `Pack builder: EPUB 3 output with covers, clean titles, scrubbed styles and kept anchors`.

---

### Task 2: Build, check and publish the Classics e-books; catalog entries

**Files:**
- Modify: `tools/pack-builder/pack_builder.py`, `tools/pack-builder/tests/test_pack_builder.py`, `tools/catalog-gen/catalog_gen.py`, `tools/catalog-gen/curated.json`, `tools/catalog-gen/test_catalog_gen.py`
- Create (generated): `tools/pack-builder/releases/granger_classics_books.json`, `catalog/catalog.json`, `catalog/catalog.json.sig`

**Interfaces:**
- Consumes: Task 1's `write_epub`, `cover_jpeg`, `book_id`, `display_title`.
- Produces:
  - `pack_builder.py build-epubs --manifest M --out-dir D [--date YYYY-MM-DD]` writes `D/gc-<slug>_<date>.epub` for every work. A failure of any kind names the work: wrap every exception as `f"{slug} (#{gutenberg}): {error}"`, not only `CleaningError`.
  - `pack_builder.py describe-epubs D --release-url-base URL --manifest M` writes `releases/granger_classics_books.json`:

    ```json
    {"releaseDate": "YYYY-MM-DD", "collection": "granger-classics",
     "books": [{"id": "gc-hamlet", "title": "...", "author": "...", "translator": null, "published": 1603,
                "section": "Drama", "language": "en", "sizeBytes": 0, "sha256": "...",
                "urls": ["<base>/gc-hamlet_<date>.epub"]}]}
    ```

  - Catalog `books` array. Each entry is:

    ```
    {id, title, author, description: "", category: <section title>, language, sizeBytes, sha256, urls,
     releaseDate, license: "Public domain (US)", collection: "granger-classics", published}
    ```

  - A curated item of the new kind, `{"bookRelease": "tools/pack-builder/releases/granger_classics_books.json", "license": "Public domain (US)"}`, gives:
    - every book entry
    - a preset `{"id": "granger-classics", "title": "Granger Classics: all books", "items": [every book id]}`.
  - The `granger_classics_en` pack entry is removed from `curated.json`. In the Starter and Full presets, its id is replaced by the `granger-classics` book ids.

- [ ] **Step 1: Failing tests:**
  - `test_pack_builder.py`:
    - `test_build_epubs_writes_one_file_per_work` (two-work fixture manifest, cached fixtures).
    - `test_build_failure_names_the_work` (a fixture that makes `chapters.split` raise `ValueError`; the message starts with the slug).
    - `test_describe_epubs_hashes_and_lists`.
  - `test_catalog_gen.py`:
    - `test_book_release_adds_books_and_preset`.
    - `test_starter_preset_swaps_classics_zim_for_books`.
- [ ] **Step 2:** Run both test suites. The new tests FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Both suites pass.
- [ ] **Step 5: Build** the real collection from the warm Gutenberg cache at `~/.cache/granger/gutenberg/`: `python pack_builder.py build-epubs --manifest classics/manifest.json --out-dir ~/granger-build/classics-epub`. Then check every file:
  - Download `epubcheck-5.2.1.zip` from `https://github.com/w3c/epubcheck/releases` into the scratchpad.
  - Run `java -jar epubcheck.jar <file>` over every file.
  - Expected: 347 files, **0 errors** in each (warnings are allowed; list them in the report).

  Record the total size and the largest book.
- [ ] **Step 6: Ask Neal** before publishing. With his OK:
  - Run `gh release create classics-<date> ~/granger-build/classics-epub/*.epub --title "Granger Classics e-books <date>"`.
  - Run `describe-epubs` with `--release-url-base https://github.com/nealt9000/Granger/releases/download/classics-<date>`.
  - Run `catalog_gen.py build`, and sign with `~/.config/granger/catalog-signing.key`.
  - Commit. Push `catalog/` only after Neal's OK.
- [ ] **Step 7: Commit** `Classics as e-books: build-epubs and describe-epubs, catalog books and the Granger Classics preset`.

---

### Task 3: Catalog `BookEntry` and book downloads

**Files:**
- Modify: `core/catalog/.../Catalog.kt`, `CatalogParser.kt`, tests `CatalogParserTest.kt`, `PublishedCatalogTest.kt`, `TestCatalogs.kt`
- Modify: `core/downloads/.../DownloadRecord.kt`, `Downloads.kt`, `Volumes.kt`, tests `DownloadManagerTest.kt`, `QueuePolicyTest.kt` if touched

**Interfaces:**
- Produces:
  - The entry type:

    ```kotlin
    @Serializable data class BookEntry(
      override val id: String, override val title: String, val author: String = "",
      val description: String = "", val category: String, val language: String,
      override val sizeBytes: Long, override val sha256: String, override val urls: List<String>,
      val releaseDate: String, override val license: String, val collection: String,
      val published: Int? = null,
    ) : CatalogItem
    ```

  - `Catalog.books: List<BookEntry> = emptyList()`.
    - `items` = `models + packs + books`.
    - The parser validates books like packs: `valid()` plus `DATE` on `releaseDate`.
    - Ids are distinct across all three lists.
    - Presets may reference book ids.
    - `FORMAT_VERSION` stays 1.
  - `enum class ItemKind { PACK, MODEL, BOOK }`.
    - `DownloadManager.targetFile` for a `BookEntry` is `File(root, "books/granger/${id}_${releaseDate}.epub")`.
    - `snapshotDate` = `releaseDate`.
  - Cap accounting counts `BOOK` like `PACK`: `isPack` becomes "is capped" (`item is PackEntry || item is BookEntry`), and `queuedPackBytes` sums both kinds.
  - `Volume.booksDir = File(root, "books")`. `grangerBytes` adds the size of `books/`, counted recursively.

- [ ] **Step 1: Failing tests:**
  - `CatalogParserTest`:
    - `booksParseAndAppearInItems`.
    - `bookWithBadDateIsDropped`.
    - `presetKeepsBookIds`.
    - `catalogWithoutBooksStillParses`.
  - `PublishedCatalogTest`: update the Starter expectations to the books swap from Task 2, if Task 2 has landed; otherwise leave it.
  - `DownloadManagerTest`:
    - `bookTargetsBooksGrangerFolder`.
    - `booksCountTowardTheCap` (with `capBytes` small: a queued book plus a pack over the cap gives `OverCap`).
- [ ] **Step 2:** `./gradlew :core:catalog:testDebugUnitTest :core:downloads:testDebugUnitTest` gives FAIL.
- [ ] **Step 3: Implement.** The `ItemKind` column is stored by name, so adding `BOOK` needs no migration. Confirm this with the existing schema JSON.
- [ ] **Step 4:** Rerun. PASS.
- [ ] **Step 5: Commit** `Catalog books and book downloads: BookEntry, ItemKind.BOOK, books folder, cap counts books`.

---

### Task 4: `:core:books`: inspect, refuse DRM, convert text, chunk XHTML

**Files:**
- Create: `core/books/build.gradle.kts` (Android library, namespace `app.granger.core.books`, deps `org.jsoup:jsoup:1.21.2`, `org.commonmark:commonmark:0.30.0`; test deps as `:core:library`; `testFixtures { enable = true }`), `core/books/src/main/AndroidManifest.xml`, the sources under `core/books/src/main/kotlin/app/granger/core/books/`, tests, and `core/books/src/testFixtures/kotlin/app/granger/core/books/TestEpubs.kt`
- Modify: `settings.gradle.kts` (`":core:books"`), `gradle/libs.versions.toml` (jsoup, commonmark, readium ×3, fragment-compose)

**Interfaces:**
- Produces:
  - `enum class DrmKind { LCP, ADOBE, OTHER }`. `object DrmCheck { fun check(zip: ZipFile): DrmKind? }`:
    - `META-INF/license.lcpl` gives LCP.
    - `META-INF/rights.xml` gives ADOBE.
    - In `META-INF/encryption.xml`, any `EncryptionMethod/@Algorithm` other than `http://www.idpf.org/2008/embedding` or `http://ns.adobe.com/pdf/enc#RC` gives OTHER. Those two are font obfuscation, which is allowed.
  - `data class EpubMeta(val title: String, val authors: List<String>, val translators: List<String>, val date: String, val language: String, val subjects: List<String>, val description: String, val identifier: String, val collection: String?, val cover: ByteArray?, val coverExtension: String?, val spine: List<String> /* zip paths, reading order */)`
  - `sealed interface Inspection { data class Ok(val meta: EpubMeta) : Inspection; data class Protected(val kind: DrmKind) : Inspection; data class Invalid(val reason: String) : Inspection }`
  - `object EpubInspector { fun inspect(file: File, fallbackTitle: String = file.nameWithoutExtension): Inspection }`:
    - Not a zip, no `mimetype` equal to `application/epub+zip`, no `container.xml`, no OPF, or an empty spine gives `Invalid`.
    - It runs DRM detection before parsing.
    - It parses the OPF with jsoup's XML parser.
    - The cover comes from the manifest item with `properties~=cover-image`, else from EPUB 2 `<meta name="cover">`.
    - A blank title gives `fallbackTitle`.
  - `object TextDecoding { fun decode(bytes: ByteArray): String }`: honours a UTF-8, UTF-16LE or UTF-16BE BOM; else strict UTF-8; on failure, Windows-1252.
  - `object TextToEpub { fun convert(text: String, title: String, markdown: Boolean, out: OutputStream, maxChapterChars: Int = 100_000) }` writes a valid EPUB 3 with the same layout rules as Task 1 (mimetype first, OPF, nav, `cNNN.xhtml`):
    - **TXT:** chapters start at lines matching `(?m)^\s*(CHAPTER|Chapter|BOOK|Book|PART|Part)\s+([0-9]+|[IVXLCDM]+)\b.*$`, used only when it matches at least twice. Otherwise the text is split at blank lines into chunks of at most `maxChapterChars`; a paragraph is never cut unless it alone is longer, in which case it is cut at a space. Paragraphs are blank-line separated, become `<p>`, and are XML-escaped.
    - **Markdown:** rendered with commonmark, `HtmlRenderer.builder().escapeHtml(true)`. Images are dropped. Chapters split at the highest heading level used at least twice. The output is normalised to XHTML with jsoup (`outputSettings.syntax(xml)`).
    - Chapter titles come from the heading line, else `"Part N"`.
  - `data class TextChunk(val href: String, val ordinal: Int, val progression: Double, val text: String)`. `object XhtmlText { fun chunks(zip: ZipFile, href: String): List<TextChunk>; fun all(file: File, spine: List<String>): Sequence<TextChunk> }`:
    - Text is taken from block elements (`p, h1–h6, li, blockquote, pre, td, dd, dt, div` with direct text).
    - Consecutive blocks are merged until the chunk is at least 400 characters; it is cut at 2000 characters.
    - `progression` = characters before the chunk ÷ characters in the resource.
    - Output is deterministic, because snippets re-extract by `(href, ordinal)`.
  - `object Snippets { fun around(text: String, query: String, radius: Int = 80): String }` returns HTML-escaped text around the first query term, with each term wrapped in `<b>…</b>` and `…` at cut ends.

- [ ] **Step 1: Failing tests** (Robolectric is not needed; these are plain JUnit with Truth). `TestEpubs` builds EPUB zips in a temp folder with options for the cases below.
  - `DrmCheckTest`:
    - `lcpLicenseIsLcp`.
    - `adobeRightsIsAdobe`.
    - `fontObfuscationIsAllowed`.
    - `otherEncryptionIsProtected`.
    - `plainEpubIsClean`.
  - `EpubInspectorTest`:
    - `readsMetadataCoverAndSpine`.
    - `missingTitleUsesFileName`.
    - `epub2CoverMetaIsFound`.
    - `pdfRenamedEpubIsInvalid`.
    - `zipWithoutOpfIsInvalid`.
    - `emptyFileIsInvalid`.
    - `protectedIsReportedBeforeParsing`.
  - `TextDecodingTest`:
    - `utf8`.
    - `utf8Bom`.
    - `utf16LeBom`.
    - `windows1252CurlyQuotes` (bytes `0x93 0x94` decode to `“”`).
  - `TextToEpubTest`:
    - `chapterHeadingsSplitTxt`.
    - `fiveMegabytesWithoutHeadingsSplitsBySize` (every chapter ≤ 100 000 characters plus one paragraph; no paragraph cut).
    - `markdownHeadingsBecomeChapters`.
    - `markdownScriptIsEscaped` (no `<script` element in any chapter).
    - `outputPassesInspector` (`EpubInspector.inspect` gives `Ok`).
  - `XhtmlTextTest`:
    - `chunksAreDeterministicAndOrdered`.
    - `progressionRisesFromZeroToBelowOne`.
  - `SnippetsTest`:
    - `highlightsTermsAndEscapes`.
- [ ] **Step 2:** `./gradlew :core:books:testDebugUnitTest` gives FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Rerun. PASS.
- [ ] **Step 5: Commit** `core:books: EPUB inspection, DRM refusal, TXT and Markdown to EPUB, text chunks for search`.

---

### Task 5: Library schema v4 for books

**Files:**
- Modify: `core/library/build.gradle.kts` (`implementation(project(":core:books"))`), `Entities.kt`, `Daos.kt`, `LibraryDatabase.kt`
- Create: `core/library/.../BookIds.kt`
- Test: `MigrationTest.kt`, `DaosTest.kt`

**Interfaces:**
- Produces:
  - `enum class BookOrigin(val key: String) { GRANGER("granger"), IMPORT("import"), ARCHIVE_ORG("archive-org") }`
  - `object BookIds { fun of(origin: BookOrigin, ref: String): String = "book:${origin.key}:$ref"; fun isBook(id: String): Boolean }`
  - The `books` table:

    ```
    @Entity("books") Book(
      @PrimaryKey id: String, origin: BookOrigin, sourceRef: String /* catalog id, import sha256, archive.org identifier */,
      filePath: String, title: String, author: String, translator: String, date: String, language: String,
      subjects: String /* "; "-joined */, description: String, coverPath: String?, sizeBytes: Long,
      status: PackStatus, indexedAt: Long?, addedAt: Long)
    ```

  - `@Entity("book_positions") BookPosition(@PrimaryKey bookId: String, locatorJson: String, totalProgression: Double, chapterTitle: String, updatedAt: Long)`
  - `enum class MarkKind { BOOKMARK, HIGHLIGHT }`
  - The `book_marks` table, indexed on `bookId`:

    ```
    @Entity("book_marks", indices=[Index("bookId")]) BookMark(
      @PrimaryKey(autoGenerate = true) id: Long, bookId: String, kind: MarkKind,
      locatorJson: String, text: String, color: String /* "yellow"|"green"|"blue"|"pink"; "" for bookmarks */,
      note: String, createdAt: Long, updatedAt: Long)
    ```

  - `@Entity("book_chunks", indices=[Index("bookId")]) BookChunk(@PrimaryKey(autoGenerate = true) id: Long, bookId: String, href: String, ordinal: Int, progression: Double)`.
    - `autoGenerate` gives `AUTOINCREMENT`, so ids are **never reused**. This is required: FTS4 contentless rows can't be deleted, and a reused id would make stale index rows match a new chunk.
  - `book_text`, raw SQL that Room doesn't manage: `CREATE VIRTUAL TABLE IF NOT EXISTS book_text USING fts4(content="", text, tokenize=unicode61)`. Its `docid` = `BookChunk.id`. The same `createBookText(db)` runs from `MIGRATION_3_4` and `Callback.onCreate`.
  - `BookDao`:
    - books: `observeAll()`, ordered by title `COLLATE NOCASE`; `getAll()`; `get(id)`; `upsert`; `delete`; `notIndexed()`, which returns OK books with `indexedAt IS NULL`; `setIndexed(id, at)`; `clearIndexed()`
    - positions: `upsertPosition`, `position(bookId)`, `observePositions()`
    - marks: `observeMarks(bookId)`, oldest first; `insertMark`; `updateMark`; `deleteMark`
    - chunks: `insertChunk(chunk): Long`, `deleteChunks(bookId)`, `chunkCount()`, `chunk(id)`
  - `LibraryDatabase` version 4.

- [ ] **Step 1: Failing tests:**
  - `MigrationTest.version3DataSurvivesAndBookTablesExist`: a v3 database with a pack, a placement, a tab and a history row migrates to 4 with validation. Every row remains, and `book_text` exists: `INSERT` then `MATCH` works.
  - `DaosTest`:
    - `freshDatabaseHasBookText`.
    - `chunkIdsAreNeverReused`: insert 3, delete all, insert 1; the new id is 4.
    - `notIndexedListsOnlyOkUnindexedBooks`.
    - `marksAreOldestFirstPerBook`.
- [ ] **Step 2:** `./gradlew :core:library:testDebugUnitTest` gives FAIL.
- [ ] **Step 3: Implement.** Build once so KSP writes `schemas/…/4.json`, and commit it.
- [ ] **Step 4:** Rerun. All `:core:library` tests PASS.
- [ ] **Step 5: Commit** `Library schema v4: books, positions, marks, text chunks and the book_text index`.

---

### Task 6: `Bookshelf`, `BookIndex`, filing books

**Files:**
- Create: `core/library/.../Bookshelf.kt`, `core/library/.../BookIndex.kt`, tests `BookshelfTest.kt`, `BookIndexTest.kt`
- Modify: `Filing.kt`, `Shelves.kt`, `LibrarySearch.kt`, tests `ShelvesTest.kt`, `ZimLibrarySearchTest.kt`

**Interfaces:**
- Consumes: Task 4 (`EpubInspector`, `TextToEpub`, `TextDecoding`, `XhtmlText`, `Snippets`), Task 5 entities and `BookDao`.
- Produces:
  - The import result:

    ```kotlin
    sealed interface ImportResult {
      data class Imported(val bookId: String) : ImportResult
      data class AlreadyThere(val bookId: String) : ImportResult
      data class Protected(val kind: DrmKind) : ImportResult
      data object Unsupported : ImportResult
      data class Damaged(val reason: String) : ImportResult
    }
    ```

    User messages live in one place, `ImportResult.message(name)`:
    - Protected: `"<name> is copy-protected (DRM). Granger can't open it and won't remove the protection."`
    - Unsupported: `"<name> isn't an EPUB, TXT or Markdown file."`
    - Damaged: `"<name> is damaged or isn't a complete e-book."`
  - The façade:

    ```kotlin
    interface Bookshelf {
      val books: Flow<List<Book>>
      val positions: Flow<Map<String, BookPosition>>
      suspend fun book(id: String): Book?
      suspend fun refresh()
      suspend fun addDownloaded(file: File, catalogId: String): Book?
      suspend fun import(displayName: String, open: () -> InputStream): ImportResult
      suspend fun remove(id: String): Boolean
      suspend fun savePosition(bookId: String, locatorJson: String, totalProgression: Double, chapterTitle: String)
      suspend fun position(bookId: String): BookPosition?
      fun marks(bookId: String): Flow<List<BookMark>>
      suspend fun addMark(mark: BookMark): Long
      suspend fun updateMark(mark: BookMark)
      suspend fun deleteMark(id: Long)
    }
    ```

  - `class RoomBookshelf(db: LibraryDatabase, bookDirs: () -> List<File> /* each volume's books/ */, importDir: () -> File /* internal books/imports */, coversDir: File, io: CoroutineDispatcher = Dispatchers.IO, now: () -> Long = System::currentTimeMillis) : Bookshelf`
    - **`addDownloaded`** inspects the file and upserts `Book(id = BookIds.of(GRANGER, catalogId), origin = GRANGER, sourceRef = catalogId, …)` with `indexedAt = null`. It writes the cover to `coversDir/<sha1 of id>.<ext>`. An existing row keeps its id, so the position and marks carry over. It returns null if the file isn't `Ok`.
    - **`import`**:
      1. Copies the stream to `importDir/.incoming-<random>`, computing a sha256 on the way.
      2. If a book with `BookIds.of(IMPORT, sha256)` already exists, returns `AlreadyThere`.
      3. Sniffs the format: zip magic `PK` means EPUB, then `EpubInspector`; a `.txt`/`.md`/`.markdown` name, or text with no NUL in the first 4 KB, goes through `TextDecoding` + `TextToEpub` (Markdown when the name ends `.md`/`.markdown`).
      4. Moves the result to `importDir/<sha256 first 12>-<sanitised name>.epub`.
      5. Upserts the book.

      On every non-`Imported` path, and on any exception, the incoming file is deleted.
    - **`refresh`**:
      - Sets each known book's status to OK or MISSING by whether its file exists.
      - Registers stray `.epub` files found under `books/granger/` (origin GRANGER; ref = the name before `_`) and `books/imports/` (origin IMPORT; ref = sha256 of the file).
      - A file that doesn't inspect `Ok` is registered CORRUPT.
    - **`remove`** deletes the file, the cover, the book row and its chunks. **It keeps the position and marks**, so a re-download restores them. It returns false only when the file exists and can't be deleted.
  - `data class BookHit(val bookId: String, val bookTitle: String, val author: String, val href: String, val progression: Double, val snippet: String)`
  - `class BookIndex(db: LibraryDatabase, io: CoroutineDispatcher = Dispatchers.IO, now: …)`:
    - **`suspend fun indexPending()`**: for each `notIndexed()` book, run one transaction that deletes the book's chunks, inserts each `XhtmlText.all` chunk into `book_chunks` and `book_text`, then `setIndexed`. A failure skips that book and leaves it pending. It then calls `compactIfNeeded()`. A `Mutex` makes concurrent calls run one at a time.
    - **`suspend fun search(query: String, limit: Int = 30, perBook: Int = 3): List<BookHit>`**:
      - Builds the MATCH string from the query's words, each wrapped in double quotes (embedded `"` removed), with `*` after the last one.
      - Runs `SELECT c.* FROM book_text t JOIN book_chunks c ON c.id = t.docid WHERE book_text MATCH ? LIMIT 400`. The join drops orphans.
      - Groups the hits by book, orders the books by hit count descending, and keeps `perBook` hits per book, `limit` in total.
      - The snippet is `Snippets.around` over the chunk text re-extracted with `XhtmlText.chunks(zip, href)[ordinal]`.
    - **`internal suspend fun compactIfNeeded()`**: orphans = `sqlite_sequence.seq` for `book_chunks` minus `chunkCount()`. If orphans > `max(chunkCount(), 50_000)`, it drops and recreates `book_text`, deletes all chunks, calls `clearIndexed()`, and re-indexes.
  - `Filing`:
    - `fun bookKey(book: Book) = book.id`.
    - `fun defaultKey(book: Book)`: GRANGER gives `"granger-classics"`, IMPORT gives `"my-books"`, ARCHIVE_ORG gives `"archive-org"`.
    - Names: `"Granger Classics"`, `"My books"`, `"Archive.org"`.
    - Media type `literature`.
    - `fun isSuperseded(pack: Pack) = pack.name == "granger_classics_en"`.
  - `Shelves`:
    - `CollectionRow(collection, packs, books: List<Book> = emptyList())`.
    - `RoomShelves(db, library, bookshelf: Bookshelf)` files books exactly as it files packs, with placement key `Filing.bookKey`.
    - `suspend fun moveBook(book: Book, toCollectionId: String)`.
    - Emptiness and hiding count books as well as packs.
    - `deleteCollection` re-files the collection's books too.
  - `LibrarySearch` gains `suspend fun books(query: String): List<BookHit>`. `ZimLibrarySearch(library, bookIndex: BookIndex? = null, …)` delegates to it, or returns empty when the index is null.

- [ ] **Step 1: Failing tests** (Robolectric, in-memory db, temp folders; `TestEpubs` through `testImplementation(testFixtures(project(":core:books")))`):
  - `BookshelfTest`:
    - `downloadedBookIsFiledUnderGrangerClassics` (through `RoomShelves`).
    - `redownloadKeepsPositionAndMarks`.
    - `importEpubCopiesAndRegisters`.
    - `importTxtConvertsToEpub`.
    - `importSameFileTwiceIsAlreadyThere`.
    - `importRejectsProtectedAndLeavesNoFile` (`importDir` holds no files afterwards).
    - `importDamagedLeavesNoFile`.
    - `refreshMarksMissingAndRegistersStrays`.
    - `removeDeletesFileButKeepsMarks`.
  - `BookIndexTest`:
    - `indexesAndFindsAWord` (snippet has `<b>`).
    - `indexingIsResumableAndIdempotent`: index book A; mark B pending; throw inside B's transaction through a test hook; A is still searchable, B is pending, and rerunning indexes B.
    - `reindexedBookMatchesOnce`.
    - `removedBookNeverMatches`.
    - `compactionRebuildsWhenOrphansDominate` (with the threshold lowered through a constructor parameter).
    - `quotesInQueryAreSafe`.
  - `ShelvesTest`:
    - `booksAndPacksShareACollection` (the Classics ZIM and a Classics book are both in `granger-classics`).
    - `importsFileUnderMyBooks`.
    - `movedBookKeepsItsPlace`.
  - `ZimLibrarySearchTest`: `booksDelegatesToIndex`.
- [ ] **Step 2:** Run. FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `./gradlew :core:library:testDebugUnitTest` PASS.
- [ ] **Step 5: Commit** `Bookshelf and book index: downloads, imports, positions, marks, resumable FTS indexing, filing by origin`.

---

### Task 7: App wiring, `BookRoute`, launch and the rail

**Files:**
- Modify: `app/build.gradle.kts` (`:core:books`, readium ×3, fragment-compose), `MainActivity.kt` (extends `FragmentActivity`), `GrangerApp.kt`, `GrangerNavHost.kt`, `Features.kt`, `launch/LaunchDecider.kt`, `shell/RailViewModel.kt`, `shell/Rail.kt`, `shell/HistoryViewModel.kt`, tests `LaunchDeciderTest.kt`, `RailViewModelTest.kt`, `HistoryViewModelTest.kt`, `Fakes.kt` (`FakeBookshelf`)

**Interfaces:**
- Consumes: `Bookshelf`, `BookIndex`, `BookIds` (Tasks 5–6), `ItemKind.BOOK` (Task 3).
- Produces:
  - `Features`: add `const val IMPORT = true` (Plan 10 adds the Pro gate) and `const val READ_ALOUD = false`.
  - `AppContainer`:
    - `bookDirs`: each volume's `books/`, created on use.
    - `bookshelf = RoomBookshelf(libraryDb, bookDirs, { File(internal volume, "books/imports") }, File(context.filesDir, "covers"))`.
    - `bookIndex = BookIndex(libraryDb)`.
    - `search = ZimLibrarySearch(library, bookIndex)`.
    - `shelves = RoomShelves(libraryDb, library, bookshelf)`.
  - When a `BOOK` download finishes, `onInstalled` runs `bookshelf.addDownloaded(File(record.filePath), record.itemId)`, then `appScope.launch { bookIndex.indexPending() }`.
  - On start, `MainActivity` runs `bookshelf.refresh()` beside `library.refresh()`, then launches `indexPending()` in `appScope`.
  - `@Serializable data class BookRoute(val tabId: Long, val href: String? = null, val progression: Double? = null)`.
  - A nav helper, `fun NavController.showTab(tab: ReadingTab, at: BookTarget? = null)`, with `data class BookTarget(val href: String, val progression: Double)`. It navigates to `BookRoute` when `BookIds.isBook(tab.packId)`, else to `ReaderRoute`. The rail, History, Collection and Search all open tabs through it.
  - The shell treats `BookRoute` as a reading route everywhere it now checks `hasRoute<ReaderRoute>()`: rail visibility, the edge-swipe request, and replacing the reader on top.
  - `StartScreen.Reader(val tabId: Long, val book: Boolean = false)`. `MainActivity` computes `lastTabPackOk` for a book tab as `bookshelf.book(id)?.status == OK && File(filePath).exists()`. `LaunchDecider.MISSING_NOTICE` is unchanged, and covers books too.
  - `RailTabUi` gains `coverPath: String?`.
    - For book tabs, `subtitle` = `"${chapterTitle} · ${percent}%"` from `bookshelf.positions`, or the author when there's no position yet.
    - `available` comes from the book status.
    - The narrow rail draws the cover, decoded at rail size with `inSampleSize` and remembered, and falls back to the letter tile.
  - In `HistoryViewModel`, a history row for a book shows the book title and opens through `showTab`.

- [ ] **Step 1: Failing tests:**
  - `LaunchDeciderTest`:
    - `normalOpenResumesLastBook` (gives `Reader(tabId, book = true)`).
    - `launchWithMissingBookOpensLibraryWithNotice`.
  - `RailViewModelTest`:
    - `bookTabShowsCoverChapterAndPercent`.
    - `missingBookTabIsUnavailable`.
  - `HistoryViewModelTest`: `bookHistoryRowUsesBookTitle`.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` gives FAIL.
- [ ] **Step 3: Implement.** `BookRoute`'s destination shows a placeholder `Text(book title)` until Task 8.
- [ ] **Step 4:** Rerun. PASS. `./gradlew :app:assembleDebug` succeeds.
- [ ] **Step 5: Commit** `App: books in the container, BookRoute, launch resumes books, covers and progress on the rail`.

---

### Task 8: Readium reader: open, paginate, immersive, keep the place

**Files:**
- Create: `ebook/ReadiumBooks.kt`, `ebook/NavigatorHost.kt`, `ebook/StartLocator.kt`, `ebook/ReadingPrefs.kt`, `ebook/EbookReaderViewModel.kt`, `ebook/EbookReaderScreen.kt`; tests `ebook/StartLocatorTest.kt`, `ebook/ReadingPrefsTest.kt`, `ebook/EbookReaderViewModelTest.kt`
- Modify: `GrangerNavHost.kt` (the `BookRoute` destination), `MainActivity.kt` (install `ReadiumFragmentFactory` before `super.onCreate`)

**Interfaces:**
- Consumes: `BookRoute`/`BookTarget` (Task 7), `Bookshelf.book/position/savePosition`, `ReadingSessions.tab/showInTab`, the shell's `onChromeVisibleChange` and `showChromeRequest` (as `ReaderScreen` uses them today).
- Produces:
  - `interface BookOpener { suspend fun open(file: File): Publication? }`, implemented by `ReadiumBookOpener(context)` with `AssetRetriever(context.contentResolver, DefaultHttpClient())` + `PublicationOpener(DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory = null))`. The HTTP client is only a constructor argument; nothing is fetched, because the book is a local file.
  - `object OpenBooks { var current: Pair<String /* bookId */, EpubNavigatorFactory>? }`: a process-wide holder that the view model sets.
  - `ReadiumFragmentFactory : FragmentFactory`. For `EpubNavigatorFragment`, it creates the fragment from `OpenBooks.current`. When nothing is open, which happens on restore after process death, it returns a blank `Fragment()`. `NavigatorHost` replaces that blank fragment once the book is open. Every other class goes to the default factory.
  - `@Composable fun NavigatorHost(bookId: String, factory: EpubNavigatorFactory, initial: Locator?, prefs: EpubPreferences, listener: EpubNavigatorFragment.Listener, config: EpubNavigatorFragment.Configuration, onReady: (EpubNavigatorFragment) -> Unit)`.
    - It is an `AndroidView` of a `FragmentContainerView` with a stable view id.
    - It commits the navigator fragment with `commitNow`, and only when the container doesn't already hold a live `EpubNavigatorFragment` for `bookId`. So rotation reuses it.
  - `object StartLocator { fun choose(publication: Publication, savedJson: String?, target: BookTarget?): Locator? }`, in this order:
    1. The target, as `Locator(href, XHTML, locations = Locations(progression))`, when its href is in `readingOrder`.
    2. The saved locator, when its href is in `readingOrder`.
    3. `publication.locateProgression(saved.totalProgression)`.
    4. Null, which means the start of the book.
  - `enum class ReadingTheme { DAY, SEPIA, NIGHT }`, `enum class Typeface { SERIF, SANS }`, `enum class Align { START, JUSTIFY }`.
  - `data class ReadingPrefs(val typeface: Typeface = SERIF, val fontScale: Double = 1.0 /* 0.75..2.5, step 0.125 */, val lineHeight: Double = 1.5 /* 1.2..2.0 */, val margins: Double = 1.0 /* 0.5..2.0 */, val align: Align = START, val theme: ReadingTheme = NIGHT)` with `fun toEpub(): EpubPreferences`:
    - `publisherStyles = false`, `scroll = false`.
    - Theme: DAY gives `Theme.LIGHT`; SEPIA gives `Theme.SEPIA`; NIGHT gives `Theme.DARK` with `backgroundColor`/`textColor` from `GrangerColors.Background`/`OnSurface`.
    - Typeface: SERIF gives `FontFamily.SERIF`; SANS gives `FontFamily.SANS_SERIF`.
  - `class ReadingPrefsStore(prefs: SharedPreferences) { val prefs: StateFlow<ReadingPrefs>; fun update(p: ReadingPrefs) }`. Values are clamped to their ranges. The prefs are global, not per book.
  - `EbookReaderViewModel(tabId: Long, target: BookTarget?, bookshelf: Bookshelf, sessions: ReadingSessions, opener: BookOpener, prefsStore: ReadingPrefsStore, appScope: CoroutineScope)`:
    - `state: StateFlow<EbookUi>`, where `EbookUi(val title: String = "", val loading: Boolean = true, val error: String? = null, val publication: Publication? = null, val initial: Locator? = null, val chromeVisible: Boolean = false, val locator: Locator? = null, val prefs: ReadingPrefs = ReadingPrefs())`.
    - Errors:
      - `TAB_GONE = "This bookmark was removed."`
      - `BOOK_GONE = "This book isn't on the phone any more. Check the SD card, or download it again from Granger."`
      - `BOOK_DAMAGED = "This book couldn't be opened. Try removing it, then download or import it again."`
    - `fun onLocator(locator: Locator)`: saves with `bookshelf.savePosition(bookId, locator.toJSON().toString(), locator.locations.totalProgression ?: 0.0, locator.title ?: "")`, debounced 1 s in `appScope`. `fun flush()` saves now, and is called from `ON_STOP`.
    - `fun toggleChrome()`.
    - On open, it calls `sessions.showInTab(tabId, bookId, "", book.title)`.
  - Navigator wiring in `EbookReaderScreen`:
    - `navigator.addInputListener(DirectionalNavigationAdapter(navigator, animatedTransition = true))` turns pages on edge taps.
    - A second `InputListener` toggles the chrome on a tap in the middle 60 % of the width.
    - `navigator.currentLocator` is collected into `onLocator`.
    - The system bars are hidden while the chrome is hidden, as in `ReaderScreen`.
    - The shell's `showChromeRequest` (edge swipe) shows the chrome.

- [ ] **Step 1: Failing tests** (build `Publication` objects in the test with `Manifest(metadata, readingOrder = [c001.xhtml, c002.xhtml, c003.xhtml])`; `FakeBookOpener`):
  - `StartLocatorTest`:
    - `startLocatorPrefersRouteTargetThenSavedThenStart`.
    - `staleHrefFallsBackToTotalProgression` (saved href `c009.xhtml`, `totalProgression` 0.5, gives a locator in `c002.xhtml`).
    - `garbageSavedJsonStartsAtTheBeginning`.
  - `ReadingPrefsTest`:
    - `toEpubOverridesPublisherStylesAndPaginates`.
    - `nightUsesAppColours`.
    - `storeClampsOutOfRangeValues`.
  - `EbookReaderViewModelTest`:
    - `opensAtSavedPosition`.
    - `missingFileShowsBookGone`.
    - `unopenableShowsBookDamaged`.
    - `missingTabShowsTabGone`.
    - `locatorChangesAreSavedDebounced` (advance the virtual clock).
    - `chromeStartsHiddenAndToggles`.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` gives FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Rerun. PASS. `./gradlew :app:assembleDebug`.
- [ ] **Step 5: Device check** on the Pixel 6. Import a test EPUB, or push one into `books/imports/` and let refresh register it.
  - Edge taps and swipes turn pages.
  - A middle tap shows and hides the rail and the system bars.
  - The text uses the Night colours, and a Gutenberg book shows no blue links on black.
  - Rotating keeps the page.
  - Open a book, press Home, run `adb shell am kill app.granger`, then reopen: the same page, and no crash.
  - Back from the book lands where it came from.
- [ ] **Step 6: Commit** `E-book reader: Readium paginated reading, immersive chrome, saved places that survive rotation, process death and rebuilt editions`.

---

### Task 9: Reader controls: top bar, slider, contents, bookmarks, text settings

**Files:**
- Create: `ebook/ReaderControls.kt`, `ebook/ContentsPanel.kt`, `ebook/TextSettingsSheet.kt`; tests in `EbookReaderViewModelTest.kt`, plus `ebook/ProgressLabelTest.kt`
- Modify: `EbookReaderViewModel.kt`, `EbookReaderScreen.kt`

**Interfaces:**
- Consumes: Task 8's view model and navigator, and `Bookshelf.marks/addMark/deleteMark`.
- Produces:
  - `EbookUi` gains:
    - `toc: List<TocRow>`, where `TocRow(val title: String, val href: String, val depth: Int)` is `publication.tableOfContents` flattened depth-first.
    - `currentTocIndex: Int?`: the last row whose href (without its fragment) matches the locator's href.
    - `positionsCount: Int`, from `publication.positions().size`.
    - `bookmarks: List<BookMark>` and `highlights: List<BookMark>`.
    - `isBookmarkedHere: Boolean`: a bookmark's locator has the same href and the same `locations.position` as the current locator.
    - `panel: Panel?`, where `enum class Panel { CONTENTS, SETTINGS, SEARCH }`.
  - `object ProgressLabel { fun of(locator: Locator, positionsCount: Int): String }` gives `"<chapter title> · p. <position> of <count>"`. Without positions, it gives `"<chapter title> · <percent>%"`. A blank title drops the chapter part.
  - View-model methods:
    - `fun toggleBookmark()` adds a `MarkKind.BOOKMARK` with the current locator, text = the chapter title, or deletes the one here.
    - `fun goTo(href: String)`, `fun goToProgression(p: Double)` (the slider, via `publication.locateProgression`) and `fun goToMark(mark: BookMark)` emit a `navigation: SharedFlow<Locator>` that the screen passes to `navigator.go(locator)`.
    - `fun open(panel: Panel?)`, `fun updatePrefs(p: ReadingPrefs)`; the screen calls `navigator.submitPreferences(p.toEpub())`.
  - **Top bar:** Back, book title, Contents, Search.
  - **Bottom:** a slider (0..1 total progression; the jump happens on release) with the `ProgressLabel` above it. Under it, the reader bar: Contents · Text settings · Read-aloud (only when `Features.READ_ALOUD`) · Bookmark (filled when `isBookmarkedHere`).
  - **Contents panel:** a side sheet with tabs Contents · Bookmarks · Highlights.
    - Contents: the TOC indented by depth, with the current chapter in flame orange.
    - Bookmarks: newest first, with the chapter and percent.
    - Highlights: the text, a colour swatch and the note.
    - A tap on any row navigates and closes the panel.
  - **Text settings sheet:**
    - Typeface (Serif, Sans).
    - Size −/+ with the percent.
    - Line spacing, margins (sliders).
    - Alignment (Left, Justified).
    - Theme chips Day · Sepia · Night.
    - Changes apply live.

- [ ] **Step 1: Failing tests:**
  - `ProgressLabelTest`:
    - `withPositions`.
    - `withoutPositionsUsesPercent`.
    - `blankChapterTitleIsDropped`.
  - `EbookReaderViewModelTest`:
    - `tocIsFlattenedWithDepth`.
    - `currentChapterFollowsLocator`.
    - `bookmarkTogglesAtCurrentPlace`.
    - `sliderJumpEmitsNavigation`.
    - `prefsChangesAreStored`.
- [ ] **Step 2:** Run. FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run. PASS.
- [ ] **Step 5: Device check:**
  - The slider jumps and the label reads like "ACT III — Scene I · p. 120 of 410".
  - A Contents tap jumps there.
  - A bookmark appears in the panel and survives a restart.
  - Sepia and Day recolour live.
  - Font size reflows.
- [ ] **Step 6: Commit** `E-book reader controls: contents, slider with chapter and page, bookmarks, text settings and themes`.

---

### Task 10: Selection, highlights, notes, dictionary, search in book

**Files:**
- Create: `ebook/SelectionMenu.kt`, `ebook/HighlightSheet.kt`, `ebook/BookSearchPanel.kt`, `ebook/Dictionary.kt`; tests `ebook/DictionaryTest.kt`, plus more in `EbookReaderViewModelTest.kt`
- Modify: `ReadiumBooks.kt` (`BookSearcher`), `EbookReaderViewModel.kt`, `EbookReaderScreen.kt`, `GrangerNavHost.kt` (Search with the selection prefilled; Define opens a Wiktionary tab)

**Interfaces:**
- Consumes: `SearchRoute(query)`, `ReadingSessions.openTab`, `Library.packs/archive`, `Bookshelf` marks.
- Produces:
  - `SelectionMenu(onAction: (SelectionAction) -> Unit, dictionaryAvailable: Boolean) : ActionMode.Callback`, passed as `EpubNavigatorFragment.Configuration(selectionActionModeCallback = …)`.
    - `enum class SelectionAction { COPY, HIGHLIGHT, NOTE, DEFINE, SEARCH, INQUIRE }`, shown in that order.
    - DEFINE only when `dictionaryAvailable`; INQUIRE only when `Features.INQUIRE`.
    - The screen reads `navigator.currentSelection()` (locator + text), acts, then calls `clearSelection()`.
    - COPY puts the text on the clipboard.
    - SEARCH opens `SearchRoute(text.take(200))`.
  - View model:
    - `fun highlight(locator: Locator, text: String, color: String = "yellow"): Long`.
    - `fun note(locator, text)` creates a yellow highlight and opens the note editor.
    - `fun updateHighlight(mark: BookMark)`, `fun deleteHighlight(id: Long)`.
    - `EbookUi.decorations: List<Decoration>`: one `Decoration(id = mark.id.toString(), locator, style = Decoration.Style.Highlight(tint = colour))` per highlight whose locator href is in `readingOrder`. The screen applies them with `navigator.applyDecorations(decorations, group = "highlights")`.
    - `EbookUi.unplaceable: Set<Long>` holds highlight ids whose href isn't in `readingOrder`. The Highlights tab shows those rows with "Can't find this passage in the current edition" and no jump.
  - A tap on a drawn highlight (`addDecorationListener("highlights", …)`) opens `HighlightSheet`: colour swatches, note text field, Delete.
  - `interface BookSearcher { suspend fun search(publication: Publication, query: String, max: Int = 200): List<Locator> }`, with `ReadiumBookSearcher` using `publication.search(query)` and collecting `next()` pages until null or `max`.
    - `EbookUi.search: BookSearchUi(val query: String, val results: List<BookSearchRow>, val running: Boolean)`, where `BookSearchRow(val locator: Locator?, val chapter: String, val before: String, val match: String, val after: String, val markId: Long?)`.
    - Marks whose text or note contains the query (ignoring case) come first, labelled "Your highlight" or "Your note". The text hits follow.
    - `fun search(query: String)` cancels any running search.
  - `sealed interface DictionaryChoice { data class Wiktionary(val packId: String) : DictionaryChoice; data object ProcessText : DictionaryChoice; data object None : DictionaryChoice }`. `object Dictionary { fun choose(packs: List<Pack>, hasProcessTextHandler: Boolean): DictionaryChoice }`:
    - An OK pack whose `name` starts with `wiktionary_` wins; the newest `date` if there are several.
    - Otherwise `ProcessText` when an activity handles `Intent.ACTION_PROCESS_TEXT` for `text/plain` (`packageManager.queryIntentActivities`).
    - Otherwise `None`, and Define is hidden.
  - What Define does:
    - **Wiktionary:** resolve the word (lower-cased, trimmed of punctuation) with `archive.suggest(word, 1)`. If there is a hit, `sessions.openTab(packId, path, word)` and open it in the web-style reader; Back returns to the book. If there is none, a snackbar says "No entry for “word” in Wiktionary".
    - **ProcessText:** start that intent with `EXTRA_PROCESS_TEXT` and `EXTRA_PROCESS_TEXT_READONLY = true`.

- [ ] **Step 1: Failing tests:**
  - `DictionaryTest`:
    - `wiktionaryPackWins`.
    - `newestWiktionaryIsChosen`.
    - `processTextWhenNoPack`.
    - `noneHidesDefine`.
    - `missingWiktionaryPackIsIgnored`.
  - `EbookReaderViewModelTest`:
    - `highlightIsStoredAndDecorated`.
    - `unplaceableHighlightIsListedNotDrawn`.
    - `noteCreatesHighlightWithNote`.
    - `searchListsMarksThenTextHits` (`FakeBookSearcher`).
    - `newSearchCancelsTheOld`.
- [ ] **Step 2:** Run. FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Run. PASS.
- [ ] **Step 5: Device check:**
  - Select a word: the menu reads Copy · Highlight · Note · Define · Search.
  - A highlight persists after a restart.
  - Tapping the highlight changes its colour.
  - A note shows in Contents → Highlights.
  - Search in book lists hits in context, and a tap jumps and marks the match.
  - With no Wiktionary pack and no handler app, Define is absent.
  - Search opens the Search screen with the word filled in.
- [ ] **Step 6: Commit** `E-book reader: highlights, notes, dictionary lookup, search in book, selection to Search`.

---

### Task 11: Library, imports, the Granger source and Search over books

**Files:**
- Modify: `library/CollectionScreen.kt`, `library/CollectionViewModel.kt`, `library/LibraryScreen.kt`, `library/LibraryViewModel.kt`, `sources/SourceViewModel.kt`, `sources/SourceScreen.kt`, `search/SearchViewModel.kt`, `search/SearchScreen.kt`, tests for each view model
- Create: `library/ImportRunner.kt`, test `library/ImportRunnerTest.kt`, `library/BookCover.kt` (cover image or a generated title tile)

**Interfaces:**
- Consumes: `Shelves` with books (Task 6), `Bookshelf.import/remove`, `Downloads.remove`, `LibrarySearch.books`, `showTab`/`BookTarget` (Task 7), `Filing.isSuperseded`.
- Produces:
  - `CollectionViewModel`:
    - `ui` carries `CollectionRow` with books, plus `positions: Map<String, BookPosition>`.
    - `fun openBook(book: Book): Long` calls `sessions.openTab(book.id, "", book.title)`; it does nothing for a book that isn't OK.
    - `fun removeBook(book: Book)`: for GRANGER books, `downloads.remove(book.sourceRef)` first, then `bookshelf.remove(book.id)`.
    - `fun moveBook(book, collectionId)`.
    - `superseded: Set<String>` holds the pack ids where `Filing.isSuperseded`.
  - `CollectionScreen`:
    - Packs as the existing list.
    - Then books as `LazyVerticalGrid(GridCells.Adaptive(104.dp))`: a `BookCover` 2:3 card, the title (2 lines), and a thin progress bar from `totalProgression`.
    - Long-press a book for Move to collection… and Remove from phone (with a confirm dialog).
    - A superseded pack row reads "Replaced by the Granger Classics e-books" and has a "Get the e-books" button, which opens `SourceRoute(GRANGER)`.
  - `BookCover(book: Book, modifier)` shows the cover file when `coverPath` exists. Otherwise it draws a near-black card with the title in off-white and a flame-orange rule.
  - `class ImportRunner(bookshelf: Bookshelf, open: (Uri) -> InputStream?, nameOf: (Uri) -> String) { suspend fun run(uris: List<Uri>): ImportSummary }`.
    - It imports one file at a time; a failure never stops the rest.
    - `ImportSummary(imported: Int, alreadyThere: Int, problems: List<String> /* ImportResult.message */)`, with `fun text(): String`. Examples: "Imported 3 books.", "Imported 2 books. 1 was already in My books. Book.epub is copy-protected (DRM)…".
  - Library:
    - When `Features.IMPORT`, the top bar overflow has "Import books…", which launches `ActivityResultContracts.OpenMultipleDocuments()` with `arrayOf("application/epub+zip", "text/plain", "text/markdown", "text/x-markdown", "application/octet-stream")`.
    - `LibraryViewModel.import(uris)` runs `ImportRunner` in `appScope` and shows the summary in a snackbar.
    - The empty-library state adds "Or import your own EPUB, TXT or Markdown books".
  - Granger source:
    - `SourceViewModel` lists `BookEntry` items in GRANGER, grouped under their `category`, which is the section title, after the packs.
    - The `granger-classics` preset is shown as the first preset.
    - Item status for books uses the same download records as packs.
    - The "Installed" tab includes installed books.
  - Search:
    - `SearchUi` gains `books: List<BookHit>`, shown as a third section, "In books", after Titles and In article text. Each row shows the book title, the author and the snippet.
    - `SearchViewModel.openBook(hit: BookHit): Pair<Long, BookTarget>` records the search and opens or reuses the book's tab. The screen navigates with `showTab(…, BookTarget(hit.href, hit.progression))`.
    - Grouping by collection (spec §4) is still Neal's open call from Plan 5; this keeps the current sections.

- [ ] **Step 1: Failing tests:**
  - `CollectionViewModelTest`:
    - `booksListWithProgress`.
    - `openBookOpensOneTabPerBook` (opening twice returns the same tab id).
    - `removeGrangerBookAlsoRemovesItsDownload`.
    - `supersededClassicsZimIsFlagged`.
  - `ImportRunnerTest`:
    - `oneBadFileDoesNotStopTheOthers`.
    - `summaryCountsAndNamesProblems`.
    - `unreadableUriIsAProblemNotACrash`.
  - `LibraryViewModelTest`: `importSummaryIsShown`.
  - `SourceViewModelTest`:
    - `grangerListsBooksByCategory`.
    - `classicsPresetComesFirst`.
    - `installedTabIncludesBooks`.
  - `SearchViewModelTest`:
    - `bookHitsAppearInTheirOwnSection`.
    - `openingABookHitRecordsTheSearchAndTargetsTheHit`.
- [ ] **Step 2:** Run. FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** `./gradlew :app:testDebugUnitTest` PASS.
- [ ] **Step 5: Commit** `Library and sources: book covers and progress, My books import, Granger Classics books with Download all, Search in books`.

---

### Task 12: Whole-app check and roadmap

**Files:**
- Modify: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`

- [ ] **Step 1:** `./gradlew testDebugUnitTest :app:assembleDebug` gives BUILD SUCCESSFUL, with every test passing. Also run `cd tools/pack-builder && python -m pytest -q` and `cd tools/catalog-gen && python -m pytest -q`; both pass.
- [ ] **Step 2: Pixel 6 walkthrough.** Install over the Plan 5 build **without uninstalling**, so the v3 → v4 migration runs on real data. Then check:
  1. Packs, rail tabs, history and saved articles survive the upgrade. The Classics ZIM shows "Replaced by the Granger Classics e-books".
  2. Granger source → "Granger Classics: all books" queues 347 downloads. Measure the total, the time over Wi-Fi, and the index size afterwards (`du` on `databases/library.db*`). Record all three in the roadmap.
  3. Library → Literature → Granger Classics shows a cover grid. Open Hamlet: Night theme, no blue links, no "[Pg 3]" clipping. The two Korans read "The Koran (tr. J. M. Rodwell)" and "The Koran (tr. George Sale)".
  4. Search "quality of mercy": *The Merchant of Venice* appears under "In books", and a tap opens it at the passage.
  5. Read a few pages, highlight a line, add a note, bookmark a page. Swipe the app away and reopen it: you're back on the same page in the book, immersive. The rail shows the cover with "chapter · %".
  6. Import an EPUB, a TXT and a Markdown file from Downloads: each appears in My books and opens paginated. Import a DRM-protected EPUB, if one is to hand, and check the message.
  7. Kill the app during the Classics download or indexing, reopen it, and check that indexing finishes and search finds books indexed after the restart.
- [ ] **Step 3: Update the roadmap.** Mark Plan 6 **Done** with this plan's filename. Under "Carried forward from Plan 6", record:
  - The Step 2 numbers.
  - Everything the walkthrough found.
  - Plan 10: import is Pro-only; queueing the whole Classics preset must be checked against the cap as one group (spec §13); `Features.IMPORT` becomes the entitlement check.
  - Plan 7: archive.org books go to `books/archive-org/` with origin `ARCHIVE_ORG`; `Bookshelf.addDownloaded` needs an origin parameter then.
  - Plan 8: `BookIndex` and `BookHit` are the e-book retrieval path for Inquire & Abstract; the `INQUIRE` selection action is already wired.
  - The search-grouping call from Plan 5 is still open.
- [ ] **Step 4: Commit** `Plan 6 done: e-book reader and Classics e-books verified on the Pixel 6; roadmap updated`.
