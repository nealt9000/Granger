# A living Home, the Timeline and the Atlas — design

**Status:** Draft, 2026-11-07, from Neal's notes the same day, revised after the prototype (§0). Not yet approved.

## 0. After the prototype (Neal, 2026-11-07)
- **The Day becomes the Timeline** (Neal, corrected later the same day). There's no single day any more. Home's hero is **a timeline of every dated article on the phone, filtered by category** (the lanes, §5.2), built on what the prototype proved: the river, the scrub, the pinch. The reachability fixes (§3.1, *Reaching one article*) stand. This makes the time index (G1, §4) a prerequisite of H5, and it replaces the Days-pack proposal (G0, §10). The full-screen Timeline (§5) is the same view opened out, not a separate feature. The On this day feed stays below for phones whose packs include day articles.
- **Wander:** "really nice". Add a search for the starting article (§3.2).
- **Which came first?:** "a solid mini trivia game". Make it the first of a **family of learning games** (§3.3).
- **The Atlas base:** zoom 7, as an **optional download**, not bundled in the app (§6).
**Why now:** Closed testing is open, so the feature freeze lifts for most work. Ask stays an experimental Preview and stops being Granger's focal point. Neal's reference point is what Encarta '95 and '98 did fully offline on one CD: you could explore the whole body of knowledge **through time** (a horizontal timeline from the earliest history to now) and **through place** (a world map with knowledge pinned to where it happened). Granger holds orders of magnitude more than those discs did, but it offers almost none of that human-centred, playful way into it. This spec is about the *feel*, not a copy: no names, art, layouts or features lifted from Encarta.

## 1. The change in one line

**Home becomes a place you play in, not a list you scroll.** Time leads (Neal, 2026-11-07). Place follows once the data exists.

Neal's expectation (2026-11-07): lean towards *novel, engaging, interactive* ways into the packs. Horizontal rails of cards are fine where they fit, but Home mustn't keep recycling that one pattern. Every new Home piece has to answer: *what do your hands do here that they can't do in a list?*

## 2. Goals, non-goals, anti-goals

**Goals**
- Home feels alive on first open, with whatever is on the phone, offline.
- One time axis component, built once in H5 and grown into the full Timeline (§5).
- A time and place index for Wikipedia packs (G1, §4), so the Timeline and the Atlas aren't limited to the 4–5% of articles that carry dates or coordinates in their HTML.
- An Atlas that works on a phone without the 72 GB world map (§6).

**Non-goals (for now)**
- Ask on Home. It stays a Preview behind its own entry.
- New pack types or sources.
- Accounts, sync, sharing or social anything.

**Anti-goals**
- Notifications, streak nagging or "come back" prompts. The keeper waits (brand §2); Home is lively when you're there and silent when you're not.
- Engagement tricks: infinite feeds tuned to hold attention, or variable rewards.
- Animation for its own sake. Motion has to carry meaning: where something sits in time, what links to what.

## 3. H5: the living Home (on the time index, G1)

The existing feeds (Random, On this day, Search) and their settings stay below, unchanged, so testers' setups don't break. The readiness strip and Continue stay too, compacted. Above them sit up to three new pieces. Each one is built on something Granger already reads.

**Clean separation between sections (Neal, 2026-11-07, after trying Wander on the Pixel 6).** As Home gains sections, each must read as its own thing at a glance:
- every section has its own heading row and its own surface or clear spacing band, so one section never runs into the next;
- gestures stay inside their section: a scrub, a drag or a tap in one section never moves or scrolls another;
- each section lives in its own model and composable (as `WanderModel` / `WanderSection` do), and Home only places them;
- the order and the separation are checked together on the phone each time a section is added.

### 3.1 The Day, a timeline you can scrub (the hero)

