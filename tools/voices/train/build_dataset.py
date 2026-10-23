#!/usr/bin/env python3
"""Builds the training set for the Granger voice from LibriTTS-R (Plan V3).

    build_dataset.py list  --libritts <work>/libritts_r [--subsets train-clean-100 train-clean-360] [--readers 3922 4535]
    build_dataset.py build --libritts <work>/libritts_r --out <work>/dataset/<name> --readers 3922 4535 3307:10 … [--max-minutes 60]

`list` prints each reader's gender, name and minutes of usable audio per subset, most audio first (or only the
readers given), for choosing speakers (Decision 2).

`build` takes the readers' utterances in order (longest chapters first, until `--max-minutes`, or the minutes after a
reader's colon: `3307:10`), leaves out the
utterances whose speech restoration failed and those outside 1–15 s, resamples 24 kHz to 22 050 Hz mono 16-bit
(`soxr_hq`) into `<out>/wav/`, and writes `<out>/metadata.csv` as piper1-gpl wants it: `<file>.wav|<reader>|<text>`
for several readers, `<file>.wav|<text>` for one, using LibriTTS-R's normalized text. Piper numbers the speakers in
the order they first appear, so `--readers` sets the speaker ids. `<out>/minutes.tsv` records what went in.

Run it with the training venv's Python (it needs `soundfile` and `librosa`).
"""
import argparse
import csv
import glob
import os
import sys
from collections import defaultdict

SUBSETS = ["train-clean-100", "train-clean-360"]
SAMPLE_RATE = 22050
MIN_SECONDS, MAX_SECONDS = 1.0, 15.0


def readers_info(root):
    """reader id -> (gender, name) from LibriTTS-R's speakers.tsv / SPEAKERS.txt."""
    info = {}
    for path in glob.glob(os.path.join(root, "**", "speakers.tsv"), recursive=True):
        with open(path, encoding="utf-8") as f:
            for row in csv.reader(f, delimiter="\t"):
                if row and row[0].isdigit():
                    info[row[0]] = (row[1].strip(), row[3].strip() if len(row) > 3 else "")
    for path in glob.glob(os.path.join(root, "**", "SPEAKERS.txt"), recursive=True):
        with open(path, encoding="utf-8") as f:
            for line in f:
                cols = [c.strip() for c in line.split("|")]
                if len(cols) >= 5 and cols[0].isdigit():
                    info.setdefault(cols[0], (cols[1], cols[4]))
    return info


def failed_ids(root):
    """Utterance ids whose speech restoration failed (listed by LibriTTS-R)."""
    ids = set()
    for path in glob.glob(os.path.join(root, "**", "*failed*", "**", "*.txt"), recursive=True) + glob.glob(
        os.path.join(root, "**", "*failed*.txt"), recursive=True
    ):
        with open(path, encoding="utf-8") as f:
            for line in f:
                for token in line.replace(",", " ").split():
                    ids.add(os.path.splitext(os.path.basename(token))[0])
    return ids


def utterances(root, subsets, reader):
    """(utterance id, wav path, seconds, normalized text) for one reader, longest chapters first."""
    import soundfile

    by_chapter = defaultdict(list)
    for subset in subsets:
        for wav in glob.glob(os.path.join(root, "LibriTTS_R", subset, reader, "*", "*.wav")):
            text_path = wav[: -len(".wav")] + ".normalized.txt"
            if not os.path.exists(text_path):
                continue
            with open(text_path, encoding="utf-8") as f:
                text = " ".join(f.read().split())
            utt = os.path.basename(wav)[: -len(".wav")]
            chapter = utt.split("_")[1]
            by_chapter[chapter].append((utt, wav, soundfile.info(wav).duration, text))
    chapters = sorted(by_chapter.values(), key=lambda us: -sum(u[2] for u in us))
    return [u for us in chapters for u in sorted(us)]


def usable(utts, failed):
    return [u for u in utts if u[0] not in failed and MIN_SECONDS <= u[2] <= MAX_SECONDS and "|" not in u[3]]


def cmd_list(a):
    info = readers_info(a.libritts)
    failed = failed_ids(a.libritts)
    rows = []
    for subset in a.subsets:
        for d in glob.glob(os.path.join(a.libritts, "LibriTTS_R", subset, "*")):
            reader = os.path.basename(d)
            if a.readers and reader not in a.readers:
                continue
            minutes = sum(u[2] for u in usable(utterances(a.libritts, [subset], reader), failed)) / 60
            gender, name = info.get(reader, ("?", ""))
            rows.append((minutes, reader, subset, gender, name))
    rows.sort(reverse=True)
    print("reader\tgender\tsubset\tminutes\tname")
    for minutes, reader, subset, gender, name in rows[: a.top]:
        print(f"{reader}\t{gender}\t{subset}\t{minutes:.1f}\t{name}")


def cmd_build(a):
    import librosa
    import numpy as np
    import soundfile

    failed = failed_ids(a.libritts)
    wav_dir = os.path.join(a.out, "wav")
    os.makedirs(wav_dir, exist_ok=True)
    multi = len(a.readers) > 1
    rows, minutes = [], []
    for spec in a.readers:
        reader, _, cap = spec.partition(":")
        cap = float(cap) if cap else a.max_minutes
        total = 0.0
        for utt, wav, seconds, text in usable(utterances(a.libritts, a.subsets, reader), failed):
            if total + seconds > cap * 60:
                break
            target = os.path.join(wav_dir, utt + ".wav")
            if not os.path.exists(target):
                audio, rate = soundfile.read(wav, dtype="float32", always_2d=True)
                audio = librosa.resample(audio.mean(axis=1), orig_sr=rate, target_sr=SAMPLE_RATE, res_type="soxr_hq")
                soundfile.write(target, np.clip(audio, -1, 1), SAMPLE_RATE, subtype="PCM_16")
            rows.append([utt + ".wav", reader, text] if multi else [utt + ".wav", text])
            total += seconds
        if total == 0:
            raise SystemExit(f"reader {reader}: no usable audio in {a.subsets}")
        minutes.append((reader, total / 60))
        print(f"{reader}\t{total / 60:.1f} min", file=sys.stderr)
    with open(os.path.join(a.out, "metadata.csv"), "w", encoding="utf-8", newline="") as f:
        csv.writer(f, delimiter="|", quoting=csv.QUOTE_NONE, escapechar="\\", lineterminator="\n").writerows(rows)
    with open(os.path.join(a.out, "minutes.tsv"), "w", encoding="utf-8") as f:
        f.write("reader\tminutes\n")
        f.writelines(f"{r}\t{m:.1f}\n" for r, m in minutes)
    print(os.path.join(a.out, "metadata.csv"))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["list", "build"])
    p.add_argument("--libritts", required=True, help="the directory fetch.py unpacked LibriTTS-R into")
    p.add_argument("--subsets", nargs="+", default=SUBSETS)
    p.add_argument("--readers", nargs="+", help="LibriTTS reader ids; for build, in speaker-id order, each optionally :<minutes>")
    p.add_argument("--out")
    p.add_argument("--max-minutes", type=float, default=60.0, help="per reader")
    p.add_argument("--top", type=int, default=40, help="list: rows to print")
    a = p.parse_args()
    if a.command == "list":
        cmd_list(a)
    else:
        if not (a.out and a.readers):
            p.error("build needs --out and --readers")
        cmd_build(a)


if __name__ == "__main__":
    main()
