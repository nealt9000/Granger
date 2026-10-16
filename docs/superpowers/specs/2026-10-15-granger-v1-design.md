# Granger v1 — Design Spec

Date: 2026-10-15 · Owner: Neal Thompson · Status: approved design, pending spec review

Supersedes the v1 scope in `Offline Knowledge App — Android Product Design.md` where they differ. That doc remains the source for market, positioning, and the long-range hardware vision.

## 1. Brief

**Granger** ("Civilization, backed up.") is an Android app that turns a phone into an offline library with a local AI librarian that always cites its source.

Governing rule: **knowledge lives in files, not in model weights.** The model is the interface, never the source of truth.

### v1 scope

| In v1 | Deferred to v2+ |
| --- | --- |
| Library: browse/search installed ZIM packs, bookmarks, reading position | Emergency cards |
| Downloads: catalog of packs and models, storage meter, presets | Read-aloud, voice input/output, all speech |
| Ask: cited answer or "not in your library"; Think deeper | LLM-curated knowledge packs |
| Readiness: pack/model integrity, free storage, snapshot dates, device tier | Wi-Fi Direct / hotspot sharing |
| Free/Pro (storage cap + import), opt-in telemetry | Battery estimates, Ask history, languages beyond English |
| **Granger Classics** curated pack (religion, philosophy, plays, libretti, novels) in Starter | Fine-tuned models (contingency only), preloaded SD kits, hardware |
| **Import your own books** (Pro): DRM-free EPUB, PDF, TXT/MD | OCR for scanned PDFs; ZIM sideloading |
| | Fahrenheit 451 easter egg (on hold) |

### Decisions

| Area | Decision |
| --- | --- |
| Platform | Kotlin, Jetpack Compose, Android 10+ |
| Models | Gemma 4 **E2B** = small (default answerer); Gemma 4 **E4B** = large ("Think deeper", user-triggered). Stock models, no fine-tune unless eval fails. |
| Runtime | **LiteRT-LM only.** One model resident at a time. |
| Embedder | EmbeddingGemma (~300M) on LiteRT, may co-reside with E2B |
| Device tiers | < 6 GB RAM: library only · 6 GB: + Ask (E2B) · 8 GB+: + Think deeper (E4B) |
| Retrieval | LLM query rewrite → ZIM built-in full-text index → passage chunking → embedding rerank → threshold or decline |
| Medical | Detect medical questions → fixed safety banner → answer stays close to quoted source |
| Monetization | Free/Pro, one-time Play IAP, cheap. Pro = **unlimited library** (Free caps installed packs at 10 GB; models excluded; all volumes incl. SD count) **+ import your own books**. |
| Content | Public domain or commercially redistributable licenses only. US public-domain line: published 1930 or earlier (as of 2026). |
| License | GPLv3, public repo from day one, libzim/libkiwix via Kiwix's published Android bindings |
| Channels | Google Play (paid Pro) and F-Droid/direct APK (**uncapped**, "support development" link) |
| Hosting | We host only a signed catalog JSON; packs from Kiwix mirrors, models from Hugging Face |
| Telemetry | Opt-in (off by default), minimal, user-inspectable queue, never queries or content |
| Language | English only; strings i18n-ready |
| Team | Neal + Claude Code; test device Pixel 6 (8 GB, Tensor G1) |

### Success criteria for v1

- Play internal beta shipped.
- On the Pixel 6, E2B first token in ≈3 s or less after retrieval (validated in milestone 1; the target adjusts if the spike shows otherwise, the architecture does not).
- Every Ask response cites at least one passage or declines.
- Eval: ≥ 90% correct declines on unanswerable questions; ≥ 80% grounded answers on answerable ones.

## 2. Architecture

### Modules

