# L3 desk research: Spanish knowledge catalog

2026-10-30 · desk research for plan L3 (spec `docs/superpowers/specs/2026-10-30-languages-design.md` §3, §5, §10). No repo files were changed. Sources: the live Kiwix OPDS catalog, fetched 2026-10-30 20:29 UTC (`https://opds.library.kiwix.org/catalog/v2/entries?lang=spa&count=500`, 430 entries; the old `library.kiwix.org` host now 301-redirects there), plus Gutendex, en/es Wiktionary APIs, es.wikipedia, and the browse.library.kiwix.org copies of the ZIMs. Raw data is under `scratchpad/l3/data/` (`spa.json`, `spa_table.txt`, `all.json`, `gutendex_es.json`, `gutendex_es.tsv`, `curated_tables.md`).

Sizes are the OPDS `length` in decimal GB (1 GB = 10⁹ B), the same unit as the H4 numbers. Dates are the OPDS `updated` date, which `catalog_gen.py` uses as `snapshotDate`.

---

## 0. The Spanish slice of Kiwix at a glance

- `lang=spa` matches 430 entries. **356 are TED** (held back as `video` and non-commercial). That leaves **74**, matching the spec's "about 74".
- Five of the 74 are multilingual (`*_mul_*`, language `eng,spa,…`) and **are already in today's English catalog**: `es.stackoverflow.com_mul_all`, `spanish.stackexchange.com_mul_all`, `solar.lowtechmagazine.com_mul_all`, `gutenberg_mul_all` and `phet_mul_all`. Adding `spa` to `curated.json` lists about 64 new entries (74 − 5 already listed − 5 with no family row; see §1.4). It's 63 if Khan Academy's `video` hold stays, and 62 if `ifixit_es_all` is held too.
- Kiwix has **no Spanish maps** (`maps_*` are all `eng`), **no Spanish Top 1M**, **no `wikipedia_es_100`**, **no Spanish mdwiki**, and **no Spanish zimgit, LibreTexts, Appropedia, CDC or Stack Exchange topic sites** beyond the two above.
- Every Wikimedia `es` ZIM has a full-text index. Without one: `gutenberg_es_all`, `ifixit_es_all`, `khanacademy_es_all` and `phet_es_all`.

### Full Spanish list (excluding TED), newest per name and flavour

| Name | Flavour | GB | Date | FT index | Articles | Family row today → licence · domain |
|---|---|---:|---|---|---:|---|
| wikipedia_es_all | maxi | 36.517 | 2026-10-09 | yes | 4,232,639 | wikipedia catch-all → CC BY-SA 4.0 · reference |
| wikipedia_es_all | nopic | 13.298 | 2026-10-09 | yes | 4,232,654 | same |
| wikipedia_es_all | mini | 4.114 | 2026-10-09 | yes | 4,205,442 | same |
| wikipedia_es_top | maxi | 4.066 | 2026-10-10 | yes | 348,403 | same |
| wikipedia_es_top | nopic | 1.209 | 2026-10-10 | yes | 348,403 | same |
| wikipedia_es_top | mini | 0.217 | 2026-10-10 | yes | 340,128 | same |
| wikipedia_es_medicine | maxi / nopic | 0.469 / 0.171 | 2026-10-15 | yes | 56,839 | catch-all → **reference** (should be medicine) |
| wikipedia_es_physics | maxi / nopic / mini | 0.346 / 0.093 / 0.024 | 2026-10-15 | yes | 22,711 | catch-all → reference (should be science) |
| wikipedia_es_chemistry | maxi / nopic / mini | 0.135 / 0.039 / 0.014 | 2026-10-15 | yes | 11,055 | → reference (science) |
| wikipedia_es_mathematics | maxi / nopic / mini | 0.335 / 0.105 / 0.022 | 2026-10-14 | yes | 22,096 | → reference (science) |
| wikipedia_es_molcell | maxi / nopic | 0.172 / 0.055 | 2026-10-16 | yes | 14,239 | → reference (science) |
| wikipedia_es_climate-change | maxi / nopic / mini | 0.062 / 0.028 / 0.008 | 2026-10-16 | yes | 3,760 | → reference (science) |
| wikipedia_es_computer | maxi / nopic | 0.226 / 0.103 | 2026-10-15 | yes | 34,121 | → reference (technology) |
| wikipedia_es_geography | maxi / nopic | 0.316 / 0.063 | 2026-10-15 | yes | 21,620 | → reference (maps) |
| wikipedia_es_history | maxi / nopic | 0.688 / 0.197 | 2026-10-14 | yes | 55,869 | → reference (thought) |
| wikipedia_es_movies | maxi / nopic | 0.952 / 0.546 | 2026-10-13 | yes | 130,293 | → reference (literature, as en) |
| wikipedia_es_football | maxi / nopic | 1.441 / 0.604 | 2026-10-12 | yes | 177,565 | → reference (null, as en) |
| wikipedia_es_basketball | maxi / nopic | 0.198 / 0.081 | 2026-09-17 | yes | 24,863 | → reference (null, as en) |
| wikipedia_es_comics | maxi / nopic | 0.142 / 0.093 | 2026-10-15 | yes | 18,436 | → reference (null, as en) |
| wikipedia_es_venezuela | maxi / nopic | 0.199 / 0.069 | 2026-09-16 | yes | 27,379 | → reference (fine, or maps) |
| wiktionary_es_all | nopic | 0.656 | 2026-09-20 | yes | 953,170 | wiktionary → CC BY-SA 4.0 · reference |
| wiktionary_es_all | (none) | 0.997 | **2025-03-02** | yes | 936,806 | same (stale unflavoured build; listed as a separate id) |
| wikivoyage_es_all | maxi / nopic | 0.139 / 0.043 | 2026-09-17 | yes | 4,858 | wikivoyage → CC BY-SA 4.0 · maps |
| wikisource_es_all | maxi / nopic | 0.512 / 0.400 | 2026-10-12 | yes | 76,398 | wikisource → CC BY-SA 4.0 · literature |
| wikibooks_es_all | maxi / nopic | 0.281 / 0.093 | 2026-10-25 | yes | 19,438 | wikibooks → CC BY-SA 4.0 · reference |
| wikiquote_es_all | maxi / nopic | 0.069 / 0.031 | 2026-10-16 | yes | 16,695 | wikiquote → CC BY-SA 4.0 · literature |
| wikiversity_es_all | maxi / nopic | 0.041 / 0.016 | 2026-10-16 | yes | 4,953 | wikiversity → CC BY-SA 4.0 · reference |
| vikidia_es_all | maxi / nopic | 0.134 / 0.016 | 2026-09-08 | yes | 10,523 | vikidia → CC BY-SA 3.0 · reference |
| medlineplus.gov_es_all | — | 1.767 | **2024-12-26** | yes | 11,616 | medlineplus.gov → "Not stated by the source" · medicine |
| ready.gov_es_all | — | 2.066 | 2026-10-14 | yes | 2,504 | ready.gov → Public domain · food |
| scoutwiki.org_es_all | maxi | 0.020 | 2026-10-05 | yes | 2,053 | scoutwiki.org → GFDL · food |
| es.stackoverflow.com_mul_all | — | 1.436 | 2026-10-19 | yes | 284,665 | stack_exchange (kiwixCategory row) → CC BY-SA 4.0 · technology. **Already listed** |
| spanish.stackexchange.com_mul_all | — | 0.072 | 2026-10-20 | yes | 16,415 | stack_exchange languages sub-row → reference. **Already listed** |
| solar.lowtechmagazine.com_mul_all | — | 0.701 | 2025-01-02 | yes | 1,468 | "Not stated by the source" · making. **Already listed** |
| freecodecamp_es_all (+4 sub-packs) | — | 0.012 each | 2026-10-13 | yes | 996 | freecodecamp → "Not stated" · technology |
| gutenberg_es_all | — | 1.860 | 2026-01-05 | **no** | 1,285 | gutenberg → PD (US) · literature |
| gutenberg_mul_all | — | 253.06 | 2025-11-27 | no | 100,909 | **Already listed** |
| phet_es_all | — | 0.109 | 2026-08-25 | no | 120 | phet → CC BY / CC BY-NC (**nonCommercial**) · science |
| phet_mul_all | — | 0.148 | 2026-08-25 | no | 8,405 | **Already listed** (NC) |
| ifixit_es_all | — | 3.681 | 2026-03-06 | **no** | 105,603 | ifixit → CC BY-NC-SA 3.0 (**nonCommercial**, no hold) · making |
| khanacademy_es_all | — | 161.11 | 2023-03-01 | no | 27,221 | khanacademy → NC, **hold `video`** |
| kiwix.ecured_es_all | — | 3.322 | **2019-05-21** | yes | 200,968 | **no row** (see §2) |
| marxists.org_es_all | — | 13.306 | 2026-02-17 | yes | 9,835 | **no row** (see §2) |
| inciclopedia_es_all | full | 1.206 | 2026-10-24 | yes | 27,229 | **no row**: parody wiki, licence not checked; leave out |
| wikidex_es_all | maxi | 2.030 | 2026-10-10 | yes | 63,692 | **no row**: Pokémon wiki, licence not checked; leave out |
| partituras-gratis.es_es_all | — | 3.782 | 2026-02-22 | yes | 7,658 | **no row**: Celtic sheet music (OPDS title says "Breizh partitions"), licence not checked; leave out |

---

## 1. Spanish tiers per readiness domain