Today's day article (On this day, `DayPage`) drawn as a **river of years** across the width of the screen instead of three lists:
- **Marks by kind:** events are dots on the line, births rise above it, deaths hang below. The shading along the river shows how dense each era is, so you can *see* how many of the day's events fall in the twentieth century.
- **A lens under your thumb:** drag along the river and whatever is under the lens comes up large, with its year, its line, and the article's thumbnail if it has one (the H2 thumbnail finder). Tap to open the article. Lift your thumb and the lens settles on the nearest mark.
- **Pinch to zoom** from "all of history" to a single century. The scale is compressed for the deep past and linear for recent centuries (§5.1), so 800 BC and 1066 both get room.
- **Yesterday and tomorrow:** swipe the date to scrub through the 366 day articles. This is one gesture, not a date picker.
- **Owl, read this:** an optional owl button reads the item under the lens aloud in the chosen voice (Listen and Piper already exist).

*Revised (§0): the Home timeline.* The sections above describe the prototype's single day. The build drops the day: the river holds **every dated article on the phone**, from the time index (§4), across every Wikipedia pack, with each article shown once where packs overlap.
- **Category chips** above the river turn lanes on and off (§5.2). Each kind keeps its form: points for events, bars for spans, thin bars for lifetimes. With one lane on, the river is that lane; with two or three, they stack and you can compare them.
- **Density by zoom** (§5.1): zoomed all the way out you see eras and the best-known spans; zooming in brings out events, then people. A million articles never draw at once.
- **Opening it out:** an expand control opens the same view full screen (§5) with search and Show in time. It's one view at two sizes.

Every mark is an article you have, so every mark opens, which the day-article version couldn't manage: only about 15% of its lines linked to Top.

*Reaching one article* (Neal: "more accessible"). In the prototype, marks crowd and the thumb hides what it's choosing. Fixes for the plan:
- **A loupe:** while dragging, a magnified strip of the few years around the lens floats above your finger, with the marks spread out and labelled, so you choose from the loupe, not from the crowded river.
- **Step buttons:** ‹ › on the lens card to move one mark at a time, so scrubbing isn't the only way. Their order matches TalkBack's.
- **Nearby, not just nearest:** the lens card shows the marks close to the selected one as a short list of titles, so a crowded year (1948, say) is one tap per person rather than a hunt.
- **A bigger target and snapping:** a lens catch area of 48 dp, and on lift it snaps to a mark that has a picture, if one is within a few pixels.
- **Ranking:** when marks collide, the more notable article (article length, inbound links; §5.1) goes on top and stays visible when zoomed out.

*Why this first:* it's Home's hero, and its axis becomes the Timeline's axis (§5).

### 3.2 Wander, a constellation of links

One article sits in the middle, with the articles its opening paragraphs link to orbiting it, the closest links largest. **Tap an orbiting node and it flies to the centre**, bringing its own links with it. The one you left shrinks into a trail of breadcrumbs behind you. Tap a crumb to step back, or long-press the centre to open the article. It's "See also" as a place to walk around in.
- **Seeds:** **search for a starting article** (Neal, 2026-11-07; the same title search as the library's, across every pack on the phone), the item under The Day's lens, a random article (the Random feed's draw), or a Continue tab. The search field sits above the constellation, and "Somewhere else" stays beside it for a random start.
- **Data:** the article's HTML from the pack, with links taken from the lead section only, so the orbit is meaningful rather than all 400 links. Fetched as you go; nothing is precomputed.
- **Limits:** at most 8–10 orbiting nodes; if there are more, the strongest are chosen by whether a link appears in the first paragraph and whether it appears more than once.

### 3.3 Which came first?, a small game of time

Two (or three) events, cards to drag into order. The reveal **drops them onto the time axis** (the same component as §3.1) with the real gap: "*312 years apart*". Tap either card to open its article.
- **Data:** year-tagged lines from any of the 366 day articles; after G1, the time index (§4).
- **No pressure:** no timer, and a local count of rounds played, never pushed. Rounds get harder by narrowing the gap, not by speeding you up.
- **Accessible:** order with buttons as well as drag; TalkBack reads each card and its result.

#### 3.3.1 A family of learning games (Neal, 2026-11-07)
Which came first? is the first of a family. Every game shares the same rules:
- It's built from what's on the phone and checked automatically, with no model.
- No timers, no pressure; each answer opens the articles behind it.
- One shared round engine: pick, ask, reveal, open.

Candidates, by the data each needs:

| Game | You… | Needs |
|---|---|---|
| Which came first? | order two or three events | time index |
| Guess the year | slide a marker to when it happened; scored by distance, with the real date shown on the axis | time index |
| Who lived at the same time? | pick which person overlaps another's lifetime | time index (lifetimes) |
| Where in the world? | drop a pin; scored by distance | place index, Atlas base |
| Name the picture | choose the title for an article's lead image from four | article thumbnails |
| Odd one out | find the one that isn't linked to the others | article links (Wander's data) |
| Link chain | get from one article to another in as few hops as you can | Wander |

Which came first? and Guess the year come first; they share the time axis. The rest wait on their data. Where the games live (one Home piece that rotates, or a Games entry with Home showing one round) is for the plan.

### 3.4 What H5 doesn't add

No new rails of cards. If a piece is a horizontal scroll of rectangles, it belongs in the feeds below, not in this section.

## 4. G1: the time and place index (build pipeline)

The article HTML in the packs carries too little: across 600 random articles each in Top and History, 4% and 5% had in-page coordinates, 5% and 8% had an infobox year, and 4% and 17% had a Wikidata ID (all measured 2026-11-07). So the index is built offline, like the `.gvec` files, and listed in the catalog beside each Wikipedia pack.

- **Sources:** Wikipedia's coordinates table (`geo_tags`: primary coordinates per page, about a fifth of English Wikipedia's articles), plus a one-time slice of Wikidata (CC0) matched by each item's Wikipedia sitelink. The Wikidata fields used:
  - **Dates:** point in time (P585), start and end (P580, P582), inception and dissolution (P571, P576), birth and death (P569, P570).
  - **Place:** coordinates (P625), the fallback when `geo_tags` has none.
  - **Subject:** instance of (P31), occupation (P106), genre (P136) and field (P101), used for subject filtering (§5.2).
