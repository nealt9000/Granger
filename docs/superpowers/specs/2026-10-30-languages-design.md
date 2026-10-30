# Languages, Spanish first — design

2026-10-30 · Neal Thompson (decisions) · written up by Claude

Status: decided 2026-10-30 (§10). Research: `docs/superpowers/spikes/2026-10-30-language-research.md`. Roadmap row L0. Decisions so far (Neal, 2026-10-30): the app in Spanish and Spanish knowledge are built together and both are done before Spanish ships; the base is neutral Latin American Spanish; work proceeds now until it needs the phone; Spanish voice training is planned but not started.

## 1. Why

Granger's promise, "The world's knowledge. Yours to keep.", reaches only English speakers today. Spanish is the second most spoken first language, and its knowledge base is strong offline: every Spanish Wikimedia ZIM has a full-text index, and there are 922 Spanish books on Project Gutenberg and about 565 Spanish LibriVox recordings. The work done for Spanish (text out of the code, a language on every catalog item, a language layer in Research) is the same work every later language needs, so the second language costs the most and each one after it costs less.

## 2. Goals, non-goals, anti-goals

**Goals**
- Every screen, notification, error and widget reads in Spanish when the app's language is Spanish. The language follows the phone by default and can be set in the app.
- A Spanish speaker can build a Spanish library: Spanish packs in Get more, Spanish tiers in onboarding and Readiness, Spanish Classics, and archive.org and LibriVox filtered to Spanish.
- Ask answers a Spanish question in Spanish, from Spanish and English sources, with the same citations, decline behaviour and medical safety as in English, measured on a Spanish eval set against the same pass bar.
- Read-aloud reads Spanish text in a Spanish voice: the phone's own voice until a Granger Spanish voice ships (L5).
- Define looks words up in the Wiktionary of the book's language, and On this day works on Spanish Wikipedia.
- A bilingual user can hold both: English packs with a Spanish app, or the reverse, and Search and Ask cover both.

**Non-goals (this round)**
- Machine translation of content. A Spanish speaker reading an English pack reads English.
- Languages beyond Spanish. The design must make a third language mostly a data task (strings, catalog rows, word lists, an eval set), but nothing beyond Spanish is built.
- Right-to-left layout.
- A Granger Spanish voice. That's L5, planned but not started (§8).
- Castilian-specific copy. No `values-es-rES` overrides unless a walkthrough finds a string that reads wrong in Spain.

**Anti-goals: failures even if it technically works**
- **Half a translation.** No English string left on a Spanish screen. A test fails the build if a user-facing literal appears in Compose code.
- **Weaker medical safety in Spanish.** The medical banner and the personal-medical detection catch Spanish questions as well as English ones, checked against a test list before Spanish Ask ships.
- **A Spanish user steered into English.** Onboarding, Home feeds, Browse's encyclopedia and Readiness's tiers don't default to English packs when the user's language is Spanish.
- **Concatenated grammar.** No sentence built by gluing fragments ("Random from " + name, noun + "s"). Whole sentences with placeholders, and plurals through plurals resources.
- **Losing anything on a language switch.** Packs, books, places, chats and settings are unaffected; only text and defaults change.

## 3. Two languages per user

- **The app's language** is the language of the UI. It follows the phone, and Settings → Language can set it to English or Español.
- **Knowledge languages** are the languages of the library. They default to the app's language. Onboarding asks only when the phone has more than one language ("Also English?"), and Settings → Language can add or remove them. They decide which tiers Readiness measures, which packs Get more and onboarding lead with, and which Classics and source filters are on by default. They never hide something already on the phone.

Ask answers in the question's language, whatever the app's language is (§6.2).

## 4. The app in Spanish

### 4.1 Strings out of Compose (L1)

- Every user-facing literal moves to `res/values/strings.xml`, in `app/` and in each `core/` module that shows text (player notifications, download errors, speech, archive.org shelf titles). It's read with `stringResource` in Compose and `context.getString` elsewhere.
- About 700–900 strings. Interpolated text becomes whole-sentence placeholders. The 19 hand-built plurals become `<plurals>`.
- Dates and numbers use the current locale: month names in `DayPage`, decimals in `Bytes`, and joined date phrases in Readiness.
- Copy held in constants and models (onboarding blurbs, the medical banner, category names) becomes string ids.
- A lint check fails on new `Text("…")`, `contentDescription = "…"` and named-argument string literals in `app/` and `core/`. Test code and debug-only screens are exempt.
- No visible change: L1's walkthrough is the English app looking exactly as before.

### 4.2 The Spanish text (L2)

- `values-es/strings.xml` in neutral Latin American Spanish: *ustedes*, never *vosotros*; *tú* for the user, matching the plain, friendly English voice.
- A glossary fixes the names first, so every screen uses the same word. Proposals, for Neal to approve:
  - Home → Inicio
  - Library → Biblioteca
  - Browse → Explorar
  - Ask → Preguntar
  - Listen → Escuchar
  - Search → Buscar
  - Readiness → Preparación
  - Get more → Conseguir más
  - Supporter → Colaborador
  - Granger Classics → Clásicos Granger
  - The tagline: "El conocimiento del mundo. Tuyo para siempre."
