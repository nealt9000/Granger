# Granger v1 — Plan Roadmap

Spec: `docs/superpowers/specs/2026-10-15-granger-v1-design.md`

The spec spans nine milestones across independent subsystems. Each milestone gets its own implementation plan, written when the previous one has landed, so every plan builds on real code and measured numbers instead of guesses. Each plan ends with working, tested software.

| # | Plan | Delivers | Status |
| --- | --- | --- | --- |
| 1 | Foundation + model spike | Toolchain, Gradle project, `:app` + `:core:ai`, `DeviceTier`, `LlmEngine` over LiteRT-LM, debug spike screen, measured Pixel 6 results and decisions | **Written:** `2026-10-15-plan-01-foundation-model-spike.md` |
| 2 | Reader | `:core:zim` (java-libkiwix `org.kiwix:libkiwix`), test ZIM, `:core:library` (Room), Library screen, WebView reader, title + full-text search, bookmarks, reading position | **Done:** `2026-10-15-plan-02-reader.md` |
| 3 | Catalog + downloads + Readiness | `tools/catalog-gen`, Ed25519-signed catalog, `:core:catalog`, `:core:downloads` (WorkManager, resume, sha256, volumes), Downloads + Readiness screens | **Done:** `2026-10-16-plan-03-catalog-downloads-readiness.md` |
| 4 | Pack builder + Granger Classics | `tools/pack-builder` (python-libzim), Classics manifest, Classics pack on a GitHub release, catalog entry | **Done:** `2026-10-16-plan-04-pack-builder-classics.md` |
| 5 | Shell + Library | Flame theme + icon, side rail (bookmarks per article, History, Recent), immersive mode, open-to-last-place, media types / collections / filing, Library screens, Granger + Kiwix source screens, Settings (Models, Readiness), polished web-style reader with selection actions, schema migration | **Done:** `2026-10-16-plan-05-shell-library.md` (reader device tests pending) |
| 6 | E-book reader + Classics EPUBs | Readium reader (pagination, text settings, highlights, notes, dictionary, in-book search), `:core:books` (EPUB/TXT/MD, DRM detection; search index is contentless FTS4), My books, pack-builder EPUB output, Classics re-release, Search over e-books | **Done:** `2026-10-17-plan-06-ebook-reader-classics.md` (the 347-book "Download all" not yet run on a device) |
| 7 | Archive.org source | Public-domain filter (1930 or earlier) until waiver, shelves + search, open-only downloads (EPUB, else text / OCR text) with sha1/md5 verify, filing, metadata | **Done:** `2026-10-17-plan-07-archive-org.md` |
| 8 | Inquire & Abstract | Old Ask pipeline (rewrite → find → chunk → rank → answer/decline, citation validation, medical banner, Think deeper) plus metadata grounding, cross-source connections, selection entry, kept chats | |
| 9 | Eval harness | `tools/eval`, ~300-question dataset, metrics, threshold/prompt tuning to the pass bar | |
| 10 | Monetization + onboarding + telemetry | `:core:billing` (Play + F-Droid flavors), cap (all sources) + import gating, onboarding, `:core:telemetry` | |
| 11 | Beta | Release config, signing, Play listing (incl. archive.org policy check), internal track | |
| — | After v1: Media | Player, mini-player, audiobooks/music/video, read-aloud + mix, then the PDF pass | |

Library redesign spec (2026-10-16): `docs/superpowers/specs/2026-10-16-library-redesign-design.md` amends the v1 spec and set this order.

Plan 1's measurements feed directly into Plans 6–8 (backend choice, model files in the catalog, the latency target, embedder co-residency).

## Carried forward from Plan 3