- **Output, per pack:** an SQLite file holding article path, kind (event, person, place, work, polity, idea), year or span, precision (day, year, decade or century), latitude and longitude, and subject tags. It has a year index and an R-tree on coordinates. Measured in §11: a few MB per pack.
- **Languages:** English first, then Spanish (`eswiki` sitelinks), since Spanish is in.
- **Checks before any UI depends on it:** coverage by pack (the share of articles with a time, a place, or both); a hand check of 100 random rows; and the date errors to expect (Julian/Gregorian, BC, "circa").

## 5. The Timeline (after G1)

A full-screen view: **from deep time to now, one horizontal sweep.**

### 5.1 The axis
- A piecewise scale: compressed for geological and prehistoric time, then easing to linear from roughly 3000 BC onward, with the join visible as a soft change in the ruler rather than a jump. It's the same component as The Day (§3.1).
- **Three kinds of marks:** **points** (events), **spans** (empires, eras, wars, movements and works in progress, drawn as bars) and **lifetimes** (people, drawn as thin bars).
- **Density by zoom level:** zoomed out you see eras and the biggest spans; zooming in brings out events, then people. Ranking comes from article length and how often an article is linked to, both measurable at build time.

### 5.2 Filtering by subject (Neal, 2026-11-07)
Neal wants to look at *specific things across time*. Each subject is a **lane** you can turn on, so you can stack two or three lanes and compare them, for example empires above philosophy.
- **Starting lanes:** Philosophy and religion · Civilisations, cultures and empires · Languages · Science and invention · Wars and politics · Literature · Film · Music · Art and architecture · People (with occupation as a sub-filter).
- **Lanes are data, not code:** a mapping table from Wikidata classes and occupations to lanes (empire, religion, language, film, album, philosopher…), kept beside the catalog tools like `kiwix_families.json`. The nine readiness domains are a sensible top level, so the lanes and Readiness speak the same language.
- **Search inside the Timeline:** type "Roman" or "jazz" and the matches light up on the axis.
- **From an article:** a "Show in time" button on any article that has a time row in the index.

### 5.3 Moving between views
Tap a mark to get a preview with an Open button. From any mark you can **Wander** (§3.2) from it, or after §6, show it on the Atlas.

## 6. The Atlas (after G1, and a map spike)

