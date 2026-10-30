# Plan WF: Wikipedia as one family — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** the Kiwix list offers Wikipedia as **one row**. You pick how much (100 essentials, Best 50,000, Top 1 million, or Everything) and with or without pictures. Subject packs show as add-ons that say how much they add to what you have. A bigger Wikipedia counts as having the smaller ones.

Neal, KB walkthrough (2026-10-30): "wikipedia's 1m Top Articles and 100 essential articles probably all belong as options within the wikipedia set, as I expect all of the smaller ones are already inside the bigger ones."

**Settled with Neal, 2026-10-30:**
- **Subjects are supplements.** Neal: "most phones will be running between 15–100 GB offline data, rarely if ever the full wikipedia, so the subjects would supplement the top 50k in most real-life scenarios. I don't know how much of that is duplicated in the top 50k and top 1 mil packs."
  - So we **measure** the overlap first (Task 1).
  - Each subject then says what it adds to the Wikipedia you have or are choosing (Decision 4).
- **Bigger covers smaller.** Readiness and onboarding count a smaller slice as had, and its row says "Inside your Top 1 million". Getting a bigger one offers to remove the smaller once it's in, keeping your places and bookmarks (Decision 5).

**Out of this plan:**
- Wikipedia in other languages.
- Simple English Wikipedia and Wiktionary: different text, so they stay their own rows.
- Wikibooks, Wikisource and the like (single packs).
- MDWiki (a different project from WikiMed).

**Tech Stack:** unchanged (Kotlin 2.4.21, Compose Material 3, Room, Robolectric 4.17, JUnit 4 + Truth; Python for catalog-gen).
- **Catalog:** new optional fields only, `FORMAT_VERSION` 1, re-signed after `check-links` with a newer `generatedAt`.
- **Schema:** no library migration expected.
- **Build env:** `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
- **Worktree:** `.claude/worktrees/wf` (branch `wf-wikipedia-family`, off main `c70fdd94`), with submodules and `local.properties` set up.
- **Phone:** shared with other workstreams (F1 is running in another session). Check with Neal before installing. One app on the phone.

**Roadmap:** a new WF row in `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, after KB.

## The Wikipedia family in the catalog (main `c70fdd94`)

| Pack (`zimName`) | Title today | Sizes (GB): maxi / nopic / mini | Domain | Featured |
|---|---|---|---|---|
| `wikipedia_en_100` | Wikipedia: 100 essential articles | 0.3 (one flavour) | reference | yes |
| `wikipedia_en_top` | Wikipedia: best articles **(no pictures)** | 7.0 / 2.7 / 0.3 | reference | yes |
| `wikipedia_en_top1m` | Wikipedia's 1m Top Articles | 49.4 / 17.1 / 3.0 | reference | |
| `wikipedia_en_all` | Wikipedia | 127.4 / 52.7 / 14.4 | reference | |
| `wikivoyage_en_europe` | Wikivoyage - Europe | 0.4 / 0.1 | maps | |
| `wikivoyage_en_all` | Wikivoyage travel guide **(no pictures)** | 1.1 / 0.3 | maps | yes |

- **Subject packs (25), mostly maxi/nopic:**
  - **science:** astronomy, chemistry, climate-change, mathematics, molcell, physics;
  - **medicine:** WikiMed (`wikipedia_en_medicine`, featured);
  - **technology:** computer;
  - **maps:** geography;
  - **thought:** history;
  - **reference:** sociology;
  - **literature:** indian-cinema, movies, nollywood;
  - **making:** knots;
  - **domain blank:** baseball, basketball, comics, cricket, football, golf, ice-hockey, tennis;
  - **sample:** ray-charles, a 2 MB test pack.
- **Readiness tiers and presets** name specific ids (for example Reference's essential is `wikipedia_en_top_maxi`, its comprehensive is `top1m`; Medicine's essential is WikiMed). Onboarding builds its download list from them.
- **A bug to fix along the way:** KB's grouped row takes its title from whichever flavour comes first, so two featured rows read "WikiMed (no pictures)" and "Wikipedia: best articles (no pictures)" while offering all flavours.

## Decisions