- Claude drafts the translation. **A native speaker reviews it before friends see it** (open question §10).
- Catalog copy (domain titles, use-case blurbs, preset names) is translated in the catalog (§5.1), not in `strings.xml`.

### 4.3 The language setting

- `minSdk` is 29 and `MainActivity` is a `FragmentActivity`, not AppCompat.
- On Android 13+ the setting uses the platform's per-app language (`LocaleManager`). A `locales_config.xml` from `generateLocaleConfig` lists the languages in the system's app-language screen too.
- On Android 10–12 the app stores the choice and applies it in `attachBaseContext` for the activity, the services (player, downloads) and the widget. This avoids moving to AppCompat; the L2 plan checks whether AppCompat's `setApplicationLocales` is the smaller change.
- A change recreates the activity; nothing else is touched.

## 5. Spanish knowledge (L3)

### 5.1 The catalog learns languages

- **One code style.** `language` is BCP-47 (`en`, `es`) on packs, books, voices, models, presets and catalog copy. Kiwix's `eng` and `spa` are mapped in `catalog_gen.py`. The app normalises old values on parse, so a cached old catalog still works.
- **Readiness domains per language.** Each domain keeps one id and one icon. Its `essential` and `comprehensive` lists become keyed by language: `{"en": [...], "es": [...]}`. A domain with no list for a language counts as empty there and says so ("No Spanish packs for this area yet").
- **Copy per language.** Titles and blurbs carry `title` (English, so older builds still read it) plus `i18n: {"es": {"title": ..., "blurb": ...}}`. The app shows the app language's copy and falls back to English.
- **Presets and onboarding use cases** are keyed by language in the same way. The size cap is checked again with `spa` added: about 74 entries once TED is excluded, so far under 16 MB.
- `formatVersion` bumps. The catalog is re-signed.

### 5.2 Kiwix

