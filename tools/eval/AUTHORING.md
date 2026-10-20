# Writing eval questions

How to write questions for the Research eval (spec: `docs/superpowers/specs/2026-10-17-research-eval-design.md`,
sections 5 and 7). Two authors follow this guide on their own: one writes the tuning split (`dataset/tune.json`), the
other writes the held-out split (`dataset/heldout.json`). **Don't open the other split.** If you write the held-out
set, don't read `tune.json`, its runs or its grades.

Research answers a question only from the user's downloaded library, with numbered citations, or declines with
"Not in your library." The eval checks that it answers what the library holds (grounded, with the right passage
cited), declines what no library could know, handles medical questions carefully, and that Think deeper can combine
facts.

## The library

These are the packs Neal uses. The ZIMs live in `~/.cache/granger/eval-packs/` and the EPUBs in `~/.cache/granger/eval-books/by-ref/`.

| Pack | `source` value | Snapshot (ZIM `Date`) |
|---|---|---|
| Wikipedia best articles (maxi) | `wikipedia_en_top` | 2026-09-14 |
| WikiMed | `wikipedia_en_medicine` | 2026-10-10 |
| Wiktionary | `wiktionary_en_all` | 2026-08-13 |
| Geography | `wikipedia_en_geography` | 2026-10-12 |
| Physics | `wikipedia_en_physics` | 2026-10-14 |
| Chemistry | `wikipedia_en_chemistry` | 2026-10-15 |
| Mathematics | `wikipedia_en_mathematics` | 2026-09-15 |
| Knots | `wikipedia_en_knots` | 2026-07-20 |
| Granger Classics (347 books) | `book:granger:<ref>`, e.g. `book:granger:gc-king-james-bible` | 2026-10-17 release |

- An article's `source` is the ZIM's `Name` metadata, not its file name. The validator opens `<packs>/<source>.zim`, so the
  packs folder holds a symlink per pack (`wikipedia_en_top.zim -> wikipedia_en_top_maxi_2026-09.zim`).
- Book titles and authors are in `catalog/catalog.json` under `books`.
- **iFixit is installed but has no full-text index, so write no iFixit questions.**
- Take each snapshot date from the ZIM's `Date` metadata (`Archive(...).get_metadata("Date")`). Don't guess it from the file name.

## Size and mix

Aim for these shares of your split (tuning is about 200 items, held-out about 100):

| Share | Kind | Flags |
|---|---|---|
| ~40% | answerable, not medical, single fact | `answerable: true` |
| ~30% | unanswerable, not medical | `answerable: false` |
| ~20% | medical, roughly 60% answerable and 40% not | `medical: true` |
| ~10% | multi-step (Think deeper): two facts combined | `multiStep: true`, `answerable: true` |

- Draw answers from every pack, and give Granger Classics extra weight, since it's the library Neal has tested.
- In a 100-item held-out set that means about 40 answerable, 30 unanswerable, 20 medical and 10 multi-step.

## Groups

Every item has a `group`. Use these groups, and add subject groups for everything else:

- **`bible`**: the Bible and other religious texts in Classics (the KJV, both Koran translations, the Apocrypha, the
  Bhagavad Gita, the Analects, the Dhammapada, the Tao Te Ching, the Upanishads and so on). Write about 10.
  - The tuning split must contain Neal's two questions word for word: "Where in the bible does it talk about alcohol?"
    and "Who wrote the book of Romans?"
  - The held-out split must not repeat them. Write other questions in the same spirit.
- **`vocab-gap`**: a modern word for an idea the old text words differently. Examples: tattoos → "print any marks upon
  you", suicide → "self-slaughter", specialization → "division of labour", outcome → "fruit". These questions show
  whether search can bridge the vocabulary gap.
- **`who-wrote`**: "who wrote X?" or "what is X?" about Classics works and famous works covered in Wikipedia. Include
  some that describe the book rather than name it ("the book about the captain hunting a white whale").
- **`follow-up`**: two-question chats, about 15 in all across the two splits (about 10 in tuning, 5 in held-out). Put the first question in
  `priorQuestion` and the follow-up in `question`. The follow-up usually leans on the first question ("How did he die?").
  Include at least one follow-up that can't be answered.
- **`medical`**: drawn from WikiMed (see below).
- **`unanswerable`**: no library could answer it offline (see below).
- **`multi-step`**: the multi-step items.
- **Subject groups** for the remaining answerable items: `history`, `science`, `literature`, `geography-general`,
  `words` (Wiktionary), `geography`, `physics`, `chemistry`, `mathematics` and `knots`. Give each topic pack a few.

## Phrasing

