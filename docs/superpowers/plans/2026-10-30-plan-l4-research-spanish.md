# Plan L4: Research in Spanish — notes

## Language layer

Spec §6.2's word lists, on `l4-spanish-eval` (commits c1b1a628 to 4fde2dee). Before: 214 `:core:research` tests. After: 259, all passing. 101 app Research/Ask tests pass, and `:app:compileDebugKotlin` builds.

**The rule.** The English lists haven't changed. The Spanish lists apply only when `ResearchLanguage.of(question)` contains ES. `ResearchPipeline` works out the languages once and passes them to every list. A function that works on keywords or passages defaults to English alone, so existing callers behave as before. A function that takes the question defaults to `ResearchLanguage.of(question)`. `LanguageLayerTest` runs every changed function over every English eval question (tune, heldout, media), first with English alone and then with the detected languages. The results are identical.

**Spanish words that are English too.** `SpanishWords.ENGLISH_TOO` lists words like son, sea, con, los, la, el, este, dice, banda, mayor, para, como and era. These count only when Spanish leads the question. A question with no function words in either language gets `[EN, ES]`. Without this rule, "Los Angeles population", "Con Edison" or "Este Lauder" would lose words, and their runs would change. Applied to: stopwords, follow-up words, media cues, literary words, Entities openers, particles and `de` joins, and YearFacts stop and event words.

**Judgement calls**
- **FollowUp.** On their own, "la", "los" and "las" would fire on nearly every Spanish question ("la batalla de Boyacá"). They count only right after a question word, "no", "ya" or "nunca" ("¿quién la pintó?"). "Lo" doesn't count before que, más, mejor, mismo or cual. "Le" and "les" don't count when the question has "a" or "al", which doubles them ("a los bebés les dicen"). With these rules, 5 of tune-es's 6 follow-ups are caught. The miss is "¿con quién se casó?": Spanish drops the subject, and there's no pronoun to find. Of the standalone tune-es questions, only "mi bebé… ¿lo llevo al doctor?" fires, and personal-medical declines it first. "Él" needs its accent, and "está" isn't "esta". An unaccented "donde esta…" will fire.
- **Entities.** "de", "del" and "de la/los/las" join a run before a capital ("Isla de Pascua", "Don Quijote de la Mancha"). "y" doesn't join a run: "Bolívar y Santander" is more common than "Ramón y Cajal". "y" is a particle in `match` only. Spanish titles capitalise only their first word, so "Cien años de soledad" gives the run "Cien". I left that to the rewrite's keywords. Plain "ya" was too common as a cue, so it counts only alongside "cuando". I added Spanish openers beyond the spec's list (hay, se, de, con, para…), because "¿Hay vuelos…" made "Hay" a name.
- **YearFacts.** In Spanish the verb often comes first ("cuando salió el Quijote", "se publicó Pedro Páramo"). A word after cuando, se, mientras, tras, hasta or desde, with no name in between, goes to the next name. So does a word that names the next name with "de" ("la batalla de Boyacá", up to three words before the "de"). "¿Llegó X a ver…?" is blanked out, so it doesn't count as an arrival. Nació, murió and falleció are stop words, as "born" and "died" are. "a. C." and the other BC eras exclude the year, as "BC" does; "d. C." needs nothing. `SENTENCE_ES` doesn't split after "a.", "Sr." and the like. Spanish leads put the birthplace after the year ("1547-Madrid"), so the dash rule takes `(?<=\d)-(?=\p{Lu})`. Centuries ("siglo XIX") give no year, as in English. Left out: "1.º de enero" (the ordinal day) and "between" readings. For "1648 o 1651" the code takes the last year, as it does for English "between … and". The block it writes stays English.
- **CitedQuote.** Sentences break by the passage's language when it's known, and otherwise by the question's main language (`Locale es`). Stopwords come from both. `Passage` has no language field yet, so in the pipeline it's always the question's.
- **Citations.** The English decline patterns always apply, because the prompts are English and the model may answer a Spanish question in English. The Spanish ones are added when ES is detected. In Spanish, "a. C." ends a sentence unless the text continues in lower case. A sentence ending "…44 a. C." is common, and merging it with the next sentence turned an answer into a decline.

