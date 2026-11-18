# Online and offline: design

2026-11-18 · Neal Thompson (decisions) · written up by Claude

Status: approved in its decisions (Neal, 2026-11-18; see §11). **It supersedes the readiness half of
`2026-10-21-home-readiness-onboarding-design.md`** (§3 the readiness model, §4 the screen, §6 onboarding's "Pick a start").
The nine domains, their tiers in the catalog and Home's feeds stay as they are. What changes is what they mean: they're
one view of outfitting, not the measure of the app. Mockups: canvas *Granger online and offline*, https://claude.ai/artifact/SXH5nLhtUHj5L8DwzwesWX.

## 1. Why

Granger is built offline-first: the app is what you've downloaded. A fresh install opens to a near-empty Home, and the
smallest Wikipedia that makes Explore work is 334 MB. That keeps the people who'd most enjoy Granger as a learning tool
from ever seeing it, and it turns "try it" into "commit to it" (the 1495 release smoke showed this: Explore's only door
on a new phone is "Get a Wikipedia").

The new rule: **when the internet is there, everything works; offline is what you chose to keep.** Granger still serves
the offline purpose fully. A phone outfitted for no signal is as ready as it is today. But outfitting becomes a choice
you make *after* you know what you use, and the app helps you make it.

## 2. Goals, non-goals, anti-goals

**Goals**
- A fresh install, online, gives the whole experience within a minute and a few MB: Explore, topic pages, search,
  Timeline, games, the reader. No pack is needed.
- Every item (article, book, pack, index) shows where it lives: **On this phone**, **Saved for now** or **Online**.
- You choose at every level what to keep: one article, one book, a subject, a pack.
- **Local only**: one switch that makes the app behave exactly as it would with no signal, so you can see the offline
  experience and outfit for it.
- **Outfitting** replaces Readiness. It looks at what you actually use ("12 of the 40 things you opened this month
  would work offline") as well as the nine domains, and offers packs that close the gap, with sizes.
- When online, the fullest version wins: a shorter kept copy (mini, no pictures) is filled from online.

**Non-goals (this project)**
- A Granger server. Online means public sources directly: Wikimedia, Gutenberg, archive.org, LibriVox, and our own
  GitHub releases.
- Reading other Kiwix packs online (Wiktionary, WikiMed, Stack Exchange, iFixit…). Kiwix's online library
  (`browse.library.kiwix.org`) shows an "Access confirmation" page to non-browsers (checked 2026-11-18). Those packs
  stay download-only, with "Open the website" where the source has one. Asking Kiwix is an open question (§10).
- Ask and semantic search online. Both stay local features you outfit for.
- Accounts, sync between devices.

**Anti-goals: failures even if it technically works**
- **Telling a user they hold something they don't.** Carried over from the 2026-10-21 spec. "Saved for now" is never
  shown as "On this phone"; Local only is never softer than real offline.
- **Online that quietly costs privacy.** Reading online tells Wikimedia (or Gutenberg, archive.org) what you opened.
  Granger says so once, plainly, before the first online read, and "Never go online" exists from day one.
- **An offline phone that feels broken.** With no signal, the screens that suggest things (Home, Wander, games) draw
  only from what's on the phone, so they still feel full. Only the screens you browse show gaps.
- **A shop.** Outfitting suggests packs from what you used; it never nags, counts streaks or blocks anything.

## 3. Three states

| State | Means | Mark | Counts as offline |
|---|---|---|---|
| **On this phone** | Kept on purpose: a pack, a book, a kept article. Never removed by Granger. | filled dot | yes |
| **Saved for now** | Fetched because you read it; removed oldest first when the cache is full. | ring | works offline, but *not* counted in coverage |
| **Online** | Needs the internet. | cloud | no |

- The mark goes on article headers, search results, Explore plates and rows, book rows, Timeline marks and the
  Library. It's small (12 dp, left of the title or in the plate's corner) and has a label for TalkBack ("on this phone",
  "saved for now", "needs internet").
- Marks only show where they tell you something. Online is the common case, so **online is unmarked and the two
  offline states are marked**. In Local only and real offline, online items are greyed with "Needs internet" instead.
- A kept item whose online version is fuller (a mini or no-picture copy) shows "On this phone (shorter)", and the
  header offers "Full article online" when online.

## 4. Where each thing comes from

| Thing | Online source | Kept as |
|---|---|---|
| Wikipedia article | Wikimedia REST (`/api/rest_v1/page/mobile-html/{title}` or Parsoid `/w/rest.php/v1/page/{title}/html`; picked in O0) | a pack, or a kept article (§5) |
| Article summary and lead picture (topic page header, plates) | `/api/rest_v1/page/summary/{title}` (0.4 s from the Mac, 2026-11-18) | the index's lead + pack thumbnail |
| Pictures | `upload.wikimedia.org` thumbnails at the plate's width | with the kept article or pack |
| Search | Wikipedia's search API, merged under the local results with the online mark | pack full-text + semantic |
| Topic page: people, events, Explore rooms, A–Z | **our index files** (.gqid, .gtime) (§6) | the index files |
| Topic page: Related | Wikipedia `morelike:` search (`list=search&srsearch=morelike:{title}`) | vectors (as today) |
| Topic page: quotes from your books | local only (book vectors) | Classics + vectors |
| Classics, Gutenberg books | our GitHub release / gutenberg.org; opening a book fetches it (books are small) as Saved for now | Keep (as today's download) |
| Archive.org, LibriVox | already online (`ArchiveOrgClient`, `LibriVoxClient`); streaming playback added | download |
| Other Kiwix packs | none (§2); "Open the website" when the pack names one | the pack |
| Timeline, games, Wander | the index files, pictures online | the index files + a pack |
| Ask, semantic search, voices | local only | models, vectors, voices |

**Article identity.** An article is (language, title) with its QID. A pack copy is (pack, path); the .gqid maps
between them, and a Wikimedia response carries its own title and `wikibase_item`. Topic pages, history and keeping
all key on (language, QID) so an article read online and later kept is the same thing.

**Which copy opens.** In order: a kept full copy (maxi pack or kept article with pictures) → online when online → any
kept copy (mini, no pictures) → "Needs internet". A kept full copy always wins even online: faster, free and private.
A kept shorter copy loses to online (the fullest-experience rule), and Local only always uses it.

**One gate for the network.** Every request goes through the shared OkHttp client, with one interceptor that refuses
when Local only or Never go online is set (the same place `ArchiveUserAgent` sits). That makes Local only honest by
construction: no feature can forget to check it. Downloads in progress pause; the catalog doesn't refresh.

**User-Agent.** Wikimedia asks for a contactable agent: `Granger/<version> (https://opengranger.org;
feedback@opengranger.org)`, the project's own address, never the user's.

## 5. Keeping

- **Keep an article**: the header's Keep button stores its HTML and pictures in a new local store (files plus a Room
  table `kept_articles(lang, qid, title, bytes, keptAt, revision)`), outside any pack. It opens offline through the
  reader's interceptor like a pack page.
- **Keep a book**: today's download, renamed in the UI.
- **Keep a subject or room**: when a Kiwix subject pack matches (Medicine, Physics…), offer the pack with its size;
  otherwise keep the room's articles as kept articles, showing the count and estimated size first.
- **Keep a pack**: today's Kiwix flow, renamed.
- **Saved for now**: every online article, book chapter and picture you open goes into a cache with a size cap
  (default 300 MB, Settings → Online). Oldest goes first. "Keep" on a saved item moves it to On this phone without
  fetching again.
- **Updates**: kept articles carry their revision; Outfitting lists ones with a newer version when online, as an
  update row (never automatic).

## 6. Our index files online

The index files are what make Explore and the topic page more than Wikipedia: rooms, people of a time, events, the A–Z,
pictures picked by rule. Sizes today: Top 17 MB (en) / 16 MB (es), subject indexes 0.1–43 MB, Top 1M 142 MB (en), all
Spanish 287 MB; time index ~2.3 MB.

- **Phase 1 (O1): fetch the small set quietly.** On the first online start, Granger fetches the language's Top .gqid
  and .gtime (~20 MB together), on Wi-Fi or with a one-line ask on mobile data. Explore, topic pages, Timeline and games
  work from them at once, with articles and pictures online. They count as On this phone (they're kept, and small).
- **Articles outside the Top index** (most of Wikipedia) get a lighter topic page online: the summary header, Related
  from `morelike:`, quotes if books are kept, and no people or events (no index covers them). That's honest and still
  better than a bare article.
- **Phase 2 (after the O0 spike): read the big indexes remotely.** The .gqid files are SQLite. They can be read straight
  from the GitHub release with HTTP range requests, fetching only the pages a query touches (a read-only SQLite VFS over
  HTTP), with no server, the same data and nearly the same queries. If it is fast enough on a phone, Top 1M (en) and
  all Spanish give full topic pages for ~1M articles without a download.
- **The O0 bar**: topic page header ≤ 1 s and Related ≤ 3 s on the Pixel 6 over Wi-Fi, page reads cached on the phone
  (part of Saved for now). If it misses, Phase 2 is a "Keep the big index" offer instead.

## 7. Local only

- **The switch**: a top-bar toggle (cloud icon with a slash) on Home, Explore and Search; Settings → Online; and a quick
  settings tile. It stays on until turned off, across restarts.
- **The banner**: a thin bar under the top bar, "Local only. Showing what this phone holds. Turn off", in Night and
  Field colours.
- **Behaviour is exactly no signal**: the network gate refuses (§4), so what you see is what you'd get on a plane.
- **What offline looks like** (Local only or real):
  - **Browse screens** (Explore rooms and spreads, search results, the A–Z, Get books): online-only items are greyed
    with "Needs internet". Plates keep their place but lose the picture unless it's saved. You see the gaps.
  - **Volunteered screens** (Home feeds, Wander, Timeline glance, games, Continue): only items on the phone or saved for
    now. They stay full.
  - **A greyed item tapped**: a sheet with what it would take: "Photosynthesis is online only. Keep this article
    (180 KB) next time you're online, or get Wikipedia Top (2.7 GB), which holds it." The keep is queued for when the
    switch goes off.
  - **Search** shows local results, then one line: "Online results appear when you're connected."

## 8. Outfitting (replaces Readiness)

Same place on the rail and in Settings; Home's strip changes from nine dots to one line.

1. **The switch** at the top: "Try Local only" with a line about what it does.
2. **Your use**: from the reading history that's already on the phone (it never leaves the phone). "Of the 40 things
   you opened in the last 30 days, 12 work offline (8 on this phone, 4 saved for now)." Then up to three suggestions,
   picked by how much of your recent use each would cover, per MB: "Wikipedia Top 100k, 1.2 GB, covers 34 of 40".
   "Keep the 28 articles you read, 6 MB" is always one of the choices.
3. **Ready for anything**: the nine domains, bars and signals exactly as today (§3–§4 of the 2026-10-21 spec), under
   one plain heading: what a phone needs when nothing else is there. Kept articles count toward no domain.
4. **On this phone**: kept (packs, books, articles, indexes) with sizes; Saved for now with its cap and "Clear"; storage
   by source as today.
5. **Files**: the integrity rows as today.

**Home strip**: "12 of 40 recent reads work offline ›" (or, before any use, "Everything online, nothing kept yet ›").

## 9. First run, settings, privacy

- **Onboarding** keeps Welcome and This phone, then one screen, **How do you want to start?**:
  - **Start reading now**: online, nothing downloaded beyond the small index set. Lands on Explore.
  - **Outfit for offline**: today's Efficient / Comprehensive picks (from the catalog model) and region.
  - Under both, the privacy line: "Reading online asks Wikipedia (and other free libraries) for each page, so they see
    what you open. Granger itself never sees it. You can keep things offline or switch online off any time."
  Skip = Start reading now.
- **Settings → Online** (new section):
  - Use the internet: on / **Never go online** (Never = Local only that can't be toggled from the top bar)
  - Local only (the switch)
  - Big downloads on Wi-Fi only (moved here)
  - Saved for now: size cap, Clear
  - Pictures online: always / Wi-Fi only / never
- **Metered networks**: pictures follow the setting; articles and summaries always load (small).
- **Store listing and site**: "works offline" becomes "works offline, and online before you've downloaded anything".
  Copy is later work, not this project.

## 10. Open questions (decide in the plans)

1. **Kiwix online.** Ask Kiwix whether an app may read `browse.library.kiwix.org` (or another host) for non-Wikipedia
   packs, and on what terms. Until then, those packs are download-only.
2. **Online HTML in the reader.** Wikimedia's HTML is not mwoffliner's. The reader's scripts (contents, read aloud,
   links, picture rule) assume the ZIM's shape. O0 measures which endpoint needs least change (mobile-html is the likely
   one) and whether a small converter is enough.
3. **Revision drift.** The index is built from a September snapshot; live Wikipedia moves. Titles that moved resolve by
   QID; lead and picture come from the index unless missing. Measure how often they disagree in O0.
4. **Remote index speed** (§6 Phase 2): the O0 bar decides.

## 11. Decisions (Neal, 2026-11-18)

1. Online sources are Wikimedia and Gutenberg (and archive.org, LibriVox) directly. No Granger server.
2. The index files: fetch the small set now; spike remote reads of the big ones.
3. Online is on by default at install, with the privacy line shown before the first online read.
4. Saved for now is its own state, never merged into On this phone.
5. Outfitting replaces Readiness: your use plus the nine domains.
6. 1495 goes out as is (it's on internal); this is the next plan, opening with a design round and the O0 spike.

**Builder's calls (for Neal's review):**
- Online is unmarked; the two offline states carry the mark (§3). Fewer marks on the common case.
- A kept full copy beats online even when online; a kept shorter copy loses to online (§4).
- One network gate in the shared client makes Local only honest by construction (§4).
- Saved for now: 300 MB default cap, oldest first.
- Kept articles count toward "your use" but toward no domain (domains stay Granger's baseline, as in the 2026-10-21
  spec's "counting the personal" rule).
- "Keep a subject" offers the Kiwix subject pack when one matches before keeping article by article.

## 12. Order

| Plan | Delivers |
|---|---|
| **O0** spike | Wikimedia endpoints in the reader (HTML shape, scripts, pictures); remote SQLite reads of a .gqid from the GitHub release on the Pixel 6 against the §6 bar; revision drift sample; network gate prototype |
| **O1** online sources | the network gate + User-Agent; online article, summary, pictures, search, `morelike:` Related; article identity by QID; the small index set on first run; the three states in the data layer |
| **O2** marks and keeping | marks everywhere; kept articles store; Keep on article, book, subject, pack; Saved for now cache + cap |
| **O3** Local only | the switch, banner, quick settings tile; browse vs volunteered rules; the "what it would take" sheet; search's online line |
| **O4** Outfitting | the screen (your use, suggestions, domains, on this phone, files); Home strip line |
| **O5** first run + settings | "How do you want to start?", privacy line, Settings → Online, metered rules; walkthrough on a fresh install |
| **O6** (after O0) | remote reads of Top 1M / all Spanish, if the bar holds |

Mockups: the *Granger online and offline* canvas (https://claude.ai/artifact/SXH5nLhtUHj5L8DwzwesWX; first run, an Explore spread online, the same in Local only, a topic
page with marks and Keep, the "what it would take" sheet, Outfitting, Settings → Online).
