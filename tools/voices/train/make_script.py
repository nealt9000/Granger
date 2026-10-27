#!/usr/bin/env python3
"""Writes the reading script for a teacher-voice dataset (V4: a voice designed in Qwen3-TTS, taught to Piper).

    make_script.py --source <metadata.csv …> --extra granger_lines.txt --out <dir> \
        --speakers NorthLondonNanny SouthLondonProfessor --minutes 60 [--espeak-voice en-gb-x-rp]

Candidates are LibriTTS-R's normalized sentences (public-domain books, CC BY 4.0; the `metadata.csv` files V3's
`build_dataset.py` wrote) plus `--extra`, hand-written lines in spoken form (no digits). A line is kept when it is
plain text of 30–190 characters (about 2–14 s read aloud) ending a sentence, and is not one of the held-out listening
lines (`HELD_OUT`, `say.py`'s text). Lines are then picked greedily for phoneme-pair coverage under `--espeak-voice`,
dealt out in turn so each speaker gets a different script of about `--minutes` (at `CHARS_PER_SECOND`). Every
speaker reads all the extra lines.

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

CHARS_PER_SECOND = 14.0
MIN_CHARS, MAX_CHARS = 30, 190
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


def candidates(sources, extra):
    seen, out = set(), []
    for path in sources:
        with open(path, encoding="utf-8") as f:
            for row in csv.reader(f, delimiter="|", quoting=csv.QUOTE_NONE):
                text = clean(row[-1])
                if (
                    MIN_CHARS <= len(text) <= MAX_CHARS
                    and PLAIN.match(text)
                    and not held_out(text)
                    and text.lower() not in seen
                ):
                    seen.add(text.lower())
                    out.append(text)
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
    """Adjacent phoneme pairs, stress marks and length dropped, word gaps kept."""
    phones = []
    for sentence in phonemizer.phonemize(voice, text):
        for p in sentence:
            if unicodedata.combining(p) or p in "ˈˌː":
                continue
            phones.append("_" if p in " ,.;:!?" else p)
    return {(a, b) for a, b in zip(phones, phones[1:]) if not (a == b == "_")}


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--source", nargs="+", required=True)
    p.add_argument("--extra")
    p.add_argument("--out", required=True)
    p.add_argument("--speakers", nargs="+", required=True)
    p.add_argument("--minutes", type=float, default=60)
    p.add_argument("--espeak-voice", default="en-gb-x-rp")
    args = p.parse_args()

    from piper.phonemize_espeak import EspeakPhonemizer

    phonemizer = EspeakPhonemizer()
    pool, extras = candidates(args.source, args.extra)
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
                if i in taken:
                    continue
                g = gain(i, c)
                if h and g < -h[0][0] - 1e-9:
                    heapq.heappush(h, (-g, i))
                    continue
                taken.add(i)
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
                f.write(f"{s}_{k:05d}\t{text}\n")
        covered = sum(1 for pr in all_pairs if counts[s].get(pr, 0) >= 3)
        print(
            f"{s}: {len(scripts[s])} lines, ~{chars[s] / CHARS_PER_SECOND / 60:.0f} min, "
            f"{covered}/{len(all_pairs)} phoneme pairs seen 3+ times",
            file=sys.stderr,
        )


if __name__ == "__main__":
    main()