1. **Measure the overlap first.**
   - `zim_probe.py` gains a `titles` command: it lists a ZIM's article paths over HTTP ranges by walking the path-pointer list and the directory entries, never the article content.
   - Run it on the mini or nopic flavour of each Wikipedia slice and subject pack. Article paths are the same across flavours; prefer the smallest flavour.
   - **For every subject pack, compute:**
     - its article count;
     - the share already in Best 50,000;
     - the share in Top 1 million;
     - the share in Everything.
   - **For the slices, compute** 100 ⊂ Best 50,000 ⊂ Top 1 million ⊂ Everything, and Europe ⊂ the whole Wikivoyage. Confirm the nesting holds, and record any slice that isn't contained.
   - **Budget:**
     - Top 1 million's path list is the largest, roughly tens of MB.
     - Everything's mini is 14.4 GB, so probe only its path-pointer list and directory entries. If that's over about 500 MB, use Kiwix's article counts and treat it as containing everything (it is the full dump).
   - Results go in `tools/catalog-gen/pack_sets.json` and a short report in the plan.

2. **Sets in the catalog.**
   - `PackEntry` gains three optional fields:
     - `set` (e.g. `"wikipedia_en"`, `"wikivoyage_en"`);
     - `setRank` (1 = smallest);
     - `setLabel` ("100 essential articles", "Best 50,000 articles", "Top 1 million articles", "Everything"; "Europe", "The whole world").
   - Subject packs get `subjectOf: "wikipedia_en"` and `adds: {"wikipedia_en_top": 0.71, "wikipedia_en_top1m": 0.18}`: the share of their articles **not** in that slice, from Decision 1.
   - `catalog_gen.py` reads `pack_sets.json`, then runs `check-links`, re-signs and `verify` with a newer `generatedAt`.
   - A group's title comes from the set or the curated base title, never a flavour-suffixed one ("Wikipedia", "WikiMed", "Wikivoyage").

