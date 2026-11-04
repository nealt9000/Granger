# Held-out scoring 1: the misses by kind, per domain (2026-11-04)

Written blind for the Ask tuning session per `docs/ask/r2-heldout-1-by-kind-brief.md`. Counts and kinds only;
nothing here names, quotes or paraphrases an item. Runs: `r2-h2-1` (171 items) and `r2-h2-1-own` (8). Grades:
`tools/eval/grades/grades.jsonl`, latest line per reply.

**How the retrieval kinds were computed.** "Found" means an expected passage is among any hit in `found.jsonl`
(the keyword search's full list or the dense search's list) or in `results.jsonl`'s `found`; "in the prompt"
means among the 4 `chosen` passages. A hit counts if it matches by `metrics.matches` (source + location) **or**
by `metrics.quote_in` (the answer quote in the passage text). The quote rule is needed because (a) twin packs hold
copies of the same article under a different source id, which the location rule treats as a miss, and (b) the
own-book run's source ids don't map, so only the quote rule works there. For comparison, the pure `metrics.py`
numbers (location only, `results.found` only) over the same 42 misses are: 15 with the answer location among the
chosen 4, 3 found-not-chosen, 24 never found. The brief's rule (either search's full list, plus quotes) moves 13 of
those 24 into "found, not chosen" and 2 of the location-misses into "in the prompt" (twin-pack copies).

## 1. Answerable, not grounded: 42 misses by domain and kind

Each miss has exactly one kind, assigned in the brief's order of precedence.

| Domain | Answerable | Grounded | Misses | Never found | Found, not chosen | In prompt: declined | adjacent answer | misread | partial | invented | Borderline |
|---|---|---|---|---|---|---|---|---|---|---|---|
| classics | 33 | 19 | 14 | 4 | 6 | 1 | 2 | 1 | 0 | 0 | 0 |
| food | 10 | 6 | 4 | 0 | 2 | 1 | 1 | 0 | 0 | 0 | 0 |
| making | 10 | 7 | 3 | 1 | 0 | 0 | 2 | 0 | 0 | 0 | 0 |
| maps | 10 | 8 | 2 | 0 | 0 | 0 | 1 | 0 | 0 | 0 | 1 |
| medicine | 16 | 11 | 5 | 2 | 1 | 0 | 1 | 0 | 1 | 0 | 0 |
| reference | 10 | 7 | 3 | 1 | 0 | 1 | 1 | 0 | 0 | 0 | 0 |
| science | 10 | 6 | 4 | 0 | 1 | 0 | 1 | 0 | 0 | 2 | 0 |
| technology | 10 | 7 | 3 | 1 | 2 | 0 | 0 | 0 | 0 | 0 | 0 |
| thought | 10 | 8 | 2 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |
| own-book | 8 | 6 | 2 | 1 | 1 | 0 | 0 | 0 | 0 | 0 | 0 |
| **total** | **127** | **85** | **42** | **11** | **14** | **3** | **9** | **1** | **1** | **2** | **1** |

Retrieval (never found + found, not chosen) accounts for 25 of 42. The 17 "in the prompt" misses split further:

- **10 had the exact answer sentence in a chosen passage** (all 3 declined, 3 of the adjacent, the misread, the
  partial, both invented). Two of these were twin-pack copies of the expected article.
- **7 matched only by chapter or article** (6 of the 9 adjacent, the borderline): the right book chapter or the right
  article was shown, but the excerpt shown did not contain the answer sentence (lead section shown, answer in a
  later section; or the reverse; or a different excerpt of the same chapter). For these, "adjacent answer" is
  mostly a passage-granularity retrieval problem: the model answered from the excerpt it had.

Other notes on the kinds:

- *Adjacent answer* (9): 3 wrong-entity cases (the reply answered for a neighbouring thing: a related item of the
  same kind, a different category of person, a different passage of the same chapter), 4 answered the general topic
  without the asked-for fact, 2 answered a neighbouring question (the previous turn's question in a follow-up; a
  nearby biographical fact instead of the asked arithmetic).
- *Misread* (1): a number in older prose read with its parts in the wrong order.
- *Partial* (1): a two-part medical comparison with both facts in the prompt; one half answered, the other half
  replaced by a related general statement.
- *Invented* (2): both are two-fact arithmetic or ordering questions where only one of the two dated facts reached
  the prompt and the reply filled in the missing side (a made-up gap in years; an ordering with one side undated).
- *Borderline* (1): the reply gave the right short answer; a passage among the 4 supports it, but the reply cited a
  neighbouring passage that only lists it without the explanation. Fails the wrong-passage rule; another grader
  might pass it. A second miss, counted under never found by precedence, also had the right work named in
  the reply with the author supplied from the model's own knowledge and cited to a passage that doesn't hold it.
- *Declined, answer in prompt* (3): all multi-step; see the tally below.

**Multi-step items** (19 answerable, 5 grounded, 14 misses): never found 2, found not chosen 3, in prompt and
declined 3, adjacent 3, partial 1, invented 2. By how much of the two-fact answer reached the prompt:

