# Plan BK: the online bookshelf, and reading editions inside it

2026-11-20. Builds on KB2 (book packs, `.gbooks` manifests), O1–O6 (online by default; Saved for now; Keep; Local
only), O4's reading shelf (S1) and Plan RE (reading editions; branch `re-readable-editions`, f2a534ef).

Neal (2026-11-20): *"As we re-present those packs as book collections, I want to make it easier for users to explore
undownloaded packs, choosing what they want to save to their device following how Explore works now. In the bookshelf
area when connected to internet, everything available would be browseable, caching what you've opened along the way,
and offering the user the option of making packs or books within packs available offline."*

**Goal:**
- Online, the Library shows every book collection in the catalog, not just what's on the phone.
- Any book in any collection opens without downloading the pack. A 220 GB pack is as easy to try as a 30 MB one.
- What you open goes into Saved for now.
- You can Keep one book, or the whole pack, from where you are.
- Where Granger has made a reading edition of a book, that edition opens first.

**Not in BK:**
- Reading website packs online (Wiktionary, Stack Exchange, iFixit…). Their pages sit in compressed clusters, so they
  need a remote ZIM reader (§6, later).
- LibriVox and archive.org streaming.
- New reading editions beyond the two built so far. Those follow the rights pass (RE Task R2).

## 1. What the spike found (2026-11-20, on the Mac)

- **Every PDF in every book pack checked is stored uncompressed**: 52 of 52 in Knots, Food and Military Medicine
  (local files), and samples from Survivor Library, Algorithms and Milne (read remotely).
  - So a book is one contiguous byte range in the pack file.
  - Its offset and length can be worked out on the Mac from the ZIM's directory.
- **The Kiwix mirror honours range requests.** `download.kiwix.org` redirects to a mirror. A `Range:` GET for Knots (2)
  (590 KB at a known offset) returned 206, byte-identical to the local copy, in 2.0 s.
  - Two of the catalog's alternative mirrors 404 on `zim/other/` paths, so the app uses the primary URL and follows its
    redirect.
- **The book lists are already published before the pack.**
  - Each manifest pack's `.gbooks` sidecar has its own catalog URL, sha256 and size: Military Medicine 45 KB, Algorithms
    238 KB, Survivor Library 4.3 MB.
  - Nothing in the app fetches it before the pack yet.
- **Sizes:**
  - Survivor Library: 11,802 books, 220 GB, median book 9 MB, largest 851 MB.
  - Algorithms: 696 files, median under 1 MB.
  - Military Medicine: 24 books, median 2 MB.

## 2. How it works

### Browsing everything (the Library, online)

- The Library keeps O4's shelf on top: Reading now, your collections, Finished.
- Below it, when online, comes **Every collection**: the catalog's book collections in Explore's spread style.
  - **Grouped** by the existing kinds: Literature, Children, Manuals, Learning, More books.
  - **What's included:** Granger Classics, Gutenberg, African Storybook sets, Prunelle and every book pack.
  - **Each collection is a plate** with a fanned stack of covers (or title blocks), the provider, the book count and the
    mark (dot = on this phone, ring = some books saved for now, none = online).
- **A collection opens to the same Collection screen** as a downloaded one. Its sections and rows come from the
  manifest, fetched and checked against the catalog's sha256, then cached under Saved for now.
  - Rows carry marks.
  - The header carries **Keep the whole collection · 220 GB** (O3's `WouldTakeSheet`, with the room's size and free
    space).
- **Offline or in Local only:** collections you hold open as today. Others are greyed with "Needs internet", as
  Explore's plates are.

### Opening a book that isn't on the phone

- Tapping a book fetches it into Saved for now, then opens it in the reader.
  - **A manifest book** is one range GET: `pack URL`, `offset`, `length`, checked with the item's sha256.
  - **A Classics or Gutenberg book** uses O2's existing Read now.
  - **An ASB book** comes from its GitHub release, also through Read now.
- **Progress:** the reader shows a progress line with bytes, and can be cancelled. Above 50 MB, a sheet first says "This
  book is 120 MB" with Read now / Keep / Cancel.
- **Place and highlights carry over:** the book id is the one the pack would give (`book:kiwix:<zimName>/<key>`). If
  you later keep the pack, your place survives.

### Keeping