3. **One Wikipedia row.** In the Kiwix list (and Readiness's Reference view):
   - **Title and description:** "Wikipedia", "The free encyclopedia, from 100 essential articles to all of it."
   - **How much:** a radio list of the slices with sizes for the chosen pictures setting ("Best 50,000 articles · 2.7 GB").
   - **Pictures:** With pictures, No pictures, or Mini (intro paragraphs only), as KB's flavour choice. A setting a slice doesn't offer is disabled for it, with its reason.
   - **The default choice** is the installed one, else Best 50,000 with pictures (today's recommendation).
   - **Actions:** Get, Open, or "Switch to …", with KB's confirm dialog naming both sides.
   - Wikivoyage gets the same row with Europe or The whole world.

4. **Subjects as add-ons.**
   - Under the How much choices, the Wikipedia row has **"Add a subject"**. It opens an A–Z list of the subject packs (LV shelf, with the letter strip).
   - Each subject says what it adds to your Wikipedia: "Adds about 4,200 articles to your Best 50,000". It compares against the installed slice, or the chosen slice if none is installed.
   - When Everything is installed, the subject reads "Already inside your Wikipedia", and Get becomes a quiet text button.
   - WikiMed keeps its own Recommended row too (it's Medicine's essential), with the same "adds" line.
   - The 2 MB Ray Charles sample leaves the list (it's a test pack). It stays in the catalog for tests.

5. **Bigger covers smaller** (Neal's choice).
   - **Covering:**
     - A slice of rank n is covered by any installed slice of the same set with a higher rank.
     - A subject is covered only by Everything, or by a slice where its `adds` share is under 5 %.
   - **Readiness:** a tier item that's covered counts as present. "Reference: Wikipedia Top 1 million covers Best 50,000".
   - **Onboarding and presets:**
     - a covered item isn't queued;
     - if the review holds two slices of one set, only the biggest is queued, and the review says so.
   - **Rows:**
     - a covered slice's choice reads "Inside your Top 1 million", with no Get;
     - a subject reads per Decision 4.
   - **Getting a bigger slice:** once it installs, Granger offers to remove the smaller slices of the same set.
     - It's a one-time prompt from the download's completion, on Home's downloads card and in Downloads: "Top 1 million is in. Remove Best 50,000 (2.7 GB)? Your places and bookmarks move across."
     - It reuses KB's `Library.supersede`, extended to accept a different `zimName` within one set. Article paths match across Wikipedia slices; a place whose article isn't in the new pack is kept as a history row.
     - It never removes automatically.

6. **Ask and search don't double up.** When both a slice and a covering slice are installed (before the smaller is removed), Search and Ask skip the smaller one's hits for articles the bigger one also has. Dedupe by article path within one set; the bigger pack wins.

## Global Constraints

- `./gradlew testDebugUnitTest :core:research:test` stays green after every task, and the count only grows. The baseline is 2711 (47 skipped).
- Catalog: only new optional fields, `FORMAT_VERSION` 1, re-signed after `check-links`, with a newer `generatedAt` than main's (2026-10-30T17:06:54Z).
- Nothing is ever removed without the user's yes.
- Commit after every task with the message given, ending with the Co-Authored-By line.
- The phone is shared: ask Neal before installing.

## Review Focus

- Is the nesting real (Decision 1's numbers), and do the subjects' "adds" figures look right on a spot check?
- Does the Kiwix list show one Wikipedia row (and one Wikivoyage row) with How much × Pictures, correctly titled?
- Do subjects say what they add to the installed or chosen slice?
- Does Readiness count Best 50,000 as present when Top 1 million is installed, and does onboarding queue only the biggest slice?
- Does getting a bigger slice offer, not force, removing the smaller, and do places and bookmarks move?
- Are there no duplicate hits in Search and Ask while both are installed?

---

### Task 1: Measure the overlap

- [x] `zim_probe.py titles` (article paths over ranges), with tests against the fixture ZIM.
- [x] Run it over the slices and subject packs (Decision 1). Write `pack_sets.json` (sets, ranks, labels, `subjectOf`, `adds`) and a findings section in this plan:
  - counts;
  - nesting holds or not;
  - each subject's share new to Best 50,000 and to Top 1 million;
  - bytes fetched.
- [x] **Commit.** `Wikipedia family: measure what each slice and subject holds`

#### Task 1 findings (2026-10-30)

Measured with `zim_probe.py titles` on each pack's smallest flavour; numbers in `tools/catalog-gen/pack_sets.json`.

- **What counts as an article.** A namespace C text/html entry that isn't a redirect, *less* section-redirect pages. Older mwoffliner (1.17.5: Top 1 million, the 100, some subject nopics) writes a redirect to a section ("Abyssinian lion" → Lion#Subspecies) as a ~220-byte HTML page with a meta refresh, packed thousands to a cluster at the end. `titles` tells them apart by cluster (64+ blobs averaging under 120 stored bytes) and lists them with the redirects. Without this the 100 read as 1,323 articles and Top 1 million as 1,774,450.
- **A path is "in" a slice** when the slice has it as an article or as a redirect. Paths are URL-decoded, NFC, spaces as underscores.
- **Flavours:** chemistry mini 9,314 articles, nopic 9,313 (the one is an article sharing the boundary cluster with the section pages). Same paths otherwise.

**Slices**

| Slice | Probed | Articles | Inside the next one up |
|---|---|---|---|
| 100 essential articles | `wikipedia_en_100` (2026-08) | 100 | 100 % in Best 50,000 |
| Best 50,000 | `top_mini` (2026-09) | 50,002 | 99.97 % in Top 1 million: 15 missing, renames between snapshots (Top 1 million is 2026-04) |
| Top 1 million | `top1m_mini` (2026-04) | 999,593 | Everything not read |
| Everything | `all_mini` (2026-09) | 7,331,817 (Kiwix's Counter) | — |
| Wikivoyage Europe | `europe_nopic` | 10,376 | 99.99 % in the whole world (1 missing: its own home page) |
| Wikivoyage, the whole world | `all_nopic` | 34,600 | — |

The nesting holds. Everything's directory region is 1.02 GB (plus 145 MB of pointers), over the 500 MB budget, so it was not read; it is taken to contain every slice and subject (the full dump).

**Subjects: share of articles new to each slice** (`adds`)

| Subject | Articles | New to Best 50,000 | New to Top 1 million |
|---|---|---|---|
| astronomy | 28,272 | 97.8 % | 74.0 % |
| baseball | 50,622 | 99.1 % | 82.5 % |
| basketball | 37,018 | 99.4 % | 85.3 % |
| chemistry | 9,314 | 93.7 % | 55.9 % |
| climate-change | 3,701 | 92.3 % | 57.2 % |
| comics | 21,515 | 98.3 % | 69.3 % |
| computer | 43,489 | 95.7 % | 62.0 % |
| cricket | 46,252 | 99.7 % | 92.1 % |
| football | 308,031 | 99.5 % | 85.0 % |
| geography | 91,985 | 99.1 % | 92.5 % |
| golf | 12,140 | 99.6 % | 89.8 % |
| history | 48,733 | 96.8 % | 67.7 % |
| ice-hockey | 41,982 | 99.7 % | 86.7 % |
| indian-cinema | 28,391 | 99.6 % | 83.0 % |
| knots | 599 | 98.8 % | 69.3 % |
| mathematics | 23,326 | 96.0 % | 66.9 % |
| medicine (WikiMed) | 72,901 | 95.5 % | 65.6 % |
| molcell | 28,369 | 97.6 % | 80.8 % |
| movies | 309,311 | 98.9 % | 83.6 % |
| nollywood | 1,916 | 99.7 % | 93.5 % |
| physics | 21,783 | 95.4 % | 62.7 % |
| sociology | 10,654 | 92.6 % | 52.4 % |
| tennis | 45,704 | 99.6 % | 86.6 % |
| ray-charles (sample) | 152 | 95.4 % | 70.4 % |

- Every subject is about 100 % new to the 100, and taken as 0 % new to Everything.
- So subjects really do add: nearly all of each is new to Best 50,000, and half to nine-tenths is new to Top 1 million. No subject falls under Decision 5's 5 % line for any slice short of Everything.
- Spot check (medicine, new to Top 1 million): Amylmetacresol, Bartonella chomelii, Contraceptive security; shared: Renal corpuscle, Propafenone. Astronomy new: 208 Lacrimosa, HD 114533; shared: NGC 3242.
- **Cost:** 30 packs plus a chemistry nopic check, 8 at once; 573 MB and 145 s a pass (Top 1 million alone 318 MB, 120 s). Run twice (once before the section-page fix, once after), so about 1.15 GB in all, plus a few MB of spot checks.

### Task 2: Sets in the catalog

- [ ] `set`, `setRank`, `setLabel`, `subjectOf` and `adds` through `pin()`, the bulk fields, the curated path and `release_entry`, plus `PackEntry`. Group titles come from the set or base title (Decision 2).
- [ ] Edit `catalog.json` in place, then `check-links`, re-sign and `verify`. Update `PublishedCatalogTest`.
- [ ] Tests in `test_catalog_gen.py` and `CatalogParserTest`.
- [ ] **Commit.** `Catalog: Wikipedia and Wikivoyage as sets, subjects with what they add`

### Task 3: One Wikipedia row

- [ ] The set row (How much × Pictures), the title fix, Wikivoyage, "Add a subject" with its A–Z list and "adds" lines, WikiMed's line, and the sample hidden (Decisions 3 and 4).
- [ ] Tests in `SourceViewModelTest` and `SourceScreenTest`, against the published catalog:
  - one Wikipedia row with 4 slices;
  - sizes follow the pictures choice;
  - default Best 50,000 with pictures;
  - "Adds about …" against installed and against chosen;
  - "Already inside your Wikipedia" with Everything installed.
- [ ] ThemeShots: the Wikipedia row, the subject list, 360 dp, Field and Night. Review them.
- [ ] **Commit.** `Kiwix list: Wikipedia as one row, subjects as add-ons`

### Task 4: Bigger covers smaller

- [ ] Covering rules. Readiness counts covered items; onboarding and presets queue only the biggest slice; rows read "Inside your …" (Decision 5).
- [ ] The offer-to-remove prompt and `supersede` across a set. Search and Ask dedupe (Decision 6).
- [ ] Tests:
  - `ReadinessModelTest`: Reference with Top 1 million installed;
  - `OnboardingModelTest`: Best 50,000 and Top 1 million both chosen → one queued;
  - `RoomLibraryTest`: supersede across `zimName` in a set, keeping places and turning missing articles into history;
  - Home/Downloads prompt tests;
  - a `ZimLibrarySearch` dedupe test.
- [ ] Full suite, then build.
- [ ] **Commit.** `Wikipedia family: a bigger slice covers the smaller, with an offer to tidy up`

### Task 5: Walkthrough (Neal, Pixel 6)

- [ ] Ask Neal if the phone is free, then install over `app.granger`. Put the branch's catalog in the app's cache, as KB did, until it's merged.
- [ ] Neal checks:
  - the Kiwix list: one Wikipedia and one Wikivoyage row;
  - How much × Pictures sizes;
  - "Add a subject" lines;
  - WikiMed's line;
  - Readiness Reference;
  - with a cheap pair: get Wikipedia 100 essentials, then Best 50,000 mini (0.3 GB). The 100 should read "Inside your Best 50,000", and the remove offer should appear and work.
- [ ] Fix what the walkthrough finds. Record the result in the roadmap's WF row and a "Carried forward from WF" section.
- [ ] Merge and push only on Neal's say-so.