- both facts in the prompt: 4 (2 declined "the passages don't state the computed value", 1 answered a nearby fact
  without doing the arithmetic, 1 partial). The model does not do the subtraction or comparison when both dates
  are in front of it.
- one fact in the prompt: 5 (1 declined, 2 invented the missing side, 2 answered the second half from the wrong
  entity or fact).
- neither: 5 (2 never found, 3 found but not chosen; of those 3, 1 declined before the answer stage, 1 declined by
  the model, 1 answered half from a non-expected passage).

## 2. Never found (11): what the gap looks like

Gap between the question and the expected passage, one primary kind per item:

| Kind | Count | Domains |
|---|---|---|
| older or different wording | 1 | classics (scripture) |
| a synonym a modern reader uses | 2 | medicine (a lay name for a condition whose article is titled by the clinical name; an everyday word where the article uses the clinical term) |
| a dictionary headword | 0 | |
| a named thing missing from the keywords | 0 | |
| the right book or article was read, but not the passage | 4 | classics 3 (2 where the answer sits on the book's title page or front matter, 1 where it sits in another chapter), own-book 1 |
| expected source a book while articles were read, or the reverse | 0 | |
| the pack has no full-text index | 1 | making (a device identifier that appears only in body text; also the second fact of a two-fact question) |
| the second fact of a two-fact question | 0 primary (2 secondary: the making item above and the thought item below) | |
| other | 3 | thought 1: an equivalent passage from a different pack reached the prompt (the fact was there, from a source the dataset didn't expect); reference 1: a plot-description question with nothing named, whose answer is in an author's biography article, and whose one concrete noun keyword-matched an unrelated technical article; technology 1: a task-phrased how-to in a docs pack, where the pack's table-of-contents page outranked the how-to page and the dense search returned Q&A-site look-alikes |

Cross-cutting:

- **Keywords held the passage's key terms anyway in 3 of 11** (the device-identifier item, the one-concrete-noun
  item, and the first fact of the thought item), plus 1 partly (two of four keywords). In those the index or ranking
  missed, not the rewrite. In the other 7 the keywords and the passage share no content word.
- **5 of the 42 misses are "which work / who wrote it" questions that describe a plot or scene without naming the
  work** (3 never found, 2 found but not chosen). For these the expected passage is usually a title page or an
  author's biography, which no search reached; the keyword rewrite produces plot nouns that match unrelated
  technical or lexical articles. None of the 5 was grounded.
- In 2 of the 11 the fact reached the prompt from a source other than the expected one (an equivalent article from
  another pack; equivalent repair guides for the same device) and the reply still missed it, so the retrieval count
  slightly overstates the loss.
- The rewrite fallback fired on none of the 42 misses.

By domain: classics 4, medicine 2, making 1, technology 1, reference 1, thought 1, own-book 1.

Classics never-found by section kind: scripture 1, novel 3, drama 0, poetry 0, philosophy 0, other 0.

## 3. Found but not chosen: what outranked the expected passage

**The metric's 12** (`metrics.py`: in `results.found`, not among the chosen 4, by location). 9 of the 12 were
grounded anyway, from a different passage; 3 are misses (1 answered a different work, 2 declined before the answer
stage with nothing chosen). What outranked, over the 10 with chosen passages:

- other articles from the same pack or a twin pack on the same topic: 5 (all grounded anyway, i.e. equivalent
  passages were chosen)
- articles from other packs on the same topic: 5, of which 3 are a factual-article passage chosen over a scripture
  or fiction passage (2 grounded anyway, 1 miss) and 2 Q&A-site or other-pack articles (both grounded anyway)
- a fiction passage over a factual question: 0
- a dense hit over a keyword hit: in 8 of the 10 the expected passage was a keyword-only hit and 3 or 4 of the chosen
  passages came from the dense list. The reverse (keyword over dense): 0 in these 12.
- expected rank in the 60-passage list: top 5 in 5 cases (one at rank 0), 13 to 40 in the other 7.

**The misses' 14** (the brief's rule: either search's full list or the quote). Sub-kinds:

- nothing outranked, declined before the answer stage (no passages chosen): 4 (classics 2, food 2)
- the same book's other chapters, carried over from the previous turn of a follow-up: 1 (classics)
- related articles from the same or a twin pack (right pack, neighbouring articles): 4 (science 1, medicine 1,
  thought 1, technology 1)
- articles from other packs on the question's generic words (the dense book hit outranked by keyword hits on
  everyday nouns from the question; encyclopedia articles about a work over the work's text; Q&A-site passages
  over the docs pages): 4 (classics 3, technology 1)
- the own book's other chapters plus an encyclopedia plot summary: 1 (own-book)
- origin of the expected hit: dense search only in 9 of 14, both searches in 5. **In 9 of 14 the expected passage was
  in the dense search's top 3** and still not among the 4 chosen. It appeared in the 60-passage ranked list in only
  3 of 14; in the other 11 it was found by a search but never entered the list that gets ranked.
