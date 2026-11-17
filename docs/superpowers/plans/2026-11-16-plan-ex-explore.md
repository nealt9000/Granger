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
- **Task 3 built `30dc646a`, Pixel-checked 2026-11-16** (History: 10,430 topics, 8,130 dated; eras, both orders, Middle Ages most linked = Columbus, Marco Polo, Ottoman Empire…; Quiz; Wander → Abel Tasman; Books & writing: In your books 171, a cover opens the book):
  - **A subject opened:** Wander (a random well-known main-subject article, into Home's Wander) and Quiz (the six time games dealing only the subject's dated articles; own best runs; shown from 40 dated); era chips with counts; Most linked / Oldest first; rows show pictures and dates; In your books; packs; articles, 60 a page.
  - **Eras:** one pass over the time indexes per subject (`datedIn`), kept as primitive arrays; a life counts at forty, as on the landing.
  - **Books:** subjects from each book's Wikidata work (404 of 622 Classics have one, almost all Books & writing; Religion and History where they fit). Measured and rejected: a book's subject from what it mentions (Places leads for 555 of 622). KB2's catalog book subjects join after its merge.
  - **Explore's rail icon is a compass.**
  - **Fixed on the Pixel:** the quiz dealt countries (El Salvador vs Russia carry History second) → it deals only main-subject articles, and offers itself from 40 of those.
  - **Found, not fixed:**
    - **Deep years wrap:** 18 Spanish Top marks before −2.1 billion (Universe, Big Bang, Hadean) read through `getInt`, so Big Bang shows "c. 902 million years ago". Shipped bug, Timeline too; the fix is `TimeMark.y0/y1` as Long (~70 uses). Its own small task.
    - **Deep time carries History:** the past-event rule gives Big Bang and Cambrian explosion History second, so History's Oldest first opens on geology. Rule fix: no History before ~3000 BC (needs a subjects rebuild and re-sign).
    - **Spanish-only articles** (Ediacárico, Fanerozoico) appear in an English list when English Top lacks them: right by Decision 6, but worth a look in the editorial pass.
    - **Two editions of one work** (Don Quijote, Don Quixote) both in In your books.
    - **Landing examples** show odd mains from subject packs (Technology: Main Page, Wikipedia; Sports: Art; Business: Education, Architecture). For the editorial landing's curation.
- **Deep years fixed** (`bba6ecf6`): `TimeMark.y0/y1`, the histogram, time-index query bounds, Timeline slices and Explore's eras are Long; the games keep an Int view. Pixel: Big Bang "c. 13.8 billion years ago", first in History's Oldest first (Prehistory 98 → 100).
- **Task 5 built, Pixel-checked (2026-11-16):** "As a map" beside the landing's summary opens the library as a map (mockup C), over Explore as the topic map is over a topic page.
  - **Subjects:** your library (the Granger mark) in the middle; the ten biggest subjects round it, each circle's area by its count (six inside, four on the diagonals, the topic map's spacing). The rest are chips below ("Also"). 16, then 12 circles crowded the Pixel; 10 fit.
  - **Time:** the seven eras clockwise round one ring.
  - **A tap steps inside:** its ten best-known main-subject articles (an era's, as its examples are picked) take the ring; a tap opens a topic page; hold or "Open the subject" opens the subject page (an era: the Timeline at its best-known). Back steps out, then closes.
  - **Built on the topic map:** its circle is now `MapCircle`, shared by both maps.
  - **Places** waits for the Atlas (LL6).
  - **For the editorial pass:** some pictures don't stand for their subject or era: Sports shows an art collage (its top article is "Art"), Technology a manuscript, Health a blue sphere (Eye), Since 1946 the Wikipedia logo (the article "Wikipedia"). Landing door pictures also arrive slowly at the top.
- **Subjects rebuild, Neal's OK (2026-11-16)** (`8892cae7`): namesakes (the subject's own article belongs to it alone: Art had been Sports, Health Food, Politics Science, Technology Business, Television Technology, Philosophy Science; Education goes to Society & politics) and History only from 3000 BC. 37 items change; graded sample unchanged at 97.8%; all 44 pass. **To publish (Neal):** upload `granger-ll/gqid-v3/*.gqid` (44) to a new release `sidecars-2026-11-16b`, run check-links, then sign.
- **Editorial landing, built and Pixel-checked** (`a3e7cab3`), Neal 2026-11-16: not a grid; curated; pictures that frame their subject and stand for the whole of it.
  - **The flow:** Today's subject leads (a different one of the eight biggest each day): a large picture, the subject's own opening sentence ("History is the systematic study of the past…"), count and examples. Then two blocks of three (one large tile with its sentence beside two small, mirrored), Through time, then More subjects as picture-led rows.
  - **Pictures:** each subject's own article (Bohr model for Science, Fildes' *The Doctor* for Health, the Duomo for Art & architecture), each era's own (stained glass for the Middle Ages, Verdun for 1900–45); at 800 px; icons fall back to the best example's. Examples leave out the namesake.
  - **Framing:** crops centre on faces found by Android's own detector; else a little above the middle.
