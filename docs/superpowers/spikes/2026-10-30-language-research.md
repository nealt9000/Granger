# L0: Languages, Spanish first — research notes (2026-10-30)

Desk research and a code audit, no code changed. Feeds the L0 spec and the L1+ plans. Paths are relative to the repo root.

## Two kinds of "language support"

1. **The app in Spanish**: every screen, notification and error in Spanish.
2. **Spanish knowledge**: Spanish packs, books, audiobooks, Research that answers in Spanish, a Spanish voice.

They're independent: a Spanish speaker may want a Spanish app over English packs, or the reverse. EmbeddingGemma handles questions in one language over passages in another, which helps the mixed case.

## What the code assumes today

| Area | Size | What's English-only |
| --- | --- | --- |
| UI text | L | `strings.xml` holds 4 widget strings; about 700–900 user-facing strings are literals in Compose (about 350 `Text("…")`, 99 content descriptions) across about 155 files, plus copy in `core/` (notifications, download errors, archive.org shelf titles). No plurals resources: about 19 hand-built English plurals. `Locale.ENGLISH` month names (`home/DayPage.kt`), `Locale.US` byte sizes (`Bytes.kt`), no `localeConfig` |
| Catalog | L | `tools/catalog-gen/curated.json` filters Kiwix to `eng`; 11 `kiwix_families.json` rows are `_en`-only. Packs, books and voices carry `language` in three code styles (`eng`, `en`, `en-US`); models, presets, readiness domains and onboarding have none, and their titles and blurbs are English in the signed catalog. Readiness domains are lists of `*_en_*` ids. Hardcoded ids: `granger_classics_en` (`LibraryPassageFinder.kt`, `Filing.kt`), the en_US voice in onboarding, English map titles via `getDisplayCountry(Locale.ENGLISH)` |
| Sources | M | archive.org queries have no `language:` clause and English subject words; LibriVox has no language parameter and strips English articles; `tools/pack-builder/manifest.py` rejects non-English works, `language="en"` hardcoded in the builders; `TextToEpub.kt` writes `xml:lang="en"` |
| Research | L | English prompts (`AnswerPrompt.kt`, `Rewrite.kt`, `GuessPrompt.kt`, the `NOT IN LIBRARY` token), stopwords and possessives (`Keywords.kt`), **the medical and personal-medical detection (`Keywords.kt` Medical / PersonalMedical, safety-critical)**, citation phrasing (`Citations.kt`), follow-up pronouns, media cues, fiction weight, entity openers, `YearFacts.kt` (months, BC/BCE, event verbs, sentence regex), `BreakIterator(Locale.ENGLISH)` in `CitedQuote.kt`, 4 chars per token. Language-neutral already: the chunker, FTS4 `unicode61` (folds accents, no stemming), query-side accent folding, libzim search (each ZIM's own language) |
| Voice | M–L | `SpokenText.kt` (years, decades, units, circa) is English; `SherpaTts.kt` hardcodes `lang = "en-us"`; installed-voice and system-voice code assume en-US; `Sentences.kt` defaults to `Locale.US` |
| Define, Home | S–M | Define picks the newest Wiktionary of any language; On this day looks for English Wikipedia's "October 22" and Events/Births/Deaths (Spanish: "22 de octubre", Acontecimientos/Nacimientos/Fallecimientos); Browse picks the encyclopedia by English ids |
| Eval | M | All 302 questions, the rubric and the few-shots are English. A Spanish set (about 100 questions) comes before any prompt change |
| Speech input | — | Nothing on main yet (A0 is on its own branch); build A1 with a language from the start |

## Spanish content

**Kiwix** (live OPDS catalog, 2026-10; every Wikimedia `es` ZIM has a full-text index):

| Pack | Sizes |
| --- | --- |
| `wikipedia_es_all` | maxi 36.5 GB · nopic 13.3 GB · mini 4.1 GB (about 4.2M articles) |
| `wikipedia_es_top` | maxi 4.07 GB · nopic 1.21 GB · mini 0.22 GB (about 348k) |
| `wiktionary_es_all` | nopic 0.66 GB |
| `wikivoyage_es_all` | 0.14 GB |
| `wikisource_es_all`, `wikibooks_es_all`, wikiquote, wikiversity | 0.03–0.51 GB |
| `vikidia_es` (children's) | 0.13 GB |
| Topic Wikipedias | medicine (0.47 GB), history, geography, maths, physics, chemistry, computer, climate change, and more |
| `es.stackoverflow.com_mul_all` | 1.44 GB (CC BY-SA) |
| `medlineplus.gov_es_all` | 1.77 GB (US government; check image licences) |
| `ready.gov_es` | 2.07 GB (US government) |

- There's **no Spanish Top 1M**: the tiers step from `top` (348k articles) to `all` nopic (13.3 GB). There's **no `mdwiki_es`**: the medical domain uses `wikipedia_es_medicine` plus MedlinePlus.
- **Excluded (non-commercial):** `ifixit_es`, `khanacademy_es`, TED.
- **To check:** `ecured_es` (licence unclear), `marxists.org_es`.
- `gutenberg_es_all` (1.86 GB) has no full-text index, the same gap as the English packs.

**Books:**
- **Project Gutenberg** has 922 Spanish books (Gutendex `languages=es`): Don Quijote, Lazarillo de Tormes, Bécquer, Pérez Galdós, Valera, Blasco Ibáñez, and translations. That's enough for a Granger Clásicos collection through the pack builder.
- **archive.org:** the language field is inconsistent, so query `language:(spa OR Spanish)`. With the 1930 filter that's about 490k texts.
- **LibriVox** has about 565 Spanish items. Its API ignores a language parameter, so filter on the field client-side or query archive.org's `librivoxaudio` collection.

## Research in Spanish

- **Gemma 3n E2B:**
  - Trained on 140+ languages; Google names Spanish among its improved languages.
  - Its multilingual scores are modest: Global-MMLU 55.1, MGSM 53.1, MMLU-ProX 8.1.
  - The model card has no Spanish-only numbers and no RAG numbers. A Spanish eval set is the only real measure.
- **EmbeddingGemma:**
  - MTEB Multilingual v2 is 61.2 at 768d, against 69.7 for English v2.
  - It's the strongest open multilingual embedder under 500M parameters and handles questions and passages in different languages.
  - Dense recall needs re-measuring on Spanish vectors.
- **Prompts:** decide between translating them or keeping English instructions plus "answer in the question's language". The eval decides.
- **The medical banner and the personal-medical detection must be ported before Spanish Research ships.**

## A Spanish voice: a licence problem

Licences below are from each voice's model card on `rhasspy/piper-voices`:

| Voice | Quality | Data licence | Base model | Verdict |
| --- | --- | --- | --- | --- |
| es_ES-davefx | medium | CC0 | lessac | Tainted |
| es_ES-sharvard | medium | CC BY 3.0 | lessac | Tainted |
| es_AR-daniela | high | CC BY-SA 4.0 | lessac | Tainted |
| es_MX-ald | medium | Unlicense | from davefx (lessac) | Tainted |
| es_ES-mls_* | low | CC BY 4.0 | ryan (CC BY-NC-SA) | Tainted |
| es_ES-carlfm | x_low | public domain | from scratch | Clean but poor |
| es_MX-claude | high | undocumented | unknown | Flag |

The lessac data is research-only and excludes commercial voice products. That leaves **no off-the-shelf medium Spanish Piper voice clean for a paid app**. The same issue kept `libritts_r` out in V3.

The clean data is CC0 davefx (Castilian), public-domain carlfm, MLS (CC BY), SHARVARD (CC BY), OpenSLR 61 (CC BY-SA, Argentine) and Ald (Unlicense). So a Spanish voice is trained the way V3 or V4 were:
- from scratch on that data, or
- a Qwen3-TTS teacher distilled into Piper with `es` phonemes. Confirm Qwen3-TTS's Spanish support and licence first.

Supporting pieces:
- espeak-ng has `es` and `es-419` phonemes.
- sherpa-onnx's Kokoro builds are English and Chinese only.
- MMS-TTS is CC BY-NC, so it's out.

## Speech input

- **Android:** `createOnDeviceSpeechRecognizer` (API 31+) reports supported and installed languages and can request a Spanish pack (`checkRecognitionSupport` / `triggerModelDownload`, API 33+). Which Spanish variants exist as separate on-device packs is unconfirmed; probe the Pixel 6 during A0.
- **iPhone:** Spanish is on iOS 26 SpeechAnalyzer's launch list.

## App text practice

- **Spanish text:** strings go to `res/values/strings.xml` with `values-es/` for Spanish, read through `stringResource`; plurals resources, no concatenation.
- **Language setting:** the per-app language setting (Android 13+, AppCompat back to API 21) via `generateLocaleConfig` and `AppCompatDelegate.setApplicationLocales`.
- **Recommendation:** write one neutral, Latin-American-leaning `values-es` (no vosotros) and add `es-ES` overrides only where needed. Localise the es-419 and es-ES Play listings.

## Suggested order (for the spec)

1. **L1, strings out of Compose** (the L-sized chore, no visible change). It's worth doing early whatever the order, because every UI plan after it adds more English literals. Plurals, date and number formats go in the same pass.
2. **L2, the Spanish app**: the `values-es` translation, the per-app language setting, onboarding asking for the language.
3. **L3, Spanish knowledge**:
   - a `language` on presets, readiness domains and onboarding;
   - one code style for `language`;
   - Kiwix `es` families with Spanish tiers (Efficient around `wikipedia_es_top`, Comprehensive around `all` nopic or maxi);
   - archive.org and LibriVox filters;
   - a Spanish Classics collection;
   - Define and On this day per language.
4. **L4, Research in Spanish**: a Spanish eval set first, then Spanish vectors, the word lists and medical detection, prompts, `YearFacts`, and the pass bar.
5. **L5, a Spanish voice**: a trained clean voice, `SpokenText` for Spanish, `lang` per voice.

## Open questions for Neal

- **Scope:** the app in Spanish, Spanish knowledge, or both? And in what order?
- **Variant:** neutral Latin American as the base, or Castilian?
- **Timing:** before or after F1/F2? L1 (string extraction) could run now, beside the UX work.
- **Voice:** train a Spanish voice now, or ship Spanish without read-aloud until one is ready? The system TTS is the stopgap either way.

## Neal's calls (2026-10-30)

1. **Both, together.** The app in Spanish and Spanish knowledge are built hand in hand, and neither ships without the other.
2. **Neutral Latin American Spanish** is the base (`values-es`, no vosotros).
3. **Proceed now** with everything that doesn't need the phone. Phone steps wait until the other work using the Pixel 6 is finished with it.
4. **Plan for Spanish voice training, but don't start it yet.** Until then the system voice reads Spanish.
