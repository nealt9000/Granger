# tools/links — Linked Library LL1 (reference links in the Classics)

Desk-only (the Mac): the phone never runs any of this, it reads the `.glink` sidecar Task 7 will write.

Venv: `~/.venvs/granger-links` (a symlink to the X10; spaCy `en_core_web_trf` + `es_core_news_lg`,
sentence-transformers for EmbeddingGemma). Working files: `/Volumes/X10 Pro/granger-ll/ll1/`.

1. `kb.py ITEMS.jsonl ZIM GQID kb.sqlite` — names to look up: every article title and redirect in a whole
   Wikipedia ZIM, with each item's sitelinks, coarse class (P31) and year (P569/P571).
2. `ner.py EPUB ner.jsonl --lang en|es` — entity spans with chapter offsets (`text.py`).
3. `link.py EPUB ner.jsonl kb.sqlite ZIM cand.jsonl --lang en|es --year YYYY` — each span's candidate or the
   reason it has none (type, name agreement, the book's own people, common words, born after the book…).
4. `rules.py cand.jsonl… --tier light|more` — Decision 5: rank floor, first mention per chapter, density cap.

Tests: `~/.venvs/granger-links/bin/python -m pytest -q tools/links/tests`.