The Kiwix world map is 72.4 GB. That's fine for a base station but not a phone. **Two densities:**
1. **A light world base, always available.** Two candidates for the spike to choose between:
   - **(a)** Natural Earth outlines (public domain): coastlines, countries, major rivers and lakes and big cities, simplified to a few MB and shipped in the app.
   - **(b)** A "World map, light" pack: OpenStreetMap vector tiles capped at zoom 5–6, about 17 MB at zoom 5 and 45–60 MB at zoom 6 per Protomaps' published extracts (ODbL, with attribution).

   Both are enough for pins at world and continent scale, which is the Atlas's job.
2. **Full density where you've added it.** When you zoom into a region whose Kiwix maps pack (`maps_en_*`, already in the catalog) is on the phone, the Atlas switches to that pack's tiles.

**The spike answers three questions:** whether the `maps_` packs' tiles can be read directly (the format and the style); which renderer to use, MapLibre Native or MapLibre GL JS in a WebView like the packs use; and how the switch between densities looks at the seam.

**Pins** come from the index (§4), clustered when zoomed out and filtered by the same lanes as the Timeline (§5.2). A **time slider** along the bottom ties the two views together: *show me philosophy, 500 BC – AD 200, on the map*.

## 7. Constraints
- Offline, on-device only, with no telemetry.
- Smooth on a Pixel 6: scrubbing and pinching at 60 fps, with the time axis drawn in Compose Canvas, not as a list of composables.
- English and Spanish from day one, as the day articles already are.
- TalkBack: The Day can also be read as a list, and the game can be played without dragging.
- Night palette: marks and lanes keep their contrast in both themes.
- Testers' existing feeds and settings survive the H5 update.

## 8. Order (revised §0)
1. **G1 feasibility:** get the Wikidata slice and the coordinates table, build the index for Top and History, and measure coverage: how many articles have a date, how many have day precision, and how many marks a typical day gets across both packs.
2. **H5:** Wander with search first (it needs no index, so it can start while G1 builds), then the Home timeline on the index (category chips plus the fixes for reaching one article), then Which came first? and Guess the year.
3. **G1 production:** every English Wikipedia pack in the catalog, then Spanish; built and signed with the catalog.
4. **The Timeline:** axis, marks, lanes, search, Show in time.
5. **The world map pack** (zoom 7, optional), the map spike, then **the Atlas** and Where in the world?

## 9. Decisions
1. **All three H5 pieces go in the first build** (Neal, 2026-11-07).
2. **The Day sits at the very top** of Home, with Continue and Readiness, compacted, under the new pieces (Neal, 2026-11-07).
3. **The Atlas base is a zoom 7 world map, an optional download in the catalog, not bundled** (Neal, 2026-11-07). Without it, the Atlas falls back to plain outlines (Natural Earth, a few MB) or asks you to download it. **Measured 2026-11-07** (Protomaps build `20261107.pmtiles`, `pmtiles extract --dry-run`): zoom 5 is 15 MB, zoom 6 45 MB, **zoom 7 190 MB**, zoom 8 560 MB. These replace the estimates below. *Earlier note:* Neal wanted the sizes at zoom 7 and 8 before choosing. Measuring them needs `pmtiles extract … --maxzoom=N --dry-run` against the current Protomaps build (`20261107.pmtiles`, 139 GB at zoom 15). Extrapolating from zoom 5 (about 17 MB) and zoom 6 (about 45–60 MB), with each zoom level roughly doubling to tripling the size, gives about 120–180 MB at zoom 7 and about 300–500 MB at zoom 8. These are estimates until measured.
4. **Lanes for the first build** (Neal, 2026-11-08, after §11): Philosophy & religion · Civilisations, cultures & empires · Languages · Film & music · Art & literature · Science & invention · Wars · **Events & politics** (added) · People. "All" shows every dated article, including those in no lane. The finer split in §5.2 (Film apart from Music, People by occupation) can follow once the Timeline has its own screen.
5. **The Wikidata dump stays** on the X10 drive (Neal, 2026-11-08): the full set will feed the Granger home server project.