- `curated.json` languages become `["eng", "spa"]`, and the family regexes already match any language.
- `ifixit_es` is treated as `ifixit_en_all` is (non-commercial flag, in Making; F1's policy check decides both). Excluded: `khanacademy_es` and TED (Neal, 2026-10-30; L3 research §7). `ecured_es` and `marxists.org_es` stay out until their licences are checked.
- The Spanish tiers are calibrated the way H4 calibrated English. There's no Spanish Top 1M, so:
  - **Efficient** is built around `wikipedia_es_top` (pictures by default, 4.07 GB) or nopic (1.21 GB) with Save space.
  - **Comprehensive** is built around `wikipedia_es_all` maxi (36.5 GB) or nopic (13.3 GB).
- **Medicine** is `wikipedia_es_medicine` plus `medlineplus.gov_es_all`, since there's no Spanish mdwiki. Preparedness adds `ready.gov_es`. The other domains take Wiktionary, Wikivoyage, Wikisource, Wikibooks, Vikidia, Stack Overflow en español and the topic Wikipedias.
- The L3 plan fixes the lists with sizes, and Neal signs them off.

### 5.3 Books and audio

- **Clásicos Granger:** the pack builder takes a Spanish manifest (`manifest.py` stops rejecting non-English works; `language` is read from the manifest, not hardcoded). The Spanish Gutenberg works are curated the way the 347 English ones were, published as `gc-es-*` books in their own release, and filed as their own collection, Clásicos Granger (§10).
- **archive.org:** a `language:(spa OR Spanish)` clause when the user's knowledge languages include Spanish, with Spanish subject words on the shelves. The 1930 rule is unchanged.
- **LibriVox:** filtered on each item's `language` field client-side, because the API ignores a language parameter. Leading articles (*el, la, los, las, un, una*) are stripped for sorting.
- `TextToEpub` writes the book's detected or declared language, not `en`.

### 5.4 Home, Browse, Define

- **On this day** knows each Wikipedia's day-page title and section ids: Spanish uses "22 de octubre" and *Acontecimientos / Nacimientos / Fallecimientos*. It draws from the encyclopedia in the user's first knowledge language.
- **Browse and Home** pick the encyclopedia by language, not by English ids. Feed labels are translated.
- **Define** uses the Wiktionary matching the book's or page's language, falling back to any Wiktionary with a note.
- `PageTitles`, `IndexPages` and `Filing` lose their `_en` assumptions.

## 6. Research in Spanish (L4)

### 6.1 The eval comes first

- A Spanish eval set comes first: about 100 tuning questions and 50 held-out, over Spanish packs and books, written and graded the way R2's held-out set is (a separate subagent grades).
- A mixed set (Spanish questions over English sources, and the reverse) of about 30 questions checks the bilingual case.
- The pass bar is the English one (spec §8 of the research-library spec). No prompt changes until there's a baseline.
- Spanish dense vectors are built on the Mac with `tools/vectors` for the Spanish catalog packs and Classics, the same as R1.

### 6.2 A language layer

- A `ResearchLanguage` in `:core:research` holds what's English today:
  - stopwords and possessives
  - medical and personal-medical terms
  - citation phrasing
  - follow-up pronouns
  - media cues
  - fiction weighting words
  - entity openers
  - `YearFacts`' months, eras ("a. C.", "d. C."), ranges ("de … a", "entre … y") and event verbs
  - sentence breaks
- English is today's lists unchanged. Spanish is new.
- **The question's language** is detected cheaply from stopword overlap. A question that mixes languages counts as both for keyword search. The detected language picks the word lists and the answer language.
- **Prompts:** first test English instructions with "Answer in Spanish" against fully Spanish prompts on the eval, and keep the better one. The decline token stays an internal English token, never shown.
- **The medical layer is safety work:** its Spanish lists are written from the English ones and a test list of Spanish questions (common symptoms, drugs, dosages, "me duele…", "mi hijo tiene…"), and must catch every one before Spanish Ask is on.
- Chars per token is re-measured for Spanish.

## 7. The voice in Spanish

- **Read-aloud picks a voice by the text's language.** It uses the book's or pack's language, falls back to the app's, and picks the phone's own Spanish voice until a Granger one exists.
- `SherpaTts` takes `lang` from the voice; `Sentences` and `Utterance` pass the text's locale.
- `SpokenText` becomes per language. Spanish needs years (*mil novecientos noventa y nueve*), decades (*los años veinte*), units, *ca.*, and ordinals for centuries (*siglo XIX*).
- **Speech input (A1)** asks the recognizer for the question's expected language: the app's language, with the knowledge languages offered.

## 8. The Spanish voice (L5, planned, not started)

No off-the-shelf medium Spanish Piper voice is licence-clean: every one is fine-tuned from `lessac` (research-only) or `ryan` (non-commercial). So Granger trains its own:

- **Data:**
  - davefx (CC0, Castilian)
  - MLS Spanish (CC BY 4.0, many speakers; a clean multi-speaker base)
  - SHARVARD (CC BY 3.0)
  - OpenSLR 61 (CC BY-SA 4.0, Argentine)
  - Ald (Unlicense, Mexican)
  - carlfm (public domain)
- **Route A, V3's:**
  1. Train a clean Spanish base from scratch on MLS.
  2. Fine-tune single-speaker voices from it.
  3. Prefer a Latin American speaker for the neutral base.
- **Route B, V4's:** a Qwen3-TTS teacher distilled into Piper with `es-419` phonemes, if Qwen3-TTS's Spanish quality and licence check out.
- **Both routes:** the Pixel 6 speed check and Neal's listening session, as V3 and V4 had.
- **When:** after V4 finishes with the Mac's GPU time, and when Neal says go.

## 9. Plans and the phone

| Plan | Delivers | Needs the phone |
| --- | --- | --- |
| L1 | Strings out of Compose, plurals, locale formats, the lint check | Only an English walkthrough at the end |
| L2 | `values-es`, the glossary, the language setting, the native-speaker review | Walkthrough in Spanish |
| L3 | Catalog languages, Spanish Kiwix tiers, Clásicos Granger, sources, Home/Browse/Define | Walkthrough; Spanish downloads |
| L4 | Spanish eval set and vectors, `ResearchLanguage`, prompts, the medical layer, the pass bar | Phone eval runs |
| L5 | The Spanish voice | Speed check, listening |

Desk and Mac work runs now: all of L1's code, L2's translation, L3's catalog and manifests, L4's eval set, word lists and vectors. Phone steps wait until the other work using the Pixel 6 (the bug pass, GB, A0, I0) is finished with it.

**Ordering note:** L1 touches about 155 files. The bug pass and GB are open branches that edit many of the same screens, so L1 lands after they merge, or is rebased module by module. L1 can start with `core/` and the screens those branches don't touch.

**Shipping:** Spanish ships when L1–L4 pass, with the phone's own voice. L5 follows when it's ready.

## 10. Decisions (Neal, 2026-10-30)

1. **Translation review:** Claude drafts the Spanish. A small group of Spanish-speaking friends reviews it before anyone else sees it. Until then, no one outside Neal and Claude sees the Spanish.
2. **Glossary:** the names in §4.2 stand for now (Preparación, Colaborador, "El conocimiento del mundo. Tuyo para siempre."). The friend review may change them.
3. **Spanish Classics are their own collection,** Clásicos Granger, beside Granger Classics, not a section of it. Neal is learning Spanish and wants to learn with them, so §5.3 changes:
   - Clásicos Granger is a collection of its own in the Library and Get more.
   - Define in a Spanish book uses the reader's own Wiktionary first: for an English-speaking learner that's English Wiktionary, which glosses Spanish words in English. Spanish Wiktionary is second. The L3 plan checks how well the English Wiktionary ZIM covers Spanish headwords.
   - Learner tools beyond Define (parallel texts, graded shelves) are later ideas, not this round.
4. **Bilingual setup:** do what's typical. Onboarding offers a second knowledge language only when the phone's language list has more than one (§3, as proposed). Settings → Language is where anyone else adds one.
