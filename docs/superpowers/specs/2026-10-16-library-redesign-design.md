# Granger — Library Redesign: side toolbar, collections, shared e-book reader

Date: 2026-10-16 · Owner: Neal Thompson · Status: draft with Neal's answers folded in; pending his review

Amends `2026-10-15-granger-v1-design.md`. Where the two differ, this document wins; everything it doesn't touch (Ask pipeline internals, catalog signing, downloads, billing, telemetry, eval) stands as written there.

## 1. Why

Plan 4 shipped Granger Classics as one 84 MB ZIM read like a website. Reading it on the Pixel 6 exposed a bigger miss (Neal: "a bigger overall design miss on my part"): Granger is turning into a personal library of many media types from many sources, and the current app treats everything as a flat list of website-style packs. Books should feel like books, the library should be organised the way a person thinks about it, and the app should open onto what you were doing.

**Picture of the finished app (Neal's):** you open Granger and you are back in the book you were reading, where you left off. Down the left edge is a vertical toolbar: tools at the top, then bookmarks to the books and media you have open, then recent chats, searches and media, and Settings at the very bottom. Something you half-remember is found through Search; failing that, through Inquire & Abstract, the app's expert librarian and senior research scientist over everything in your library.

## 2. Goals, non-goals, anti-goals

**Goals (v1)**
- The app opens to where you left off. No home screen.
- A vertical side toolbar is the app's navigation, and is designed now for the media features that come after v1.
- The Library is organised by **media type → collection**, with a separate **Archive sources** list for getting more content.
- One e-book reader, modelled on Google Play Books, for every e-book whatever its source.
- Granger Classics becomes a collection of individual EPUB e-books.
- Archive.org becomes a source for old books and technical manuals, with a disclaimer.
- The Neural Flame logo becomes the app icon and sets the app theme.
- Wikipedia and other web-style packs keep the current web-style reader, polished to production quality.

**Non-goals for v1 (designed for, built right after v1)**
- Audio player, mini-player, audiobooks, music, video.
- Read-aloud, and mixing read-aloud with music.
- PDF in the e-book reader (comes in a later pass; import and search of PDFs follow the existing Plan 5 rules when that pass lands).
- Archive.org audiobooks (they arrive with the player).

**Anti-goals: things that make this a failure even if it works**
- Books that read like web pages: scrolling text, the book's own CSS fighting the app theme, nav-bar text in places it doesn't belong.
- Landing anywhere other than where you left off when you open the app.
- A toolbar that eats the reading screen on a phone.
- Rework later: v1 must leave clean slots for the Player button, the mini-player and read-aloud, not paint over them.
- Granger implying it vouches for what users download from archive.org, or helping around lending restrictions.

## 3. App shell

### Side toolbar (rail)

Top to bottom:

1. **Tools**: Search · Inquire & Abstract · Library · Player. The Player button is hidden in v1 and reserved in the layout.
2. **Bookmarks**: the books and articles you have open, most recently opened first. Each one jumps back into that item where you left it, so you can move quickly between several. **Each article is its own spot**: three open Wikipedia articles take three spots. Opening a book or article adds it; you can remove one (long-press). An item's place survives removal; it's just no longer on the rail. At the bottom of the section, a **History** button opens the full list of everything you've opened, newest first, searchable. *Builder's call: the cap on how many spots show (around 8), and what happens past it (oldest drop off the rail but stay in History).*
3. **Recent**: recent Inquire & Abstract chats and searches, newest first, tapping one reopens it. After v1, Recent also lists audio and video you played.
4. **Settings**: pinned to the bottom.

**On a phone** the rail has two widths:
- **Narrow** (about 56–60 dp): icons for tools, small covers for bookmarks (the current item outlined in flame orange), icons for recents.
- **Expanded** (about 200 dp, over the content with a scrim): labels, titles with progress ("Ch. XII · 34%"), and the text of recent chats and searches. Opened by swiping from the left edge or tapping the rail.

**Immersive reading:** in either reader, reading is full screen with no rail. Tapping the middle of the page brings up the reader's controls **and** the rail (and, after v1, the mini-player when something is playing). Tapping the middle again hides them. On non-reading screens (Library, Search, Inquire & Abstract, Settings, archive sources) the narrow rail is always visible.

*Builder's call:* the exact icons, the scrim, animation, and whether a tablet/landscape layout keeps the rail expanded.

### Opening the app

- Normally, opening Granger restores the last item you were reading, at your exact place, in its reader, immersive. It restores the last thing you were *reading*, not the last screen you were on.
- **These cases open the Library instead:** after a crash, after the phone rebooted, and the first open after install and the intro (onboarding, Plan 10).
- If the last item is gone (deleted, SD card out), it opens the Library with a short notice.
- *Builder's call:* the OS reclaiming a backgrounded app, and the user swiping it away, count as normal opens (resume), not crashes. Crash detection is "the previous session didn't end cleanly" (a flag cleared on clean background/stop); reboot detection compares boot time with the last session's.
- This also closes the Plan 2 deferral: reopening after the OS killed the app shows the last page, not the first one opened.

### Reserved for after v1 (layout slots built now, features hidden)

- **Player button** in the tools: toggles the player between full screen and a **mini-player** across the top of the screen that stays while you use the rest of the app.
- **Mini-player** area at the top of the content column, which pushes content down rather than covering it.
- **Read-aloud** button in the e-book reader's bar and in the web-style reader's controls. Read-aloud is a reader function for any text media. It can play at the same time as music; while both play, a **music/voice mix slider** appears (the mockup put it in the mini-player).

## 4. Library

### Structure

Tapping **Library** opens two lists:

**Media**: media types, each holding collections.
- **Literature**: collections such as *Wikipedia*, *Granger Classics*, *Archive.org*, *My books* and anything the user creates.
- **Audiobooks**, **Music**, **Video**: present after v1 when the player lands. *Builder's call: in v1 hide empty media types, or show them disabled; prefer hide.*
- User-created media types appear here too.

**Archive sources**: where you search, browse, download and manage content from each source.
- **Granger**: Granger's own curated content (Classics and future Granger packs) and presets.
- **Kiwix**: Kiwix packs (Wikipedia, WikiMed, Wiktionary, iFixit, Stack Exchange…).
- **Archive.org**: see §5.

Opening a collection lists its items: e-books as a cover grid or list, packs as a list. Opening a pack opens it in the web-style reader at the last article read.

### Filing

- When content is installed, the **app files it** into a media type and a collection.
  - Kiwix packs: Literature. The collection comes from the pack family (all Wikipedia variants → *Wikipedia*; WikiMed → *Wikipedia* too, being a Wikipedia subset; others get a collection named for the pack family, e.g. *iFixit*, *Stack Exchange*). *Builder's call: the mapping table, kept in one place so it's easy to change.*
  - Granger Classics → *Granger Classics*. Archive.org downloads → *Archive.org*. Imports → *My books*.
- The user can **move** an item or a whole collection to another media type, **create** a new media type and move things into it, and create, rename and delete their own collections. Built-in media types can't be deleted; a user-created one can be deleted once empty.
- User filing choices survive updates: a re-downloaded or updated pack keeps the place the user gave it.

### Search

Search (the rail tool) searches everything installed: ZIM full-text and title suggest, plus the e-book text index (FTS5 from Plan 5's `:core:imports`, now also covering Classics and archive.org books). Results group by collection; tapping a result opens it in the right reader at that spot. Recent searches go to the rail's Recent section.

## 5. Archive sources

Each source screen has search, browse, and **Installed** / **Updates** views (Updates where the source has versions). Downloading, resuming, verifying and storage location use the existing `:core:downloads` machinery and storage meter. The current Downloads screen's contents move here; models move to **Settings → Models** (Inquire & Abstract points there when no model is installed). Readiness moves to **Settings → Readiness**.

### Granger
Lists Granger Classics (whole collection or single books) and the Starter/Full presets. Comes from the signed catalog.

### Kiwix
v1 lists the curated Kiwix packs from the signed catalog, searchable. *Builder's call: browsing Kiwix's whole OPDS catalog beyond the curated list is out of v1 unless Neal says otherwise (see §11).*

### Archive.org
For **old books, technical manuals and, after v1, public-domain audiobooks**. Granger behaves like a web browser built for one site: it searches and browses archive.org's public API and downloads files archive.org serves openly.

- **Filtered by default.** Until the user accepts the waiver, search and browse show only texts that are public domain: published 1930 or earlier, or marked public domain / CC0 in their rights metadata. A short note says the view is filtered and how to change it. *Builder's call: the exact archive.org query; when an item's date is missing, leave it out of the filtered view.*
- **Waiver:** a "Show everything" option (on the source screen and in Settings) asks the user to accept a waiver. In substance it says: Granger is a browser for archive.org; Granger doesn't host, check or vouch for the content; you are responsible for having the right to download and use what you take. Once it's accepted, the filter is lifted and everything archive.org serves openly is shown. The waiver can be withdrawn in Settings, which restores the filter; files already downloaded stay. *Builder's call: exact wording, kept plain.*
- **Openly downloadable files only.** Items archive.org restricts to lending (`access-restricted-item` or files marked private) appear with a "Borrow on archive.org" label that opens the item in the system browser. Granger never handles lending accounts or copy protection (LCP/Adobe), consistent with the v1 import rule of never removing DRM.
- **Books you own:** Granger cannot check ownership. Openly downloadable → download; lend-only → the browser link, or import your own copy.
- **Formats in v1:** EPUB. When an item offers EPUB, download that. Items offering only PDF are listed but marked "PDF: coming soon" until the PDF pass. Text-only (TXT) items are allowed and open in the e-book reader as simple text.
- **No signed hash:** archive.org's metadata API gives each file's size and md5/sha1. The download is verified against those (sha1 preferred) rather than a catalog sha256. That's weaker than the signed catalog and accepted for this source.
- Downloaded books are filed under Literature → *Archive.org*, indexed for Search and Inquire & Abstract like any e-book, and keep their archive.org identifier and metadata (title, creator, date, subject).
- **Free, but capped:** archive.org downloads are free (not Pro-only) and count toward the Free 10 GB cap like packs.
- **Play Store risk:** an in-app archive.org downloader may draw Play policy review. The default public-domain filter, the waiver and the openly-downloadable-only rule are the mitigation; F-Droid/direct APK channels are unaffected. Check Play policy before the beta (Plan 9).

## 6. The e-book reader

One reader for every e-book: Granger Classics, archive.org books and imports. Modelled on **Google Play Books**.

**Rendering:** the Readium Kotlin Toolkit (BSD-3, compatible with GPLv3), not a hand-rolled WebView/OPF parser. This replaces the v1 spec's "EPUBs reuse the ZIM WebView" approach. Readium also has a PDF path for the later PDF pass.

**Reading**
- Paginated: swipe, or tap the left or right edge, to turn pages. No continuous scroll in v1.
- Tap the middle to show or hide controls (and the rail, per §3).
- Top bar: book title, table of contents, search in book.
- Bottom: a slider for jumping anywhere, with the chapter and page/percent; under it the reader bar: Contents · Text settings · Read-aloud (hidden in v1, slot reserved) · Bookmark.
- Opens exactly where you left off (Readium locator stored per book).

**Text settings** (the user's settings win over the book's own styles)
- Typeface, size, line spacing, margins, alignment.
- Themes: Day, Sepia, Night. Night follows the app theme.
- Publisher styles are overridden by default, which also fixes Classics' Gutenberg CSS overriding the theme.

**Marking and lookup**
- **Bookmarks inside a book** (saved places), listed in the contents panel.
- **Highlights** in a few colours, and **notes** on a highlight; listed in the contents panel; included when searching the book.
- **Dictionary lookup** on a selected word. *Builder's call: use an installed offline dictionary pack (Wiktionary from Kiwix) when present, else Android's text-processing intent, else hide the option. This must not need a network.*
- **Search inside the book**, with results in context.

**Selecting text** brings up: Copy · Highlight · Note · Define · **Search** · **Inquire & Abstract**. Search and Inquire & Abstract open that tool with the selection already filled in, so it's easy to start a search or a chat from what you're reading.

## 7. The web-style reader (Wikipedia, Kiwix and other web-style packs)

These stay close to how the current build presents them, with production polish:

- The same immersive behaviour: full screen; tapping a non-link area shows or hides controls and the rail.
- The same text-selection actions: Copy · Search · Inquire & Abstract (Highlight/Note can wait).
- Reopens at the last article and scroll position (exists today).
- Bookmarks on the rail work the same as for e-books.
- Production refinements: consistent app theme over pack styles where packs allow it; Kiwix-style jQuery injection so main pages like the Ray Charles sample render (Plan 3 carry-forward); a find-in-page; a tidy, readable title bar. *Builder's call: the rest of the polish list, judged on the Pixel 6.*

## 8. Granger Classics as e-books

- `tools/pack-builder` gains an EPUB output: one **EPUB 3 per work**, built from the same manifest, cleaning and Gutenberg-branding removal as the ZIM build. Each has a generated cover (title, author, Granger flame mark; *builder's call on design*), proper metadata (title, author, translator, original date, subjects) and a real table of contents from the existing chapter/act/scene detection.
- The four open Classics defects are fixed in the EPUB build:
  - Two works both titled "The Koran": show the translator in the title or subtitle when there's no author.
  - Book CSS overriding the theme: the build strips Gutenberg CSS that sets colours/backgrounds and drops `.pagenum` markers; the reader's style override covers the rest.
  - Nav text in search snippets: no in-text nav in EPUBs; the index is built from body text only.
  - Merged heading-only titles: the TOC uses clean chapter titles.
  - Also fix the Plan 4 minors that carry over (lost anchor ids, `<1>` markers in Canterbury Tales titles, failures that don't name the work).
- **Hosting:** individual EPUBs as assets on a GitHub release (`classics-<date>`), each in the signed catalog with its own sha256 and metadata, plus a "whole collection" entry that queues them all. *Builder's call: whether a single archive bundle is also offered for the whole set.*
- The Classics ZIM is retired from the catalog. An installed Classics ZIM keeps working but is labelled as superseded, with a prompt to download the e-book collection.
- Full-text search and Inquire & Abstract reach Classics through the e-book FTS5 index rather than ZIM full-text.

## 9. Inquire & Abstract

**Inquire & Abstract** is the new name for Ask everywhere in the UI. Its v1 job, as in the v1 spec, is the cited answer or "not in your library". This redesign widens its stated purpose so that Plan 6 designs for it:

- Beyond finding and summarising, it is a thinking partner for working through scientific, engineering, philosophical and other scenarios.
- It grounds every claim in your library's text **and metadata** (author, date, subject, collection), citing as before.
- Where it helps, it surfaces connections between sources that seem unrelated.
- It can be started from a text selection in either reader (§6, §7).
- Chats are kept and listed in the rail's Recent section. That lifts "Ask history" out of the v1 spec's deferred list.

The governing rule is unchanged: knowledge lives in files, not in model weights. Cite or decline.

## 10. Brand and theme

- **App icon:** the Neural Flame mark (circuit-flame over an open book), adapted as an Android adaptive icon with a monochrome layer for themed icons. Source: `Gemini_Generated_Image_fe0rssfe0rssfe0r.jpeg` (raster, 2752×1536). The builder vectorises the mark and commits the source.
- **Wordmark and tagline:** "GRANGER" and "Civilization, backed up.", used on the splash and About screens.
- **Theme:** near-black surfaces, flame orange (≈ `#E8601C`, sampled from the mark) as the single accent, warm off-white text. Defined as Material 3 colour tokens in one theme file so later passes can evolve it. Dark is the default. *Builder's call: whether a light theme ships in v1; at minimum the token structure must make one easy.*
- The e-book reader's Day and Sepia themes are reading themes, independent of the app theme.

## 11. Data model and migration

New concepts for `:core:library` (Room, migrating from schema 2 without losing packs, bookmarks or reading positions):

- **Media type**: id, name, built-in flag, order.
- **Collection**: id, name, media type, origin (Kiwix family / Granger / Archive.org / Imports / user), user-created flag.
- **Library item**: one row per thing the library shows. Kind is ZIM pack or e-book (later: PDF, audio, video). It has a collection, a source reference (catalog id, archive.org identifier, or import), its file path(s), title, author/creator, cover and metadata. The existing `packs` table becomes, or is joined to, the ZIM-pack kind.
- **Position**: per item; a Readium locator for e-books, path + scroll fraction for ZIM (the existing `reading_positions` rows carry over).
- **Rail state and history**: open items (rail bookmarks) in order, keyed by item + entry path so each article is its own entry; the full open history behind the History button; recent chats and searches.
- **In-book bookmarks, highlights and notes** for e-books.
- The existing ZIM `bookmarks` table (saved articles) carries over as in-pack saved places.

*Builder's call: the exact schema and the `:core:imports` vs `:core:library` split.* The constraint is a single `Library` façade that the screens depend on, as today.

## 12. Effect on the roadmap

The rest of the roadmap reorders like this (Plans 1–4 are done):

| # | Plan | Delivers |
| --- | --- | --- |
| 5 | **Shell + Library** | Theme and icon, side rail, immersive behaviour, open-to-last-place, media types/collections/filing, Library screens, archive-source screens for Granger and Kiwix (Downloads content moved), Settings with Models and Readiness, polished web-style reader with selection actions, schema migration |
| 6 | **E-book reader + Classics EPUBs** | Readium reader with all §6 features, `:core:imports` EPUB/TXT/MD import + FTS5 index + DRM detection, My books, pack-builder EPUB output, Classics re-release and catalog entries, Search over e-books |
| 7 | **Archive.org source** | Disclaimer, search/browse, open-only downloads with md5/sha1 verify, filing, metadata |
| 8 | Inquire & Abstract | Old Plan 6 (Ask pipeline) under the new name, with metadata-aware grounding, cross-source connections, selection entry and kept chats |
| 9 | Eval harness | Old Plan 7 |
| 10 | Monetization + onboarding + telemetry | Old Plan 8 |
| 11 | Beta | Old Plan 9; includes the Play policy check for the archive.org source |
| — | **After v1: Media** | Player, mini-player, audiobooks/music/video, archive.org audiobooks, read-aloud, music/voice mix, Recent media; then the PDF pass |

## 13. Free/Pro and the cap

- **All archive sources are free** (Granger, Kiwix, Archive.org): no source is Pro-only.
- **The Free 10 GB cap still applies** to everything downloaded from them: ZIM packs, Classics e-books and archive.org books all count. Queueing the whole Classics collection is checked as one group against the cap.
- Imports of your own files stay Pro-only and outside the cap, as in the v1 spec.
- Models stay excluded from the cap.
- Kiwix catalog breadth stays the curated list in v1 (§5).

## 14. Builder's calls made in this document (for Neal's review)

- Narrow/expanded rail on phones; the rail hidden while reading until a middle tap.
- Rail bookmarks added automatically on open, removable, capped at about 8; older ones stay in History.
- Swipe-away and OS reclaim resume the book; only crash, reboot and first open land on the Library.
- Archive.org filtered view: published 1930 or earlier, or marked public domain/CC0; undated items hidden until the waiver.
- Readium for the e-book reader; EPUB 3 for Classics, one file per work.
- Archive.org: openly downloadable files only, lend-only items link out, sha1/md5 verification, PDF-only items listed but disabled until the PDF pass.
- Models and Readiness move under Settings; Downloads content moves to the archive sources.
- Dictionary from an offline Wiktionary pack when installed.
- Ask renamed to Inquire & Abstract everywhere; chats kept (Ask history moves into v1).
- Dark-only theme in v1 unless light is cheap; tokens structured for change.
- The Classics ZIM is retired, not deleted from devices.
