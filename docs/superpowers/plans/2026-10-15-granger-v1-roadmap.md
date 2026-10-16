# Granger v1 — Plan Roadmap

Spec: `docs/superpowers/specs/2026-10-15-granger-v1-design.md`

The spec spans nine milestones across independent subsystems. Each milestone gets its own implementation plan, written when the previous one has landed, so every plan builds on real code and measured numbers instead of guesses. Each plan ends with working, tested software.

| # | Plan | Delivers | Status |
| --- | --- | --- | --- |
| 1 | Foundation + model spike | Toolchain, Gradle project, `:app` + `:core:ai`, `DeviceTier`, `LlmEngine` over LiteRT-LM, debug spike screen, measured Pixel 6 results and decisions | **Written:** `2026-10-15-plan-01-foundation-model-spike.md` |
| 2 | Reader | `:core:zim` (java-libkiwix `org.kiwix:libkiwix`), test ZIM, `:core:library` (Room), Library screen, WebView reader, title + full-text search, bookmarks, reading position | **Done:** `2026-10-15-plan-02-reader.md` |
| 3 | Catalog + downloads + Readiness | `tools/catalog-gen`, Ed25519-signed catalog, `:core:catalog`, `:core:downloads` (WorkManager, resume, sha256, volumes), Downloads + Readiness screens | **Done:** `2026-10-16-plan-03-catalog-downloads-readiness.md` |
| 4 | Pack builder + Granger Classics | `tools/pack-builder` (python-libzim), Classics manifest, Classics pack on a GitHub release, catalog entry | **Done:** `2026-10-16-plan-04-pack-builder-classics.md` |
| 5 | Import | `:core:imports` (EPUB/PDF/TXT/MD, DRM detection, FTS5), `:core:search` (`SearchSource`), My books, EPUB/PDF/TXT readers | |
| 6 | Ask pipeline | `:core:ask-logic`, rewrite → find → chunk → rank → answer/decline, citation validation, medical banner, Think deeper, Ask screen | |
| 7 | Eval harness | `tools/eval`, ~300-question dataset, metrics, threshold/prompt tuning to the pass bar | |
| 8 | Monetization + onboarding + telemetry | `:core:billing` (Play + F-Droid flavors), cap + import gating, onboarding, `:core:telemetry` | |
| 9 | Beta | Release config, signing, Play listing, internal track | |

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
- Measured: the first Classics build is 84.2 MB, 347 works, 13,816 entries, under the 300–600 MB target because it's text-only. There's room to add works.
- ZIM full-text search has no phrase search (libzim indexes without positions): a famous line like "the quality of mercy" finds its play, but not first; a distinctive word (`Portia mercy`) does.

Deferred, low priority (from Plan 2):
- Opening the reader after the OS killed the app shows the article it was first opened on, not the last page.
- Large media entries are read whole into memory.
- No device test yet for a ZIM truncated or ejected (SD card) while it is open.
