#!/usr/bin/env python3
"""Writes the reading script for a teacher-voice dataset (V4: a voice designed in Qwen3-TTS, taught to Piper).

    make_script.py --source <metadata.csv …> --extra granger_lines.txt --out <dir> \
        --speakers NorthLondonNanny SouthLondonProfessor --minutes 60 [--espeak-voice en-gb-x-rp] \
        [--passages --exclude <earlier script dir …>]

Candidates are LibriTTS-R's normalized sentences (public-domain books, CC BY 4.0; the `metadata.csv` files V3's
`build_dataset.py` wrote) plus `--extra`, hand-written lines in spoken form (no digits). A line is kept when it is
plain text of 30–190 characters (about 2–14 s read aloud) ending a sentence, and is not one of the held-out listening
lines (`HELD_OUT`, `say.py`'s text). Lines are then picked greedily for phoneme-pair coverage under `--espeak-voice`,
dealt out in turn so each speaker gets a different script of about `--minutes` (at `CHARS_PER_SECOND`). Every
speaker reads all the extra lines.

`--passages` (round 2) makes the candidates runs of 2–4 consecutive sentences from one chapter (LibriTTS ids
`speaker_chapter_paragraph_sentence`), 60–250 characters, so the teacher reads with a paragraph's flow rather than
one sentence cold. `--exclude` drops every line an earlier script used. A `--source` ending in `.txt` is a Project Gutenberg book
(public domain): its text between the START/END markers is split into paragraphs and sentences, with "Mr." and the
like spelled out, and numbered like LibriTTS ids so passages work the same. `--tag` goes into the line ids
(`<speaker>_<tag><n>`) so a second round's files don't collide with the first's. Coverage counts a sentence's first phoneme
as its own pair (`^`), so sentence-initial sounds (the pilot's weak "Boil") get lines too.

Writes `<out>/<speaker>.tsv` (`id<TAB>text`) and prints the coverage. Run it with the training venv's Python (it
needs piper1-gpl's espeak bridge).
"""
import argparse
import csv
import heapq
import os
import re
import sys
import unicodedata

CHARS_PER_SECOND = 18.5  # the pilot voices read 18–20; the first scripts assumed 14 and came out short
MIN_CHARS, MAX_CHARS = 30, 190
PASSAGE_CHARS, PASSAGE_LINES = (60, 250), (2, 4)
PLAIN = re.compile(r"^[A-Za-z][A-Za-z ,.;:'\"!?\-—()]*[.!?][\"']?$")

# The listening lines (the twelve V4 test lines and say.py's text) never go into training.
HELD_OUT = [
    "After the rain had passed",
    "The kettle should come to a full boil",
    "The Rosetta Stone was found",
    "Between 1914 and 1918",
    "Boil the water for at least",
    "This is general information, not medical advice",
    "It is a truth universally acknowledged",
    "Call me Ishmael",
    "Are you coming with us",
    "You will need three things",
    "Marie Curie, born in Warsaw",
    "Shall I compare thee",
    "The battery reads",
    "Wait — did you hear that",
    "It was the best of times",
    "Would you like me to read the next chapter",
]


def clean(text):
    text = text.replace('\\"', '"').strip()
    text = re.sub(r"\s+", " ", text)
    if text.count('"') % 2:
        text = text.strip('"').strip()
    return text


def held_out(text):
    low = text.lower()
    return any(h.lower() in low for h in HELD_OUT)


def follows(prev, cur):
    """True when LibriTTS utterance id `cur` is the sentence right after `prev` in the same chapter."""
    a, b = prev.split("_"), cur.split("_")
    if len(a) != 4 or len(b) != 4 or a[:2] != b[:2]:
        return False
    (pp, ps), (cp, cs) = map(int, a[2:]), map(int, b[2:])
    return (cp == pp and cs == ps + 1) or (cp == pp + 1 and cs == 0)


SPELL_OUT = [(r"\bMr\.", "Mister"), (r"\bMrs\.", "Missus"), (r"\bDr\.", "Doctor"), (r"\bSt\.", "Saint")]
SENTENCE = re.compile(r".+?[.!?]+[\"']?(?=\s+[\"'(]?[A-Z]|\s*$)")


