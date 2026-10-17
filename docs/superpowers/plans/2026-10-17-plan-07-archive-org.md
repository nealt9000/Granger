# Plan 7: Archive.org Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Archive.org becomes a third archive source. You can search it and browse a few shelves. It is filtered to public domain until you accept a waiver. Openly served EPUBs, or OCR text where there's no EPUB, download with sha1/md5 verification, are filed under Literature → *Archive.org*, and open in the e-book reader and in Search.

**Architecture:** A new `:core:archive` module talks to archive.org's public API: `advancedsearch.php` for search and browse, and `/metadata/<id>` for an item's files. It also decides what an item offers (EPUB, OCR text, PDF only, lend-only). `:core:downloads` learns to verify with sha1 or md5 as well as sha256 and to queue a non-catalog `DownloadSpec`. `:core:library`'s `Bookshelf.addDownloaded` gains an origin, archive.org metadata, and TXT→EPUB conversion. The app adds an Archive.org source screen with an item sheet and a waiver dialog, a Settings switch, and the wiring.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), Room (AutoMigration), OkHttp 5.5.0, kotlinx.serialization 1.11.0, Robolectric 4.17, JUnit 4 + Truth.

**Spec:** `docs/superpowers/specs/2026-10-16-library-redesign-design.md` §5 "Archive.org", §2 (anti-goals), §4 (filing), §13 (cap). The roadmap is `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md` ("Carried forward from Plan 6").

## Decisions made with Neal for this plan (2026-10-17)

- **OCR text counts as TXT.** About 470k public-domain archive.org texts have an EPUB; about 9 million have only the scanned PDF plus archive.org's OCR text (`*_djvu.txt`, format `DjVuTXT`). An item with an EPUB downloads the EPUB. Otherwise its text file downloads (an uploaded original `.txt` first, then the OCR text) and opens as plain text, labelled "OCR text: may have errors". An item that has a PDF but no EPUB also says "PDF: coming soon".
- **Browse is a few fixed shelves:** Most downloaded · Technical manuals · Science & engineering · Literature, each sorted by downloads.
- Noted for later, not in this plan: Neal is considering data sources per library category, with archive.org appearing under several.

## Global Constraints

- Filtered view (until the waiver is accepted): `mediatype:texts AND year:[* TO 1930]`. Items with no year never match, so undated items are left out (spec §5). Changed 2026-10-17 after the Pixel 6 walkthrough: uploader licence tags are not trusted. (It was `(year:[* TO 1930] OR licenseurl:*publicdomain*)`; the licence half let modern books through.)
- Unfiltered view: `mediatype:texts` with no rights clause.
- **Never download** a file with `private == "true"`, or a file whose format starts with `LCP Encrypted`. No lending accounts, no DRM handling (spec §5, §2 anti-goal).
- Lend-only items (`access-restricted-item` true with no open EPUB or text) show **"Borrow on archive.org"**, which opens `https://archive.org/details/<identifier>` in the system browser.
- Verification uses sha1 when archive.org gives one, else md5. Never skip verification: a file with neither isn't offered.
- Files go to `<volume>/books/archive-org/<identifier>.epub`, or `<identifier>.txt` with its converted `<identifier>.epub` beside it. Download item ids are `archive-org:<identifier>`; book ids are `book:archive-org:<identifier>` (`BookIds.of(BookOrigin.ARCHIVE_ORG, identifier)`).
- Identifiers must match `[A-Za-z0-9._-]+` and not start with `.`; anything else is refused before any URL or path is built.
- Archive.org downloads count toward the cap exactly like Classics books (`ItemKind.BOOK`, capped). Free, not Pro-only (spec §13).
- Every archive.org request sends `User-Agent: Granger/<versionName> (+https://github.com/nealt9000/Granger)`.
- Copy (exact):
  - Filter note: "Showing public-domain texts only: published 1930 or earlier." (changed 2026-10-17; it ended ", or marked public domain") plus a **Show everything** button.
  - Unfiltered note: "Showing everything archive.org serves openly. You're responsible for what you download."
  - Waiver dialog title "Show everything on archive.org?", body: "Granger is a browser for archive.org. It doesn't host, check or vouch for anything there, and much of it is still under copyright. You're responsible for having the right to download and use what you take.", buttons **I accept** / **Cancel**.
  - Standing disclaimer at the foot of the source screen: "Archive.org content is provided by the Internet Archive. Granger doesn't host or vouch for it."
  - Labels: "EPUB" · "OCR text: may have errors" · "PDF: coming soon" · "Borrow on archive.org" · "No downloadable text".
- Builds on this 5.7 GB machine: `./gradlew -Pkotlin.compiler.execution.strategy=in-process …`, one build at a time, then `./gradlew --stop`.
- `connectedDebugAndroidTest` wipes the phone's packs; don't run it in this plan. Never touch the phone while a personal app is in front.

## Review Focus

1. **A lending item whose open-looking EPUB is `private`.** On a real lending item (`lbg.vol.xviii.p.1.agriculturaljour0000unse_b7i9`) the `.epub` is `private: "true"` and the `_lcp.epub` isn't private but is DRM. Expected: lend-only, nothing downloadable. Pinned in Task 3 (`lendingItemOffersNothingToDownload`).
2. **Changing the filter while results are loading, or tapping Get on an item reached while unfiltered after withdrawing the waiver.** Expected: results never mix the two views, and Get in the filtered view refuses an item whose metadata isn't public domain. Pinned in Task 3 (`isPublicDomain…`) and Task 6 (`staleResultsFromTheOtherViewAreDropped`, `filteredGetRefusesANonPublicDomainItem`).
3. **Process death between a verified `.txt` download and its conversion.** Expected: the next start's scan converts and registers it, so the book isn't lost. Pinned in Task 4 (`refreshConvertsAStrayArchiveText`).
4. **Upgrading a phone with existing downloads.** Expected: download records survive the Room 1→2 migration as sha256 records, and Readiness still verifies them. Pinned in Task 1 (`version1RecordsBecomeSha256`).
5. **Offline, or archive.org down or rate-limited (429/503).** Expected: a plain message and a Retry; the Installed tab still lists downloaded items from the ledger. Pinned in Task 2 (`serverErrorIsAnArchiveError`) and Task 6 (`offlineSearchShowsAMessageAndInstalledStillWorks`).

