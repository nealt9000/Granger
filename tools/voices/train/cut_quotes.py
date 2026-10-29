#!/usr/bin/env python3
"""Cuts teacher passages at their quote marks into narration and dialogue pieces (V4b, the dialogue slot).

    cut_quotes.py --script <dir> --raw <dir> --out <dir> [--speakers A B]

The phonemizer drops quote marks, so a voice can only learn how its teacher reads quoted speech if the quoted parts
are their own clips under their own speaker slot. For each take `<id>_t<n>.wav` of a line with double quotes:
Whisper's words (with timings) are aligned to the script's words, each boundary between a quote and the narration
around it is cut in the quietest 10 ms near the gap between the two words, and every piece is checked again on its
own (re-transcribed, so a clipped word fails). Writes `<out>/<id>_t<n>_p<k>.wav` (the take's rate, 20 ms fades) and
`<out>/pieces.tsv`: piece id, speaker, slot (`narration` or `dialogue`), text without quotes, seconds, wer, and
`ok` or why not. A take whose words don't all align at a boundary is skipped whole.
"""
import argparse
import difflib
import os
import re
import sys

from qc import envelope, scripts, takes, words

MODEL = "mlx-community/whisper-large-v3-turbo"
SEARCH = 0.15  # seconds either side of the gap between two words
FADE = 0.02
MIN_SECONDS = {"narration": 0.6, "dialogue": 0.4}
MAX_WER = 0.2


def speech(text):
    """Whether every double quote in a line opens speech: at the line's start or after
    punctuation. `The "sampler"` is a scare quote, not dialogue, and the line is left out."""
    text = text.replace("“", '"').replace("”", '"')
    opens = [m.start() for m in re.finditer('"', text)][::2]
    if text.count('"') % 2:
        return False
    return all((not text[:i].strip() or text[:i].rstrip()[-1] in ".,:;!?—\"")
               for i in opens)


def same(text):
    """Script words with SPELL_OUT's titles folded back to what Whisper writes."""
    return " ".join({"mister": "mr", "missus": "mrs", "doctor": "dr"}.get(w, w) for w in words(text))


def segments(text):
    """`(text, slot)` runs of a line, split at its double quotes, quote marks dropped."""
    out = []
    for k, part in enumerate(re.split(r'"', text.replace("“", '"').replace("”", '"'))):
        part = part.strip()
        if part:
            out.append((part, "dialogue" if k % 2 else "narration"))
    return out


def cut_point(audio, rate, lo, hi):
    """Seconds of the quietest 10 ms frame between lo and hi."""
    start = max(0, int(lo * rate))
    env = envelope(audio[start:int(hi * rate)], rate)
    if len(env) == 0:
        return (lo + hi) / 2
    return start / rate + int(env.argmin()) * 0.01 + 0.005


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--script", required=True)
    p.add_argument("--raw", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--speakers", nargs="*")
    a = p.parse_args()

    import jiwer
    import mlx_whisper
    import numpy as np
    import soundfile

    _, lines = scripts(a.script, a.speakers)
    os.makedirs(a.out, exist_ok=True)
    rows, skipped = [], 0
    for line_id, take, path in takes(a.raw, lines):
        speaker, text = lines[line_id]
        segs = segments(text)
        if not any(slot == "dialogue" for _, slot in segs) or not speech(text):
            continue
        audio, rate = soundfile.read(path, dtype="float32")
        heard = mlx_whisper.transcribe(path, path_or_hf_repo=MODEL, language="en", word_timestamps=True)
        timed = [(w, x) for s in heard["segments"] for x in s.get("words", []) for w in words(x["word"])]
        # Script words, each tagged with its segment.
        script_words = [(w, k) for k, (part, _) in enumerate(segs) for w in words(part)]
        match = difflib.SequenceMatcher(a=[w for w, _ in script_words], b=[w for w, _ in timed], autojunk=False)
        to_heard = {}
        for blk in match.get_matching_blocks():
            for j in range(blk.size):
                to_heard[blk.a + j] = blk.b + j
        cuts, ok = [0.0], True
        for i in range(len(script_words) - 1):
            if script_words[i][1] == script_words[i + 1][1]:
                continue
            if i not in to_heard or i + 1 not in to_heard:
                ok = False
                break
            end, start = timed[to_heard[i]][1]["end"], timed[to_heard[i + 1]][1]["start"]
            cuts.append(cut_point(audio, rate, min(end, start) - SEARCH / 2, max(end, start) + SEARCH / 2))
        if not ok:
            skipped += 1
            continue
        cuts.append(len(audio) / rate)
        fade = int(FADE * rate)
        ramp = np.linspace(0, 1, fade, dtype=np.float32)
        for k, ((part, slot), lo, hi) in enumerate(zip(segs, cuts, cuts[1:])):
            piece = audio[int(lo * rate):int(hi * rate)].copy()
            seconds = len(piece) / rate
            piece_id = f"{line_id}_t{take}_p{k}"
            if seconds < MIN_SECONDS[slot]:
                rows.append((piece_id, speaker, slot, part, f"{seconds:.2f}", "", "short"))
                continue
            piece[:fade] *= ramp
            piece[-fade:] *= ramp[::-1]
            target = os.path.join(a.out, piece_id + ".wav")
            soundfile.write(target, piece, rate)
            again = same(mlx_whisper.transcribe(target, path_or_hf_repo=MODEL, language="en")["text"])
            wer = jiwer.wer(same(part), again or "-")
            rows.append((piece_id, speaker, slot, part, f"{seconds:.2f}", f"{wer:.2f}", "ok" if wer <= MAX_WER else f"wer: {again}"))
    with open(os.path.join(a.out, "pieces.tsv"), "w", encoding="utf-8") as f:
        for r in rows:
            f.write("\t".join(r) + "\n")
    good = [r for r in rows if r[6] == "ok"]
    for slot in ("narration", "dialogue"):
        n = [r for r in good if r[2] == slot]
        print(f"{slot}: {len(n)} pieces ok, {sum(float(r[4]) for r in n) / 60:.1f} min", file=sys.stderr)
    print(f"{len(rows) - len(good)} pieces failed or short; {skipped} takes skipped (words didn't align)", file=sys.stderr)


if __name__ == "__main__":
    main()
