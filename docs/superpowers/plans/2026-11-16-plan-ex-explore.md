# Plan EX: Explore (Browse becomes a way into subjects, time and place)

**Goal:** the rail's Browse becomes **Explore**. It's a way into the library by subject and by time, with the packs one tap back under Your sources. Every subject, era and topic leads to topic pages (LL2), so Explore is the front door to the linked library.

**Mockups (real Pixel data):** https://claude.ai/artifact/HG4yrYyYPFssmRvsYscdvq, second row: A Subjects first, A with a subject open, B Time and place first, C the library as a map, Sources.

## Neal's calls (2026-11-16)
1. **The landing is A:** subject doors, then Through time drawn as **B's vertical timeline**, then Your sources as a single line.
2. **C, the map, is a view opened from A,** like the topic page's connections map. It isn't the landing.
3. **The packs move one tap back** (Sources), and open as sites, as Browse does today. Non-Wikipedia packs become subject doors by their catalog domain (Health, Cooking & food, Knots).
4. **No hand fixes per article.** The mockup data exposed basic misfiles (Einstein in Philosophy, Butter in Science, Reagan in Music & film). The subjects have to come right by rule and be measured, before any UI is built.
5. **After Task 2 on the Pixel (2026-11-16):**
   - **Rail icon:** Explore takes a compass before we ship.
   - **The final landing** is editorial, not a grid: an asymmetric flow you read like a page, more alive, curated, with pictures that frame their subject and stand for the whole subject or topic. That's a design pass before the tester build. The Task 2 landing is its working skeleton.
   - **Door order by size is fine** for now.
   - **Spanish** gets looked at in its final form.

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
- Reuse KB2's shared composables in `theme/GroupedRows.kt` (GroupLabel, SectionHeader, GroupedRows, GroupedItem, GroupedRow, Chevron, OutlineChip; kb2-ux `ca0a97cb`) rather than forking them.
- Keep the pack-row long-press entry "Files in this pack (N)" (PackFilesRoute, KB2 `382c433d`).
- The Books row counts every book collection on the phone (Classics, Gutenberg, Kiwix libraries, manifest packs, African Storybook).
- Build after KB2 merges.

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
- **Round 1 (Neal, seed 911, run 9, one subject):** 93.5% of judged, 47 not sure: forcing one subject was the fault. Several subjects per article (up to 3) from run 10.
- **Round 2 (Neal, seed 4242, run 11, several subjects):** https://claude.ai/artifact/FYTgxmhcAr9SRtjNt8revS. 198 graded: **220 right, 4 wrong, 21 not sure = 98.2% of judged marks** (main subject 185/188 = 98.4%); coverage 98.4%. **Passes the bar.** Gaps: 25 items missing a subject (History 14, Society 8, Business 3); "not sure" mostly a secondary Science on non-science articles (Franz Ferdinand, Julia Domna, European integration, Theodicy, Salazar).
- **Fable as a grader:** agreed on 207/245 marks and caught 3 of Neal's 4 wrongs, but flagged 11 marks Neal accepted, and found only 4 of Neal's 14 missing History. Good as a strict screen, not a stand-in for Neal.
- **Neal accepted Fable's 6 extra wrongs** (NYPD/Mercosur Places, Bob Lazar/Arroyo Science, ISIN Technology, Namokar Mantra Music): round 2 held-out = **214 right / 10 wrong, 95.5%. Passes.**
- **Run 15 fixes** (`e227798e`): stop classes, History rule (dated past events, dead public figures), Places as a second subject needs coordinates, root/dual fixes. On the round-2 sample 97.8% (tuned on it, so not held-out); 60 known all right.
- **Task 0: GO. Ids locked** (subjects.json v1): history, society, business, places, science, nature, health, technology, art, literature, music, film, sports, games, religion, languages, food. KB2 told.
- Still wrong after run 15: Valentino Film & TV, NYPD Places, Death row Places (meaning only), Bob Lazar Science (Wikidata says physicist), ISIN Technology (meaning only).
- **Task 1 done (desk), 2026-11-16:**
  - **Where:** a `subjects(qid, ord, subject)` table in each pack's `.gqid` (ord 0 = main), indexed by subject. It's an extra table, so `schemaVersion` stays 1 and shipped apps open the files as before; `builderVersion` 2, meta `subjectsVersion`, `withSubjects`, `packSubject`.
  - **How:** `tools/subjects/build.py` writes one `subjects-v1.sqlite` keyed by QID (X10 `granger-ll/`): the whole method for Top en (49,157) and Top es (27,862 items not in en Top; `roles_es` added), rules alone for the other 2.74M items of the 44 packs. One item, one set of subjects in every pack and language. `tools/qidindex/build.py --subjects` attaches it on rebuild; `add_subjects.py` attached it to the 44 published files without their ZIMs (sourceSha256 unchanged).
  - **Subject packs:** an article the rules miss takes the pack's subject (`subjects.json` `packs`: chemistry → science, movies → film…), the same map the catalog's `subjects` field will use for Wikipedia packs once KB2's field is merged. Knots and the Ray Charles category pages have none.
  - **Coverage:** Top en 98.4%, Top es 99.7%, every subject pack 100%, Top 1M en 83.3% and es all 83.0% (rules alone: 0 wrong marks on the graded sample, but ~15% of articles get nothing). Vectors for the big encyclopedias would close that gap later.
  - **Check:** `tools/subjects/check.py` (unknown ids fail; floor for encyclopedias and subject packs; `--graded tools/subjects/graded-2026-11-16.json`: Top en 97.8%); in `kinds.json`'s gqid check. All 44 pass both checks. Tests: `tools/subjects/tests`, `tools/qidindex/tests`.
  - **Sizes:** +38% on average (Top 5.6 → 7.7 MB, Top 1M 95 → 124 MB, es all 198 → 260 MB); 559 MB for the 44.
  - **To publish (Neal):** upload `granger-ll/gqid-v2/*.gqid` to release `sidecars-2026-11-16`; the catalog already points there (attached, unsigned); check-links, then sign.
