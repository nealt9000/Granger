# Plan GC2: Granger Classics, round 2 — Implementation Plan

> **For agentic workers:** steps use checkbox (`- [ ]`) syntax. Build in worktree `.claude/worktrees/gc2` (branch `gc2-classics-round`); parallel tasks get their own worktrees and merge into `gc2-classics-round`.

**Goal:** new releases `classics-<date>` and `clasicos-es-<date>` in which:
- covers sit centred, clear of the reader's chrome, and Clásicos wear the owl;
- plays read as plays (speaker, speech, stage direction, act/scene headings) and text-converter books (Analects, Sale's Koran) have their structure back;
- Homer uses Greek names, and a few dated translations are swapped or joined by plainer ones;
- the obvious gaps are filled (Neal's list plus the audit's top picks).

Neal, 2026-11-09: covers at the very top under the mini-player and top-aligned in full screen (Clásicos too, plus old branding); "still heavily riddled with awkward formatting" (Analects, Shakespeare, Aristophanes); older English tedious, Odyssey uses Latin names; missing Luther, founding documents, Hemingway, Faulkner, Christie, Nancy Drew 1–4.

**Settled with Neal, 2026-11-09:**
- **Homer:** Butler's prose with Latin names mapped to Greek, with an editor's note; **replaces** #1727 Odyssey and #2199 Iliad.
- **Translations:** Oldfather's Epictetus (Wikisource) **replaces** Long #10661; Peters's Nicomachean Ethics (Standard Ebooks) **replaces** Chase #8438; Tatlock & MacKaye's modern Chaucer #79952 **added and shown first**, Middle English kept; Chrystal's Meditations #55317 **added** beside Long; T. C. Williams's Aeneid **added**.
- **Additions:** Neal's list plus the audit's ★ top picks (about 65 books).
- **New importers:** Standard Ebooks and Wikisource. Not Faded Page (so no In Our Time, Torrents of Spring, Mosquitoes this round).

**Research** (committed with this plan, `docs/superpowers/spikes/2026-11-09-classics-round-2/`):
- `formatting-audit.md`: causes, counts, the six drama source patterns, fixes F1–F10, per-work hints (§6), checker design.
- `translations-research.md`: options per work with opening samples and "thou" rates; the Homer name map.
- `missing-works.md`: every id checked on gutenberg.org 2026-11-09; traps (Kafka #5200/#7849 copyrighted, Siddhartha #2500 modern, Bambi #63849, Pensées #18269).

**Out of this plan:** Faded Page; Pickthall; the Psalm verse-number gutter; any app change except the cover check (Task 1 is EPUB-side).

---

## Task 1: Covers centred (both collections)

- [ ] `epubpack.py`: the cover page fills one page and centres the image both ways (flex column, `height: 100vh`, `max-height` a little under the page so Readium never spills a second column). Test: cover page markup.
- [ ] Render the cover page in `verse_check` at 390×844 with the reader chrome's top band (status + mini-player + top bar ≈ 150 dp) and bottom band marked; the image must clear both. Screenshot before/after.
- [ ] Phone check folded into Task 13 (open three books with the mini-player up; then full screen).

## Task 2: Clásicos on the owl

- [ ] `covers.py`: grounds for the ten Spanish section ids (map each to the nearest English ground: escritura→scripture, ensayo/historia→philosophy, poesia→poetry, teatro→drama, novelas/cuentos/universales→novels, lecturas/infantil → a new learner ground, picked with Neal from a board).
- [ ] Board of 10 Spanish covers for Neal (one per section) before the full build.

## Task 3: Drama normaliser (`drama.py`) — formatting-audit F1

- [ ] Fixtures for the six source patterns (audit §2 table) from real books.
- [ ] Cast list per play from repeating labels; mark speeches `<div class="gd-speech"><p class="gd-who">HAMLET</p>…</div>`, stage directions `gd-sd` (block) / `gd-sdi` (inline); act/scene labels promoted to `h2`/`h3` before the chapter split.
- [ ] `<pre>` plays split into speeches before `verse.py`; the `_GLOSS` rule off inside drama.
- [ ] CSS (audit §4): speaker bold small caps at text size, directions italic and indented; Medea's look as the target.
- [ ] Per-work hints in the manifest (`drama`, `speakerCase`, `speakerStop`) per audit §6.

## Task 4: Text-converter repair (`textfix.py`) — F3

- [ ] Restore paragraph starts hidden as newline + 8 spaces; join paragraphs split at printed page breaks.
- [ ] `labels: "promote"`: "CHAP. N." → chapter heading, "1." → small verse number at the head of its passage. On for Analects and Tao Te Ching; paragraph repair for Koran (Sale).

## Task 5: Builder clean-up — F2, F4–F10

- [ ] F2 rejoin hard-wrapped prose in `<pre>`; convert `<pre>` in every section (Montaigne, Lucretius; Lucretius `verse: true`).
- [ ] F4 KJV-style verse refs on their own lines (`verseRefs: "split"`: KJV, Apocrypha, Douay).
- [ ] F5 a heading's preceding anchors move with it in the chapter split.
- [ ] F6 spacer/anchor-only blocks dropped; F7 `tei-pb` dropped; F8 footnote markers styled (Korans' glued markers, Norton Dante, Pilgrim's Progress `{N}`); F9 nav titles (no leading "Part 1", long headings shortened); F10 drop List of Illustrations when images are gone.

## Task 6: `drama_check` + regression

- [ ] Static measures (audit §5): speech coverage ≥ 98%, 0 gloss misfires, 0 act/scene labels outside headings, 0 stranded anchors, 0 hidden paragraph starts. Render checks: speaker weight, direction italics; `verse_check` still passes.
- [ ] Screenshot pairs for Neal: Hamlet, Frogs, Clouds, Duchess of Malfi, Gilbert & Sullivan, Doll's House, Analects, KJV.

## Task 7: Standard Ebooks importer

- [ ] `source: "standardebooks"` with `se: "<author>/<title>[/<translator>]"`; fetch the compatible EPUB, take its XHTML chapters and semantics (`z3998:persona`, `z3998:stage-direction` map to the Task 3 classes); licence CC0 recorded; rights guard accepts CC0 with first publication ≤ 1930.
- [ ] Reject SE placeholder pages (no download link).

## Task 8: Wikisource importer

- [ ] `source: "wikisource"` with the page title; fetch the rendered HTML of each subpage via the API, drop page-number spans and headers/notes templates; require PD-US (≤ 1930) or US-government licence template.
- [ ] Used for: Oldfather Epictetus, Williams Aeneid, the founding-document parts not on Gutenberg.

## Task 9: Greek-named Butler

- [ ] A `rename` transform: whole capitalised words, straight and curly apostrophes and possessives; Butler's preface and notes untouched; map from `translations-research.md` (Ulysses→Odysseus, Jove→Zeus, Minerva→Athena, Pallas Minerva→Pallas Athena, Juno→Hera, Venus→Aphrodite, Mars→Ares, Mercury→Hermes, Neptune→Poseidon, Vulcan→Hephaestus, Diana→Artemis, Saturn→Cronus, Ceres→Demeter, Proserpine→Persephone, Bacchus→Dionysus, Latona→Leto, Hercules→Heracles, plus any found in a word-frequency pass).
- [ ] Editor's note in the colophon and front: "Samuel Butler's translation, with the gods' and heroes' names given in their Greek forms." Translator line "Samuel Butler (Greek names)".
- [ ] Check: zero remaining Latin forms outside the preface; spot-read 20 lines with Neal.

## Task 10: Manifest — translations and additions

- [ ] Replacements (same slug, so reading places carry where they can): odyssey, iliad, discourses-and-encheiridion (Oldfather), nicomachean-ethics (Peters).
- [ ] Added: canterbury-tales-modern (shown first), meditations-chrystal, aeneid-williams.
- [ ] Neal's list: Luther Vol. II #34904, 95 Theses #274, Large Catechism #1722; *Founding Documents of the United States* (compiled: Declaration #1, Articles of Confederation (WS), Constitution #5, Bill of Rights #2, Amendments 11–27 (WS/NARA), Washington's Farewell Address (WS)); Lincoln #14721; Franklin #20203; Hemingway #67138 #69683 #75201; Faulkner #75170, As I Lay Dying and Soldiers' Pay (SE); Christie #69087 #863 #1155 #58866 #61262 #78084; Nancy Drew #77924 #77602 #78704 #77873.
- [ ] Audit top picks (`missing-works.md` §7 ★): Book of Mormon #17, Book of the Dead #7145; Herodotus #2707+#2456, Thucydides #7142, Plutarch #674, City of God #45304+#45305, Communist Manifesto #61, Douglass #23, Du Bois #408, Washington #2376, Tocqueville #815+#816, Seven Pillars #79877; Waste Land #1321, Prufrock #1459, Venus and Adonis #1045, Lucrece #1505, Poetic Edda #73533; Chekhov #7986, Prometheus Bound #27458, Saint Joan #76323, Pirandello #42148, R.U.R. #59112; Woolf To the Lighthouse + Orlando (SE), A Room of One's Own (WS), Kafka The Castle (SE), Magic Mountain (SE), All Quiet #75011, and the rest of the ★ list there (children's: Pooh #67098, Oz #55, Pinocchio #500, Heidi #1448, Just So Stories #2781; Passage to India #61221, Steppenwolf #75756, Death Comes for the Archbishop #69730, Bridge of San Luis Rey #69768; Tolstoy short fiction (SE), Lady with the Dog #13415).
- [ ] Re-check each new id's RDF rights at build (existing guard) and the SE/WS ≤ 1930 rule.
- [ ] New section? Children's books currently sit in novels; keep them there unless Neal asks.

## Task 11: Spanish quick adds (optional, ask Neal)

- [ ] Fuente Ovejuna #60198, Facundo #33267 into `manifest_es.json`.

## Task 12: Full build + vectors

- [ ] Build both collections; epubcheck 0/0; `verse_check` and `drama_check` pass; size and count report.
- [ ] Rebuild Classics `.gvec` for changed + new books (Mac, `~/.venvs/granger-vectors`; GPU shared, ask first).

## Task 13: Release, catalog, phone

- [ ] Neal uploads `classics-<date>`, `clasicos-es-<date>` and vectors; catalog regen, check-links, re-sign (Neal's OK).
- [ ] Phone walkthrough (check `lastUpdateTime` first): covers with mini-player up and in full screen; Hamlet, Frogs, Analects, Odyssey, a Christie, a Clásico.