## 10. Found while prototyping (2026-11-07)
- **The day articles aren't in the packs phones have.** Neither Top (best 50,000) nor History has `November_7` (checked by path and by title), and the Pixel 6 and the emulator hold the same English Wikipedia packs. So the H2 On this day feed, and The Day as specified, have nothing to draw from on most phones. **Superseded by §0** (The Day reads the time index instead). The earlier proposal, kept for the record. **Proposal, G0:** a small "Days" pack built once from the full Wikipedia: 366 day lists with each line's year, kind and main link. That's an estimated 1–2 MB compressed, CC BY-SA. It ships in the catalog and goes on with any Wikipedia, so H5 doesn't have to wait for G1. Measure the size, and check which slices (if any) already include day articles.
- **Only about 15% of a day's lines link to an article in Top (best 50,000)** (71 of 460 on November 7, 30–71 a day across November 4–10), because most lines are births of minor figures. The Day shows every line and marks the linked ones brighter and tappable. With G1 we could rank lines by how notable they are instead.
- **Prototype:** artifact `Xe2YXjeszujR5ZvXgw6xUc` (The Day, Wander and Which came first?, on real November 4–10 lists with articles and pictures from Top (best 50,000) 2026-09). Built by scripts in the session scratchpad.

## 11. G1 feasibility: measured (2026-11-08)

**Run.** Wikidata `latest-all.json.gz` dated 2026-11-06 (146 GB, CC0), streamed on the X10 drive through a filter that kept items with an English Wikipedia article in the eight English packs: 338,368 items, plus 4.5 M class items for the lanes. Measured per pack with scripts in the session scratchpad (`g1measure.py`, `g1sum.py`). The files are on `/Volumes/X10 Pro/granger-g1/`.

**Real articles match almost completely.** Each pack also carries many ~200-byte redirect stubs (Top: 174,135 stubs beside 50,004 articles). With those left out, 99.8–99.9% of real articles match a Wikidata item, so the ZIM path works as the join key.

| Pack | Articles | Dated | Year or finer | Day | Coordinates | BCE |
|---|---:|---:|---:|---:|---:|---:|
| Top (`wikipedia_en_top_maxi`) | 50,004 | 54% | 52% | 36% | 14% | 1,023 |
| History | 48,733 | 68% | 56% | 12% | 10% | 523 |
| Physics | 21,782 | 37% | 36% | 26% | 2% | 34 |
| Mathematics | 23,253 | 22% | 21% | 15% | 0% | 50 |
| Medicine | 72,904 | 7% | 7% | 2% | 3% | 39 |
| Geography | 91,987 | 6% | 6% | 3% | 83% | 34 |
| Chemistry | 9,313 | 6% | 6% | 0% | 1% | 2 |
| Knots | 437 | 3% | 2% | 0% | 0% | 0 |

About 85,000 dated and 91,000 placed articles across the eight packs, so the index is small: an estimated 3–5 MB for dates plus about 3 MB for an R-tree of places, roughly 1–2 MB for Top alone. **The timeline is fed by Top and History; the Atlas by Geography.** Subject packs like Medicine and Chemistry add little to the timeline.

**Dated articles per lane** (an article can sit in several):

| Pack | Philosophy & religion | Civilisations | Languages | Film & music | Wars | Science | Art & literature | People | No lane |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Top | 1,282 | 700 | 223 | 6,882 | 811 | 2,562 | 3,944 | 10,281 | 10,448 |
| History | 3,751 | 1,502 | 36 | 1,252 | 1,453 | 5,067 | 3,650 | 15,499 | 10,991 |

**What the lanes need before production:**
- **People by their main occupation only.** Wikidata lists every occupation, so Einstein lands in philosophy and art & literature, and Carl Sagan in film & music. Use the first one or two occupations, or the ones the article's lead names.
- **An Events & politics lane.** Treaties, revolutions, political parties, sovereign states, government agencies and outbreaks are most of the dated articles in no lane.
- **Drop time pages from the timeline.** "1066 in England" (events in a year: 5,776), years, decades and centuries are the axis, not points on it.
- **Languages means human languages and scripts.** It currently catches programming languages (MATLAB, Ada).
- **Smaller kinds left unplaced:** video games, organisations and universities, awards, villages and towns (founding dates). Leave them in "All" only.
- **Lane roots were checked by label.** The first try used "entity" and "written work" and caught nearly everything, so every root goes through a label check and a traced sample before it ships.
