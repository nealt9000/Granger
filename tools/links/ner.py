#!/usr/bin/env python3
"""Plan LL1 Task 6, step 1: named-entity spans in a Classics EPUB (spaCy; the Mac only).

  ner.py EPUB OUT.jsonl --lang en|es

One line per span: book, href, start, len, text, label, block (index), heading (whether the block is a heading).
Offsets are into the chapter text: its blocks joined by "\\n" (text.py).
"""
import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from text import chapters  # noqa: E402

MODELS = {"en": "en_core_web_trf", "es": "es_core_news_lg"}
KEEP = {"PERSON", "PER", "GPE", "LOC", "ORG", "NORP", "EVENT", "FAC", "WORK_OF_ART", "MISC"}


def load(lang):
    import spacy
    return spacy.load(MODELS[lang], exclude=["lemmatizer"])


def spans(nlp, epub, book=None):
    """Each kept entity span of the book, as the dicts main() writes."""
    book = book or Path(epub).name.split("_")[0]
    for href, bs in chapters(epub):
        offsets, pos = [], 0
        for _, t in bs:
            offsets.append(pos); pos += len(t) + 1
        for i, doc in enumerate(nlp.pipe((t for _, t in bs), batch_size=32)):
            for ent in doc.ents:
                if ent.label_ in KEEP:
                    yield {"book": book, "href": href, "start": offsets[i] + ent.start_char, "len": len(ent.text),
                           "text": ent.text, "label": ent.label_, "block": i, "heading": bs[i][0].startswith("h")}


def main(argv=None):
    a = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    a.add_argument("epub"); a.add_argument("out"); a.add_argument("--lang", required=True, choices=MODELS)
    args = a.parse_args(argv)
    n = 0
    with open(args.out, "w", encoding="utf8") as out:
        for s in spans(load(args.lang), args.epub):
            out.write(json.dumps(s, ensure_ascii=False) + "\n"); n += 1
    print(f"{Path(args.epub).name}: {n:,} spans", file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