- **Keep this book** (reader ⋯, and on the row's long-press) moves the saved file into kept storage. The book then
  joins your shelf in its collection with the dot.
  - The pack itself isn't downloaded.
  - The collection shows on your shelf as "3 of 11,802 kept".
- **Keep the whole collection** is today's pack download. Books already kept are adopted, not fetched again.
- **Remove** on a kept book frees its file. Removing the pack keeps books you kept on their own.

### Reading editions inside a collection

- **A manifest item can name an edition**: an EPUB that Granger built, hosted on our GitHub release, with its own URL,
  sha256 and size.
- **The row** shows the edition's title and a small "Reading edition" line.
- **Opening it** gives the EPUB. The reader's ⋯ offers "Original scan", which is the range read from the pack.
- **Whole-work editions** can stand beside the fragments they replace:
  - rigging.epub: the whole FM 5-125, while the pack holds only chapter 2;
  - canning.epub: all eight USDA guides, while the pack holds only Guide 6.
  
  The fragment's row says "Part of the reading edition ›".

## 3. Builder's calls (for Neal's review)

1. **Kiwix mirror etiquette.**
   - Range-reading single books from `download.kiwix.org` uses their mirrors exactly as a download does, but for a
     fraction of the bytes.
   - I recommend a short note to Kiwix before release. It says what Granger does, the expected volume (one book per
     open; nothing prefetched) and that the User-Agent names Granger.
   - The spec's §10 question was about `browse.library.kiwix.org` (their web server), which we don't touch.
   - Build now; ship after the note, unless Neal decides otherwise.
2. **Manifests for every book pack.**
   - **The zimgit and nautilus packs** (Knots, Food, Water, Medicine, Post-disaster, USDA, Prunelle) carry their own
     lists inside the ZIM today, so they have no `.gbooks`. BK builds one for each on the Mac, so every book pack works
     online the same way.
   - **Gutenberg Kiwix packs** stay as they are. Their books already open online through gutenberg.org (O2), and the
     Gutenberg shelf is the way in.
3. **The manifest gains three columns** (`schemaVersion` 2, and old apps ignore unknown columns): `zim_offset`,
   `sha256` and `edition` (a JSON object with url, sha256, bytes and title, or null).
   - Each is computed from the published ZIM, which is named by date, so its offsets never move.
   - When a pack is refreshed, the upkeep loop rebuilds the manifest, as KB2 already does.
4. **Saved for now's cap**: books share O2's 300 MB cap, trimmed oldest first; a book you're reading isn't trimmed.
   - A book bigger than the cap opens, but is dropped when you close it unless you keep it. Its sheet says so.
5. **"Every collection" sits under your shelf**, not on a separate tab.
   - When the shelf is empty (a fresh install), Every collection is the whole screen.
   - Offline with nothing kept, it shows O3's offline line.
6. **Books kept from a pack without the pack** are a new state. They get a row in `books` with `origin KIWIX`, a file
   in kept storage, and `packId` of a pack that isn't installed.
   - Migration 18, adding `books.keptFile`.
   - This needs a message to the O7 session and d0 first: migration numbers are shared.
7. **Big-book sheet at 50 MB.**
8. **Reading editions are opt-out, not opt-in.** The edition opens first; "Original scan" is one tap away.
   - Editions ship only for titles the rights pass marks safe (US government, pre-1931 or open-licence).

## 4. Tasks

### Task 0: branch and coordination
- Branch `bk-online-bookshelf` from main, in worktree `.claude/worktrees/bk` (submodules, `local.properties`).
- Tell the O7 session and d0 about migration 18 and the Library files BK touches:
  - `library/*`
  - `sources/BooksScreen.kt`
  - `online/SavedStore.kt`
  - `online/Marks.kt`
  - the `.gbooks` reader in `core/library/ManifestBooks.kt`

### Task 1: manifests with ranges (tools)
- `tools/bookpacks/build.py`:
  - reads each PDF/EPUB's cluster and blob from the ZIM directory and writes `zim_offset`;
  - computes `sha256` from the bytes;
  - fails if an item sits in a compressed cluster, and lists it.
- New `.gbooks` for the list-carrying packs: zimgit ×5, usda-2015, prunelle ×3.
- Check: for 20 random items per pack, a remote range read equals the local bytes.
- Publish as a new sidecar release; then the catalog change (sidecars + `schemaVersion` 2), check-links, then Neal signs.

### Task 2: remote collections (core)
- `RemoteManifests`: fetch, check and cache a pack's `.gbooks` before the pack is installed. It goes through the shared
  OkHttp client, so Local only refuses it.
- `CollectionSource` = installed (DB) | remote (manifest), behind one interface the Collection screen reads.
- **Tests:** fake catalog + fake HTTP; sha mismatch refused; cache hit offline.

### Task 3: book range reads
- `PackBookFetcher`: a range GET with the redirect resolved once per pack and refreshed on 403/410 (as O6's
  RemoteRecords does), streamed to Saved for now, with a sha256 check, progress and cancel.
- Hooks into `readWithoutKeeping` for manifest books.
- **Tests:** range header, sha mismatch, cancel cleans up, redirect expiry.

### Task 4: Every collection
- Library sections under the shelf: plates by kind, marks, greyed offline.
- Collection screen from a remote source: Keep the whole collection (`WouldTakeSheet`), row marks.
- **Tests:** sections from a fake catalog; greying; empty shelf shows Every collection only.

### Task 5: Keep a book; adopt on pack keep
- Migration 18 (`books.keptFile`).
- Keep this book (reader ⋯, row long-press); "3 of 11,802 kept" on the shelf.
- When the pack is installed, `PackBooks.sync` adopts kept books (same id; the file is dropped or kept as the restore
  copy). Removing the pack spares kept books.
- **Tests:** migration; adopt; remove pack; remove book.

### Task 6: reading editions in manifests
- `edition` column read; the row line; open the edition; ⋯ "Original scan".
- Publish rigging.epub and canning.epub on a release once Neal OKs them. Attach them through the Knots and Food (and
  usda-2015) manifests.
- **Tests:** edition opens first; original scan path.

### Task 7: Pixel pass
- Knots online: open Knots (2), keep it, then keep the pack. Survivor Library: open a 9 MB book and an 850 MB one (the
  sheet). Local only. Spanish UI. TalkBack by tree.
- Check the phone's DB version and lastUpdateTime first, and tell d0 before installing.

### Task 8: merge
- Neal's call. Merging, pushing and signing are his.

## 5. Order

Task 1 (tools, no app risk) → 2 → 3 → 4 → 5 → 6 → 7 → 8. Tasks 1–3 can start before the design round on Every
collection's look.

## 6. Later: website packs online

- A remote ZIM reader needs:
  - the directory, by binary search over the path pointer list (about 17 small range reads for 100k entries, or one
    Mac-built index sidecar);
  - one compressed cluster per page (≈0.3–1 MB, zstd);
  - pictures from the same or nearby clusters.
- This is feasible, but it's a bigger piece. It also raises the Kiwix question more sharply, since it would read whole
  sites page by page.
