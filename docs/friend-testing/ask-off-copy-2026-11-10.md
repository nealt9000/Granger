# Ask off: copy outside the app (Plan SQ Task 0.3) — draft for Neal, 2026-11-10

Nothing here is published until Neal approves. Two rounds: **now** (Ask leaves; Search is as today) and **when SQ ships**
(Search's Best matches is the new headline). "Now" claims nothing Search can't do today.

## Play Console (Neal's account, by hand or with Chrome on his say)

| Field | Today | Now |
|---|---|---|
| Short description (≤80) | Wikipedia, books and a librarian you can ask, on your phone, with no signal. | Wikipedia, maps, books and audiobooks on your phone. Works with no signal. (73) |
| Short description, es-419 | (current Spanish) | Wikipedia, mapas, libros y audiolibros en tu teléfono. Funciona sin señal. (74) |
| Full description, 1st paragraph | …classic books and audiobooks, with a librarian you can ask. It answers only from your library and shows you the page it read. It works… | Granger keeps the world's knowledge on your phone: Wikipedia, maps, manuals, classic books and audiobooks, with one search over all of it. It works on a plane, at a campsite, and through outages and disruptions. No account, no subscription, and nothing you look up leaves your phone. |
| App access note | Ask needs 6 GB of RAM and the model… | All functionality is available without special access. |
| Health declaration | …Ask's medical answers with their banner… | Medical reference and education only (WikiMed, first-aid packs); not a medical device, no health data. |
| AI-generated content | Yes, Ask generates text… | No (the app generates no text). Change back when Ask returns. |
| Release notes (next build) | — | Ask is resting while on-device AI catches up; we'll bring it back when it can answer well. Everything else works as before. / Preguntar está en pausa mientras la IA en el teléfono mejora; volverá cuando pueda responder bien. Todo lo demás funciona igual. |

## opengranger.org (granger-site repo; edit `_*.html` / `_template.html`, run `build.py`)

- **Home, "Ask it · an add-on" section** (and its "Ask it" nav link): remove for now. When SQ ships, replace it with a
  "Find it" section that shows real Best-matches screenshots (a question → the passage, highlighted).
- **Home, medical card** ("Add the librarian and it quotes medical sources…"): becomes "WikiMed, MDWiki and first-aid
  manuals, a tap from Home. Granger is a reference, not a doctor: see someone who can examine you."
- **Home, travel and privacy lines** ("and so is the librarian, if you've added one" / "if you add the librarian, not
  what you ask"): drop the librarian clauses. "Nothing is logged anywhere: not what you read or search. No telemetry."
- **Footer** "nothing you read or ask leaves the phone" → "nothing you read or search leaves the phone".
- **/test and /prueba**: drop the Ask requirement line and test step 5; add one line: "Ask, the librarian, is resting
  for now: on-device models can't yet answer well enough. Search is getting the attention instead." (es: "Preguntar, el
  bibliotecario, está en pausa: los modelos en el teléfono aún no responden lo bastante bien. Ahora mejoramos Buscar.")
- **Guides**: *Offline Wikipedia* drops the "An optional librarian" item; *Free offline library* changes the FAQ "Does
  the AI work offline?" to "Is there AI? Not for now. Granger is a library and a search, and it all works offline"; and
  "what you read or ask" becomes "what you read or search"; *Emergency preparedness* drops its librarian sentence.
- **Privacy / Privacidad**: leave as is. It's still true (Ask data from older builds stays on the phone) and a policy
  change notice isn't worth it. Revisit when Ask returns.

## Testers

- `docs/friend-testing/round-1-tester-note.md` (+ es): remove the Ask lines (memory note, "11.5 with Ask", "Ask's model
  2 GB", step 5, "What to expect from Ask", the Ask bullets) and add the resting line above.
- A short note to the current testers group (Neal sends):
  > Quick update: I've switched off Ask, the librarian, in the next build. The models that fit on a phone today get too
  > many answers wrong, and I'd rather it said nothing than said it badly. I'll look again in a few months. If you
  > downloaded its model, you can remove it under Settings → Models to free about 2.5 GB. Meanwhile I'm working on
  > making Search much better: tell me what you searched for and didn't find.

## The repo (on Neal's approval of the wording above)

- `README.md` first lines and `funding.json`: "with a librarian that answers from your books" → "with one search over
  everything". Brand spec §9 keeps the librarian copy as the version for Ask's return.