The tiers follow the English shape after H4 and U3a: pictures by default (maxi where it exists), one pack per ZIM, Wiktionary in Comprehensive, Stack Overflow and Gutenberg bundles left out of the tiers. Sizes are pack bytes only. **No Spanish pack has search vectors yet** (L4), while the English tier sizes include vectors, so the Spanish totals will grow when L4 adds them. `@clasicos-granger` is estimated at about 0.065 GB for 244 books (the English average is 0.27 MB per EPUB); the split is 80 % literature and 20 % thought.

### 1.1 Proposed lists

| Domain | `essential` (es) | `comprehensive` adds (es) | Notes |
|---|---|---|---|
| medicine | `wikipedia_es_medicine_maxi` (0.469, 2026-10-15, FT, CC BY-SA 4.0) | `medlineplus.gov_es_all` (1.767, **2024-12-26**, FT, "Not stated by the source") | Spanish has no mdwiki, zimgit-medicine or CDC. The MedlinePlus build is 22 months old. |
| food | `ready.gov_es_all` (2.066, 2026-10-14, FT, Public domain) | `scoutwiki.org_es_all_maxi` (0.020, 2026-10-05, FT, GFDL) | **Thin.** Spanish has no cooking, water or gardening packs. Ready.gov is the only real preparedness pack and it's large; the alternative is an empty essential tier with Ready.gov in Comprehensive (as in English). |
| making | *(none)* | `solar.lowtechmagazine.com_mul_all` (0.701, 2025-01-02, FT, "Not stated") | **Nothing Spanish-only.** `ifixit_es_all` (3.681, no FT, CC BY-NC-SA 3.0) would fit only if the English rule applies: `ifixit_en_all` is NC and in English essentials, yet spec §5.2 excludes `ifixit_es`. See §1.4. |
| science | `wikipedia_es_physics_maxi` (0.346), `wikipedia_es_chemistry_maxi` (0.135), `wikipedia_es_mathematics_maxi` (0.335); all 2026-10-14/15, FT, CC BY-SA 4.0 | `wikipedia_es_molcell_maxi` (0.172), `wikipedia_es_climate-change_maxi` (0.062) | Spanish has no astronomy pack, LibreTexts, PlanetMath or SE sites. `phet_es_all` is left out: it's NC, has no FT index, and English doesn't tier PhET. |
| technology | `wikipedia_es_computer_maxi` (0.226, 2026-10-15, FT) | `es.stackoverflow.com_mul_all` (1.436, 2026-10-19, FT, CC BY-SA 4.0) | English dropped Stack Overflow from the tiers for size (114.9 GB). The Spanish one is 1.4 GB, so it's proposed here, **flagged for Neal**. `freecodecamp_es_*` is tiny, with no stated licence; left out. |
| maps | `family:maps`, `wikivoyage_es_all_maxi` (0.139, 2026-09-17, FT) | `wikipedia_es_geography_maxi` (0.316, 2026-10-15, FT) | Maps stay the English-named `maps_en_*` (OSM labels are local names anyway). No Spanish OSM wiki or travel SE. |
| reference | `wikipedia_es_top_maxi` (4.066, 2026-10-10, FT) | Wikipedia (see 1.2), `wiktionary_es_all_nopic` (0.656, 2026-09-20), `wikibooks_es_all_maxi` (0.281), `wikiversity_es_all_maxi` (0.041), `vikidia_es_all_maxi` (0.134), `spanish.stackexchange.com_mul_all` (0.072) | Vikidia is the closest Spanish match for Citizendium/ELL's "plain reference" slot. |
| literature | `@clasicos-granger` | `wikisource_es_all_maxi` (0.512, 2026-10-12), `wikiquote_es_all_maxi` (0.069, 2026-10-16) | `gutenberg_es_all` (1.86 GB, no FT) stays out of the tiers, as English LCC-PR does. No literature or writing SE. |
| thought | `@clasicos-granger` | `wikipedia_es_history_maxi` (0.688, 2026-10-14) | **Thin.** There's no philosophy encyclopedia and no religion or history SE. `marxists.org_es_all` is excluded (§2). |

### 1.2 The Comprehensive Wikipedia: a call for Neal

There's no Spanish Top 1M. The step from Top (348k articles) is straight to `all` (4.23M). And `wikipedia_es_all` really **does** hold every `wikipedia_es_*` subject pack, unlike English Top 1M, which H4 measured as holding only 18–39 % of a subject pack. So `readiness.contains` should get `"wikipedia_es_all": ["wikipedia_es_*"]`. The `contains` key is a ZIM name, not a flavour, so in a tier that lists `wikipedia_es_all` every subject pack and Top count as held. They're left out of the Comprehensive preset and count 0 bytes.

- **Option A, `wikipedia_es_all_nopic` (13.3 GB).** Mirrors English's choice of Top 1M nopic. Side effect: Comprehensive then has **no Wikipedia pictures at all**, because the maxi subject packs and Top maxi are dropped as held. That cuts against U3a's pictures by default.
- **Option B, `wikipedia_es_all_maxi` (36.5 GB). Recommended.** Pictures throughout, and still under half the English Comprehensive (90.3 GB). Save space swaps it to nopic (`OnboardingModel` swaps any pack for its `_nopic` sibling of the same ZIM).

### 1.3 Totals

