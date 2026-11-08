# Plan H5a: Wander

**Spec:** `../specs/2026-11-07-living-home-design.md` §3.2, with §0 (search for the starting article).
**Branch:** `h5-living-home` (worktree `.claude/worktrees/h5`).
**Why first:** Wander needs no time index, so it ships while G1 builds (spec §8, step 2). The Home timeline (H5b) and the games (H5c) follow on the index.

**Goal:** a Wander piece on Home. One article sits in the middle with up to eight of the articles its opening links to orbiting it. Tap one and it flies to the centre and brings its own links; a trail of where you've been runs underneath. You can start from a search, from "Somewhere else", or from any article.

## What it builds on
- `FeedDrawer.random` / `ZimArchive.randomPath()` and `IndexPages.isIndex` for a random start.
- `ArticleThumbnails.thumbnail(archive, path, html)` for each node's picture (cached JPEGs, already pruned).
- `ArticleThumbnails.candidates`'s path resolution (`../I/x` from `A/Cat`, `x` from `Cat`): the same rule resolves links.
- `LibrarySearch.titles(query)` for the start search (every OK pack, turns per pack, exact and starts-with first, slices de-duplicated).
- `CardTarget.Article` + `onOpenCard` to open an article in the reader.

## Decisions (proposed; Neal to confirm at the phone check)
1. **Placement:** at the top of Home's list, under any notices (a crash notice, the downloads card, tidy offers), above Continue and the feeds. The Home timeline (H5b) goes above it later. The readiness strip moves below Wander, compacted, in H5b.
2. **Which links orbit:** links from the lead only (the paragraphs before the first heading, at most the first three with real text). A link scores 3 in the first paragraph and 1 after it, plus 1 for each repeat. Years, decades, centuries, dates, files, images and namespaced pages (`Help:`, `File:`) are dropped, and so are index pages. The top eight that **resolve on this phone** orbit.
3. **Links to other packs:** a link resolves in the article's own pack first, then in the library's other encyclopedias in the same language, so History's "Byzantine Empire" can open from Top. A link that resolves nowhere is dropped. When fewer than three resolve, up to three missing ones show dashed, labelled "not on this phone", and do nothing on tap.
4. **A walkable start:** a random start comes from a whole encyclopedia (Top, Everything) when the phone has one, not a subject pack, and needs at least five resolving links. The draw retries up to eight times, then takes the best it found. *(Revised at the emulator check: a Geography pack start gave a one-line village stub.)*
5. **The summary** under the constellation is the lead's first two sentences as plain text (references, IPA and pronunciation brackets stripped), at most 300 characters.
6. **The trail** holds the last 12 centres. Tapping a crumb returns there and drops the crumbs after it. A fresh start (search, Somewhere else) clears it.
7. **State** lives in a `WanderModel` owned by `HomeViewModel`, so it survives rotation and leaving Home. It isn't persisted across process death; Wander starts fresh next launch.
8. **Motion:** the tapped node animates to the centre (spring, about 450 ms). The old centre shrinks towards the trail and fades, and new links grow out from the centre, staggered 30 ms apart. With animations off (the system animator scale is 0), positions jump.

## Tasks

### Task 1: Lead links and summary (pure, tested)
`app/src/main/kotlin/app/granger/home/LeadLinks.kt`:
- `LeadLinks.parse(html, articlePath): Lead`, where `Lead(title, summary, links: List<String>)`, with links as ZIM paths in score order (Decision 2).
- `resolve(href, articlePath)` is shared with `ArticleThumbnails` (moved into one internal helper, `ZimPaths.resolve`), so links and images resolve by one rule.
- **Tests** (`LeadLinksTest`) on fixture HTML from both layouts (`A/Cat` and `Cat`):
  - first-paragraph scoring and repeats;
  - year, century and date links dropped;
  - `File:` and `Help:` links dropped;
  - fragment-only and external links dropped;
  - IPA brackets removed from the summary;
  - a page with no lead paragraph gives no links and an empty summary;
  - a Spanish page works with the same rules.

