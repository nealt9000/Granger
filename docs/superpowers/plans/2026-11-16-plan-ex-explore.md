# Plan EX: Explore (Browse becomes a way into subjects, time and place)

**Goal:** the rail's Browse becomes **Explore**. It's a way into the library by subject and by time, with the packs one tap back under Your sources. Every subject, era and topic leads to topic pages (LL2), so Explore is the front door to the linked library.

**Mockups (real Pixel data):** https://claude.ai/artifact/HG4yrYyYPFssmRvsYscdvq, second row: A Subjects first, A with a subject open, B Time and place first, C the library as a map, Sources.

## Neal's calls (2026-11-16)
1. **The landing is A:** subject doors, then Through time drawn as **B's vertical timeline**, then Your sources as a single line.
2. **C, the map, is a view opened from A,** like the topic page's connections map. It isn't the landing.
3. **The packs move one tap back** (Sources), and open as sites, as Browse does today. Non-Wikipedia packs become subject doors by their catalog domain (Health, Cooking & food, Knots).
4. **No hand fixes per article.** The mockup data exposed basic misfiles (Einstein in Philosophy, Butter in Science, Reagan in Music & film). The subjects have to come right by rule and be measured, before any UI is built.

## Why the current lanes aren't enough
The Timeline lanes (`tools/timeindex/lanes.json`) were traced item by item (2026-11-16). They have three general faults:
- **Occupations:** any of a person's first two P106 values counts, and Wikidata keeps them in no particular order. Reagan is a "television actor", Einstein a "philosopher of science", Mandela an "autobiographer". Spider-Man (fictional) is a "scientist".
- **Depth and broad roots:** the walk goes up to 10 steps, and roots like *science*, *chemical compound* and *government agency* catch too much. Butter reaches *chemical compound* in 8 steps, Tokyo reaches *government agency* in 7, and the Gregorian calendar reaches *science* through *chronology*.
- **Missing subjects:** there's no Food, Places, Nature, Technology, Sports or Health lane, so those articles drift into whatever a deep walk finds.

Coverage is also short: only 26,592 of Top's 50,000 articles are dated, and 8,993 of those have no lane.

## Tasks
### Task 0: Subjects spike (Mac, measured) — the gate for everything below
Give **every** Top article (en, then es) **one main subject**, plus up to two secondary ones, by rule:
1. **A subject list** covering the undated library too: People is a facet, not a subject. A person's subject is their field.
2. **Class rules:** P31/P279/P136 walk to subject roots with **depth ≤ 3**, narrower roots, and the broad ones removed.
3. **People:** the main subject comes from field of work (P101) and occupations, weighted by agreement with the article's first sentence ("…was an American politician and actor who…"). Fictional characters ignore P106.
4. **Meaning check:** average each article's shipped `.gvec` rows into one vector. Each subject's centroid comes from articles that the rules tag with high agreement. When the rules give several candidates, or none, the nearest centroid decides. When the rules and meaning disagree strongly, the article is flagged in the report.
5. **Measure:**
   - a random 200 graded on a page like LL1's (main subject right or wrong);
   - the known misses list (Einstein, Newton, Reagan, Mandela, Caesar, Spider-Man, Butter, Tokyo, Gregorian calendar, Hamas, Photosynthesis…) must all come out right, with no special-casing.
   - **Bar:** ≥ 95% of the 200 right, and coverage ≥ 98% of articles.
6. **GO/NO-GO** to Neal with the numbers.

The Timeline lanes can then take the same subjects (a lanes v2), so the shipped Timeline gets the fixes too.

### Task 1: The subjects sidecar (Mac, then catalog; Neal signs)
- Where the subject lives: a `subjects` table keyed by QID, delivered either in the QID index (it already covers every article) or in the time index. The choice is made after Task 0, following spec §5 (one sidecar format, QID the only join key).
- Built and checked on the desk, with upkeep rebuilds like the other sidecars.

### Task 2: Explore landing (phone, tested)
- Replaces Browse on the rail (keep the RailTool id, and migrate pinned or arranged rail state).
- Subject doors with counts and three examples each, Through time as the vertical timeline, Your sources line, Get more.
- Spanish from day one.

### Task 3: A subject opened (phone, tested)
- Era filter chips with counts, most-linked first or oldest first, rows open topic pages, In your books (books need subjects too: from their QID's subjects or the MentionIndex), Wander and Quiz.

### Task 4: Sources (phone, tested)
- The old Browse list, grouped by domain, one tap from Explore, with Downloads, Readiness and Import in its menu.

### Task 5: The library as a map (phone, tested)
- C opened from A, built on the topic map's component.

### Task 6: Walkthrough (emulator, then the Pixel) and checkpoint with Neal

## Not in EX
Places as a subject door beyond counts (the Atlas is LL6); Trails (LL6); the online layer (LL2b).

## Progress (2026-11-16)
- **Task 0, runs 1–9** (`c3b4afa2`, `e43e19c3`; work files on X10 `granger-ll/ex0`):
  - **The method now:** class rules (depth ≤ 3; a root 2+ steps closer wins), occupation articles take the occupation's subject, and a neighbour vote among single-subject articles (people against people, things against things, each vote weighted against its subject's size). For people, the lead's role words also count and can name a subject Wikidata's occupations miss. Food became "Food & farming".
  - **Coverage:** 49,157 of 49,955 Top articles get a subject; the other 798 are list pages, which are dropped.
  - **Known misses:** all 60 come out right.
  - **My strict grades:** 93% on run 8's sample (seed 20261116), then about 97% on the same sample after the fixes. Both samples (seeds 16 and 20261116) were used to tune, so they don't count towards the bar.
  - **The held-out 200 for Neal** (seed 911, run 9): https://claude.ai/artifact/7x3W1iie6Rt9Tbf5xzK7Zp (db collection `grades`).
- **Catalog field agreed with KB2 (granger-b9):** `subjects: [ids]` on pack and book entries. The ids are final once Task 0 passes.
