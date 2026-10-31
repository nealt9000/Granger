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

- [ ] **Step 1:** Run `git worktree add .claude/worktrees/l3 -b l3-spanish-knowledge main`. Then run `git submodule update --init` and copy `local.properties`.
- [ ] **Step 2:** Run `python -m pytest tools/catalog-gen tools/pack-builder/tests` and `./gradlew testDebugUnitTest`, and record the counts.

## Task 1: The generator learns languages (desk, `tools/catalog-gen`)

**Files:** `catalog_gen.py`, `test_catalog_gen.py`, `kiwix_families.json`, `curated.json` (`kiwix.languages` only).

- [ ] **Step 1: Codes.**
  - Add `bcp47(code)`: `eng` → `en`, `spa` → `es`, and `eng,spa` → `en,es` (keep the order; drop duplicates). Use a small table for the codes Kiwix uses, and pass through unknown codes.
  - Apply it in `opds_entries` (`language`), `release_entry` and `book_entries`.
  - The voices' `en-US` stays as written; voice language is L5's.
  - Tests: the mapping, a `mul` pack, and a release descriptor that already says `en`.
- [ ] **Step 2: Families.**
  - Generalise the `_en`-only Wikipedia subject rows to `^wikipedia_[a-z]{2,3}_<subject>$`: medicine, physics, chemistry, mathematics, molcell, climate-change, history, geography, computer, movies, football, basketball and comics (research §1.4 (1)).
  - Add held rows `^kiwix\.ecured_` (`"hold": "licence-nc"`) and `^marxists\.org_` (`"hold": "licence-mixed"`), each with its evidence URL from research §2.
  - Pin `wiktionary_es_all` to the `nopic` flavour, so the stale unflavoured 2025 build isn't listed. Do it as a curated row, or as a family-row `flavours` rule if one exists. Otherwise add the smallest rule that does it, with a test.
  - `ifixit_es_all` needs no change: the `^ifixit_` row already flags it as non-commercial.
- [ ] **Step 3: Kiwix languages.** Set `curated.json` `kiwix.languages` to `["eng", "spa"]`. A dry `build` against the live feed should list about 63 new Spanish entries (research §0), and the report should name ecured and marxists as held.
- [ ] **Step 4: `i18n` passthrough.**
  - `readiness_block`, `onboarding_block` and the preset code copy an `i18n` object through. They check that it's a map from a language code to string fields, and fail the build otherwise.
  - `apply_sets` copies a set's `i18n` (title, description, per-slice label) onto every flavour of each slice as `i18n.es.title` (as "<set>: <label>") and `i18n.es.setLabel`.
- [ ] **Step 5: Per-language tiers.**
  - `readiness_block` reads `languages.<code>.essential/comprehensive`, checks every token as it checks the English ones, folds essential into comprehensive, and emits `languages` on the domain.
  - It generates `efficient-<code>` and `comprehensive-<code>` presets with `language` and `i18n` titles ("Inicio eficiente" and "Biblioteca completa"; mark both for the friend review).
  - `@<collection>` expands by collection and domain as today.
  - `onboarding_block` does the same for use cases.
- [ ] **Step 6: Sizes per language.** `tier_sizes(catalog, language="en")` reads that language's lists, and the CLI prints one table per language. Tests use a small Spanish fixture with a held subject pack.
- [ ] **Step 7:** Commit "L3: the catalog generator learns languages".

## Task 2: The Spanish Wikipedia set (desk, Mac)

- [ ] **Step 1:** Run `zim_probe.py titles` (range reads; nothing is downloaded whole) over:
  - `wikipedia_es_top_mini`
  - `wikipedia_es_all_mini` (if it's over budget, take Kiwix's count and treat it as holding every subject, as WF did for English Everything)
  - each `wikipedia_es_*` subject pack's smallest flavour
- [ ] **Step 2:** Add to `pack_sets.json`:
  - the `wikipedia_es` set: rank 1 `wikipedia_es_top` ("Los 348.000 mejores artículos"), rank 2 `wikipedia_es_all` ("Todo")
  - the subjects, with their `adds` against Top
  - `i18n` for the English user who browses Spanish packs: English title "Wikipedia in Spanish", labels "Best 348,000 articles" and "Everything". The Spanish copy is the set's own.

  A one-slice set isn't a set, so `wikivoyage_es` stays a plain pack.
- [ ] **Step 3:** Add `"wikipedia_es_all": ["wikipedia_es_*"]` to `readiness.contains` (the prefix covers Top too). The app's `Containment` also reads the sets, but the generator's presets and `tier_sizes` read only `contains`, as for English. Check that `tier_sizes` drops every Spanish subject and Top from `comprehensive-es`.
- [ ] **Step 4:** Commit "L3: Spanish Wikipedia as one family".