### Task 2: `WanderModel` (state, seeds, moves)
`app/src/main/kotlin/app/granger/home/WanderModel.kt`:
- **State:** `WanderUi(centre: Node?, orbit: List<Node>, trail: List<Node>, loading: Boolean, hits: List<SearchHit>)`, where `Node(packId, path, title, imagePath, reachable)`. The centre also carries its `summary`.
- **Calls:** `start()` (random, Decision 4), `go(node)`, `back(toIndex)`, `startFrom(packId, path)`, `search(query)` (debounced 250 ms; article hits only, no books) and `pick(hit)`.
- **Resolution (Decision 3):** encyclopedias are the OK Kiwix packs whose family is Wikipedia, in the library's language order.
- **Loading:** orbit thumbnails load after the orbit shows, one by one, so the constellation appears at once and pictures fill in.
- Wired into `HomeViewModel` with the same `archiveOf`, `thumbnail` and `librarySearch` that `FeedDrawer` gets. It starts on first composition of the Wander item, not at app start.
- **Tests** (`WanderModelTest`) with fake archives:
  - start retries until walkable;
  - go pushes the trail;
  - back trims it;
  - a fresh start clears it;
  - a cross-pack link resolves in the second pack;
  - an unresolvable link is dropped, or shown dashed when fewer than three resolve;
  - the trail caps at 12;
  - search returns article hits only.

### Task 3: The constellation (Compose)
`app/src/main/kotlin/app/granger/home/WanderCard.kt`:
- **Layout:** a square `Box` (the width of the list, at most 380 dp). The centre disc is 104 dp, and orbit discs run from 62 dp down to 44 dp by rank, on a ring at 38% of the side, every second one nudged out so labels don't collide. There's a dashed guide ring.
- **Nodes:** discs show the thumbnail if there is one, else the title's initials on the surface container colour. Labels are two lines, ellipsised.
- **Below the constellation:** the summary in Spectral, Open (`onOpenCard(Card(... CardTarget.Article))`), and the trail as a `FlowRow` of text buttons separated by arrows.
- **Motion:** as in Decision 8, with `Animatable` offsets keyed by path, so the tapped node keeps its identity as it moves.
- **Accessibility:** each orbit node is a button labelled "Go to %s". The centre reads its title and summary. The traversal order is the search field, the centre, the orbit clockwise from the top, Open, then the trail.
- **Strings** in English and Spanish (`strings_home.xml`, both locales): the heading, "Somewhere else", "Open", "Your path", "Go to %s" and "Not on this phone".
- **Compose UI test:** tap a node and the centre's title changes and the trail gains a crumb; tap the crumb and you're back.

### Task 4: Search for the start
- **The field:** a search field in the Wander heading row, with "Somewhere else" beside it as an icon button. Typing shows up to six title hits in a dropdown under the field, and picking one starts there (`pick(hit)`).
- **Edge cases:** no hits shows "No articles match"; book hits are left out.
- **IME:** the Search action takes the first hit.
- **Test:** a UI test that types, picks, and checks the centre.

### Task 5: Home placement, then the phone check
- **Placement:** the Wander item goes in `HomeContent`'s `LazyColumn` per Decision 1, with key `wander`. A phone with no encyclopedia hides the item.
- **Gates:** unit tests and `:app:lintDebug` pass.
- **Phone check, Pixel 6:** the debug build, following the shared-phone rules (check `lastUpdateTime` first; never install below the phone's DB version).
  1. **First draw:** a random start shows within about a second, with thumbnails filling in.
  2. **Walking:** ten hops on, a cross-pack hop (Top → History) works, and the trail goes back.
  3. **Search:** "Hagia Sophia", then "Ada Lovelace".
  4. **Rotation:** the state survives.
  5. **Accessibility:** TalkBack's order is right, and with animations off positions jump.
  6. **Night:** the theme reads well.

  Screenshot before each tap (memory: reader clean-up).
- **Hand-off:** findings go into this plan, and the branch goes to Neal before merging.

## Findings
- **Emulator (build 1205):** the fixes in commit 9849c00c: pictures borrowed across packs, starts from a whole encyclopedia in the main language with 5+ links, room for bottom labels, a half-step orbit, and the keyboard put away on pick.
- **Pixel 6 (build 1206, Neal, 2026-11-07):** "working and has a good feel overall." New rule for every later section: clean separation between Home sections (spec §3). TalkBack order, night theme, rotation and animations-off weren't checked separately; they go with the H5b phone check.

## Not in H5a
Read aloud (the owl), which comes with H5b so all three pieces share one button and voice path. The Home timeline (H5b, after G1) and the games (H5c).
