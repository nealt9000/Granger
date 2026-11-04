# Brief: the held-out misses by kind, with no items (2026-11-04)

For a separate blind agent, not the Ask tuning session. Neal's decision (2026-11-04): the tuning session may see
the held-out set's misses **by kind, per domain**, never by item. One scoring of this set is left, so nothing
in your report may let a reader recognise a question.

## Inputs

- `tools/eval/RUBRIC.md` and the grades already given: `tools/eval/grades/grades.jsonl` (keyed by id, kind, reply
  hash; the latest line wins).
- The runs: `tools/eval/runs/r2-h2-1/` (171 items) and `tools/eval/runs/r2-h2-1-own/` (8 own-book items). Each has
  `dataset.json` (the items), `results.jsonl` (per item: `keywords`, `found`, `chosen`, `cited`, `outcome`, `reply`,
  `prompts`, `rewriteFallback`) and `found.jsonl` (the keyword search's and the dense search's hits, `passages` with
  `text`).
- How metrics count: `tools/eval/metrics.py` (`recallAt4`, `foundNotChosen`, `neverFound`, the per-domain block)
  and `tools/eval/report.py`. `~/.venvs/granger-pack/bin/python eval.py report r2-h2-1` rebuilds the report.
  Python: `~/.venvs/granger-pack/bin/python`.
- The own-book run's source ids don't map (the app ids imported books by sha256, the set by `pg` stem); grades are
  unaffected, retrieval numbers for it are not.

## What to produce

Write `docs/ask/r2-heldout-1-by-kind.md`: counts and short descriptions of kinds, nothing that names an item.
Change nothing else.

1. **Answerable, not grounded (85 of 127 grounded, so 42 misses) by domain × kind.** Put each miss in exactly one
   kind, in this order of precedence:
   - *never found*: no expected passage among any hit in `found.jsonl` (either search);
   - *found, not chosen*: a hit, but not among the 4 passages in `chosen`;
   - *in the prompt, declined*: the answer passage was shown and the reply declined ("Not in your library");
   - *in the prompt, adjacent answer*: the reply answers a nearby question (right topic, wrong fact, wrong entity,
     wrong passage of the four);
   - *in the prompt, misread*: a number, date, superlative, comparison or arithmetic read wrongly;
   - *in the prompt, partial*: a multi-step second half or a two-part question's second part skipped;
   - *in the prompt, invented*: a claim no passage holds;
   - *borderline*: a strict grading call that another grader might pass.
   Give the table per domain (classics, food, making, maps, medicine, reference, science, technology, thought,
   own-book) and the totals. Also the multi-step items' tally by these kinds.
2. **Never found, what they have in common.** For the never-found answers, classify the gap between the question
   and the expected passage, with counts, as kinds only: older or different wording; a synonym a modern reader
   uses; a dictionary headword; a named thing (person, place, work) missing from the recorded `keywords`; the right
   article was read but not the passage; the expected source is a book while articles were read (or the reverse);
   the pack has no full-text index; the second fact of a two-fact question; other (describe the kind in a few words).
   Say for how many the recorded keywords contain the passage's key terms anyway (so the search, not the keywords,
   missed). Report by domain too, and separately for classics by section kind (scripture, novel, drama, poetry,
   philosophy, other) without titles.
3. **Found but not chosen (12):** what outranked the expected passage, by kind: a passage from the same source,
   a passage from another pack on the same topic, a fiction passage on a factual question, a dense-search hit
   over a keyword hit, or the reverse.
4. **Unanswerable, answered (8 of 52):** by cue kind: personal, future, after-snapshot, local or private, no-record
   fact on a covered topic, personal medical, and whether the reply cited a passage or answered from the model's
   own knowledge. By domain.
5. **Declined wrongly (answerable but declined)**, if any, split by whether the answer was in the prompt.
6. **Classics in particular (19 of 33 grounded):** the 14 misses by the kinds above and by section kind; and
   whether the Classics one-page notes (`book:import:` notes passages) appeared among the chosen passages.
7. **A closing paragraph** on which kinds are large enough to clear run-to-run noise (about 4 items either way on
   this set) and which are too small to steer by.

## Rules

- No question text, no partial question text, no quotes, no `reference` text, no article or book titles, no ids,
  no reply text, no keywords. Describe kinds in your own general words ("a question about a character's death
  in a novel" is fine; the character's name is not).
- Counts and short phrases only. Where a kind has 1 or 2 items, give the count and keep the description generic.
- Before handing back, re-read your report once with this test: could the tuning session use any line of it to
  find or fix one specific question? If so, generalise the line.
- The report will be checked mechanically against the set's question, quote and reference strings before the
  tuning session reads it.
