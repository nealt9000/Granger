#!/usr/bin/env python3
"""Reads make_script.py's scripts aloud through the audio.cpp server's Qwen3-TTS clone model (V4).

    generate.py --script <dir> --out <dir>/raw [--speakers A B] [--take 0] [--only ids.txt] \
        [--server http://127.0.0.1:8080] [--model qwen3-clone]

Each speaker is a voice in the server's `voice-library/` (`voice.sh add <name> <clip> -t <transcript>`): the
reference clip and its exact transcript. Lines go round-robin across speakers, one request at a time (the server is
serial). Writes `<out>/<id>_t<take>.wav` (24 kHz mono, as the server returns it) and appends one JSON line per
request to `<out>/log.jsonl` (seed, wall time, audio seconds) so the data can be traced and regenerated. A WAV that
exists is skipped, so rerunning resumes. `--take N` with `--only` (ids that failed qc.py) makes retakes with fresh
seeds. Standard library only.
"""
import argparse
import json
import os
import sys
import time
import urllib.request
import wave
import zlib


def read_script(path):
    with open(path, encoding="utf-8") as f:
        return [tuple(line.rstrip("\n").split("\t", 1)) for line in f if line.strip()]


def seed_for(line_id, take):
    # Stable per line and take, so a rerun or a regeneration months later gives the same audio.
    return zlib.crc32(f"{line_id}#{take}".encode()) % 1_000_000_000


def speak(server, model, voice, text, seed, timeout):
    body = json.dumps({"model": model, "input": text, "voice": voice, "options": {"seed": str(seed)}}).encode()
    req = urllib.request.Request(
        f"{server}/v1/audio/speech", data=body, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--script", required=True, help="make_script.py's output directory")
    p.add_argument("--out", required=True)
    p.add_argument("--speakers", nargs="*", help="default: every <speaker>.tsv in --script")
    p.add_argument("--take", type=int, default=0)
    p.add_argument("--only", help="a file of line ids to (re)generate")
    p.add_argument("--server", default="http://127.0.0.1:8080")
    p.add_argument("--model", default="qwen3-clone")
    p.add_argument("--timeout", type=float, default=180)
    args = p.parse_args()

    speakers = args.speakers or sorted(f[:-4] for f in os.listdir(args.script) if f.endswith(".tsv"))
    only = None
    if args.only:
        with open(args.only, encoding="utf-8") as f:
            only = {line.split()[0] for line in f if line.strip()}
    queues = {s: [(i, t) for i, t in read_script(os.path.join(args.script, f"{s}.tsv")) if only is None or i in only]
              for s in speakers}
    order = []
    for k in range(max(len(q) for q in queues.values())):
        order += [(s, queues[s][k]) for s in speakers if k < len(queues[s])]

    os.makedirs(args.out, exist_ok=True)
    log = open(os.path.join(args.out, "log.jsonl"), "a", encoding="utf-8")
    done = audio_total = wall_total = 0
    for n, (speaker, (line_id, text)) in enumerate(order):
        target = os.path.join(args.out, f"{line_id}_t{args.take}.wav")
        if os.path.exists(target):
            continue
        seed = seed_for(line_id, args.take)
        start = time.time()
        try:
            data = speak(args.server, args.model, speaker, text, seed, args.timeout)
        except Exception as e:  # a runaway or a server hiccup: log it, qc.py sees the line missing
            log.write(json.dumps({"id": line_id, "take": args.take, "seed": seed, "error": str(e)}) + "\n")
            log.flush()
            print(f"{line_id}: {e}", file=sys.stderr)
            continue
        wall = time.time() - start
        with open(target + ".tmp", "wb") as f:
            f.write(data)
        with wave.open(target + ".tmp") as w:
            seconds = w.getnframes() / w.getframerate()
        os.replace(target + ".tmp", target)
        log.write(json.dumps({"id": line_id, "speaker": speaker, "take": args.take, "seed": seed, "model": args.model,
                              "wall": round(wall, 2), "seconds": round(seconds, 2), "text": text}) + "\n")
        log.flush()
        done += 1
        audio_total += seconds
        wall_total += wall
        if done % 25 == 0:
            print(f"{n + 1}/{len(order)}: {audio_total / 60:.1f} min of audio in {wall_total / 60:.1f} min "
                  f"(RTF {wall_total / audio_total:.2f})", file=sys.stderr, flush=True)
    print(f"done: {done} new, {audio_total / 60:.1f} min of audio", file=sys.stderr)


if __name__ == "__main__":
    main()
