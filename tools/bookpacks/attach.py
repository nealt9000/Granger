#!/usr/bin/env python3
"""
Puts `.gbooks` book manifests on their packs in a catalog, in place (Plan BK): each manifest goes on every entry built
from its source ZIM (the catalog's sha256 equals its `meta.sourceSha256`, and every flavour of that snapshot), replacing
that entry's `gbooks` sidecar and leaving its other sidecars alone. A manifest whose source the catalog no longer lists
is reported, not attached.

  attach.py CATALOG GBOOKS... --base-url URL

URL is where the files are served: the release's download URL (`https://github.com/<repo>/releases/download/<tag>/`),
or a local server for an emulator pass. The catalog must be signed again afterwards (Neal).
"""
import argparse
import hashlib
import json
import pathlib
import sqlite3
import sys


def meta_of(path):
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        return dict(db.execute("SELECT key, value FROM meta"))
    finally:
        db.close()


def attach(catalog, files, base_url):
    lines, attached = [], 0
    packs = catalog["packs"]
    for f in files:
        f = pathlib.Path(f)
        meta = meta_of(f)
        if meta.get("kind") != "gbooks":
            lines.append(f"  {f.name}: not a gbooks file, left out")
            continue
        source = next((p for p in packs if p["sha256"] == meta["sourceSha256"]), None)
        if source is None:
            lines.append(f"  {f.name}: built from {meta['sourceZim']}, which the catalog doesn't list; rebuild it")
            continue
        data = f.read_bytes()
        sidecar = {"kind": "gbooks", "url": base_url.rstrip("/") + "/" + f.name, "sha256": hashlib.sha256(data).hexdigest(),
                   "sizeBytes": len(data), "schemaVersion": int(meta["schemaVersion"])}
        for p in packs:
            if p["zimName"] == source["zimName"] and p["snapshotDate"] == source["snapshotDate"]:
                p["sidecars"] = [s for s in p.get("sidecars", []) if s["kind"] != "gbooks"] + [dict(sidecar)]
                attached += 1
        lines.append(f"  {source['zimName']}: {f.name} ({len(data) / 1e6:.2f} MB)")
    return attached, lines


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("catalog")
    ap.add_argument("gbooks", nargs="+")
    ap.add_argument("--base-url", required=True)
    a = ap.parse_args(argv)
    path = pathlib.Path(a.catalog)
    catalog = json.loads(path.read_text(encoding="utf-8"))
    attached, lines = attach(catalog, a.gbooks, a.base_url)
    path.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{path}: gbooks on {attached} entries")
    for line in lines:
        print(line)
    return 0


if __name__ == "__main__":
    sys.exit(main())
