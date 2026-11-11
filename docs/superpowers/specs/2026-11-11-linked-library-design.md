# The Linked Library: Granger's difference from Kiwix — design

**Status:** Draft, 2026-11-11, from a session with Neal the same day. Neal's calls are in §1. Not yet approved as a whole; LL0 and LL1 are planned (`../plans/2026-11-11-plan-ll0-ll1-qid-index-linked-classics.md`).

**Builds on:** the living Home spec (`2026-11-07-living-home-design.md`: the time index, Timeline, Wander, games, the Atlas in §6), Plan SQ (Search first, `sq-search-first`), the upkeep loop (`2026-11-09-upkeep-loop.md`). Like the living Home spec, this takes Encarta's *feel* as the reference point, not its names, art, layouts or features.

## 0. Why

Encarta's content was licensed from Funk & Wagnalls. What made it Encarta was everything around the articles: cross-references, sidebars, the timeline, the atlas, guided tours, quizzes, the research organizer. Granger is in the same position with Kiwix's content, and most of that layer is missing:

1. **An article reads the same as in Kiwix.** The web reader shows the ZIM's HTML with no context, no related items and no highlights (only the ebook reader has them).
2. **The packs don't connect to each other.** A Classics book never links to Wikipedia, an article never points to your books, and a composer's article doesn't know their music is on the phone. One search is the only bridge, and it needs a query to start.
3. **Research lost its feature.** Ask is off (SQ). Search finds things, but nothing helps you collect, connect, cite or review what you found.
4. **Features read as bolted on.** Music, audiobooks, archive.org, games, maps and the readiness kit each work, but together they read as "Kiwix plus extras".
5. **The audiences blur.** Preparedness and learning want different front doors.

**The position:** Kiwix gives you the shelves; Granger is the librarian who has read everything and cross-referenced it ahead of time. All of this is done without generated text, so it doesn't conflict with SQ's rule.

## 1. Neal's calls (2026-11-11)

1. **Learning and curiosity lead.** The app serves preparedness fully too, but learners and curious readers set the front door and the store copy.
2. **The order in §6 stands** (LL0 to LL6).
3. **Reference links default to Light.**
4. **Nothing retires.** Music, audiobooks, archive.org/LibriVox, games, maps and readiness all **fold into** the Linked Library (§4), so none of them feels bolted on, unnecessary or out of place.
5. **Music works out of the box** (2026-11-11, later the same day). Granger offers a curated, prebuilt music pack (or a few) of freely licensed recordings tied to the library, so music links are useful before anyone goes looking for music (§3.7).
6. **Audiobooks become the fallback, not a feature** (same day). The Owl voice reading the book is how Granger listens to books. LibriVox recordings exist for older phones that have the storage but can't run the reader voice well. They're offered where the reader voice won't run well, and stay for anyone who already has audiobooks, but they aren't promoted elsewhere.

## 2. What we already have

- **The Wikidata slice.** `tools/timeindex/wdfilter.py` matches every article in our packs to its Wikidata item (99.9% by ZIM path, living Home §11). `items.jsonl` on the X10 holds 338,368 English and 116,165 Spanish items with sitelink counts, labels, the other language's title, dates and coordinates. The 156 GB dump (2026-11-06) is on the same drive. Today we only keep the dated items, in `.gtime`.
- **EmbeddingGemma**: `.gvec` vectors for Wikipedia and the books, and the embedder on the phone. It's multilingual, so English and Spanish passages compare directly.
- **The time index** (`.gtime`), its lanes and rank, and the six games built on it.
- **Join keys already in our data:** Classics manifests carry Gutenberg IDs (Wikidata P2034); LibriVox has its IDs (P1899); music tags carry MusicBrainz IDs (P434); archive.org items have identifiers (P724).
- Passage addresses (deep links into any page), the dictionary, Keywords, ebook highlights and annotations, the voices.
- The desk-built pipeline: pack builder, signed catalog, upkeep loop.

## 3. The pieces

### 3.1 Reference links (LL1)
- **Book → library.** In the Classics, people, places, works and events become quiet links. They're built on the Mac (entity extraction, then matching to Wikipedia titles and QIDs).
  - Precision before recall. Only notable things (sitelink rank).
  - First mention per chapter, capped at about one link per 150 words at Light.
  - **Off / Light / More** in Reading settings. **Light is the default.**