- **Catalog signed** (`915b8986`): the 44 `.gqid` with subjects on release `sidecars-2026-11-16` (Neal uploaded; sizes and one hash checked; check-links 3,221 OK).
- **Task 2 built, Pixel-checked (2026-11-16):** `8828804b` + fixes.
  - **Explore** replaces Browse on the rail behind `Features.EXPLORE` (rides with `LINKED_CLASSICS`, so debug only for now); same RailTool id, label Explore/Explorar, globe icon kept (no extended icon set). Your sources opens the old Browse list unchanged until Task 4.
  - **Landing:** summary line (distinct articles · packs), subject doors (2-column cards: picture, count, three examples), Through time as B's vertical timeline (7 eras, count, years, three examples, picture), Your sources card. A door opens a basic subject page: packs on the subject, then articles (main subject first, then most-linked), 60 at a time, each opening its topic page. Task 3 adds the era chips, sort, In your books, Wander and Quiz.
  - **Counts are exact:** an item counts once across packs (en and es Top share ~22k items), by merging each pack's qid-sorted list without holding it.
  - **Fixed on the Pixel:** examples were countries everywhere (they carry History or Society second) → door examples use main subject only, and subject pages list main-subject articles first; era examples were founded places and tamed animals (Paris, Dog) → leave out main subject Places/Nature; lives sat in their birth era (Hitler in the 1800s) → a life counts at about forty (or its end, if sooner).
  - **Pack doors** use a domain → subject map for now (medicine → Health, food → Food, thought → Religion…); switch to the catalog `subjects` field once KB2 merges. Book collections join the doors then too (`Catalog.bookCollectionsBySubject`).
  - **Neal to look at:** door order is by size, so WikiMed puts Health first (39k); Spanish screens not yet seen on the phone (strings done).
  - Tests: 3,159 green. The Pixel ran with `-PcatalogUrl` (local signed catalog) to fetch the new indexes; reinstalled on the normal catalog URL after.
- **Task 3 built (desk) `30dc646a`, Pixel check pending (phone was locked):**
  - **A subject opened:** Wander (a random well-known main-subject article, into Home's Wander) and Quiz (the six time games dealing only the subject's dated articles; own best runs; shown from 40 dated); era chips with counts; Most linked / Oldest first; rows show pictures and dates; In your books; packs; articles, 60 a page.
  - **Eras:** one pass over the time indexes per subject (`datedIn`), kept as primitive arrays; a life counts at forty, as on the landing.
  - **Books:** subjects from each book's Wikidata work (404 of 622 Classics have one, almost all Books & writing; Religion and History where they fit). Measured and rejected: a book's subject from what it mentions (Places leads for 555 of 622). KB2's catalog book subjects join after its merge.
  - **Explore's rail icon is a compass.**
