# Plan LL2: The article, enriched

**Goal:** an article in the web reader stops reading as it does in Kiwix. Around the ZIM's own page it gains *At a glance* (its place in time), *Related across your library* (articles, book passages and recordings near it in meaning), a link to the same article in the other language, and highlights that work as they do in books. After LL2 is pushed, the next Play build goes to testers with LL1 and LL2 on (Neal, 2026-11-15).

**Spec:** `../specs/2026-11-11-linked-library-design.md` §3.2, §4 and §5. Granger Music (§3.7) is a plan of its own (Neal, 2026-11-15); Related shows recordings once that pack exists, and LL2 leaves the slot for them.

## What it builds on
- **The web reader** (`reader/ReaderScreen.kt`, `ReaderViewModel.kt`, `ReaderWebView.kt`): the page, its bars, the selection menu (`SelectionAction`: Search, Ask, Read from here), Show in time (`isDated` / `onShowInTime`), and LL1's footer band and sheet (`ArticleFooterViews.kt`).
- **The time index** (`core/library/TimeIndex.kt`): `TimeIndexes.find(pack, path)` gives the page's `TimeMark` (kind, years, precision, lanes, rank). `marks(from, to, lanes, …)`, `top(…)` and `around(…)` give the people and events around it.
- **The QID index** (`QidIndexes`): `qidOf`, `otherTitle(pack, path)` (the other language's title, built in LL0), `articlesOf(qid, language)`.
- **Vectors** (`core/vectors`): every Wikipedia pack with vectors (Top and the subject packs in both languages, 48 in the catalog) has its chunks in a `.gvec`, each row keyed by article path (`VectorFile.key(row)`). `VectorSearch.top(query, files, k, pool, probes)` searches every file at once, using IVF lists on version 2 files. Books have `.gvec` files too.
- **Book highlights** (`BookAnnotation` in `book_marks`, `MarkKind.HIGHLIGHT`, four colours and a note, `BookAnnotations` and the highlight sheet in the ebook reader). The library database is at version 15.

## Decisions

1. **Where the new pieces sit.** Neither goes over the article's own text:
   - *At a glance* is a slim strip under the top bar, shown only for dated pages: "1769–1821 · 52 years", plus a lifespan bar. A tap opens its sheet with contemporaries, the same year, and Show in time, which moves from the overflow menu into the sheet.
   - *Related* and *Read in Spanish* join the footer: the footer card's line grows ("Mentioned in 44 of your books · 12 related · En español"), and the sheet gains a Related section.
   - **Neal chose this, 2026-11-15** (over all in the footer, and over an Encarta-style side panel, which covers the page on a phone).
2. **Related is built on the phone from the vectors we already ship** (changed from the spec, which planned a new Mac-built per-article vector). The article's own chunk rows are averaged into one query, then run through `VectorSearch` over every installed `.gvec` (articles and books). Hits are grouped by article or book, and the page itself is dropped.
   - No new sidecar, release or catalog field, and the upkeep loop is untouched.
   - Task 1 spikes it on the Pixel: finding the article's rows (keys are per row, so a lookup per file may be needed), search time with probes, and whether the results read as related.
   - If the spike fails on speed or quality, the fallback is the spec's Mac-built `.grel` sidecar of precomputed neighbours per article.
   - Packs without vectors get no Related; the footer just leaves it out.
3. **The other language** is a link, not a translation. It shows when `otherTitle` gives a title *and* an installed pack in that language has the article (`articlesOf(qid, otherLanguage)`). It opens in a new tab, as a link card's Open does. With no pack in that language it doesn't show; Get is left for later.
4. **Web highlights follow the book model:** the same four colours, a note, and the same sheet.
   - **Anchored by text, not by DOM position:** the quote plus up to 32 characters either side, and the page's path. They survive a pack refresh that keeps the paragraph.
   - **Stored in a new table, `article_marks`.** It's the Linked Library's first new user data (spec §5.6), so it comes with a migration (15 → 16) and its test. Markdown export arrives with the Notebook (LL3); the table carries what it needs (pack title, page title, path, date).
   - *Highlight* joins the selection menu. Highlights are drawn by a script injected like read-aloud's.
   - A highlight whose text is gone is kept and listed, but not drawn.
5. **One flag per piece:** `Features.ARTICLE_GLANCE`, `ARTICLE_RELATED`, `ARTICLE_OTHER_LANGUAGE` and `WEB_HIGHLIGHTS`. Debug turns them on as they land; the tester build turns on all four with `LINKED_CLASSICS`.
6. **Spanish from day one:** every label in both languages, and the glance's dates in the app's locale ("a. C." for BC in Spanish).

## Tasks

### Task 1: Related spike (phone, measured)
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
  - **Results stay in the page's language:** the other-language article is the Read-in link (Task 3), not Related.
  - Same title in two packs is shown once.
  - Books need a score of at least **0.79**: science pages otherwise got Metamorphoses and the Bible at about 0.75.
  - Shown: up to 8 articles and 4 books, mixed by score.
- **Speed on the Pixel:** warm **0.70–0.96 s** for all 10 (bar 1.5 s). The first search after the files open took 1.8 s. Opening 627 files cold took 3–5 s; the app already opens them for search, and Related should reuse that.
- **Quality (my count of related in the top 6):** London 4, Napoleon 5, Photosynthesis 6, Jane Austen 5, Don Quixote 6, Zaragoza 5, Insulin 6, Byzantine Empire 6, Jazz 6, Volcano 6. All 10 meet the bar of 4. The weak spots:
  - London, Ontario and Æthelred under London.
  - Zamora and Zagreb under Zaragoza: near in the alphabet and in the vectors, not in meaning.
  - Caviar under Don Quixote, at #8.
  - Books that share a mood rather than a subject (Babbitt for Jazz, Main Street for Jane Austen).
- **Still open for Task 4:**
  - Drop redirects and other packs' copies by QID rather than by title.
  - Land a book at its best passage, not at the colophon (Spanish Don Quijote's best hit is `colophon.xhtml`).
  - Show titles, not paths.

### Task 2: At a glance (phone, tested)
- **`ArticleGlance`**, built from `TimeIndexes`:
  - the page's mark;
  - its span text: life (born–died, age), span, or point, at the mark's precision (year, decade or century);
  - up to 6 contemporaries: people whose lives overlap, by rank, in the same or a neighbouring lane;
  - up to 4 events of the same year, by rank.
- **The strip** under the top bar, for dated pages only, with a lifespan bar scaled to the mark.
- **The sheet:** contemporaries and same-year rows (each opens its article in a new tab), plus Show in time.
- **Tests:** span text for life, span, point and BC (both languages), contemporaries' overlap and order, a page without a mark showing nothing, and the strip hidden when the flag is off.

### Task 3: Read in the other language (phone, tested)
- `ArticleOtherLanguage(qid, title, packId, path)` from `QidIndexes`, in the reader's view model with the footer.
- The footer card line and sheet get "Leer en español" / "Read in English".
- **Tests:** shown only when a pack in that language has the item; it opens there; nothing shows for a page without a QID.

### Task 4: Related (phone, tested)
- **`RelatedSource`** (from Task 1's go): the article → its query → hits grouped by article or book. It runs after the page shows, on IO, and is cached per page.
- **The footer:** card line "12 related"; a Related section in the sheet (articles with thumbnail and pack, book passages that open at the passage), and a slot for recordings when Granger Music lands.
- **Tests:** grouping and dropping the page itself, books and articles mixed by score, no vectors meaning no section, and the cache.

### Task 5: Web highlights (phone, tested; the migration)
- **`ArticleMark` entity and DAO,** migration 15 → 16 and its test.
- **The selection menu** gets Highlight.
- **The page script:** find the quote with its context and wrap it, reusing read-aloud's text walk. Tapping a highlight opens the book reader's highlight sheet (colour, note, delete).
- **Library:** an article's highlights listed with the book ones, where Library shows highlights today. Checked in the task.
- **Tests:** anchoring (exact, after a paragraph moves, text gone → kept but not drawn), the migration, the sheet's actions, and Spanish text with accents.

### Task 6: Walkthrough (emulator, then the Pixel)
- **English:** Napoleon (glance, contemporaries, related, footer), London, Photosynthesis (an undated page has no strip), Jane Austen (Read in Spanish).
- **Spanish:** Cervantes, Zaragoza.
- **Highlights:** add, recolour, annotate, delete; survive a restart; a page with none.
- **Timings:** glance and related on the Pixel.
- **LL2 checkpoint with Neal:** placement (Decision 1), what Related shows, then merge, push, and the tester build (flags on, `LINKED_CLASSICS` on in release).

## Order and size
Task 1 first, because its answer decides Task 4's shape and whether a Mac sidecar is needed. Tasks 2, 3 and 5 don't depend on it and can go in any order. About four to five sessions, plus one more if Related needs `.grel`.

## Not in LL2
Granger Music (its own plan); the Notebook, clips, citations and export (LL3); Show on the map (LL6); Get for an other-language pack not installed; tablet two-pane layouts.