- The catalog lives at `https://raw.githubusercontent.com/nealt9000/Granger/main/catalog/catalog.json`, signed with the key at `~/.config/granger/catalog-signing.key` (not in the repo; backed up by Neal). Plan 4 adds Granger Classics and OpenStax to `tools/catalog-gen/curated.json`; their packs need a host (a GitHub release asset is the default) since Kiwix doesn't carry them.
- Models are `evalPassed: false`, so release builds list none until Plan 7 flips them.
- Kiwix's Gutenberg packs (`gutenberg_en_lcc-b`, `lcc-pr`) have no full-text index (`hasFulltextIndex: false`, checked 2026-10-16), so Ask can't search them; Granger Classics (Plan 4, built with the index on) covers that ground.
- Plan 6's `ModelManager` finds installed models through the `downloads` ledger: an `INSTALLED` record of kind `MODEL`, file at `filePath`.
- Plan 8 replaces `capBytes = { Long.MAX_VALUE }` in `AppContainer` with `:core:billing`'s cap, and shows the Pro upsell for `EnqueueResult.OverCap`.
- SD cards were tested only in JVM tests (the Pixel 6 has no slot). Try a phone with one before the beta.
- Android 15 limits `dataSync` foreground services to 6 hours a day; a very slow multi-GB download pauses at the limit and resumes when WorkManager next runs it.
- The Ray Charles sample pack's main page (`index`) renders blank: the ZIM's own `_res_/script.js` throws `$ is not defined` because the page never loads jQuery, so its masonry article list never lays out. Articles open and render fine. Reader item for a later plan (Kiwix injects jQuery for such main pages).
- `connectedDebugAndroidTest` uninstalls the app and wipes downloaded packs and models on the phone; re-download after running device tests.
- Device testing a network drop: `adb shell cmd connectivity airplane-mode enable` alone did not stop an in-flight download on the Pixel 6 (it kept growing); add `adb shell svc wifi disable`. With both off the row shows "Waiting for Wi-Fi" and the `.part` file holds its size; on reconnect it resumes from the same size. A force-stop plus relaunch also resumes by itself.
- Kiwix retires snapshots; the catalog pins exact URLs and hashes, so regenerate and re-sign it at least monthly (or schedule it). Downloads of a retired snapshot now fail with a reason and a Retry button.
- Plan 8: `installedPackBytes` counts every pack row, including MISSING ones (e.g. a removed SD card); decide whether the cap should.
- raw.githubusercontent.com caches `catalog.json` and `.sig` separately; for a few minutes after a push the app may report a failed security check and keep the last good catalog.

## Carried forward from Plan 4

- Physics pack: OpenStax *University Physics* is now CC BY-NC-SA 4.0, so it's out. A curation spike picks a replacement before it's built.
- Rebuilding Classics means a new release tag (`classics-<date>`), `pack_builder.py describe` into `tools/pack-builder/releases/granger_classics_en.json`, then a catalog rebuild and re-sign. The monthly Kiwix regeneration leaves the Classics entry alone.
- The Gutenberg cache (HTML + RDF from the aleph.pglaf.org mirror) lives at `~/.cache/granger/gutenberg/`; a rebuild from a warm cache takes a few minutes and fetches nothing.
- Measured: the first Classics build is 83.7 MB, 347 works, 13,194 entries, no page over 150 KB, under the 300–600 MB target because it's text-only. There's room to add works.
- ZIM full-text search has no phrase search (libzim indexes without positions): a famous line like "the quality of mercy" finds its play, but not first; a distinctive word (`Portia mercy`) does.
- Classics presentation defects found on the Pixel 6 (2026-10-16), left unfixed pending the library redesign below: the two Korans both read "The Koran" on the main page (translator not shown when there's no author); some books' Gutenberg CSS overrides the reader theme (`body { background: initial; color: initial }` and link colours load after `classics.css`, so Hamlet shows default blue links on near-black; `.pagenum` sits past the right edge, clipping "[Pg 3]"); full-text snippets start with the nav bar text ("Hamlet‹ PreviousNext ›"); some merged heading-only titles read oddly ("by William Shakespeare — Contents").
- Deferred minors from the Plan 4 review: anchor ids are lost when their `<a>` is unwrapped (65 TOC links land at page top); size-splitting a giant `<pre>`/`<table>` drops the wrapper; build failures other than `CleaningError` don't name the work; seven Canterbury Tales titles show literal `<1>` markers.

### Design pivot raised after Plan 4 (designed: see the library redesign spec)

Neal's direction, 2026-10-16, to brainstorm before the next plan: the library will hold more media types from more sources (Kiwix, Granger Classics, archive.org with a legal disclaimer, the user's own collections). Instead of everything landing flat in the Library, content is grouped into collections: Wikipedia packs nest in a Wikipedia area, and Granger Classics becomes a collection whose works are individual e-books rather than one ZIM read as a website. All e-books, whether Classics, archive.org or the user's own, open in one shared e-book reader. Finished-app picture: opening the app shows where you left off in the book you were reading; a vertical side toolbar has a section at the top with bookmarks to recently opened books and media. This likely reshapes Plan 5 (Import) and the Classics pack format, so the four Classics defects above wait for it.

