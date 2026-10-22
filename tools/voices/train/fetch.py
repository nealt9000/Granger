#!/usr/bin/env python3
"""Fetches what the Granger voice is trained from (Plan V3).

    tools/voices/train/fetch.py base     --work <dir>
    tools/voices/train/fetch.py libritts --work <dir> train_clean_100 [train_clean_360 ...]

`base`: Piper's `en_US-ljspeech-medium` checkpoint (LJ Speech, public domain) with its `config.json` and `MODEL_CARD`
from `rhasspy/piper-checkpoints`, checked against the published sha256, into `<work>/base/`; and sherpa-onnx's
`vits-piper-en_US-ljspeech-medium` voice (for `espeak-ng-data/` and the reference `tokens.txt`), unpacked into
`<work>/base/sherpa/`.

`libritts`: LibriTTS-R subsets (OpenSLR 141, CC BY 4.0), checked against OpenSLR's `md5sum.txt`, unpacked into
`<work>/libritts_r/` (giving `<work>/libritts_r/LibriTTS_R/<subset>/`), plus `doc` and the list of files where the
speech restoration failed (which `build_dataset.py` leaves out).

Nothing of `lessac` or `libritts_r`-the-voice is fetched here: they are not training inputs (Decision 5). Downloads
are untrusted input: archives are unpacked with tarfile's `data` filter and are never executed.
"""
import argparse
import hashlib
import os
import shutil
import sys
import tarfile
import urllib.request

CHECKPOINTS = "https://huggingface.co/datasets/rhasspy/piper-checkpoints/resolve/main/en/en_US/ljspeech/medium/"
CHECKPOINT = "lj-med_1000.ckpt"
CHECKPOINT_SHA256 = "dcf2449bdbdaad09256a08dfac211c59f6b36ce8d3f244fd844a9eb1d7384c7c"
SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-ljspeech-medium.tar.bz2"
OPENSLR = "https://www.openslr.org/resources/141/"
EXTRAS = ["doc.tar.gz", "libritts_r_failed_speech_restoration_examples.tar.gz"]


def digest(path, algorithm):
    h = hashlib.new(algorithm)
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def download(url, target):
    """Downloads to `target` unless it is already there (a `.part` file is resumed from scratch)."""
    if os.path.exists(target):
        return target
    print("fetching", url, file=sys.stderr)
    part = target + ".part"
    with urllib.request.urlopen(url) as r, open(part, "wb") as f:
        shutil.copyfileobj(r, f, 1 << 22)
    os.replace(part, target)
    return target


def unpack(archive, into):
    with tarfile.open(archive) as t:
        for m in t.getmembers():
            if m.name.startswith("/") or ".." in m.name.split("/"):
                raise SystemExit(f"refusing path {m.name} in {archive}")
        t.extractall(into, filter="data")


def base(work):
    into = os.path.join(work, "base")
    os.makedirs(into, exist_ok=True)
    ckpt = download(CHECKPOINTS + CHECKPOINT, os.path.join(into, CHECKPOINT))
    if digest(ckpt, "sha256") != CHECKPOINT_SHA256:
        raise SystemExit(f"{ckpt}: sha256 does not match {CHECKPOINT_SHA256}")
    for name in ("config.json", "MODEL_CARD"):
        download(CHECKPOINTS + name, os.path.join(into, name))
    sherpa = os.path.join(into, "sherpa")
    if not os.path.isdir(os.path.join(sherpa, "vits-piper-en_US-ljspeech-medium")):
        os.makedirs(sherpa, exist_ok=True)
        unpack(download(SHERPA, os.path.join(into, os.path.basename(SHERPA))), sherpa)
    print(into)


def libritts(work, subsets):
    into = os.path.join(work, "libritts_r")
    os.makedirs(into, exist_ok=True)
    sums_path = download(OPENSLR + "md5sum.txt", os.path.join(into, "md5sum.txt"))
    sums = {}
    with open(sums_path) as f:
        for line in f:
            md5, name = line.split()
            sums[name.lstrip("*")] = md5
    for name in EXTRAS + [s + ".tar.gz" for s in subsets]:
        done = os.path.join(into, name + ".unpacked")
        if os.path.exists(done):
            continue
        archive = download(OPENSLR + name, os.path.join(into, name))
        if name in sums and digest(archive, "md5") != sums[name]:
            raise SystemExit(f"{archive}: md5 does not match OpenSLR's md5sum.txt")
        unpack(archive, into)
        open(done, "w").close()
        print("unpacked", name, file=sys.stderr)
    print(into)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("what", choices=["base", "libritts"])
    p.add_argument("subsets", nargs="*", help="LibriTTS-R subsets, e.g. train_clean_100 train_clean_360")
    p.add_argument("--work", required=True)
    a = p.parse_args()
    if a.what == "base":
        base(a.work)
    else:
        libritts(a.work, a.subsets or ["train_clean_100"])


if __name__ == "__main__":
    main()
