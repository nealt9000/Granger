# Plan R1: Dense retrieval — the vector set and hybrid search

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Research finds passages by meaning as well as by keyword. Vectors for the spec's vector set are built on the Mac,
published through the catalog, downloaded with their packs and books, and searched on the phone. Dense hits share the answer
slots with keyword hits, and fiction weighting is on by default. R1 ends with tuning-split numbers, not a pass claim: the fresh
held-out set and the pass-or-stop scoring are R2.

**Spec:** `docs/superpowers/specs/2026-10-21-research-library-design.md` (§3–§6, §9). Spike results:
`docs/superpowers/spikes/2026-10-research-spike-results.md`.

**Architecture:**
- **One chunker, two callers.** `XhtmlText` (now in `:core:books`, an Android module) and `HtmlPassages` (`:core:research`)
  move to a new pure-JVM module, `:core:text`, keeping their packages. The phone uses them as before. On the Mac, a small
  JVM CLI, `tools/chunker`, runs the same code, so pack-time chunks match the phone's by construction, not by a port.
- **Mac pipeline (`tools/vectors/`):**
  1. Python reads each source with python-libzim (ZIM) or zipfile (EPUB) and streams HTML to the chunker as JSONL.
  2. The chunker sends back keyed chunks.
  3. sentence-transformers `google/embeddinggemma-2` embeds them titled and normalized, and they're truncated to the
     chosen dims and quantized.
  4. One vector file per pack or book is written.
- **Phone (`:core:vectors`, pure JVM):** reads vector files memory-mapped, scans them in parallel with int8 dot products, and
  returns keys with cosines. `DensePassageFinder` (`:core:library`) turns keys back into `Passage`s through the same chunkers.
  `ResearchPipeline` embeds the question while the rewrite runs and merges dense and keyword hits.

**Tech Stack:** Kotlin and Gradle as today. jsoup (already used). Python 3 in the eval venv, plus python-libzim
(`tools/pack-builder/requirements.txt`), sentence-transformers and numpy. The phone embedder is
`embeddinggemma-2-text-270m.litertlm` (LiteRT-LM 0.18.0).

## Decisions already made (spec §12, Neal 2026-10-21)

- **The vector set (spec §3.2):**
  - Granger Classics EPUBs;
  - `wikipedia_en_medicine_nopic`, `wikem_en_all_maxi`;
  - `appropedia_en_all_maxi`, `based.cooking_en_all`, `foss.cooking_en_all`;
  - `ifixit_en_all`, `wikipedia_en_knots_maxi`;
  - `libretexts.org_en_k12`, `wikipedia_en_physics_maxi`, `_chemistry_maxi`, `_mathematics_maxi`;
  - `jeffe.cs.illinois.edu_en_all`;
  - `wikivoyage_en_all_nopic`;
  - `wikipedia_en_top_nopic`, `milneopentextbooks.org_en_all`;
  - `wikiquote_en_all_nopic`;
  - `internet-encyclopedia-philosophy_en_all`, `wikipedia_en_history_nopic`.