The preset arithmetic copies `tier_sizes()`: one pack per ZIM, held ZIMs dropped, `family:maps` excluded (the region's map is added in onboarding). Save space applies the `_nopic` swap where a sibling exists; Ready.gov, MedlinePlus, Stack Overflow, Scoutwiki and Low-tech have no sibling.

| Domain | Efficient (pictures) | Efficient, Save space | Comprehensive B (all maxi) | Comprehensive A (all nopic) | Comprehensive, Save space (either) |
|---|---:|---:|---:|---:|---:|
| medicine | 0.47 | 0.17 | 2.24 | 2.24 | 1.94 |
| food | 2.07 | 2.07 | 2.09 | 2.09 | 2.09 |
| making | 0.00 | 0.00 | 0.70 | 0.70 | 0.70 |
| science | 0.82 | 0.24 | 1.05 | 1.05 | 0.32 |
| technology | 0.23 | 0.10 | 1.66 | 1.66 | 1.54 |
| maps (excl. region map) | 0.14 | 0.04 | 0.46 | 0.46 | 0.11 |
| reference | 4.07 | 1.21 | 37.70 | 14.48 | 14.15 |
| literature | 0.05 | 0.05 | 0.63 | 0.63 | 0.48 |
| thought | 0.01 | 0.01 | 0.70 | 0.70 | 0.21 |
| **Whole start** | **7.84 GB** | **3.88 GB** | **44.47 GB** | **21.25 GB** | **20.67 GB** |

Per-domain Comprehensive figures count the subject packs, because a domain's own list doesn't name the full Wikipedia. The whole-start Comprehensive drops them as held by `wikipedia_es_all`. For comparison, English after H4 is Efficient 11.1 GB and Comprehensive 90.3 GB (with vectors).

- If food's essential is left empty (Ready.gov moved to Comprehensive only), Efficient is **5.77 GB** with pictures and **1.81 GB** with Save space.
- If `ifixit_es_all` joins making's essential, add 3.68 GB to every column.

### 1.4 `kiwix_families.json`: which rows match as-is, and which are new

**Match as-is, correct licence and domain:** wikipedia (catch-all, for `_all` and `_top`), wiktionary, wikivoyage, wikisource, wikibooks, wikiquote, wikiversity, vikidia, medlineplus.gov, ready.gov, scoutwiki.org, gutenberg, freecodecamp, the stack_exchange category row (es.stackoverflow → technology), the stack_exchange languages sub-row (spanish.SE → reference), phet (NC) and khanacademy (hold `video`).

**Rows to change or add:**

1. **The Wikipedia subject sub-rows are `_en`-only**, so every Spanish subject pack falls through to the catch-all and gets domain `reference`. Generalise them, for example `^wikipedia_(en|es)_medicine$`, or better `^wikipedia_[a-z]{2,3}_medicine$` so a third language is pure data. The same applies to the physics/chemistry/mathematics/molcell/climate-change (science), history (thought), geography (maps), computer (technology), movies (literature) and football/basketball/comics (null) rows. `knots` has no Spanish pack. `wikipedia_es_venezuela` can stay `reference`.
2. **`ifixit_es_all` is listed automatically** when `spa` is added: the `^ifixit_` row is NC but has no hold. To honour spec §5.2's exclusion, add a sub-row `^ifixit_es_` with a hold (for example `"hold": "licence-nc-es"`). The other option is for Neal to drop the exclusion and treat it like `ifixit_en_all`.
3. `phet_es_all` is also listed automatically (NC flag, like `phet_mul_all`). That's acceptable, since it's outside the tiers.
4. **New held rows, so the Kiwix report names them** instead of counting them under "no row": `^kiwix\.ecured_` (licence non-commercial; see §2) and `^marxists\.org_` (licence mixed; see §2). The name prefix `kiwix.ecured` is unusual but passes `SAFE_NAME`.
5. Optional: `inciclopedia`, `wikidex` and `partituras-gratis.es` have no row and are silently left out. That's fine for now; their licences were not checked.
6. `catalog_gen.py` must map Kiwix `spa`/`eng` to `es`/`en` (spec §5.1). Multilingual packs carry `eng,spa`, so the app needs a rule: "has `es`" counts as Spanish for tiers and filters.
7. The stale unflavoured `wiktionary_es_all` (2025-03, 0.997 GB) is listed beside the nopic one. Consider a hold or a curated pin to nopic only, so Get more doesn't show two Wikcionarios.

### 1.5 Copy for the domain titles

The domain ids are unchanged; the `i18n.es` titles follow §4.2's glossary tone (for the native-speaker review):

| Domain | Title (es) |
|---|---|
| medicine | Medicina y primeros auxilios |
| food | Comida, agua y supervivencia |
| making | Fabricación, reparación e ingeniería |
| science | Ciencia y matemáticas |
| technology | Tecnología e informática |
| maps | Mapas, lugares y viajes |
| reference | Consulta general |
| literature | Literatura y artes |
| thought | Religión, filosofía e historia |

Empty lists show "No Spanish packs for this area yet" (spec §5.1). That applies to **making** essentials if iFixit stays out.

---

## 2. Licence checks

| Pack | Call | Evidence |
|---|---|---|
| **kiwix.ecured_es_all** (3.32 GB, 2019-05) | **Exclude** (add a held row) | EcuRed's own copyright page (`ecured.cu/EcuRed:Derechos_de_autor`, fetched 2026-10-30) names no standard licence. Its own policy lets articles be "copiado, reproducido y distribuido libremente **sin fines comerciales**", with attribution and the same terms on derivatives: effectively BY-NC-SA. It's also a 2019 snapshot from a state-run Cuban project. The spec's "stays out until checked" becomes "out: non-commercial". |
| **marxists.org_es_all** (13.31 GB, 2026-02) | **Exclude** (add a held row) | MIA has no blanket licence. The Spanish admin page says most material is public domain "o puede ser utilizado libremente" unless stated otherwise, and "el material creado por el MIA está protegido por la Licencia Creative Commons" (CC BY-SA 2.0 per the MIA legal index's link `admin/legal/cc/by-sa.htm`). Third-party translations are hosted with permission, and rights are per work: the 2014 Lawrence & Wishart takedown of MECW texts shows it. Nothing grants a licence for the whole archive. It's also 13 GB, larger than the whole Spanish Efficient start. |
| **medlineplus.gov_es_all** (1.77 GB, 2024-12) | **Include on the same terms as English** (existing row, "Not stated by the source"), Comprehensive only. **Flag the images.** | MedlinePlus "Using content" (`medlineplus.gov/about/using/usingcontent/`; it says the rules apply "in both English and Spanish"): health topic pages and NLM-watermarked illustrations are public domain. **A.D.A.M. encyclopedia articles and images, ASHP drug monographs and "images… except those noted" may be copyrighted. Unattributed images are licensed stock, and "you may not reproduce or redistribute these images."** A search of the ZIM on browse.library.kiwix.org confirms it includes `spanish/ency/article/*` (A.D.A.M.) and `spanish/druginfo/meds/*` (ASHP). The ibuprofeno monograph snippet in the ZIM reads "…para su **uso comercial**, deberá ser autorizada por ASHP. Traducido del inglés por HolaDoctor." This is the same mixed status Neal accepted for `medlineplus.gov_en_all` on 2026-10-20 (it's in English Comprehensive). Granger links to Kiwix's file and doesn't rehost it, but the risk is no lower in Spanish. Neal's call whether to keep both, or neither. |
| **ready.gov_es_all** (2.07 GB, 2026-10) | **Include** (existing row: Public domain) | FEMA website information (row source `fema.gov/about/website-information`): "Most material on FEMA.gov is free of copyright and may be copied and distributed without permission"; some licensed photos and graphics are restricted. There's no separate Spanish policy page (`ready.gov/es/aviso-legal` returns 404). Same status as `ready.gov_en_all`. |
| **vikidia_es_all** (0.13 / 0.02 GB, 2026-09) | **Include** (existing row: CC BY-SA 3.0) | `es.vikidia.org` returns 403 to fetches, so the Spanish licence page couldn't be read directly. The family row (en.vikidia footer: CC BY-SA 3.0; fr also GFDL) applies. Vikidia's French licence category bans NC and ND variants. Spanish Vikidia articles in the ZIM carry "Esta página utiliza contenido de Wikipedia en español … con licencia Creative Commons". One Vikidia page cites **CC BY-SA 4.0** for Spanish-origin material, so the version is **uncertain (3.0 or 4.0)**; both are ShareAlike and commercial-OK. |
| **es.stackoverflow.com_mul_all** (1.44 GB, 2026-10) | **Include** (existing row: CC BY-SA 4.0); **already in the catalog** today | It's a Stack Exchange network site, so the network licensing (`stackoverflow.com/help/licensing`, the stack_exchange row's source) applies: CC BY-SA 4.0 for current posts (older posts CC BY-SA 2.5/3.0). `es.stackoverflow.com` blocks automated fetches, so the help page wasn't read directly. It's already listed because its language is `eng,spa`. |

Also from the spec, unchanged: `ifixit_es_all` is NC; `khanacademy_es_all` is NC and held (`video`, 161 GB); TED is NC and held. See §1.4 (2) for the iFixit inconsistency with English.

---

## 3. Define for learners: Wiktionary coverage

### 3.1 English Wiktionary (`wiktionary_en_all_nopic`, the pack Granger already lists)

| Measure (en.wiktionary API, 2026-10-30) | Count |
|---|---:|
| Category:Spanish lemmas | **118,924** |
| Category:Spanish non-lemma forms (conjugations, plurals, feminines) | **666,286** |
| Spanish nouns / verbs / adjectives (lemmas) | 73,915 / 11,098 / 25,582 |
| Spanish terms with IPA | 151,721 |
| Category:English lemmas (for scale) | 885,168 |

- **The ZIM includes the non-English sections.** `wiktionary_en_all_nopic_2026-08` is 9.16 GB with **9,129,949 articles**, against 9,749,323 content pages on en.wiktionary, so it's the whole site.
- Checked in the ZIM itself:
  - `perro` has h2 sections Chavacano, Ladino, Old Spanish, Portuguese and **Spanish** (`id="Spanish"`), with the gloss "dog", IPA, gender and plural.
  - `madrugada` has Spanish only ("dawn; early hours of the morning…").
  - `tenía` is a form-of entry: "first/third-person singular imperfect indicative of *tener*". Inflected words a reader taps resolve to a page that links the lemma.
  - `hablar` and `desenvolvimiento` are present.
- **Coverage estimate:** about 119k Spanish lemmas plus 666k forms, around **785k Spanish headwords glossed in English**. That covers the vocabulary of 19th-century Spanish prose well; archaic Golden Age spellings (Lazarillo, Quijote) will miss more.
- **Two gaps in the current code** (`app/…/ebook/Dictionary.kt`):
  1. `Dictionary.choose()` takes the **newest `wiktionary_*` of any language**. With both installed, `wiktionary_es_all_nopic` (2026-09) beats `wiktionary_en_all_nopic` (2026-08). Spec §10.3 wants the reader's own Wiktionary first, so the choice must be by language.
  2. The page opens at the top. On `perro` the Spanish section is the fifth h2, so Define should jump to `#Spanish`, the anchor named after the book's language: "Spanish" in en.wiktionary, "Español" in es.wiktionary.
- The English Wiktionary is 9.16 GB, which is heavy for a Spanish learner. There's no smaller English-glossed Spanish dictionary on Kiwix (`wiktionary_en-simple_all` is 0.03 GB, English only).

### 3.2 Spanish Wiktionary (`wiktionary_es_all_nopic`, the fallback)

| Measure (es.wiktionary API) | Count |
|---|---:|
| Content pages | 954,762 |
| Categoría:Español | 840,888 |
| ES:Sustantivos / ES:Verbos | 73,672 / 16,783 |
| Categoría:Inglés (for scale) | 18,714 |

- **Structure,** checked in the 2026-09 ZIM:
  - h2 `id="Español"` (other languages as their own h2s; `Referencias_y_notas` last);
  - h3 `Etimología_N`;
  - h4 part of speech, for example `Sustantivo_masculino`, `Sustantivo_masculino_y_femenino`, `Adjetivo`, `Forma_verbal`;
  - then `Locuciones`, `Información_adicional`, `Véase_también` and `Traducciones` (h4).
  - Pronunciation, syllables, stress and rhyme are in a table before the definitions.
- Definitions are monolingual Spanish. `tenía` → "Primera persona del singular (yo) del pretérito imperfecto de indicativo de tener", with the lemma linked, so inflected forms resolve here too.
- Good as a fallback for advanced learners and native speakers. It's 0.66 GB.

---

## 4. On this day on Spanish Wikipedia

| Item | English (today) | Spanish (verified) |
|---|---|---|
| Day-page title | "October 22" | **"22 de octubre"**: day number, "de", month in **lower case**, no leading zero; 1 January is "1 de enero" (not "1.º") |
| ZIM path | `October_22` | `22_de_octubre` (present in `wikipedia_es_all_maxi_2026-10`, `wikipedia_es_all_nopic_2026-10` **and `wikipedia_es_top_maxi_2026-10`**) |
| Section h2 ids | Events / Births / Deaths | **`Acontecimientos` / `Nacimientos` / `Fallecimientos`**, then `Celebraciones`, `Véase_también`, `Referencias`, `Enlaces_externos` |
| Line format | "1871 – …" | `<li><a>1383</a>: en Portugal…`: year link, then a **colon**. BCE is "4004 a. C."; some lines have no year ("El 22 de octubre de algún año del siglo XXII a. C., …") |

- Verified on:
  - the live page via the es.wikipedia API (`action=parse&prop=sections`) for 22 de octubre, 1 de enero and 29 de febrero, all three with the same h1-level sections;
  - Parsoid HTML (`<h2 id="Acontecimientos">`);
  - the ZIM HTML on browse.library.kiwix.org: `<section … aria-labelledby="Nacimientos"><div class="mw-heading mw-heading2"><h2 id="Nacimientos">`.
- The lists are flat: on 22 de octubre, 0 nested lists, with 100, 213 and 58 items.
- `DayPage.parse()` already matches h2 by id or text, so a per-language section map (`es` → Acontecimientos/Nacimientos/Fallecimientos) and a title function using the Spanish month names in lower case are enough.
- Watch for any code that splits the year on " – ": Spanish uses ": ".

---

## 5. Clásicos Granger

### 5.1 What Gutenberg has

- Gutendex `languages=es`: **922 books**, the same as the spec. Of these, 914 are text and 8 are sound: the 3 LibriVox-style Esopo volumes and 5 cylinder recordings. Exclude the sound items.
- In the PG catalogue CSV (2026-10-30), 894 are Spanish only, and 28 are mixed-language, including 19 `en; es` school editions and readers. The current manifest check (`"en" not in rdf.languages`) must become "`es` in languages".
- **Non-PD (Gutendex `copyright: true`), 13 books.** These would fail `manifest.py`'s `Public domain in the USA` rights check, so they're excluded:
  - **#5201 Don Juan Tenorio** (Zorrilla; the Kline and Mayberry bilingual translation is copyrighted);
  - **#1619 La Celestina** (a copyrighted edition; **there's no PD Celestina on PG**);
  - #6528 Sagradas Escrituras (Stendal, a modern translation);
  - #30175 Fernández Bolívar;
  - 9 books by Marie Lebert on ebooks (#27035, 29803, 42023, 34091, 33461, 36986, 30716, 27048, 27034).
- **Incomplete multi-volume sets on PG**, excluded from the proposal:
  - *El libro de las mil noches y una noche* (Blasco Ibáñez's translation of Mardrus): tomes 1, 2, 3, 6, 7 and 8 only; 4 and 5 are missing.
  - *Nuestra Señora de París*: tomo 1 of 2 only.
- **Duplicates:** two *Platero y yo* (#9980 and #39209), two *Shanti Andía* (#12848 and #47057), two *Mala hierba* (#43017 and #43033), *Tradiciones peruanas* plus *Las mejores tradiciones peruanas*, *Lo prohibido* (complete #63412 plus a 2-tome copy), *La familia de León Roch* (combined and split), and *El Doctor Centeno* (complete and split). The proposal takes one of each.
- **Gaps:** no PD *La Celestina*, no standalone Bécquer *Rimas* (it's inside #53552 *Obras escogidas* and the bilingual #10814), no standalone *La vida es sueño* (it's inside Calderón's *Teatro selecto*), no Isaacs *María*, and no complete Reina-Valera Bible (New Testament only: 1858, 1862, 1865 and **1909**).
- **Odd titles:** Gutendex titles carry MARC `$b` (for example "Corazón : $b (diario de un niño)"). Manifest titles should drop the `$b` part. The title check is a subset check after accent folding, so the shorter title passes.

### 5.2 Rights flags beyond US public domain

Granger's rule is US public domain (`PD_LINE = 1930`, the same as English). Several authors in the set died after about 1955, so their works are **still in copyright in Spain (life + 80 for authors who died before 1987) or Argentina (life + 70)**, though PD in the US. This is the same policy as English, but more visible to a Spanish audience. Flag for Neal:

| Author | Died | Works in the proposed set | Protected at home until about |
|---|---|---|---|
| Juan Ramón Jiménez | 1958 | *Platero y yo* | 2039 (ES) |
| Pío Baroja | 1956 | 6 novels (plus the anthology *La voz de la conseja*) | 2037 (ES) |
| José Ortega y Gasset | 1955 | *Meditaciones del Quijote* | 2036 (ES) |
| Jacinto Benavente | 1954 | *Tres comedias*, *La Malquerida* | 2035 (ES) |
| Azorín | 1967 | *Antonio Azorín* | 2048 (ES) |
| Oliverio Girondo | 1967 | *20 poemas para ser leídos en el tranvía* | 2038 (AR) |
| Enrique Larreta | 1961 | *La gloria de don Ramiro* | 2032 (AR) |
| G. Martínez Sierra (translator) | 1947 | *Cuando la tierra era niña* | 2028 (ES) |

Translations also need their `published` year to be the **translation's** first appearance, and it must be 1930 or earlier. Years to verify in the plan:
- #61851 *Crimen y castigo* (Pedraza y Páez);
- #25807 Poe *Poemas* (Lasplaces et al.);
- #55215 Hawthorne (Martínez Sierra);
- #51019 Borrow (Azaña, about 1921).

**#77334 Kafka *El proceso* is left out:** PG marks it PD, but no translator is named, and the first Spanish translation is believed to be post-1930. That's unverified.

### 5.3 Proposed first set: 244 works in 10 sections

The full tables, with PG numbers, translators, download counts and flags, are below. The sections and their domains:

| Section id | Title (es) | Works | Domain |
|---|---|---:|---|
| lecturas | Lecturas para aprender | 20 | literature |
| infantil | Fábulas y lecturas infantiles | 12 | literature |
| cuentos | Cuentos y novelas cortas | 30 | literature |
| poesia | Poesía | 19 | literature |
| teatro | Teatro | 24 | literature |
| novelas | Novelas | 56 | literature |
| universales | Clásicos universales en español (translations) | 42 | literature |
| ensayo | Ensayo y pensamiento | 18 | thought |
| historia | Historia y crónicas | 21 | thought |
| escritura | Escritura y religión | 2 | thought |

- **Translations vs original Spanish:** **58 translations** (42 in `universales` plus 16 elsewhere: children's, stories, history, essays) (marked `transl.`). The other 186 are original Spanish works, from the *Libro de Buen Amor* and *Lazarillo* through Galdós, Clarín, Pardo Bazán, Valera, Blasco Ibáñez, Unamuno, Darío, Martí, Rizal, Quiroga, Lugones and Lorca.
- **Multi-volume sets in the proposal** (each volume is its own book, as in English):
  - Calderón *Teatro selecto* (4)
  - Las Casas *Historia de las Indias* (5)
  - Bernal Díaz *Verdadera historia* (3)
  - Acosta (2)
  - Heródoto (2)
  - Tucídides (2)
  - Borrow *La Biblia en España* (3)
  - Cajal *Recuerdos* (2)
  - Gracián *El criticón* (2)
  - Ovidio *Metamorfóseos* (4)
  - *Orlando furioso* (2)
  - Eurípides (3)
  - Aristófanes (3)
  - *Los miserables* (2)
  - *Gil Blas* (3)
  - *Martín Fierro* (ida and vuelta)
- **Left out on purpose:**
  - archival documents (*Colección de documentos inéditos*);
  - local histories and catalogues;
  - Philippine legal and political pamphlets;
  - cookbooks;
  - dictionaries (#20738 *Diccionario Inglés-Español-Tagalog*, which is popular but not a reading book);
  - the incomplete sets;
  - the 9-tome *La araña negra*, the 4-tome *El doncel de don Enrique*, Benavente's 5-part *De sobremesa* and Darío's 22-volume *Obras completas* beyond 3 key poetry volumes (to keep the size down);
  - the Mil noches;
  - Lozana andaluza, the Quijote apócrifo and Zayas's *Novelas ejemplares* (kept: #64465 is in cuentos);
  - commercial grammars (#15127 Pitman's).
- Room to grow toward 300: more Galdós *Episodios nacionales* (about 20 on PG), the rest of Darío and Blasco, and Valle-Inclán's *Comedias bárbaras*.

### 5.4 Twenty for a Spanish learner (marked **L**)

They're chosen to be short, in plain modern prose, or in school editions with English notes and vocabulary.

| PG # | Work | Why |
|---|---|---|
| 15353 | *A First Spanish Reader* (Roessler & Remy) | Graded reader; English notes and vocabulary |
| 36805 | *Spanish Tales for Beginners* | Short graded tales |
| 22065 | *An Elementary Spanish Reader* (Harrison) | Graded reader |
| 24250 | *Lecturas fáciles con ejercicios* (Wilkins & Luria) | Easy readings with exercises |
| 39647 | *The Spanish American Reader* (Nelson) | Latin American texts with notes |
| 11047 | *Libro segundo de lectura* (Cyr) | Children's school reader |
| 33406 | *Spanish short stories* | School anthology |
| 29731 | Alarcón, *El Capitán Veneno* (Hispanic Series) | Short novel, school edition |
| 15532 | Alarcón, *Novelas cortas* (en/es edition) | Short stories with notes |
| 10814 | Bécquer, *Legends, Tales and Poems* | Leyendas and Rimas, school edition |
| 16109 | Álvarez Quintero, *Doña Clarines y Mañana de sol* | Short modern plays, notes |
| 14235 | Tamayo y Baus, *Más vale maña que fuerza* | One-act play with notes, exercises, vocabulary |
| 55206 | Samaniego, *Fábulas* | Short verse fables |
| 29497 | Iriarte, *Fábulas literarias* | Short verse fables |
| 36558 | Coloma, *Ratón Pérez* | Children's story, very short |
| 19898 | Martí, *La Edad de Oro* | Written for children, clear Latin American prose |
| 9980 | Jiménez, *Platero y yo* | Short prose-poem chapters (US PD; see 5.2) |
| 55514 | Pardo Bazán, *Cuentos de amor* | Short, plain 1890s stories |
| 17340 | Galdós, *Marianela* | Short, plain novel, a classroom standard |
| 73486 | De Amicis, *Corazón* (transl.) | Diary of a schoolboy, simple prose |

### 5.5 The manifest: what the Spanish one needs

The English manifest (`tools/pack-builder/classics/manifest.json`) has top-level `name`, `title`, `description`, `language` (`"eng"`), `sections[{id,title}]` and `works[]`. Each work has `gutenberg`, `slug`, `title`, `author`, `translator`, `published`, `section`, `splitAt`, `titleCheck` and `note`.

**Proposed Spanish manifest** (`tools/pack-builder/classics/manifest_es.json`):

```json
{
  "name": "clasicos_granger_es",
  "title": "Clásicos Granger",
  "description": "Novela, cuento, poesía, teatro y ensayo en español hasta 1930",
  "language": "es",
  "collection": "clasicos-granger",
  "bookIdPrefix": "gc-es-",
  "sections": [{"id": "lecturas", "title": "Lecturas para aprender", "domain": "literature"}, …],
  "works": [{"gutenberg": 2000, "slug": "don-quijote", "title": "Don Quijote", "author": "Miguel de Cervantes Saavedra",
             "translator": null, "published": 1605, "section": "novelas", "splitAt": null, "titleCheck": true,
             "learner": false, "note": "Parts I (1605) and II (1615) in one PG file"}]
}
```

Changes to the code this implies (none made):

| Where | Today | Needed |
|---|---|---|
| `manifest.py validate()` | `"en" not in rdf.languages` → error | Check `m.language` (2-letter) is in `rdf.languages`. `en,es` readers then pass. |
| `manifest.py` `STOP_WORDS` | English only | Add `el la los las lo un una de del y o e` (title check) |
| `manifest.load` | Ignores unknown keys | Read `collection`, `bookIdPrefix`, section `domain`, and optional `learner` |
| `pack_builder.py` (lines 81, 105) | `language="en"` hardcoded | Use `m.language` |
| `pack_builder.describe_epubs` | `"collection": "granger-classics"` hardcoded | `m.collection`; release file `releases/clasicos_granger_es_books.json` |
| `epubpack.book_id` | `gc-{slug}` | `{prefix}{slug}` → `gc-es-…` (spec §5.3) |
| `catalog_gen.BOOK_DOMAINS` | Keyed by English section *titles* ("Philosophy" → thought) | Key by section id plus the manifest's `domain`, or add the Spanish titles. Otherwise every Spanish book defaults to `literature`, and **thought gets no Clásicos at all** |
| `curated.json books[]` | One `bookRelease` | Add the Spanish release, with `"license": "Public domain (US)"` |
| `zimpack.py` | `lang="en"`, "translated by" | Use the manifest language; "traducción de" |
| `epubpack.py` | "Contents" nav title, "(tr. …)" | "Índice", "(trad. …)" |
| `chapters.clean_title` | Strips "by …" and "Contents" | Also "por …", "Índice" and "Capítulo" headings as needed (check on real PG HTML) |
| `readiness` | `@granger-classics` | `@clasicos-granger` in the `es` lists, plus a preset `clasicos-granger` ("Clásicos Granger: todos los libros") |

Size is about 244 EPUBs × 0.27 MB ≈ **65 MB**, in its own GitHub release (for example `clasicos-es-YYYY-MM-DD`).

---

## 6. Open calls for Neal (summary)

1. Comprehensive Wikipedia: **all maxi (36.5 GB, recommended) or all nopic (13.3 GB)**. Nopic drops every Wikipedia picture from Comprehensive.
2. Food essentials: Ready.gov (2.07 GB) in essentials, or Comprehensive only.
3. iFixit Spanish: keep it excluded (needs a held sub-row), or treat it like `ifixit_en_all` (NC, in the essentials).
4. Stack Overflow en español (1.4 GB) in technology Comprehensive, though English Stack Overflow left the tiers.
5. MedlinePlus (both languages): the ZIMs carry A.D.A.M. and ASHP content and stock images that are not licensed for redistribution.
6. Clásicos with authors still protected in Spain or Argentina (§5.2): keep them under the US-PD rule, or leave them out of the Spanish set.
7. English Wiktionary (9.2 GB) as the learner's Define: offer it in the learner flow, and fix `Dictionary.choose` to pick by language and jump to `#Spanish`.

---

## Appendix: Clásicos Granger proposed works (244)

Flags: `transl.` = translation; `lang en,es` = bilingual or school edition (passes once the check is "es in languages"); `multi-vol` = one volume of a set. **L** = learner pick. Downloads are Gutendex's 30-day count, 2026-10-30.

#### `lecturas` — Lecturas para aprender (20 works, domain `literature`)

| PG # | Title | Author | Translator | Downloads | Flags | Learner |
|---|---|---|---|---|---|---|
| 15353 | A First Spanish Reader | Roessler, Erwin W.; Remy, Alfred |  | 2669 | lang en,es | **L** |
| 36805 | Spanish Tales for Beginners |  |  | 2624 |  | **L** |
| 22065 | An Elementary Spanish Reader | Harrison, Earl Stanley |  | 1987 |  | **L** |
| 39647 | The Spanish American Reader | Nelson, Ernesto |  | 1938 | lang en,es | **L** |
| 24250 | Lecturas fáciles con ejercicios | Wilkins, Lawrence A.; Luria, Max Aaron |  | 1387 |  | **L** |
| 11047 | Libro segundo de lectura | Cyr, Ellen M. |  | 799 | lang en,es | **L** |
| 33406 | Spanish short stories |  |  | 1076 |  | **L** |
| 63464 | Nuestra Pampa; libro de lectura | Molins, W. Jaime |  | 589 |  |  |
| 12435 | Paginas Sudamericanas | Phipps, Helen |  | 680 |  |  |
| 16059 | Modern Spanish Lyrics |  |  | 1418 | lang en,es |  |
| 10814 | Legends, Tales and Poems | Bécquer, Gustavo Adolfo |  | 1413 | lang en,es | **L** |
| 15532 | Novelas Cortas | Alarcón, Pedro Antonio de |  | 1950 | lang en,es | **L** |
| 29731 | El Capitán Veneno: The Hispanic Series | Alarcón, Pedro Antonio de |  | 1162 |  | **L** |
| 15725 | Doña Perfecta | Pérez Galdós, Benito |  | 1744 | lang en,es |  |
| 24917 | Mariucha | Pérez Galdós, Benito |  | 1554 | lang en,es |  |
| 28106 | Tres Comedias : Sin querer; De pequenas causas...; Los intereses creados | Benavente, Jacinto |  | 2150 | lang en,es |  |
| 16109 | Doña Clarines y Mañana de Sol | Álvarez Quintero, Serafín; Álvarez Quintero, Joaquín |  | 630 | lang en,es | **L** |
| 14235 | Más vale maña que fuerza: Proverbio en un acto; with notes, exercises, and vocab | Tamayo y Baus, Manuel |  | 531 | lang en,es | **L** |
| 15781 | El Estudiante de Salamanca and Other Selections | Espronceda, José de |  | 773 | lang en,es |  |
| 38124 | Spanish Composition | Broomhall, Edith J. |  | 792 |  |  |

#### `infantil` — Fábulas y lecturas infantiles (12 works, domain `literature`)

| PG # | Title | Author | Translator | Downloads | Flags | Learner |
|---|---|---|---|---|---|---|
| 55206 | Fábulas | Samaniego, Félix María |  | 1745 |  | **L** |
| 29497 | Fábulas literarias | Iriarte, Tomás de |  | 856 |  | **L** |
| 64058 | Fábulas y cuentos en verso: Selección |  |  | 731 |  |  |
| 19898 | La Edad de Oro: publicación mensual de recreo e instrucción dedicada a los niños | Martí, José |  | 1691 |  | **L** |
| 36558 | Ratón Pérez: cuento infantil | Coloma, Luis |  | 1104 |  | **L** |
| 9980 | Platero y yo | Jiménez, Juan Ramón |  | 1558 |  | **L** |
| 73486 | Corazón : (diario de un niño) | De Amicis, Edmondo |  | 1435 | transl. | **L** |
| 55215 | Cuando la tierra era niña | Hawthorne, Nathaniel | Martínez Sierra, Gregorio | 924 | transl. |  |
| 69552 | El libro de las tierras vírgenes | Kipling, Rudyard | Perés, Ramón D. (Ramón Domingo | 2091 | transl. |  |
| 45438 | La isla del tesoro | Stevenson, Robert Louis | Caballero, Manuel | 1945 | transl. |  |
| 69164 | Cuentos chilenos de nunca acabar | Laval, Ramón A. |  | 409 |  |  |
| 63424 | Cuentos populares en Chile | Laval, Ramón A. |  | 846 |  |  |

#### `cuentos` — Cuentos y novelas cortas (30 works, domain `literature`)

| PG # | Title | Author | Translator | Downloads | Flags | Learner |
|---|---|---|---|---|---|---|
| 55514 | Cuentos de amor | Pardo Bazán, Emilia, condesa de |  | 3859 |  | **L** |
| 13507 | Cuentos de Amor de Locura y de Muerte | Quiroga, Horacio |  | 1734 |  |  |
| 61202 | Novelas ejemplares | Cervantes Saavedra, Miguel de |  | 1034 |  |  |
| 65738 | El conde Lucanor | Juan Manuel, Infante of Castile |  | 870 |  |  |
| 29506 | El sombrero de tres picos: Historia verdadera de un sucedido que anda en romance | Alarcón, Pedro Antonio de |  | 1285 |  |  |
| 67248 | El clavo | Alarcón, Pedro Antonio de |  | 827 |  |  |
| 21282 | Tradiciones peruanas | Palma, Ricardo |  | 1592 |  |  |
| 55812 | Cuentos de navidad y reyes; cuentos de la patria; cuentos antiguos | Pardo Bazán, Emilia, condesa de |  | 735 |  |  |
| 75814 | En tranvía : Cuentos dramáticos | Pardo Bazán, Emilia, condesa de |  | 617 |  |  |
| 77759 | El espejo de la muerte : Cuentos cortos | Unamuno, Miguel de |  | 734 |  |  |
| 55916 | Tres novelas ejemplares y un prólogo | Unamuno, Miguel de |  | 647 |  |  |
| 27214 | Cuentos y diálogos | Valera, Juan |  | 619 |  |  |
| 62361 | El Señor y los demás son Cuentos | Alas, Leopoldo |  | 613 |  |  |
| 64589 | Doctor Sutilis (Cuentos) | Alas, Leopoldo |  | 629 |  |  |
| 75613 | Cuentos de Fray Mocho | Alvarez, José S. |  | 889 |  |  |
| 65689 | Las Fuerzas Extrañas | Lugones, Leopoldo |  | 616 |  |  |
| 27736 | La condenada (cuentos) | Blasco Ibáñez, Vicente |  | 612 |  |  |
| 66514 | Cuentos valencianos | Blasco Ibáñez, Vicente |  | 426 |  |  |
| 40827 | La voz de la conseja, t.1: Selección de las mejores novelas breves y cuentos de  | Pérez Galdós, Benito; Baroja, Pío et al. |  | 913 |  |  |
| 30053 | Los Puritanos, y otros cuentos | Palacio Valdés, Armando |  | 822 |  |  |
| 53355 | La novela en el tranvía | Pérez Galdós, Benito |  | 444 |  |  |
| 25074 | Novelas y cuentos | Estébanez Calderón, Serafín |  | 1097 |  |  |
| 26771 | Thespis (novelas cortas y cuentos) | Bunge, Carlos O. |  | 991 |  |  |
| 46000 | Cuentos ingenuos | Trigo, Felipe |  | 696 |  |  |
| 45945 | Antaño i Ogaño: Novelas i Cuentos de la Vida Hispano-Americana | Lastarria, José Victorino |  | 540 |  |  |
| 60376 | Cuentos de poeta | Blanco-Fombona, Rufino |  | 356 |  |  |
| 12457 | El Diablo Cojuelo | Vélez de Guevara, Luis |  | 721 |  |  |
| 10825 | La Navidad en las Montañas | Altamirano, Ignacio Manuel |  | 698 |  |  |
| 62359 | El anacronópete; Viaje a China; Metempsicosis | Gaspar, Enrique |  | 783 |  |  |
| 64465 | Novelas ejemplares y amorosas | Zayas y Sotomayor, María de |  | 779 |  |  |

#### `poesia` — Poesía (19 works, domain `literature`)

| PG # | Title | Author | Translator | Downloads | Flags | Learner |
|---|---|---|---|---|---|---|
| 53552 | Obras escogidas | Bécquer, Gustavo Adolfo |  | 1983 |  |  |
| 52894 | Azul...: Obras Completas Vol. IV | Darío, Rubén |  | 2789 | multi-vol |  |
| 47650 | Prosas Profanas: Obras Completas Vol. II | Darío, Rubén |  | 997 | multi-vol |  |
| 50341 | Cantos de Vida y Esperanza, Los Cisnes y otros poemas.: Obras Completas Vol. VII | Darío, Rubén |  | 935 | multi-vol |  |
| 68525 | Poesías completas | Machado, Antonio |  | 1054 |  |  |
| 49437 | Páginas escogidas | Machado, Antonio |  | 866 |  |  |
| 72665 | Romancero gitano | García Lorca, Federico |  | 1218 |  |  |
| 75703 | Libro de poemas | García Lorca, Federico |  | 849 |  |  |
| 58103 | 20 poemas para ser leídos en el tranvía | Girondo, Oliverio |  | 786 |  |  |
| 14765 | El Gaucho Martín Fierro | Hernández, José |  | 1286 |  |  |
| 15066 | La Vuelta de Martín Fierro | Hernández, José |  | 746 |  |  |
| 18600 | Mi Ultimo Adiós | Rizal, José |  | 2266 |  |  |
| 49333 | Coplas por la muerte de su padre | Manrique, Jorge |  | 740 |  |  |
| 68131 | Obras | Vega, Garcilaso de la |  | 593 |  |  |
| 74087 | Obras selectas de la celebre monja de Mejico, sor Juana Ines de la Cruz : preced | Juana Inés de la Cruz, Sister |  | 684 |  |  |
| 16625 | Clásicos Castellanos: Libro de Buen Amor | Ruiz, Juan |  | 2014 |  |  |
| 57648 | Romancero selecto del Cid | Anonymous |  | 864 |  |  |
| 65880 | Las cien mejores poesías (lí­ricas) de la lengua castellana |  |  | 993 |  |  |
| 70984 | En las orillas del Sar | Castro, Rosalía de |  | 559 |  |  |

#### `teatro` — Teatro (24 works, domain `literature`)

| PG # | Title | Author | Translator | Downloads | Flags | Learner |
|---|---|---|---|---|---|---|
| 60198 | Fuente Ovejuna | Vega, Lope de |  | 1628 |  |  |
| 23206 | La moza de cántaro | Vega, Lope de |  | 1116 |  |  |
| 28408 | Comedias: El remedio en la desdicha; El mejor alcalde, el rey | Vega, Lope de |  | 542 |  |  |
| 54436 | Teatro selecto, tomo 1 de 4 | Calderón de la Barca, Pedro |  | 871 | multi-vol |  |
| 55917 | Teatro selecto, tomo 2 de 4 | Calderón de la Barca, Pedro |  | 662 | multi-vol |  |
| 58643 | Teatro selecto, tomo 3 de 4 | Calderón de la Barca, Pedro |  | 529 | multi-vol |  |
| 63328 | Teatro selecto, tomo 4 de 4 | Calderón de la Barca, Pedro |  | 657 | multi-vol |  |
| 50027 | El sí de las niñas | Fernández de Moratín, Leandro |  | 1284 |  |  |
| 28002 | Electra | Pérez Galdós, Benito |  | 2177 |  |  |
| 59492 | Don Álvaro, o, La fuerza del Sino | Rivas, Angel de Saavedra, duque de |  | 789 |  |  |
| 29677 | El trovador | García Gutiérrez, Antonio |  | 765 |  |  |
| 10909 | Los Amantes de Teruel: Drama en cuatro actos en verso y prosa | Hartzenbusch, Juan Eugenio |  | 593 |  |  |
| 57955 | Los entremeses | Cervantes Saavedra, Miguel de |  | 540 |  |  |
| 16110 | Viage al Parnaso: La Numancia (Tragedia) y El Trato de Argel (Comedia) | Cervantes Saavedra, Miguel de |  | 702 |  |  |
| 57590 | La verdad sospechosa | Ruiz de Alarcón, Juan |  | 456 |  |  |
| 18580 | Los favores del mundo | Ruiz de Alarcón, Juan |  | 482 |  |  |
| 58194 | Tirso de Molina | Molina, Tirso de |  | 429 |  |  |
| 49013 | La venganza de Don Mendo | Muñoz Seca, Pedro |  | 638 |  |  |
| 68745 | Luces de Bohemia: Esperpento | Valle-Inclán, Ramón del |  | 540 |  |  |
| 69951 | Divinas palabras : Tragicomedia de aldea | Valle-Inclán, Ramón del |  | 497 |  |  |
| 10506 | Romance de lobos, comedia barbara | Valle-Inclán, Ramón del |  | 609 |  |  |
| 76410 | La Malquerida : Drama en tres actos y en prosa | Benavente, Jacinto |  | 457 |  |  |
| 53947 | La señorita de Trevelez: Farsa cómica en tres actos | Arniches y Barrera, Carlos |  | 616 |  |  |
| 76631 | O locura o santidad : Drama en tres actos y en prosa | Echegaray, José |  | 392 |  |  |

#### `novelas` — Novelas (56 works, domain `literature`)

| PG # | Title | Author | Translator | Downloads | Flags | Learner |
|---|---|---|---|---|---|---|
| 2000 | Don Quijote | Cervantes Saavedra, Miguel de |  | 16096 |  |  |
| 320 | Vida De Lazarillo De Tormes Y De Sus Fortunas Y Adversidades | Anonymous |  | 2290 |  |  |
| 32315 | Historia de la vida del Buscón, llamado Don Pablos, ejemplo de vagabundos y espe | Quevedo, Francisco de |  | 885 |  |  |
| 17013 | Fortunata y Jacinta: dos historias de casadas | Pérez Galdós, Benito |  | 1836 |  |  |
| 17340 | Marianela | Pérez Galdós, Benito |  | 1648 |  | **L** |
| 21831 | Misericordia | Pérez Galdós, Benito |  | 846 |  |  |
| 52392 | Miau | Pérez Galdós, Benito |  | 1216 |  |  |
| 55563 | El amigo Manso | Pérez Galdós, Benito |  | 844 |  |  |
| 25956 | La desheredada | Pérez Galdós, Benito |  | 857 |  |  |
| 66979 | Tristana | Pérez Galdós, Benito |  | 577 |  |  |
| 16961 | Trafalgar | Pérez Galdós, Benito |  | 924 |  |  |
| 14311 | Bailén | Pérez Galdós, Benito |  | 571 |  |  |
| 17073 | La Regenta | Alas, Leopoldo |  | 1815 |  |  |
| 17341 | Su único hijo | Alas, Leopoldo |  | 582 |  |  |
| 18005 | Los pazos de Ulloa | Pardo Bazán, Emilia, condesa de |  | 1056 |  |  |
| 58059 | La madre naturaleza (2ª parte de Los pazos de Ulloa) | Pardo Bazán, Emilia, condesa de |  | 560 |  |  |
| 52597 | Insolación y Morriña (Dos historias amorosas) | Pardo Bazán, Emilia, condesa de |  | 2885 |  |  |
| 17491 | La Tribuna | Pardo Bazán, Emilia, condesa de |  | 761 |  |  |
| 17223 | Pepita Jiménez | Valera, Juan |  | 1002 |  |  |
| 16484 | Juanita La Larga | Valera, Juan |  | 750 |  |  |
| 27738 | José | Palacio Valdés, Armando |  | 2860 |  |  |
| 31013 | La hermana San Sulpicio | Palacio Valdés, Armando |  | 620 |  |  |
| 24536 | Los cuatro jinetes del apocalipsis | Blasco Ibáñez, Vicente |  | 1604 |  |  |
| 14944 | La Barraca | Blasco Ibáñez, Vicente |  | 740 |  |  |
| 57781 | Cañas y barro: Novela | Blasco Ibáñez, Vicente |  | 420 |  |  |
| 26983 | Sangre y arena | Blasco Ibáñez, Vicente |  | 750 |  |  |
| 25640 | Los argonautas | Blasco Ibáñez, Vicente |  | 3717 |  |  |
| 23236 | Mare nostrum | Blasco Ibáñez, Vicente |  | 1267 |  |  |
| 49836 | Niebla (Nivola) | Unamuno, Miguel de |  | 3019 | multi-vol |  |
| 44512 | Abel Sánchez: Una Historia de Pasión | Unamuno, Miguel de |  | 864 |  |  |
| 44358 | La Tía Tula (Novela) | Unamuno, Miguel de |  | 840 |  |  |
| 49149 | Amor y Pedagogía | Unamuno, Miguel de |  | 809 |  |  |
| 60464 | El árbol de la ciencia: novela | Baroja, Pío |  | 2009 |  |  |
| 13264 | Zalacaín El Aventurero: (Historia de las buenas andanzas y fortunas de Martín Za | Baroja, Pío |  | 577 |  |  |
| 12848 | Las inquietudes de Shanti Andia | Baroja, Pío |  | 1007 |  |  |
| 43432 | La lucha por la vida: La busca | Baroja, Pío |  | 664 |  |  |
| 43017 | Mala Hierba | Baroja, Pío |  | 762 |  |  |
| 40544 | La lucha por la vida: Aurora roja | Baroja, Pío |  | 612 |  |  |
| 42440 | Sonata de primavera: memorias del marqués de Bradomín | Valle-Inclán, Ramón del |  | 579 |  |  |
| 42424 | Sonata de estío: memorias del marqués de Bradomín | Valle-Inclán, Ramón del |  | 617 |  |  |
| 46182 | Sonata de otoño; Sonata de invierno: memorias del Marqués de Bradomín | Valle-Inclán, Ramón del |  | 652 |  |  |
| 68154 | Tirano Banderas: Novela de tierra caliente | Valle-Inclán, Ramón del |  | 555 |  |  |
| 23600 | La gaviota | Caballero, Fernán |  | 1207 |  |  |
| 55058 | La Puchera | Pereda, José María de |  | 761 |  |  |
| 49388 | Sotileza | Pereda, José María de |  | 757 |  |  |
| 49413 | El señor de Bembibre | Gil y Carrasco, Enrique |  | 603 |  |  |
| 75126 | Sab (novela original) | Gómez de Avellaneda, Gertrudis |  | 861 |  |  |
| 28281 | Cecilia Valdés o la Loma del Ángel | Villaverde, Cirilo |  | 1663 |  |  |
| 47584 | Noli me tángere: Novela Tagala, Edición completa con notas de R. Sempau | Rizal, José |  | 2743 |  |  |
| 30903 | El Filibusterismo (Continuación del Noli me tángere) | Rizal, José |  | 1777 |  |  |
| 29920 | La gloria de don Ramiro: una vida en tiempos de Felipe segundo | Larreta, Enrique |  | 626 |  |  |
| 31724 | La gran aldea; costumbres bonaerenses | López, Lucio Vicente |  | 658 |  |  |
| 18166 | Amistad funesta: Novela | Martí, José |  | 718 |  |  |
| 16082 | Angelina: (novela mexicana) | Delgado, Rafael |  | 623 |  |  |
| 26545 | Antonio Azorín: pequeño libro en que se habla de la vida de este peregrino señor | Azorín |  | 1595 |  |  |
| 20011 | Pequeñeces | Coloma, Luis |  | 875 |  |  |

#### `universales` — Clásicos universales en español (42 works, domain `literature`)

| PG # | Title | Author | Translator | Downloads | Flags | Learner |
|---|---|---|---|---|---|---|
| 58221 | La Odisea | Homer | Segalá y Estalella, Luis | 7904 | transl. |  |
| 57654 | La Ilíada | Homer | Segalá y Estalella, Luis | 2586 | transl. |  |
| 57303 | La Divina Comedia | Dante Alighieri | Aranda y Sanjuan, Manuel | 2939 | transl. |  |
| 67092 | El paraíso perdido | Milton, John | Rosell, Cayetano | 2770 | transl. |  |
| 64775 | Los Lusíadas: Poema épico en diez cantos | Camões, Luís de | Gómez de Tapia, Luis, 16th cen | 540 | transl. |  |
| 46201 | Orlando Furioso, Tomo I | Ariosto, Lodovico | Aranda y Sanjuan, Manuel | 954 | transl. · multi-vol |  |
| 49063 | Orlando Furioso, Tomo II | Ariosto, Lodovico | Aranda y Sanjuan, Manuel | 994 | transl. · multi-vol |  |
| 68566 | Fausto: Primera parte | Goethe, Johann Wolfgang von | Llorente, Teodoro | 1148 | transl. |  |
| 66337 | Metamorfóseos o Transformaciones (1 de 4) | Ovid | Crivell, Francisco | 644 | transl. · multi-vol |  |
| 66338 | Metamorfóseos o Transformaciones (2 de 4) | Ovid | Crivell, Francisco | 561 | transl. · multi-vol |  |
| 66339 | Metamorfóseos o Transformaciones (3 de 4) | Ovid | Crivell, Francisco | 533 | transl. · multi-vol |  |
| 66340 | Metamorfóseos o Transformaciones (4 de 4) | Ovid | Crivell, Francisco | 466 | transl. · multi-vol |  |
| 67961 | El arte de amar | Ovid |  | 1169 | transl. |  |
| 66591 | La metamorfosis, o, El asno de oro | Apuleius | López de Cortegana, Diego | 784 | transl. |  |
| 56454 | Hamlet: Drama en cinco actos | Shakespeare, William | Fernández de Moratín, Leandro | 1189 | transl. |  |
| 53207 | Dramas de Guillermo Shakspeare [vol. 1] | Shakespeare, William | Menéndez y Pelayo, Marcelino | 991 | transl. · multi-vol |  |
| 63509 | Edipo rey; Edipo en Colona; Antígona | Sophocles | Bojart, José Pérez | 989 | transl. |  |
| 66023 | Tragedias | Aeschylus | Brieva Salvatierra, Fernando S | 827 | transl. |  |
| 73500 | Obras dramáticas de Eurípides (1 de 3) | Euripides | Mier, Eduardo de | 750 | transl. · multi-vol |  |
| 74704 | Obras dramáticas de Eurípides (2 de 3) | Euripides | Mier, Eduardo de | 733 | transl. · multi-vol |  |
| 75775 | Obras dramáticas de Eurípides (3 de 3) | Euripides | Mier, Eduardo de | 471 | transl. · multi-vol |  |
| 68443 | Comedias, tomo 1 de 3 : Los Acarnienses, los Caballeros, las Nubes | Aristophanes | Baráibar y Zumárraga, Federico | 691 | transl. · multi-vol |  |
| 69799 | Comedias, tomo 2 de 3 : Las Avispas, la Paz, las Aves, Lisístrata | Aristophanes | Baráibar y Zumárraga, Federico | 637 | transl. · multi-vol |  |
| 70542 | Comedias, tomo 3 de 3 : Las Fiestas de Ceres, las Ranas, las Junteras, Pluto | Aristophanes | Baráibar y Zumárraga, Federico | 732 | transl. · multi-vol |  |
| 61851 | El crimen y el castigo | Dostoyevsky, Fyodor | Pedraza y Páez, Pedro | 29958 | transl. |  |
| 75739 | Los miserables - Tomo 1 (de 2) | Hugo, Victor | J. A. R. | 1064 | transl. · multi-vol |  |
| 78068 | Los miserables - Tomo 2 (de 2) | Hugo, Victor | J. A. R. | 25344 | transl. · multi-vol |  |
| 61887 | Una historia de dos ciudades | Dickens, Charles | Lafuerza, Gregorio | 1023 | transl. |  |
| 36990 | La letra escarlata: novela escrita en inglés | Hawthorne, Nathaniel | Francisco, Sellén | 1103 | transl. |  |
| 24823 | Silas Marner | Eliot, George |  | 716 | transl. |  |
| 24801 | El prisionero de Zenda | Hope, Anthony |  | 663 | transl. |  |
| 7109 | Candido, o El Optimismo | Voltaire |  | 582 | transl. |  |
| 74029 | La dama de las camelias; Una familia corsa | Dumas, Alexandre; Dumas, Alexandre |  | 861 | transl. |  |
| 50492 | Historia de Gil Blas de Santillana: Novela (Vol 1 de 3) | Le Sage, Alain René | Isla, José Francisco de | 709 | transl. · multi-vol |  |
| 52682 | Historia de Gil Blas de Santillana: Novela (Vol 2 de 3) | Le Sage, Alain René | Isla, José Francisco de | 560 | transl. · multi-vol |  |
| 55796 | Historia de Gil Blas de Santillana: Novela (Vol 3 de 3) | Le Sage, Alain René | Isla, José Francisco de | 631 | transl. · multi-vol |  |
| 46196 | Cuentos Clásicos del Norte, Primera Serie | Poe, Edgar Allan | Calderón de Pinillos, Carmen T | 1745 | transl. |  |
| 52262 | Cuentos de la Alhambra | Irving, Washington | Lamarca, Luis | 920 | transl. |  |
| 74908 | Cuentos escogidos | Maupassant, Guy de | Batlle, Carlos de | 682 | transl. |  |
| 29706 | Cartas de mi molino | Daudet, Alphonse | Cabañas, F. | 597 | transl. |  |
| 62627 | El caso extraño del Doctor Jekyll | Stevenson, Robert Louis | Soulère, Emilio Augusto | 1130 | transl. |  |
| 25807 | Poemas | Poe, Edgar Allan | Lasplaces, Alberto; Pérez Bona | 821 | transl. |  |

#### `ensayo` — Ensayo y pensamiento (18 works, domain `thought`)

| PG # | Title | Author | Translator | Downloads | Flags | Learner |
|---|---|---|---|---|---|---|
| 78052 | Andanzas y visiones españolas | Unamuno, Miguel de |  | 24924 |  |  |
| 59852 | Del sentimiento trágico de la vida | Unamuno, Miguel de |  | 920 |  |  |
| 75472 | Vida de Don Quijote y Sancho | Unamuno, Miguel de |  | 723 |  |  |
| 57448 | Meditaciones del Quijote | Ortega y Gasset, José |  | 746 |  |  |
| 22899 | Ariel | Rodó, José Enrique |  | 868 |  |  |
| 64974 | El Hombre Mediocre: Ensayo de psicologia y moral | Ingenieros, José |  | 1778 |  |  |
| 62691 | El criticón (tomo 1 de 2) | Gracián y Morales, Baltasar |  | 794 | multi-vol |  |
| 63402 | El criticón (tomo 2 de 2) | Gracián y Morales, Baltasar |  | 540 | multi-vol |  |
| 31541 | Fígaro (Artículos selectos) | Larra, Mariano José de |  | 606 |  |  |
| 28929 | El Criterio | Balmes, Jaime Luciano |  | 669 |  |  |
| 66373 | Reglas y consejos sobre investigación científica: (Los tónicos de la voluntad) | Ramón y Cajal, Santiago |  | 803 | multi-vol |  |
| 58331 | Recuerdos de mi vida (tomo 1 de 2) | Ramón y Cajal, Santiago |  | 877 | multi-vol |  |
| 60675 | Recuerdos de mi vida (tomo 2 de 2) | Ramón y Cajal, Santiago |  | 691 | multi-vol |  |
| 43861 | Granos de oro: Pensamientos Seleccionados en las Obras de José Martí | Martí, José |  | 688 |  |  |
| 61573 | La perfecta casada | León, Luis de |  | 534 |  |  |
| 33267 | Facundo | Sarmiento, Domingo Faustino |  | 1365 |  |  |
| 57505 | El tratado de la pintura | Leonardo, da Vinci; Alberti, Leon Battista | Rejón de Silva, Diego Antonio | 840 | transl. |  |
| 62711 | Naturaleza de las cosas: Versión en prosa del poema «De rerum natura» |  | Rodríguez-Navas, Manuel | 483 | transl. |  |

#### `historia` — Historia y crónicas (21 works, domain `thought`)

| PG # | Title | Author | Translator | Downloads | Flags | Learner |
|---|---|---|---|---|---|---|
| 64945 | Verdadera historia de los sucesos de la conquista de la Nueva-España (1 de 3) | Díaz del Castillo, Bernal |  | 982 | multi-vol |  |
| 64946 | Verdadera historia de los sucesos de la conquista de la Nueva-España (2 de 3) | Díaz del Castillo, Bernal |  | 551 | multi-vol |  |
| 64947 | Verdadera historia de los sucesos de la conquista de la Nueva-España (3 de 3) | Díaz del Castillo, Bernal |  | 615 | multi-vol |  |
| 11071 | Naufragios de Alvar Núñez Cabeza de Vaca | Núñez Cabeza de Vaca, Alvar, active 16th century |  | 724 |  |  |
| 49298 | Historia de las Indias (vol. 1 de 5) | Casas, Bartolomé de las |  | 1240 | multi-vol |  |
| 50351 | Historia de las Indias (vol. 2 de 5) | Casas, Bartolomé de las |  | 1326 | multi-vol |  |
| 53171 | Historia de las Indias (vol. 3 de 5) | Casas, Bartolomé de las |  | 1087 | multi-vol |  |
| 56283 | Historia de las Indias (vol. 4 de 5) | Casas, Bartolomé de las |  | 995 | multi-vol |  |
| 53131 | Historia de las Indias (vol. 5 de 5) | Casas, Bartolomé de las |  | 804 | multi-vol |  |
| 70219 | Historia natural y moral de las Indias (vol. 1 of 2) | Acosta, José de |  | 857 | multi-vol |  |
| 50430 | Historia natural y moral de las Indias (vol. 2 of 2) | Acosta, José de |  | 764 | multi-vol |  |
| 25255 | Segunda parte de la crónica del Perú, que trata del señorio de los Incas Yupanqu | Cieza de León, Pedro de |  | 849 |  |  |
| 25705 | Suma y narracion de los Incas, que los indios llamaron Capaccuna, que fueron señ | Betanzos, Juan de |  | 769 |  |  |
| 72753 | Los nueve libros de la Historia (1 de 2) | Herodotus | Pou i Puigserver, Bartomeu | 1007 | transl. · multi-vol |  |
| 72754 | Los nueve libros de la Historia (2 de 2) | Herodotus | Pou i Puigserver, Bartomeu | 1006 | transl. · multi-vol |  |
| 66866 | Historia de la guerra del Peloponeso (1 de 2) | Thucydides | Gracián, Diego | 670 | transl. · multi-vol |  |
| 66867 | Historia de la guerra del Peloponeso (2 de 2) | Thucydides | Gracián, Diego | 487 | transl. · multi-vol |  |
| 66949 | Las Helénicas; ó, Historia griega desde el año 411 hasta el 362 antes de Jesucri | Xenophon | Soms y Castelín, Enrique | 580 | transl. |  |
| 51019 | La Biblia en España, Tomo I (de 3): O viajes, aventuras y prisiones de un inglés | Borrow, George | Azaña, Manuel | 860 | transl. · multi-vol |  |
| 51020 | La Biblia en España, Tomo II (de 3): O viajes, aventuras y prisiones de un inglé | Borrow, George | Azaña, Manuel | 690 | transl. · multi-vol |  |
| 51689 | La Biblia en España, Tomo III (de 3): O viajes, aventuras y prisiones de un ingl | Borrow, George | Azaña, Manuel | 667 | transl. · multi-vol |  |

#### `escritura` — Escritura y religión (2 works, domain `thought`)

| PG # | Title | Author | Translator | Downloads | Flags | Learner |
|---|---|---|---|---|---|---|
| 5881 | Reina Valera New Testament of the Bible 1909 |  | Reina, Casiodoro de; Valera, C | 788 | transl. |  |
| 65165 | Vida de Jesús | Renan, Ernest | Vega, Federico de la | 856 | transl. |  |

## 7. Neal's calls (2026-10-30)

1. **Comprehensive Wikipedia:** `wikipedia_es_all_maxi` (36.5 GB), pictures throughout. Save space swaps it to nopic.
2. **Ready.gov en español:** in Comprehensive only.
   - **Totals with calls 1–3 together** (corrected 2026-10-30: the first figures here left out iFixit's 3.68 GB):
     - Efficient: about 9.5 GB with pictures, 5.5 GB with Save space.
     - Comprehensive: about 48.2 GB with pictures, 24.4 GB with Save space.
   - These are packs only, before L4's search vectors.
3. **iFixit en español:** treated the same as `ifixit_en_all`: in the Making essentials under the same non-commercial flag. F1's Play policy check decides for both languages together. This replaces the spec's "excluded".
4. **Clásicos Granger:** only works public domain both in the US and where the readers live (Spain, Argentina, the life+70/80 countries). The ~8 authors in §5.2 and the 13 non-PD Gutenberg books are out.

Defaults taken without asking, matching the English calls:
- Stack Overflow en español stays out of the tiers, as English Stack Overflow left them in H4. It can still be had from Get more.
- MedlinePlus en español has the same standing as the English pack.
- English Wiktionary is the learner's Define, per spec §10.3.
