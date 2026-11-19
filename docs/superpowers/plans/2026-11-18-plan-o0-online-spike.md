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

## Task 4: online article in the reader (Pixel 6) — NEXT

Debug-only: open an article by title from Wikimedia (Parsoid HTML + the Task 1 transform) in `ReaderWebView` through
`ZimWebViewClient`'s interceptor, pictures from `upload.wikimedia.org`. Check: Contents (sections), read aloud (blocks,
highlight), links open the next article online, pictures load, Night/Field styles, the topic page in front. Measure on
Wi-Fi: summary, HTML fetch, first paint.

## Task 5: one network gate (Pixel 6) — after Task 4

An OkHttp interceptor on the shared client that refuses every request when a debug Local only flag is on; check that
nothing else in the app opens its own connection (WebView loads go through the interceptor in Task 4; libzim, the
catalog, downloads, archive.org, LibriVox). List anything that bypasses the client.

## Bar (spec §6)

Topic page header ≤ 1 s and Related ≤ 3 s on the Pixel 6 over Wi-Fi.
