# Plan L3: Spanish knowledge — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a user whose knowledge language is Spanish gets Spanish packs, tiers, onboarding, Classics, sources, On this day and Define. Nothing changes for an English user, and nothing already on the phone is hidden.

- Spec: `docs/superpowers/specs/2026-10-30-languages-design.md` §3, §5, §10.
- Research: `docs/superpowers/spikes/2026-10-30-l3-spanish-catalog.md` (with Neal's calls in §7).
- Roadmap row L0.

**Settled with Neal, 2026-10-30:**
- Spanish Comprehensive uses the full Spanish Wikipedia with pictures (`wikipedia_es_all_maxi`); Save space swaps it to nopic.
- Ready.gov en español is in Comprehensive only.
- iFixit en español is treated like `ifixit_en_all`: in the Making essentials, under the non-commercial flag.
- Clásicos Granger takes only works that are public domain in the US **and** where its readers live. Every named author, translator and editor died in 1945 or earlier: Spain gives life + 80 to authors who died before 1987, which is stricter than Argentina's life + 70.
- Clásicos Granger is its own collection.
- Define in a Spanish book uses English Wiktionary first, for learners.
- A second knowledge language is offered only when the phone lists more than one language.

**Defaults taken without asking, matching the English calls:**
- Stack Overflow en español stays out of the tiers.
- MedlinePlus en español has the English pack's standing.

**Two changes from the spec, for compatibility:**
1. **`formatVersion` stays 1.** The parser rejects any other version (`CatalogParser.FORMAT_VERSION`), so a bump would stop every installed build from reading new catalogs. Every new field is additive, and older builds ignore unknown keys.
2. **English stays where it is.** A domain's `essential` and `comprehensive` lists stay the English lists. Spanish goes in a new `languages: {"es": {...}}` beside them, rather than turning the lists into a map that older builds can't read. The same applies to presets and use cases.

**Out of this plan:** Research in Spanish (L4), the Spanish voice (L5), the app's own text (L1/L2), speech input (A0/A1).

**Tech stack:** unchanged. Python 3 for `tools/catalog-gen` and `tools/pack-builder` (pytest); Kotlin, Robolectric, JUnit 4 and Truth for the app. No Room schema change.
- **Build env:** `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
- **Worktrees:**
  - **Tasks 1–4** (tools, plus `core/catalog`) run in `.claude/worktrees/l3` on branch `l3-spanish-knowledge`, off main.
  - **Tasks 5–10** (app) run on the same branch, after it has merged `l2-spanish` once L1 is finished. That way every new string lands in resources with its Spanish beside it.

**Publishing:** two steps here are outward-facing and wait for Neal's OK, like a push:
- **Clásicos release:** uploading the Clásicos Granger release to GitHub.
- **Catalog:** merging the Spanish catalog to main.

`catalog/catalog.json` is what installed builds fetch. An older build would list the 60-odd Spanish Kiwix packs in Get more without any language filter, so the Spanish catalog merges together with the app that filters by language (Task 11).

## Data shapes (every task follows these)

**Language codes.** Every `language` in the catalog is BCP-47 primary subtags, comma-separated for multilingual packs:
- `en`, `es`, and `en,es` for Kiwix `eng,spa`
- `mul` stays `mul`

`catalog_gen.py` maps Kiwix's ISO 639-3 codes. The app normalises on parse too, so a cached old catalog and installed packs' ZIM metadata (`eng`) compare equal. "Has `es`" means Spanish.

**Copy.** Generator-written copy keeps its English field and gains:

```json
"i18n": {"es": {"title": "…", "blurb": "…"}}
```

That applies to domain titles, preset titles, use-case titles and blurbs, set titles, set descriptions and slice labels, and curated pack titles. The app shows the app language's copy and falls back to English. Text that Kiwix or Gutenberg wrote (pack titles and descriptions, book titles) stays as written.

**Readiness domain:**

```json
{"id": "medicine", "title": "Medicine & first aid", "essential": ["…en…"], "comprehensive": ["…en…"],
 "i18n": {"es": {"title": "Medicina y primeros auxilios"}},
 "languages": {"es": {"essential": ["wikipedia_es_medicine_maxi"], "comprehensive": ["…", "medlineplus.gov_es_all"]}}}
```

A domain with no list for a language is empty in that language.

**Presets:**
- `Preset` gains `language` (default `en`) and `i18n`.
- The generated tier presets are `efficient` and `comprehensive` (English, unchanged ids), plus `efficient-es` and `comprehensive-es` with `"language": "es"`.
- The Clásicos collection preset is `clasicos-granger`, with its own title instead of the hardcoded "Granger Classics: all books".

**Use cases:** `items` stays English, plus `languages: {"es": {"items": […]}}` and `i18n`. A use case with no items in a language isn't offered in that language.

**Knowledge languages (app):**
- An ordered list of primary codes: the first is the user's main one.
- The default is the app language's code when the catalog has tiers for it, else `en`.
- **Readiness of a domain** is the best coverage over the knowledge languages: Basic if any language's essential tier is started, Comprehensive if any one language's comprehensive tier is complete.
- **Pick a start** offers the first language's presets. A second language adds its encyclopedia (that language's `reference` essential) as its own Review group, which the user can drop.

## Task 0: Worktree

- [x] **Step 1:** Run `git worktree add .claude/worktrees/l3 -b l3-spanish-knowledge main`. Then run `git submodule update --init` and copy `local.properties`.
- [x] **Step 2:** Run `python -m pytest tools/catalog-gen tools/pack-builder/tests` and `./gradlew testDebugUnitTest`, and record the counts.
  - 2026-10-30 at bad36fef: pytest 96 (catalog-gen) + 84 (pack-builder); `./gradlew testDebugUnitTest` 2553 (53 skipped, 0 failed).

## Task 1: The generator learns languages (desk, `tools/catalog-gen`)

**Files:** `catalog_gen.py`, `test_catalog_gen.py`, `kiwix_families.json`, `curated.json` (`kiwix.languages` only).

- [x] **Step 1: Codes.**
  - Add `bcp47(code)`: `eng` → `en`, `spa` → `es`, and `eng,spa` → `en,es` (keep the order; drop duplicates). Use a small table for the codes Kiwix uses, and pass through unknown codes.
  - Apply it in `opds_entries` (`language`), `release_entry` and `book_entries`.
  - The voices' `en-US` stays as written; voice language is L5's.
  - Tests: the mapping, a `mul` pack, and a release descriptor that already says `en`.
- [x] **Step 2: Families.**
  - Generalise the `_en`-only Wikipedia subject rows to `^wikipedia_[a-z]{2,3}_<subject>$`: medicine, physics, chemistry, mathematics, molcell, climate-change, history, geography, computer, movies, football, basketball and comics (research §1.4 (1)).
  - Add held rows `^kiwix\.ecured_` (`"hold": "licence-nc"`) and `^marxists\.org_` (`"hold": "licence-mixed"`), each with its evidence URL from research §2.
  - Pin `wiktionary_es_all` to the `nopic` flavour, so the stale unflavoured 2025 build isn't listed. Do it as a curated row, or as a family-row `flavours` rule if one exists. Otherwise add the smallest rule that does it, with a test.
  - `ifixit_es_all` needs no change: the `^ifixit_` row already flags it as non-commercial.
- [x] **Step 3: Kiwix languages.** Set `curated.json` `kiwix.languages` to `["eng", "spa"]`. A dry `build` against the live feed should list about 63 new Spanish entries (research §0), and the report should name ecured and marxists as held.
  - Dry build 2026-10-30: Kiwix lists 430 `spa` entries; 67 listed with `es` (62 new Spanish-only, plus the 5 multilingual packs already listed). Held: `kiwix.ecured` (licence-nc), `marxists.org` (licence-mixed), the stale unflavoured `wiktionary_es_all` (flavour), 356 TED and Khan Academy (video). No family-table row: inciclopedia, partituras-gratis.es, wikidex.
- [x] **Step 4: `i18n` passthrough.**
  - `readiness_block`, `onboarding_block` and the preset code copy an `i18n` object through. They check that it's a map from a language code to string fields, and fail the build otherwise.
  - `apply_sets` copies a set's `i18n` (title, description, per-slice label) onto every flavour of each slice as `i18n.es.title` (as "<set>: <label>") and `i18n.es.setLabel`.
- [x] **Step 5: Per-language tiers.**
  - `readiness_block` reads `languages.<code>.essential/comprehensive`, checks every token as it checks the English ones, folds essential into comprehensive, and emits `languages` on the domain.
  - It generates `efficient-<code>` and `comprehensive-<code>` presets with `language` and `i18n` titles ("Inicio eficiente" and "Biblioteca completa"; mark both for the friend review).
  - `@<collection>` expands by collection and domain as today.
  - `onboarding_block` does the same for use cases.
- [x] **Step 6: Sizes per language.** `tier_sizes(catalog, language="en")` reads that language's lists, and the CLI prints one table per language. Tests use a small Spanish fixture with a held subject pack.
- [x] **Step 7:** Commit "L3: the catalog generator learns languages".

## Task 2: The Spanish Wikipedia set (desk, Mac)

- [x] **Step 1:** Run `zim_probe.py titles` (range reads; nothing is downloaded whole) over:
  - `wikipedia_es_top_mini`
  - `wikipedia_es_all_mini` (if it's over budget, take Kiwix's count and treat it as holding every subject, as WF did for English Everything)
  - each `wikipedia_es_*` subject pack's smallest flavour
- [x] **Step 2:** Add to `pack_sets.json`:
  - the `wikipedia_es` set: rank 1 `wikipedia_es_top` ("Los 348.000 mejores artículos"; measured: "Los 50.000 mejores artículos"), rank 2 `wikipedia_es_all` ("Todo")
  - the subjects, with their `adds` against Top
  - `i18n` for the English user who browses Spanish packs: English title "Wikipedia in Spanish", labels "Best 348,000 articles" (measured: "Best 50,000 articles") and "Everything". The Spanish copy is the set's own.

  A one-slice set isn't a set, so `wikivoyage_es` stays a plain pack.
- [x] **Step 3:** Add `"wikipedia_es_all": ["wikipedia_es_*"]` to `readiness.contains` (the prefix covers Top too). The app's `Containment` also reads the sets, but the generator's presets and `tier_sizes` read only `contains`, as for English. Check that `tier_sizes` drops every Spanish subject and Top from `comprehensive-es`.
  - Measured 2026-10-30 (`zim_probe.py titles` on each smallest flavour): **Top holds 50,002 articles, not 348,000**. Kiwix's 348,403 counts Top's 290,127 redirects too, as English Top is also 50,002. So Top's label is "Los 50.000 mejores artículos" / "Best 50,000 articles". Everything's directory is 244 MB, over budget: its count is Kiwix's `Counter` (2,142,129 text/html; Kiwix's 4.23M again counts redirects), and it holds every subject. Each subject adds 80–98 % to Top (chemistry 0.805 … basketball 0.984), so none counts as held by Top.
- [x] **Step 4:** Commit "L3: Spanish Wikipedia as one family".

## Task 3: Spanish tiers, presets, onboarding and copy (desk)

**Files:** `curated.json`.

- [x] **Step 1: Curated rows** for the Spanish anchors, with Spanish titles and English `i18n` (`i18n.en`): Top (maxi and nopic), All (maxi and nopic), WikiMed en español (maxi and nopic), Wikcionario (nopic), Wikiviajes (maxi and nopic), and English Wiktionary for learners (already curated). Featured marks them under Recommended for a Spanish user.
- [x] **Step 2: The Spanish lists** (research §1.1, with §7's calls). Each domain gets `languages.es` and `i18n.es.title` (research §1.5):

  | Domain | essential | comprehensive adds |
  | --- | --- | --- |
  | medicine | `wikipedia_es_medicine_maxi` | `medlineplus.gov_es_all` |
  | food | — | `ready.gov_es_all`, `scoutwiki.org_es_all_maxi` |
  | making | `ifixit_es_all` | `solar.lowtechmagazine.com_mul_all` |
  | science | physics, chemistry, mathematics (`_maxi`) | molcell, climate-change (`_maxi`) |
  | technology | `wikipedia_es_computer_maxi` | — (Stack Overflow en español stays out) |
  | maps | `family:maps`, `wikivoyage_es_all_maxi` | `wikipedia_es_geography_maxi` |
  | reference | `wikipedia_es_top_maxi` | `wikipedia_es_all_maxi`, `wiktionary_es_all_nopic`, `wikibooks_es_all_maxi`, `wikiversity_es_all_maxi`, `vikidia_es_all_maxi`, `spanish.stackexchange.com_mul_all` |
  | literature | `@clasicos-granger` | `wikisource_es_all_maxi`, `wikiquote_es_all_maxi` |
  | thought | `@clasicos-granger` | `wikipedia_es_history_maxi` |

  `@clasicos-granger` resolves only once Task 4's release is listed. Until then leave it out, and say so in the commit.
- [x] **Step 3: Use cases** in Spanish: the same seven ids, with `languages.es.items` from the Spanish packs and `i18n.es` titles and blurbs. Leave out a use case with nothing Spanish to add, rather than padding it with English packs.
- [x] **Step 4: Sizes.** Build, then print the per-language table. Expected, packs only and before L4's vectors:
  - Efficient: about 9.5 GB with pictures, 5.5 GB with Save space.
  - Comprehensive: about 48.2 GB with pictures, 24.4 GB with Save space.

  Write the real table into this plan.

  **Measured 2026-10-30** (dry build of the live feed; packs only, no vectors, `family:maps` left out; `@clasicos-granger` not yet listed, about 0.07 GB more once it is). "Save space" swaps each pack for its `_nopic` sibling where one exists, as `OnboardingModel` does.

  | Domain (es) | Efficient | Efficient, Save space | Comprehensive | Comprehensive, Save space |
  | --- | ---: | ---: | ---: | ---: |
  | medicine | 0.47 | 0.17 | 2.24 | 1.94 |
  | food | 0.00 | 0.00 | 2.09 | 2.09 |
  | making | 3.68 | 3.68 | 4.38 | 4.38 |
  | science | 0.82 | 0.24 | 1.05 | 0.32 |
  | technology | 0.23 | 0.10 | 0.23 | 0.10 |
  | maps | 0.14 | 0.04 | 0.46 | 0.11 |
  | reference | 4.07 | 1.21 | 37.70 | 14.15 |
  | literature | 0.00 | 0.00 | 0.58 | 0.43 |
  | thought | 0.00 | 0.00 | 0.69 | 0.20 |
  | **Whole start** | **9.40 GB** | **5.44 GB** | **46.66 GB** | **22.86 GB** |

  - Per-domain Comprehensive counts the subject packs; the whole start drops them and Top as held by `wikipedia_es_all`.
  - `efficient-es` (8 packs): WikiMed en español, iFixit en español, physics, chemistry, mathematics, computer, Wikiviajes, Top (all maxi).
  - `comprehensive-es` (14 packs): MedlinePlus, Ready.gov, Scoutwiki, iFixit, Low-tech Magazine, Wikiviajes, Wikipedia en español (all, maxi), Wikcionario, Wikilibros, Wikiversidad, Vikidia, Spanish Language SE, Wikisource, Wikiquote.
  - Comprehensive is 1.5 GB under the expected 48.2 / 24.4 GB because those totals still counted Stack Overflow en español (1.44 GB), which stays out of the tiers.
  - English is unchanged: 17.7 GB / 99.7 GB.
- [x] **Step 5: Neal signs off the lists and sizes.** This is the spec §5.2 sign-off, and it happens before any signing.
  - **Signed off 2026-10-30:**
    - The Spanish lists stand as built: Efficient 9.4 GB with pictures (5.4 GB Save space), Comprehensive 46.7 GB (22.9 GB). Packs only.
    - Stack Overflow en español stays in the Spanish Software use case.
    - The Clásicos rights rule is Spain and Argentina only (every named person died in 1945 or earlier). Mexico's life + 100 is not applied.
  - The Spanish copy still goes to the friend review.
- [ ] **Step 6:** Commit "L3: Spanish tiers, presets and onboarding".

## Task 4: Clásicos Granger (desk, Mac)

**Files:** `tools/pack-builder/classics/manifest_es.json`, plus `manifest.py`, `pack_builder.py`, `epubpack.py`, `zimpack.py`, `chapters.py` and their tests. Research §5.5 lists every place.

- [x] **Step 1: Builder changes** (research §5.5 table):
  - The language check reads `m.language`, so `en,es` readers pass.
  - Spanish stop words.
  - The manifest reads `collection`, `bookIdPrefix`, section `domain` and `learner`.
  - `language` comes from the manifest.
  - `book_id` uses the prefix (`gc-es-…`).
  - Nav titles: "Índice" and "(trad. …)".
  - `clean_title` handles "por …", "Índice" and "Capítulo" on real PG HTML.
  - `catalog_gen.BOOK_DOMAINS` is keyed by section id plus the manifest's `domain`, so Spanish *thought* gets its books.
  - English output must be byte-identical. Rebuild three English books and compare their sha256.
- [x] **Step 2: The rights rule** (Neal's call 4):
  - Each work records `died` for every named author, translator and editor, with a `diedSource` (Wikipedia or a national library page).
  - `validate()` fails on any death after 1945 (`PD_DEATH_LINE`, the year Spain's life + 80 leaves free in 2026), on an unknown death year for a named person, and on a `published` (the translation's own first year for translations) after 1930.
  - Anonymous and pre-1800 works pass with `"died": null` and a note.
  - The research's §5.2 eight authors are out. So is anyone else the check finds (for example the translator Ramón D. Perés, d. 1956, and any school-edition editor who died after 1945). List them in the commit.
- [x] **Step 3: The manifest.** Write `manifest_es.json` from research §5.3 and the appendix:
  - ten sections with their domains;
  - the twenty learner picks marked;
  - `$b` dropped from titles;
  - one copy of each duplicate;
  - the translators' years checked (research §5.2).

  Expect about 220–230 works once the rule is applied.
- [x] **Step 4: Build** the EPUBs and the release descriptor, `releases/clasicos_granger_es_books.json` (collection `clasicos-granger`, about 65 MB). Open five EPUBs in the reader's test harness (or `epubcheck` if it's installed) and check: one learner reader, one verse, one play, one multi-volume, one translation.
- [ ] **Step 5: Upload (waits for Neal).** Create the GitHub release `clasicos-es-YYYY-MM-DD` and add a `curated.json` `books` row with `"license": "Public domain (US)"`. Then restore `@clasicos-granger` in Task 3's lists.
- [x] **Step 6:** Commit "L3: Clásicos Granger".

### Task 4 results (2026-10-30, branch `l3-clasicos`)

Steps 1–4 and 6 are done; Step 5 (the release upload) waits for Neal. The manifest is `tools/pack-builder/classics/manifest_es.json` and the descriptor is `tools/pack-builder/releases/clasicos_granger_es_books.json`: **185 books, 46.3 MB**, release date 2026-10-30. Its URLs point at the `clasicos-es-2026-10-30` release, which doesn't exist yet.

**The rights rule as applied.** Every named author, translator and editor has a `died` year and a `diedSource` (Wikidata, Wikipedia or a Library of Congress authority record). An editor here is anyone whose words are in the book: editors, prologue and introduction writers, annotators, commentators, compilers, and vocabulary makers. That includes people named only on the book's title page, not in Gutenberg's metadata. Illustrators don't count, because the EPUBs carry no images. 185 of the 244 proposed works pass. The plan's estimate of 220–230 was too high: the research checked authors and translators but not the editors and prologue writers on the title pages. Many American school editions also fail because their editors' death years can't be found.

**Works kept per section:** lecturas 5, infantil 8, cuentos 26, poesía 17, teatro 14, novelas 43, universales 34, ensayo 15, historia 21, escritura 2. That makes 147 books in *literature* and 38 in *thought*.

**Learner picks kept (11 of 20):** #15353 *A First Spanish Reader*, #11047 *Libro segundo de lectura*, #10814 Bécquer *Legends, Tales and Poems*, #14235 *Más vale maña que fuerza*, #55206 Samaniego *Fábulas*, #29497 Iriarte *Fábulas literarias*, #19898 *La Edad de Oro*, #36558 *Ratón Pérez*, #73486 *Corazón*, #55514 *Cuentos de amor*, #17340 *Marianela*. Lost: #36805, #22065, #24250, #39647, #33406, #15532, #29731, #16109 and #9980 (reasons below).

**Judgment calls for Neal to check:**
- #66337–66340 Ovid *Metamorfóseos* (Crivell, Imprenta Real 1805–1819). Wikidata gives Crivell's death only to the century: the 19th. The manifest records 1900 as an upper bound. Strictly, "unknown year = out" would drop these four.
- #64775 *Los Lusíadas*: the translator Gómez de Tapia's death year is unknown, but the work dates from 1580, so the pre-1800 rule applies.
- #15353 *A First Spanish Reader* prints the Guatemalan anthem's words under the composer's name, Rafael Álvarez (d. 1946). The words are by José Joaquín Palma (d. 1911), and the music isn't reproduced. The book is kept.
- #73486 *Corazón*, #24823 *Silas Marner*, #24801 *El prisionero de Zenda*, #74029 Dumas and #67961 *El arte de amar* are anonymous translations. Each is dated by its own edition (1925, 1919, 1909, 1912 and 1821), so `published` is that edition's year.
- #5881 holds only the Epistle to the Romans, although Gutenberg catalogues it as the 1909 Reina-Valera New Testament. It's listed as *Epístola de San Pablo a los romanos*. Its opening note is by an anonymous modern web transcriber.
- Some editions are later printings of earlier editions: #29506 (Bourland's 1907 edition, printed in 1934) and #16625 (Cejador's 1913 edition, printed in 1931). Gutenberg marks both public domain in the USA, and the editor of each died before the reprint.

**Excluded (59):**
- #36805 *Spanish Tales for Beginners* (`lecturas`, learner): contributor Louise Reinhardt (b. 1866): death year not found (LoC no97020669 gives none).
- #22065 *An Elementary Spanish Reader* (`lecturas`, learner): author Earl Stanley Harrison (b. 1871): death year not found.
- #39647 *The Spanish American Reader* (`lecturas`, learner): author Ernesto Nelson: death year not found (LoC nr94034672 gives none).
- #24250 *Lecturas fáciles con ejercicios* (`lecturas`, learner): author Max A. Luria died 1966 (LoC no2013030201); co-author Lawrence A. Wilkins's death year not found.
- #33406 *Spanish short stories* (`lecturas`, learner): co-editor Louise Reinhardt: death year not found.
- #63464 *Nuestra Pampa; libro de lectura* (`lecturas`): author W. Jaime Molins died 1965.
- #16059 *Modern Spanish Lyrics* (`lecturas`): co-editor S. Griswold Morley died 1970.
- #15532 *Novelas Cortas* (`lecturas`, learner): editor William F. Giese (b. 1864): death year not found (LoC no91028075 gives none).
- #29731 *El Capitán Veneno* (`lecturas`, learner): editor Percy Bentley Burnet: death year not found on Wikipedia, Wikidata or LoC.
- #15725 *Doña Perfecta* (`lecturas`): vocabulary by Steven T. Byington, died 1957.
- #24917 *Mariucha* (`lecturas`): editor S. Griswold Morley died 1970.
- #28106 *Tres Comedias* (`lecturas`): author Jacinto Benavente died 1954; editor John Van Horne died 1959.
- #16109 *Doña Clarines y Mañana de Sol* (`lecturas`, learner): editor S. Griswold Morley died 1970.
- #15781 *El Estudiante de Salamanca and Other Selections* (`lecturas`): editor George Tyler Northup died 1964.
- #38124 *Spanish Composition* (`lecturas`): author Edith J. Broomhall: death year not found on Wikipedia, Wikidata or LoC.
- #64058 *Fábulas y cuentos en verso* (`infantil`): compiler María Goyri died 1954; series director Ramón Menéndez Pidal died 1968.
- #9980 *Platero y yo* (`infantil`, learner): author Juan Ramón Jiménez died 1958; Federico de Onís (introduction) died 1966.
- #55215 *Cuando la tierra era niña* (`infantil`): translator Gregorio Martínez Sierra died 1947.
- #69552 *El libro de las tierras vírgenes* (`infantil`): translator Ramón D. Perés died 1956.
- #65738 *El conde Lucanor* (`cuentos`): prologue and notes by F. J. Sánchez Cantón, died 1971.
- #40827 *La voz de la conseja, t.1* (`cuentos`): includes Pío Baroja (d. 1956), Jacinto Benavente (d. 1954), Pedro Mata (d. 1946) and Pedro de Répide (d. 1948); compiler Emilio Carrere died 1947.
- #30053 *Los Puritanos, y otros cuentos* (`cuentos`): editor W. T. Faulkner: death year not found.
- #10825 *La Navidad en las Montañas* (`cuentos`): editors Edith A. Hill and Mary Joy Lombard: death years not found.
- #58103 *20 poemas para ser leídos en el tranvía* (`poesia`): author Oliverio Girondo died 1967.
- #68131 *Obras* (`poesia`): editor Tomás Navarro Tomás died 1979.
- #60198 *Fuente Ovejuna* (`teatro`): edition revised by Américo Castro, died 1972.
- #23206 *La moza de cántaro* (`teatro`): editor Madison Stathers (b. 1877): death year not found.
- #28408 *Comedias* (`teatro`): editor and commentator Justo Gómez Ocerín died 1968.
- #28002 *Electra* (`teatro`): editor Otis Gridley Bunnell: death year not found on Wikipedia, Wikidata or LoC.
- #29677 *El trovador* (`teatro`): editor Herbert Hunter Vaughan died 1948.
- #10909 *Los Amantes de Teruel* (`teatro`): editor George Wallace Umphrey died 1950 (LoC no98084301).
- #57590 *La verdad sospechosa* (`teatro`): preliminary notes by Julio Jiménez Rueda, died 1960.
- #18580 *Los favores del mundo* (`teatro`): edition by Pedro Henríquez Ureña, died 1946.
- #58194 *Tirso de Molina* (`teatro`): selection by Samuel Gili Gaya (d. 1976); series director Ramón Menéndez Pidal (d. 1968).
- #76410 *La Malquerida* (`teatro`): author Jacinto Benavente died 1954.
- #16484 *Juanita La Larga* (`novelas`): modern edition with a prologue by Paulino Garagorri (d. 2007), printed 1982–83.
- #27738 *José* (`novelas`): editor F. J. A. Davidson died 1946 (LoC no2017053727); vocabulary by Alice P. F. Hubbard, death year not found.
- #60464 *El árbol de la ciencia* (`novelas`): author Pío Baroja died 1956.
- #13264 *Zalacaín El Aventurero* (`novelas`): author Pío Baroja died 1956.
- #12848 *Las inquietudes de Shanti Andia* (`novelas`): author Pío Baroja died 1956.
- #43432 *La busca* (`novelas`): author Pío Baroja died 1956.
- #43017 *Mala Hierba* (`novelas`): author Pío Baroja died 1956.
- #40544 *Aurora roja* (`novelas`): author Pío Baroja died 1956.
- #75126 *Sab (novela original)* (`novelas`): introduction by Hugo D. Barbagelata (Paris, 1920), died 1971.
- #29920 *La gloria de don Ramiro* (`novelas`): author Enrique Larreta died 1961.
- #18166 *Amistad funesta* (`novelas`): includes essays on Martí by Miguel Tedín and Román Vélez, whose death years were not found.
- #16082 *Angelina* (`novelas`): preliminary study by Ventura García Calderón, died 1959.
- #26545 *Antonio Azorín* (`novelas`): author Azorín died 1967.
- #63509 *Edipo rey; Edipo en Colona; Antígona* (`universales`): translator José Pérez Bojart: death year not found.
- #66023 *Tragedias* (`universales`): editor Julio Torri died 1970.
- #61851 *El crimen y el castigo* (`universales`): translator Pedro Pedraza y Páez (b. 1877): death year not found (Wikidata gives only the 20th century); translation year unknown.
- #75739 *Los miserables, tomo 1* (`universales`): translator "J. A. R." unidentified, so no death year.
- #78068 *Los miserables, tomo 2* (`universales`): translator "J. A. R." unidentified, so no death year.
- #61887 *Una historia de dos ciudades* (`universales`): translator Gregorio Lafuerza: death year not found; translation year unknown.
- #7109 *Candido, o El Optimismo* (`universales`): anonymous translation whose year can't be established.
- #25807 *Poemas* (`universales`): translator Alberto Lasplaces died 1950.
- #57448 *Meditaciones del Quijote* (`ensayo`): author José Ortega y Gasset died 1955.
- #43861 *Granos de oro* (`ensayo`): editor Rafael G. Argilagos died 1967.
- #33267 *Facundo* (`ensayo`): preliminary notice by Ricardo Rojas, died 1957.

**Corrections to the research:**
- The 244-work proposal checked only Gutenberg's author and translator fields. Ten books' title pages name editors or prologue writers who fail the rule: Sánchez Cantón, Américo Castro, Jiménez Rueda, Henríquez Ureña, Garagorri, Barbagelata, Ricardo Rojas, García Calderón, Tedín and Román Vélez, and Hill and Lombard. Gutenberg's own editor fields name more: Gómez Ocerín, Gili Gaya, Navarro Tomás, Torri, Morley, Northup, Van Horne, Umphrey, Vaughan, Davidson, Byington and Goyri.
- The §5.2 list was incomplete. Besides its eight authors and Perés, the following also fail: Molins (d. 1965), Argilagos (d. 1967), Lasplaces (d. 1950, Poe *Poemas*), Carrere, Mata and Répide (*La voz de la conseja*), and Luria (d. 1966).
- Of the §5.2 translator years: Pedraza y Páez has no death year (out); Lasplaces died 1950 (out); Martínez Sierra died 1947 (out); Azaña's *La Biblia en España* is 1921 (kept).
- #5881 isn't a New Testament; see above.
- #61887 Dickens and #61851 Dostoyevsky are undated Sopena editions with translators whose death years can't be found.
- `language` is `spa` (ISO 639-3, like English's `eng`), not `es`. The builder maps it to `es` for EPUBs, descriptors and the Gutenberg check.

**Five EPUBs checked** (all 185 pass `epubcheck` 5 with no errors or warnings):
- *Marianela*, the learner reader: Spanish metadata, "Índice" nav, 23 pages from "Preliminares" to "-XXII- Adiós".
- *El gaucho Martín Fierro*, the verse: line breaks are kept stanza by stanza. The source has no headings, so the book is one 76 KB page.
- *El sí de las niñas*, the play: pages for Personas and Actos I–III.
- *Historia de las Indias*, tomo 1 de 5, a multi-volume set: 87 pages by chapter.
- *La Odisea* (Segalá), a translation: the translator is listed as an OPF contributor, with a page per canto.

None of the five mentions Gutenberg.

## Task 5: The app reads languages (`core/catalog`)

**Files:** `Catalog.kt`, `CatalogParser.kt`, a new `Languages.kt`, and tests.

- [x] **Step 1: `Languages`.** Add `normalise(code)` (ISO 639-2/3 → 639-1, from `Locale.getISOLanguages()` and `isO3Language`; comma lists kept in order), `codes(entryLanguage): List<String>`, and `has(entryLanguage, code)`. These are pure JVM functions.
- [x] **Step 2: Model.**
  - `PackEntry.language` and `BookEntry.language` are normalised in the parser.
  - `Domain`, `Preset` and `UseCase` gain `i18n: Map<String, Copy>` and `languages: Map<String, Tiers>` (or `Items`).
  - `Preset` gains `language = "en"`.
  - The parser checks each language's tokens as it checks English ones, and drops a language whose lists are empty after checking.
- [x] **Step 3: Helpers.** Add:
  - `Domain.essential(lang)`, `Domain.comprehensive(lang)` (English lists for `en`)
  - `Copy`-picking `title(appLang)`
  - `Catalog.tierPresets(lang)`
  - `Catalog.languages`: the codes that have tiers
- [x] **Step 4: Tests.**
  - An old catalog (no `languages`, no `i18n`, `eng` codes) parses to the same English model as today.
  - A Spanish domain with an unknown token drops only that token.
  - `i18n` falls back to English.
  - Also: `PackEntry` gains `i18n`, `setTitle` and `setDescription` (the generator writes them on curated packs and set slices), and `localised()` picks a field of the app language's copy, else the entry's own text. A Spanish pack's own title is Spanish with its English in `i18n.en`, so English isn't short-circuited.
  - `./gradlew testDebugUnitTest`: 2563 (was 2553; 53 skipped, 0 failed). `:app` unchanged at 1406.
- [x] **Step 5:** Commit.

## Task 6: Knowledge languages (app)

- [x] **Step 1: `KnowledgeLanguages`** in `settings/`. It's stored like the other settings, and its default is described under Data shapes. Expose it as a `Flow<List<String>>`.
- [x] **Step 2: Settings → Language** (L2 Task 3's screen) gains "Idiomas de tu biblioteca" / "Your library's languages". It lists the languages the catalog has tiers for; at least one stays on. Wording goes through the glossary and both resource files.
- [x] **Step 3: Onboarding.** When the phone's `LocaleList` has more than one language the catalog supports, the first step offers the second one ("¿También en inglés?" / "Also Spanish?"). Otherwise nothing new appears (Neal's call 4).
- [x] **Step 4: Tests.**
  - The default follows the app language.
  - An unsupported app language falls back to `en`.
  - The prompt shows only with two supported phone languages.
- [x] **Step 5:** Commit.
  - Done: `settings/KnowledgeLanguages.kt` (SharedPreferences `knowledge`; nothing stored until the user chooses), `AppLanguages.code` (the choice, else the phone's language when the app has text in it, else `en`). Settings → Language shows the second section only when the catalog has tiers in two or more languages: each language named in itself with a checkbox, the last one on can't be unticked, "Main language" under the first, "Make main" beside the others. Onboarding's Welcome step shows "Also Spanish?" / "¿También en inglés?" as an unticked checkbox (it writes the setting at once); the language is named in the app's language.

## Task 7: Readiness, Pick a start and Get more by language (app)

- [x] **Step 1: Readiness.**
  - `ReadinessModel` measures each domain over the knowledge languages: best coverage, with a missing essential taken from the first language.
  - An empty domain in every knowledge language says "No Spanish packs for this area yet" (a resource string, with the language name as an argument).
  - Domain titles come from `i18n`.
  - Signals are unchanged.
- [x] **Step 2: Pick a start.**
  - `OnboardingModel.choices` uses `catalog.tierPresets(first)`.
  - Save space swaps flavours as today.
  - A second language adds its `reference` essential as its own Review group.
  - Use cases read the first language's items, plus the second language's when it's on.
  - `voiceLine` is unchanged (L5).
- [x] **Step 3: Get more.**
  - The Kiwix list's language facet defaults to the knowledge languages; multilingual packs count for each of their languages.
  - Recommended shows the featured packs in the knowledge languages.
  - Set rows take their title and description from the catalog's set `i18n`, replacing `KiwixList`'s hardcoded `wikipedia_en` copy.
  - `SourceViewModel.OWN_ROW_SUBJECTS` holds the medicine subject of every language.
  - An installed pack in another language always shows.
- [x] **Step 4: Browse.** The empty-Browse suggestion takes the encyclopedia from the first language's Efficient preset.
- [x] **Step 5: Tests.**
  - A Spanish-only user sees Spanish tiers and starts.
  - An English user sees exactly today's lists. Pin this with the current `ReadinessModelTest` and `OnboardingModelTest` expectations unchanged.
  - A bilingual user gets best-of coverage.
- [x] **Step 6:** Commit.
  - Done. Choices where the plan left room:
    - **Readiness:** a language whose list has no essential tier (Spanish Food) counts as Basic once any of its comprehensive items is here; otherwise such a domain could never leave None short of the whole tier. The bar's sizes are the first knowledge language's with tiers. A domain with no tiers in any knowledge language says "No Spanish packs for this area yet" in place of its status line, and raises no signal.
    - **Pick a start:** the start ids stay `efficient` / `comprehensive` whatever the language, so the rest of onboarding is unchanged. The second language's encyclopedia is an extra under the starts, ticked by default ("Encyclopedia in Spanish", like Ask and the voice) and its own Review group. A use case with no items in any knowledge language isn't offered.
    - **Get more:** the pack lists (Kiwix, Gutenberg, Kiwix books) and Granger's books show the knowledge languages, `mul` packs and anything installed or downloading; an "All languages" chip shows the rest. **A search looks in every language**, so the existing search tests keep their French pack. Granger's collection presets also follow the knowledge languages. The set copy comes from the catalog (`i18n.<app>.setTitle` etc., then `setTitle`); the two English sets keep their resource line, the only Spanish their copy has. `OWN_ROW_SUBJECTS` is now `ownRow()`, `^wikipedia_[a-z]{2,3}_medicine$`. Curated pack titles show the app language's copy (`KiwixList.title`).

## Task 8: Clásicos Granger in the app

- [x] **Step 1: Generalise the Classics constants.**
  - `OnboardingModel.CLASSICS`, `SourceViewModel.CLASSICS_PRESET` and `HomeViewModel`'s `c:granger-classics` feed become a set of Granger collections: `granger-classics` and `clasicos-granger`.
  - Home's default feed uses the first knowledge language's collection.
- [x] **Step 2: Filing.**
  - A catalog book files by its catalog `collection`, so `gc-es-*` books land in "Clásicos Granger", not "Granger Classics".
  - Check `Filing.kt` (`BookOrigin.GRANGER`), the default-key list in `LibraryDatabase` and `CollectionScreen`.
  - Add a test that files one book of each collection.
- [x] **Step 3: Download order.** Setup downloads the voice first, then either Classics collection (Plan DL Decision 5).
- [x] **Step 4:** Commit.
  - Done. `Filing.GRANGER_COLLECTIONS` (`en` → `granger-classics`, `es` → `clasicos-granger`) is the one table: onboarding's download order and quick-first line, Get more's Granger presets (first, in knowledge-language order) and Home's first-run feeds read it. Home seeds a Random feed for each of the library's languages' collections, then the app language's (so a Spanish phone seeded before the catalog loads still gets Clásicos); one not on the phone stays dormant, like Quotes.
  - Filing: the library keeps no catalog collection per book, so a Granger book's id stands in for it: `gc-es-…` files in `clasicos-granger` ("Clásicos Granger"), anything else in Granger Classics (`gc-essays-…` stays English). The v9 migration's Kiwix-folder exclusion lists `clasicos-granger` too.
  - Default collection names (L2's note), **no schema change**: `ShelfCollection.shownName()` shows the app's words for "My books", "Other" and "On this phone" while the stored name is still the English default (`Filing.storedDefaultName`); a renamed or user-made collection shows as stored. Granger Classics, Clásicos Granger, Archive.org, LibriVox and Kiwix are names and stay as stored. Media type names (Literature, Music…) are still stored English: not in this step.
  - Glossary change: in Spanish, the English collection keeps its name "Granger Classics" (`onboarding_granger_classics`), since "Clásicos Granger" is now the Spanish collection. The Get more link (`sources_link_classics`) still says "Clásicos Granger": it opens Granger's books, which a Spanish library sees as Clásicos.

## Task 9: Sources in Spanish (app and `core/archive`)

- [ ] **Step 1: archive.org.**
  - When the knowledge languages include Spanish, queries add `language:(spa OR Spanish)`.
  - With English too, the clause is `language:(eng OR English OR spa OR Spanish)`.
  - The shelves get Spanish subject words, with titles from resources.
  - The 1930 rule is unchanged.
  - Pin the query strings in `ArchiveQuery` tests.
- [ ] **Step 2: LibriVox.**
  - Filter each item's `language` field client-side against the knowledge languages.
  - Strip the Spanish articles *el, la, los, las, un, una* for sorting, beside the English ones.
  - Tests use a fixture with one Spanish item and one English item.
- [ ] **Step 3: `TextToEpub`** writes the book's declared language, or else a detected one: Spanish if its stopword overlap beats English's on the first 2,000 words, using the word list in `core/text`. It no longer hardcodes `en`. `EpubPackage`'s `"en"` fallback works the same way.
- [ ] **Step 4:** Commit.

## Task 10: Home, Browse and Define per language (app)

- [ ] **Step 1: `DayPage` per language.**
  - `title(day, lang)`:
    - English "October 22", unchanged.
    - Spanish "22 de octubre": lower-case month, no leading zero, and 1 January is "1 de enero".
  - The section map:
    - `en`: Events / Births / Deaths.
    - `es`: Acontecimientos / Nacimientos / Fallecimientos.
  - Years are split on "–" for English and ":" for Spanish. A line with no year keeps its text.
  - Tests use fixtures cut from the Spanish ZIM's `22_de_octubre` (research §4).
- [ ] **Step 2: On this day** draws from the encyclopedia of the first knowledge language that has one installed, then any installed Wikipedia.
- [ ] **Step 3: Define.**
  - `Dictionary.choose(packs, bookLanguage, knowledgeLanguages)` prefers, in order:
    1. the Wiktionary in the user's main language when the book is in another language (the learner case: English Wiktionary for a Spanish book);
    2. the Wiktionary in the book's language;
    3. any Wiktionary, with a note ("Defined in English Wiktionary").
  - The page opens at the anchor for the book's language: `#Spanish` in en.wiktionary, `#Español` in es.wiktionary.
  - Tests cover each case and the anchor.
- [ ] **Step 4: `_en` assumptions.** Check `PageTitles`, `IndexPages`, `Filing`, `GutenbergNames` (add "Todos los libros en español" for `gutenberg_es_all`) and `LibraryDatabase`'s Gutenberg names. Fix each one, or comment why it's language-neutral.
- [ ] **Step 5:** Commit.

## Task 11: Sign, merge, walkthrough

- [ ] **Step 1:** Full suites:
  - `pytest` for tools
  - `./gradlew testDebugUnitTest` with the count at or above Task 0's
- [ ] **Step 2: Sign** the Spanish catalog: `check-links`, then `sign`.
- [ ] **Step 3: Phone walkthrough** (after A0 is done with it; one app, `app.granger`; check `lastUpdateTime` first):
  1. App in Español with the knowledge language Spanish: onboarding, Pick a start (Efficient), a Spanish pack download, Readiness, Clásicos Granger, On this day, Define in a Clásicos book (English Wiktionary jumps to Spanish), archive.org and LibriVox in Spanish.
  2. App in English with knowledge languages English: everything is as before.
- [ ] **Step 4: Merge (waits for Neal).** Merge with L1 and L2, so no build reads the Spanish catalog without the language filter. Update the roadmap row.
