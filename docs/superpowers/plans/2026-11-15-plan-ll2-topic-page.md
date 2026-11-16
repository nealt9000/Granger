# Plan LL2: The topic page

**Goal:** a tap on a Wikipedia article opens a Granger **topic page** in front of the ZIM's own page. It shows the subject at a glance (picture, lead, dates), then ways into it: its time, the reader's books that name it, the closest topics across the library, and the other language. "Read the full article" opens the page itself. A connections map opens from it. This is where Granger stops reading like Kiwix.

**Why it changed (Neal, 2026-11-16):** the first LL2 hung these pieces on the article: a glance strip, plus Related in the LL1 footer. On a long page nobody reaches the footer, and nobody comparing Granger with Kiwix would find features there. The connections should be a way of looking at the subject, not an appendix to it.

**The tester build waits for LL2** (Neal, 2026-11-16: "present a clear intuitive experience for testers").

**Spec:** `../specs/2026-11-11-linked-library-design.md` §3.2, §4 and §5. Granger Music (§3.7) is a plan of its own; the topic page leaves a slot for recordings.

**Mockups (real data from the Pixel):** https://claude.ai/artifact/HG4yrYyYPFssmRvsYscdvq
- Frames: A, the overview scroll (Napoleon); B, tiles; C, the connections map; A on a thin page (Photosynthesis); A in Spanish (Zaragoza).
- **Neal chose A as the page and C as a view opened from it**, 2026-11-16.

## What it builds on
- **The web reader** (`reader/ReaderScreen.kt`, `ReaderViewModel.kt`, `ReaderWebView.kt`): tabs, bars, Show in time (`isDated` / `onShowInTime`), and LL1's footer card and sheet (`ArticleFooterViews.kt`).
- **The time index** (`core/library/TimeIndex.kt`): `TimeIndexes.find(pack, path)` gives the page's `TimeMark`; `marks`, `top` and `around` give the people and events around it. Rows carry latitude and longitude too, for the map later (LL6).
- **The QID index** (`QidIndexes`): `qidOf`, `otherTitle`, `articlesOf(qid, language)`.
- **The mention index** (`MentionIndex`, LL1): which books name a QID, and the passages.
- **Vectors** (`core/vectors`): Related from the shipped `.gvec` files, proven on the Pixel in Task 1 (`VectorFile.rowsOf`, `copyRow`).
- **Wander and the Timeline** on Home, and the Games: the topic page links into them.

## Decisions
1. **The topic page is the default way into a Wikipedia article.**
   - A tap from search, a link card, Home, Wander or the Timeline opens it. A link inside an article's text opens the page directly, so reading isn't interrupted.
   - "Read the full article" opens the ZIM page in the same tab, and Back returns to the topic page.
   - A setting, "Open articles at", offers Topic page (default) or Full article. Neal said yes to the default, 2026-11-16.
