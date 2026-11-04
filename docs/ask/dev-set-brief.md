# Brief: the Ask development set (`dev`), written blind

Date: 2026-11-04 · For a separate author session, not the Ask tuning session. Neal's decision (2026-11-04).

## Why

Ask's tuning split (126 questions) has been tuned against for weeks and now overstates the app by 13 points
grounded. The held-out set is blind and can be scored once more only. We need a third set that the tuning work
can be **scored on freely** but was **not written with any knowledge of what Ask gets wrong**, so that a change
that holds on it is a change by kind, not a fix for named items.

## Blindness for the author

Do not open, grep or summarise any of these: `tools/eval/dataset/tune.json`, `heldout.json`, `heldout2.json`,
`heldout2-own.json`, `tune-dates.json`, anything under `tools/eval/runs/`, `tools/eval/grades/`, `tools/eval/sets/`,
`tools/eval/experiments/`, and no plan, spec or note under `docs/` about Ask's results (`docs/ask/`,
`docs/superpowers/plans/*plan-r*`, `*research*`). Read only this brief, `tools/eval/AUTHORING.md`,
`tools/eval/dataset.py` (to see how answers are matched) and the packs and books themselves. If you use subagents,
brief them the same way. The point is that the questions come from the library, not from Ask.

## What to write

Follow `tools/eval/AUTHORING.md` (phrasing, answerable items, unanswerable items, medical, multi-step, quotes,
validation), with these overrides:

- **Files:** `tools/eval/dataset/dev.json` (a JSON array) and `tools/eval/dataset/dev-own.json`.
- **`split`:** `"dev"`. **ids:** `d-<group>-NNN`, numbered from 001 per group.
- **Every item carries a `domain`** as well as a `group` (the AUTHORING guide predates domains). Item shape:

  ```json
  {"id": "d-bible-001", "group": "bible", "domain": "classics", "split": "dev", "answerable": true,
   "medical": false, "multiStep": false, "question": "...", "priorQuestion": null,
   "answers": [{"source": "...", "location": "...", "quote": "..."}],
   "reference": "...", "goodGuess": null, "notes": ""}
  ```

- **Size and mix.** About 150 answerable and 60 unanswerable in `dev.json`, 10 answerable own-book items in
  `dev-own.json`. Answerable per domain, with the groups each domain uses:

  | Domain | Answerable | Groups (answerable) | Unanswerable |
  |---|---|---|---|
  | classics | 44 | bible 10, literature 8, vocab-gap 8, who-wrote 8, follow-up 5, multi-step 5 | 5 (incl. 1 follow-up) |
  | medicine | 20 | medical 18, multi-step 2 | 13 (all `medical: true`) |
  | food | 12 | cooking 10, multi-step 2 | 4 |
  | making | 12 | repair 6, knots 4, multi-step 2 | 4 |
  | maps | 12 | travel 10, multi-step 2 | 8 |
  | reference | 12 | history 4, science 3, literature 2, geography-general 1, multi-step 2 | 16 |
  | science | 12 | physics 4, chemistry 4, mathematics 2, multi-step 2 | 4 |
  | technology | 12 | programming 10, multi-step 2 | 4 |
  | thought | 12 | history 5, philosophy 5, multi-step 2 | 3 |
  | own-book (`dev-own.json`) | 10 | own-book 10 | 0 |

  Unanswerable items use group `unanswerable` (medicine's use `medical`), with the kinds AUTHORING lists spread
  across domains: personal, future, after-snapshot, local, and a few no-record facts on a topic the library covers.
  Follow-ups: 5 answerable and 1 unanswerable in classics, as AUTHORING describes.
- **Classics** draws on 25 or more different books across the 347 (`catalog/catalog.json`; EPUBs in
  `~/.cache/granger/eval-books/by-ref/`), not only the most-read dozen. Ask things a reader of that book would
  ask: plot, who said what, where a phrase comes from, what a chapter is about, the vocabulary-gap questions.
- **Own books** (`dev-own.json`, domain and group `own-book`): the three EPUBs in `~/.cache/granger/eval-books/own/`:
  `pg55.epub` (The Wonderful Wizard of Oz), `pg23.epub` (Narrative of the Life of Frederick Douglass), `pg201.epub`
  (Flatland). `source` is `book:import:<stem>` (`book:import:pg55`), `location` the spine item's href. The validator
  only resolves `book:granger:` ids, so check these quotes by hand against the spine item's text and say so in `notes`.
- **Newest snapshot is foss.cooking's, 2026-11-02.** After-snapshot items must be about things after that date,
  preferably after 2026-11-04. Put the date reasoning in `notes`.
- **English only.** Leave the `_es_` packs alone.

## The library (the packs to draw from)

The ZIMs are symlinks in `~/.cache/granger/eval-packs/`; `source` is the ZIM's `Name` metadata (read it, as
AUTHORING says; it usually equals the link name without `.zim`). Read each `Date` from metadata.

| Domain | Packs (link name) |
|---|---|
| classics | the Granger Classics EPUBs (`book:granger:<ref>`) |
| medicine | `wikipedia_en_medicine` |
| food | `based.cooking_en_all`, `foss.cooking_en_all`, `appropedia_en_all` |
| making | `ifixit_en_all`, `appropedia_en_all`, `wikipedia_en_knots` |
| maps | `wikivoyage_en_all` |
| reference | `wikipedia_en_top`, `wikipedia_en_history`, `wiktionary_en_all` (if present) |
| science | `wikipedia_en_physics`, `wikipedia_en_chemistry`, `wikipedia_en_mathematics` |
| technology | `devdocs_en_*` (bash, c, git, http, javascript, python, sqlite), `cs.stackexchange.com_en_all`, `unix.stackexchange.com_en_all`, `softwareengineering.stackexchange.com_en_all`, `reverseengineering.stackexchange.com_en_all` |
| thought | `internet-encyclopedia-philosophy_en_all`, `wikipedia_en_history` |

A pack with no full-text index can still take questions (the app also searches by meaning), but keep iFixit to
about half of `repair`. If a pack listed here is missing on disk, write no questions for it and say so in the handback.

## Tooling

- Python with libzim: `~/.venvs/granger-pack/bin/python`.
- Validate until it reports 0 problems (it takes minutes):

  ```
  cd tools/eval
  ~/.venvs/granger-pack/bin/python eval.py validate dataset/dev.json --packs ~/.cache/granger/eval-packs --books ~/.cache/granger/eval-books/by-ref
  ~/.venvs/granger-pack/bin/python eval.py validate dataset/dev-own.json --packs ~/.cache/granger/eval-packs --books ~/.cache/granger/eval-books/own
  ```

- Work in `tools/eval/dataset/` only; change nothing else in the repo. Don't commit.

## Hand back

A short note with: counts per domain and group (answerable, unanswerable, medical, multi-step, follow-ups), the
number of distinct Classics books used, the validator's result for each file, and any pack you couldn't use. The
note may describe kinds of questions; it should not be needed to list them, the files are the deliverable. Neal
reviews the set before it is first scored; fixes go back through the author.
