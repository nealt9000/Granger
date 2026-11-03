# R2 held-out scoring 1 of 2: what we learned (2026-11-03)

Written at the end of the day the first held-out scoring failed, to start from tomorrow. The numbers are in Plan R2
(`docs/superpowers/plans/2026-11-02-plan-r2-pass-or-stop.md`, Task 10). The tuning session has seen only totals and
per-domain numbers of the held-out set, and should stay that way: **one scoring is left**.

## The result

| | Held-out (`r2-h2-1` + own) | Tuning (`r2-t10-cut`) | Bar |
|---|---|---|---|
| Answerable grounded | 85/127 (67%) | 101/126 (80%) | 80% |
| Unanswerable declined | 44/52 (85%) | 71/76 (93%) | 90% |
| Answer quote in the 4 passages | 47/119 (39%) | 68/126 (54%) | not gating |
| Found but not chosen / never found | 12 / 51 | 17 / 45 | |
| Best guess: confidently wrong / right or partly | 4% / 80% | | ≤10% / ≥60%: passes |
| First words, median | 12.6 s | 12.2 s | 9 s target |

Held-out grounded by domain: maps 8/10, thought 8/10, own books 6/8, making, reference, technology 7/10, medicine
11/16, food 6/10, science 6/10, classics 19/33. Declined: medicine 8/11, reference 12/14, classics 3/4, making 2/3,
technology 3/4, the rest all. Graded blind by a Fable agent per `RUBRIC.md`.

## What it tells us

1. **The tuning split stopped measuring the app.** Tuning said 80% and 93%; unseen questions say 67% and 85%. A
   13-point gap is far outside run-to-run noise (about 4 items either way on 126). The 126 tuning questions had been
   run, read and fixed against for weeks; every fix was judged on them. They are a training set now, not a test.
2. **Retrieval is the biggest loss, not the model.** On unseen questions the answer reaches the 4 passages 39% of the
   time against 54% on tuning, and 51 of 119 answers are never found at all. Grounded can't rise above what reaches the
   prompt. The retrieval work (keywords, older words, headwords, entity reserve, date passages, article cut) was shaped
   around the tuning questions' vocabulary and generalises less than it looked.
3. **The item-shaped fixes carried over least.** Classics, where tuning got one-page notes for the 50 most-read works,
   is the weakest held-out domain (58%). The decline check was built from the tuning unanswerables; held-out declines
   fell to 85%, medicine to 8/11. The date work (YearFacts) moved multi-step 10 to 13 of 20 on tuning; held-out
   multi-step isn't broken out, but the grader names skipped second halves among the misses.
4. **The misses are mostly misreading, not inventing.** The grader's summary: replies answering an adjacent question,
   misreading a number, or skipping a two-part question's second half; few made-up claims. On tuning, the four
   losses in the last run were all misreads with the answer in the prompt. E2B ignores reading rules (Task 8), and the
   behaviour LoRA is blocked on LiteRT-LM#3173.
5. **Best guess is fine** on the held-out set (4% confidently wrong, 80% right or partly).
6. **Cost noted:** reading 16 articles instead of 8 adds 0.7 s to the keyword search (1.5 to 2.2 s median); first
   words didn't move.

## What has to change in how we work

- **We need an unseen set to develop against.** Tuning on the same 126 questions overfits; the held-out set can't be
  used to tune (blindness, two scorings). Before more changes: a fresh development set, written blind by another
  session like heldout2 was, scored freely but never tuned on item by item, so every change is checked on questions it
  wasn't shaped around. Only changes that hold on it go to the last held-out scoring.
- **Judge changes by kind, not by item.** A fix that names items ("this question needs that passage") is suspect. Prefer
  changes whose effect shows on a set they weren't built from.
- **Measure noise first.** Run-to-run variance is about ±4 answered items on 126; a change worth less than that needs
  repeated runs or a replay to show.

## Questions for tomorrow

- A third blind set for development: who writes it (Fable, as for heldout2), how big (enough to see 5 points: about
  150 answerables), and whether it reuses heldout2's domain mix.
- Whether a blind agent may report the held-out misses **by kind** (never found / found not chosen / misread / partial
  answer / declined wrongly, per domain), without items. It would aim the work, but it is more than totals: Neal's
  call under the blindness rule.
- Retrieval levers to test on the new set: the keyword rewrite (what never-found answers have in common), the search
  by meaning's share, how many passages the model sees (4), and a reranker (the cross-encoders tried offline lost as
  many as they gained on tuning).
- The answer model: whether anything other than the LoRA can cut misreads (a larger model on bigger phones, two-pass
  answer checking), and what it costs in seconds.
- Whether 80% on E2B is reachable at all in this plan, or Ask ships as a Preview below the bar (Task 11, pass or stop).