- **Links resolve by QID on the phone** to whichever installed pack has the article (Wander's pack order). If none does, the tap offers Preview or Get for the best pack, never a dead link.
- **Library → books.** The article's *In your books* section: "London appears in 41 of your books", "Napoleon: *War and Peace*, Book 9". Each row opens at the passage.
- **Author → works, work → its text.** Jane Austen's article lists her books in your library (P50 / P2034). The article on a work opens the work itself.
- **Your own EPUBs** (later): matched on the phone against the most notable titles in the QID index, at import time.

### 3.2 The article, enriched (LL2)
- **At a glance**, from the time index: dates, a lifespan bar, contemporaries, the same year, Show in time, and Show on the map once the Atlas exists.
- **Related across your library:** nearby articles by meaning, book passages, the audiobook, the music. This uses one vector per article (the mean of its chunks), built on the Mac.
- **Read in Spanish / English**, through the QID.
- **Highlights in the web reader**, the same model as the ebook reader's. This is also the first step of LL3.

### 3.3 Notebook (LL3)
- **Clip from anywhere:** articles, books, search results, the Timeline, a game's answer. Each clip keeps its source, a deep link and a citation (MLA/APA/Chicago from metadata and the ZIM's date).
- Headings, your own notes, and read aloud with the Granger voices.
- **More like this clip**, across the library by meaning.
- Themes: clips grouped by vector clustering, labelled with Keywords, no generated text.
- **Export as Markdown, PDF or EPUB, and share.** User data, so it's the only new Room schema in the Linked Library (§5.6).

### 3.4 Reading companion (LL4)
- **Characters and places, spoiler-safe:** who someone is and where they've appeared, only up to your current position. Built on the Mac from LL1's entity pass.
- **Trace a theme** through a book: a heat strip over its chapters from the book's own vectors on the phone.
- **Books like this** across the Classics, from per-book vectors.
- **Listen and read as one** is the Owl voice reading the book, which already exists. On phones that fall back to LibriVox (§1.6), the recording opens the book at the same chapter, and back.

### 3.5 Study (LL5)
- **A review deck** from highlights, clips and dictionary lookups: cloze cards with simple spaced repetition.
- **Quiz me** on a notebook, a book or a trail, reusing the games engine with that set as the pool.

### 3.7 Granger Music, a curated pack (with LL2)
- **Why:** music links only help if there's music. Most people won't go and find recordings first.
- **What:** one or a few packs built by the pack builder, each recording keyed to the QIDs of its work, composer or performer. Every recording is free to share (public domain or CC0/CC BY), with attribution in the pack:
  - **Classical and early music:** recordings released into the public domain or CC0 (Musopen, the Open Goldberg Variations, Wikimedia Commons).
  - **Early recordings:** US sound recordings now in the public domain under the Music Modernization Act (released before 1926 as of 2026), from archive.org's 78rpm and cylinder collections and the Library of Congress.
  - **Folk and world:** field recordings and anthems from Commons and archive.org, where the licence allows.
- **Shape:** tiers like the Classics. A small starter (~300 MB, Opus at ~64–96 kbps): the works the most-linked composers and topics point to. Then larger add-ons by era or kind. English and Spanish share one pack, since music needs no translation, only titles and notes.
- **Curation, not volume:** selection starts from what the library links to most (composer and work articles by rank, the works named in the Classics), so every track has somewhere to link from.
- **Delivery:** a ZIM like the Classics, readable by Kiwix as a media pack, plus its own `.gqid`-style index as a sidecar. The music player plays it like local music.
- **Play builds:** the same public-domain rules as the books. The early-recordings tier follows the US rule only, and the policy check states that.

### 3.6 Explore (LL6)
- **Trails:** curated paths through articles, book chapters, timeline stops, map pins and recordings. Seeded from Wikipedia's "Outline of…" pages and hand-picked. Preparedness trails count too (first aid, water, knots).
- **The Atlas** as specified in living Home §6, with pins from the QID index's coordinates and places from books and notebooks.
- **Topic map:** a 2D layout of a book's chapters or a notebook, by vectors.

## 4. Folding in what's there (Neal's call 4)

Every existing feature becomes one more kind of thing the library can link to and from:

