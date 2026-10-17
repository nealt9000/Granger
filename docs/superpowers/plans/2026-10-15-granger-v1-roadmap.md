# Granger v1 — Plan Roadmap

Spec: `docs/superpowers/specs/2026-10-15-granger-v1-design.md`

The spec spans nine milestones across independent subsystems. Each milestone gets its own implementation plan, written when the previous one has landed, so every plan builds on real code and measured numbers instead of guesses. Each plan ends with working, tested software.

| # | Plan | Delivers | Status |
| --- | --- | --- | --- |
| 1 | Foundation + model spike | Toolchain, Gradle project, `:app` + `:core:ai`, `DeviceTier`, `LlmEngine` over LiteRT-LM, debug spike screen, measured Pixel 6 results and decisions | **Written:** `2026-10-15-plan-01-foundation-model-spike.md` |
| 2 | Reader | `:core:zim` (java-libkiwix `org.kiwix:libkiwix`), test ZIM, `:core:library` (Room), Library screen, WebView reader, title + full-text search, bookmarks, reading position | **Done:** `2026-10-15-plan-02-reader.md` |
| 3 | Catalog + downloads + Readiness | `tools/catalog-gen`, Ed25519-signed catalog, `:core:catalog`, `:core:downloads` (WorkManager, resume, sha256, volumes), Downloads + Readiness screens | **Done:** `2026-10-16-plan-03-catalog-downloads-readiness.md` |
| 4 | Pack builder + Granger Classics | `tools/pack-builder` (python-libzim), Classics manifest, Classics pack on a GitHub release, catalog entry | **Done:** `2026-10-16-plan-04-pack-builder-classics.md` |
| 5 | Shell + Library | Flame theme + icon, side rail (bookmarks per article, History, Recent), immersive mode, open-to-last-place, media types / collections / filing, Library screens, Granger + Kiwix source screens, Settings (Models, Readiness), polished web-style reader with selection actions, schema migration | **Done:** `docs/superpowers/plans/2026-10-16-plan-05-shell-library.md` |
| 6 | E-book reader + Classics EPUBs | Readium reader (pagination, text settings, highlights, notes, dictionary, in-book search), `:core:imports` (EPUB/TXT/MD, DRM detection, FTS5), My books, pack-builder EPUB output, Classics re-release, Search over e-books | |
| 7 | Archive.org source | Public-domain filter until waiver, search/browse, open-only downloads with sha1/md5 verify, filing, metadata | |
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

## Carried forward from Plan 5

- Rail letter tiles become covers in Plan 6.
- The Inquire & Abstract slot in the rail is switched on in Plan 8.
- The Classics ZIM stays filed as "Granger Classics" until Plan 6 replaces it with e-books.
- The three `ReaderWebViewTest` device tests are written but unrun, pending the owner's go-ahead: `connectedDebugAndroidTest` uninstalls the app and wipes downloaded packs and models.
- Pixel 6 walkthrough items not run (the phone rebooted into a secure lock screen that adb cannot pass): the reboot-lands-on-Library check, moving Wikipedia to a new "Reference" media type, the Granger/Kiwix/Models source screens and the small-pack download and remove, and the About screen. Run these by hand. The unit tests cover the logic; the launcher icon was seen in Recents showing the flame mark.
- The rail's edge swipe works only inside a 24 dp wide, 200 dp tall strip at the left-middle (Android's gesture-navigation exclusion limit), and only while the chrome is visible. Judge its discoverability on a device; tapping the narrow rail's background is the other way to expand it.
- On Kiwix main pages every tile is a link, and the reader's middle tap ignores links, so a tap on a tile navigates instead of summoning the chrome. By reading the code, the gaps between tiles still toggle it, but nothing else does (Back only goes back a page). Check on a device whether the rail can always be reached; if not, give the reader another summon gesture.
- `PublishedCatalogTest` expected a four-item Starter preset; it has had five since the Classics entry was added (Plan 4). The assertion is now five.

### Design pivot raised after Plan 4 (designed: see the library redesign spec)

Neal's direction, 2026-10-16, to brainstorm before the next plan: the library will hold more media types from more sources (Kiwix, Granger Classics, archive.org with a legal disclaimer, the user's own collections). Instead of everything landing flat in the Library, content is grouped into collections: Wikipedia packs nest in a Wikipedia area, and Granger Classics becomes a collection whose works are individual e-books rather than one ZIM read as a website. All e-books, whether Classics, archive.org or the user's own, open in one shared e-book reader. Finished-app picture: opening the app shows where you left off in the book you were reading; a vertical side toolbar has a section at the top with bookmarks to recently opened books and media. This likely reshapes Plan 5 (Import) and the Classics pack format, so the four Classics defects above wait for it.

Deferred, low priority (from Plan 2):
- Opening the reader after the OS killed the app shows the article it was first opened on, not the last page.
- Large media entries are read whole into memory.
- No device test yet for a ZIM truncated or ejected (SD card) while it is open.
