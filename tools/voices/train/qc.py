#!/usr/bin/env python3
"""Checks generate.py's takes and builds the training set from the good ones (V4).

    qc.py check --script <dir> --raw <dir>/raw [--asr mlx-community/whisper-large-v3-turbo]
    qc.py build --script <dir> --raw <dir>/raw --out <work>/dataset/<name>

`check` transcribes every take with Whisper (through mlx-whisper, cached in `<raw>/asr.jsonl`) and writes
`<raw>/qc.tsv`: per take its word error rate against the script line, its speaking rate (characters per second of
trimmed speech), its peak, and a verdict. A take passes with a WER of at most `MAX_WER` (one wrong word in a short
line; Whisper's digits are spelled out first), a rate within `RATE` of its speaker's median (catching rushed, dragged or runaway takes), 1–15 s of speech,
and no clipping. Lines with no passing take go to `<raw>/retake.txt` for `generate.py --take N --only`.

`build` takes each line's best passing take (lowest WER, then the rate nearest the median), trims the silence to
`PAD` seconds each side, evens the loudness (RMS to `TARGET_DBFS`, peaks held under -1 dBFS), resamples 24 kHz to
22 050 Hz mono 16-bit (`soxr_hq`, as `build_dataset.py`), and writes `<out>/wav/` and `<out>/metadata.csv`
(`<file>.wav|<speaker>|<text>`, the script's text) plus `<out>/minutes.tsv`. Speakers are numbered in the order of
`--speakers` (default: the script files' order, sorted).

Run it with `~/.venvs/granger-asr` (mlx-whisper, jiwer, librosa, soundfile).
"""
import argparse
import csv
import json
import os
import re
import statistics
import sys

MAX_WER, MAX_WRONG_SHORT = 0.10, 1
RATE, RATE_SHORT, SHORT_CHARS = 0.30, 0.60, 50  # headings and short questions vary more
MIN_SECONDS, MAX_SECONDS = 1.0, 15.0
TOP_DB, PAD = 40, 0.12
TARGET_DBFS, PEAK_DBFS = -20.0, -1.0
SAMPLE_RATE = 22050

# British and American spellings Whisper may swap; folded before scoring.
SPELLING = [(r"(?<=[a-z]{3})our\b", "or"), (r"ise\b", "ize"), (r"ised\b", "ized"), (r"ising\b", "izing"), (r"tre\b", "ter"),
            (r"ogue\b", "og"), (r"ll(ed|ing)\b", r"l\1"), (r"tres\b", "ters"), (r"ae", "e")]


def spell_numbers(text):
    """Whisper writes digits ("1945", "1860s", "250"); the script has them in words, as SpokenText would."""
    from num2words import num2words

    def say(m):
        digits, plural = m.group(1).replace(",", ""), m.group(2)
        n = int(digits)
        spoken = num2words(n, to="year") if len(digits) == 4 and 1100 <= n <= 2099 else num2words(n)
        if plural:
            spoken = spoken[:-1] + "ies" if spoken.endswith("y") else spoken + "s"
        return f" {spoken} "

    return re.sub(r"(\d[\d,]*)(s\b)?", say, text)


def words(text):
    text = spell_numbers(text).lower().replace("’", "'").replace("—", " ").replace("-", " ")
    text = re.sub(r"[^a-z' ]", " ", text)
    out = []
    for w in text.split():
        w = w.strip("'")
        for pat, rep in SPELLING:
            w = re.sub(pat, rep, w)
        if w:
            out.append(w)
    return out


def scripts(script_dir, speakers):
    speakers = speakers or sorted(f[:-4] for f in os.listdir(script_dir) if f.endswith(".tsv"))
    lines = {}
    for s in speakers:
        with open(os.path.join(script_dir, f"{s}.tsv"), encoding="utf-8") as f:
            for row in f:
                if row.strip():
                    line_id, text = row.rstrip("\n").split("\t", 1)
                    lines[line_id] = (s, text)
    return speakers, lines


