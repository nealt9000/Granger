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
  - **Pixel pass (2026-11-17, debug on the Pixel 6, DB 16, gqid-v5 for Top en/es + Medicine pushed by hand; v4 backups in scratchpad `gqid-phone-v4-backup/`):** Airplane's broad page renders (summary, kinds mosaic, History timeline with captions and pictures, 12 pilots with years, chapters with figure + chips); the ring opens and a stop scrolls to its part; lens cards with distinct pictures; a lens listing in A–Z with the rail, M jumps. Fixed on the way: the ring (7 stops, kinds/story/people/close kin always, chapters with pictures first; wider, smaller labels; an initial when a disc has no picture), the story's gap (24 links a section, so Airplane's runs 1868 → 1903 → WWII → Battle of Britain → 2000 Concorde crash → 2024), and the order row (scrolls; A–Z had wrapped). gqid-v5 re-attached (646 MB) and re-checked: all 44 pass. Seen, inherited, not fixed: Helicopter leads Airplane's kinds (Wikidata files it under airplane); Spanish weekdays (Domingo, Lunes) among Matter & energy's examples and Spanish-only articles (Miedo) in English listings; Life & the cell's card picture from "Science".
  - **Published for signing (2026-11-17):** the 44 gqid-v5 on release `sidecars-2026-11-17` (sizes match the descriptors); catalog's 97 index entries point there (`a6c8478f`), nothing else changed; check-links 4,346 OK. Unsigned: Neal signs (`catalog_gen.py sign catalog/catalog.json`), then PublishedCatalogTest passes.

## Next session: UX design round 2 (Neal, 2026-11-17)

Neal's verdict after the build: Explore, its subject view and the map view still present as early proofs of concept at best, with no crafted design or thought yet towards the most enjoyable learning and exploration experience. The Spanish walk and the light-theme run are skipped until the design passes his bar. The brief for a fresh session:

> Plan EX: Explore UX design round 2. Design only: no app code, no data rebuilds.
>
> Branch: main (Granger repo). Read first:
> - docs/superpowers/plans/2026-11-16-plan-ex-explore.md, all of it, especially Neal's calls, Neal's checkpoint (2026-11-17), Neal's review (2026-11-17), and the Build and Pixel pass bullets at the end.
> - Memory: linked-library-2026-11-16-ex.md, linked-library-2026-11-11.md, brand-vision-2026-10-23.md, t1-field-and-night-2026-10-26.md, feedback-marks-trace-dont-draw.md.
> - The design canvas https://claude.ai/artifact/HG4yrYyYPFssmRvsYscdvq (rows 1–3: topic page layouts, Explore A/B/C, broad topic A/B/C).
> - The code as it stands: app/src/main/kotlin/app/granger/explore/ (ExploreScreen, SubjectPage, Mosaic, the library map) and app/src/main/kotlin/app/granger/topic/ (TopicPage, BroadTopic, TopicMap).
>
> Where we are: Explore works end to end on the Pixel. It has a landing, subject pages with 108 many-to-many lenses, mosaic listings with A–Z, the library-as-a-map ring view, and broad topic pages. Neal's verdict (2026-11-17): Explore, its subject view and the map view still present as early proofs of concept at best. There's no crafted design and no thought yet towards the most enjoyable learning and exploration experience possible. This round fixes that before any more polish or data work.
>
> What it has to be: Granger is an offline library, "Your door to knowledge. Always open." Explore should feel like wandering a beautiful, curious, well-edited encyclopedia: Encarta-feel, learning leads, delight and discovery. It should not feel like a file browser or a dashboard. Everything stays procedural from the data (no hand-picked articles; Neal's rule), but the design can and should be editorial.
>
> How to work:
> 1. Before designing anything, interview Neal with AskUserQuestion, a few questions at a time, until the target is clear. Cover at least: who he pictures exploring and in what moments (a curious adult, a family, a kid, a learner with 10 minutes); what "enjoyable learning" should feel like here, and the products, books, museums or apps he loves for that feel (Encarta, DK books, museum wayfinding, Apple TV, Arc, Readwise, Kinopio…); what exactly feels POC about the subject view and the map view today (show him screenshots from the Pixel if useful); the jobs each screen must do (land, choose, browse, dive, wander, come back); how much motion, play and game he wants; density versus calm; how the map should earn its place against plain lists; what must stay (lenses, A–Z, time, Today's subject, Wander and Quiz) and what can go. Propose options with previews where it helps him choose. Don't ask what the code or plan already answers.
> 2. Write back a short design brief (principles, audiences, the experience in a few sentences, what success looks like) and get Neal's OK.
> 3. Look outward. Study 4–6 strong references for exploratory learning interfaces and note what each does well. Name the patterns worth borrowing, without copying any product's look.
> 4. Design 2–3 distinct directions for the full Explore flow: landing → subject → lens listing → map view → broad topic page. Put them on the same canvas as new rows (load the artifact-design skill; real Pixel data and pictures, as before: Top en, the 108 lenses, the topic parts in X10 granger-ll/ex3). Every direction should state its idea in a line and show how it delights, how it teaches and how it scales to thin subjects. Note where the data can't support something yet.
> 5. Review with Neal, iterate, and converge on one direction. Then write the build plan into the plan doc as a new section, with the data each screen needs flagged against what the .gqid already holds.
>
> Constraints:
> - Field (light) and Night (dark) palettes, Spectral for display, Roboto for UI; phone first (a 390 dp frame); TalkBack and 48 dp targets.
> - The pack pictures are small (about 250–300 px), so design around them (framing, crops, collage, type-led tiles) rather than full-bleed blow-ups.
> - Never name Claude in user-facing copy.
> - Don't install to any phone or touch the catalog this round.
> - Commit only docs and plan notes, on a branch off main.

Still open after the design round (from the build): lens accuracy 91–94% (inherited from the sub-category seeds and upstream subjects: a facets/subjects rules rebuild); Spanish-only articles in English listings; Spanish weekdays in Matter & energy; Helicopter leading Airplane's kinds; Life & the cell's card picture; the 2 s blank landing on a cold start; the Spanish walk and the light theme.

## UX design round 2 (2026-11-17)

Branch `ex-design-round-2` (docs only). Screens captured from the Pixel as it stood (landing, History, Wars & battles, the map) before the interview.

### Interview: Neal's answers
- **Who explores:** a curious adult with ten idle minutes; a kid or teen alone; a learner with a subject. (Not parent-and-child on a shared screen.)
- **The feel:** all four together: Encarta / DK Eyewitness (a rich spread per topic), a good museum (rooms, wayfinding, one lit object and its label), a magazine or atlas (edited, authored, different each day), and the wiki rabbit hole (momentum, tempting doors).
- **Why it reads as a POC:** it lists and doesn't teach; the pictures feel arbitrary; the map is a diagram. In Neal's words: it doesn't feel curated for exploration. All the information and pieces are here, but the design, the UX and the ability to feel that at every depth aren't.
- **Pictures:** no curved edges. Pictures touch and connect in mosaics. Explore pages keep that feel, and the first couple of layers in are the most thoughtfully constructed.
- **Isomorphisms:** parallels (the same shape elsewhere: a revolution beside the other revolutions) and one topic through every dataset (article, books, time, place, other language, kin). More broadly, insightful ways to cross-analyse the data for learning.
- **Viewpoints** (how cultures and politics shape the same event): later, as its own plan. This round leaves a marked place for it.
- **Control:** where am I and how did I get here: a visible trail to step back along.
- **Play:** a game layer on top of a calm Explore. It rewards discovery (rooms visited).
- **Privacy:** the record holds rooms only (subjects, lenses, eras), never articles. On the phone only, with a switch to turn it off and one to clear it.
- **Density:** a spread: one idea per screenful (a lead image group, one sentence that teaches, a few ways on).
- **The map's job:** show connections, which a list can't. Lines mean something, and it opens on a topic or a lens, not on the whole library.
- **Cuts:** none. Lenses, A–Z, time, Today's subject, Wander, Quiz and Your sources all stay; the round is about craft.

### Design brief (for Neal's OK)
**The experience.** Opening Explore is like opening a well-edited illustrated encyclopedia at today's page. Each screenful is a composed spread that teaches one thing before you tap. Going deeper feels like walking from the entrance hall into a wing, then a room, then up to one object. You always see the way you came, and each stop shows what it resembles elsewhere in the library.

**Audiences.** The curious adult with ten minutes sets the tone and the reading level. A teen alone has to be able to steer by pictures. A learner with a subject has to be able to come back and see how much of it they've walked.

**Principles.**
1. **Teach before the tap.** Every spread carries a fact, a caption, a date or a pattern drawn from the data (lead sentences, figure captions, time marks, counts turned into sentences). A count alone doesn't teach.
2. **Spreads, not lists.** One idea per screenful. Lists and A–Z are still there, one step behind the spread.
3. **Connected mosaics.** Square-edged pictures that touch. A picture is chosen by rule to stand for its subject and is framed at the size its pixels can bear; where no picture is good enough, the tile is type.
4. **Each depth has its own character.** The landing is a cover, a subject is a wing, a lens is a room, a topic is an object on its spread. They share one grammar, so they read as one book, but no two levels are the same card.
5. **You are here.** A trail (Explore › History › Wars & battles › Napoleon) is always in sight, and you can step back along it.
6. **Parallels and cross-views.** Each topic and room shows its counterparts and its other datasets: books, time, the other language, kin by meaning.
7. **The map shows connections only.** It opens on a topic or a lens. Every line says what it means.
8. **A quiet game layer.** Rooms visited, shown as the library filling in. Rooms only, never articles; on the phone; one switch off, one to clear.
9. **Procedural content, editorial design.** No hand-picked articles. The editing is in the templates and the rules.
10. **Nothing is cut.** Lenses, A–Z, time, Today's subject, Wander, Quiz and Your sources all stay.

**Success looks like this.**
- A screenshot taken at any depth looks designed and teaches one thing.
- In ten minutes a new person goes three levels down, moves sideways once, and can say where they are.
- A thin subject (Games, Languages) still makes a good spread.
- The map shows something its list can't.
- Nothing reads as a file browser or a dashboard.
- It holds in Field and Night, with TalkBack, at 48 dp targets, in the width the rail leaves (about 316 dp of a 390 dp phone).

**Left for later, with a marked place:** Viewpoints (its own plan); the Atlas (LL6).

### References and the patterns worth borrowing
Written from what I know of these, not from a fresh look at each this session.
- **Encarta (1995–99).** An article was a stage: text in the middle, media in a margin, and always "related" one click off. Its Timeline and Collages let one set be seen in a different shape. *Borrow:* the topic as a spread with a margin of media; one set, several views.
- **DK Eyewitness.** One idea per spread. A hero object on a plain ground, smaller objects round it, and every picture has a caption that teaches. Small pictures look deliberate because they're arranged, not enlarged. *Borrow:* the captioned plate; arrangement over enlargement; the plain ground.
- **Museum wayfinding and labels** (the V&A, the Rijksmuseum's Rijksstudio). A hall, wings, rooms; a sign at each doorway says where you are and what's next door. A label is a title, a date and one sentence. *Borrow:* the doorway sign (the trail plus the neighbours); the label as the unit of teaching.
- **A magazine or atlas** (National Geographic, a Times atlas). A cover that changes, a contents page, a standfirst under each headline, pacing between plates and type. *Borrow:* the daily cover; the standfirst; type and a rule line as layout, with no boxes.
- **Google Arts & Culture.** One collection re-sorted by time, by colour, by place; "stories" stitched from objects. *Borrow:* re-sorting the same set in place; a short procedural story (five dated plates) as a way into a lens.
- **The Wikipedia rabbit hole** (the Wiki Game, the app's Explore feed). Momentum comes from a few tempting doors on every page and from seeing your path. *Borrow:* three doors at the foot of every spread; the trail as a thing you can read back.
- **Are.na and Kinopio.** A connection is worth drawing only when it's named. *Borrow:* labelled lines on the map (wrote, fought in, same decade, also in).

**Patterns to carry into every direction:** the captioned plate; the doorway sign; the standfirst; three doors on; one set, several views; named connections; the parallels strip ("the same shape elsewhere"); the library filling in.

### Three directions on the canvas (2026-11-17)
Canvas https://claude.ai/artifact/HG4yrYyYPFssmRvsYscdvq, rows 4–6. Each row is the whole flow on the same real data (Top en, the lenses, Airplane's parts): landing → Technology → Cars, planes & ships → connections → Airplane → a thin room (Words & grammar, 17 topics) → the landing in the other theme. The lenses are called **rooms** in all three. Every sentence that teaches is a template filled from the data. Each screen was rendered and looked at before it went up.

- **A · Plates** (Field leads): every screen is a spread from an illustrated encyclopedia. Numbered plates at the size their pixels can bear, each with a caption; a sentence per room; "Where these also hang"; a connections plate with each line labelled by where the link was found. Thin rooms set a pictureless topic as a word tile. Weakest point: closest to what's built.
- **B · Galleries** (Night leads): the library is a museum. One lit object and its label per wall; doors left and right on every screen; a floor plan (17 wings, a square per room, lit when seen) that is the trail and the game layer in one; Threads draws the rooms a topic reaches into. Weakest point: fewer pictures per screen.
- **C · Almanac** (Field leads): a daily edition. The date as the cover, On this day, headlines that state a finding, each room as seven squares from the 1400s to the 2000s, a room read era by era, Airplane side by side with Ship and Car, view tabs that keep the set. Weakest point: fewest pictures.

**What the data can't support yet (all three):**
- A one-line description per room. It needs writing once for the 108, like their names.
- Room sentences (oldest, busiest century) and room overlaps are computable from the index and the time index, but should be precomputed.
- Dates are thin: 228 of this room's 882 topics carry one, and the time index dates a life at birth.
- Pictures by rule are hit and miss: Wikipedia's is a portrait, Electric car (1835) shows a modern Tesla, Amelia Earhart shows a house. A better picture rule is needed whichever direction wins.
- Parallels need broad-topic parts on both sides: Ship and Car have them; Train, Bicycle and Helicopter don't.
- Rooms seen is new user data (a small table on the phone).
- Connection labels can only say what the parts hold: chapter name, kind, people near it, close in meaning.

Desk files: X10 `granger-ll/ex4/` (`gather2.py` pulls the data; `ex.py`, `dirA.py`, `dirB.py`, `dirC.py`, `build.py` generate the artboards; `view.py` renders them with headless Chrome).

**Waiting on Neal:** which direction leads, and which parts of the others to fold in.

### Neal's picks (2026-11-17) and the converged direction, D
- **A blend leads:** A's plates and captions as the page grammar.
- **Kept from the others:** B's doors left and right (a bar at the foot of every screen); C's finding headlines and side by side.
- **The map:** A's, plates round the topic with every line labelled.
- **Not picked, so left out:** B's floor plan, C's date cover and On this day, the time squares, Threads by room and through time. The rooms-seen layer stays as A's thin bar.
- **D is drawn** on the canvas, row 7 (same flow, same data), generated from A's source by `ex4/dirD.py`. Neal hasn't looked at D yet; the build plan below is a draft until he has.

### Build plan for D (draft)
Data is flagged against what the gqid-v5 index holds: **have** (on the phone today), **compute** (derivable on the phone from what it holds), **new** (needs a desk build or new user data).

**P0. Two desk jobs first (Neal reviews both before any app work)**
- **The picture rule.** Today's rule takes an article's first usable image. It gives Wikipedia a portrait, Electric car a modern Tesla and Amelia Earhart a house. Study the candidates per article (position, size, caption, whether it shows a person) on a sample of 200, propose a rule, and grade it with Neal as the subjects were graded. **New:** a chosen picture per article in the index, or a rule the phone can apply.
- **Room descriptions.** One line for each of the 108 rooms, en and es, in `lenses.json` beside the names. **New**, written once.

**G0. Shared pieces (app, theme shots in Field and Night)**
- Plate mosaic: square edges, 2 dp seams, the title over the foot, a word tile when there's no picture. Row patterns by rule from the count and the pictures' shapes.
- Numbered captions, the trail (collapses past three steps), the doors bar, the three-ways-on list, the rooms-seen bar.
- TalkBack: each plate is one button with its title; plate numbers and seams are hidden; the doors bar reads "the room before, The internet".
- Data: none.

**D3. A room** (was the lens listing)
- Description line: **new** (P0).
- Finding sentence (oldest thing, share in the busiest century): **compute** from `facets` and the time index; precompute into the index meta if it's slow on big packs.
- The room in six plates (best-known dated topic per era): **compute**; the eras pass exists (`datedIn`).
- Views Plates, Through time, A–Z: **have** (mosaic, era chips, A–Z rail). Narrow keeps the era and order filters.
- Where these also hang (shared topics with other rooms, top four): **compute**, one join on `facets`. Its heading states the largest share.
- Doors bar: the rooms before and after in the subject: **have**.

**D2. A subject**
- Three opening plates and their captions: the pictures are **have**; a caption for an article's lead picture is **new** (only broad topics carry captions). Fall back to the article's title.
- Room tiles in pairs with a sentence each: tiles **have**; sentences as in D3.
- Finding heading from the rooms' century counts: **compute**.
- In your books, Wander, Quiz: **have**. Rooms seen: **new** user data (below).
- Doors bar: the subjects before and after: **have**.

**D1. The landing**
- Today's spread: the topic's own picture and two of its figures with captions, and its counts: **have** (`topic_parts`).
- Subject mosaic with a finding heading: **have** (counts, namesake pictures, P0's rule).
- Through time rows with bars: **have**.
- Wander, Quiz, Your sources as three ways on: **have**. Rooms seen bar: **new**.

**D5. A broad topic**
- Plates and captions, kinds in families, the History story, people, chapters: **have** (`topic_parts`).
- The diagram plate: **compute**; it needs a rule for which figure (a first try: the first figure outside the story chapter whose picture is a drawing).
- Side by side: the two best-known other broad topics in the same room; rows from `topics` and `topic_parts` (kinds, families, people, chapters, links in): **have**. Left out when the room has no other broad topic.
- Doors bar: the topics before and after in the room, best known first: **have**.
- Viewpoints: not built; no placeholder ships.

**D4. Connections: the quilt** (Neal 2026-11-17: the right direction, clears the bar, needs design polish)
- The middle plate with its groups touching it; a paper tab starts each group. For a broad topic the groups are kinds, people, chapters and close kin: **have** (`topic_parts`, the vectors). For other topics: leads to, close kin, born within a year, rooms, in your books: **have** (time index, `facets`, mention index).
- Touching a plate re-forms the quilt round it; a thread strip keeps the walk. The shared-plate square: **compute** from the last quilt.
- Leads to, and the corner number: a lookup on `topic_parts.item`. It needs an index on that column: **new**, small, in the next rebuild.
- Holding a plate shows its label: where the link was found, its rooms, and the topics that lead on.
- Draw order: parts, rooms and time first; close kin fills in when the vectors answer (0.7 to 1 s in the spike).
- Still to design: polish (Neal), a topic with very few connections, and the TalkBack form (a list of groups).

**R. Rooms seen**
- **New** user data: a small table (kind, id, first seen). Rooms, subjects and eras only, never articles. A switch to turn it off and one to clear it in Settings. The next free migration number after `article_marks`.

**Order:** P0 → G0 → D3 → D2 → D1 → D5 → D4 → R → walkthrough on the Pixel in Field and Night, with TalkBack, then Spanish.

**Open for Neal:**
- Whether lenses are called rooms in the app (the mockups do).
- The picture rule and the room descriptions (P0) are his to review.
- The rebuild: sentences and overlaps can be computed on the phone, so no new index is needed unless they're slow. P0's picture choice would need one.

### Neal on D (2026-11-17)
- **D is the strongest so far** and is OK'd as the point to iterate from. It's barely at the bar for testers.
- **The map is now the weakest part.** It's the next thing to redesign, before D4 is built.
- **Lenses are called rooms** in the app, for now.
- The build plan above stands as the working plan. D4 (Connections) was held until the map was redrawn; it's now the quilt.

### The map, redrawn as a quilt (2026-11-17)
Neal on D's map: it says little a list couldn't, a few taps in it dead-ends without leading on to interesting connections, and it's visually uninteresting, with no designer's hand. A redraw is on the canvas, row 8 (four boards: Airplane, one touch later on Charles Lindbergh, a held plate, Night).

- **No spokes.** Plates that touch the middle plate are connected to it. Each group starts with a paper tab (Kinds · 60, Pilots · 12, History · 24).
- **No dead ends.** Touching a plate re-forms the quilt round it. A corner number says how many other topics lead to that plate.
- **What a list can't show.** After a touch, a small square marks the plates that were on the last quilt too: three of the people closest to Lindbergh also stand near Airplane.
- **A thread** at the top keeps the walk; any step in it goes back.
- **Hold a plate** for its label: where the link was found, its rooms, and the topics that lead on from it.
- **Every group in full** is one tap away, as a list.

**Data, against the index:**
- Groups for a broad topic (kinds, people, chapters): **have** (`topic_parts`).
- Leads to (the broad topics whose parts hold this plate) and the corner number: **compute**, a lookup on `topic_parts.item`; it needs an index on that column, so a small **new** index in the next rebuild, or a slow scan.
- Close kin: **have** (the vectors; 0.7 to 1 s per topic in the LL2 spike, so the quilt should draw its other groups first).
- Born within a year: **have** (time index). Rooms: **have**. In your books: **have** (mention index).
- The shared-plate mark: **compute** on the phone from the last quilt.

**Not yet known:** how a topic with very few connections looks, and how the quilt reads with TalkBack (a list of groups is the likely form).

**Neal (2026-11-17):** the quilt is the right direction and much better; it clears the bar for the map and needs some design polish. It's swapped into D on the canvas (row 7) and into D4 above.

**Quilt text pass (2026-11-17).** Neal: the polish it needs is the text around it, which should be more useful and easier on the eyes. Redone on the canvas (rows 7 and 8):
- One short finding replaces the title and the instructions ("137 connections in eight groups. Kinds is the largest, with 60.").
- The key is drawn with the marks themselves: the corner number, the shared square, "Hold a plate for its label".
- Every group sits in a two-column index, largest first; empty groups are left out.
- The held plate's label carries where the link was found, its date and rooms, and its opening sentence.

### P0: the picture rule study (started 2026-11-17)
**A correction first.** The round-2 notes say today's rule gives Wikipedia a portrait, Electric car a Tesla and Amelia Earhart a house. Those came from the mockup script's own extractor (it skipped `.svg` names and failed on doubly-encoded paths), not from the app. The app's rule (`ArticleThumbnails.candidates`) takes the first `img` that isn't svg/ico, isn't an icon or formula, and is 64 px or more a side. It gives Earhart her infobox portrait and Wikipedia its logo. The study below measures the app's rule.

**What the app's rule picks** (random 200 Top en articles, seed 1117, used for development):
- 197 of 200 get a picture. 188 come from the lead; 124 are the infobox picture.
- 154 picks are photos. When the pick is a photo it nearly always stands for the topic.
- **43 picks (21%) are drawings:** flags, coats of arms and seals (Mexico, Honduras, Venezuela, South Carolina, Columbia University), locator and diaspora maps, logos (NeXT, Star Wars, HTML), chemical structures, charts, and series-sidebar symbols.
- A few photo-type picks are really maps or diagrams (a battle map, a climate map).
- Pixel statistics (share of white, number of colours) don't separate these: old black-and-white portraits look like diagrams. The rule has to work from the page's markup.

**Proposed rule v2** (markup only: order, container, file type, file name, drawn size; no extra decoding):
1. Take the first usable picture, as today, if it's a photo and not an emblem. (An emblem is a file named as a flag, coat of arms, seal, emblem, map, locator, projection, chart or signature, or a series sidebar's drawing or small picture.)
2. If the first is an infobox drawing that isn't an emblem (a logo, a structure, a character), keep it: it's the topic's identity.
3. If the first is a chart or diagram in the text, take a JPEG photo from the same or the next section, if there is one.
4. If the first is an emblem, take the first JPEG photo among the first eight pictures (120 × 100 or more), wide ones before tall ones, because a tall photo there is usually one person's portrait. Failing that, a non-emblem drawing; failing that, the emblem.

On the development 200 it changes 22 picks (Mexico's flag → the Pyramid of the Sun, Ibiza's flag → the island from orbit, Colombians' map → people, Poultry farming's chart → hens). Known misses there: South Carolina falls to a locator map, Zurvanism's symbol goes to a statue.

**Held-out test for Neal** (seed 4417, 400 articles, not looked at): the rule changes 28 picks (places 10, society 7, history 3). Grading page: https://claude.ai/artifact/SdvFpPuoQLthuqniGYEe8g (db collection `grades`; doc `p<n>` verdict `new`|`old`|`both`|`neither`, doc `s<n>` verdict `yes`|`no`|`unsure`). Part one is the 28 pairs in a blind left/right order; part two is 100 pictures where both rules agree.
- **Proposed bar:** on the pairs, the new pick is better or as good in 85%+, and worse in under 10%. On the singles, 90%+ yes of those judged.

Desk files: X10 `granger-ll/p0/` (`extract.py`, `rule.py`, `sheet.py`, the samples and the grading page).

**Not covered yet:** other packs and Spanish (file names differ: `Bandera_de`, `Escudo_de` are in the pattern, others may not be); a subject's or room's own picture; whether the phone can read `data-file-type` from every pack's HTML.

### P0 round 1 result: rule v2 fails the pairs bar (2026-11-17)

Neal graded all 128 items on the held-out set (seed 4417).

- **Singles (both rules agree): pass.** 86 yes, 3 no, 11 not sure; 97% yes of the 89 judged (bar 90%). The three no's:
  Waldorf Astoria New York (logo, with the building next in the infobox), Medical history, Compiler (diagram).
- **Pairs (the 28 picks v2 changes): fail.** New better 14, both fine 2, neither 5, old better 7. Better-or-equal is 57%
  (75% if "neither" counts as equal) against a bar of 85%; worse is 25% against a bar of under 10%.

What the seven losses share:

| Loss | Cause |
|---|---|
| Flag of Washington, Coat of arms of Hungary | the topic is the emblem, so the emblem is the right picture |
| Uncharted 3 | a file-name bug: "chart" matched inside "Uncharted", so the cover art was treated as a chart |
| Monothelitism | a small sidebar photo was treated as a series symbol; that part of the rule won 0 of 3 pairs |
| IGN | an infobox logo under 80 px tall gave way to an office photo |
| Scramble for Africa | the maps are the topic, though the title doesn't say "map" |
| Confederate government of Kentucky | the flag gave way to one man's portrait |

Also found: the pack stores some drawings far smaller than the page draws them (Washington's flag is 85×53 px in the
file, drawn at 255×152). The app's decode check already skips these; the rule has to test the stored size, not the
drawn size.

**Rule v3** (`rule3.py`), tuned with seed 4417 now counted as development data:

1. A photo that comes first stands. A sidebar photo counts as a photo.
2. If the title names an emblem kind (flag, coat of arms, seal, emblem, map, logo, insignia, symbol), the first picture stands.
3. An infobox logo, structure or character of 80 px or more stands.
4. A chart or diagram gives way to a JPEG photo in the same or next section.
5. A flag, seal, map or series symbol gives way to the first roomy JPEG photo among the first eight, wide before tall.
6. File-name words match as whole words only. A candidate must be stored at 64 px or more a side.

Tried and dropped: letting an infobox photo beat the infobox logo (it gave Sega an office tower and In-N-Out a
university building), and lowering the logo size to 64 px (it lost Virgin Atlantic's aircraft).

On seed 4417, v3 changes 19 picks instead of 28. Of those, Neal's round-1 grades read new 12, both 1, neither 3, old 3.
That is tuning data, so it is not a score. Scramble for Africa, Kentucky and IGN stay wrong; nothing in the markup
separates them from the cases the rule gets right.

**Round 2 held-out:** a fresh 400 (seed 7723), 34 picks changed, none looked at before publishing. The same page,
https://claude.ai/artifact/SdvFpPuoQLthuqniGYEe8g, now shows those 34 blind pairs and saves to `grades2`. Same bar.
If v3 fails it too, the fallback is to ship only the parts with a clear record (rules 1, 2 and 6 plus the app's rule).

### P0 round 2 result: rule v3 fails too; what survives is one narrow swap (2026-11-17)

Neal graded the 34 fresh pairs (seed 7723): **new better 17, old better 15, neither 2.** Worse is 44% against a bar of
under 10%. Swapping a drawing for a later photo is a coin flip overall, so v3 is dropped.

By what the first picture was:

| First picture | New better | Old better | Neither |
|---|---|---|---|
| A series sidebar's drawn symbol (Dharma wheel, handcuffs icon, MTV logo on a series box) | 7 | 0 | 1 |
| An infobox flag, seal, coat of arms or map | 5 | 9 | 1 |
| A chart, diagram or map in the text | 3 | 4 | 0 |
| An infobox logo | 2 | 2 | 0 |

The lesson: an infobox emblem or a map is the article's own chosen identity (Paraguay, Tanzania, Eurasia, the PLO),
and the next photo down is often incidental (a battle painting, a stone tool, a campaign poster). A series sidebar's
symbol belongs to the series, not the article, so there the first photo is better.

**Rule v4** (`rule4.py`): today's rule, plus one swap. When the first usable picture is a series sidebar's drawing,
take the first roomy JPEG photo among the first eight pictures, wide before tall; otherwise keep the first picture.

Record across both graded rounds: 11 pairs, new better 9, neither 2, old better 0. It changes about 1.5% of articles
(3 of 400, 8 of 400, 5 of 200). Caveat: the slice was chosen after seeing round 2, so only round 1's three pairs
(2 new, 1 neither) count as an independent check. A clean confirmation would be a third fresh sample of about 800
articles, giving roughly 12 pairs.

What this means for the design: the picture problem is smaller than the mockups suggested. Today's rule gives a
picture Neal accepts 97% of the time where the rules agree, and the drawings it picks are mostly the right identity.
Plates must therefore be designed to carry flags, seals, logos and maps well (contain, not crop, on a paper ground),
since no markup rule will reliably replace them.

### P0 closed on the picture rule; room descriptions drafted (2026-11-17)

- **Neal accepted rule v4**: today's first-picture rule, plus the series-sidebar swap. No third grading round. The
  phone can apply it from the article's HTML, so no index change is needed. The build plan's P0 picture line is
  superseded by this, and its examples (Wikipedia, Electric car, Earhart) were wrong, as noted above.
- **Design consequence for G0:** plates must show flags, seals, logos and maps whole on a paper ground, not cropped.
- **Room descriptions** are drafted for review in `2026-11-17-ex-room-descriptions.md` (108 rooms, en + es). They
  move into `lenses.json` after Neal's OK. The draft also lists eleven rooms whose contents and names pull apart.