---

### Task 1: Downloads verify with sha1/md5 and queue non-catalog items

**Files:**
- Modify: `core/downloads/src/main/kotlin/app/granger/core/downloads/DownloadRecord.kt`, `Downloads.kt`, `DownloadRunner.kt`, `FileDownloader.kt`, `IntegrityChecker.kt`, `DownloadDatabase.kt`, `DownloadWorker.kt` (wherever it passes `sha256`)
- Modify: `core/downloads/build.gradle.kts` (add `testImplementation(libs.androidx.room.testing)`, as `:core:library` has)
- Modify: `app/src/main/kotlin/app/granger/readiness/ReadinessViewModel.kt` and other `record.sha256` call sites (`grep -rn "\.sha256" app core/downloads`)
- Generated: `core/downloads/schemas/app.granger.core.downloads.DownloadDatabase/2.json`
- Test: `core/downloads/src/test/kotlin/app/granger/core/downloads/{FileDownloaderTest,DownloadManagerTest,IntegrityCheckerTest,DownloadMigrationTest}.kt`

**Interfaces:**
- Produces:
  - `enum class HashAlgorithm(val jcaName: String) { SHA256("SHA-256"), SHA1("SHA-1"), MD5("MD5") }`
  - `DownloadRecord`: the field `sha256` is renamed to `hash` (still part of the primary key `["itemId", "hash"]`), and `val hashAlgorithm: HashAlgorithm = HashAlgorithm.SHA256` is added after it. DAO parameters named `sha256` become `hash`.
  - `data class DownloadRequest(val urls: List<String>, val sizeBytes: Long, val hash: String, val algorithm: HashAlgorithm = HashAlgorithm.SHA256)`
  - `data class DownloadSpec(val itemId: String, val kind: ItemKind, val title: String, val urls: List<String>, val sizeBytes: Long, val hash: String, val hashAlgorithm: HashAlgorithm, val relativePath: String, val snapshotDate: String, val capped: Boolean)`. `relativePath` is under the volume root, e.g. `books/archive-org/x.epub`.
  - `Downloads.enqueue(spec: DownloadSpec, volumeRoot: File, wifiOnly: Boolean): EnqueueResult`. The existing `enqueue(item: CatalogItem, …)` stays on the interface and builds a spec (same paths as today, `SHA256`, `capped = item is PackEntry || item is BookEntry`), then calls the new one.
  - `Downloads.recordCheck(itemId: String, hash: String, verified: Boolean)` (parameter renamed).
  - `DownloadRunner.run(itemId, hash, …)` and `giveUp(itemId, hash)` (parameters renamed).

- [ ] **Step 1: Write the failing tests**

```kotlin
// FileDownloaderTest
@Test fun verifiesWithSha1() = runTest {
    server.files[a] = content
    val result = downloader.download(DownloadRequest(listOf(a), content.size.toLong(), sha1(content), HashAlgorithm.SHA1), part)
    assertThat(result).isEqualTo(DownloadResult.Done)
}
@Test fun md5MismatchDeletesThePart() = runTest {
    server.files[a] = content
    val result = downloader.download(DownloadRequest(listOf(a), content.size.toLong(), "0".repeat(32), HashAlgorithm.MD5), part)
    assertThat(result).isEqualTo(DownloadResult.HashMismatch)
    assertThat(part.exists()).isFalse()
}
@Test fun resumedSha1DownloadHashesTheEarlierBytes()  // partWith(first half) then serve with Range; Done with SHA1

// DownloadManagerTest
@Test fun specIsQueuedAtItsRelativePathWithItsAlgorithm() // enqueue(DownloadSpec(itemId="archive-org:x", kind=BOOK, …, relativePath="books/archive-org/x.epub", hashAlgorithm=SHA1, capped=true))
    // record.filePath == File(root, "books/archive-org/x.epub").path, record.hashAlgorithm == SHA1, record.kind == BOOK
@Test fun cappedSpecCountsAgainstTheCap()  // capBytes = 100, installed 90, spec 20, capped → OverCap(100, 110)
@Test fun uncappedSpecIgnoresTheCap()      // same numbers, capped=false → Queued
@Test fun specWithTheInstalledHashIsAlreadyInstalled()

// IntegrityCheckerTest
@Test fun fullCheckUsesTheRecordsAlgorithm()  // record(hash = sha1(bytes), hashAlgorithm = SHA1) → VERIFIED; wrong sha1 → CORRUPTED

// DownloadMigrationTest (Robolectric, sdk 35, MigrationTestHelper like core/library's MigrationTest)
@Test fun version1RecordsBecomeSha256() {
    // createDatabase(…, 1) with one INSTALLED row (sha256 = "ab…"); runMigrationsAndValidate(…, 2, true)
    // SELECT hash, hashAlgorithm → "ab…", "SHA256"
}
```

- [ ] **Step 2: Run them and check they fail**

Run: `./gradlew -Pkotlin.compiler.execution.strategy=in-process :core:downloads:testDebugUnitTest`
Expected: compile failure: `HashAlgorithm`, `DownloadSpec`, `hash` unresolved.

- [ ] **Step 3: Implement**