| Module | Responsibility | Depends on |
| --- | --- | --- |
| `:app` | Compose UI + navigation: Library, Ask, Downloads, Readiness, Onboarding | all core modules |
| `:core:zim` | Open ZIM, read entries, full-text search, title suggest. Thin Kotlin facade over Kiwix's `java-libkiwix` Maven artifact (no hand-written JNI). | libkiwix |
| `:core:library` | Installed-pack registry, bookmarks, reading position (Room) | `:core:zim` |
| `:core:catalog` | Fetch, verify (Ed25519), cache, and expose packs/models | — |
| `:core:downloads` | WorkManager resumable downloads, streaming sha256, volume selection, cap enforcement | catalog, billing |
| `:core:ai` | `ModelManager`, `Embedder`, `AskPipeline` | LiteRT-LM, `:core:zim` |
| `:core:ask-logic` | Pure-Kotlin pipeline logic (prompts, chunking, threshold, citation validation) shared by the app and the eval harness | — |
| `:core:imports` | Import DRM-free EPUB/PDF/TXT/MD via the system file picker; copy into app storage; extract text; chunk; index in SQLite FTS5 | PdfBox-Android (Apache-2.0), billing |
| `:core:search` | `SearchSource` interface with two implementations: ZIM full-text (`:core:zim`) and imports (FTS5). Ask and Library search query all sources through it. | `:core:zim`, `:core:imports` |
| `:core:billing` | Play Billing; exposes `isPro` / `capBytes` / `canImport`. F-Droid flavor: always uncapped and import enabled, no Play dependency. | Play Billing (play flavor only) |
| `:core:telemetry` | Opt-in event queue, preview screen, upload when online | — |
| `tools/catalog-gen` | Script: pull Kiwix catalog, filter to curated list, pin hashes, sign | — |
| `tools/pack-builder` | Build our own ZIMs (python-libzim, full-text index enabled) from a manifest of source texts; strips Project Gutenberg branding/headers. Builds Granger Classics; groundwork for v2 curated packs. | python-libzim |
| `tools/eval` | Desktop eval harness + dataset | `:core:ask-logic` |

Each core module exposes a small interface; `:app` depends on interfaces, enabling fakes in UI tests.

### Reading

ZIM articles are HTML. The reader is a WebView whose `shouldInterceptRequest` serves every entry (HTML, images, CSS) directly from the ZIM — the same approach as Kiwix Android. Reading position and bookmarks persist per pack + entry path.

Imported EPUBs reuse the same WebView: an EPUB is a zip of XHTML, served by the same interception mechanism with a minimal OPF/spine parser for chapter order. PDFs display through Android's `PdfRenderer`; TXT/MD render as simple HTML.

### Ask pipeline

1. **Rewrite** — E2B produces structured output: `{ searchTerms: [...], medical: bool }`. The rewrite is kept even though it costs roughly 1–2 s, because ZIM search is keyword-only.
2. **Find** — query every `SearchSource`: the full-text index of each installed pack with `hasFulltextIndex`, plus imported books (FTS5, which already stores passages); take the top ~8 documents overall.
3. **Chunk** — extract article text; split into ~200-word passages along headings/paragraphs, keeping article title + section heading as passage metadata.
4. **Rank** — EmbeddingGemma scores passages against the original question; keep the top ~4 above a similarity threshold. **If none pass, decline in code** ("Not in your library") — the LLM is not called.
5. **Answer** — E2B answers from numbered passages, required to cite `[n]`. Post-validate: every citation must reference a supplied passage, and at least one must exist. On failure, show the passages with "Couldn't answer from these" instead of model text.
6. **Medical** — if flagged, the UI prepends a fixed banner ("Not medical advice. If you can reach emergency services or a professional, do so.") and the prompt instructs close quoting.

The answer view always shows cited passages; tapping one opens the article at that section.

**Think deeper** reuses retrieved passages (expanded to ~8), unloads E2B, loads E4B, regenerates. E4B stays resident until the next normal Ask swaps back. A loading indicator shows progress and an estimated wait.

### ModelManager

- Determines the device tier once from total RAM (`ActivityManager.MemoryInfo.totalMem`) and persists it; shown in Readiness.
- Guarantees at most one LLM resident; the embedder may co-reside with E2B (to be confirmed by the milestone-1 memory measurements; if not, it loads per query).
- Hides Ask / Think deeper on devices below the tier or when models aren't installed.

## 3. Content, catalog, downloads, storage, billing

### Content rules

- Ship only public-domain or commercially redistributable content. Works published in the US in 1930 or earlier are public domain as of 2026; later works need a license. Non-US public-domain terms differ (open item).
- **Excluded from v1:** *Fahrenheit 451* (copyright Bradbury estate) and the Feynman Lectures online edition (Caltech grants no download or redistribution right). Users who own DRM-free copies of works like these can import them (Pro).
- Physics substitute for Feynman: OpenStax *University Physics* (CC BY 4.0).

### Catalog categories

Medical, Reference, Religion & Philosophy, Literature & Drama, Science, Repair, Regional.

