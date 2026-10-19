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
| 8 | Research (was Inquire & Abstract) | Old Ask pipeline (rewrite → find → chunk → rank → answer/decline, citation validation, medical banner, Think deeper) plus metadata grounding, cross-source connections, selection entry, kept chats with threaded follow-ups | **Done:** `2026-10-17-plan-08-research.md` |
| 9 | Eval harness | `tools/eval`, ~300-question dataset, metrics, threshold/prompt tuning to the pass bar | **Done, bar not reached:** `2026-10-18-plan-09-research-eval.md` (Research stays debug-only; see below) |
| 10 | Monetization + onboarding + telemetry | `:core:billing` (Play + F-Droid flavors), cap (all sources; non-commercial packs exempt) + import gating, onboarding, `:core:telemetry` | |
| 11 | Beta | Release config, signing, Play listing (incl. the Play policy check for the archive.org source and non-commercial packs), internal track | |
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
- ~~The Inquire & Abstract slot in the rail is switched on in Plan 8.~~ Done in Plan 8, as Research.
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
- ~~Plan 8: `BookIndex` and `BookHit` are the e-book retrieval path for Inquire & Abstract; the `INQUIRE` selection action is already wired.~~ Done in Plan 8 (`BookIndex.passages`; the action is now `RESEARCH`).
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

## Carried forward from Plan 8

Neal's calls (2026-10-17):
- **Inquire & Abstract is renamed Research** everywhere in the UI (rail tool, both selection menus, screen title, Readiness); in code `INQUIRE` became `RESEARCH`. Spec §9 is updated.
- Chats are threads with follow-ups: each follow-up searches again with the new question plus the previous one, and the model sees the previous question and a short form of its answer.
- Research from a selection opens a new chat with the selection quoted as source [1]; sending empty asks "Explain this passage and how it connects to the rest of my library."

Builder's calls made while building:
- The Research tool shows at the ASK tier and up even before the models are installed; the screen points to Settings → Models.
- Models are released 2 minutes after leaving Research, or at once when the app goes to the background.
- Book and pack catalogue cards are passages, so metadata questions can be cited; at most 3 passages per source reach ranking.
- **Rerank is off** (`RERANK_WITH_EMBEDDER = false`, `EMBEDDER_BACKEND = GPU` for when it comes back): the rule was "on only if a query plus 12 passages embeds in ≤ 3,000 ms on the faster backend".
- A rewrite keyword phrase ("portia mercy") matches its words in any order, in book chunks, ZIM full text (one search per keyword) and catalogue cards. Before this, phrases had to occur verbatim and most questions found nothing.
- **Lexical scoring (ruled 2026-10-17, Task 9 fix round 1; replaced in Plan 9, see below):** each keyword gets partial credit, the share of its words present as whole words; stopwords don't count. Over the passages a search found, words are weighted by a smoothed idf (`ln((N+1)/(df+1)) + 1`) and each keyword by the weight of its words, so keywords about the question's form ("literature analysis") count for little. The title bonus stays. `lexicalThreshold` is **0.32**. Partial credit alone left the two sets overlapping (Wi-Fi 0.50, Portia 0.50).
- When the rewrite is cut off before its `medical:` line, the medical lexicon decides. A reply containing `NOT IN LIBRARY` anywhere is a decline.

Pixel 6 measurements (2026-10-17; `ResearchBenchmarkActivity`, debug only, `adb shell am start -n app.granger/.research.ResearchBenchmarkActivity`, logcat tag `Granger`, lines `Research:`):
- Embedder, "Who wrote Hamlet?" plus 12 real passages: GPU 4,218 / 4,375 / 4,667 ms (load 2.2 s warm, 6.9 s cold); CPU 9,663 / 9,575 / 10,222 ms (load 1.1 s warm, 3.5 s cold). With 6 passages: GPU 1,959–1,973 ms, CPU 4,014–4,210 ms.
- Send to first answer words, E2B already loaded (7.7–8.1 s to load), lexical ranking: "Who wrote Hamlet?" **6,782 ms, inside the 9.0 s target** (rewrite 2.2 s, search 1.4 s, ranking under 0.1 s, then 3.2 s to the first words). Declines in code take 3.5–4.1 s; declines by the model about 4.5–4.7 s.
- Top cosine of the passages each question found (embedder on): answerable "Who wrote Hamlet?" 0.774, "What does Portia say about mercy?" 0.830, "What is the boiling point of water?" 0.635; unanswerable "routing number" 0.564, "2031 World Cup" 0.637, "library Wi-Fi password" 0.666. **They overlap**, so `cosineThreshold` stays 0.40. Leaving out the boiling-point question, which the phone's library can't really answer (the model declined it), the gap would be 0.666–0.774.
- Top lexical scores. Plan 8's first scoring (whole keywords only): Hamlet 0.50, boiling point 0.50, Portia 0.20, Ray Charles's life 0.40, Ray banned in Georgia 0.17, Hamlet follow-up 0.00; routing number 0.17, World Cup 0.17, Wi-Fi 0.50. Partial credit: 0.75 / 0.50 / 0.50 / 0.78 / 0.33 / 0.50; 0.37 / 0.28 / 0.50. **Final, idf-weighted:** 0.64 / 0.59 / 0.46 / 0.74 / 0.35 / 0.55; 0.27 / 0.28 / 0.30. With the threshold at 0.32 every answerable case reaches the model and the three unanswerable ones decline in ranking. The margin is thin (0.30 against 0.35) and comes from one phone's library.
- The phone has no real Wikipedia pack, only the 3 MB Ray Charles test ZIM and the retired Classics ZIM (still searched, so Classics texts can come back twice: as ZIM articles and as e-book chunks).