- `FileDownloader`: `MessageDigest.getInstance(request.algorithm.jcaName)`; compare with `request.hash.lowercase()`. The mismatch message in `DownloadRunner` becomes "Checksum mismatch: the download didn't match its expected checksum."
- `IntegrityChecker.full`: digest by `record.hashAlgorithm`.
- `DownloadDatabase`: `version = 2`, `autoMigrations = [AutoMigration(from = 1, to = 2, spec = DownloadDatabase.Rename1To2::class)]`, where `@RenameColumn(tableName = "downloads", fromColumnName = "sha256", toColumnName = "hash") class Rename1To2 : AutoMigrationSpec`. `hashAlgorithm` gets `@ColumnInfo(defaultValue = "SHA256")`. Room stores enums by name, so no converter is needed.
- `DownloadManager.enqueue(spec…)`: today's body, with `spec.capped` replacing `isCapped`, `File(root, spec.relativePath)` replacing `targetFile`, and `spec.hash` for the version checks. `queuedPackBytes` stays "active PACK or BOOK records".
- Rename the call sites. Readiness keys (`keyOf(record)`) use `hash`.

- [ ] **Step 4: Run the module's tests and the app's tests**

Run: `./gradlew -Pkotlin.compiler.execution.strategy=in-process :core:downloads:testDebugUnitTest :app:testDebugUnitTest`, then `./gradlew --stop`
Expected: all pass, and the `2.json` schema is generated.

- [ ] **Step 5: Commit**

```bash
git add core/downloads app/src/main/kotlin/app/granger/readiness app/src/test
git commit -m "Downloads verify with sha1 or md5 as well as sha256, and queue items that aren't in the catalog"
```

---

### Task 2: `:core:archive`: search and item metadata client

**Files:**
- Create: `core/archive/build.gradle.kts` (copy `core/catalog/build.gradle.kts`: android library + serialization plugin, namespace `app.granger.core.archive`, `api(libs.okhttp)`, serialization-json, coroutines; tests: junit, truth, coroutines-test)
- Modify: `settings.gradle.kts` (add `":core:archive"`), `app/build.gradle.kts` (`implementation(project(":core:archive"))`)
- Create: `core/archive/src/main/kotlin/app/granger/core/archive/ArchiveQuery.kt`, `ArchiveModels.kt`, `ArchiveOrgClient.kt`
- Create fixtures by capturing real responses with curl: `core/archive/src/test/resources/search-steam-engine.json` (the advancedsearch query below for `steam engine`, `rows=3`), `item-open-epub.json` (`/metadata/contributionletu0037vari_w0s3`), `item-lending.json` (`/metadata/lbg.vol.xviii.p.1.agriculturaljour0000unse_b7i9`), `item-scan-only.json` (`/metadata/scientific-american-1881-10-15`)
- Test: `core/archive/src/test/kotlin/app/granger/core/archive/{ArchiveQueryTest,ArchiveOrgClientTest}.kt` (an OkHttp `Interceptor` fake like `core/downloads/src/test/.../FakeServer.kt`, serving fixtures by path)

**Interfaces:**
- Produces:
  - `enum class ArchiveShelf(val title: String, val clause: String)`:
    - `MOST_DOWNLOADED("Most downloaded", "")`
    - `MANUALS("Technical manuals", "collection:manuals")`
    - `SCIENCE("Science & engineering", "subject:(science OR engineering OR mechanics OR chemistry OR physics)")`
    - `LITERATURE("Literature", "subject:(fiction OR literature OR poetry OR drama)")`
  - `object ArchiveQuery { const val PUBLIC_DOMAIN = "year:[* TO 1930]"; fun build(text: String, shelf: ArchiveShelf?, filtered: Boolean): String; fun isValidIdentifier(id: String): Boolean }`. `build` gives `mediatype:texts`, then ` AND (<text>)` when the text isn't blank, ` AND <clause>` for a shelf with one, and ` AND $PUBLIC_DOMAIN` when filtered. Search text is passed through, but unbalanced quotes and parentheses are stripped first, so a stray `"` can't break the whole query.
  - `data class ArchiveDoc(val identifier: String, val title: String, val creator: String, val year: Int?, val formats: List<String>, val restricted: Boolean, val downloads: Long)`
  - `data class SearchPage(val total: Int, val docs: List<ArchiveDoc>)`
  - `data class ArchiveFile(val name: String, val format: String, val sizeBytes: Long?, val sha1: String?, val md5: String?, val source: String, val private: Boolean)`
  - `data class ArchiveItem(val identifier: String, val title: String, val creator: String, val date: String, val year: Int?, val subjects: List<String>, val description: String, val language: String, val licenseUrl: String, val restricted: Boolean, val files: List<ArchiveFile>)`
  - `class ArchiveError(message: String) : Exception(message)` for network, HTTP-status and parse failures.
  - `class ArchiveOrgClient(client: OkHttpClient, userAgent: String, baseUrl: String = "https://archive.org")` with:
    - `suspend fun search(query: String, page: Int, rows: Int = 50, sortByDownloads: Boolean = true): SearchPage`: GET `/advancedsearch.php` with `q`, `fl[]` = identifier, title, creator, year, format, access-restricted-item, downloads; `sort[]=downloads desc` when sorting; `rows`, `page` (1-based), `output=json`. Runs on `Dispatchers.IO`.
    - `suspend fun item(identifier: String): ArchiveItem`: GET `/metadata/<identifier>`. An empty `{}` body (unknown item) or `is_dark: true` throws `ArchiveError("Not found on archive.org")`.
    - `fun downloadUrl(identifier: String, fileName: String): String`: `"$baseUrl/download/$identifier/" + fileName`, percent-encoded per path segment (file names can contain spaces and `/`).
    - `fun detailsUrl(identifier: String): String`: `"$baseUrl/details/$identifier"`.

Parsing notes, all checked against the live API on 2026-10-17:
- `creator`, `subject`, `title` and `description` are a string *or* an array: join arrays with `", "` (creator), keep them as a list (subject), or take the first (title, description).
- `year` comes as a number in search and is missing from metadata, so derive it from the first four digits of `date`.
- `access-restricted-item` and `private` are the string `"true"`, or missing.
- `size` is a string of digits in metadata.
- Strip HTML from `description` with a simple tag regex, capped at 2,000 characters.
- Use `Json { ignoreUnknownKeys = true }` with `JsonElement`-level reading for the string-or-array fields.

