#!/usr/bin/env python3
"""
Checks a `.gbooks` book manifest (Plan KB2, schema.md).

  check.py FILE [--source ZIM_FILE_OR_URL] [--previous FILE]

Exit 0: it keeps the contract and nothing drifted. Exit 1: a problem (the meta contract, a duplicate or empty title,
an unknown format, a path the source ZIM doesn't hold as a PDF/EPUB). Exit 2: no problem, but it drifted from
--previous beyond the thresholds below, which the upkeep loop reports as `bookpack-drift` (the pack stays held).

Drift thresholds (design §5):
- any book lost: a key in the previous manifest that isn't in this one (its readers' places would be orphaned);
- the item count moved by more than 10%;
- titles from file names (the weakest source) over 5% of items, and more than in the previous manifest.
"""
import argparse
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import build  # noqa: E402

COUNT_DRIFT = 0.10
FILENAME_SHARE = 0.05
FORMATS = {"pdf", "epub"}


def problems_of(manifest, source=None):
    out = []
    items = manifest["items"]
    if not items:
        out.append("no items")
    keys = [i["key"] for i in items]
    if len(set(keys)) != len(keys):
        out.append(f"{len(keys) - len(set(keys))} duplicate keys")
    for i in items:
        if not (i.get("title") or "").strip():
            out.append(f"{i['key']}: empty title")
        if i.get("format") not in FORMATS:
            out.append(f"{i['key']}: format {i.get('format')!r}")
    for k in ("title", "noun"):
        if not manifest["collection"].get(k):
            out.append(f"collection lacks {k}")
    if source is not None:
        pack = build.Pack(source)
        try:
            for i in items:
                e = pack.lookup(i["path"])
                if e is None or e["mimeType"] not in build.DOC_MIMES:
                    out.append(f"{i['key']}: {i['path']} isn't a PDF/EPUB in {source}")
        finally:
            pack.close()
    return out


def drift_of(manifest, previous):
    if previous is None:
        return []
    out = []
    now = {i["key"]: i for i in manifest["items"]}
    before = {i["key"]: i for i in previous["items"]}
    lost = [k for k in before if k not in now]
    if lost:
        sample = ", ".join(f"{before[k]['title']!r}" for k in lost[:5])
        out.append(f"{len(lost)} books lost (keys gone): {sample}{' …' if len(lost) > 5 else ''}")
    if before and abs(len(now) - len(before)) / len(before) > COUNT_DRIFT:
        out.append(f"item count {len(before)} -> {len(now)}")

    def filename_share(items):
        return sum(1 for i in items if i.get("titleSource") == "filename") / max(1, len(items))

    share, prev_share = filename_share(manifest["items"]), filename_share(previous["items"])
    if share > FILENAME_SHARE and share > prev_share:
        out.append(f"titles from file names {prev_share:.0%} -> {share:.0%} (the pages may have changed shape)")
    return out


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file")
    ap.add_argument("--source")
    ap.add_argument("--previous")
    a = ap.parse_args(argv)
    problems = []
    if not a.file.endswith(".json"):
        sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "sidecar"))
        import meta
        problems += meta.check(a.file, a.source if a.source and not a.source.startswith("http") else None)
    manifest = build.read_gbooks(a.file)
    problems += problems_of(manifest, a.source)
    drift = drift_of(manifest, build.read_gbooks(a.previous) if a.previous else None)
    for line in problems:
        print(f"problem: {line}")
    for line in drift:
        print(f"drift: {line}")
    if not problems and not drift:
        print(f"ok: {len(manifest['items'])} items")
    return 1 if problems else 2 if drift else 0


if __name__ == "__main__":
    sys.exit(main())
