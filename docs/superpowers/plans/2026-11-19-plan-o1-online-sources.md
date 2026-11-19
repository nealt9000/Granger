# Plan O1: online sources

2026-11-19 · spec `docs/superpowers/specs/2026-11-18-online-offline-design.md` (§3, §4, §6 Phase 1, §12) · spike results
`docs/superpowers/plans/2026-11-18-plan-o0-online-spike.md` · spike code on branch `o0-online-spike` (2ae2741b, debug only)
· canvas https://claude.ai/artifact/SXH5nLhtUHj5L8DwzwesWX

**Goal:** a phone with no Wikipedia downloaded gets the full Granger experience online. That means Explore, topic pages
(header, people and events, rooms, Related), the reader with Contents, read aloud and links, Search, Timeline, Wander and
games, with articles and pictures read live from Wikimedia. All of it goes through one network gate, and the data layer
knows, for every article, whether it is on this phone or online.

**Not in O1:**
- Marks in the UI, Keep, Saved for now (O2).
- The Local only switch, banner and grey-outs (O3).
- Outfitting (O4).
- First run, privacy line and Settings → Online (O5).
- Remote big indexes (O6).

O1 builds what those plans stand on.

**Behind a flag until O5.** `Features.ONLINE` is on in debug and off in release. Release turns it on in O5, together
with the first-run choice and the privacy line (Decision 3: online on by default, with the privacy line shown before the
first online read). That way O1 can merge to main in pieces without changing what testers get.

## Builder's calls (for Neal's review)