- [ ] **Step 1: Capture the four fixtures with curl** (the exact commands are in the Files list). Check each is valid JSON with `python3 -m json.tool`.

- [ ] **Step 2: Write the failing tests**

```kotlin
// ArchiveQueryTest
@Test fun filteredSearchAddsThePublicDomainClause() = assertThat(ArchiveQuery.build("steam engine", null, filtered = true))
    .isEqualTo("mediatype:texts AND (steam engine) AND year:[* TO 1930]")
@Test fun unfilteredShelfHasNoRightsClause() = assertThat(ArchiveQuery.build("", ArchiveShelf.MANUALS, filtered = false))
    .isEqualTo("mediatype:texts AND collection:manuals")
@Test fun mostDownloadedFilteredIsJustTextsAndRights() // "mediatype:texts AND year:[* TO 1930]"
@Test fun unbalancedQuotesAndParensAreStripped()      // build("\"steam (engine", null, false) == "mediatype:texts AND (steam engine)"
@Test fun identifiersAreValidated()                   // "lbg.vol.xviii.p.1.x_b7i9" ok; "../x", ".x", "a/b", "a b", "" rejected

// ArchiveOrgClientTest
@Test fun searchParsesDocsAndSendsTheUserAgent()   // total 1341, first doc "garcoyle192343", year 1923, "Text PDF" in formats; the request has User-Agent and q
@Test fun searchSendsSortAndPage()                 // sort[]=downloads desc, page=2, rows=50
@Test fun itemParsesFilesAndPrivateFlags()         // item-lending: the .epub file is private=true; the _lcp.epub format "LCP Encrypted EPUB"; restricted=true; year 1923
@Test fun itemJoinsCreatorsAndListsSubjects()      // item-scan-only: subjects contain "steam engine"; creator ""
@Test fun emptyMetadataIsNotFound()                // body "{}" → ArchiveError("Not found on archive.org")
@Test fun serverErrorIsAnArchiveError()            // 503 → ArchiveError whose message names the status; IOException → ArchiveError("Couldn't reach archive.org")
@Test fun downloadUrlEncodesEachSegment()          // downloadUrl("x", "sub dir/My Book.epub") == "https://archive.org/download/x/sub%20dir/My%20Book.epub"
```

- [ ] **Step 3: Run them and check they fail**

Run: `./gradlew -Pkotlin.compiler.execution.strategy=in-process :core:archive:testDebugUnitTest`
Expected: compile failure, as the classes don't exist yet.

- [ ] **Step 4: Implement `ArchiveQuery`, the models and `ArchiveOrgClient` as specified above.**

- [ ] **Step 5: Run the tests and check they pass**, then `./gradlew --stop`.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts app/build.gradle.kts core/archive
git commit -m "Add :core:archive: archive.org search, shelves and item metadata"
```

---

### Task 3: What an archive.org item offers

**Files:**
- Create: `core/archive/src/main/kotlin/app/granger/core/archive/ArchiveOffer.kt`
- Test: `core/archive/src/test/kotlin/app/granger/core/archive/ArchiveOfferTest.kt` (uses the Task 2 fixtures plus hand-built `ArchiveItem`s)

**Interfaces:**
- Consumes: `ArchiveItem`, `ArchiveFile`, `ArchiveDoc` (Task 2).
- Produces:
  - `enum class TextKind { EPUB, TEXT, OCR_TEXT }`
  - `sealed interface Offer`:
    - `data class Download(val file: ArchiveFile, val kind: TextKind, val alsoPdf: Boolean)`
    - `data object PdfOnly`
    - `data object LendOnly`
    - `data object Nothing`
  - `object ArchiveOffers`:
    - `fun choose(item: ArchiveItem): Offer`
    - `fun preview(doc: ArchiveDoc): Offer?`, a guess from search formats for the result list, before metadata. It returns `Download` with a placeholder file (`ArchiveFile(name = "", …)`), so the screen only reads `kind` and `alsoPdf`. It is null when it can't tell.
    - `fun isPublicDomain(item: ArchiveItem): Boolean`: `year != null && year <= 1930` (licence ignored since 2026-10-17).

Rules for `choose`, in order:
1. Usable files are those not `private` and not `format.startsWith("LCP Encrypted")`, with `sha1` or `md5` present and a `sizeBytes` greater than 0.
2. Any usable file of format `EPUB` → `Download(it, EPUB, …)`. If there are several, take the largest.
3. Else a usable `.txt` with `source == "original"` (format `Text`, or a name ending `.txt`) → `TEXT`.
4. Else a usable `DjVuTXT` → `OCR_TEXT`.
5. Else, `restricted`, or any private text/PDF file → `LendOnly`.
6. Else any usable PDF (format contains `PDF`) → `PdfOnly`.
7. Else `Nothing`.

`alsoPdf` is true when step 3 or 4 matched and a usable PDF exists.

`preview(doc)`:
- `"EPUB" in formats` and not restricted → EPUB.
- `"DjVuTXT" in formats` and not restricted → OCR_TEXT with `alsoPdf` = any format containing "PDF".
- Restricted → `LendOnly`.
- Formats containing "PDF" → `PdfOnly`.
- Otherwise `null`.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun openItemOffersItsEpub()                    // item-open-epub → Download(kind = EPUB), file.name endsWith ".epub", sha1 present
@Test fun lendingItemOffersNothingToDownload()       // item-lending → LendOnly (its .epub is private, its _lcp.epub is DRM)
@Test fun scanOnlyItemOffersOcrTextAndSaysPdf()      // item-scan-only → Download(kind = OCR_TEXT, alsoPdf = true), file.name endsWith "_djvu.txt"
@Test fun originalTextBeatsOcr()                     // item with an original "Text" .txt and a DjVuTXT → TEXT
@Test fun fileWithoutAnyChecksumIsNotOffered()       // the only EPUB has sha1 = null, md5 = null → falls through to PdfOnly/Nothing
@Test fun pdfOnlyItem()                              // only "Text PDF" → PdfOnly
@Test fun isPublicDomainByYearOnly()                 // year 1930 → true; 1931 → false; null year + ".../publicdomain/zero/1.0/" → false (2026-10-17); null + "" → false
@Test fun previewFromSearchFormats()                 // ["EPUB"] → EPUB; ["DjVuTXT","Text PDF"] → OCR_TEXT alsoPdf; restricted → LendOnly; ["Text PDF"] → PdfOnly; [] → null
```

