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