| Pack | Source | Approx. size | Notes |
| --- | --- | --- | --- |
| **Granger Classics** | Built by `tools/pack-builder` | 300–600 MB (target) | In Starter. Hand-picked: King James Bible, a public-domain English Quran translation (Rodwell/Palmer via Gutenberg; Pickthall 1930 if verified), Plato, Aristotle, Marcus Aurelius and core philosophy; complete Shakespeare; public-domain opera libretti; a few hundred essential novels and plays from antiquity to 1930. Gutenberg branding stripped. |
| OpenStax University Physics | Built by `tools/pack-builder` | ~100–300 MB | CC BY; optional |
| Gutenberg: Philosophy & Religion (`lcc-b`) | Kiwix | 5.9 GB | Optional |
| Gutenberg: English lit (`lcc-pr`), American lit (`lcc-ps`), Fiction (`lcc-pz`), Classical (`lcc-pa`), Romance langs (`lcc-pq`), etc. | Kiwix | 0.5–18 GB each | Optional |
| Wikisource English (no pictures) | Kiwix | 3.6 GB | Optional; libretti, scripture, historical texts |
| WikiMed, Wikipedia variants, etc. | Kiwix | per Kiwix | As before |

The Granger Classics manifest (title list + source IDs) lives in the repo and is reviewed before each build.

### Catalog

A single JSON file at a fixed URL with a detached Ed25519 signature; the public key is compiled into the app. Invalid signature or fetch failure → use the last good cached catalog.

Pack entry: `id, title, category, language, sizeBytes, sha256, urls[], snapshotDate, license, hasFulltextIndex, topics[]` (`topics` reserved for v2 curation).
Model entry: `id, role (small|large|embedder), sizeBytes, minRamGb, sha256, url, license, evalPassed`. Release builds list only models with `evalPassed = true`.

Generated by `tools/catalog-gen` from Kiwix's OPDS catalog plus our curated list.

**To verify:** whether the Gemma 4 LiteRT files on Hugging Face require an authenticated license acceptance. If so, host the model files ourselves (Apache-2.0 permits it).

### Downloads

- WorkManager with a foreground notification; HTTP Range resume; Wi-Fi-only by default (user can override).
- Write to `*.part`, compute sha256 while streaming, verify, atomic rename, then register in the library. Unverified files are never registered.
- Volumes from `Context.getExternalFilesDirs()` (internal + SD card, no permissions). The UI notes that uninstalling Granger removes downloaded files.
- Storage meter per volume: free space, Granger usage, queued size.

### Storage cap (Play flavor)

- Free: installed packs + queued pack downloads ≤ 10 GB. Models are excluded.
- Checked when queueing. Over the cap → upsell to Pro.
- Never deletes or disables anything to enforce the cap. A refunded purchase keeps what is installed and blocks only new packs above the cap.

### Import your own books (Pro)

- System file picker (Storage Access Framework); multi-select. Supported: **EPUB, PDF, TXT, MD**, DRM-free only.
- DRM-protected files are detected (EPUB `encryption.xml`, encrypted PDF) and rejected with a clear explanation and pointers to DRM-free sources. The app never attempts to remove DRM.
- Files are copied into app storage, so they survive the source being moved, and are excluded from the pack cap.
- Text is extracted (EPUB XHTML; PDF via PdfBox-Android; plain text), chunked with the same chunker as ZIM articles, and stored in an SQLite FTS5 table with passage → location mapping (chapter or page).
- Scanned image-only PDFs: readable but flagged "not searchable" (no OCR in v1).
- Imports appear in the Library under "My books", are searchable, and are Ask sources with the same citations.
- If Pro is lost (refund), existing imports remain readable and searchable; new imports are blocked.

### Billing

- One non-consumable product, `pro`.
- The entitlement is cached locally after the first verification, so it works offline indefinitely; it's re-checked silently when online.
- F-Droid flavor: `capBytes = ∞`, no Play Billing dependency, "Support development" link.

### Onboarding

1. Welcome screen.
2. Tier detection, shown in plain language.
3. Telemetry opt-in prompt (off by default).
4. Downloads with the **Starter** preset preselected: E2B + EmbeddingGemma + WikiMed + the ~2 GB curated Wikipedia core + Granger Classics (≈ 5.5 GB of packs, under the 10 GB cap). Editable per pack; nothing downloads without confirmation. The **Full** preset requires Pro on Play.

### Readiness

Per pack and model: verified / incomplete / corrupted (size check always; full sha256 re-hash on request, with progress), plus snapshot date. Also free space per volume and the device tier. No battery estimate in v1.

### Telemetry (opt-in)