Deferred, low priority (from Plan 2):
- ~~Opening the reader after the OS killed the app shows the article it was first opened on, not the last page.~~ Fixed by Plan 5: tabs keep the path and scroll position.
- Large media entries are read whole into memory.
- No device test yet for a ZIM truncated or ejected (SD card) while it is open.

## Carried forward from Plan 5

- ~~Rail letter tiles become covers in Plan 6.~~ Done in Plan 6.
- The Inquire & Abstract slot in the rail is switched on in Plan 8.
- ~~The Classics ZIM stays filed as "Granger Classics" until Plan 6 replaces it with e-books.~~ Plan 6: the ZIM is retired from the catalog; an installed copy shows "Replaced by the Granger Classics e-books" with "Get the e-books".
- The three `ReaderWebViewTest` device tests are written but unrun, pending the owner's go-ahead: `connectedDebugAndroidTest` uninstalls the app and wipes downloaded packs and models.
- Pixel 6 walkthrough, after unlock: moving Wikipedia into a new "Reference" media type and back, the Granger, Kiwix and Models source screens, and About all passed. Not checked on a device:
  - Reboot → Library: a reboot leaves the phone on a secure lock screen that adb can't pass. The unit tests cover BOOT_COUNT.
  - A small-pack download and remove: no uninstalled pack is under 50 MB; the next smallest is 272 MB.
- The rail's edge swipe works only inside a 24 dp wide, 200 dp tall strip at the left-middle (Android's gesture-navigation exclusion limit), and, after the final review fix, also in the immersive reader, where it shows the chrome and rail. Judge its discoverability on a device; tapping the narrow rail's background is the other way to expand it.
- On Kiwix main pages every tile is a link, and the reader's middle tap ignores links, so a tap on a tile navigates instead of summoning the chrome. By reading the code, the gaps between tiles still toggle it, but nothing else does (Back only goes back a page). Fixed in the final review (see below). On the device, the left-edge swipe brings up the chrome in the reader, and later readers still open immersive.
- `PublishedCatalogTest` expected a four-item Starter preset; it has had five since the Classics entry was added (Plan 4). The assertion is now five.
- The schema-migration check covered the end state only (the migrated database opens and reads). The first open after the upgrade was not reproduced.
- Search groups results as Titles / In article text with the pack shown on each hit, rather than grouping by collection as spec section 4 says. This is a plan-level deviation for Neal to decide: keep it, or regroup by collection.
- The Readiness screen's Library top-bar entry is left as it is: the owner's call.
- System-bar swipes (the transient bars) do not show the reader chrome; only the left-edge swipe and the middle tap do.

### Fixed in the final whole-branch review of Plan 5

