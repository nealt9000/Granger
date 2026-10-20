# M3 checkpoint: media in Research (Plan M3, Task 10)

All runs are on the Pixel 6, with Neal's library and `--guesses none`. Grades are in `grades/grades.jsonl`, keyed by reply hash.
The held-out split was not run or opened.

## Numbers

| Run | Build | Media cited | Intrusion on controls | Unanswerable declined | Answerable grounded |
|---|---|---|---|---|---|
| m3-media-1 | `349dc97` (cap only) | 3/10 ❌ | 0/3 | — | — |
| m3-media-2 | `0d2197e` (+ lift, cues, word split) | **9/10** ✅ | **0/3** ✅ | — | 12/13 |
| m3-tune-1 | `0d2197e` | — | — | 92% (70/76) | 44% (56/126) |
| m3-media-3 | final (− "score" cue) | **9/10** ✅ | **0/3** ✅ | — | 12/13 (replies identical to m3-media-2) |
| m3-tune-2 | final, unanswerable-031 and -033 only | — | — | 031 now answered, 033 declined | — |
| **Final build, tuning split** | m3-tune-1 with m3-tune-2's two items | — | — | **91% (69/76)** ✅ | **44% (56/126)** ✅ |
| tune-5 (Plan 9, for comparison) | `bd72c3a` + `dfeed99` | — | — | 91% (69/76) | 45% (57/126) |

Bars: media cited ≥ 8/10, intrusion 0/3, tuning split ≥ 89% declined and ≥ 43% grounded. All met.

The tune-5 run files aren't on this Mac, so the comparison uses the totals in `runs/checkpoints/tuning/NOTES.md`. m3-tune-1
had 3 replies with no grade yet (bible-006, who-wrote-010, words-003). All three are not-grounded and none involve media.
The one-question drop in grounded is within run-to-run drift: the library has changed since Plan 9 (M1/M2 media, a removed collection).

## What changed and why

- **m3-media-1 missed badly (3/10).** The cards were found for 8 of 10 questions, but ranking put the encyclopedia article about
  the same album above them ("What songs are on The Dark Side of the Moon?" picked three passages of the Wikipedia article and
  declined). The cap only limits media; nothing lifted a card. Two more had no card at all: "Do I have any Holst?" (no cue word)
  and "Which album is the song Money from?" (the rewrite's keyword "money from" needs "from" in the album's text).
- **Lift** (`Ranking.liftMedia`): when the question asks about media, the finder's best media card (lexical order) goes first,
  whether or not ranking kept it. Then the cap applies as before. Non-media questions are untouched, so the tuning split can
  move only on its cue questions, of which it has 2 (the two "score" questions below).
- **Cues:** added "do I have" and "have I got" as phrases.
- **Word split** (`LibraryPassageFinder.mediaCards`): a keyword of several words that matches no work is tried word by word, stopwords left
  out. This applies to every question, but the cap of 1 and ranking still decide whether the card reaches the answer. m3-tune-1 had media in
  the answer on only those 2 questions.
- **"score" dropped from the cues** after m3-tune-1. "What was the final score of last night's NFL game?" and "…the score of
  the dodgers game" lifted *The Secret of NIMH* (genre "Score") to first place. Both still declined there. Without the card,
  unanswerable-031 answers with an unrelated Cardinals–49ers result from Wikipedia. That's the pre-media behaviour, and it
  leaves declined at tune-5's 91%. A film-score card shouldn't be what keeps a sports question declined.

## The remaining miss

- **media-007 "Who wrote Wicked?"** has no media cue. It answers from *Wicked (musical)* (Schwartz and Holzman), which is grounded and
  a fair reading of the question. The audiobook card (Gregory Maguire) isn't chosen. A "who wrote" cue would also catch most of
  the tuning split's who-wrote group, so it was left out.

## Grading notes

- media-004 ("You have access to a music album by Gustav Holst [1]") is graded not-grounded: the folder-named card says only
  "Holst - The Planets"; "Gustav" isn't in it. The phone album has no artist or composer tags.

## Decision

Media stays on in Research. The flag isn't needed.
