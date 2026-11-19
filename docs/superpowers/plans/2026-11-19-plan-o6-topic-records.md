# Plan O6: full topic pages online for a million articles (topic records)

2026-11-19. Spec `docs/superpowers/specs/2026-11-18-online-offline-design.md`: §6 Phase 2 and §12's O6 row. Builds on
O0 Task 2b (the design and its numbers), O1 (online articles by QID, the shared client and gate) and O2 (Saved for
now).

**Goal:** an online article outside the small Top index gets the full topic page without any download:
- its subjects and room;
- the broad-topic spread with its parts;
- "leads here";
- coordinates;
- dated links.

It covers about a million English articles (Top 1M) and the whole Spanish Wikipedia. Today these articles get the
lighter online page: summary header, Related from `morelike:`, quotes, and nothing from our indexes.

**How (O0's finding):** not SQLite over HTTP.
- **Topic records:** a build step pre-joins each article's page data into one small compressed record, sorted by QID,
  in blocks of 128.
- **The directory:** a ~93 KB file, fetched once, says which block holds a QID.
- **One page costs one HTTP range read** (~10 KB, 0.1–0.2 s) against a GitHub release asset. No server, no native
  code.
- **Measured at the desk, Top 1M (en):**
  - 994,325 records;
  - an 86 MB `.grec` (the `.gqid` is 142 MB);
  - a 93 KB directory;
  - a 14 s build.

**The bar (spec §6),** on the Pixel 6 over Wi-Fi:
- the topic page header in ≤ 1 s;
- Related in ≤ 3 s.

If it misses, Phase 2 becomes a "Keep the big index" offer instead (the `.gqid` download that already exists).

**Not in O6:**
- **Room lists from Top 1M on Explore.** That's 98 KB, but Explore's rooms and A–Z stay the Top index's.
- **People and events of an article's time for the long tail.** The time index covers Top only.
- **Other languages.**
- **Records for book packs.**

## Builder's calls (for Neal's review)

1. **Which records ship:** two files, each a `.grec` plus its `.grdir`.
   - **English:** from the Top 1M `.gqid` (994k articles).
   - **Spanish:** from the "all" `.gqid` (the whole Spanish Wikipedia, 287 MB as a `.gqid`). The record file comes out
     at roughly 60% of that, to be measured.
   - Another language gets records when its big `.gqid` exists. The build is one command per `.gqid`.
2. **The format: O0's, plus integrity and a version.**
   - The directory gains a header (`GRD2`, version, record count, source `.gqid` sha256) and a CRC32 per block.
   - Each block read is checked against its CRC before it's parsed. A mismatch drops that block, re-resolves the URL
     and tries once more.
   - The whole `.grec` is never hashed on the phone: it's never downloaded whole.
3. **In the catalog, records are a remote-only field on the pack, not a sidecar download.**
   - Shape: `records: {url, dirUrl, dirSha256, sizeBytes, edition}`.
   - It isn't a `SidecarKind`. Nothing queues it, so it never shows in Downloads or Outfitting's files.
   - The directory's sha256 is checked when it's fetched.
   - The catalog is re-signed with the new field (the key is on this Mac, as for every catalog change).
4. **When the directory is fetched:** lazily, the first time a topic page needs it, never at first run.
   - It's kept in `filesDir/records/<edition>.grdir`, 93 KB a language, and counts as an index, as On this phone.
   - A new edition in the catalog replaces it the next time it's needed.
5. **The block cache:** blocks read are kept in `cacheDir/records/`, one file per block (~10 KB), inside Saved for
   now's cap and cleared by its Clear.
   - A long-tail topic page seen once opens again offline with its parts.
   - In Local only and real offline, only cached blocks are read. Nothing reaches the network (the shared client's
     gate already refuses).
6. **URLs:**
   - **The redirect:** resolved once per session (`github.com/…/releases/download/…` to its signed
     `release-assets.githubusercontent.com` URL) and reused. O0 measured 0.5–0.65 s through the redirect against
     0.08–0.2 s direct.
   - **A 403 or 410** means the signed URL has expired: resolve it again, once.
   - **Range reads** go through the shared OkHttp client, with its User-Agent and gate, and a Semaphore(4) per host as
     Wikimedia has.
7. **What the topic page takes from a record:**

   | From the record | On the page |
   |---|---|
   | subjects and rooms | the breadcrumb |
   | coordinates | the map line |
   | dated links | the time span |
   | broad-topic parts, every item already resolved to a path | the spread |
   | the top 8 topics that lead to it, plus their count | "Leads here" |

   - **Item titles** come from paths.
   - **Pictures** for parts and holders come from online summaries, batched 20 to a request (O1's batch).
   - **Order of sources:** the phone's own index wins. A record is used only when no local index holds the QID, so a
     kept or installed big index always beats records.
8. **The QID of an online article** comes from its summary (`wikibase_item`, already fetched for the header). No
   path-to-QID file is needed.

## Tasks

### Task 0: branch

Branch `o6-topic-records` from main, in worktree `.claude/worktrees/o6`.

### Task 1: the build step (tools)

- **Where:** `tools/topicrecords/build.py`, from `tools/online-spike/records.py`.
- **Format:** v2 (call 2).
- **Input and output:** a `.gqid` in; `.grec` and `.grdir` out; a size and timing report.
- **Tests:** unittest on a tiny `.gqid` fixture built in the test, covering:
  - a plain article, a broad topic with parts, and holders;
  - lookup at block edges, a missing QID, and a CRC mismatch.
- **Then:** build English (Top 1M) and Spanish (all) on the X10 drive, and record the sizes and build times.

### Task 2: publish

- Upload to a new release `records-2026-11-xx`.
- Add `records` to the two packs in the catalog (curated input, then generated), then check links and sign.
- **Tests:** the catalog model parses `records`; packs without it behave as today.

### Task 3: the reader (core)

`core/library/TopicRecords.kt`:
- parses the directory;
- finds a QID's block (binary search);
- parses the block header and checks its CRC;
- inflates and parses one record (org.json) into `TopicRecord(path, rank, subjects, rooms, coords, links, parts,
  holders, holderCount)`.

The reader works on byte arrays, with no I/O, so it's pure for tests.

**Tests:** a fixture `.grec`/`.grdir` made by Task 1's tool, checked into test resources (a few KB). Cases: edges,
missing, corrupt, broad, plain.

### Task 4: remote reads (app)

`online/RemoteRecords.kt`:
- the catalog's `records` for a language;
- the directory, fetched lazily, checked and kept (call 4);
- range GETs through the shared client;
- the redirect, re-resolved on 403/410 (call 6);
- the block cache (call 5);
- cache-only reads under Local only or with no signal.

**Tests:** MockWebServer for range requests and a 403 then success; the cache hit; Local only reading only the cache;
a CRC failure retried once, then null.

### Task 5: the topic page

`TopicSource` asks records for an online article whose QID no local index holds. It fills the same `TopicHeader` /
topic fields the local index fills (call 7), with pictures from batched summaries.
- **The lighter page stays** for articles outside every record set (other languages) and while a read fails.
- **Records never hold the page up:** the header from the summary shows first, and the record fills in when it arrives.
- **Tests:**
  - a long-tail article gets its breadcrumb, spread and "leads here" from a fake records source;
  - a local index beats records;
  - a failing read leaves the lighter page.

### Task 6: the bar and the walkthrough (Pixel 6, then the Pixel 7)

**Measure on the Pixel 6, debug build, over Wi-Fi:**
- **Cold:** the directory plus a block.
- **Warm:** a cached block.
- **Scope:** ten long-tail English articles, five broad ones, and five Spanish.
- **What's timed:** header ≤ 1 s and Related ≤ 3 s against the bar; also when the record arrives.

**Then on the phone:**
- Local only on a page seen before (from the cache) and on one not seen (the lighter page, greyed links).
- Clear Saved for now, which empties the block cache.

**If the bar misses:** stop, write it up, and propose the "Keep the big index" offer instead.

### Task 7: merge

- Run the full suite, then a fast-forward merge to main.
- Update the roadmap and spec §6 (Phase 2 shipped, with the numbers).
- A candidate only if Neal wants one.

## Order

Tasks 1 and 3 together (the format is shared), then 2, then 4 and 5, then the bar. Task 6's measurement decides
whether Task 7 ships records or the fallback.

## Results so far (2026-11-19): built and fast, but the long tail gains little

Tasks 1–5 are built (branch `o6-topic-records`) and the full suite passes. The records are published (release
`records-2026-11-19`) and the catalog on the branch is signed; neither is merged or pushed.

**Sizes:**

| | English (Top 1M) | Spanish (all) |
|---|---|---|
| Records | 994,325 | 2,131,364 |
| `.grec` | 100 MB | 201 MB |
| Directory | 155 KB | 333 KB |
| Build time | 15 s | 30 s |
| Median block | 12.3 KB | 11.8 KB |
| p99 block | 28.5 KB | 21.2 KB |

**Speed (Pixel 6, debug build, Wi-Fi), eight long-tail English articles, each from a cold app start:**
- **A record:** 0.47–2.4 s after the QID is known. That includes the catalog load, the redirect and one range read; the
  first read also fetched the directory.
- **A kept block:** 85 ms.
- **The header:** never held up; it came at 0.35–2.5 s, which is Wikipedia's summary request on a cold start.
- **Related:** unaffected (`morelike:`).
- **Local only:** a kept block reads from Saved for now; an unseen article asks for nothing.

**Fixed on the phone:** a topic page opened straight after a cold start came before anything had loaded the catalog, so
there were no records. It now loads the catalog first.

**The finding: the long tail doesn't get what the plan promised.**
- All 1,890 English broad topics and all Spanish ones are already in the small Top index, and broad topics' parts only
  name Top articles.
- So no long-tail article is a spread, and none has "leads here".
- **What its record carries:**

  | | English long tail (944k articles) | Spanish long tail (2.08M) |
  |---|---|---|
  | Subject | 83% | 83% |
  | Room | 78% | 70% |
  | Other-language title | 56% | 67% |

- **What today's topic page shows from that:**
  - room plates on the Connections map;
  - "Read in …", which Wikipedia online already gives through its language links.

  The subject and room appear nowhere else for a non-broad topic.
- **So as built, O6 adds little a reader would notice.** The value would need new UI on the topic page: the article's
  room (breadcrumb, "more in this room" from the Top index), or room lists from records.
