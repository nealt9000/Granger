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
| Free/Pro (storage cap), opt-in telemetry | Battery estimates, Ask history, languages beyond English |
| | Fine-tuned models (contingency only), preloaded SD kits, hardware |

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
| Monetization | Free/Pro, one-time Play IAP, cheap. **Only difference: Free caps installed packs at 10 GB** (models excluded; all volumes incl. SD count). |
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
| `:core:billing` | Play Billing; exposes `isPro` / `capBytes`. F-Droid flavor: always uncapped, no Play dependency. | Play Billing (play flavor only) |
| `:core:telemetry` | Opt-in event queue, preview screen, upload when online | — |
| `tools/catalog-gen` | Script: pull Kiwix catalog, filter to curated list, pin hashes, sign | — |
| `tools/eval` | Desktop eval harness + dataset | `:core:ask-logic` |

Each core module exposes a small interface; `:app` depends on interfaces, enabling fakes in UI tests.

### Reading

ZIM articles are HTML. The reader is a WebView whose `shouldInterceptRequest` serves every entry (HTML, images, CSS) directly from the ZIM — the same approach as Kiwix Android. Reading position and bookmarks persist per pack + entry path.

### Ask pipeline

1. **Rewrite** — E2B produces structured output: `{ searchTerms: [...], medical: bool }`. The rewrite is kept even though it costs roughly 1–2 s, because ZIM search is keyword-only.
2. **Find** — query the full-text index of every installed pack with `hasFulltextIndex`; take the top ~8 articles overall.
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

## 3. Catalog, downloads, storage, billing

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

### Billing

- One non-consumable product, `pro`.
- The entitlement is cached locally after the first verification, so it works offline indefinitely; it's re-checked silently when online.
- F-Droid flavor: `capBytes = ∞`, no Play Billing dependency, "Support development" link.

### Onboarding

1. Welcome screen.
2. Tier detection, shown in plain language.
3. Telemetry opt-in prompt (off by default).
4. Downloads with the **Starter** preset preselected: E2B + EmbeddingGemma + WikiMed + the ~2 GB curated Wikipedia core (≈ 5 GB of packs). Editable per pack; nothing downloads without confirmation. The **Full** preset requires Pro on Play.

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
| Catalog unreachable or bad signature | Use the last good catalog; installed content unaffected |
| Generation runs too long | Hard token cap; show what was generated if it is validly cited, else show the passages |

## 5. Testing and evaluation

### Tests

- **JVM unit tests:** chunking, decline threshold, citation validation, cap arithmetic, catalog signature + parsing, download state machine, tier detection.
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
4. **Ask pipeline** — rewrite, find, chunk, rank, answer, decline, medical banner, Think deeper.
5. **Eval harness + dataset** — tune threshold and prompts to the pass bar.
6. **Billing, cap, onboarding, telemetry, F-Droid flavor.**
7. **Play internal beta.**

## 7. Open items (not blocking the plan)

- Trademark search for "Granger".
- Lawyer review of GPLv3 + Play IAP approach before public launch.
- Exact Pro price (tested in beta).
- Hugging Face gating for Gemma 4 LiteRT files (see §3).
- Confirm the curated Wikipedia core and WikiMed sizes for the Starter preset.