def takes(raw, lines):
    out = []
    for name in sorted(os.listdir(raw)):
        m = re.match(r"(.+)_t(\d+)\.wav$", name)
        if m and m.group(1) in lines:
            out.append((m.group(1), int(m.group(2)), os.path.join(raw, name)))
    return out


def speech_bounds(audio, rate):
    import librosa

    _, (a, b) = librosa.effects.trim(audio, top_db=TOP_DB)
    pad = int(PAD * rate)
    return max(0, a - pad), min(len(audio), b + pad)


def check(args):
    import jiwer
    import mlx_whisper
    import numpy as np
    import soundfile

    speakers, lines = scripts(args.script, args.speakers)
    cache_path = os.path.join(args.raw, "asr.jsonl")
    cache = {}
    if os.path.exists(cache_path):
        with open(cache_path, encoding="utf-8") as f:
            for row in f:
                r = json.loads(row)
                cache[r["file"]] = r["text"]
    cache_file = open(cache_path, "a", encoding="utf-8")

    rows = []
    all_takes = takes(args.raw, lines)
    for n, (line_id, take, path) in enumerate(all_takes):
        name = os.path.basename(path)
        if name not in cache:
            result = mlx_whisper.transcribe(path, path_or_hf_repo=args.asr, language="en",
                                            condition_on_previous_text=False)
            cache[name] = result["text"].strip()
            cache_file.write(json.dumps({"file": name, "text": cache[name]}) + "\n")
            cache_file.flush()
            if (n + 1) % 100 == 0:
                print(f"{n + 1}/{len(all_takes)} transcribed", file=sys.stderr, flush=True)
        audio, rate = soundfile.read(path, dtype="float32")
        if audio.ndim > 1:
            audio = audio.mean(axis=1)
        a, b = speech_bounds(audio, rate)
        seconds = (b - a) / rate
        speaker, text = lines[line_id]
        ref, hyp = words(text), words(cache[name])
        wrong = round(jiwer.wer(" ".join(ref), " ".join(hyp)) * len(ref)) if hyp else len(ref)
        rows.append({"id": line_id, "take": take, "speaker": speaker, "file": name, "seconds": seconds,
                     "rate": len(text) / seconds if seconds else 0, "wer": wrong / len(ref), "wrong": wrong,
                     "peak": float(np.max(np.abs(audio))) if len(audio) else 0.0, "heard": cache[name]})

    medians = {s: statistics.median([r["rate"] for r in rows if r["speaker"] == s] or [1]) for s in speakers}
    for r in rows:
        reasons = []
        if r["wer"] > MAX_WER and r["wrong"] > MAX_WRONG_SHORT:
            reasons.append("words")
        short = len(lines[r["id"]][1]) < SHORT_CHARS
        if abs(r["rate"] / medians[r["speaker"]] - 1) > (RATE_SHORT if short else RATE):
            reasons.append("rate")
        if not MIN_SECONDS <= r["seconds"] <= MAX_SECONDS:
            reasons.append("length")
        if r["peak"] >= 0.999:
            reasons.append("clipped")
        r["verdict"] = ",".join(reasons) or "ok"

    fields = ["id", "take", "speaker", "file", "verdict", "wer", "wrong", "seconds", "rate", "peak", "heard"]
    with open(os.path.join(args.raw, "qc.tsv"), "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fields, delimiter="\t", extrasaction="ignore", lineterminator="\n")
        w.writeheader()
        for r in rows:
            w.writerow({**r, "wer": f"{r['wer']:.3f}", "seconds": f"{r['seconds']:.2f}", "rate": f"{r['rate']:.1f}",
                        "peak": f"{r['peak']:.3f}"})
    passed = {r["id"] for r in rows if r["verdict"] == "ok"}
    retake = sorted(i for i in lines if i not in passed)
    with open(os.path.join(args.raw, "retake.txt"), "w", encoding="utf-8") as f:
        f.writelines(f"{i}\n" for i in retake)
    for s in speakers:
        mine = [r for r in rows if r["speaker"] == s]
        ok = [r for r in mine if r["verdict"] == "ok"]
        reasons = {}
        for r in mine:
            for why in r["verdict"].split(","):
                if why != "ok":
                    reasons[why] = reasons.get(why, 0) + 1
        print(f"{s}: {len(ok)}/{len(mine)} takes pass, {sum(r['seconds'] for r in ok) / 60:.1f} min; "
              f"median {medians[s]:.1f} chars/s; failures {reasons or 'none'}", file=sys.stderr)
    print(f"{len(retake)} lines without a passing take -> retake.txt", file=sys.stderr)


def build(args):
    import librosa
    import numpy as np
    import soundfile

    speakers, lines = scripts(args.script, args.speakers)
    with open(os.path.join(args.raw, "qc.tsv"), encoding="utf-8") as f:
        rows = [r for r in csv.DictReader(f, delimiter="\t") if r["verdict"] == "ok"]
    medians = {s: statistics.median([float(r["rate"]) for r in rows if r["speaker"] == s] or [1]) for s in speakers}
    best = {}
    for r in rows:
        key = (float(r["wer"]), abs(float(r["rate"]) / medians[r["speaker"]] - 1))
        if r["id"] not in best or key < best[r["id"]][0]:
            best[r["id"]] = (key, r)

    wav_dir = os.path.join(args.out, "wav")
    os.makedirs(wav_dir, exist_ok=True)
    out_rows, minutes = [], {s: 0.0 for s in speakers}
    for line_id in sorted(best, key=lambda i: (speakers.index(lines[i][0]), i)):
        r = best[line_id][1]
        speaker, text = lines[line_id]
        audio, rate = soundfile.read(os.path.join(args.raw, r["file"]), dtype="float32")
        if audio.ndim > 1:
            audio = audio.mean(axis=1)
        a, b = speech_bounds(audio, rate)
        audio = audio[a:b]
        rms = float(np.sqrt(np.mean(audio**2))) or 1e-9
        gain = min(10 ** (TARGET_DBFS / 20) / rms, 10 ** (PEAK_DBFS / 20) / (float(np.max(np.abs(audio))) or 1e-9))
        audio = librosa.resample(audio * gain, orig_sr=rate, target_sr=SAMPLE_RATE, res_type="soxr_hq")
        name = f"{line_id}.wav"
        soundfile.write(os.path.join(wav_dir, name), np.clip(audio, -1, 1), SAMPLE_RATE, subtype="PCM_16")
        out_rows.append((name, speaker, text))
        minutes[speaker] += len(audio) / SAMPLE_RATE / 60
    with open(os.path.join(args.out, "metadata.csv"), "w", encoding="utf-8") as f:
        csv.writer(f, delimiter="|", quoting=csv.QUOTE_NONE, escapechar="\\", lineterminator="\n").writerows(out_rows)
    with open(os.path.join(args.out, "minutes.tsv"), "w", encoding="utf-8") as f:
        f.write("speaker_id\tspeaker\tutterances\tminutes\n")
        for n, s in enumerate(speakers):
            count = sum(1 for row in out_rows if row[1] == s)
            f.write(f"{n}\t{s}\t{count}\t{minutes[s]:.1f}\n")
            print(f"speaker {n} {s}: {count} utterances, {minutes[s]:.1f} min", file=sys.stderr)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("command", choices=["check", "build"])
    p.add_argument("--script", required=True)
    p.add_argument("--raw", required=True)
    p.add_argument("--out")
    p.add_argument("--speakers", nargs="*")
    p.add_argument("--asr", default="mlx-community/whisper-large-v3-turbo")
    args = p.parse_args()
    if args.command == "build" and not args.out:
        p.error("build needs --out")
    check(args) if args.command == "check" else build(args)


if __name__ == "__main__":
    main()
