#!/usr/bin/env python3
"""Packs a sherpa-onnx Piper voice as the one zip the catalog lists (Plan V1, Task 8).

    tools/voices/pack_voice.py vits-piper-en_US-ljspeech-medium --out /path/to/dir

Downloads `<id>.tar.bz2` from sherpa-onnx's `tts-models` release (or takes `--from <tar.bz2|dir>`), keeps the model,
`tokens.txt`, `espeak-ng-data/`, `MODEL_CARD` and `LICENSE`, writes a `manifest.json` (name, size, sha256 per file,
which Readiness checks after the zip is unpacked and deleted), and writes `<id>.zip` deterministically (sorted
names, fixed timestamps, deflate) so the same input always gives the same sha256. Prints the catalog entry's
`sizeBytes` and `sha256`. Run it in an empty directory: downloaded archives are untrusted input.
"""
import argparse
import hashlib
import io
import json
import os
import shutil
import sys
import tarfile
import tempfile
import urllib.request
import zipfile

RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
KEEP_DIRS = {"espeak-ng-data"}
KEEP_FILES = {"tokens.txt", "MODEL_CARD", "LICENSE", "README.md"}
EPOCH = (2026, 1, 1, 0, 0, 0)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch(voice_id, into):
    url = RELEASE + voice_id + ".tar.bz2"
    target = os.path.join(into, voice_id + ".tar.bz2")
    print("fetching", url, file=sys.stderr)
    with urllib.request.urlopen(url) as r, open(target, "wb") as f:
        shutil.copyfileobj(r, f)
    return target


def unpack(archive, into):
    with tarfile.open(archive, "r:bz2") as t:
        for m in t.getmembers():
            if m.name.startswith("/") or ".." in m.name.split("/"):
                raise SystemExit(f"refusing path {m.name}")
        t.extractall(into, filter="data")
    dirs = [d for d in os.listdir(into) if os.path.isdir(os.path.join(into, d))]
    if len(dirs) != 1:
        raise SystemExit(f"expected one directory in the archive, found {dirs}")
    return os.path.join(into, dirs[0])


def wanted(voice_dir):
    """Files to ship, as (relative path, absolute path), sorted."""
    out = []
    for name in sorted(os.listdir(voice_dir)):
        path = os.path.join(voice_dir, name)
        if os.path.isdir(path):
            if name in KEEP_DIRS:
                for root, _, files in os.walk(path):
                    for f in sorted(files):
                        full = os.path.join(root, f)
                        out.append((os.path.relpath(full, voice_dir), full))
        elif name.endswith(".onnx") or name.endswith(".onnx.json") or name in KEEP_FILES:
            out.append((name, path))
    out.sort()
    return out


def pack(voice_id, voice_dir, out_dir):
    files = wanted(voice_dir)
    models = [n for n, _ in files if n.endswith(".onnx")]
    if len(models) != 1 or not any(n == "tokens.txt" for n, _ in files) or not any(n.startswith("espeak-ng-data/") for n, _ in files):
        raise SystemExit(f"not a Piper voice directory: {voice_dir}")
    manifest = {
        "id": voice_id,
        "engine": "piper",
        "files": [{"name": n, "sizeBytes": os.path.getsize(p), "sha256": sha256(p)} for n, p in files],
    }
    os.makedirs(out_dir, exist_ok=True)
    zip_path = os.path.join(out_dir, voice_id + ".zip")
    with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        info = zipfile.ZipInfo("manifest.json", date_time=EPOCH)
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o644 << 16
        z.writestr(info, json.dumps(manifest, indent=1, sort_keys=True).encode("utf-8"))
        for n, p in files:
            info = zipfile.ZipInfo(n, date_time=EPOCH)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            with open(p, "rb") as f:
                z.writestr(info, f.read())
    size = os.path.getsize(zip_path)
    digest = sha256(zip_path)
    print(json.dumps({"id": voice_id, "file": zip_path, "sizeBytes": size, "sha256": digest, "unpackedBytes": sum(f["sizeBytes"] for f in manifest["files"]), "files": len(files)}, indent=1))
    return zip_path


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("voice_id")
    ap.add_argument("--out", required=True, help="directory the zip is written to")
    ap.add_argument("--from", dest="source", help="a .tar.bz2 already downloaded, or an unpacked voice directory")
    args = ap.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        source = args.source
        if source and os.path.isdir(source):
            voice_dir = source
        else:
            archive = source or fetch(args.voice_id, tmp)
            voice_dir = unpack(archive, os.path.join(tmp, "x"))
        pack(args.voice_id, voice_dir, args.out)


if __name__ == "__main__":
    main()