- [ ] **Step 2: Run them and check they fail**

Run: `./gradlew -Pkotlin.compiler.execution.strategy=in-process :core:archive:testDebugUnitTest`
Expected: compile failure on `ArchiveOffers`.

- [ ] **Step 3: Implement `ArchiveOffers` with the rules above.**

- [ ] **Step 4: Run the tests and check they pass**, then `./gradlew --stop`.

- [ ] **Step 5: Commit**

```bash
git add core/archive
git commit -m "Choose what an archive.org item offers: EPUB, text or OCR text; never private or LCP files"
```

---

### Task 4: The bookshelf files archive.org books

**Files:**
- Modify: `core/library/src/main/kotlin/app/granger/core/library/Bookshelf.kt`
- Modify: `app/src/main/kotlin/app/granger/GrangerApp.kt` (the `onBookInstalled` call only: pass `BookOrigin.GRANGER`, so this task compiles)
- Modify: `app/src/test/kotlin/app/granger/BookInstalledTest.kt` and `FakeBookshelf` (the new signature)
- Test: `core/library/src/test/kotlin/app/granger/core/library/BookshelfTest.kt`

**Interfaces:**
- Produces:
  - `data class BookDetails(val title: String, val author: String, val date: String, val subjects: List<String>, val description: String, val language: String)`, in `Bookshelf.kt`.
  - `Bookshelf.addDownloaded(file: File, origin: BookOrigin, ref: String, details: BookDetails? = null): Book?`. This replaces `addDownloaded(file, catalogId)`. When `details` is given, its non-blank fields win over the EPUB's own metadata. A `.txt` file is decoded with `TextDecoding.decode`, converted with `TextToEpub.convert(text, details?.title ?: file.nameWithoutExtension, markdown = false, out)` to `File(file.parentFile, file.nameWithoutExtension + ".epub")`, and that EPUB is registered. A text file over `maxTextBytes` returns null.
  - `refresh()` also scans `<booksDir>/archive-org/`. A stray `.epub` registers with origin `ARCHIVE_ORG` and ref `nameWithoutExtension`; no date suffix is stripped, since identifiers contain `_` and `.`. A `.txt` with no `.epub` beside it is converted, then registered.

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun archiveEpubIsFiledUnderArchiveOrgWithItsMetadata() = test {
    val file = epubIn(File(booksDir, "archive-org"), "steam_1908.epub", title = "Untitled scan")
    val book = shelf.addDownloaded(file, BookOrigin.ARCHIVE_ORG, "steam_1908",
        BookDetails("Steam Engines", "A. Watt", "1908", listOf("steam", "engines"), "A manual.", "eng"))!!
    assertThat(book.id).isEqualTo("book:archive-org:steam_1908")
    assertThat(book.title).isEqualTo("Steam Engines")
    assertThat(book.subjects).isEqualTo("steam; engines")
    assertThat(book.date).isEqualTo("1908")
}
@Test fun archiveTextIsConvertedBesideItAndRegistered()   // x.txt → x.epub exists in the same dir; book.filePath endsWith "x.epub"; title from details
@Test fun blankDetailFieldsKeepTheEpubsOwn()              // details.author = "" → the EPUB's author
@Test fun refreshRegistersAStrayArchiveEpub()             // archive-org/a.b_c.epub with no row → book:archive-org:a.b_c
@Test fun refreshConvertsAStrayArchiveText()              // archive-org/scan.txt, no scan.epub, no row → after refresh(), book:archive-org:scan is OK and scan.epub exists
@Test fun grangerDownloadsStillFileAsBefore()             // the existing downloadedBookIsFiledUnderGrangerClassics, with the new signature
```

- [ ] **Step 2: Run them and check they fail**

Run: `./gradlew -Pkotlin.compiler.execution.strategy=in-process :core:library:testDebugUnitTest`
Expected: compile failure on the new `addDownloaded` signature and `BookDetails`.

- [ ] **Step 3: Implement.** `register` takes the merged metadata. Split today's `EpubMeta → Book` mapping so `BookDetails` can override title, author, date, language, subjects and description. Leave the conversion temp file handling as `import` does it: write to `.incoming-…epub`, then move into place.

- [ ] **Step 4: Run `:core:library:testDebugUnitTest :app:testDebugUnitTest` and check they pass**, then `./gradlew --stop`.

- [ ] **Step 5: Commit**

```bash
git add core/library app/src/main/kotlin/app/granger/GrangerApp.kt app/src/test
git commit -m "Bookshelf files archive.org books with their metadata and converts their text to EPUB"
```

---

### Task 5: App wiring: archive downloads end up on the shelf

**Files:**
- Create: `app/src/main/kotlin/app/granger/archive/ArchiveDownloads.kt`, `app/src/main/kotlin/app/granger/archive/ArchivePrefs.kt`
- Modify: `app/src/main/kotlin/app/granger/GrangerApp.kt` (the client, prefs, and routing in `onInstalled`)
- Modify: `app/src/main/kotlin/app/granger/library/CollectionViewModel.kt` (`removeBook`)
- Test: `app/src/test/kotlin/app/granger/archive/ArchiveDownloadsTest.kt`, `app/src/test/kotlin/app/granger/BookInstalledTest.kt`, `app/src/test/kotlin/app/granger/library/CollectionViewModelTest.kt`

**Interfaces:**
- Consumes: `DownloadSpec`, `HashAlgorithm` (Task 1); `ArchiveOrgClient`, `ArchiveItem`, `Offer.Download`, `TextKind` (Tasks 2–3); `Bookshelf.addDownloaded(file, origin, ref, details)`, `BookDetails` (Task 4).
- Produces:
  - `object ArchiveDownloads` with:
    - `const val PREFIX = "archive-org:"`
    - `fun itemId(identifier: String) = PREFIX + identifier`
    - `fun identifierOf(itemId: String): String?` (null unless prefixed)
    - `fun spec(item: ArchiveItem, offer: Offer.Download, client: ArchiveOrgClient): DownloadSpec`. This gives kind `BOOK`, `capped = true`, `snapshotDate = ""`, and hash and algorithm sha1, else md5. `relativePath` is `books/archive-org/<identifier>.epub`, or `.txt` for TEXT/OCR_TEXT. The URL is `client.downloadUrl(identifier, file.name)`. The title is `item.title`, or the identifier if the title is blank. It requires `ArchiveQuery.isValidIdentifier`.
    - `fun details(item: ArchiveItem, kind: TextKind): BookDetails`. For OCR_TEXT the description is prefixed with "OCR text from a scan: may have errors. ".
  - `interface ArchivePrefs { val waiverAccepted: StateFlow<Boolean>; fun accept(); fun withdraw() }` with `SharedArchivePrefs(context)` (prefs file `"archive"`, key `waiverAcceptedAt: Long`, 0 = not accepted).
  - `AppContainer.archiveClient: ArchiveOrgClient`, `AppContainer.archivePrefs: ArchivePrefs`.
  - `AppContainer` keeps archive item details from the moment Get is tapped until install, so `onInstalled` can file the book with archive.org's metadata. Store them as a small JSON sidecar `books/archive-org/<identifier>.json` (title, creator, date, subjects, description, language, kind), written by the view model before enqueue and deleted after filing, so they survive process death. Helpers `ArchiveDownloads.writeSidecar(dir: File, item: ArchiveItem, kind: TextKind)` and `readSidecar(file: File): Pair<BookDetails, TextKind>?`.
  - `onBookInstalled(bookshelf, file, origin, ref, details, startIndexing)` (signature extended). In `GrangerApp`'s `onInstalled`, a BOOK record whose itemId has the archive prefix → origin `ARCHIVE_ORG`, ref = the identifier, details from the sidecar (or null when it's missing; the EPUB's own metadata is then used).
  - `CollectionViewModel.removeBook`: for `ARCHIVE_ORG`, call `downloads.remove(ArchiveDownloads.itemId(book.sourceRef))` too. That deletes the `.txt`; the bookshelf removes the `.epub`.

- [ ] **Step 1: Write the failing tests**

```kotlin
// ArchiveDownloadsTest
@Test fun epubSpecUsesSha1AndTheArchivePath()   // spec(itemOpenEpub, Download(epubFile, EPUB, false), client): itemId "archive-org:contributionletu0037vari_w0s3",
                                                // relativePath "books/archive-org/contributionletu0037vari_w0s3.epub", SHA1, capped, kind BOOK, urls == [client.downloadUrl(...)]
