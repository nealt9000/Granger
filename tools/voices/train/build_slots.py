#!/usr/bin/env python3
"""Builds one voice's two-slot training set: speaker `narration` (0) and `dialogue` (1) (V4b).

    build_slots.py --voice NorthLondonNanny --base <dataset/NorthLondonNanny> --cuts <cut dir …> --out <dir>

Narration is the voice's earlier set (`--base`, wavs linked, not copied) minus its lines with double quotes, which
read speech as narration, plus the narration pieces of `cut_quotes.py`; dialogue is the dialogue pieces. A piece
comes from the first `--cuts` directory (in the order given) where it passed, so retakes only fill gaps. Pieces are
trimmed, levelled and resampled as `qc.py build` does lines. Narration rows come first, so Piper numbers the
speakers narration 0, dialogue 1 (ids follow first appearance).
"""
import argparse
import csv
import os
import sys

from qc import FADE, PEAK_DBFS, SAMPLE_RATE, TARGET_DBFS, speech_bounds


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--voice", required=True)
    p.add_argument("--base", required=True)
    p.add_argument("--cuts", nargs="+", required=True)
    p.add_argument("--out", required=True)
    a = p.parse_args()

    import librosa
    import numpy as np
    import soundfile

    wav_dir = os.path.join(a.out, "wav")
    os.makedirs(wav_dir, exist_ok=True)
    rows = {"narration": [], "dialogue": []}
    minutes = {"narration": 0.0, "dialogue": 0.0}

    with open(os.path.join(a.base, "metadata.csv"), encoding="utf-8") as f:
        for r in csv.reader(f, delimiter="|", quoting=csv.QUOTE_NONE, escapechar="\\"):
            name, text = r[0], r[-1]
            if '"' in text or "“" in text:
                continue
            src = os.path.realpath(os.path.join(a.base, "wav", name))
            dst = os.path.join(wav_dir, name)
            if not os.path.lexists(dst):
                os.symlink(src, dst)
            rows["narration"].append((name, "narration", text))
            minutes["narration"] += soundfile.info(src).duration / 60
    base_minutes = minutes["narration"]

    picked = {}  # (line id, piece number) → (cut dir, row)
    for d in a.cuts:
        with open(os.path.join(d, "pieces.tsv"), encoding="utf-8") as f:
            for line in f:
                piece_id, speaker, slot, text, seconds, wer, verdict = line.rstrip("\n").split("\t")
                if speaker != a.voice or verdict != "ok":
                    continue
                stem, number = piece_id.rsplit("_p", 1)
                key = (stem.rsplit("_t", 1)[0], int(number))
                picked.setdefault(key, (d, piece_id, slot, text))

    fade = int(FADE * SAMPLE_RATE)
    for (line_id, number), (d, piece_id, slot, text) in sorted(picked.items()):
        audio, rate = soundfile.read(os.path.join(d, piece_id + ".wav"), dtype="float32")
        lo, hi = speech_bounds(audio, rate)
        audio = audio[lo:hi]
        rms = float(np.sqrt(np.mean(audio**2))) or 1e-9
        gain = min(10 ** (TARGET_DBFS / 20) / rms, 10 ** (PEAK_DBFS / 20) / (float(np.max(np.abs(audio))) or 1e-9))
        audio = librosa.resample(audio * gain, orig_sr=rate, target_sr=SAMPLE_RATE, res_type="soxr_hq")
        audio[:fade] *= np.linspace(0, 1, fade)
        audio[-fade:] *= np.linspace(1, 0, fade)
        name = f"{line_id}_p{number}.wav"
        soundfile.write(os.path.join(wav_dir, name), np.clip(audio, -1, 1), SAMPLE_RATE, subtype="PCM_16")
        rows[slot].append((name, slot, text))
        minutes[slot] += len(audio) / SAMPLE_RATE / 60

    with open(os.path.join(a.out, "metadata.csv"), "w", encoding="utf-8") as f:
        csv.writer(f, delimiter="|", quoting=csv.QUOTE_NONE, escapechar="\\", lineterminator="\n").writerows(
            rows["narration"] + rows["dialogue"])
    with open(os.path.join(a.out, "minutes.tsv"), "w", encoding="utf-8") as f:
        f.write("speaker_id\tspeaker\tutterances\tminutes\n")
        for n, slot in enumerate(("narration", "dialogue")):
            f.write(f"{n}\t{slot}\t{len(rows[slot])}\t{minutes[slot]:.1f}\n")
    print(f"{a.voice}: narration {len(rows['narration'])} clips, {minutes['narration']:.1f} min "
          f"({base_minutes:.1f} from --base); dialogue {len(rows['dialogue'])} clips, {minutes['dialogue']:.1f} min",
          file=sys.stderr)


if __name__ == "__main__":
    main()