1. **Online Wikipedia is a source, not a pack row.**
   - `wikipedia_<lang>_online` is registered in a small `OnlineSources` registry, not in the `packs` table.
   - The pack table, the Library screen and storage counts stay about files on the phone.
   - Lookups that need any source (Continue, the rail, history, language and name lookups, Home's encyclopedias) ask
     `knownSources` = packs + online.
2. **Wikipedia online exists for each knowledge language** (Settings: English, Spanish). There's no Kiwix online
   (spec §10.1).
3. **Interim HTTP cache:** a 50 MB OkHttp disk cache, so Back and a second visit don't fetch again. O2 replaces it with
   Saved for now (300 MB, counted and clearable).
4. **Related:**
   - A pack page keeps its vectors (they work offline, and today's results are good).
   - An online page uses `morelike:`.
   - A pack page with no vectors, when online, also uses `morelike:`.
5. **Being polite to Wikimedia:**
   - At most 4 requests at once to each Wikimedia host.
   - One User-Agent for every host the app talks to: `Granger/<version> (https://opengranger.org;
     feedback@opengranger.org)`. It replaces today's archive.org agent, which points at the GitHub repo.
   - No API key, no login.
6. **Pictures named in our indexes come from Commons by file name.** The index stores ZIM image paths. Online they
   become `commons.wikimedia.org/wiki/Special:FilePath/{name}?width={px}`, after undoing mwoffliner's renames (O0 Task
   3: 40/40 resolve).
7. **The small index set is bound to the online source**, not to a pack file.
   - Top `.gqid` (17 MB en / 16 MB es) and `.gtime` (2.3 / 2.2 MB) are downloaded once per knowledge language.
   - If the user later downloads a Top pack of the same snapshot, the pack uses the same files. Nothing is fetched
     twice.

## Tasks

### Task 0: branch and carry the spike over

- Branch `o1-online-sources` from main (3f83373f). Worktree `.claude/worktrees/o1`.
- Bring over `online/OnlineWikipedia.kt` and `online/NetworkGate.kt` from 2ae2741b.
- Drop the spike-only wiring: the `MainActivity` debug extras and the `GrangerNavHost` `openOnlineTitle` effect. The
  `local_only` extra stays as a debug hook until O3.
- Add `Features.ONLINE = BuildConfig.DEBUG`.

### Task 1: the network gate and the User-Agent, for real

- Move `NetworkGate` to `core/downloads` (it sits beside the shared client's other interceptors). It takes its state
  from a `NetworkPolicy` flow:
  - `online`
  - `localOnly` (O3 switches it)
  - `never` (O5's "Never go online")
- **Placement:** `addInterceptor` goes first on the shared client, before `ArchiveUserAgent`. A refusal throws
  `LocalOnly : IOException`, so every caller's existing IOException path handles it.
- **One User-Agent interceptor** for every host, with the agent above. `ArchiveUserAgent` becomes `GrangerUserAgent`.
- **The audit, as a test:** a unit test lists every `OkHttpClient` built in `app/` and `core/` (a source grep in
  `NetworkAuditTest`) and fails on any client not derived from the shared one.
  - O0 found that everything already goes through the shared client: catalog, `FileDownloader`, archive.org,
    LibriVox.
  - The WebView blocks everything it doesn't serve itself.
  - The player plays only local files. When LibriVox streaming comes, it must use an OkHttp data source on the shared
    client. Recorded in the test's comment.
- **Downloads in progress** treat `LocalOnly` as "paused, waiting for network", not as a failure. Check how
  `FileDownloader` classifies IOExceptions, and add a test.
- **Tests:**
  - The gate refuses in `localOnly` and `never`.
  - The gate passes when online.
  - The User-Agent is set on every request.

### Task 2: `WikimediaClient`, the one place that talks to Wikimedia

`app/granger/online/WikimediaClient.kt`, on the shared client:

| Call | Endpoint | Use |
|---|---|---|
| `html(lang, title)` | `/w/rest.php/v1/page/{title}/html` | the reader |
| `summary(lang, title)` | `/api/rest_v1/page/summary/{title}` | headers, cards, plates (title, extract, thumbnail, `wikibase_item`, revision) |
| `titles(lang, q, n)` / `pages(lang, q, n)` | `/w/rest.php/v1/search/title` and `/search/page` | Search |
| `related(lang, title, n)` | `/w/api.php?action=query&list=search&srsearch=morelike:{title}&srnamespace=0` | Related |
| `random(lang)` | `/api/rest_v1/page/random/title` | Wander's fallback |
| `picture(url)` | `upload.` / `thumb.wikimedia.org`, Commons `Special:FilePath` | pictures |

- Each Wikimedia host gets its own `Semaphore(4)`.
- Redirects are followed, and the final title is returned, so a moved title resolves.
- A summary result carries `wikibase_item` (the QID), which links an online read to the index and to history (Task 5).
- Every call is blocking and asserts it is off the main thread in debug (`check(Looper.myLooper() != mainLooper)`). That
  catches O0's main-thread mistake at its source.
- **Tests:** recorded responses (Moon, Knowth, a redirect, a missing page, an es page) under `app/src/test/resources/online/`.
  They run against `MockWebServer`, never the live site.

### Task 3: online articles in the reader

- **`OnlineWikipediaArchive`** reads through `WikimediaClient`. `transform` keeps the O0 rules:
  - drop `base`, `link[rel~=mw:]`, `meta[property]`, scripts and edit links;
  - `./Title` becomes `Title`;
  - pictures become `_img/<host>/<path>` without the query, and `srcset` goes.

  Two changes:
  - Use the `<link>` to `_granger/online.css` instead of inline CSS.
  - Keep `srcset`'s 2x candidate as `src` when the screen is dense, so pictures stay sharp.
- **`assets/reader/online.css`:** Wikipedia's look in Granger's reader styles. It covers infobox, figures, tables that
  scroll, navboxes hidden, references small, and hatnotes. It follows the reader's Field/Night/Match variables, the same
  way the reader colours pack pages today.
  - `ZimWebViewClient.appAsset` serves `.css` as `text/css` (today it serves only `.js` with a type).
  - Night must darken online pages. Check this on the Pixel.
- **Links never touch the network on the main thread** (O0 found that `ZimResolver.linkAction` → `archive.entry()` →
  `read()` → fetch). Two changes:
  1. `OnlineWikipediaArchive` overrides `entry()` to answer `text/html` from the path alone, with no fetch.
  2. `ZimResolver.linkAction` skips `entry()` for an archive with `remote = true` (a new `ZimArchive` property, false by
     default).
- **External Wikipedia links** (`https://en.wikipedia.org/wiki/X`, and `es.` links when Spanish is a knowledge
  language) open as online pages in the reader. Any other host stays external, as today.
- **Transform cost** (O0: ~0.5 s on Moon, 3.9 s on a cold first run):
  - Run it on the reader's IO dispatcher (already true through `shouldInterceptRequest`).
  - Warm Jsoup at app start, off the main thread: one tiny parse.
  - Measure again. Bar: Moon ≤ 0.6 s fetch+transform warm, ≤ 1.5 s cold.
- **Tests:**
  - `transform` on recorded Moon and Knowth: link forms, picture paths, the stylesheet link, h1.
  - `linkAction` on a remote archive returns `Load` without calling `read`.

### Task 4: online as a known source

- **`OnlineSources`** (`app/granger/online/`) holds one `OnlineSource(id, lang, title = "Wikipedia")` per knowledge
  language while `Features.ONLINE` is on.
- It's exposed as `Library.knownSources: Flow<List<SourceInfo>>`, the packs plus the online sources.
  `OnlineLibrary.archive`/`cachedArchive` answer the online ids, as in the spike.
- Switch these places to `knownSources`:
  - `RailViewModel`: "Removed pack" becomes "Wikipedia".
  - `HistoryViewModel`.
  - `CollectionScreen`.
  - `TopicSource.languageOf` and `packIds`.
  - `WanderModel.encyclopediasOf`, so Home, Wander, Timeline and games see online Wikipedia.
  - The topic page's other-language link.
- **Rail tabs:** an online article's tab shows like a pack article's, with the article title over "Wikipedia".
- **Tests:**
  - Continue and history name online articles "Wikipedia", not "Removed pack".
  - `encyclopediasOf` includes online sources only when `Features.ONLINE` is on.

### Task 5: article identity and which copy opens

- **`ArticleCopies`** (`app/granger/online/ArticleCopies.kt`): for (lang, QID), or (lang, title) when the QID is
  unknown, it lists the copies:
  - pack copies, from `QidIndexes.articlesOf(qid, lang)`, each tagged full (maxi flavour) or short (mini, nopic);
  - the online copy, when `Features.ONLINE` is on.
- **`open(lang, qid, title)`** picks in the spec's order: a kept full copy, then online when online, then any kept copy,
  then `NeedsInternet`.
  - "Online" means the gate is open. It does not mean there's a signal: a failed fetch falls back to a kept short copy
    in the reader. Add a test.
- Use it everywhere an article is opened by topic rather than by pack:
  - Explore plates and spreads;
  - topic page links (people, events, Related, broad-topic parts);
  - Wander;
  - Timeline;
  - games' "read about it";
  - search results that came from online;
  - history reopen.

  Pack-to-pack links inside the reader stay in their pack, as today.
- **History keys** stay `(packId, path)`, so DB 16 needs no migration.
  - An online read records `wikipedia_<lang>_online` / title.
  - "The same article" across copies is decided by QID through the index or the summary's `wikibase_item`, when Outfitting
    counts your use (O4). Recorded here so O4 doesn't add a column it doesn't need.
- **Tests:** the order table, every row of spec §4 "Which copy opens", plus the fetch-failure fallback.

### Task 6: the topic page online

- **Header from the summary endpoint** for an online article. This fixes O0's double fetch, and the lead now comes from
  live Wikipedia (O0 Task 3: leads drift). `topicHeader` and `articleLead` in `GrangerApp` branch on `archive.remote`:
  - remote: `WikimediaClient.summary`;
  - pack: `ArticleCards.card` as today.

  Summaries are cached in `article-cards.db` with a 7-day age.
- **Pictures from our indexes:** `ArticleThumbnails.picture(archive, image)` for a remote archive maps the ZIM image path
  to Commons `Special:FilePath` at the plate's width (port `drift.py`'s rename undo).
  - This covers broad-topic figures, plates and the quilt.
  - Tests: the rename table (`.svg.png` → `.svg`, names decoded twice, `.webp` suffixes).
- **Related online:** in `TopicSource`, a `related` source is chosen per page (builder's call 4). `morelike:` results are
  filtered by the adult-content filter through their QID (from `pageprops`), as Related is filtered today.
- **Articles outside the Top index** (most of Wikipedia) get the lighter page: summary header, Related, and quotes if
  books are kept. They have no people, events or rooms.
- **The bar (spec §6),** measured on the Pixel 6 on Wi-Fi with `Perf.time`:
  - header ≤ 1 s;
  - Related ≤ 3 s;
  - a broad topic's full page ≤ 2 s.

### Task 7: Search online

- **New groups, below the local ones in `SearchViewModel.State`:**
  - `onlineTitles`: rest.php `search/title`, top 8.
  - `onlinePages`: `search/page`, top 8, with its excerpt.
- **De-duplicate:** an online hit whose title (or QID, when known) is already a local hit is dropped. The local copy
  wins its place.
- **Timing:** the online search starts with the local one, on the same debounce. Local results never wait for it.
- **When the gate refuses or there's no signal,** the online groups are empty and nothing is said. O3 adds "Online
  results appear when you're connected."
- **No mark.** Online is unmarked; local results get their dot in O2.
- **Tapping a hit** goes through `ArticleCopies.open` (Task 5), so a hit for an article that is also on the phone opens
  the right copy.
- **Tests:** the merge, the de-duplication, and that a slow online search doesn't block local results.

### Task 8: the small index set on first online start

- **When it runs:** on the first start with `Features.ONLINE` and the gate open, for each knowledge language, queue that
  language's Top `.gqid` and `.gtime` (from the catalog's `wikipedia_<lang>_top_mini` entry: 16.9 MB + 2.3 MB en,
  15.8 MB + 2.2 MB es).
  - On Wi-Fi it starts quietly.
  - On mobile data, Explore shows one line, "Explore needs a 19 MB index. Get it now?". O5 moves the ask into first run.
- **Download records:** a new parent kind, `ONLINE_INDEX`, with item ids `wikipedia_<lang>_online.gqid` /
  `.gtime`.
  - `Sidecars.reconcile` keeps them while their online source exists, and doesn't remove them as orphans.
  - The catalog's later snapshots replace them the way a pack's sidecars are replaced.
- **Binding:** `ReferenceLinksSource.qidFiles()` and `listedTimeIndexes()` bind these files to
  `wikipedia_<lang>_online`. `QidIndex.open` skips the pack-sha check for an online binding (there's no pack file to
  match). The paths in the index are titles, which the online archive takes as is.
- **Shared with a later pack:** when a Top pack of the same snapshot is installed, its sidecar entry points at the same
  file (same sha256), so `Sidecars` doesn't fetch it again. Both bindings open it.
- **They count as On this phone** (Task 9).
- **Tests:**
  - Queued once per language.
  - Kept by `reconcile`.
  - Bound to the online id.
  - Reused when a Top pack arrives.

### Task 9: the three states in the data layer

- **`Availability`** (`app/granger/online/Availability.kt`): `OnPhone(full: Boolean)`, `Saved` (always false until O2),
  `Online`, `Nowhere`. It's computed from `ArticleCopies` for an article, and from the shelf and downloads for a book.
- **`availability(lang, qid, title)`** and a batch form for a list (a spread's plates), cached per screen.
- **O2's marks read only this,** so no screen decides availability on its own.
- **Tests:**
  - A maxi pack gives `OnPhone(full)`.
  - A mini pack gives `OnPhone(false)`.
  - No pack gives `Online`.
  - Online off and no pack gives `Nowhere`.

### Task 10: Pixel 6 pass

- **Setup:** a debug switch, **Preview: no Wikipedia on the phone** (Settings → Debug). It hides installed Wikipedia
  packs from `knownSources`, `QidIndexes` and vectors, so the phone shows the no-download experience without removing
  Neal's packs.
  - Install with `adb -s` on the Pixel only.
  - Check `lastUpdateTime` and the DB version first; DB stays 16.
- **Walk through, in English and then Spanish:**
  - Explore landing, a room, a subject, a broad topic.
  - The topic page, then Read the article.
  - Contents, read aloud (only after asking Neal, because the mini-player holds his audiobook), links.
  - Search, Timeline, Wander, a game, Continue.
  - Night and Field.
- **Timings** against the bars in Tasks 3 and 6.
- **Debug Local only on:** nothing crashes, online parts fail quietly, and downloads pause. O3 makes it look right; O1
  only makes it safe.
- **TalkBack by tree:** online pages read as pack pages do.

### Task 11: merge

- Run the full test suite, then merge `o1-online-sources` to main as a fast-forward.
- `Features.ONLINE` stays off in release.
- Delete the `o0-online-spike` branch and its worktree (check `git status` and the branch tip first, never `--force`).
- Update the roadmap.

## Order and size

Tasks 1–4 first. They give a working online reader behind the flag, and each is a few hours.

Tasks 5–7 make it the app. Topic page and Search are the bulk.

Task 8 can run beside 5–7.

Task 9 is small.

Then the Pixel pass.

## Results (2026-11-19, branch `o1-online-sources`)

Tasks 0–9 built, each with unit tests. Commits: bd94a32c (0–4), 2aadfcaf (6, 8), 3d6d798d (5, 9), Search (7), 00d8a8d9 (the
preview), 11a2ab63 (Pixel pass fixes).

**Pixel 6 pass (Task 10, debug 1506+, DB 16, "no Wikipedia on the phone" preview on; English):**

| Check | Result |
|---|---|
| Online index set | Top .gqid + .gtime (19.3 MB) downloaded on its own; English only, as the phone's knowledge languages |
| Explore landing | today's spread with Commons figures, 17 subjects / 108 rooms from the online index; door pictures 0.15–0.5 s each (summary) |
| Topic page, plain article (Knowth, outside the index) | header 1.08 s (with its 960 px picture), Related by `morelike:` 1.9 s, pictures |
| Topic page, broad (University / Capital city / Genocide) | header 0.3–0.5 s; Related 2.5–4.3 s; the broad parts 11.4 s → 7.3 s (figures together) → **5.1 s** (figures straight from Commons' thumbnail servers) |
| Reader | Field and Night styles (online.css), infobox, pictures at 2x, Contents, section jump, link tap 0.6 s, no main-thread fetch |
| Search | Wikipedia's titles and pages under the phone's, "Wikipedia" source line, Knowth first |
| Continue / rail | "Wikipedia", not "Removed pack"; pictures from the summary |
| Games, Timeline, Wander | from the online index, pictures online; Wander now starts from the index (well-known articles) |
| Local only (debug) | nothing crashes; refused before any connection; an online article says "Needs internet" under its own title |
| Packs back (preview off) | Explore opens the maxi pack's copy, no online fetch; broad page 1.3 s |

**Bars:** header ≤ 1 s holds for broad topics (0.3–0.5 s) and is just over for a plain article with a big picture (1.08 s).
Related ≤ 3 s holds alone (1.9–2.5 s) and slips to 4.3 s while a broad topic's figures share the host. The broad parts
miss their 2 s (5.1 s): the item cards are one summary request each.

**Left for O1's tail or O2:**
- Broad topics: cards in one batched request (`action=query&prop=extracts|pageimages|pageprops`, 20 titles a call).
- Spanish online and read aloud not checked on the phone (Spanish isn't a knowledge language there; read aloud would
  take the mini-player from Neal's audiobook).
- Local only on volunteered screens: Wander waits on "Finding somewhere to start…", Continue loses its pictures (O3).
- Home's feeds (On this day…) still draw from packs only.
- TalkBack not checked.