- keyword hit over a dense hit: 1 (the generic-word case above); dense over keyword: 0 among the misses.

## 4. Unanswerable, answered (8 of 52)

| Cue kind | Count | Domains | Cited a passage? | How the reply answered |
|---|---|---|---|---|
| personal | 1 | reference | yes | from a fiction passage about a group of characters |
| future | 3 | medicine 2, technology 1 | yes | hedged that nothing is known yet, then summarised passages about research or release process |
| after-snapshot | 1 | reference | yes | gave an earlier year's result as if current |
| local or private | 0 | | | |
| no-record fact on a covered topic | 2 | classics 1, making 1 | yes | one answered the nearest recorded fact instead of the unrecorded origin asked for; one's reply says in substance that the passages don't hold it, a decline that the outcome detector recorded as answered |
| personal medical | 1 | medicine | yes | general signs of the condition from the passages, no diagnosis |

All 8 cited at least one passage; none answered from the model's own knowledge alone. By domain: medicine 3,
reference 2, technology 1, classics 1, making 1. (Declines by domain were medicine 8/11, reference 12/14,
classics 3/4, making 2/3, technology 3/4, the rest all.) Medicine's three are the two future-dated treatment
questions and the one personal-medical; the medical decline check misses future-tense questions about treatments.

## 5. Declined wrongly (answerable but declined): 15

- **Answer in the prompt: 3.** All multi-step; 2 with both facts in the chosen 4, 1 with one. The model's decline
  says the passages don't give the computed value.
- **Answer not in the prompt: 12.**
  - declined before the answer stage, nothing chosen (the prompts list has no answer stage): 6 (classics 3, food 2,
    medicine 1). Of these, 4 had the expected passage found by a search (3 in the dense top 3) and 2 never found.
  - declined by the model with 4 passages shown, none the answer: 6 (classics 3, technology 1, thought 1,
    medicine 1). Of these, 3 found-not-chosen and 3 never found.

So 7 of the 15 wrong declines had the expected passage found by a search but not among the 4; the decline was the
right behaviour given the prompt. Only the 3 in-prompt declines are the model's.

## 6. Classics in particular (19 of 33 grounded)

The 14 misses by kind: never found 4, found not chosen 6, in prompt and declined 1, adjacent 2, misread 1.

By section kind (section kind assigned from the expected source; the whole classics set is 9 scripture, 13 novel,
2 drama, 2 poetry, 5 philosophy or other non-fiction, 2 encyclopedia articles about an author or work):

| Section kind | Answerable | Grounded | Misses | Miss kinds |
|---|---|---|---|---|
| scripture | 9 | 8 | 1 | never found 1 |
| novel | 13 | 3 | 10 | never found 3, found not chosen 5, adjacent 1, misread 1 |
| drama | 2 | 1 | 1 | found not chosen 1 |
| poetry | 2 | 1 | 1 | adjacent 1 |
| philosophy / non-fiction | 5 | 5 | 0 | |
| other (articles about an author or work) | 2 | 1 | 1 | in prompt, declined 1 |

Novels are the loss: 3 of 13 grounded against 16 of 20 for everything else in classics. Of the 10 novel misses, 8 are
retrieval (the right book was in the hits for 7 of them; the passage reached the 4 for 2) and 5 are the
plot-description or follow-up shapes noted above.

**The one-page notes** (`book:import:` passages in `r2-h2-1`): among the chosen passages for 2 of the 33 classics
items, both grounded; for none of the 14 misses. Among the search hits (either list) for 8 of the 14 misses and for
36 of the 171 items overall, so they are found but not chosen. For the own-book run, `book:import:` is the user's
own book and was excluded from this count.

## 7. What is large enough to steer by

Run-to-run noise on this set is about 4 items either way. Kinds clear of it:

- **Retrieval as a whole: 25 of 42** (never found 11, found not chosen 14), rising to 31 if the 6 adjacent-answer
  misses whose excerpt lacked the answer sentence are counted as passage-granularity misses. Within it, the one
  sub-kind big enough on its own is **found by the dense search in its top 3 but not among the 4 chosen: 9 of 14**,
  and the broader "found but never entered the ranked 60": 11 of 14.
- **Classics novels: 10 misses of 13**, against 4 misses across the other 20 classics items. Section kind, not
  domain, is the lever.
- **Multi-step: 14 misses of 19**, with a 9-item core where at least one fact reached the prompt and the model
  declined, invented the other side, or skipped the arithmetic.
- **Wrong declines: 15**, of which 6 happened before the answer stage and 7 had the passage found but not chosen;
  this is retrieval again, not the decline check.

Too small to steer by (1 to 3 items each, inside noise): misread 1, partial 1, invented 2, borderline 1, in-prompt
declines 3, any single never-found gap kind (the largest is 4), each cue kind among the 8 wrongly answered
unanswerables (1 to 3), and every per-domain count outside classics (2 to 5 misses per domain). A change aimed at
one of these cannot be seen on this set; it needs the development set or a replay over the recorded hits.
