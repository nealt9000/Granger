# Granger v1 — Plan Roadmap

Spec: `docs/superpowers/specs/2026-10-15-granger-v1-design.md`

The spec spans nine milestones across independent subsystems. Each milestone gets its own implementation plan, written when the previous one has landed, so every plan builds on real code and measured numbers instead of guesses. Each plan ends with working, tested software.

| # | Plan | Delivers | Status |
| --- | --- | --- | --- |
| 1 | Foundation + model spike | Toolchain, Gradle project, `:app` + `:core:ai`, `DeviceTier`, `LlmEngine` over LiteRT-LM, debug spike screen, measured Pixel 6 results and decisions | **Written:** `2026-10-15-plan-01-foundation-model-spike.md` |
| 2 | Reader | `:core:zim` (java-libkiwix `org.kiwix:libkiwix`), test ZIM, `:core:library` (Room), Library screen, WebView reader, title + full-text search, bookmarks, reading position | **Done:** `2026-10-15-plan-02-reader.md` |
| 3 | Catalog + downloads + Readiness | `tools/catalog-gen`, Ed25519-signed catalog, `:core:catalog`, `:core:downloads` (WorkManager, resume, sha256, volumes), Downloads + Readiness screens | Next |
| 4 | Pack builder + Granger Classics | `tools/pack-builder` (python-libzim), Classics manifest, OpenStax physics pack, catalog entries | |
| 5 | Import | `:core:imports` (EPUB/PDF/TXT/MD, DRM detection, FTS5), `:core:search` (`SearchSource`), My books, EPUB/PDF/TXT readers | |
| 6 | Ask pipeline | `:core:ask-logic`, rewrite → find → chunk → rank → answer/decline, citation validation, medical banner, Think deeper, Ask screen | |
| 7 | Eval harness | `tools/eval`, ~300-question dataset, metrics, threshold/prompt tuning to the pass bar | |
| 8 | Monetization + onboarding + telemetry | `:core:billing` (Play + F-Droid flavors), cap + import gating, onboarding, `:core:telemetry` | |
| 9 | Beta | Release config, signing, Play listing, internal track | |

Plan 1's measurements feed directly into Plans 6–8 (backend choice, model files in the catalog, the latency target, embedder co-residency).

## Carried forward from Plan 2's review

For Plan 3, which introduces pack updates and internet access:
- **Pack identity across updates.** Packs are keyed by ZIM UUID, which changes with every snapshot. When the downloader replaces a snapshot, re-key `bookmarks` and `reading_positions` to the new pack (match on the ZIM `Name` metadata), or users lose them.
- **Reader network block.** The reader makes no requests today only because the app lacks `INTERNET`. Before adding it, set `blockNetworkLoads = true` and disable Safe Browsing on `ReaderWebView`.

Deferred, low priority:
- Opening the reader after the OS killed the app shows the article it was first opened on, not the last page.
- Large media entries are read whole into memory.
- No device test yet for a ZIM truncated or ejected (SD card) while it is open.