- Immersive reader: the left-edge swipe and its gesture-exclusion strip now stay active in the reader and show the chrome and rail, so a main page of links can't strand the user. The page script's click listener runs in the capture phase and ignores handled clicks, headings and `[role=button]`, so collapsible Wikipedia headings don't also toggle the chrome.
- Launch decision: resume requires the pack's file to exist, not only an OK row from the last scan, and `library.refresh()` runs at startup, so a pulled SD card lands on the Library with the missing-pack notice.
- Theme: the remaining Material purple-tinted roles (inverse, surface containers, dim/bright, secondary and tertiary containers, scrim) are warm neutrals and flame tones; tests check contrast and that no role keeps the baseline.
- Follow-up: the edge-swipe request is one-shot, so a reader opened after a swipe starts immersive (checked on the Pixel 6, including rotation).
- Resume no longer fades the Library into the book; a recent search from the rail replaces only a Search that is on top (a reader opened from Search stays on the stack); a tap on a greyed rail tile explains why nothing opened; unused coroutine scope and import removed.

## Carried forward from Plan 6

Released and measured:
- Release `classics-2026-10-17`: 347 EPUB 3 files, 95.2 MB in total, the largest *Les Misérables* at 1.7 MB. epubcheck 5.2.1 reports 0 errors and 0 warnings for every file. The catalog has a `books` array (347 entries) and a `granger-classics` preset, "Granger Classics: all books"; the Starter preset now has 351 items. The old `classics-2026-10-16` ZIM release and its descriptor are left in place, unreferenced, so older builds' cached catalogs still resolve.
- Library database on the Pixel 6 with 5 books indexed: 3,876 KB (library.db 2,972 KB + wal 406 KB + shm 32 KB).
- **Not measured:** "Get all" (347 downloads, 95 MB) time over Wi-Fi and the index size afterwards. The automated tap on "Get all" was blocked by the session's permission check; run it by hand and record the numbers here.

Pixel 6 walkthrough (2026-10-17), after the fixes below: the upgrade kept packs, tabs, History and saved articles; cover grid; Night links in the flame accent (no blue); tap zones 20/60/20, a link in the middle band follows the link; slider label; contents, bookmarks, highlights, notes, colours legible in Day/Sepia/Night; selection menu (Copy · Highlight · Note · Define · Search) closes after each action and works after rotation; in-book search jumps and marks; Search "quality of mercy" → *The Merchant of Venice* under "In books", opens at the passage and marks it; rotation and process death keep the page; imports of TXT (Windows-1252 and UTF-16), Markdown (script shown as text, `javascript:` link inert) succeed, and a PDF renamed `.epub` and a DRM EPUB are refused with their messages; removing a book shows "Removed book" on the rail and in History.

Fixed after the walkthrough (merged `f8082c9`): a crash on the first launch after process death with a book open; the Contents panel's tabs under the status bar in portrait; reopening a book one page early; a search hit landing a page late and unmarked; the rail clipped in landscape (a Plan 5 bug: the cutout inset was taken out of the rail's width); the status bar left showing after switching readers through the rail.