- **Main merged in, KB2 included** (`58d78632`): the catalog is main's (signed, with KB2's book subjects) plus the 97 index entries moved to `sidecars-2026-11-16b`, unsigned until Neal signs. DB 16.
- **Catalog signed** (`ec9344cf`, 2026-11-17): the 44 gqid-v3 on release `sidecars-2026-11-16b`; check-links 4,346 OK; full suite green.
- **Book collections join subjects** (`f3804545`, Pixel-checked): the catalog's `subjects` tag book packs, Classics and book sets only, never websites (0 of 938), so website doors keep the domain map. Each subject page lists its installed book collections with how many of their books are on it (Books & writing: Granger Classics 346, Clásicos Granger 162) and opens the Library collection; a subject with only collections still gets a door. Built from the shelf and catalog directly rather than `bookCollectionsBySubject`, so each opens its Library collection id (`c:<key>`).
  - The phone sees it only once a catalog with `subjects` is published (origin/main's has none); checked by putting the signed ll catalog in the debug app's cache.
- **Task 4 built, Pixel-checked:** Sources is the Browse list on KB2's GroupedRows: teal labels by domain (still collapse), rounded blocks, pack rows with tile, size/Ask line and chevron; long press keeps Files in this pack / Remove. A Books row leads ("Your books · 4 collections": every book pack's and Granger collection, each once) and opens the Library. From Explore the title is Your sources, with Back; Get more and the Downloads / Readiness / Import menu stay.
- **Task 6 walkthrough on the Pixel (2026-11-17):** landing, map (subjects, time, stepping inside), History (eras, Oldest first, collections, Quiz round, Wander), Technology → topic page, Sources (Books row, long press, Back) all work.
  - **Fixed** (`49e570f7`): transparent pictures came out black (JPEG thumbnails): flattened onto white; the landing's pictures fill in top first (about 4 s cold, was 10–14 s).
  - **Found, needs a subjects rebuild (Neal's call):** History's from-3000-BC rule only guards History as a second subject; geologic eons (Hádico, Precámbrico, Arcaico) still take History as main through the historical-period class, so History's Oldest first opens on geology (Prehistory · 88). And Wikipedia's Main Page carries Technology (first in Technology's list and the door examples). Both rules, one rebuild, a new release, re-sign.
  - **Small, open:** the landing is blank for about 2 s on a cold start (no placeholder); Spanish-only articles in English lists (Sports: Danza); dark theme not walked.
- **Neal's checkpoint (2026-11-17):** editorial pass still needed: Today's subject highlights one topic behind it (unique daily); each subject page curated as sub-categories; a sub-category opens its topics with sort/filter as a thumbnail mosaic, titles over the foot of each picture, procedural from the images. Rebuild OK once rebuild-implicating things settle.
- **Sub-categories** (`0da157e9`): `tools/subjects/facets.json` curates 91 (en + es names) from each subject's roots and occupations; `facets.py` assigns each article one in its main subject (thing: nearest roots; person: occupations vote, broad ones half; catch-alls and gaps: neighbours' vote, 89% held-out on Top en). Coverage: Top en 97.5%, Top es 97.1%, rules-only packs 99.6%. In the `.gqid` as small codes (+7%: 559 → 599 MB for the 44); names in meta. Same rebuild: History never main before 3000 BC (History's Prehistory 88 → 30, archaeology only); Wikimedia internal items (Main Page) dropped. Built: `granger-ll/subjects-v2.sqlite`, `granger-ll/gqid-v4/` (44, all pass check at the release floor; graded 97.8%). Not published.
- **App** (`ef94538f`, Pixel-checked on the v4 indexes pushed by hand): today's topic card (Airplane in Technology); editorial subject page (hero, Wander/Quiz, sub-categories wide + block + rows, See all, collections, books); mosaic listings for a sub-category or the whole subject (eras, order, paging). 2,638 tests green.
- **Neal's review (2026-11-17), the next session's brief:**
  1. **Broad topics need an editorial topic view.** Today's topic (Airplane) exposed the topic page's weakness on general topics: it's generic. A broad topic should get a procedurally generated, editorialized view: more related pictures, and jump-off points into more specific sub-topics. Reach for what the back end already holds: QID links (`.gqid` links: P800/P50/P921/P86/P175…), Related (the `.gvec` neighbours, LL2 Task 1 spike), the topic map, MentionIndex and `.glink` reader links, time marks, sub-categories. Design it first (mockups, Neal picks), then build. Find "broad" by rule (e.g. many inbound links / many neighbours / a namesake-like class), not by hand.
  2. **Mosaic listings: easier to search across.** Add an A–Z order with a jump-to-letter rail (beside Most linked / Oldest first).
  3. **Sub-categories are lenses, not partitions.** Curated ways to group and explore, not permanent homes: an article can sit in several when it's logical (Neal's example: Animal kingdom & Plant kingdom vs Ecosystems & habitats). So facets become many-to-many and a lens may draw on more than one criterion (classes, occupations, links, neighbours), possibly across subjects. This changes `facets.json`, `facets.py` and the `.gqid` table (a (facet, qid) table already allows many rows per item; meta and counting need no "one per item" assumption). Redesign the lens list with Neal before the rebuild.
  - **Hold:** don't publish gqid-v4. The rebuild waits for item 3 (and anything item 1 needs from the indexes). The History-from-3000-BC and Main Page fixes ride with that rebuild.
  - **State to know:** the Pixel runs debug ef94538f with gqid-v4 pushed by hand into its sidecars folder (originals backed up in this session's scratchpad `gqid-phone-backup/`; re-downloading from the catalog also restores them). Its catalog cache holds the ll signed catalog (generatedAt 2026-11-17T03:07Z). Install to the Pixel only, `adb -s adb-21121FDF6007JN-gtbanj._adb-tls-connect._tcp` (KB2 uses emulator-5580); check the installed build and DB version first (DB 16). Open for the editorial pass: Technology's examples (Main Page, Wikipedia) and the weak era examples (Prehistory: Earth · Moon · Astronomy; Since 1946: Wikipedia).
- **Neal's review, step 1: mockups and lens list (2026-11-17):**
  - **Broad topic view, three mockups** (Airplane, real Top en data) on the canvas https://claude.ai/artifact/HG4yrYyYPFssmRvsYscdvq, third row: **A Chapters** (kinds mosaic, the story of flight as a dark timeline chapter, pilots & pioneers, what makes it fly, who builds them / its footprint, close kin), **B Pictures first** (collage, Go deeper grid of 8 sub-topics with counts, In pictures strip with the article's captions, Through time bars, also worth a look), **C Hub** (the topic-map ring of ways in, one open below as a filterable mosaic).
  - **Where each part comes from, by rule:** Kinds = Top items whose classes reach the topic (Airplane 72), grouped by the shared class that covers most (fighter aircraft 57, airliner 38, bomber 13); chapters = the article's own h2 sections and the Top articles each links (History 23, Propulsion 9, Design 4, Environmental impact 8); pictures and captions = the article's figures; people = vector neighbours with matching occupations (13: Sikorsky, Coleman, Earhart, Lindbergh, Yeager…); close kin = Related.
  - **"Broad" rule tried:** not a person, place or dated event, and 10+ Top items under its class or 300+ articles linking in: ~2,000 of 50,000. Airplane has only 84 in-links (many link "Aircraft"), so the class test carries it.
  - **New phone data it needs:** per article, section → linked articles and figure captions (a small sidecar or read from the ZIM on open); the class tree for "under" (or precomputed kinds per broad topic, the smaller option).
  - **Lens list for Neal:** https://claude.ai/artifact/B25qzGDsjXRLRGeotTeJsy: 108 lenses over 17 subjects (codes HIS1…), many-to-many and cross-subject, each tried on Top en (label-matched prototype; counts, examples, overlaps, and the prototype's noise named per subject). Desk files: X10 `granger-ll/ex2/` (`lensproto.py`, `lenses.json`, link graph `links.pkl`, class index `under.pkl`).
  - **Waiting on Neal:** which mockup (or mix) for broad topics; keep/drop/rename/merge per lens code. A–Z rail (item 2) needs no data change and is next on the app side. gqid-v4 still held.
- **Neal's picks (2026-11-17):** broad topics = **A Chapters** as the page, with **C's ring** opening from an "As a map" button (B's Go deeper grid stays an option for A's Kinds block). Lenses: the 108 on the list **all kept as listed** (names, codes, cross-subject draws).
- **Build plan (from the picks), one rebuild carries it all (gqid-v5; v4 never published):**
  - **L1 Lenses, desk.** `tools/subjects/lenses.json` (108, en + es names), each lens a set of criteria frozen as QIDs, not label regexes: `classes` (reach within the spec depth), `occupations` (people; weak ones half), `seeds` (an old facet's roots/occupations/taxon from `facets.json`, which stays as the seed list), `in` (subjects it draws from, default its own), `after`/`before` (years, time index). `lenses.py`: every article joins every lens whose criteria it meets (many-to-many); a main-subject article in none of its subject's lenses takes the neighbours' vote. Measure per subject: coverage, articles in 2+, and a hand-graded sample of 100 memberships (bar: 95% sensible) before building. Tests.
  - **L2 Lenses into the index.** `.gqid` `facets(facet, qid)` keyed (facet, qid) WITHOUT ROWID (was qid PRIMARY KEY), meta `facets` = the lens list. App: no "one per item" assumption left (QidIndex already asks by key); counts and listings checked on the Pixel.
  - **T1 Broad topics, desk.** Broad by rule (not person/place/dated event; 10+ items under its class or 300+ in-links). For each broad topic in a Wikipedia pack, precompute its parts into the `.gqid` (new table `topic_parts(qid, part, ord, item, note)`): kinds (items under its class, grouped by the shared class that covers most), chapters (its h2 sections and the articles each links), figures with captions in order, people (vector neighbours with matching occupations), dated items it links. Small: ~2,000 topics per pack.
  - **T2 Broad topic page, app.** TopicPage gets the A layout when the index has parts for the topic: hero, kinds mosaic, the story chapter (dark timeline from figures + dated links), people grid, other chapters as cards, close kin (Related), Wander/Quiz; "As a map" opens C's ring of the parts. Topics without parts keep today's page.
  - **Z A–Z rail.** Mosaic listings get A–Z order beside Most linked / Oldest first, with a jump-to-letter rail. No data change.
  - **Then:** rebuild 44 → gqid-v5, check at the release floor, upload, catalog signed, Pixel pass, Neal looks. The History-from-3000-BC and Main Page fixes ride along.
- **Build (2026-11-17):**
  - **L1/L2 lenses** (`74d09adf`): `tools/subjects/lenses.json` (the 108, en + es) with criteria frozen as QIDs, `lenses.py` (every lens an article meets), one facets row per item and lens. Tuning, all by rule: occupations match exactly (walking them up put presidents in Rulers, Rights and Crime); too-broad classes out (biological process, cognitive process, mental state, pastry); time lenses take dated things of main subject History; Olympics by its edition classes; Doctors & medical history without pandemics. No neighbour vote for lenses: graded, it was wrong a third of the time; an article no rule places stays in the subject's whole listing. Top en coverage of main-subject articles: 94–100% for most subjects (history 82%, music 85%, science 94%). Four graded samples of 100 memberships: 91, 94, 91, 94% sensible; the last round's misses were 5 inherited (the old sub-category rule, or a wrong subject upstream: Sundown town in Continents & regions, Polaris expedition in Economics) and 1 lens-side, fixed. Below the 95% bar I set; the remaining gap is the sub-category seeds, not the lenses. Subject page: overlapping lenses take card pictures from different articles.
  - **Z A–Z rail** (`be3bb7c7`): a third order, Collator by the app language, rail of present letters (tap or drag), TalkBack buttons; order row always shown.
  - **T1 broad topics' parts** (`bffcddb0`): `tools/topics/parts.py`; Top en 1,891 broad topics (7.5 MB), Top es 2,196 (6.5 MB), in X10 `granger-ll/ex3/`. Airplane: kinds in Jet aircraft / Transport airplane / Combat airplane, History with its figures and links, people Lindbergh, Yeager, Sikorsky, Richthofen, Armstrong…; Economics: Smith, Ricardo, Keynes, Krugman; Dog (a class with a domestication date) now broad. qidindex attaches parts to every index of the language that holds the topic's article.
  - **T2 broad topic page**: `BroadTopic.kt`: summary line, kinds mosaic with group chips, the story as a dark timeline (the most dated section: dated captions + dated links), people with years, chapters with figure and link chips, Close kin; "As a map" opens C's ring of the parts, a disc scrolls to it.
  - **Rebuild, held (2026-11-17):** `granger-ll/subjects-v3.sqlite` (lenses: 95% of Top en items) and `granger-ll/gqid-v5/` (44, builder 3, lenses + broad topics; 599 → 640 MB, Top en 8.2 → 15.8 MB, Top es 8.3 → 14.8 MB). All 44 pass check.py at the default floor, same as v4. Not uploaded, catalog not touched. Next: Pixel pass on a debug build of the branch tip with gqid-v5 pushed by hand (the Pixel dropped off wireless adb mid-session), then Neal looks; then upload + catalog attach for Neal to sign.
