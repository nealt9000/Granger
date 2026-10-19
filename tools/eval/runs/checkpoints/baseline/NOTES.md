# Baseline: tuning split, 2026-10-18

Run `baseline` on the Pixel 6: 202 tuning questions, `--guesses both`, build 2bc3298, Neal's library
(Wikipedia top maxi, WikiMed, Wiktionary, Geography, Physics, Chemistry, Mathematics, Knots, iFixit, all
Granger Classics). Wall time 158 min (about 47 s a question including guesses). Graded by Claude per
`RUBRIC.md` in four batches (`grades/grades.jsonl`); borderline calls listed in the grading notes.

## Against the bar

| Metric | Baseline | Bar |
|---|---|---|
| Unanswerable declined | 89% (68 of 76) | ≥ 90% |
| Answerable grounded | 36% (45 of 126) | ≥ 80% |
| Best guess E2B: confidently wrong / right or partly | 14% / 80% (113) | ≤ 10% / ≥ 60% |
| Best guess E4B: confidently wrong / right or partly | 11% / 88% (113) | ≤ 10% / ≥ 60% |
| Medical flag accuracy / recall | 91% / 57% | recall aim 95% |
| Think deeper grounded | 0 of 1 | 80% (held-out) |
| Send to first words, median / p90 | 21.8 s / 26.4 s | 9 s |

Step medians: rewrite 9.9 s, search 9.9 s, rank 0.03 s. Guess medians: E2B 3.4 s, E4B 20.3 s.

## Where the answerable questions went (126)

| Outcome | Count |
|---|---|
| Answered, grounded | 45 |
| Answered, not grounded | 23 |
| Declined by the model (passages were sent) | 51 |
| Declined by the threshold (nothing sent) | 1 |
| Unverified (citations failed the check) | 3 |

The plan's split of wrong declines:
- **Model declined:** 51. Almost every wrong decline. The threshold is not the problem.
- **Found but not chosen:** 8 of 126.
- **Never found** (by the dataset's answer locations): 79 of 126. This overstates the problem. The authors
  pinned one location per answer, usually the primary source (e.g. the KJV chapter), while the app often
  answers correctly from another article. The `bible` group shows 10 "never found" but 6 grounded answers.
  Treat recall@4 (31%) as a lower bound. It is not a target until locations list the equivalent articles.

By group (answerable): multi-step 19 of 20 declined by the model, with the right passage chosen in 13 of
them. Words 4 of 4 declined, knots 2 of 2 declined, vocab-gap 5 of 10 declined. Bible 6 grounded of 10.
Who-wrote 6 of 11. Medical 13 grounded of 25.

## Neal's two questions

- **"Who wrote the book of Romans?"** Answered and grounded: "The Epistle to the Romans is almost universally
  accepted as the work of Paul [1]", from Wikipedia's *Authorship of the Bible*, Pauline epistles. Fixed
  since Plan 8. The other chosen passages were noise matched on "Romans": *An Essay on Man* and Sale's Koran.
- **"Where in the bible does it talk about alcohol?"** Still NOT IN LIBRARY. Keywords: "bible alcohol references",
  "alcohol in scripture", "biblical references to drinking", "alcohol biblical studies". Chosen: three
  *References* sections of *Christian views on alcohol* and one of *Alcohol intoxication*. The right article
  was found, but its bibliography won over its body, so the model saw only citations. Best guesses: both
  vague but true (partly).

## Top failure patterns

1. **The model declines when the passages do answer** (51). The worst case is multi-step: multi-step-001 (Mary
   Shelley's age at Frankenstein), multi-step-002, multi-step-003 all had the needed articles chosen and replied
   NOT IN LIBRARY. The answer prompt reads as "only if a passage states it outright". It needs to allow
   combining facts from the passages (dates, subtraction) while still citing them. → new tuning item (8h,
   answer prompt), ahead of 8a.
2. **Rewrite writes phrases about the question, not words in the source** (8a). "betimes definition", "granny
   knot problems", "etymology of quarantine", "knot for slipping rope", "bible alcohol references". The meta-words
   (definition, meaning, problems, references, history, etymology) match the wrong passages. Words-001..004 and
   knots-001..002 never reach Wiktionary or the Knots pack. Chosen passages include *COVID-19 pandemic in
   Australia* for quarantine, and Poe and Synge for knots.
3. **Reference and bibliography sections win** (8c): bible-001 (3 of 4 chosen are *References*), and similar in
   literature. These sections are dense with the topic's names.
4. **Cites the right passage but never states the answer** (part of the 23 not-grounded). Examples: Moby-Dick
   without Ishmael, Everest's 1856 survey without the height, scurvy without vitamin C (medical-012), Beowulf
   without "anonymous". Also true claims cited to the wrong passage (bible-009, medical-013). → answer prompt
   (8h): "answer the question in the first sentence".
5. **Prose declines counted as answers.** literature-005, medical-018, medical-028 ("The provided passages do not
   contain…"). These should end as declines, so Best guess is offered and the medical decline copy shows.
   → detect in the pipeline (8h).
6. **Personal medical questions get answered from loosely related passages** (6 of 15 medical unanswerables).
   Examples: medical-037, a toddler's fever answered from *Zika virus*; medical-038, doubling sertraline answered
   with dosage facts; medical-024, an ALT result. This is the safety-relevant miss. The medical flag's recall is 57%,
   and personal-advice questions ("my", "should I", "do I need to") need the decline-and-see-a-professional path
   whatever the passages say. → new tuning item (8i, medical routing).
7. **Latency.** First words come at a median 21.8 s against 9 s. Rewrite (9.9 s for at most 32 tokens) and
   search (9.9 s over 11 packs plus the book index) are each over budget alone. Not a gating metric, but an
   item for Task 8 to record and, where cheap, cut (e.g. cap search time, run ZIM searches in parallel).
8. **Best guesses fail on date arithmetic.** Most confidently-wrong guesses from both models are multi-step
   chronology: Darwin and Lincoln, Cervantes and Shakespeare, Columbus to the Mayflower. On unknowable questions
   both models mostly say they can't know. The E4B guess bar is missed by 1 question (12 of 113, needs ≤ 11). E2B
   misses by 5.
9. Minor: stray Thai tokens in one E2B answer (history-002).

## Order for Task 8

8h (answer prompt: allow combining, answer first, detect prose declines), then 8a (rewrite to source words),
8c (down-weight reference sections), 8i (medical personal-advice routing), 8b (stems), 8d (threshold sweep), 8e,
then the latency items. Measure the multi-step, words, knots, vocab-gap and bible groups first.