Still open:
- Define with no Wiktionary pack hands the word to another app (`PROCESS_TEXT`); not exercised on the device. Define through a Wiktionary pack is untested on a device too.
- Classics pushed by hand into `books/granger/` show "Get", not installed: installed status comes from download records only.
- A page turn within ~0.3 s before Back is lost (the outgoing reader's reports are ignored once it leaves the screen).
- System Back from a book opened through the rail goes to the Library, not the previous book (rail destinations replace each other).
- Contents panel tab labels are cut off in landscape.
- Imported text books are titled by file name, not by their first heading.
- Book search results are unranked (`LIMIT 400`); TXT files with single-newline paragraphs become one paragraph per chapter; the shared cover cache (4 MB) re-decodes while scrolling a long grid.
- Plan 10: import is Pro-only; queueing the whole Classics preset must be checked against the cap as one group (spec §13); `Features.IMPORT` becomes the entitlement check.
- ~~Plan 7: archive.org books go to `books/archive-org/` with origin `ARCHIVE_ORG`; `Bookshelf.addDownloaded` needs an origin parameter then.~~ Done in Plan 7.
- Plan 8: `BookIndex` and `BookHit` are the e-book retrieval path for Inquire & Abstract; the `INQUIRE` selection action is already wired.
- The search-grouping call from Plan 5 is still open.
- The four Classics presentation defects from Plan 4 are fixed in the EPUB build (translator in the Koran titles, publisher CSS dropped, page numbers not clipped, clean titles).

## Carried forward from Plan 7

Neal's calls (2026-10-17):
- Items with no EPUB download their text: an uploaded `.txt` first, else archive.org's OCR text (`*_djvu.txt`), labelled "OCR text: may have errors" and converted to EPUB beside it. About 470k public-domain texts have an EPUB; about 9 million have only scans plus OCR text.
- Browse is four fixed shelves: Most downloaded, Technical manuals (`collection:manuals`), Science & engineering, Literature.
- **The public-domain filter is date only (published 1930 or earlier).** The spec's "or marked public domain / CC0" was dropped after the Pixel 6 walkthrough: uploader licence tags put Diary of a Wimpy Kid (2007), a modern surgery textbook and a 2009 solutions manual in the filtered view. Spec §5 and §14 are updated.
- Later idea, not planned: data sources per library category, with archive.org under several (texts, audio, video).

Builder's calls made while building:
- Items archive.org restricts to lending are lend-only ("Borrow on archive.org") even if a file is served openly (spec §5 wording).
- Each archive.org book keeps a small `<identifier>.json` with its archive.org metadata in the internal `books/archive-org/` until it's removed, so a Readiness repair keeps the title and details.
- Removing a book made from text also deletes its `.txt`; text over 20 MB isn't offered (it can't be converted).
- Every request to archive.org hosts, file downloads included, sends `User-Agent: Granger/<version> (+https://github.com/nealt9000/Granger)`.
- The download ledger is Room version 2: `sha256` is renamed `hash`, and `hashAlgorithm` (SHA256/SHA1/MD5) is added; old records migrate as SHA256.

Pixel 6 walkthrough (2026-10-17), all passed after the fixes below:
- The upgrade kept packs, books, rail and resume; a migrated pack record re-verified in Readiness.
- An OCR-text book (History of the Frick Company, 81 KB) and a 62 MB scan EPUB (Steam-engine principles and practice) downloaded, verified, filed under Literature → Archive.org with archive.org's titles, opened, and the OCR text is found by Search under "In books".
- A lending item shows only Borrow, which opens Chrome; the waiver dialog, the unfiltered rerun and withdrawing it in Settings (files stay) work; offline shows "Couldn't reach archive.org" with Retry and the Installed tab still lists downloads.
- Remove deletes the `.txt`, `.epub` and `.json` and the rail shows the book as not available; the item sheet survives rotation.
- Killing the process at 6.8 of 9 MB: WorkManager restarted it after its backoff (about a minute), it resumed from the `.part` file, verified with sha1, and filed the book.

Fixed after the walkthrough:
- The filter change above.
- Search fields (Archive.org, Granger/Kiwix sources, library Search) scrambled and dropped fast typing: their text came back through the view model's combined flow a frame late. They now read Compose state set on each keystroke.
- The Installed tab called OCR downloads "Plain text"; it reads the kind from the metadata file.
- In scan EPUBs a page-wide image inside an indented paragraph ran off the page, and Readium's edge tap couldn't leave that page. Every book page gets one rule, `p:has(img) { text-indent: 0 !important }`; books' own image sizes are left alone.

Still open:
- Readium's edge tap can still be trapped by other content that overflows by less than a page (a wide table, `<pre>`); swiping works.
- Image width relies on ReadiumCSS's non-important `max-width: 100%`; a book that sets `max-width: none` on images can overflow.
- OCR text is plain: page headers and hyphenation from the scan stay inline. PDF-only items wait for the PDF pass. No thumbnails in results.
- Archive.org items have no versions, so there is no Updates tab; a file archive.org re-derives later is not offered as an update.
- Plan 11: the Play policy check for this source.
- Minor polish deferred from the reviews: the filtered view's Get re-check and query both use `year`, which some items lack (they need the waiver); the archive tests wait in real time on the IO dispatcher; silent failure when no browser is installed; "PDF: coming soon" sits below the buttons rather than beside Get.