**Eval mirrors.** `tools/eval/language.py` ports `ResearchLanguage.of` and reads the Spanish lists from the Kotlin source. replay, kinds and merge's hybrid key each question's languages to them. I ran seven recorded runs through the baseline and fiction rankers: English replay output is byte-identical. `rewrite.py medical` reads `MedicalEs.kt` and applies `looksMedical`'s language rule, and `test_language.py` checks that it catches every Spanish label. rerank.py and `merge.subjects` stay English (closed experiments).

**English-only, outside this pass**
- `core/library`: `LibraryPassageFinder` and `MediaCards` call `Keywords.isStopword` without languages, because `PassageFinder` gets keywords only. `PassageAddress.quoteOf` calls `CitedQuote.pick` with English defaults, so a Spanish answer's citation quote uses English stopwords and sentence breaks.
- `ResearchLanguage.of` reads some English keyword-only questions as Spanish alone: "Lake Como villas", "Victorian era fashion", "hay fever remedies", "El Niño effects", "Para handball". `LanguageLayerTest` pins this list. With "hay fever remedies", the English medical lexicon is off, so the question isn't flagged.
- `MedicalEs.TERMS` has regex syntax such as `sangr(?!ia)`, but its entries go through `Regex.escape`, so that syntax is matched literally.
- `tools/eval/multistep.py` parses English dates only.

## Medical layer (2026-10-31)

- `ResearchLanguage.of` picks the question's languages. `Medical`/`PersonalMedical` run each language's lists, so English questions get exactly what they got before. `MedicalEsTest` and `LanguageLayerTest` check every English eval question.
- `MedicalEs`/`PersonalMedicalEs` are the Spanish lexicon and personal phrasings, matched on folded text (no accents).
- Scores:
  - Tuning labels (`medical-labels-es`, 141): every medical label caught, every personal one declined.
  - First held-out (`medical-heldout-es`, 150), first run: 84/100. Its misses were then added, so it's tuning data now.
  - Clean held-out (`medical-heldout2-es`, 160, never tuned on): 73/110 medical caught, 17/35 personal declined, no ordinary question flagged, none wrongly declined, 9/38 near-misses flagged. `HeldOut2ScoreTest` pins the floor.
- For scale: the English lexicon catches 46/106 of its own labels. In English, the rewrite model's medical flag catches the rest.
- **So keywords alone can't meet the spec's "catch every one".** Spanish Ask's medical bar has to be measured with the model's flag (and/or the EmbeddingGemma centroid in `rewrite.py medical`). That means the phone baseline: run the three Spanish label sets through the rewrite and count misses of lexicon OR model. Until that passes, Spanish Ask stays off.
- Don't add `medical-heldout2-es` misses to the lexicon. If the lexicon grows, write a third fresh list to score it.

## Spanish vectors (2026-10-31 to 11-01)

Spec §6.1, built on the Mac with `tools/vectors` the same way as R1. Branch `l4-spanish-vectors`.

**The set** (`tools/vectors/set-es.json`): the Spanish essential tier, Wikipedia history (English has it too), and the 185 Clásicos Granger books.
- Each Wikipedia pack is built from its `_nopic` file. The nopic and maxi files share a snapshot date in the catalog, so `catalog_gen attach_vectors` puts the vectors on both flavours, as R1 did for medicine, top and history.
- I checked that nopic and maxi chunk the same way: 400 random chemistry articles gave 3,858 chunks from each file, all identical. iFixit has one flavour.
- `keywordOnly` lists the comprehensive extras: wikipedia_es_all (2.1M articles), Wiktionary, Wikisource, Wikiquote, MedlinePlus, Ready.gov, Scoutwiki, Low-tech Magazine, molcell, climate change, geography, Wikibooks, Wikiversity, Vikidia and Spanish Language SE.
- Snapshots and sha256s come from a dry `catalog_gen build` of the live Kiwix feed on 2026-10-31 (scratch only, not committed or signed), saved as `vectors-work/catalog-es-dry-2026-10-31.json`. `count.py` and `build.py` now take `--set` and `--catalog`. The defaults are unchanged, so English output is the same.

**Counts, trims, time.** At set.json's caps the set came to 1.80M chunks: iFixit 580k at 8 and Top 536k at 12. That's about 18 h at 100k/h, and the Mac measured 75–81k/h on Wikipedia. So I trimmed it the way R1 trimmed the English set:

