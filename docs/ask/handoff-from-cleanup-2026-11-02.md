# Ask: handoff from the clean-up session (2026-11-02)

Neal asked that every Ask decision and task from the project review and the clean-up pass go to the Ask session. This is that list.

## Already built, on branch `cl-cleanup` (rebased on main be26839f, not merged yet)

Two commits change the Ask files (`ResearchViewModel.kt`, `ResearchScreen.kt`, `ResearchViewModelTest.kt`). Build on them or merge them first, so the work doesn't fork:
- `5a65d5b5` **Best guess behind a tap, written ahead** (Neal's call). A decline still writes the guess straight away, but out of sight. "Not in your library" stands, and the Best guess button opens the guess at once, with a spinner while it's still being written. The model runs on the tap only when no guess exists (failed, stopped, or the app died). New `TurnUi.guessShown`; the opened set is kept in memory only.
- `480cdf9e` **A personal health question points to a professional.** `TurnUi.personalMedical` reruns the pipeline's `PersonalMedical.matches(question, true)` on a declined medical turn with no selection, and the screen says "I can't answer questions about your own health. A doctor, nurse or pharmacist can. Search instead shows what your library says on the subject."

## Release checklist items (phase A, before the closed test)

1. **Report this answer** (Play's AI-Generated Content policy: report "without needing to exit the app"). Add a Report action on every reply and best guess. It opens a sheet: what's wrong (offensive, wrong, unsafe, other), an optional note, and what's included (question, reply, source titles). Sending goes through F1's Send feedback route, which is on `f1-friend-build` (not merged). See `docs/friend-testing/play-policy-check.md` §2 on `cl-cleanup`.
2. **Medical answers in release:** the banner and the quoted passages, without generated prose.
3. **Self-check:** on a phone where the model fails a short check on load, hide Ask and say why, instead of crashing.

## Neal's decisions (2026-11-02)

4. **Model lifetime:** keep the model loaded while Ask is open; unload it after **5 minutes** with no Ask screen open. Today it's released 2 minutes after each turn (`models.releaseSoon()`, `theModelIsReleasedAQuietSpellAfterEachTurn`).
5. **"What do I have on X?"** (questions about your own library): a **fast follow** after launch. Neal sees it as a basic Ask function.

## Also in progress (Neal's ideas from the review)

- **The librarian's reaction:** instant lines tied to real search progress before E2B answers (your `owl-lines-draft.md`).
- **Behaviour LoRA spike for E2B:** first check whether LiteRT-LM loads a LoRA on Gemma 4 E2B on the GPU, or whether it must be merged and converted. Train citing and declining from passages (built from Top 50k and Classics), not the facts themselves. Score on the existing eval against 80% grounded and 90% declined.

## Open Ask items from the clean-up list (roadmap carried-forward sections)

Polish: Ask slower than the 9 s target (check after R5) · the medical word list flags "Doctor Zhivago", "computer virus", "Tropic of Cancer" · + doesn't start a clean chat and Back leaves Ask (check, U3) · the rail's Ask button does nothing with a kept chat open (check) · every failure reads "The model needs repair" · raw Markdown in answers, "2*3*4" loses its asterisks · media cue words: add "movement", "piece", "suite", "chapter" · "Who wrote Wicked?" answers from the musical; a sports score question answers with the wrong game · "Search instead" joins every keyword · iFixit and Gutenberg packs have no full-text index (check after vectors) · the speaker icon on a chat (check, U2) · no vectors for PDF chunks and pack PDFs.

Internal: a first sentence with a citation can be read as a decline · the prompt token budget isn't enforced at runtime · a question asked while another chat answers can fall back to plain keywords · a background release mid-turn still unloads later · a medical turn stopped before its first words can lose its banner · a corrupt audiobook opens from a citation · a card over 1,200 characters loses its tail · keyword scoring doesn't fold accents (Spanish) · small parsing gaps (apostrophes, keyword cap, vector-size check) · stale English Python docs vectors; check Spanish vectors before signing L3.

The full list, with tags, is on the project page: https://claude.ai/artifact/9yyq993mki2LcSPtuGwD9u