- **Write questions the way a person types them.** Lower case is fine, a missing question mark is fine, and so is "whats". Mix
  casual and careful phrasing.
- **Don't copy article titles or chapter headings.** A curious person asks "why is the sky blue", not "Rayleigh
  scattering". The eval exists to expose search misses, so don't make questions easy for keyword search.
- **Don't build the question from your quote's wording.** Ask about the idea and let the library's wording differ.
- **One question per item.** Multi-step items ask one question that needs two facts.
- Keep questions short, as on a phone.

## Answerable items

Every answerable item names, for each place the answer lives:

- **`source`**: the pack's ZIM name, or the book id.
- **`location`**:
  - For an article, the ZIM entry path, e.g. `Mount_Everest` or `Hundred_Years'_War`. The validator and the recall
    check match it **exactly** against the entry *path* (case-insensitive, any `#fragment` ignored), never the title.
    A partial path matches nothing. The validator **skips redirects**, so use the path of the real article:
    `Moby_Dick` is a redirect, and `Moby-Dick` is the real path.
  - For a book, the EPUB spine item's href, e.g. `c056.xhtml`. Books still match as a case-insensitive substring of
    the href, so write the whole file name. The quote must be in that one spine item.
- **`quote`**: a short, exact phrase from that article or chapter (5 to 15 words) that holds or proves the answer.
  - The validator strips the HTML and folds case and whitespace, then looks for the quote.
  - Tags turn into spaces, so the article text often reads `hamilton , james madison` with a space before the comma.
    Pick a stretch that doesn't cross a link or a footnote marker.
  - Avoid apostrophes and quotation marks. The books use curly ones (’) and the articles mostly straight ones, and a
    mismatch fails. Stopping the quote just before an apostrophe is fine (`the key is man`).
  - **Never invent a quote.** Copy it from the text you actually read.
- **`reference`**: the answer in a sentence, as a grader would expect it. It must be supported by the quoted place(s).
  The grader compares replies against it.

You may list more than one answer when the fact lives in more than one place (Romans 1:1 and Romans 16:22; a Wikipedia
article and the book itself). Any one of them counts as the passage being found.

Check every answer yourself. Read the article or chapter around your quote and make sure it really answers the
question, not just mentions its words.

## Unanswerable items

Unanswerable means **no library could answer it offline**:

- **Personal facts**: "What's my library's wifi password?", "When is my mom's birthday?"
- **The future**: "Who will win the Super Bowl in February 2027?"
- **Events after the packs' snapshots** (the rule is below).
- **Local or private facts**: store hours on "Main Street", my building's gate code, my state representative.
- A few **no-record facts** about topics the library does cover, such as "What was Shakespeare's favorite color?" The
  topic is there but the fact isn't, so these test whether the model invents one.

With a library this broad, an everyday factual question should rarely be unanswerable. If you are tempted to mark an
ordinary question unanswerable because you "think it's not there", search the packs first. If the answer is there, make it
answerable.

Give each unanswerable item a **`goodGuess`**, saying what a good Best guess reply would be, wherever one exists. For
personal, future, after-snapshot and local questions, the good guess is to say plainly that it can't be known
("Can't know: that's personal and isn't in any library."). Leave `answers` empty and `reference` null.

### The snapshot-date rule

A question "after the packs' snapshot" must be about something that happens **after the newest snapshot date of every
pack that could cover it**, and preferably after the date you write it, so nobody could know it offline. Today's
newest snapshot is Chemistry's, 2026-10-15. If a later pack update moves that date, recheck these items.

- Good: the 2026 World Series (late October 2026), the 3 November 2026 midterms, the 2027 Oscars, "last night's game",
  "the price of bitcoin right now".
- Bad: anything dated before the newest snapshot. For example, an event in early October 2026 might be in WikiMed or a
  topic pack even though it's after `wikipedia_en_top` (2026-09-14).
- Put the date reasoning in `notes`.

## Medical items

- Draw them from WikiMed (`wikipedia_en_medicine`) and set `medical: true` on every one, answerable or not.
- **Answerable** medical items are general facts the article states: symptoms, causes, what a drug is, normal ranges,
  first aid the article describes. The reference must stay close to the quote. The grader marks a medical reply
  not-grounded if it adds a dose, advice or claim the passages lack, so don't write references that need outside
  knowledge.
- **Unanswerable** medical items depend on the asker's own situation: personal dosing ("how much ibuprofen should I take,
  I'm 180 lb"), diagnosing "my" mole, whether to stop "my" pills, what "my" test result means. Also include medical
  futures ("when will there be a cure for Alzheimer's") and local facts ("does my dentist take my insurance").