| Pack | Cap | Chunks | .gvec MB |
|---|---:|---:|---:|
| wikipedia_es_top_nopic (2026-10-10) | 12 → 8 | 382,318 | 112.5 |
| ifixit_es_all (2026-03-06) | 8 → 4, the Stack Exchanges' cap | 339,340 | 111.4 |
| wikipedia_es_medicine_nopic (10-15) | 12 | 132,840 | 39.6 |
| wikipedia_es_history_nopic (10-14) | 12 → 8 | 101,992 | 30.3 |
| wikipedia_es_computer_nopic (10-15) | 12 → 8 | 83,771 | 24.6 |
| wikivoyage_es_all_nopic (09-17) | none | 74,903 | 21.7 |
| wikipedia_es_physics_nopic (10-15) | 12 | 59,736 | 17.8 |
| wikipedia_es_mathematics_nopic (10-14) | 12 | 59,623 | 17.8 |
| wikipedia_es_chemistry_nopic (10-15) | 12 | 25,932 | 7.7 |
| Clásicos Granger (185 books, 2026-10-30) | none | 111,928 | 32.9 |
| **Total** | | **1,372,383** | **416.4** |

- **Why Top at 8:** of the 46 tune-es answers whose quote appears in their Top article, 45 sit in the first 8 chunks (38 of them in chunk 0). At 10, all 46 are kept.
- **Build time:** 13.6 h wall clock, 17:56 to 07:32, including one restart to apply the trims. Wikipedia ran at about 78k chunks/h. iFixit's short chunks ran at about 250k/h.
- The log is `vectors-work/es.log` and the work folder is `vectors-work/build-es/`.

**Recall** (`recall.py dataset`). Questions are embedded on the Mac with the prior question first. The vector files are scanned as the phone scans them (256-dim int8). A hit is by location or quote. The table counts only answerable questions whose answer source has vectors; `--any-source` also counts the same article from a subject pack.

| Set → vectors | top 4 | top 12 | top 50 | best hit ≥ 0.78 (answerable / unanswerable) |
|---|---|---|---|---|
| tune-es → Spanish | 45/58 (48 any-source) | 54/58 (55) | 57/58 | 57/58 / 16/41 |
| tune-es → Spanish + English (R1's 372 files) | 48/58 any-source | 53/58 | 57/58 | 57/58 / 19/41 |
| mixed-es, Spanish questions → English vectors | 8/12 | 8/12 | 10/12 | 9/12 / 1/3 |
| mixed-es, English questions → Spanish vectors | 6/9 | 8/9 | 8/9 | 9/9 / 0/3 |
| mixed-es, both directions → both sets | 11/21 | 14/21 | 18/21 | 20/21 / 4/6 |

- tune-es's 5 *words* answers are in Wiktionary (keyword only). In mixed-es, 3 of the English-question answers are too. Neither is counted.
- Science misses all 4 at top 4 but finds all 4 at top 12: the physics and chemistry packs' related articles outrank Top's.
- With both sets installed, Spanish questions about English packs drop from 8 to 5 at top 4. Spanish passages from the Spanish packs take those places; they're in Spanish, so the English answer locations don't count.
- For comparison, English R1's "dense alone" ranker found 58/126 at top 4 on the English tuning split (a harder, larger set, and before every pack was built). The two numbers measure different sets and aren't directly comparable.
- `heldout-es.json` was not opened.

**Parity.** There are no Spanish phone runs yet, so `parity.py` has nothing to check against. The chunker is the app's own (`:core:text`, version 1), and the nopic/maxi check above passed.

**Left for Neal**
1. **Upload:** the 194 files in `vectors-work/build-es/out` (416.4 MB) go to a GitHub release. Either a new tag such as `vectors-es-2026-11-01`, or added to `vectors-2026-10-23` as R1 did.
2. **Catalog:** when L3's catalog is next built, run `catalog_gen.py vectors --releases tools/vectors/releases --release-url <that release>`, then check-links and sign. A dry attach on the scratch catalog put vectors on 17 Spanish pack entries (nopic and maxi) and all 185 Clásicos books.
3. The vectors match these snapshots only. If Kiwix publishes a newer Spanish snapshot before the catalog is built, that pack's vectors are reported and left out ("rebuild it").
