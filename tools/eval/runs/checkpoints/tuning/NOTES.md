# Tuning the tuning split (Plan 9, Task 8)

Every run is the 202-question tuning split on the Pixel 6, with Neal's library. Grades are in `grades/grades.jsonl`,
keyed by reply hash. Runs after `tune-1` use greedy decoding, so the same build gives the same replies.

| Run | Changes | Unanswerable declined | Answerable grounded | Medical flag recall | First words (median) |
|---|---|---|---|---|---|
| baseline | — | 89% (68/76) | 36% (45/126) | 57% | 21.8 s |
| tune-1 | 8h answer prompt, 8i personal medical (random sampling; stopped at 72) | — | — | — | — |
| tune-2 | + greedy decoding, 8a rewrite to source wording, 8c no bibliography passages | 70% (53/76) | 37% (47/126) | 98% | 14.5 s |
| tune-3 | + 8k book BM25, 8l ranking (no idf, lead bonus, per-article cap), plain decline rule, threshold 0.6 | **91% (69/76)** | **44% (56/126)** | 98% | 14.2 s |
| tune-4 | + 8m named-work search | (running) | | | |

## What each change did and why

- **Greedy decoding** (`ca18727`). The model sampled at random by default. The same question got different answers from one run to
  the next ("Who wrote Romans?" was answered in one run and declined in the next), so runs couldn't be compared. Greedy decoding makes them
  reproducible, and the rewrite now takes about 2.7 s instead of 9.9 s.
- **8h answer prompt** (`5a8d38f`, `f17aa59`): answer in the first sentence; combining facts is allowed; prose declines count as declines.
  The "only if nothing answers" decline wording let the model answer from passages about the wrong year. tune-2 fell to 70% unanswerable
  declined. `88161a2` restored the plain rule, added "passages about a different person, year, place or event don't answer", and taught
  the decline check "the library does not contain…" and "…is not specified".
- **8i personal medical** (`152a8b1`…`8568564`): questions about one person's own health, medicines or results decline before the
  search. Medical detection is wider. Medical flag recall went from 57% to 98%, and medical unanswerables declined went from 9/15 to 14/15.
- **8a/8c** (`c954c39`, `6c98666`, `6018de1`): the rewrite drops meta-words; bibliography sections give no passages.
- **8k books** (`483a68b`, `374314a`): book passages came back in index order (`ORDER BY id`), so the King James Bible gave 0–1 passages
  for 9 of 10 Bible questions. They now rank by BM25 over FTS4 matchinfo, with at most 4 passages per book.
- **8l ranking** (`a733c35`): measured offline on the recorded corpus. A Python port reproduced the app's top-4 exactly. Quote-level
  recall@4 rose from 26/99 to 43/99:
  - idf over the retrieved passages made the topic word count least, because every hit contains it, so it was removed;
  - an article's opening passage gets +0.3, because opening passages hold the key facts;
  - the cap is 3 passages per article instead of 3 per pack.

  Risk: 37 of 48 answer quotes sit in a lead, which may partly reflect how the questions were written. The held-out split checks this.
- **Threshold 0.6** (`88161a2`): from a sweep on the tune-2 corpus. Recall is flat from 0.32 to 0.6, and fewer unanswerable questions
  get passages.

## Where tune-3 still fails (answerable, 70 not grounded)

- **Vocabulary gap and named works** (bible, vocab-gap, words, literature):
  - the passage holding the answer isn't retrieved;
  - "alcohol" never appears in the King James Bible, and "tattoos" doesn't either;
  - keywords naming the work ("bible", "scripture") favour other books.

  8m targets the second part.
- **Multi-step:** 15 of 20 have the needed article chosen, but 10 answers are not grounded and 8 are declined. The small model can't
  reliably combine facts or do date arithmetic from passages, and Think deeper (E4B) grounds 3 of 12.
- **Answers that cite but don't answer**, and true claims cited to the wrong passage. These persist at a lower rate.