@Test fun md5IsUsedWhenThereIsNoSha1()
@Test fun ocrTextGoesToATxtPathAndSaysSoInTheDescription()
@Test fun badIdentifierIsRefused()              // identifier "../x" → IllegalArgumentException
@Test fun sidecarRoundTrips()
@Test fun identifierOfOnlyAcceptsThePrefix()    // "archive-org:x" → "x"; "pg1" → null

// BookInstalledTest
@Test fun archiveBookIsFiledWithItsOriginAndDetails()   // the fake shelf records (origin, ref, details) == (ARCHIVE_ORG, "x", details)

// CollectionViewModelTest
@Test fun removingAnArchiveBookRemovesItsDownload()     // the fake downloads records remove("archive-org:x")
```

- [ ] **Step 2: Run them and check they fail**

Run: `./gradlew -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest`
Expected: compile failure on `ArchiveDownloads`.

- [ ] **Step 3: Implement.** `AppContainer` builds `ArchiveOrgClient(http, "Granger/${BuildConfig.VERSION_NAME} (+https://github.com/nealt9000/Granger)")`.

- [ ] **Step 4: Run `:app:testDebugUnitTest` and check it passes**, then `./gradlew --stop`.

- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "Archive.org downloads file into Literature → Archive.org with their metadata"
```

---

### Task 6: `ArchiveViewModel`: search, shelves, filter, Get

**Files:**
- Create: `app/src/main/kotlin/app/granger/archive/ArchiveViewModel.kt`
- Test: `app/src/test/kotlin/app/granger/archive/ArchiveViewModelTest.kt`. Fakes: an `ArchiveOrgClient` over an interceptor serving fixtures (copy the four Task 2 fixtures into `app/src/test/resources/archive/`), the `FakeDownloads` already used by `SourceViewModelTest`, a fake `ArchivePrefs`, and `FakeVolumes`/`FakeDownloadPrefs` like `SourceViewModelTest`'s.

