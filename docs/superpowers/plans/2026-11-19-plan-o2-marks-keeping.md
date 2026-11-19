# Plan O2: marks and keeping

2026-11-19 · spec `docs/superpowers/specs/2026-11-18-online-offline-design.md` (§3, §4 "which copy opens", §5, §9 Offline
pictures) · builds on O1 (`docs/superpowers/plans/2026-11-19-plan-o1-online-sources.md`, merged and pushed) · canvas
https://claude.ai/artifact/SXH5nLhtUHj5L8DwzwesWX (topic page with marks and Keep)

**Goal:** you can see what's offline and choose what stays.
- Every article, plate, search result and book shows whether it's **on this phone** or **saved for now**.
- One tap keeps an online article, a room's articles, a book or a pack.
- What you read online is saved for now, so going Back, a train tunnel or Local only still shows it.

**Not in O2:**
- The Local only switch, the banner and the grey-outs (O3).
- Outfitting and its counts (O4).
- First run and the rest of Settings → Online (O5).

Everything new rides behind `Features.ONLINE`, as O1 does, apart from the copy rename in Task 7.

## Builder's calls (for Neal's review)

1. **Marks:** a filled dot means on this phone; a ring means saved for now. Online has no mark.
   - The spec's table gives online a cloud, but its text, and the call you approved, say online is unmarked. This
     follows the text.
   - The marks are 12 dp, in Granger's accent colour, and each has a TalkBack label.
2. **Saved for now replaces O1's 50 MB HTTP cache.**
   - It lives in the app's files, not its cache folder, so Android doesn't clear it behind your back.
   - The cap is 300 MB and the oldest-opened goes first.
   - It holds online articles (their prepared page and pictures), summaries and pictures, and books opened without
     keeping (Task 8).
   - While online, a saved page under an hour old opens from it at once, so Back is instant. An older one is fetched
     fresh and saved again.
3. **Kept articles live in their own store, not in a pack.**
   - Each is a Room row, `kept_articles`, plus a folder of files.
   - This is DB migration **16 → 17**, the first schema change since KB2.
   - A kept article is served by the same online source (`wikipedia_<lang>_online`), so its tab, history and links don't
     change when it's kept.
4. **What a kept article counts as:**
   - kept with pictures, it's a full copy, so it beats online (spec §4);
   - kept as text only, it's a shorter copy, so online wins when online, and "On this phone (shorter)" shows.
5. **Offline pictures** (your setting): *Save pictures* (the default) or *Text only*. It applies to both Keep and Saved
   for now.
   - Its row, and Saved for now's cap and Clear, go in a small **Settings → Online** section now.
   - O5 adds the rest of that section: Use the internet, Never go online, Local only, Wi-Fi.
6. **Keeping a room** uses a background job (WorkManager, like downloads).
   - Before keeping, it shows the count and an estimated size (from the summaries).
   - Each article is kept in turn; the job resumes after a restart and pauses in Local only.
   - When a Kiwix subject pack covers the room (Medicine, for example), the sheet offers that pack first, with its size.
7. **"Download" becomes "Keep"** in the UI, for books and packs (spec §5). The downloads system underneath doesn't change.
8. **Books open without keeping.**
   - Opening a Classics or Gutenberg book you don't have fetches its EPUB into Saved for now and opens it. Books are
     small: the Classics median is 0.22 MB (622 books).
   - Keep moves it onto the shelf without fetching again.
   - Archive.org and LibriVox stay as they are, download only (streaming is later).

## Tasks

### Task 0: branch

