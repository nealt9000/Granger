# Plan O0: the online spike

2026-11-18 · spec `docs/superpowers/specs/2026-11-18-online-offline-design.md` (§6, §10, §12) · desk scripts in
`tools/online-spike/` (venvs `granger-online` for apsw, `granger-pack` for libzim/bs4)

Four questions, each with a go/no-go: (1) can the reader show Wikimedia's HTML, (2) can the big indexes be read without
downloading them, (3) how far has live Wikipedia drifted from the index's snapshot, (4) does one network gate make Local
only honest. Desk first, then the Pixel 6.

## Task 1: Wikimedia HTML vs the reader (desk) — DONE 2026-11-18

`html_shape.py` compares a ZIM's article with Wikimedia's mobile-html and Parsoid HTML, counting what the reader reads
(`ArticleText.BLOCKS` minus `NOISE`, sections, `<details>`, pictures, links, scripts).

| Moon | ZIM (astronomy nopic, 2026-08) | mobile-html | Parsoid |
|---|---|---|---|
| blocks / words | 687 / 31,665 | 701 / 30,821 | 699 / 30,942 |
| sections | 59 | 60 | 60 |
| pictures (`img[src]`) | 6 (nopic) | 17 + 50 lazy placeholders | 78 |
| scripts | 1 | 5 | 0 |
| bytes | 1.16 MB | 0.98 MB | 1.89 MB (gzip on the wire) |

Photosynthesis: 324 / 310 / 308 blocks, 33 sections everywhere.

- **Parsoid HTML (`/w/rest.php/v1/page/{title}/html`) is the pick**: same structure as mwoffliner's output, no scripts,
  real `<img>` tags. mobile-html prepends the short description as a block and hides pictures behind placeholders a
  script fills. Parsoid is also Wikimedia's long-term endpoint.
- **Transform needed (small)**: links are `./Title` (ZIM: `Title`); picture `src` is protocol-relative
  `//upload.wikimedia.org/…`; add Granger's article stylesheet (the ZIM pages bring mwoffliner's); drop `<base>`, edit
  links and `<link rel=mw-…>`. The reader's scripts are generic (blocks, `details`, `a[href]`), so they should run as is:
  checked on the phone in Task 4.

## Task 2: the big indexes without downloading (desk) — DONE 2026-11-18, design changed

