#!/usr/bin/env python3
"""Linked Library spec §5.1: the contract every sidecar keeps, so the app, the catalog and the upkeep loop treat them alike.

A sidecar is a read-only SQLite file named after its source (`<zim stem>.<kind>`), holding a `meta(key, value)` table:

  kind            gqid, glink, …
  schemaVersion   the file layout the phone must read (the catalog's SidecarEntry.KNOWN holds the newest each app reads)
  sourceZim       the source file's name
  sourceSha256    the source file's sha256: the sidecar is valid for that exact file only
  builder         the tool that built it (tools/qindex/build.py, …)
  builderVersion  that tool's own version, so a rebuild after a builder fix is visible
  builtAt         UTC, ISO 8601

  meta.py check SIDECAR [--source ZIM]     verify the meta table (and, with --source, that it matches the file)
  meta.py release SIDECAR --id ID [--kind pack|book] [--out DIR]
                                           write the release descriptor catalog_gen.py reads (SIDECAR_KEYS)
"""
import argparse
import datetime
import hashlib
import json
import sqlite3
import sys
from pathlib import Path

REQUIRED = ("kind", "schemaVersion", "sourceZim", "sourceSha256", "builder", "builderVersion", "builtAt")


def sha256(path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def write_meta(db: sqlite3.Connection, kind: str, schema_version: int, source: Path, builder: str, builder_version: int,
               source_sha256: str = None, now=None):
    """Creates and fills the meta table. [source_sha256] saves re-hashing a big ZIM the builder already hashed."""
    now = now or datetime.datetime.now(datetime.timezone.utc)
    rows = {
        "kind": kind, "schemaVersion": str(schema_version), "sourceZim": Path(source).name,
        "sourceSha256": source_sha256 or sha256(source), "builder": builder, "builderVersion": str(builder_version),
        "builtAt": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    db.execute("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    db.executemany("INSERT OR REPLACE INTO meta VALUES (?, ?)", rows.items())
    return rows


def read_meta(path) -> dict:
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        return dict(db.execute("SELECT key, value FROM meta"))
    finally:
        db.close()


def check(path, source=None) -> list:
    """What's wrong with the sidecar's meta table, as lines; empty when it keeps the contract."""
    try:
        meta = read_meta(path)
    except sqlite3.Error as e:
        return [f"{path}: no meta table ({e})"]
    problems = [f"{path}: meta lacks {k}" for k in REQUIRED if not meta.get(k)]
    if meta.get("kind") and not str(path).endswith("." + meta["kind"]):
        problems.append(f"{path}: named .{Path(path).suffix[1:]}, but its kind is {meta['kind']}")
    if source is not None and meta.get("sourceSha256") and meta["sourceSha256"] != sha256(source):
        problems.append(f"{path}: built from another file than {source}")
    return problems


def descriptor(path, item_id: str, item_kind: str = "pack", url: str = None) -> dict:
    """The release descriptor catalog_gen.py's attach_sidecars reads (its SIDECAR_KEYS)."""
    meta = read_meta(path)
    return {"id": item_id, "kind": item_kind, "sidecar": meta["kind"], "fileName": Path(path).name,
            "sizeBytes": Path(path).stat().st_size, "sha256": sha256(path), "schemaVersion": int(meta["schemaVersion"]),
            "sourceSha256": meta["sourceSha256"], "builder": meta["builder"], "builderVersion": int(meta["builderVersion"]),
            "url": url}


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    c = sub.add_parser("check"); c.add_argument("sidecar"); c.add_argument("--source")
    r = sub.add_parser("release"); r.add_argument("sidecar"); r.add_argument("--id", required=True)
    r.add_argument("--kind", default="pack", choices=("pack", "book")); r.add_argument("--out", default=str(Path(__file__).parent / "releases"))
    a = p.parse_args(argv)
    if a.command == "check":
        problems = check(a.sidecar, a.source)
        for line in problems:
            print(line)
        return 1 if problems else 0
    problems = check(a.sidecar)
    if problems:
        raise SystemExit("\n".join(problems))
    d = descriptor(a.sidecar, a.id, a.kind)
    out = Path(a.out) / f"{a.id}.{d['sidecar']}.json"
    out.write_text(json.dumps(d, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
