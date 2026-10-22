# Home, Readiness and Onboarding — design

2026-10-21 · Neal Thompson (decisions) · written up by Claude · folded into the repo 2026-10-22

Status: approved in its decisions (Neal, 2026-10-21; see §10). This is the project between the voice (project 3) and Actions (project 4) in the roadmap. Three plans build it: H1 the readiness model, H2 Home, H3 Onboarding. Slim and Park (S1) get their own spec if Neal chooses to build them; the voice has `2026-10-21-voice-design.md`.

## 1. Why

Granger's purpose is to hold knowledge offline for when the internet is gone. Today the app can tell you whether each file is intact, but not **what you hold**: a phone with WikiMed, iFixit and three Stack Exchange sites has no medical-first-aid gap and a large making gap, and nothing says so. Readiness answers "how ready am I?", Home gives the library a front door that shows something new each day without the net, and Onboarding turns an empty install into a useful one in five screens. The three share one piece of data, the **readiness model**: knowledge domains × the packs that cover them × two tiers. Built once, it drives the Readiness chart, the Home strip and the onboarding presets. It is also the beta's first-run experience, which Plan 11 needs.

## 2. Goals, non-goals, anti-goals

**Goals**
- Readiness shows coverage per knowledge domain, with a status (None, Basic, Comprehensive), installed bytes against the comprehensive tier, and one-sentence deficiency signals with an action.
- Storage by source: how much each source (Kiwix, Granger, Archive.org, LibriVox, your own books, models, voices) takes, and what is referenced but not stored (phone media).
- Home as a rail tool: a readiness strip, Continue cards, and daily feeds (Random, On this day, Search) over the installed library; opening the app still resumes the last book.
- Onboarding: Welcome, This phone, Pick a start (Efficient or Comprehensive, from the model), Your situation (region and use cases), Review and start. Skip is always visible.
- Everything computes from the cached catalog and the installed rows. No network call is needed to show any of it.

**Non-goals (this project)**
- Billing, the Pro gate and the telemetry opt-in screen (Plan 10; the opt-in slots into the onboarding flow then).
- Literal compression of packs (measured 2026-10-21: an EPUB shrinks 0.2 % under xz; ZIM clusters are already zstd or LZMA). Slim (flavour swap) and Park are S1, if chosen.
- Models in onboarding. Research is debug-only; one line says the AI librarian is coming and needs the ASK tier.
- Battery estimates, languages beyond English, a light theme.
- A Research digest feed (project 4 adds it as a fourth feed kind).

**Anti-goals: failures even if it technically works**
- **Telling a user they hold something they don't.** Coverage counts only files that are installed and whole. Nothing parked, queued or missing counts (Neal, 2026-10-21, on Park).
- **A home screen that replaces resume.** The library redesign's rule stands: the app opens where you left off. Home is a tool, not the launch screen (§5).
- **A readiness chart that needs the internet.** The catalog is cached; the chart reads the cache.
- **Browsing taxonomy as readiness.** Kiwix's categories (Programming 278, Maps 195, Q&A 181 …) say what is on offer, not how ready you are. The domains are a separate, fixed list (§3.1).
- **Counting the personal.** Archive.org downloads, LibriVox, imports and phone media show their storage but never move a domain's status: they are the user's own curation, not Granger's civilization baseline.

## 3. The readiness model

### 3.1 The nine domains

Chosen for "bootstrap a civilization", not for browsing. The ids are the ones the research library spec (`2026-10-21-research-library-design.md` §3.1) already uses, so the vector set and the catalog agree.

| Id | Domain | Covers |
|---|---|---|
| `medicine` | Medicine & first aid | diagnosis, treatment, drugs, first aid, nursing, emergency medicine |
| `food` | Food, water & survival | growing, preserving, cooking, water, shelter, disaster preparedness |
| `making` | Making, repair & engineering | tools, repair, construction, electronics, machines, energy |
| `science` | Science & math | physics, chemistry, biology, mathematics, textbooks |
| `technology` | Technology & computing | programming, software, networks, documentation, security |
| `maps` | Maps, places & travel | maps, gazetteers, travel guides, geography |
| `reference` | General reference | encyclopedia, dictionary, languages, how society works |
| `literature` | Literature & the arts | fiction, poetry, drama, quotes, music, art |
| `thought` | Religion, philosophy & history | scripture, philosophy, history, biography, mythology |