Range reads on GitHub release assets work (`206`, `content-range` against the 142 MB Top 1M .gqid): 0.5–0.65 s through
the `github.com` redirect, 0.08–0.2 s on the redirected `release-assets.githubusercontent.com` URL (the app resolves the
redirect once and reuses it while it's valid).

**2a. SQLite over HTTP (`remote_pages.py`)**: replays the topic page's queries through an apsw VFS that counts page reads.
- A plain article (the long tail): 8–12 reads cold, measured at ~80 ms each over the network, so ~1 s. Fine.
- A broad topic: resolving 40 items is ~80 sequential reads cold (B-tree descents can't run in parallel inside SQLite).
- `holders`, `heldBy` (no index on `topic_parts.item`) and `topInFacet` / `broadIn` (sort a whole room by rank) scan
  2–36 MB each: unusable remotely.
- And Android's framework SQLite has no custom VFS from Java: it would mean bundling SQLite natively with a JNI VFS.
- **No-go as the design.**

**2b. Topic records (`records.py`)**: pre-join everything a page needs per article (path, rank, subjects, rooms, coords,
dated links, broad-topic parts with every item already resolved, the top 8 topics that lead to it + their count) into one
zlib JSON record, sorted by QID, in blocks of 128 with a small header. A 93 KB directory (first QID + offset per block)
is fetched once and cached.

| Top 1M (en) | |
|---|---|
| records | 994,325 |
| `.grec` file | 86.4 MB (the `.gqid` is 142.5 MB) |
| directory | 93 KB |
| record | median 70 B, p99 187 B; broad topics median 2.7 KB, max 5.9 KB |
| a block (128 records + header) | ~10 KB typical, so **one range read per page** |
| room lists (108, top 60 each) | 98 KB compressed in all: fetch whole |
| build | 14 s at the desk |

- **Go.** A topic page online costs one range read (~0.1–0.2 s) after the directory, and every scan becomes a lookup
  because it was done at build time. Plain HTTP, no native code. It is a build step beside `tools/qidindex` (O6), not a
  new runtime.
- The small Top indexes are still fetched whole on first run (spec §6 Phase 1): Explore's rooms, A–Z and Timeline use
  queries the records don't carry, and 17 MB is cheap.
- **Spec change**: §6 Phase 2 becomes "topic records", not "SQLite over HTTP".

## Task 3: revision drift (desk) — DONE 2026-11-18

`drift.py` on 40 random broad topics from the en Top index (built from 2026-09-14), against live Parsoid HTML:
- **Sections still there by anchor: 264/268 (99 %).**
- Figures found in the live article by file name: 186/253 (74 %). But the figure doesn't have to be *in* the article:
  online, a figure's picture comes from Commons by its file name (`Special:FilePath/{name}?width=…`) and its caption
  from the index. With mwoffliner's renames undone (`.svg.png` → `.svg`, names decoded twice), **40/40 sampled figure
  files resolve on Commons.**
- Leads move (Moon's first sentence differs from the snapshot): online, the header's gist comes from Wikimedia's
  summary, not the index.
- **Go**: the index stays the source for structure (sections, kinds, people, rooms); live Wikimedia for text and pictures.

## Task 4: online article in the reader (Pixel 6) — DONE 2026-11-18, go

Branch `o0-online-spike` (debug only): `app/.../online/OnlineWikipedia.kt` implements `ZimArchive` from Wikimedia
(Parsoid HTML + the Task 1 transform; pictures rewritten to `_img/<host>/…` so the WebView asks the reader's interceptor);
`OnlineLibrary` returns it for the pack id `wikipedia_en_online`; `adb shell am start -n app.granger/.MainActivity --es
online_title Moon` opens a title. Debug 1498 on the Pixel 6 (Wi-Fi).

- **The reader ran unchanged**: the topic page in front (live gist, "c. 4.5 billion years ago" from the time index by
  title), Read the full article, the infobox, pictures, Contents (all ten sections), a jump to a section, and a link
  (Moon → Knowth) opening the next article online.
- Timings: Moon (1.8 MB Parsoid) fetch 0.38–0.47 s + transform 0.47–0.6 s warm (3.9 s the first time after an install);
  Knowth (116 KB) fetch 0.26 s + transform 0.06 s.
- Wikimedia now serves thumbnails from `thumb.wikimedia.org` with tracking parameters; the rewrite takes any
  `*.wikimedia.org` host and drops the query.
- **For O1** (found here):
  1. The reader's link check (`ZimResolver.linkAction`) reads the entry on the main thread: online, that's a network call
     Android refuses (`NetworkOnMainThreadException`, caught; the page still loaded). An online archive must answer it
     without fetching.
  2. Online articles show as "Removed pack" in Home's Continue and keep a rail tab: online needs to be a source the
     library knows, not a missing pack.
  3. The topic page fetched the whole article twice for its gist: use the summary endpoint (one 3 KB call, ~0.4 s).
  4. No Wikipedia styling and no Night darkening on online pages: ship an article stylesheet in `assets/reader/`.
  5. Transform cost on big pages: trim (references, navboxes) before parsing, or parse once and cache.
- Not tested: read aloud (left alone so as not to touch the audiobook paused in the miniplayer). It reads the same blocks
  Contents does, so expected to work; check in O1.

## Task 5: one network gate (Pixel 6) — DONE 2026-11-18, go

`online/NetworkGate.kt`: an application interceptor on the shared OkHttp client that throws `LocalOnly` when a flag is
on (`--es local_only on|off` in debug). The online archive now uses the shared client.
- On the phone: with Local only on, opening Mars and the Knowth tab were refused at the client ("Local only: not asking
  en.wikipedia.org"), before any connection.
- Inventory of the app's network paths: **one shared `OkHttpClient`** (catalog, `FileDownloader`, archive.org, LibriVox,
  now online Wikipedia); no `HttpURLConnection`/`openStream`; no image loader library; the only WebView client
  (`ZimWebViewClient`) already blocks everything it doesn't serve. The player plays local files only today: when
  LibriVox streaming comes, it must use an OkHttp data source on the shared client, or it bypasses the gate. LiteRT
  models are downloaded by `FileDownloader`, not natively.
- **Go**: Local only is honest by construction with this one interceptor.

## Verdict

All four questions go. Changes to the spec: §6 Phase 2 is topic records (Task 2); the reader needs the five O1 items
above. O1 can start: online sources on the shared client + gate, Parsoid transform, summary header, online as a known
source, the small index set on first run.

## Bar (spec §6)

Topic page header ≤ 1 s and Related ≤ 3 s on the Pixel 6 over Wi-Fi.