2. **Layout A**, the overview scroll, top to bottom. Each section shows only when it has something; nothing empty is drawn.
   - **Header:** the page's own picture, title, a one-line description, and the lead's first paragraph, all from the ZIM. For dated pages, a lifespan or span bar from the time index.
   - **Two buttons:** *Read the full article*, and *Leer en español* / *Read in English* (only when a pack in that language has the item).
   - **His time / Su época** (dated pages): bars for up to 8 people whose adult lives overlapped, by rank, plus up to 6 events in the span (time-index kinds 1 and 2, by rank). Open in Timeline.
   - **In your books:** the count of books and passages, and three real quotes with covers (MentionIndex); each opens the book at the passage.
   - **Closest topics:** 6 articles with picture and first sentence, then up to 3 books near in meaning (Task 1's search).
   - **More on this subject:** shown when the page is thin and a pack in the catalog would fill it (Decision 4).
   - **Wander from here** and **Quiz me on this**.
3. **C, Explore connections,** is a full-screen map opened from the topic page:
   - the subject in the middle, with its closest people, events, places and books around it, and filters for each;
   - a tap re-centres the map on that node, and a long press opens its topic page;
   - a node's kind comes from the time index kind or class, or "book".
4. **What the topic page needs from the library** (Neal's question, 2026-11-16):
   - **The minimum** is one Wikipedia in the reader's language, Top or larger, with its time index, QID index and vectors. Setup always includes it.
   - **Everything else adds itself** through three keys: the Wikidata ID (books, other-language packs, subject packs), vectors (any pack or book that has them, including books the reader adds) and the time index.
   - **"More on this subject"** needs a small catalog-level file mapping each Wikidata item to the subject packs that hold it, built on the Mac from the `.gqid` files we already make. It's the one new sidecar in LL2.
5. **The LL1 footer card** stays on the full article for now, and folds into the topic page's "In your books" once LL2 lands. The glance strip and the footer Related section from the first LL2 are dropped.
6. **Flags:** `Features.TOPIC_PAGE` and `TOPIC_MAP`. Debug turns them on as they land; the tester build turns both on with `LINKED_CLASSICS`.
7. **Spanish from day one:** every label in both languages, and dates in the app's locale ("a. C." in Spanish).
8. **Web highlights move out of LL2,** to LL3 with the Notebook. They don't depend on the topic page and shouldn't hold it up. The `article_marks` design stays as written in the spec (migration 15 → 16).

## The online layer (Neal, 2026-11-16): direction set, built after Task 6 as LL2b
When a topic leads to an article the phone lacks, it reads that article online, clearly badged, with "Keep offline: <pack>, <size>". The reader keeps full control over what's stored.
- **Text from Wikipedia itself, packs from Kiwix** (Neal):
  - Online articles come from Wikipedia's own API (page HTML and summaries), found by Wikidata ID → sitelink title. It's the real source, and it's built for internet-scale use.
  - Kiwix stays the source of downloads.
  - Wikimedia asks API clients for a User-Agent with contact details. Use the project's own name and address (opengranger.org), never anything about the reader.
- **Indexes local, text online.** The topic page's richness comes from our indexes, so they can be a separate download, ahead of the ZIM:
  - per subject pack: time index 0.6–2.3 MB, QID index 10–30 MB, one vector per article at about 256 bytes;
  - Top 1M: QID index 94 MB, plus about 130–260 MB of vectors.
- **The scope** is Top 1M plus the subject packs (Neal: "totally fine"). Full English Wikipedia stays out: its QID index alone is 580 MB, and it has no vectors.
- **Asked once, not switched on quietly.** It also doubles as a **demo of the tiers**: a reader can try the experience at Starter, Top 1M or a subject pack before committing gigabytes (Neal).
- **Privacy is chosen in onboarding,** by intended use (for example "fully offline" against "online when it helps"). Whenever the app goes online it takes the pragmatic steps:
  - no identifiers and no cookies;
  - only the request the reader asked for;
  - the reader can see when something came from the internet.
- **Before building:** a spike covering Wikipedia API fetch and render in the reader, the title mapping, rate limits and etiquette, plus the onboarding question and site copy.

## Tasks
### Task 1: Related spike (phone, measured) — done, GO
- **Finding the article's rows.**
  - Check whether `.gvec` keys are sorted, so a binary search works.
  - If they aren't, build a small key → row-range map per file on first use, cached like the mention index, and measure its build time and size for Top (en, es).
- **The query.** Average the article's rows, then run `VectorSearch.top` with SQ's probes over all files, k = 48. Group the hits by article or book, keep the best 12, and drop the page itself and its redirects (same QID).
- **Measure on the Pixel:** cold and warm time for 10 articles (London, Napoleon, Photosynthesis, Jane Austen, Don Quijote, Zaragoza, Insulin, Byzantine Empire, Jazz, Volcano), and the share of the top 6 a reader would call related.
- **Go:** under 1.5 s warm and at least 4 of 6 related on 8 of the 10. Otherwise fall back to `.grel` (Decision 2).
- Written up in this plan's Task 1 notes. Neal sees the 10 lists before the UI is built.

#### Task 1 notes (2026-11-15): GO on speed and quality, pending Neal's read of the lists
- **Method:** prototyped on the Mac (`tools/vectors/related_spike.py`) with the Pixel's own files, then run on the Pixel 6 (debug `RelatedSpikeActivity`, which logs `Related:` lines). The Pixel had Top en and es, Medicine, the two cooking packs and 622 books. The two runs gave the same lists.
- **Finding the rows:** keys aren't sorted (version 2 files group rows by list), but no map is needed. `VectorFile.rowsOf(key)` compares bytes in one pass over the keys: **27 ms** for Top en (567k rows). Decoding every key as a string took 550 ms, and a full key → rows map 2.1–3.8 s, so neither is used. `copyRow` reads a row back.
- **The query and search:** the article's rows averaged (8–12 chunks, the lead), then two searches run at once: the page language's Wikipedia files (k 128, probes 1024) and its books (k 64, scanned whole, as books are version 1).
- **What changed from the plan's first shape:**
  - k 48 over everything left room for only 2–3 results: the page's own chunks, its copy in another pack (Photosynthesis is in Top and Medicine) and its other-language article took most of the hits. Hence a larger k.
  - Articles and books are searched separately, because on Don Quixote the book took 234 of 256 hits.
  - **Results stay in the page's language:** the other-language article is the Read-in button, not Related.
  - Same title in two packs is shown once.
  - Books need a score of at least **0.79**: science pages otherwise got Metamorphoses and the Bible at about 0.75.
  - Shown: up to 8 articles and 4 books, mixed by score.
- **Speed on the Pixel:** warm **0.70–0.96 s** for all 10 (bar 1.5 s). The first search after the files open took 1.8 s. Opening 627 files cold took 3–5 s; the app already opens them for search, and Related should reuse that.
- **Quality (my count of related in the top 6):** London 4, Napoleon 5, Photosynthesis 6, Jane Austen 5, Don Quixote 6, Zaragoza 5, Insulin 6, Byzantine Empire 6, Jazz 6, Volcano 6. All 10 meet the bar of 4. The weak spots:
  - London, Ontario and Æthelred under London.
  - Zamora and Zagreb under Zaragoza: near in the alphabet and in the vectors, not in meaning.
  - Caviar under Don Quixote, at #8.
  - Books that share a mood rather than a subject (Babbitt for Jazz, Main Street for Jane Austen).
- **Still open (now in Task 2):**
  - Drop redirects and other packs' copies by QID rather than by title.
  - Land a book at its best passage, not at the colophon (Spanish Don Quijote's best hit is `colophon.xhtml`).
  - Show titles, not paths.


### Task 2: The topic page's data (phone, tested)
- **`TopicSource`** turns (pack, path) into a `Topic`:
  - the header: picture, title, description and lead, read from the ZIM page (the first real paragraph, with IPA, references and inline styles stripped);
  - its `TimeMark`, contemporaries and events;
  - the other-language item;
  - mentions (count, and 3 quotes chosen at 60–320 characters);
  - Related (Task 1, with QID dedupe instead of titles).
- It runs on IO. Each section arrives as it's ready, header first, and each is cached per page.
- **Tests:**
  - the lead is cleaned;
  - contemporaries overlap and are ordered by rank;
  - events fall in the span;
  - a page without a mark has no time section;
  - the other language shows only when installed;
  - Related drops the page itself and its other-pack copies by QID, and keeps the book floor;
  - Spanish dates.

### Task 3: The topic page screen (phone, tested)
- Layout A as in the mockup and Decision 2, in Compose, with the app's Field and Night palettes and Spectral.
- **Navigation:**
  - the Decision 1 entry points open it;
  - Read the full article opens the page in the same tab, and Back returns;
  - quotes open the book at the passage;
  - topics, people and events open their own topic pages in a new tab;
  - Open in Timeline.
- The "Open articles at" setting.
- **Tests:** sections hidden when empty, navigation, the setting, and the flag off meaning the full article opens.

### Task 4: More on this subject (Mac, then phone)
- Build the item → subject packs file on the Mac from the `.gqid` files, publish it as a catalog sidecar, and add its catalog field. **Neal signs.**
- On the phone: a thin page (no time section, under 3 book quotes or under 4 related) shows the pack that holds the most of its neighbours, with size and Get.
- **Tests:** the pick, and nothing shown when the pack is installed or none fits.

### Task 5: Explore connections (phone, tested)
- The map (C) from the same `Topic`: up to 10 nodes in two rings, filters, re-centring, long press to open.
- **Tests:** node kinds, filters and re-centring.

### Task 6: Walkthrough and checkpoint (emulator, then the Pixel)
- **English:** Napoleon, London, Photosynthesis (thin), Jane Austen (Read in Spanish), Jazz.
- **Spanish:** Cervantes, Zaragoza.
- Timings on the Pixel: header under 300 ms, the whole page under 1.5 s warm.
- **LL2 checkpoint with Neal,** then merge, push, and the tester build (flags on, `LINKED_CLASSICS` on in release).

## Order and size
Tasks 2 → 3 first; they give a usable page. Then Task 5, the map, and Task 4, which needs a catalog change and Neal's signature. About four sessions. The online layer (LL2b) follows Task 6.

## Not in LL2
Web highlights (moved to LL3); the online layer (LL2b, above); Granger Music (its own plan); the Notebook, clips and export (LL3); Show on the map (LL6); tablet two-pane layouts.