def gutenberg_rows(path, book):
    """A Gutenberg book as (id, sentence) rows, ids `g<book>_0_<paragraph>_<sentence>` (see `follows`)."""
    with open(path, encoding="utf-8-sig") as f:
        text = f.read()
    start, end = text.find("*** START OF"), text.find("*** END OF")
    text = text[text.find("\n", start) + 1:end if end > 0 else None]
    rows = []
    for n, para in enumerate(re.split(r"\n\s*\n", text)):
        para = re.sub(r"\s+", " ", para.replace("_", "")).strip()
        para = para.translate(str.maketrans({"\u201c": '"', "\u201d": '"', "\u2018": "'", "\u2019": "'"}))
        para = para.replace("--", "\u2014")
        for pat, rep in SPELL_OUT:
            para = re.sub(pat, rep, para)
        if not re.search(r"[a-z]", para):  # headings
            continue
        rows += [(f"g{book}_0_{n:06d}_{k:06d}", m.group(0).strip()) for k, m in enumerate(SENTENCE.finditer(para))]
    return rows


def good_passage(text):
    """Whole quotes only (a window can start or end inside a speech), a capital start, and none of: all-caps words,
    ".-" joins, ellipses, or dialect elisions and single-quoted speech (o', th', 'em, 'Lawford!')."""
    first = text.find('"')
    return (text.count('"') % 2 == 0 and (first < 0 or first == 0 or text[first - 1] in " \u2014(")
            and re.match(r"^[\"']?[A-Z]", text) is not None
            and not re.search(r"\b[A-Z]{2,}\b|\.-|\.\.|[a-z]'(?![a-z])|(?<![A-Za-z])'", text))


def candidates(sources, extra, passages=False, exclude=()):
    seen, out = set(exclude), []
    for book, path in enumerate(sources):
        if path.endswith(".txt"):
            rows = gutenberg_rows(path, book)
        else:
            with open(path, encoding="utf-8") as f:
                rows = [(r[0].rsplit(".", 1)[0], r[-1]) for r in csv.reader(f, delimiter="|", quoting=csv.QUOTE_NONE)]
        if not passages:
            for _, raw in rows:
                text = clean(raw)
                if (
                    MIN_CHARS <= len(text) <= MAX_CHARS
                    and PLAIN.match(text)
                    and not held_out(text)
                    and text.lower() not in seen
                ):
                    seen.add(text.lower())
                    out.append(text)
            continue
        # Passages: every window of consecutive sentences; overlapping windows share lines, so the picker takes
        # each line once (`(path, index)` keys).
        for i in range(len(rows)):
            for n in range(PASSAGE_LINES[0], PASSAGE_LINES[1] + 1):
                window = rows[i:i + n]
                if len(window) < n or not all(follows(a[0], b[0]) for a, b in zip(window, window[1:])):
                    break
                if any(clean(raw).lower() in seen for _, raw in window):
                    break
                joined = re.sub(r"\s+", " ", " ".join(raw for _, raw in window).replace('\\"', '"')).strip()
                if not good_passage(joined):
                    continue
                text = clean(joined)
                if PASSAGE_CHARS[0] <= len(text) <= PASSAGE_CHARS[1] and PLAIN.match(text) and not held_out(text):
                    out.append((text, {(path, k) for k in range(i, i + n)}))
    extras = []
    if extra:
        with open(extra, encoding="utf-8") as f:
            for line in f:
                text = clean(line)
                if text and not text.startswith("#"):
                    if re.search(r"\d", text) or held_out(text):
                        sys.exit(f"extra line has digits or is held out: {text!r}")
                    extras.append(text)
    return out, extras