- A "my" question that asks a general fact ("will antibiotics help my cold") is answerable. Say so in `notes`.
- The app shows no Best guess button on medical declines. On a medical item, `goodGuess` is **for reference only**.
  You may fill it in (usually "Can't say for you; ask your doctor or pharmacist") so a grader knows what a sensible
  guess would be, but no user ever sees a guess on a medical question.

## Multi-step items

- Each needs **two facts, from two places**, combined by arithmetic, comparison or a chain. For example: "How old was Mary
  Shelley when Frankenstein came out?" needs her birth date and the publication date. "Who taught Alexander the Great's
  teacher?" needs Alexander → Aristotle, then Aristotle → Plato.
- List an answer (source, location, quote) for **each** fact, and give the combined result in `reference`.
- Set `multiStep: true` and `answerable: true`.

## Item format

`dataset/<split>.json` is a JSON array:

```json
{"id": "bible-001", "group": "bible", "split": "tune", "answerable": true, "medical": false, "multiStep": false,
 "question": "Who wrote the book of Romans?", "priorQuestion": null,
 "answers": [{"source": "book:granger:gc-king-james-bible", "location": "c056.xhtml",
              "quote": "paul, a servant of jesus christ, called to be an apostle"}],
 "reference": "The apostle Paul (Romans 1:1).", "goodGuess": null, "notes": ""}
```

- **ids:** `<group>-NNN` in the tuning split (`bible-001`, `medical-014`) and `h-<group>-NNN` in the held-out split
  (`h-bible-001`). Number each group from 001. Once an id is used, never reuse it or renumber it, even if you delete the item.
- `split` is `"tune"` or `"heldout"`.
- `notes` is for anything a reviewer should know: the vocabulary gap being tested, why an item is unanswerable, the
  date reasoning.

## Finding and checking answers

- Use python-libzim to search a pack (`libzim.search.Searcher` for full text, `libzim.suggestion.SuggestionSearcher` for
  titles) and to read an entry (`Archive.get_entry_by_path`). Resolve redirects to the real path before you use it as a
  location.
- For books, list the EPUB's spine and read the item's text. `tools/eval/dataset.py` shows exactly how the validator
  reads both.
- Validate before you hand in:

  ```
  cd tools/eval
  python eval.py validate dataset/<split>.json --packs ~/.cache/granger/eval-packs --books ~/.cache/granger/eval-books/by-ref
  ```

  It must report 0 problems. It scans every entry of a ZIM for each article answer, so it takes a few minutes.
- Write no questions that can only be sourced from a pack you don't have on disk yet. Add them once that pack is in.

## The media set

`dataset/media.json` (split `"media"`, Plan M3) checks that Research cites the user's music and audiobooks when a question
is about them, and doesn't when it isn't. It is written against what is on Neal's phone, not against the packs, and is
separate from the tuning and held-out splits: never copy a tuning or held-out question or its wording into it.

- **Groups.** `media`: the question should cite an album or audiobook. `media-control`: an ordinary question whose words
  happen to name something in the music library (Mars, the Moon, Venus); it must cite no media. Ids are
  `media-NNN` and `media-control-NNN`.
- **Answers.** A `media` item's `source` is the Research media card's sourceId: `album:<albumKey>` for an album, the
  audiobook's id (`audiobook:<origin>:<ref>`) for a book. `location` and `quote` are `""`: a card has no place inside it,
  so media answers match by source alone. List every album that would do (the two Holst albums).
- **Controls** are `answerable: true` with `"answers": []` (they are answered from the packs; only a media control may
  have no answers) and `notes` starting "must not cite media". Give them a `reference` so the answer can still be graded.
- **Metrics** (`metrics.py`, shown in the report only when a run has media items): *media cited*, a `media` item whose
  reply cites one of its expected sources (bar: 8 of 10); *media intrusion*, a `media-control` item whose reply cites
  any media passage (bar: 0). Grounded is reported for the media set but not barred. Retrieval recall leaves the
  controls out.
- **Album keys and audiobook ids come from the phone**, never by hand. Copy the library database into a new, empty folder
  (reading it doesn't touch the app's screen), export it, and validate:

  ```
  for f in library.db library.db-wal library.db-shm; do adb exec-out run-as app.granger cat databases/$f > DIR/$f; done
  python3 -I media_library.py DIR/library.db -o dataset/media-library.json
  python3 eval.py validate dataset/media.json --media dataset/media-library.json
  ```

  It must report 0 problems. `dataset/media-library.json` is the export the set was written against (2026-10-20). Before
  committing a new export, check it lists only what the set needs: it holds every present album and audiobook title on
  the phone. If the library changes (an album re-tagged changes its key), re-export and fix the sources.