Collected: device model, RAM, SoC, Android version, tier; model load time, time to first token, tokens/s per model; OOMs and fallbacks; packs installed and their sizes; battery % per Ask.
Never collected: questions, answers, reading content, pack-level reading activity.
Events are queued locally; a screen shows the exact pending payload; upload happens only when online and opted in. Opting out clears the queue.

## 4. Error handling

**The library always works, even if the AI fails.**

| Failure | Behavior |
| --- | --- |
| OOM loading a model | Fall back to E2B with a notice; if E2B fails, disable Ask with an explanation in Readiness; record a telemetry event (if opted in) |
| Model file corrupt or missing | Readiness flags it and offers a re-download; Ask shows "Model needs repair" |
| ZIM file corrupt | That pack is excluded from search and Ask; others continue; Readiness flags it |
| Download interrupted | Auto-resume |
| Checksum mismatch | Retry once, then mark failed with a Retry button; never registered |
| Insufficient space | Checked before queueing and before each chunk write |
| Import: DRM-protected, corrupt, or unsupported file | Reject that file with a specific reason; other files in the batch continue |
| Import: PDF with no text layer | Import for reading; mark "not searchable" |
| Catalog unreachable or bad signature | Use the last good catalog; installed content unaffected |
| Generation runs too long | Hard token cap; show what was generated if it is validly cited, else show the passages |

## 5. Testing and evaluation

### Tests

- **JVM unit tests:** chunking, decline threshold, citation validation, cap arithmetic, catalog signature + parsing, download state machine, tier detection, EPUB/PDF/TXT text extraction, DRM detection (fixture files), FTS5 search ranking.
- **pack-builder tests:** build a tiny ZIM from fixture texts; assert entries, full-text index presence, and no Gutenberg branding left in output.
- **Instrumented (emulator):** `:core:zim` against a tiny test ZIM committed to the repo; WebView entry serving; downloads against a local test server; billing via Play test SKUs.
- **Pixel 6:** debug-only benchmark screen measuring load time, swap time, time to first token, tokens/s, peak memory.

### Ask eval (`tools/eval`)

- Desktop harness using `:core:ask-logic`; models run via desktop LiteRT-LM or on-device over adb.
- Dataset: ~300 questions from Starter packs, split roughly 40% answerable · 30% unanswerable · 20% medical · 10% multi-step (Think deeper).
- Reported separately: retrieval recall@4, answer groundedness, decline accuracy, medical-flag accuracy.
- Pass bar: ≥ 90% correct declines, ≥ 80% grounded. A model gets `evalPassed` only after meeting it. Failing → tune threshold/prompts/chunking first; fine-tune only as a last resort.

## 6. Build order

1. **Model spike on the Pixel 6** — LiteRT-LM loads E2B, E4B, EmbeddingGemma; measure time to first token, tokens/s, peak memory, swap cost, embedder co-residency. Riskiest assumptions first.
2. **Reader** — `:core:zim`, test ZIM, Library screen, WebView, search, bookmarks.
3. **Catalog + downloads** — signed catalog, `catalog-gen`, WorkManager downloads, volumes, Readiness.
4. **Pack builder + Granger Classics** — `tools/pack-builder`, Classics manifest, OpenStax physics pack, add to catalog.
5. **Import** — `:core:imports`, `:core:search` abstraction, My books, EPUB/PDF/TXT readers.
6. **Ask pipeline** — rewrite, find (all sources), chunk, rank, answer, decline, medical banner, Think deeper.
7. **Eval harness + dataset** — tune threshold and prompts to the pass bar (dataset includes questions over Granger Classics).
8. **Billing, cap + import gating, onboarding, telemetry, F-Droid flavor.**
9. **Play internal beta.**

## 7. Open items (not blocking the plan)

- Trademark search for "Granger".
- Lawyer review of GPLv3 + Play IAP approach before public launch.
- Exact Pro price (tested in beta).
- Hugging Face gating: Gemma 4 LiteRT files are **not** gated (checked 2026-10-15). EmbeddingGemma **is** gated and is licensed under the Gemma Terms of Use (not Apache-2.0): plan to self-host it with the required notice, and include it in the lawyer review.
- Confirm the curated Wikipedia core and WikiMed sizes for the Starter preset.
- Final Granger Classics title list; verify the US public-domain status of each translation (notably the Quran translation choice).
- Non-US public-domain terms (life + 70 jurisdictions) for Classics and Gutenberg packs.
- Whether redistributing Kiwix's Gutenberg packs alongside a paid Pro tier touches the Project Gutenberg trademark terms (lawyer).
- Fahrenheit 451 easter egg (on hold).