def pairs(phonemizer, voice, text):
    """Adjacent phoneme pairs, stress marks and length dropped, word gaps kept, `^` before each sentence."""
    phones = []
    for sentence in phonemizer.phonemize(voice, text):
        phones.append("^")
        for p in sentence:
            if unicodedata.combining(p) or p in "ˈˌː":
                continue
            phones.append("_" if p in " ,.;:!?" else p)
    return {(a, b) for a, b in zip(phones, phones[1:]) if not (a == b == "_") and b != "^"}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--source", nargs="+", required=True)
    p.add_argument("--extra")
    p.add_argument("--out", required=True)
    p.add_argument("--speakers", nargs="+", required=True)
    p.add_argument("--minutes", type=float, default=60)
    p.add_argument("--espeak-voice", default="en-gb-x-rp")
    p.add_argument("--passages", action="store_true", help="read runs of 2–4 consecutive sentences")
    p.add_argument("--exclude", nargs="*", default=[], help="earlier script directories whose lines to skip")
    p.add_argument("--tag", default="", help='put in the line ids, e.g. "r2_"')
    args = p.parse_args()

    from piper.phonemize_espeak import EspeakPhonemizer

    phonemizer = EspeakPhonemizer()
    exclude = set()
    for d in args.exclude:
        for name in os.listdir(d):
            if name.endswith(".tsv"):
                with open(os.path.join(d, name), encoding="utf-8") as f:
                    exclude |= {row.rstrip("\n").split("\t", 1)[1].lower() for row in f if row.strip()}
    pool, extras = candidates(args.source, args.extra, args.passages, exclude)
    # A passage carries the source lines it uses; a single line is its own.
    keys = [c[1] if isinstance(c, tuple) else {k} for k, c in enumerate(pool)]
    pool = [c[0] if isinstance(c, tuple) else c for c in pool]
    print(f"{len(pool)} candidate lines, {len(extras)} extra", file=sys.stderr)
    pool_pairs = [pairs(phonemizer, args.espeak_voice, t) for t in pool]

    budget = args.minutes * 60 * CHARS_PER_SECOND
    scripts = {s: list(extras) for s in args.speakers}
    chars = {s: sum(len(t) for t in extras) for s in args.speakers}
    counts = {s: {} for s in args.speakers}
    for s in args.speakers:
        for t in extras:
            for pr in pairs(phonemizer, args.espeak_voice, t):
                counts[s][pr] = counts[s].get(pr, 0) + 1

    def gain(i, c):
        # Diminishing returns per pair, per character so long lines don't win on length alone.
        return sum(1.0 / (1 + c.get(pr, 0)) for pr in pool_pairs[i]) / len(pool[i]) * 100

    # Lazy greedy (the gain only falls as counts rise), one heap per speaker, speakers taking turns.
    heaps = {s: [(-gain(i, counts[s]), i) for i in range(len(pool))] for s in args.speakers}
    for h in heaps.values():
        heapq.heapify(h)
    taken = set()
    active = list(args.speakers)
    while active:
        for s in list(active):
            h, c = heaps[s], counts[s]
            while h:
                neg, i = heapq.heappop(h)
                if keys[i] & taken:
                    continue
                g = gain(i, c)
                if h and g < -h[0][0] - 1e-9:
                    heapq.heappush(h, (-g, i))
                    continue
                taken |= keys[i]
                scripts[s].append(pool[i])
                chars[s] += len(pool[i])
                for pr in pool_pairs[i]:
                    c[pr] = c.get(pr, 0) + 1
                break
            if not h or chars[s] >= budget:
                active.remove(s)

    all_pairs = set().union(*pool_pairs)
    os.makedirs(args.out, exist_ok=True)
    for n, s in enumerate(args.speakers):
        with open(os.path.join(args.out, f"{s}.tsv"), "w", encoding="utf-8") as f:
            for k, text in enumerate(scripts[s]):
                f.write(f"{s}_{args.tag}{k:05d}\t{text}\n")
        covered = sum(1 for pr in all_pairs if counts[s].get(pr, 0) >= 3)
        print(
            f"{s}: {len(scripts[s])} lines, ~{chars[s] / CHARS_PER_SECOND / 60:.0f} min, "
            f"{covered}/{len(all_pairs)} phoneme pairs seen 3+ times",
            file=sys.stderr,
        )


if __name__ == "__main__":
    main()