- E2B answers and E4B is Think deeper. There's no question-time passage embedding.
- Vectors are built off the phone, except for books a user adds (that's R2).

## Decisions on this plan (Neal, 2026-10-21)

- The Mac runs the app's own chunker code (`:core:text` and `tools/chunker`), not a Python port.
- The new baseline (`r1-base`) is expected to show a few gaps in the set, and probably some pruning before the release set.
  `set.json` is data: Task 11's per-domain results propose additions and cuts, and Neal approves the release set before
  Task 10's upload is final.

## Builder's calls in this plan (for Neal's review)

- **`:core:text` and a JVM chunker CLI** instead of the spec's Python port with a parity gate. The gate still runs (Task 1),
  but it checks that the CLI and the app call the same code, which can't drift.
- **File format v1:**
  - a 64-byte header: magic `GVEC`, version, dims, count, the embedder's sha256, the source's sha256, the chunker version;
  - int8 vectors, row-major, each with a float32 scale;
  - keys as length-prefixed UTF-8 with an 8-byte xxhash64 of the chunk text.

  It's little-endian and read with `FileChannel.map`.
- **Domains:** the per-pack domain lives in `tools/vectors/set.json`, along with the set, until H1 puts `domain` in the
  catalog.
- **A new baseline after the set is installed** (`r1-base`, keyword only, today's ranking). The added packs change keyword
  results too, as Hermeneutics did in R0, so dense's gain is measured against the same library.

## Global Constraints

- **Gates on the tuning split (202 questions), against `r1-base`:**
  - recall@4 up by at least 10 points;
  - answerable grounded up by at least 5 points;
  - unanswerable declined at 89% or more;
  - median send to first words no slower than R0's 17.4 s.

  If grounded rises but declined falls below 89%, stop and bring it to Neal.
- **The dense search budget is 1 s on the Pixel 6** for the whole installed set: query embedding excluded, text rebuild
  included.
- **Phone rules:**
  - never run `connectedDebugAndroidTest` (it wipes the library);
  - never `adb reboot`;
  - never touch the phone while one of Neal's apps is in front;
  - one Gradle build at a time with `-Pkotlin.compiler.execution.strategy=in-process`, then `./gradlew --stop`;
  - installs go over Wi-Fi (`tools/dev/phone-wifi.sh install`).
- **Neal's OK is needed at the step** for:
  - pushing commits;
  - uploading release assets;
  - re-signing the catalog;
  - downloading more than 5 GB to the phone.
- The held-out sets (Plan 9's, and the fresh one R2 writes) are not opened in R1.
- Release behavior changes only behind `DENSE_RETRIEVAL` (Task 9) until Task 11 turns it on.

## Paused (Neal, 2026-10-22)

R1's phone work waits until V1 (the voice) is finished and merged to main. V1's build moved the phone's database to v11,
and this branch is v10. To resume:
1. Rebase `r1-dense-retrieval` onto main, which will include V1's migration.
2. Ask the voice session before installing over its build.
3. Run `r1-base` (Task 7's first step): `ANDROID_SERIAL=… python eval.py run dataset/tune.json --run-id r1-base --fresh`.
   `tools/eval/runs/r1-base` links to the main checkout's runs folder, so the run outlives this worktree.

State at the pause:
- **Done:** Tasks 1–3; the code for Tasks 4–6 and 8.
- **Mac build:** the vector build keeps running there (`~/Developer/Granger/vectors-work/run-build.sh`, log `build.log`;
  it resumes per shard if stopped).
- **Phone:** the set's 16 missing packs are already on it.
- **Not started:** Task 7 (merging) and Tasks 9–12.

**Resumed 2026-10-22:** V1 is on main (59a8982), and this branch is rebased onto it. V1's `ArticleText` moved into
`:core:text` next to `HtmlPassages`, which reads its rules from it. `r1-base` ran on the phone the same day, and its 45 new
replies were graded:

| Tuning split | r0-combined | r1-base |
|---|---|---|
| Unanswerable declined | 69/76 (91%) | 71/76 (93%) |
| Answerable grounded | 57/126 (45%) | 53/126 (42%) |
| Retrieval recall@4 | 29% | 25% |
| Answer found but not chosen / never found | 12 / 77 | 12 / 82 |
| Think deeper grounded | 4/12 | 2/13 |
| Send to first words, median / p90 | 17.4 s / 23.7 s | 14.1 s / 18.4 s |

`r1-base` has the whole set installed (16 more packs) plus Task 8's parallel search and the Classics twins dropped. The extra
packs cost keyword recall (more distractors in the 12 preselected). This is the gap dense retrieval has to close. The parallel
search saves about 3.3 s at the median despite the extra packs.

---

### Task 1: `:core:text` and the chunker CLI

**Files:**
- create `core/text/build.gradle.kts`;
- move `core/books/.../XhtmlText.kt` and `core/research/.../HtmlPassages.kt` into
  `core/text/src/main/kotlin/app/granger/core/{books,research}/`, keeping their packages, with their tests;
- add `:core:text` to `settings.gradle.kts`, with dependencies from `:core:books` and `:core:research`;
- create `tools/chunker/` (a JVM application: `build.gradle.kts`, `Main.kt`, tests).

- [x] Move the two files and their tests. Every existing JVM test passes unchanged.
- [x] `XhtmlText` gets an entry point that takes chapter XHTML text, not just a `ZipFile`, so the CLI can feed it.
  `all(file, spine)` keeps its behavior.
- [x] Add `CHUNKER_VERSION = 1` in `:core:text`. Bump it on any change to either chunker's output; a vector file with another
  version is ignored on the phone.
- [x] The CLI reads JSONL from stdin: `{kind: "xhtml"|"html", key, title, html}`. It writes JSONL to stdout: `{key, ordinal,
  heading, anchor, progression, text, hash}`. `hash` is xxhash64 of `text` (the same function lives in `:core:text`).
- [x] `./gradlew :tools:chunker:installDist` produces `tools/chunker/build/install/chunker/bin/chunker`.
- [x] **Gate:** on 20 Classics chapters and 50 articles from three vectored ZIMs, the CLI's chunks equal what
  `XhtmlText`/`HtmlPassages` produce in a JVM test that reads the same bytes. Add a golden-file test.

**Done (2026-10-21).**
- `XhtmlText`'s chunking is `XhtmlChunks.of(href, bytes)` in `:core:text`. `cutAtSpaces` moved with it, and `XhtmlText` keeps
  only the zip reading.
- The hash is the first 8 bytes of SHA-1 (`Chunkers.hash`), not xxhash64: both sides have it built in.
- The CLI takes base64 bytes, so it decodes them exactly as the phone does.
- **Gate:** checked against the phone itself rather than a JVM test, which is stronger. Every passage `r0-combined` recorded
  is matched by a Mac chunk of the same file (`tools/vectors/parity.py`):
  - books: 6,369 of 6,369;
  - `internet-encyclopedia-philosophy_en_all`: 2,898 of 2,898;
  - `wikipedia_en_chemistry`: 1,871 of 1,871.
- **What the gate found:** Android's `\s` (ICU) is Unicode whitespace, but the desktop JVM's is ASCII-only, so the same
  code split thin spaces differently. Both chunkers now use ICU's set spelled out (`Chunkers.SPACE`). The phone's output
  is unchanged.

### Task 2: Size the set

**Files:** create `tools/vectors/set.json` (`{packs: [{id, domain, zimName}], books: "granger-classics"}`) and
`tools/vectors/count.py`.

- [ ] `count.py` enumerates every vectored source on the Mac:
  - ZIM: the front articles with an HTML mimetype, redirects skipped;
  - EPUB: the spine;
  - it streams them through the chunker and counts chunks per pack.
- [ ] Report chunks, the vector file size at 256 and at 768 dims (int8), and the estimated Mac embedding time (time a
  10k-chunk sample) per pack. Download any missing pack files with `tools/catalog-gen` URLs; they go under the gitignored
  `tools/vectors/sources/`.
- [ ] **Stop and tell Neal** if the set's vectors exceed 1.5 GB at 256 dims, or a single pack's file would exceed GitHub's
  2 GB asset limit. Options then: drop `_nopic` long tails, split files, or lower the dims.

**Stopped at the size check (2026-10-21).** The proposed set came to 6.69M chunks: 1.98 GB at 256 int8, and about 56 h
of Mac embedding.
- Three packs have no text the app can read:
  - *Algorithms* is 696 PDFs;
  - LibreTexts K12 renders its pages from JSON;
  - Milne is a catalogue page per book.
- WikEM is 96% translations.

**Neal's decisions:**
- trim and keep every domain:
  - WikEM English only;
  - Wikipedia packs capped at an article's first 12 chunks;
  - Wikiquote keyword-only;
- technology is the DevDocs bundle (Python, Bash, Git, HTTP, SQLite, C, JavaScript) plus the reverse engineering,
  software engineering, computer science and Unix Stack Exchanges;
- *Algorithms*, LibreTexts K12 and Milne leave the set until a PDF/JSON pass.

`set.json` records all of it (`keywordOnly` says why each pack is out).

**After the trim, a builder's call for Neal's review.** The added Stack Exchanges took the count back to 6.86M (2.1 GB):
Unix alone is 1.93M, and about 17% of its chunks are user profile pages. So:
- Stack Exchange profile and question-list pages are skipped, and each question page keeps its first 4 chunks (the question
  and its top answers);
- iFixit keeps a guide's first 8 chunks (its median guide is 8 chunks; 22 at the 90th percentile).

Estimated about 5.1M chunks, about 1.6 GB at 256 int8, and about 43 h of embedding. That's still a little over 1.5 GB. The
real limit is the phone's scan time (Task 5), and a user scans only the packs they've installed. The Mac's JVM scans 1M
rows in 90 ms on one thread and 14 ms on eight; the Pixel 6 figure decides any further cut.

**Picture and no-picture packs share vectors.** The phone's `wikipedia_en_top_maxi` passages match the `_nopic` file's
chunks on 18,525 of 18,531. The per-chunk hash check drops the rest, so one vector file serves both flavours.

### Task 3: Dims, quantization and phone queries (offline)

**Files:** extend `tools/eval/dense.py` (or `tools/vectors/recall.py`), plus `ResearchBenchmarkActivity`'s `cosines` mode.

- [x] Re-chunk R0's 350-book library through the CLI. Re-embed titled, then evaluate the 35 book questions at 768 float,
  768 int8, 256 int8 and 128 int8.
- [x] Phone queries: embed the 202 tuning questions (raw, and prior question + question for follow-ups) on the Pixel 6 through
  `ResearchBenchmarkActivity cosines=`. Pull the vectors and rerun the recall with phone queries against Mac documents.
- [x] **Decisions, recorded in this plan:**
  - dims: 256 unless it loses more than 2 of 35 against 768;
  - Mac documents: kept unless phone queries lose more than 2 of 35, in which case stop and choose with Neal between building
    documents with LiteRT-LM or accepting the loss;
  - query text: raw or prior + question, whichever recalls more on follow-ups without losing first questions.

**Done (2026-10-21).** On R0's 35 book questions (answer in the top 4; top 12 and top 50 in `recall.py`). R0's vectors
were reused for the 248,393 of 249,393 app chunks whose text is unchanged.

| Query | 768 float | 768 int8 | 256 int8 | 128 int8 |
|---|---|---|---|---|
| Mac, raw | 20 | 20 | 18 | 16 |
| Mac, prior + question | 20 | 20 | 19 | 17 |
| Phone (GPU, int4 model), raw | 18 | 18 | 19 | 14 |
| Phone, prior + question | 18 | 18 | **20** | 15 |

- **Dims: 256 int8.** With phone queries it loses nothing against 768. 35 questions is a small sample, so ±2 is noise.
- **Documents: built on the Mac.** Phone queries against Mac documents lose at most 2 of 35.
- **Query text: the prior question, then the question.** It never loses, and it lifts the follow-ups slightly.
- On the phone, a query takes about 0.12 s on the GPU (404 queries in 48 s).

### Task 4: The vector file and the build

**Files:**
- create `tools/vectors/build.py`, `tools/vectors/gvec.py` and `tools/vectors/tests/`;
- create `core/vectors/` (`VectorFile.kt`, `VectorSearch.kt`, tests);
- add `:core:vectors` to `settings.gradle.kts`.

- [ ] `gvec.py` writes format v1 and `VectorFile.kt` reads it. A shared fixture, `core/vectors/src/test/resources/tiny.gvec`
  (written by Python), is read by the Kotlin test, so both sides agree byte for byte.
- [ ] `build.py SOURCE --out DIR`:
  - chunks through the CLI, embeds titled (`title: <title — heading> | text: …`), truncates to Task 3's dims, renormalizes
    and quantizes;
  - writes `<id>_<snapshot>.gvec` and a descriptor JSON (`url`, `sha256`, `sizeBytes`, `embedderSha256`, `dims`, `dtype`,
    `chunkerVersion`, `sourceSha256`) in `tools/vectors/releases/`;
  - it's resumable per batch, since Wikipedia's run is long.
- [ ] Build every source in the set. Record the counts and times in `tools/vectors/BUILD.md`.

### Task 5: Search on the phone

**Files:** `core/vectors/.../VectorSearch.kt` and `VectorStore.kt`, with tests. Measured in `ResearchBenchmarkActivity`.

- [ ] `VectorSearch.top(query: FloatArray, files, k)`:
  - quantize the query once, then scan each file in parallel (a bounded pool, one file per task, split large files into
    ranges);
  - keep a per-thread top-k heap and merge.

  Tests: exact top-k against a float reference on random data, plus ties and empty files.
- [ ] `VectorStore` maps the installed vector files and drops any whose `embedderSha256` or `chunkerVersion` doesn't match
  the installed embedder. It's rebuilt when the library changes.
- [ ] Measure on the Pixel 6 with the whole set pushed to `files/vectors/`. Pushing over adb is fine here; the catalog comes in
  Task 10. Measure scan time at k 24 and 48.
- [ ] **Gate:** a scan of 0.6 s or less, leaving 0.4 s of the 1 s budget for the text rebuild. If it's slower, try a
  native-free fallback first (an `IntArray`-packed dot product), then fewer dims. Bring it to Neal before writing JNI.

### Task 6: `DensePassageFinder`

**Files:** create `core/library/.../DensePassageFinder.kt` and its test. Touch `Passage.kt` (add `cosine: Double? = null`)
and `BookIndex.kt` (expose the chapter re-chunk used by `chunkTexts`).

- [ ] From `VectorStore` hits, group by source and rebuild:
  - books: `(href, ordinal)` through `BookIndex`'s chapter re-chunk;
  - articles: read the entry and run `HtmlPassages.split`, taking the section ordinal.

  Each becomes a `Passage` with the same fields the keyword finder fills (location, anchor, progression, heading, meta), plus
  `cosine`.
- [ ] Drop a hit whose rebuilt text hashes differently, and count it in a debug log.
- [ ] **Classics de-duplication (spec §3.2):** when a work's EPUB is installed, keyword ARTICLE passages from
  `granger_classics_en` for that work are dropped. Match by work id; the ZIM's paths carry the `gc-` slug. Add a test.
- [ ] Measure the rebuild on the phone for k 24. **Gate:** scan plus rebuild of 1 s or less, median over the tuning split.

### Task 7: Merging, offline

**Files:** `tools/eval/replay.py` (a new ranker) and `tools/eval/tests/test_replay.py`.

- [x] Re-record the corpus on the phone with the set installed and dense off: `r1-base`, the full tuning split, graded. This
  is R1's baseline.
- [ ] Offline dense hits per tuning question, from Mac vectors with the phone query vectors from Task 3, added to the
  `r1-base` corpus.
- [ ] Rankers to compare:
  - calibrated hybrid: `α·lexical + (1−α)·cosine` where a cosine exists, lexical alone otherwise, α from 0 to 0.4;
  - reciprocal rank fusion of the lexical and cosine lists;
  - 1 or 2 of the 4 slots reserved for dense hits above a cosine floor, the rest lexical.

  Each runs with fiction weighting 0.7 (filter mode) and a cosine floor sweep. `perDocument`, the media cap and the 4/8 slots
  are kept.
- [ ] Report recall@4 by location and by quote, the empty rate on unanswerable questions, and the per-domain split from
  `set.json`'s tags. Pick the best ranker that keeps the empty rate at or above `r1-base`'s.

### Task 8: Fewer keyword searches, in parallel

**Files:** `core/library/.../LibraryPassageFinder.kt` and `core/zim/.../KiwixZimArchive.kt`, with tests.

- [ ] Run `articles()` across archives on a bounded pool (4 threads; the per-archive lock stays), not one archive after another.
- [ ] Offline on `r1-base`, check whether vectored packs can be asked for fewer terms (the 2 longest keywords, say) without
  losing recall@4 under Task 7's ranker. Adopt only if it loses nothing.
- [ ] Measure median search time before and after on the tuning split.

### Task 9: The pipeline

**Files:**
- `core/research/.../ResearchPipeline.kt`, `Ranking.kt`, and their tests;
- `app/.../GrangerApp.kt`, `research/ModelsAdapter.kt`, `core/ai/.../ModelManager.kt`.

- [ ] `ResearchPipeline` takes an optional `DenseFinder` (an interface in `:core:research`). The query (Task 3's choice) is
  embedded **concurrently with the rewrite**, and the dense search runs alongside the keyword search.
- [ ] `Ranking` gets Task 7's ranker, ported from `replay.py`. The `replay.py check` agreement test is extended to cover it:
  **gate:** phone and replay choose the same 4 on at least 200 of 202.
- [ ] Fiction weighting moves from debug (`FictionWeight`) to main, on by default at 0.7. The `sourceWeight` parameter stays
  for the eval.
- [ ] `RERANK_WITH_EMBEDDER` is replaced by `DENSE_RETRIEVAL` (default false until Task 11). With it on, `ModelManager` loads
  the embedder alongside E2B. Think deeper computes the query vector before E4B loads, since loading E4B closes the embedder.
- [ ] The Sources screen's "Not used yet" label for EmbeddingGemma changes to "Used by Research" when `DENSE_RETRIEVAL` is on.
- [ ] `EvalRunner` records `cosine` in `found.jsonl` and `ms.dense` in results.

### Task 10: Vectors in the catalog

**Files:**
- `core/catalog/.../Catalog.kt` (`VectorsEntry`, optional on `PackEntry` and `BookEntry`) and its tests;
- `tools/catalog-gen/catalog_gen.py` (merges `tools/vectors/releases/*.json`);
- `core/downloads` (a vectors item follows its parent);
- the installed-file wiring into `VectorStore`.

- [ ] Parse the optional `vectors` object. An older catalog, or an entry without it, behaves as today. Test with a catalog that
  has and lacks the field.
- [ ] Downloading a pack or book in the set also downloads its vectors, verified by sha256 and counted in the item's size.
  Deleting the item deletes them.
- [ ] An already-installed item gains its vectors after a catalog refresh, through the same queue, quietly.
- [ ] Readiness shows a vectors file as part of its item (verified / incomplete / corrupt). There's no separate row.
- [ ] **Neal's OK:** upload the `.gvec` assets to a `vectors-<date>` GitHub release, regenerate, re-sign and push the
  catalog.
- [ ] On the phone: remove the adb-pushed files, refresh the catalog, and confirm every vectored item downloads its vectors
  and `VectorStore` loads them all.

### Task 11: The tuning run and the decision

- [ ] Run the full tuning split with `DENSE_RETRIEVAL` on (`r1-dense`), and grade it in-session against
  `tools/eval/RUBRIC.md`.
- [ ] Compare it with `r1-base` (`eval.py report --compare`):
  - grounded, declined, recall@4;
  - the failure table from the R0 results (among the 4 / found but not chosen / never found);
  - per domain;
  - latency (median and p90 for send to first words, plus `ms.dense`).
- [ ] **If the gates pass,** set `DENSE_RETRIEVAL = true`. Research is still debug-only (`Features.RESEARCH`), so release
  users see nothing until R2's pass.
- [ ] **If they don't,** keep it false. Write what failed and the options in the results, and bring them to Neal before R2 is
  written.

### Task 12: Results and roadmap

- [ ] Write `docs/superpowers/spikes/2026-10-r1-dense-results.md`:
  - the tables;
  - Task 3's decisions;
  - the set's sizes and build times;
  - what R2 inherits: the on-phone embedding of added books uses `build.py`'s format; the held-out set must cover every
    domain in `set.json`.
- [ ] Add an R1 row to the roadmap and a "Carried forward from R1" section.
- [ ] Clean up: the gitignored `tools/vectors/sources/` and embedding caches stay on the Mac, and their location goes in
  `BUILD.md`. Remove the adb-pushed vectors from the phone (done in Task 10).