Pixel 6 walkthrough (2026-10-17): the Research tool and empty screen; "Who wrote Hamlet?" answered with a [n] chip that outlines its card, and cards open the book or article at the passage; a follow-up carries the previous question into its keywords; an unanswerable question says "Not in your library." and Search instead opens Search with the keywords; the medical banner (after the fix above); Stop keeps what was written; Think deeper shows "Loading the model…" and E4B replies (it quoted the passage and then declined, which led to the decline fix below); Research from a book selection and from an article selection, sent empty, explain the quote; chats are in Recent and reopen intact after a force-stop; killing the process mid-answer leaves "Stopped before an answer." and Retry works; rotating while streaming keeps the turn; a removed book's citation says "That source is no longer in your library." (Hamlet was removed and got again).

Fixed after the walkthrough: the phrase matching, lexical scoring, medical and decline fixes above; preselect scored every passage inside its sort comparator, compiling regexes on each comparison (5–17 s of ranking with about 200 candidates; now under 0.1 s); the last scroll after a turn ran before the stored answer and its cards arrived, leaving the list above the new answer.

For Plan 9 (all done in Plan 9 except the SD-card measurement, which is carried forward below):
- Lexical ranking reaches the model on the right questions but sometimes with off-topic passages: "Tell me about the life of Ray Charles" was answered from the Hank Crawford article's references, and the Hamlet follow-up from a Ben Jonson introduction. "Why was Ray banned from playing in Georgia?" got a misread rewrite (`ray turner, georgia baseball, ban reason…`) and the model declined it. Rewrite quality and passage choice are for the eval.
- Tune `lexicalThreshold` (0.32) and `cosineThreshold` (0.40) on the eval set; the rerank decision above; the 9 s target is met only with lexical ranking (6.4–7 s to the first words for "Who wrote Hamlet?" after this change, once the benchmark's own recording time is taken off).
- Models are still `evalPassed: false`.
- "Search instead" passes every rewrite keyword joined, which the library's Search rarely matches ("world cup 2031 football soccer…" found nothing).
- With rerank off, EmbeddingGemma (165 MB) is still listed in Settings → Models but unused; hide it or say so.
- Answers can carry Markdown (`*Hamlet*`), shown as typed.
- The last scroll once stopped short of a finished turn's source cards (after Retry).
- Any failure reads "The model needs repair". Tell search and storage (IO) failures apart from the model's.
- Enforce the prompt budget at runtime; chars/4 undercounts tokens in non-English text.
- The rail's Research tool does nothing while a kept chat is open (`launchSingleTop` keeps it); only **+** starts a new chat, unlike `openResearch`.
- Gate `Features.RESEARCH`, or flip `evalPassed`, before any release build.
- Measure the per-keyword ZIM search cost on a full Wikipedia on the SD card before the beta.
- Add a JVM test for ranking with `corpus` = all the passages found.
- Only one model use runs at a time, and the wait for it counts toward the rewrite's 5 s timeout, so a question asked while another chat answers can fall back to plain keywords. Start the timeout once the model is free.
- A background release requested mid-turn still runs at the turn's end even if the app is back in front, so the next question reloads the model.
- A medical turn that is stopped or fails before its first words stores the question's medical hint rather than the pipeline's flag, so its banner can vanish once settled.
- `deleteChat` and the Room `turns`/`recent` flows behind `stateIn` still have no error guard; a storage failure there can crash the app.
- No JVM tests for the citation tab-lookup catch or the card-matching dispatcher move.
- Minor polish deferred from the reviews: no `--release 17` on `:core:research`; `HtmlPassages` doesn't split on no-break spaces; `Rewrite.parse` doesn't cap at 6 keywords or reject an echoed placeholder; `Keywords` drops apostrophes (O'Brien → obrien) that passage text keeps; the question is capped at 1,000 characters, but the prompt header isn't length-capped; `Ranking.cosine` doesn't check vector sizes; `Citations.strip` eats newlines before a group; `ModelManager` closes the resident model before checking the requested file exists and doesn't enforce the tier; a large-model reload failure mid-answer fails the turn instead of falling back; `BookIndex.passages` takes its rows without `ORDER BY` and one damaged book drops all book passages; a citation to a vanished reading tab does nothing; `ZimUrls.forEntry` doesn't encode the fragment.

## Carried forward from Plan 9

Results (Pixel 6, Neal's library, graded against `tools/eval/RUBRIC.md`; full notes in `tools/eval/runs/checkpoints/`):

| Metric | Baseline | Tuned (tuning split) | Held-out | Bar |
| --- | --- | --- | --- | --- |
| Unanswerable declined | 89% | 91% | **92%** | ≥ 90% ✅ |
| Answerable grounded | 36% | 45% | **37%** | ≥ 80% ❌ |
| Medical flag recall | 57% | 98% | 95% | 95% |
| Think deeper grounded (multi-step) | — | 3/12 | 0/7 | ≥ 80% ❌ |
| Best guess confidently wrong, E2B / E4B | 14% / 11% | — | 20% / 13% | ≤ 10% ❌ |
| Send to first words, median | 21.8 s | 14.0 s | — | 9 s ❌ |

Decisions (the plan's stop path):
- `evalPassed` stays false for both models; the catalog was not re-signed.
- `Features.RESEARCH = BuildConfig.DEBUG`: Research is in debug builds only.
- `BEST_GUESS_USES_LARGE = true`: neither model meets the good-enough bar, and E4B is closer.
- Rerank stays off (`RERANK_WITH_EMBEDDER = false`); Settings → Models labels EmbeddingGemma "Not used yet".

What Plan 9 changed in Research:
- Greedy decoding (`topK = 1`): runs are reproducible, and the rewrite takes about 2.7 s instead of 9.9 s.
- Ranking: every keyword word weighs 1 (the idf over retrieved passages was removed), an article's opening passage gets `LEAD_BONUS` 0.3 when it matches, the cap is 3 passages per article or chapter (`perDocument`), and `lexicalThreshold` is 0.6. A book's title is only the `TITLE_BONUS` 0.1.
- Book search is BM25 over FTS4 `matchinfo`, at most 4 passages per book; a question that names a book also searches inside it.
- Personal medical questions decline before the search; the medical lexicon matches at word starts.
- The answer prompt answers first, may combine facts, and declines on passages about a different person, year, place or event; prose declines ("the passages do not say…") count as declines.
- Bibliography sections give no passages; the rewrite drops source meta-words (definition, scripture, quote…).
- Startup bug fixed: Research right after launch could say the model won't load, because the installed-models list was still empty.

Why it stops short, and the options for the next planning session (Neal, 2026-10-19: brought there together with how the models become expert on each user's own library, likely trying more model variants from Hugging Face):
- Keyword search can't bridge vocabulary gaps (alcohol ↔ wine, taxes ↔ tribute); options: EmbeddingGemma rerank (test offline first on the recorded `found.jsonl` corpora) and source-vocabulary expansion for named works.
- E2B rarely combines facts across passages; options: E4B as the answerer (unmeasured), other models, fine-tuning.
- Or re-scope the bar to "the answer passage is shown".

Still open:
- Measure the per-keyword ZIM search cost on a full Wikipedia on an SD card before the beta.
- iFixit (`ifixit_en_all`) has no full-text index, so Research can't search it.
- A personal medical decline shows "Not in your library."; it should say to ask a professional.
- Latency is 14 s to the first words against the 9 s target. With both Best guess models in use the next question reloads E2B (21.6 s).
- Plan 10: packs marked non-commercial (spec §10a) never count toward the Free cap.
- Plan 11: the Play policy check covers non-commercial packs as well as the archive.org source.
- Minor findings from the final review (Research ones are debug-only):
  - `Citations` treats a first sentence like "The ruins were not found until 1911 [1]." as a decline; skip the decline phrases when the sentence carries a citation.
  - The wider medical lexicon flags "Doctor Zhivago", "computer virus" and "Tropic of Cancer", which then get the banner and no Best guess.
  - `AnswerText` italicises the middle of "2*3*4" and drops the asterisks.
  - iFixit's catalog description is Kiwix's summary cut off at "Let's …", now visible in the Kiwix listing; give it a curated description.
- The eval needs the phone: `tools/eval` runs over adb (Wi-Fi works), about 70 s a question. The held-out split stays unread by whoever tunes.