Branch `o2-marks-keeping` from main (`c44b858c`), in worktree `.claude/worktrees/o2`. Then:
- `git submodule update --init`, plus the nested taglib `utfcpp` init (O1 found the worktree won't build without it);
- copy `local.properties`.

### Task 1: Saved for now

- **`SavedStore`** (`app/granger/online/SavedStore.kt`):
  - files under `filesDir/saved/`, and a small SQLite index: key, kind, bytes, `openedAt`, `savedAt`, revision;
  - kinds are page, picture, summary and book;
  - `put` / `get` / `touch` / `trim(capBytes)` / `clear()` / `size()`;
  - trimming removes the oldest-opened until the store is under the cap.
- **`OnlineWikipediaArchive.read`** order:
  1. kept (Task 3);
  2. saved and under an hour old;
  3. online, which saves what it fetches;
  4. saved of any age;
  5. a kept shorter copy in a pack (O1's fallback).

  Pictures follow the same order, and summaries are saved too. Under Offline pictures = Text only, pictures aren't saved.
- **Remove** O1's OkHttp cache on `wikimedia`. Delete its folder once, on upgrade.
- **Availability:** `Saved` is now answered, from the store's index (a single lookup, safe for a screen of plates).
- **Tests** (store, a fake clock):
  - put, get, touch;
  - trim keeps the newest under the cap;
  - Text only saves no pictures;
  - the read order above, with a gate that refuses (saved of any age is served).

### Task 2: Offline pictures and Settings → Online (first rows)

- `OnlinePrefs` holds `offlinePictures` (SAVE / TEXT_ONLY) and `savedCapMb` (300).
- A **Settings → Online** section (strings in English and Spanish) with three rows:
  - Offline pictures, with its two choices and a one-line explanation;
  - Saved for now, showing its size against its cap, with a cap choice of 100 / 300 / 1000 MB;
  - Clear saved.
- It shows only while `Features.ONLINE` is on.
- **Tests:** prefs default to Save pictures and 300 MB; Clear empties the store.

### Task 3: kept articles

- **Room:**
  - `kept_articles(lang, title, qid, path, bytes, withPictures, keptAt, revision)`, keyed by (lang, title);
  - a `KeptArticlesDao`;
  - **migration 16 → 17**, with a migration test from a version 16 database.
- **`KeptStore`:**
  - files under `filesDir/kept/<lang>/<sha1(title)>/`, holding `page.html` and the pictures by their `_img/` path;
  - `keep(lang, title)` takes the page and pictures from Saved for now when they're there, and fetches what's missing.
    Under Text only it keeps no pictures;
  - `remove`, `has`, `list`.
- **Which copy opens:** `ArticleCopies` counts a kept article as a copy, FULL with pictures and SHORT without.
  `Availability` answers `OnPhone(full)` for it.
- **Revision:** a kept article remembers its revision (from the summary) for O4's update rows.
- **Tests:**
  - keep from saved makes no request;
  - keep when nothing is saved fetches the page and its pictures;
  - Text only keeps no pictures;
  - a kept full copy is served with the gate open; a kept shorter copy loses to online and wins in Local only;
  - the migration.

### Task 4: marks

- **`AvailabilityMark`** composable (`app/granger/online/Marks.kt`): a filled dot or a ring, 12 dp, with TalkBack labels
  "On this phone", "Saved for now" and "On this phone (shorter)". It never draws for Online or Nowhere.
- **Where:**
  - the topic page header (beside the title);
  - Explore plates (corner) and rows;
  - the room and subject spreads;
  - Search rows (titles, articles, Best matches);
  - Home's Continue cards;
  - the Timeline's card;
  - rail tabs (a small dot on the tab's badge).
- **Availability by the screenful:** `ArticleAvailability.of(list)` answers each spread once and caches it. Pack copies
  come from the indexes; kept and saved articles from their indexes.
- **The shorter case:** the header says "On this phone (shorter)" and, when online, offers "Full article online".
- **Tests:**
  - each state's mark and label;
  - Online draws nothing;
  - a Compose test (Robolectric, as the Explore tests do) of a plate in each state: the mark's node and its label.

### Task 5: Keep an article

- **The topic page header gets a Keep button**, beside Read the article and Connections:
  - it shows the size for the setting in force ("Keep · 180 KB", or "Keep text · 40 KB");
  - once kept it becomes "Kept ✓", with Remove in its menu.
- **The reader's ⋮ menu** gets Keep / Remove too.
- **A saved article** keeps without fetching again (Task 3).
- **Sizes:** they come from what's saved, or are estimated from the page's length and its picture count before it's
  fetched.
- **Tests:** the button's states, Remove, and the size label for each setting.

### Task 6: Keep a room or a subject

- **Explore's room and subject pages get "Keep this room"**, which opens a sheet with two choices:
  - **A pack that covers it:** when the catalog has a Kiwix pack whose subject matches (the catalog's `subjects`, and
    the WF subject add-ons), the pack is offered first: "Medicine · WikiMed, 2.0 GB".
  - **The room's articles:** "Keep its 60 best-known articles · about 14 MB". The count comes from the room lists; the
    size is estimated from the batched summaries' extract lengths and a mean picture size.
- **`KeepWorker`** (WorkManager) keeps the list one article at a time:
  - its progress shows in Downloads as a group, "Keeping Rivers · 23 of 60";
  - it waits while the gate is shut;
  - its group can be cancelled.
- **Tests:** the sheet's choices from a fake catalog; the worker keeps, resumes after a restart, and waits in Local only.

### Task 7: "Download" becomes "Keep"

- Copy only, in English and Spanish, across:
  - Get more;
  - the source screens (Gutenberg, Classics, archive.org);
  - Downloads;
  - book details.
- Examples:
  - "Download" becomes "Keep".
  - "Downloaded" becomes "On this phone".
  - "Remove download" becomes "Remove from phone".
- The download machinery and its records don't change.
- `HardcodedStringsTest` and the Spanish string tests keep it honest. List every changed string in the commit.

### Task 8: books without keeping

- **Opening a book you don't have:** a Classics or Gutenberg book from Explore's In your books, Get books or a link card.
  1. Its EPUB is fetched into Saved for now (the catalog's URL, through the shared client).
  2. It opens in the e-book reader as a book that's saved for now.
  3. A Keep button in the book's top bar moves the file onto the shelf as a normal installed book: a download record
     with the right hash, so the library treats it as if downloaded.
- **Its mark** is the ring. Reading position and highlights stay with it when it's kept.
- **Not covered:** archive.org and LibriVox, which stay download only.
- **Tests:**
  - open-without-keep makes no download record;
  - Keep makes one with the catalog's hash and moves the file;
  - a book trimmed out of Saved for now asks again when opened.

### Task 9: from O1's tail

- **Search:** online titles from two languages are interleaved, so Spanish isn't hidden behind Show all.
- **Home's feeds** (On this day, Random from a subject) can draw from Wikipedia online when no pack covers them.
- **TalkBack:** checked on the marks and on online pages.

### Task 10: Pixel 6 pass

- **Setup:**
  - install with `adb -s` on the Pixel only, checking `lastUpdateTime` and the DB version first;
  - this build moves the database to **17**, so from then on nothing below O2 may be installed on the phone;
  - use the O1 debug preview (no Wikipedia on the phone).
- **Read and keep:**
  - Read online, go Back: instant.
  - Keep an article with pictures, then as text only: check the marks and sizes.
  - Turn on Local only (debug): kept and saved articles open, and online ones say Needs internet.
- **Keep a room:** watch its group in Downloads, restart the app mid-way, and it resumes.
- **Books:** open a Classics book without keeping it, then Keep it.
- **Settings → Online:** the size moves as you read; Clear empties it.
- **Across languages and themes:** Spanish, Night and Field.
- **Restore the phone afterwards.**

### Task 11: merge

- Run the full suite, then merge to main as a fast-forward.
- Update the roadmap.
- Push only when Neal asks.

## Order

1. Tasks 1–3 first (the stores and the migration).
2. Then 4–5, the visible part.
3. 6 and 8 are independent and can run in either order.
4. 7 is a copy pass at any point.
5. 9 is small.
6. Then the Pixel pass.

## Results (2026-11-19, branch `o2-marks-keeping`)

Tasks 1–8 built with tests, Task 9's search interleave in; commits 2bd8be3b → the Pixel fixes. Full suite green.

**Pixel 6 pass** (debug 1518+, DB migrated 16 → 17 on first open, no crash; preview: no Wikipedia on the phone):

| Check | Result |
|---|---|
| Saved for now | an article read online gets the ring; with Local only on it opens from the phone with the pictures that were shown |
| Keep (topic page) | "Keep · 556 KB" once saved; Kept → filled dot, "Kept · Remove"; opens with pictures in Local only |
| Marks | dot (kept) and ring (saved) on topic headers, Continue cards and room plates; online unmarked |
| Settings → Online | "8 of 300 MB used", the cap's choices, Clear |
| Keep this room | sheet with a covering pack and the 60 best-known articles; "Keeping · n of 60" with Cancel; 26 kept in about 40 s; Cancel stops it |

**Fixed in the pass:** pictures kept 4 at a time (a long article took 13 s one by one); a page saved for now refreshes the
marks and the Keep size; the room estimate is now 1.9 MB an article with pictures, 250 KB as text (it was 5× low); the
covering pack is a Wikipedia subject pack first (WikiMed, not NHS Medicines A to Z); a debug `--es kept_clear all`.

**Deviations from the plan:**
- A room's progress shows on the room page, not in Downloads.
- Keep on a book read without keeping is the ordinary download (fetched again, ~0.22 MB), taking over the same book id.

**Not checked on the phone:** Read now for books (every catalog book is already on Neal's Pixel; covered by unit tests),
Spanish UI, TalkBack by ear. Home feeds still don't draw from Wikipedia online (Task 9, left for O3/O4).

**Noticed:** an article in a language that isn't a knowledge language (Alhambra, es) shows "Removed pack" in Continue;
the Home downloads card says "0 of 0" for the online index set.