**Interfaces:**
- Consumes: Tasks 2, 3 and 5; `Downloads`, `VolumeSource`, `DownloadPrefs`, `formatBytes`; `ItemStatus` and `statusOf`-style logic. Build archive row status from records directly: active → Queued/Downloading/Failed, INSTALLED → Installed, else NotInstalled.
- Produces:
  - `enum class ArchiveTab(val title: String) { BROWSE("Browse"), INSTALLED("Installed") }`. Archive.org has no versions, so there's no Updates tab (spec §5).
  - `data class ArchiveRow(val identifier: String, val title: String, val details: String /* creator · year */, val preview: Offer?, val status: ItemStatus)`
  - `data class ItemSheet(val identifier: String, val loading: Boolean, val item: ArchiveItem?, val offer: Offer?, val sizeBytes: Long?, val error: String?, val status: ItemStatus)`
  - `data class ArchiveUi(val tab: ArchiveTab, val query: String, val shelf: ArchiveShelf?, val filtered: Boolean, val rows: List<ArchiveRow>, val total: Int, val loading: Boolean, val canLoadMore: Boolean, val error: String?, val sheet: ItemSheet?, val notice: String?)`
  - `class ArchiveViewModel(client: ArchiveOrgClient, downloads: Downloads, bookshelf: Bookshelf, prefs: ArchivePrefs, volumes: VolumeSource, downloadPrefs: DownloadPrefs, sidecarDir: () -> File /* internal volume books/archive-org */, appScope, io = Dispatchers.IO) : ViewModel()` with:
    - `setQuery(String)` (debounced 400 ms, then searches with no shelf)
    - `selectShelf(ArchiveShelf)` (clears the query)
    - `loadMore()`, `retry()`, `setTab(ArchiveTab)`
    - `openItem(identifier)` (fills `sheet` from `client.item`), `closeItem()`
    - `get(identifier)`, `cancel(identifier)`, `retryDownload(identifier)`, `remove(identifier)`
    - `acceptWaiver()`, `dismissNotice()`
  - On start: shelf `MOST_DOWNLOADED`, page 1.
  - The Installed tab is built from download records with the archive prefix, offline. Its title is `record.title`, and its preview comes from the file extension.

Behaviour:
- Every search carries a generation number. A response for an older query, shelf or filter state is dropped, and changing `prefs.waiverAccepted` reruns the current search. This is Review Focus 2.
- `get`:
  1. Fetch `client.item`, or use the sheet's item if it's loaded.
  2. Choose the offer; anything but `Download` → notice "Nothing on this item can be downloaded."
  3. If filtered and not `ArchiveOffers.isPublicDomain(item)` → notice "This item wasn't published in 1930 or earlier. Show everything to download it." (changed 2026-10-17) Nothing is queued.
  4. Write the sidecar.
  5. `downloads.enqueue(spec, volume.root, downloadPrefs.wifiOnly)`. Map the `EnqueueResult` to the same notices `SourceViewModel.enqueue` uses (no space, over cap).
- `remove(identifier)`: `downloads.remove(itemId)`, then `bookshelf.remove(BookIds.of(ARCHIVE_ORG, identifier))`.
- Errors: `ArchiveError` message, else "Couldn't reach archive.org. Check your connection and try again."

- [ ] **Step 1: Write the failing tests**

```kotlin
@Test fun startsOnMostDownloadedFiltered()                  // the first request's q == ArchiveQuery.build("", MOST_DOWNLOADED, true); filtered == true
@Test fun typingSearchesWithoutAShelfAfterTheDebounce()
@Test fun acceptingTheWaiverRerunsUnfiltered()              // q has no "year:[* TO 1930]"
@Test fun staleResultsFromTheOtherViewAreDropped()          // the first response delayed; accept the waiver; only the unfiltered docs end up in rows
@Test fun loadMoreAppendsTheNextPage()                      // page=2 requested; rows grow; canLoadMore false once rows.size == total
@Test fun openItemFillsTheSheetWithOfferAndSize()           // item-scan-only → offer Download(OCR_TEXT, alsoPdf = true), sizeBytes == the djvu.txt size
@Test fun getQueuesTheSpecAndWritesTheSidecar()             // FakeDownloads got a spec with itemId "archive-org:contributionletu0037vari_w0s3"; sidecar file exists
@Test fun filteredGetRefusesANonPublicDomainItem()          // item year 1950, no licence, filtered → notice; nothing enqueued
@Test fun getOnALendingItemQueuesNothing()                  // item-lending → notice "Nothing on this item can be downloaded."
@Test fun overCapShowsTheLimitNotice()
@Test fun offlineSearchShowsAMessageAndInstalledStillWorks() // the client throws; error set; the Installed tab lists an INSTALLED "archive-org:x" record
@Test fun rowStatusFollowsTheDownloadRecords()              // RUNNING record → Downloading(0.5f)
```

- [ ] **Step 2: Run them and check they fail**

Run: `./gradlew -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest --tests 'app.granger.archive.*'`
Expected: compile failure on `ArchiveViewModel`.

- [ ] **Step 3: Implement `ArchiveViewModel`.**

- [ ] **Step 4: Run the tests and check they pass**, then `./gradlew --stop`.

- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "Archive.org view model: shelves, search, public-domain filter, item details and Get"
```

---

### Task 7: Archive.org screen, waiver, Settings and Library entry

**Files:**
- Create: `app/src/main/kotlin/app/granger/archive/ArchiveScreen.kt` (screen, result rows, item bottom sheet, waiver dialog)
- Modify: `app/src/main/kotlin/app/granger/GrangerNavHost.kt` (`@Serializable object ArchiveRoute`, its composable, rail selection = LIBRARY like `SourceRoute`'s siblings)
- Modify: `app/src/main/kotlin/app/granger/library/LibraryViewModel.kt` and `LibraryScreen.kt`: `sources` becomes `List<SourceLink>`, with `enum class SourceLink(val title: String) { GRANGER("Granger"), KIWIX("Kiwix"), ARCHIVE_ORG("Archive.org") }`. `onOpenSource(SourceLink)` maps GRANGER/KIWIX to `SourceRoute` and ARCHIVE_ORG to `ArchiveRoute`.
- Modify: `app/src/main/kotlin/app/granger/settings/SettingsViewModel.kt`, `SettingsScreen.kt`: an "Archive.org: show everything" switch. Turning it on shows the same waiver dialog (shared composable `WaiverDialog(onAccept, onDismiss)`); turning it off calls `withdraw()` at once with the supporting text "Downloaded files stay."
- Test: `app/src/test/kotlin/app/granger/settings/SettingsViewModelTest.kt`, `app/src/test/kotlin/app/granger/library/LibraryViewModelTest.kt`

**Interfaces:**
- Consumes: `ArchiveViewModel`/`ArchiveUi` (Task 6), `ArchivePrefs` (Task 5).
- Produces: `SettingsUi.archiveEverything: Boolean`; `SettingsViewModel.acceptArchiveWaiver()`, `withdrawArchiveWaiver()`. The constructor gains `archivePrefs: ArchivePrefs`.

Screen layout (match `SourceScreen`'s scaffold, search field, tab row and snackbar):
1. Top bar: "Archive.org".
2. Search field: "Search archive.org texts".
3. Tabs Browse / Installed.
4. Filter note: the filtered or unfiltered copy from Global Constraints, with **Show everything** opening `WaiverDialog`.
5. Browse with an empty query: a row of `FilterChip`s, one per `ArchiveShelf`.
6. Result rows: the title; the details; a label from `preview` (EPUB · OCR text: may have errors · PDF: coming soon · Borrow on archive.org); and the status line from `SourceScreen`'s `CatalogItemRow` (reuse it by making `CatalogItemRow`'s status text an `internal` composable).
7. "Load more" at the end when `canLoadMore`.
8. The standing disclaimer as the last item.

Tapping a row opens a `ModalBottomSheet` with:
- The title, creator, date, subjects (first 6), and description (max 6 lines).
- The offer label and size.
- Buttons by offer and status:
  - Get / Cancel / Retry / Remove, as `CatalogItemRow` does.
  - **Borrow on archive.org** (`LendOnly`) → `Intent(ACTION_VIEW, Uri.parse(client.detailsUrl(id)))`.
  - "PDF: coming soon" as disabled text for `PdfOnly`, and alongside Get when `alsoPdf`.
  - **View on archive.org** always (the same intent).
- Remove asks first with an `AlertDialog`: "Remove <title>? This deletes the file. Your place and highlights are kept."
- Call `beforeDownload()` (the notification permission ask) the way `SourceScreen` does.

- [ ] **Step 1: Write the failing tests**

```kotlin
// SettingsViewModelTest
@Test fun archiveWaiverCanBeAcceptedAndWithdrawn()  // archiveEverything false → acceptArchiveWaiver() → true → withdrawArchiveWaiver() → false; the fake prefs follow
// LibraryViewModelTest
@Test fun archiveSourcesListArchiveOrgLast()        // ui.sources == [GRANGER, KIWIX, ARCHIVE_ORG]
```

- [ ] **Step 2: Run them and check they fail**

Run: `./gradlew -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest`
Expected: compile failures on `SourceLink` and `archiveEverything`.

- [ ] **Step 3: Implement the screen, the dialog, the nav route, the Library link and the Settings switch.**

- [ ] **Step 4: Run all unit tests and assemble**

Run: `./gradlew -Pkotlin.compiler.execution.strategy=in-process testDebugUnitTest :app:assembleDebug`, then `./gradlew --stop`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "Archive.org source screen with item sheet, public-domain filter and waiver; Settings switch; Library entry"
```

---

### Task 8: Pixel 6 walkthrough and roadmap

**Files:**
- Modify: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md` (Plan 7 → Done, plus a "Carried forward from Plan 7" list)

- [ ] **Step 1: Install on the Pixel 6** with `./gradlew -Pkotlin.compiler.execution.strategy=in-process :app:installDebug` (not `connectedDebugAndroidTest`), then `./gradlew --stop`. Check that the app upgrade kept the existing downloads: Readiness lists them and Verify passes on one Classics book. This covers the Room 1→2 migration on a real ledger.

- [ ] **Step 2: Walk through and record each result.** Check first that Granger is the app in front. Then:
  1. Library → Archive sources → Archive.org: the shelves load filtered, and the note shows.
  2. Search `steam engine`, open an OCR-text item, and Get it: it downloads and verifies, and appears in Library → Literature → Archive.org with archive.org's title. It opens in the e-book reader, and a word from it is found in Search under "In books".
  3. Get an item with an EPUB, such as `meditationsofmar00marc`: the same checks pass.
  4. Open a lending item (unfiltered: search `agricultural journal 1923`, or open `lbg.vol.xviii.p.1.agriculturaljour0000unse_b7i9`): it says Borrow on archive.org, and the browser opens the details page.
  5. Show everything: the waiver appears, and accepting it re-runs the search without the filter. Withdraw it in Settings: the filter is back and the downloaded books stay.
  6. Turn Wi-Fi off (`adb shell svc wifi disable` plus airplane mode): search shows the offline message, and the Installed tab still lists the downloads. Turn Wi-Fi back on.
  7. Remove a book from the item sheet: the file and its `.txt` are gone, and the rail shows "Removed book".
  8. Rotate the phone while the sheet is open, and kill the process mid-download (`adb shell am kill app.granger` with the app in the background): the download resumes, then files correctly.

- [ ] **Step 3: Fix what fails** (systematic-debugging, each fix with a test), then commit each fix.

- [ ] **Step 4: Update the roadmap.**
  - Plan 7 → Done, with the date.
  - Carried forward:
    - The Play policy check for this source (Plan 11).
    - OCR text quality: plain text only, page headers inline.
    - PDF-only items wait for the PDF pass.
    - No thumbnails in results.
    - Neal's per-category-sources idea.
    - Anything from Step 2 left open.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md
git commit -m "Plan 7 done: archive.org source verified on the Pixel 6; roadmap updated"
```
