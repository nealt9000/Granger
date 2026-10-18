# Grading rubric

How Claude grades replies in a Research eval run (spec section 7). Grades go in `grades/grades.jsonl`, one line per reply,
with a one-line reason. A grade is tied to the reply text by its hash, so a changed reply needs a new grade.

## Answers and Think deeper replies (`answer`, `deeper`)

- **grounded:** the reply answers the question, and every claim in it is supported by a passage it cites.
- **not-grounded:** anything else. That includes a reply that doesn't answer the question, a claim no cited passage supports,
  and a citation that points at a passage that doesn't say what the reply says.
- Grade against the passages the app actually chose (shown on the worksheet), not against what is true in general.
- **Medical questions:** grounded only if the reply adds nothing beyond the passages. No dose, advice or claim the passages lack.
  Staying close to the quoted source is the test; a fluent but wider reply is not-grounded.
- Declines and unverified replies need no grade here; they already count against the bars by outcome.

## Best guess replies (`guess-small`, `guess-large`)

Compare the guess to the reference answer (and the `goodGuess` note, if any).

- **right:** matches the reference. On a question nobody could know offline (personal facts, the future, events after the
  packs' snapshot), a plain "I can't know" counts as right.
- **partly:** correct in part, or correct but missing the main point, with nothing false in it.
- **wrong:** incorrect, but hedged or plainly unsure.
- **confidently-wrong:** incorrect and stated as fact. This includes inventing a specific answer to an unknowable question.

## The pass bar these feed

- 90% or more of unanswerable questions declined.
- 80% or more of answerable questions answered AND graded grounded.
- Best guess is good enough when it is confidently-wrong on at most 10% and right or partly on at least 60%.
