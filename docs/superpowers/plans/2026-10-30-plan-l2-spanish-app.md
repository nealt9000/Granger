# Plan L2: The app in Spanish — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** with the phone or the app set to Spanish, every screen reads in neutral Latin American Spanish. The English app is unchanged.

Spec: `docs/superpowers/specs/2026-10-30-languages-design.md` §4.2–§4.3, §10. Builds on L1 (`2026-10-30-plan-l1-strings.md`). Roadmap row L0.

**Settled with Neal, 2026-10-30:**
- Neutral Latin American Spanish.
- Claude drafts the translation, and a small group of Spanish-speaking friends reviews it before anyone else sees it.
- The glossary below stands for now.

**Out of this plan:**
- Catalog copy and knowledge languages, Clásicos Granger, Define and On this day per language (L3).
- Research in Spanish (L4).
- The Spanish voice (L5).
- Speech input: the A0/A1 workstream owns it, so leave its code alone.

**Tech Stack:** unchanged. No schema or catalog change.
- **Build env:** `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
- **Worktree:** `.claude/worktrees/l2` (branch `l2-spanish`, off `l1-strings`). It merges after L1, and picks up L1's Task 8 strings when `l1-strings` moves.

## Glossary

Use these words everywhere. If a string needs a different word, keep the glossary word and note why in the XML comment.

| English | Español | Notes |
| --- | --- | --- |
| Granger | Granger | Brand, never translated |
| Home | Inicio | Rail tool |
| Library | Biblioteca | |
| Browse | Explorar | Rail tool |
| Ask | Preguntar | Rail tool; "Ask (Preview)" is "Preguntar (versión preliminar)" |
| Listen | Escuchar | Rail tool |
| Search | Buscar | Verb on buttons; "Búsqueda" as a noun heading |
| Readiness | Preparación | |
| Get more | Conseguir más | |
| Sources | Fuentes | |
| Downloads | Descargas | |
| Settings | Ajustes | Android's own Spanish word |
| Supporter | Colaborador | |
| Granger Classics | Clásicos Granger | Before L3 this names the English collection, so "Clásicos Granger" here means the collection the user sees |
| pack | paquete | A Kiwix ZIM |
| book / e-book | libro / libro electrónico | |
| audiobook | audiolibro | |
| read aloud | leer en voz alta | |
| voice | voz | |
| bookmark | marcador | |
| highlight | resaltado | Verb: resaltar |
| note | nota | |
| chapter | capítulo | |
| Now playing | Reproduciendo | |
| Retry | Reintentar | |
| Remove | Quitar | Delete is Eliminar |
| Wi-Fi / mobile data | Wi-Fi / datos móviles | |
| Preview | Versión preliminar | |
| Tagline | "El conocimiento del mundo. Tuyo para siempre." | |

**Voice:**
- Address the user with *tú*, matching the plain, friendly English: "Tu biblioteca", "Toca para…".
- No *vosotros*, and no regionalisms. Prefer words understood from Mexico to Argentina: *teléfono* for the phone (not *celular* or *móvil*).
- Sentence case, as in English.
- Keep it short. Spanish runs about 20–30% longer, and rail labels, chips and buttons have little room. Where a direct translation is long, use the shortest natural wording and note it.

## Task 1: Spanish strings (desk)

**Files:** a `values-es/` beside every `values/` that holds strings:
- `app/src/main/res/values-es/strings_<package>.xml` for each `strings_*.xml`
- `core/{downloads,player,speech,archive}/src/main/res/values-es/strings.xml`

- [x] **Step 1:** Translate every `<string>` and `<plurals>`. Skip `translatable="false"`.
  - Keep each name and every placeholder (`%1$s`, `%2$d`), in whatever order the Spanish needs.
  - Plurals give `one` and `other`. Android's Spanish rules also use `many` for large numbers: copy `other` into `many`.
  - Keep typographic marks: "…" and "·"; ¿? and ¡! where Spanish needs them.
  - Escape apostrophes and quotes as in the English files.
- [x] **Step 2:** Every string a reviewer might misread gets a short comment in the English source file (not the Spanish one), so the friend review has its context: what it labels, and how much room it has.
- [x] **Step 3:** Write `docs/superpowers/l2-review.md` for the friend review:
  - the glossary
  - how to see the app in Spanish
  - the strings Claude was least sure of, with the reason
  - the places where length forced a shorter wording
- [x] **Step 4:** Commit "L2: Spanish strings (draft for review)".

## Task 2: Completeness test

- [ ] **Step 1:** Add `SpanishStringsTest`, a JVM test that parses each `values/` strings file and its `values-es/` twin. It fails when:
  - a translatable name has no Spanish
  - a Spanish name has no English
  - the placeholders differ (same set of `%n$x`)
  - a plurals entry lacks `one` or `other`
- [ ] **Step 2:** Add a Robolectric test that resolves every string under `qualifiers = "es"` with sample args, so a bad format string fails here rather than on the phone.
- [ ] **Step 3:** Commit.

## Task 3: The language setting

- [ ] **Step 1: Locale config.** Turn on `androidResources { generateLocaleConfig = true }` and add `res/resources.properties` (`unqualifiedResLocale=en-US`). Then Android 13+'s system screen (Settings → Apps → Granger → Language) lists English and Español.
- [ ] **Step 2: `AppLanguage`** (in `settings/`), with Follow the phone (default), English and Español.
  - **API 33+:** use `LocaleManager.applicationLocales`.
  - **API 29–32:** store the choice in `SharedPreferences`, and wrap the context in `attachBaseContext` for `MainActivity` and the services that build notifications (the playback service, download workers). Read the stored value through `AppLanguage`.
  - First check whether `androidx.appcompat`'s `AppCompatDelegate.setApplicationLocales` covers `FragmentActivity` on 29–32 with less code. If it does, use it; otherwise use the wrapper.
- [ ] **Step 3: Settings → Appearance → Language.**
  - The choices show in their own language ("English", "Español"). Follow the phone shows the phone's current language.
  - A change applies at once by recreating the activity. Nothing else is reset.
- [ ] **Step 4:** Tests:
  - The choice persists.
  - Follow the phone clears the override.
  - On API 33+ the platform call is made; on API 29–32 the wrapper sets the locale.
- [ ] **Step 5:** Run the widget and the notifications under Spanish in Robolectric. Their text resolves in Spanish.
- [ ] **Step 6:** Commit.

## Task 4: Fit and formats (desk)

- [ ] **Step 1:** Run the theme shots (`-PthemeShots`) under `qualifiers = "es"` for the screens they cover. Look at each for clipped or wrapped labels: rail, chips, buttons, top bars. Shorten in Spanish, or let the layout wrap, whichever the screen allows.
- [ ] **Step 2: Formats.** Check dates, numbers and sizes in Spanish:
  - "10 ene 2025"
  - "1,5 GB" (Spanish decimal comma)
  - "hace 5 minutos" style where the app says "5 min ago"
- [ ] **Step 3:** Commit.

## Task 5: Walkthrough (needs the phone, after A0 is done with it)

- [ ] **Step 1:** Install over `app.granger`, then set Granger's language to Español.
- [ ] **Step 2:** Walk every rail tool, Settings, onboarding, a book, a wiki, Listen, Ask and a download.
  - Note any English left. Catalog copy and pack content are expected to be English until L3.
  - Note anything clipped or awkward.
- [ ] **Step 3:** Switch back to English. Everything is as before, and nothing was lost.
- [ ] **Step 4:** Hand the build and `l2-review.md` to the friend reviewers. Their changes come back as a round of string edits.