| Feature | How it folds in |
|---|---|
| **Music** | **Granger Music** (§3.7) makes it work out of the box. Artist and work tags (MusicBrainz → QID) and the pack's own QIDs link both ways. A composer's article shows *In your music*. Related includes recordings. A Trail can stop at a piece. |
| **Audiobooks / LibriVox** | The fallback for phones where the reader voice won't run well (§1.6), and kept for audiobooks people already have. Where offered, a recording links to its book (P1899 / P2034) and the author's article. Elsewhere the Owl voice reads the book. The tech-debt session can slim this code path. |
| **archive.org** | The fallback for an unresolved link to a work: "Find it on archive.org" (behind the existing 1930 filter in Play builds). A work's article offers its recording or scan. |
| **Games** | Study mode (LL5): the same games over a notebook, a book or a trail. Answers open articles, as now. |
| **Maps** | The Atlas (LL6): places from articles, books and notebooks. Show on the map from At a glance. |
| **Readiness kit** | Trails and review decks for first aid and repair: preparedness as something you learn ahead, not only look up. |
| **Voices** | Notebooks and trails read aloud. The companion reads character notes. |
| **Home** | Feeds become ways in: *From your notebook*, *Continue the trail*, *Mentioned in what you're reading*. These go in Arrange Home like the other sections. |
| **Search (SQ)** | A Clip action on results (LL3). Linked entities can boost Best matches later. |

## 5. Rules that keep the care loop small

The tech-debt session comes next, and these rules exist so the Linked Library doesn't make that work harder.

1. **One sidecar format, one delivery path.** Every index built on the Mac is a read-only SQLite file named after its pack's ZIM, with the same `meta` table: `kind`, `schemaVersion`, `sourceZim`, `sourceSha256`, `builder`, `builderVersion`, `builtAt`. They share one release JSON shape, one catalog field and one download and readiness path (`Sidecars`). `.gtime` and `.gvec` move onto it. New kinds add no new download code.
2. **Heavy work on the Mac, light reading on the phone.** The upkeep loop rebuilds a pack's sidecars when the pack refreshes. A sidecar whose `sourceSha256` doesn't match the installed pack is ignored, and its feature hides instead of breaking.
3. **QIDs are the only join key.** No feature does its own title matching on the phone.
4. **One search stack.** Meaning-based features use SQ's vector API, except the book-local theme trace, which reads one book's vectors.
5. **Every builder ships a check** the upkeep loop can run (link precision on a fixed hand-graded sample, coverage), so the loop can confirm a rebuild, not just run it.
6. **User data is the only new Room schema** (the notebook, web highlights, the review deck), with migration tests and Markdown export from day one. Everything built on the Mac can be thrown away and rebuilt.
7. **Spanish works in every builder from day one.** QIDs and the multilingual embedder make that nearly free.
8. **One `Features` flag per piece.** Folding in means extending existing screens, not adding rail tools. The Notebook is the only new top-level place.

## 6. Order

| # | Plan | Delivers |
|---|---|---|
| **LL0** | Sidecars + the QID index | The shared sidecar format and delivery; a `.gqid` per Wikipedia pack (every article: QID, rank, other-language title, key Wikidata links); `.gtime` moved onto it |
| **LL1** | Linked Classics | Book → library links (Light default), *In your books*, author → works, the Reading setting |
| **LL2** | The article, enriched | At a glance, Related across your library, the other language, web highlights |
| **LL3** | Notebook | Clips from everywhere, citations, export, More like this, Clip in Search |
| **LL4** | Reading companion | Characters and places (spoiler-safe), theme trace, books like this, listen and read as one |
| **LL5** | Study | Review deck, Quiz me |
| **LL6** | Explore | Trails, the Atlas (living Home §6), topic map |

Music links (MusicBrainz) and the first Granger Music pack (§3.7) ride in LL2 with Related, a plan of their own if they grow. The LibriVox join (only for fallback phones) comes in LL4.

## 7. Open questions

- **Link density at Light:** the 1-per-150-words cap is a starting guess. LL1's spike sets it from reading three books on the Pixel.
- **Which Wikipedia to resolve to** when Top and a subject pack both have the article: Wander's order (the fullest copy) is the default.
- **The Notebook's place:** a rail tool, or inside Library? Decided in LL3.
- **Tablet layout:** the companion and notebook would gain the most from two panes. Worth a look in LL3.