Order on screen is the table's order.

### 3.2 Where it lives

Two files feed the generator (`tools/catalog-gen`); the generator writes the model into the signed catalog; the app reads the catalog. Nothing in the app hard-codes a domain's packs.

- **`kiwix_families.json`** gains `domain` per family: one of the nine ids, or `null` for families that count in storage only (games wikis, fan wikis, comics). A family may have more than one row when its packs split across domains: Stack Exchange sites are one Kiwix family, so rows with a regex on the site name (`^(cooking|gardening|homebrew|outdoors)\.stackexchange`) go before the `kiwixCategory` catch-all. The `family` name stays the same on every row (nothing in the app reads it today; the generator's report groups by it).
- **`curated.json`** gains a `readiness` block: the domain list (id, title, in order) and, per domain, `essential` and `comprehensive` item lists. An item is a catalog id (`wikipedia_en_medicine_nopic`), a book collection (`@granger-classics`, meaning the collection's books whose own domain is this one), or a family (`family:maps`, meaning any pack of that family: the region's map is chosen in H3). The comprehensive list includes the essential one by construction (the generator adds them).
- **The catalog** gets `domain` on every pack and book entry (books by their Classics category: Scripture & religion and Philosophy → `thought`, the rest → `literature`), and a top-level `readiness` object: `{ "domains": [ { "id", "title", "essential": [...], "comprehensive": [...] } ] }` with the tokens unexpanded. Format version stays 1: every field is additive and older builds ignore unknown keys. Two presets are generated from the model: `efficient` (every essential item that is a pack or book, so H3 can offer it) and `comprehensive`. The `starter` preset stays until H3 decides its fate.

### 3.3 What counts

- **Toward coverage:** Kiwix packs and Granger-curated content (Classics books, Granger-built packs) that are installed and whole (pack status OK; book status OK; a download record `INSTALLED`). A pack installed under an older catalog resolves to its catalog entry by `zimUuid`, then by `zimName` (any flavour of the same content carries the same domain), then by the family regexes; a pack that matches nothing is "Other" and counts in storage only.
- **In storage only:** archive.org books and audio, LibriVox, imports (My books), models, voices. Phone media (the scanner's music and audiobooks) is listed as "on the phone, not stored by Granger": 0 bytes.
- **Flavours are alternatives, not additions.** A tier item names one flavour; any installed pack with the same `zimName` satisfies it, and its bytes are the installed file's.

### 3.4 Status per domain

| Status | Rule |
|---|---|
| **None** | no item of the domain is installed |
| **Basic** | at least one essential item is installed |
| **Comprehensive** | every item of the comprehensive list is installed (a flavour item by any flavour; a `family:` item by any pack of the family; an `@collection` item when every book of the collection in this domain is installed) |

The bar shows installed bytes of the domain (every installed item with that domain, tier or not) against the comprehensive tier's size, with a tick at the essential tier's size. Exceeding the comprehensive size fills the bar.

### 3.5 The tiers (initial lists; data, not code)

Essential is the Efficient start Neal approved (2026-10-21), one small searchable item per domain where that list was thin. Sizes from the 2026-10-22 catalog.

| Domain | Essential | Comprehensive adds |
|---|---|---|
| medicine | `wikipedia_en_medicine_nopic` (1.05 GB) | `wikipedia_en_medicine_maxi`, `wikem_en_all_maxi`, `medlineplus.gov_en_all`, `zimgit-medicine_en`, `wwwnc.cdc.gov_en_all` |
| food | `based.cooking_en_all`, `zimgit-water_en`, `zimgit-food-preparation_en` (0.14 GB) | `appropedia_en_all_maxi`, `foss.cooking_en_all`, `usda-2015_en`, `gardenology_en_all`, `publicdomainrecipes.com_en_all`, `zimgit-post-disaster_en`, `ready.gov_en_all`, `cooking`, `gardening`, `outdoors` Stack Exchange |
| making | `wikipedia_en_knots_maxi`, `ifixit_en_all` (3.59 GB) | `energypedia_en_all_maxi`, `cd3wdproject.org_en_all`, `diy`, `electronics`, `woodworking`, `mechanics`, `engineering`, `3dprinting`, `arduino` Stack Exchange |
| science | `wikipedia_en_physics_maxi`, `wikipedia_en_chemistry_maxi`, `wikipedia_en_mathematics_maxi` (2.29 GB) | `libretexts.org_en_k12`, `_phys`, `_chem`, `_bio`, `_math`, `wikipedia_en_astronomy_maxi`, `wikipedia_en_molcell_maxi`, `planetmath.org_en_all`, `physics`, `chemistry`, `biology`, `math`, `stats` Stack Exchange |
| technology | `wikipedia_en_computer_maxi` (0.91 GB) | `jeffe.cs.illinois.edu_en_all`, `family:devdocs`, `openwrt.org_en_all`, `stackoverflow.com_en_all`, `superuser.com_en_all`, `askubuntu.com_en_all`, `unix`, `security`, `networkengineering`, `raspberrypi` Stack Exchange |
| maps | `family:maps`, `wikivoyage_en_all_nopic` (0.27 GB + the region) | `wikivoyage_en_all_maxi`, `openstreetmap-wiki_en_all_maxi`, `wikipedia_en_geography_maxi`, `travel` Stack Exchange |
| reference | `wikipedia_en_top_nopic`, `wiktionary_en_all_nopic` (11.9 GB) | `wikipedia_en_all_nopic`, `wikibooks_en_all_nopic`, `wikiversity_en_all_nopic`, `citizendium.org_en_all_maxi`, `english`, `ell` Stack Exchange |
| literature | `@granger-classics` (literature sections, 95 MB for all 347) | `wikiquote_en_all_nopic`, `wikisource_en_all_nopic`, `gutenberg_en_lcc-pr`, `literature`, `writing` Stack Exchange |
| thought | `@granger-classics` (scripture and philosophy) | `internet-encyclopedia-philosophy_en_all`, `wikipedia_en_history_nopic`, `philosophy`, `history`, `christianity`, `islam`, `judaism`, `buddhism`, `hinduism`, `hermeneutics` Stack Exchange |

Reference's essential is heavy because Wiktionary is 9.2 GB; it stays because Neal approved the Efficient list and a dictionary is the one book a civilization rebuilds from. Onboarding (H3) shows the size and lets each pack be unticked.

### 3.6 Domain per family (the rule, applied in H1)

By Kiwix category unless a row says otherwise: Medical → `medicine`; Maps → `maps`; Regional → `maps` for Wikivoyage, `reference` for the national information and school wikis; Religion & Philosophy → `thought`; Literature & Drama → `literature` (comics → `null`); Science & Math → `science` (Jeff Erickson's *Algorithms* → `technology`; music theory → `literature`); Programming → `technology`; Reference → `reference` (Smithsonian women's history → `thought`; the World Factbook → `maps`); How-to splits three ways: cooking, gardening, water, canning, preppers, disaster and self-reliance → `food`; repair, knots, energy, radio, appropriate technology → `making`; genealogy and privacy software → `technology`; Games & Hobbies → `null`, except music and instrument wikis → `literature`; Other → by content (privacy guides → `technology`, school resources → `reference`, raga → `literature`, scouting → `food`). Stack Exchange: `technology` by default, with rows for `food`, `making`, `medicine` (medical sciences, fitness), `science`, `maps` (travel, expatriates, GIS), `thought` (philosophy, history, the religions, hermeneutics, mythology, genealogy), `literature` (literature, writing, movies, music, sci-fi, anime, world-building), `reference` (English, the language sites, money, law, politics, academia, parenting, workplace, skeptics), and `null` for games (gaming, board games, chess, poker, puzzling, RPG).

## 4. Readiness, the screen

Top to bottom:

1. **This phone** (as today): tier in plain words, memory.
2. **What you hold offline**: nine rows, one per domain, in §3.1 order. Each row: the domain name, a bar (installed bytes over the comprehensive size, essential tick), the status word, and the installed size. A tap opens the Kiwix screen filtered to that domain (§4.1). Under the rows, up to three **signals**, one sentence each with an action:
   - an empty domain: "No medical reference. Get WikiMed (no pictures), 1.05 GB." → opens the Kiwix screen at the domain with the item in view;
   - a pack whose catalog entry has a newer snapshot and whose installed one is over a year old: "WikiMed is from 2025-03; a newer one is in the catalog." → the pack's Updates row;
   - an installed pack with no full-text index: "iFixit can't be searched: the pack has no index." → no action (information).
   Signals are ordered: empty domains in §3.1 order, then stale, then unsearchable; "Show all" past three.
3. **Storage**: the volumes (as today), then **by source**: Kiwix, Granger (Classics and Granger packs), Archive.org, LibriVox, My books, Models, Voices, each with its bytes; "Music & audiobooks on the phone" with "not stored by Granger".
4. **Files**: the integrity rows exactly as today (models, voices, packs, music & audiobooks), with Check and Download again.

Settings → Readiness and the Library top bar's check-circle keep opening this screen.

### 4.1 The Kiwix screen filtered to a domain

`SourceRoute(scope = KIWIX, domain = "medicine")` opens the Kiwix source screen with the category chips replaced by one chip, the domain's name with ✕ (back to All). The list shows the domain's packs: the tier items first under Recommended (essential, then comprehensive, uninstalled first), then the rest of the domain's packs by category as now. Search within the chip searches the domain.

## 5. Home (H2)

**The rule.** The library redesign says the app opens to where you left off, no home screen; that stays. Home is **a rail tool**: Home · Search · Research · Library · Player. The three cases that land on the Library today (first run, a crash, a reboot) land on Home. A setting, *Open to: Where I left off (default) / Home*.

**What Home shows.**
1. A **readiness strip**: nine dots coloured by status (§3.4) and one suggestion sentence (the first signal); tap → Readiness.
2. **Continue**: where you left off in books, articles and audiobooks (the rail's data as cards).
3. **Feeds.**

**The feed primitive.** One kind of thing, a Feed, stored in Room (`home_feeds`) with three fields: a **scope** (a pack, a collection, a media type, or the whole library), a **kind**, and an optional **query**. Kinds:
- **Random**: a random entry in scope (`ZimArchive.randomPath()` for packs; a random chapter for a book collection; a random album for music).
- **On this day**: the day article ("October 21") every Wikipedia pack carries, with its events, births and deaths; for packs without it, a full-text search for the date.
- **Search**: a saved query whose results rotate day by day ("stoicism" over Granger Classics gives passages; "philosophy" over Wikiquote gives quotes).

Every feed is a row of cards, redrawn **once a day** (seeded by the date), so Home is stable through the day and new each morning. Adding a feed is scope + kind + query; no per-widget code. Defaults on first run: Continue, On this day (the first Wikipedia pack), Random from Wikipedia, Random from Granger Classics, and a Quotes feed if a Wikiquote pack is installed. Project 4 adds a Research digest as a fourth kind without changing the shape.

## 6. Onboarding (H3)

Pulled out of Plan 10 (Neal, 2026-10-21). Billing and the telemetry opt-in stay in Plan 10 and slot into this flow later. Each step is a screen; Skip is always visible.

1. **Welcome**: the brand and one line on what Granger is. Waits on the brand decision (the Vitruvian draft on `p1-brand` against the fan-mark package at `art/android-rebrand/`).
2. **This phone**: the tier in plain words, free storage, what that means in a sentence.
3. **Pick a start**: **Efficient** (the essential tier) or **Comprehensive** (the comprehensive tier), both from the readiness model, each pack untickable, the total against free space.
4. **Your situation**: a region picker (an offline country and state list → the region's `maps_en_<region>` pack and Wikivoyage) and use-case chips (Off-grid & emergencies, Travel, Study, Build & repair, Medical work, Software, Faith & philosophy → their families). The screen shows what each choice adds, with sizes.
5. **Review and start**: the list, Wi-Fi only, Start. Lands on Home with downloads running and a progress strip.

Models are not offered until Research ships; one line says the AI librarian is coming and needs the ASK tier. "Set up suggestions again" in Settings re-runs the flow. The region→maps and family→use-case tables go in `kiwix_families.json` and `curated.json` so one regeneration carries them.

## 7. Built so later projects fit

- The readiness model is catalog data, so the vector set (R1) and the Efficient tier change together without an app release; R1's per-pack domain in the vector metadata switches to the catalog field when H1 lands.
- Home's feed kinds are an enum with a scope and a query; project 4's digest is a fourth value.
- `SourceRoute.domain` is the one filter the Kiwix screen takes beyond its chips; onboarding's "see what this adds" reuses it.
- Slim and Park (S1), if built, change what "installed and whole" means for one pack at a time; the model's rule (§3.3) is the one place to change.

## 8. Constraints

- No network call to show Readiness or Home. The catalog is the cached one; the first refresh after install fills it.
- The chart's computation runs off the main thread with the integrity quick checks, in the same `combine`.
- Format version stays 1. New fields have defaults; an older catalog shows the integrity rows and an empty "What you hold offline" with "Refresh the catalog to see your coverage."
- Kiwix retires snapshots monthly: the catalog is regenerated monthly (K1), and the domain tables ride along.
- The catalog is signed on the Mac (`~/.config/granger/catalog-signing.key`) and is live only once pushed to `main`. R1 Task 10 also edits the catalog (`vectors`); whichever lands second regenerates on top of the other.

## 9. Done means

- H1: the nine domains show on the Pixel 6 with Neal's real library, each with the right status and size; tapping one opens the Kiwix screen filtered to it; the three signal kinds appear when they apply; storage by source adds up to the volumes' Granger bytes (phone media excepted); the integrity rows work as before; the catalog is regenerated and signed with `domain` on every pack and book and the `readiness` object; the project 2 spec's domain ids are unchanged.
- H2: Home as a rail tool with the strip, Continue and three feed kinds; a crash and a reboot land on Home; the book still resumes on a normal open.
- H3: a fresh install reaches a downloading library in five screens, or in one tap of Skip.

## 10. Decisions (Neal, 2026-10-21)

1. Home is a rail tool; opening the app still resumes the last book. First run, crash and reboot land on Home. A setting offers always-Home.
2. The nine domains as listed, with these ids.
3. Granger Classics count toward domain coverage; archive.org, LibriVox, imports and phone media count in storage only.
4. Onboarding leaves Plan 10 now; the telemetry opt-in joins the flow in Plan 10.
5. No literal compression. Slim (flavour swap) is the right direction; Park is not sold: a user must never think they hold something they don't. S1 is written only if Neal chooses it.
6. The voice (project 3) goes before this work; it did (V0, V1 done 2026-10-22).
7. Onboarding waits for the brand iteration Neal is doing in Claude Design.

**Builder's calls in this document (for Neal's review):**
- Tiers live in `curated.json` per pack id, not in the family table: a family (Wikipedia) spans tiers.
- Comprehensive means every item of the list installed, not a byte threshold, so the status can't be reached by downloading the wrong big thing.
- A domain's bytes count every installed item with that domain, not only tier items.
- Classics count as one essential item per domain, satisfied by one installed book of that domain; Comprehensive needs them all.
- Readiness stays read-only: signals navigate to the Kiwix screen rather than enqueuing downloads themselves.

## 11. Order

| Plan | Delivers |
|---|---|
| H1 | The readiness model in the family table, `curated.json`, the generator and the catalog; `domain` and `readiness` in `:core:catalog`; the Readiness chart, signals and storage by source; the Kiwix screen's domain filter; the catalog regenerated and signed; the Pixel 6 walkthrough. Plan: `2026-10-22-plan-h1-readiness-model.md` |
| H2 | Home as a rail tool, the launch fallback, the setting, `home_feeds`, the three feed kinds, the strip and Continue, first-run defaults |
| H3 | The five onboarding screens, the region and use-case tables, Efficient / Comprehensive from the model, Start → Home. After the brand decision |