## Task 3: Spanish tiers, presets, onboarding and copy (desk)

**Files:** `curated.json`.

- [ ] **Step 1: Curated rows** for the Spanish anchors, with Spanish titles and English `i18n` (`i18n.en`): Top (maxi and nopic), All (maxi and nopic), WikiMed en español (maxi and nopic), Wikcionario (nopic), Wikiviajes (maxi and nopic), and English Wiktionary for learners (already curated). Featured marks them under Recommended for a Spanish user.
- [ ] **Step 2: The Spanish lists** (research §1.1, with §7's calls). Each domain gets `languages.es` and `i18n.es.title` (research §1.5):

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
- [ ] **Step 3: Use cases** in Spanish: the same seven ids, with `languages.es.items` from the Spanish packs and `i18n.es` titles and blurbs. Leave out a use case with nothing Spanish to add, rather than padding it with English packs.
- [ ] **Step 4: Sizes.** Build, then print the per-language table. Expected, packs only and before L4's vectors:
  - Efficient: about 9.5 GB with pictures, 5.5 GB with Save space.
  - Comprehensive: about 48.2 GB with pictures, 24.4 GB with Save space.

  Write the real table into this plan.
- [ ] **Step 5: Neal signs off the lists and sizes.** This is the spec §5.2 sign-off, and it happens before any signing.
- [ ] **Step 6:** Commit "L3: Spanish tiers, presets and onboarding".

## Task 4: Clásicos Granger (desk, Mac)

**Files:** `tools/pack-builder/classics/manifest_es.json`, plus `manifest.py`, `pack_builder.py`, `epubpack.py`, `zimpack.py`, `chapters.py` and their tests. Research §5.5 lists every place.

- [ ] **Step 1: Builder changes** (research §5.5 table):
  - The language check reads `m.language`, so `en,es` readers pass.
  - Spanish stop words.
  - The manifest reads `collection`, `bookIdPrefix`, section `domain` and `learner`.
  - `language` comes from the manifest.
  - `book_id` uses the prefix (`gc-es-…`).
  - Nav titles: "Índice" and "(trad. …)".
  - `clean_title` handles "por …", "Índice" and "Capítulo" on real PG HTML.
  - `catalog_gen.BOOK_DOMAINS` is keyed by section id plus the manifest's `domain`, so Spanish *thought* gets its books.
  - English output must be byte-identical. Rebuild three English books and compare their sha256.
- [ ] **Step 2: The rights rule** (Neal's call 4):
  - Each work records `died` for every named author, translator and editor, with a `diedSource` (Wikipedia or a national library page).
  - `validate()` fails on any death after 1945 (`PD_DEATH_LINE`, the year Spain's life + 80 leaves free in 2026), on an unknown death year for a named person, and on a `published` (the translation's own first year for translations) after 1930.
  - Anonymous and pre-1800 works pass with `"died": null` and a note.
  - The research's §5.2 eight authors are out. So is anyone else the check finds (for example the translator Ramón D. Perés, d. 1956, and any school-edition editor who died after 1945). List them in the commit.
- [ ] **Step 3: The manifest.** Write `manifest_es.json` from research §5.3 and the appendix:
  - ten sections with their domains;
  - the twenty learner picks marked;
  - `$b` dropped from titles;
  - one copy of each duplicate;
  - the translators' years checked (research §5.2).

  Expect about 220–230 works once the rule is applied.
- [ ] **Step 4: Build** the EPUBs and the release descriptor, `releases/clasicos_granger_es_books.json` (collection `clasicos-granger`, about 65 MB). Open five EPUBs in the reader's test harness (or `epubcheck` if it's installed) and check: one learner reader, one verse, one play, one multi-volume, one translation.
- [ ] **Step 5: Upload (waits for Neal).** Create the GitHub release `clasicos-es-YYYY-MM-DD` and add a `curated.json` `books` row with `"license": "Public domain (US)"`. Then restore `@clasicos-granger` in Task 3's lists.
- [ ] **Step 6:** Commit "L3: Clásicos Granger".

## Task 5: The app reads languages (`core/catalog`)

**Files:** `Catalog.kt`, `CatalogParser.kt`, a new `Languages.kt`, and tests.

- [ ] **Step 1: `Languages`.** Add `normalise(code)` (ISO 639-2/3 → 639-1, from `Locale.getISOLanguages()` and `isO3Language`; comma lists kept in order), `codes(entryLanguage): List<String>`, and `has(entryLanguage, code)`. These are pure JVM functions.
- [ ] **Step 2: Model.**
  - `PackEntry.language` and `BookEntry.language` are normalised in the parser.
  - `Domain`, `Preset` and `UseCase` gain `i18n: Map<String, Copy>` and `languages: Map<String, Tiers>` (or `Items`).
  - `Preset` gains `language = "en"`.
  - The parser checks each language's tokens as it checks English ones, and drops a language whose lists are empty after checking.
- [ ] **Step 3: Helpers.** Add:
  - `Domain.essential(lang)`, `Domain.comprehensive(lang)` (English lists for `en`)
  - `Copy`-picking `title(appLang)`
  - `Catalog.tierPresets(lang)`
  - `Catalog.languages`: the codes that have tiers
- [ ] **Step 4: Tests.**
  - An old catalog (no `languages`, no `i18n`, `eng` codes) parses to the same English model as today.
  - A Spanish domain with an unknown token drops only that token.
  - `i18n` falls back to English.
- [ ] **Step 5:** Commit.

## Task 6: Knowledge languages (app)

- [ ] **Step 1: `KnowledgeLanguages`** in `settings/`. It's stored like the other settings, and its default is described under Data shapes. Expose it as a `Flow<List<String>>`.
- [ ] **Step 2: Settings → Language** (L2 Task 3's screen) gains "Idiomas de tu biblioteca" / "Your library's languages". It lists the languages the catalog has tiers for; at least one stays on. Wording goes through the glossary and both resource files.
- [ ] **Step 3: Onboarding.** When the phone's `LocaleList` has more than one language the catalog supports, the first step offers the second one ("¿También en inglés?" / "Also Spanish?"). Otherwise nothing new appears (Neal's call 4).
- [ ] **Step 4: Tests.**
  - The default follows the app language.
  - An unsupported app language falls back to `en`.
  - The prompt shows only with two supported phone languages.
- [ ] **Step 5:** Commit.

## Task 7: Readiness, Pick a start and Get more by language (app)

- [ ] **Step 1: Readiness.**
  - `ReadinessModel` measures each domain over the knowledge languages: best coverage, with a missing essential taken from the first language.
  - An empty domain in every knowledge language says "No Spanish packs for this area yet" (a resource string, with the language name as an argument).
  - Domain titles come from `i18n`.
  - Signals are unchanged.
- [ ] **Step 2: Pick a start.**
  - `OnboardingModel.choices` uses `catalog.tierPresets(first)`.
  - Save space swaps flavours as today.
  - A second language adds its `reference` essential as its own Review group.
  - Use cases read the first language's items, plus the second language's when it's on.
  - `voiceLine` is unchanged (L5).
- [ ] **Step 3: Get more.**
  - The Kiwix list's language facet defaults to the knowledge languages; multilingual packs count for each of their languages.
  - Recommended shows the featured packs in the knowledge languages.
  - Set rows take their title and description from the catalog's set `i18n`, replacing `KiwixList`'s hardcoded `wikipedia_en` copy.
  - `SourceViewModel.OWN_ROW_SUBJECTS` holds the medicine subject of every language.
  - An installed pack in another language always shows.
- [ ] **Step 4: Browse.** The empty-Browse suggestion takes the encyclopedia from the first language's Efficient preset.
- [ ] **Step 5: Tests.**
  - A Spanish-only user sees Spanish tiers and starts.
  - An English user sees exactly today's lists. Pin this with the current `ReadinessModelTest` and `OnboardingModelTest` expectations unchanged.
  - A bilingual user gets best-of coverage.
- [ ] **Step 6:** Commit.

## Task 8: Clásicos Granger in the app

- [ ] **Step 1: Generalise the Classics constants.**
  - `OnboardingModel.CLASSICS`, `SourceViewModel.CLASSICS_PRESET` and `HomeViewModel`'s `c:granger-classics` feed become a set of Granger collections: `granger-classics` and `clasicos-granger`.
  - Home's default feed uses the first knowledge language's collection.
- [ ] **Step 2: Filing.**
  - A catalog book files by its catalog `collection`, so `gc-es-*` books land in "Clásicos Granger", not "Granger Classics".
  - Check `Filing.kt` (`BookOrigin.GRANGER`), the default-key list in `LibraryDatabase` and `CollectionScreen`.
  - Add a test that files one book of each collection.
- [ ] **Step 3: Download order.** Setup downloads the voice first, then either Classics collection (Plan DL Decision 5).
- [ ] **Step 4:** Commit.

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
