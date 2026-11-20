#!/usr/bin/env python3
"""
Publishes Granger's reading editions (Plan BK Task 6), in three steps that each run alone:

  publish_editions.py stage   EDITIONS_DIR OUT_DIR                 # copies each EPUB to OUT_DIR/<id>.epub (to upload)
  publish_editions.py gbooks  STAGED_DIR --base-url URL GBOOKS...  # writes the `edition` column of those packs' manifests
  publish_editions.py catalog STAGED_DIR --base-url URL CATALOG    # puts the set granger-reading-editions in the catalog

URL is the release's download URL (https://github.com/<repo>/releases/download/<tag>/). The descriptor is editions.json
beside this script (--config to change it). Changing a manifest changes its sha256: attach it again
(tools/bookpacks/attach.py) after `gbooks`. The catalog must be signed again afterwards (Neal).
"""
import argparse
import datetime
import hashlib
import json
import pathlib
import shutil
import sqlite3
import sys

HERE = pathlib.Path(__file__).resolve().parent


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def staged(cfg, folder, base_url):
    """Each edition with its published file: url, sha256, bytes."""
    out = []
    for e in cfg["editions"]:
        f = pathlib.Path(folder) / f"{e['id']}.epub"
        if not f.is_file():
            raise SystemExit(f"{f} is missing: run `stage` first")
        out.append(dict(e, url=base_url.rstrip("/") + "/" + f.name, sha256=sha256(f), sizeBytes=f.stat().st_size))
    return out


def stage(cfg, src, out):
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    for e in cfg["editions"]:
        shutil.copyfile(pathlib.Path(src) / e["file"], out / f"{e['id']}.epub")
        print(f"  {e['id']}.epub")


def write_gbooks(eds, files):
    """Sets the `edition` column of every item an edition names, in the manifests given; returns lines to print."""
    lines = []
    for f in files:
        db = sqlite3.connect(f)
        try:
            zim = dict(db.execute("SELECT key, value FROM collection")).get("zimName", "")
            db.execute("UPDATE items SET edition = NULL")
            for e in eds:
                payload = json.dumps({"id": e["id"], "url": e["url"], "sha256": e["sha256"], "bytes": e["sizeBytes"],
                                      "title": e["title"]}, ensure_ascii=False, sort_keys=True)
                for item in e["items"]:
                    if item["zimName"] != zim:
                        continue
                    n = db.execute("UPDATE items SET edition = ? WHERE key = ?", (payload, item["key"])).rowcount
                    if n != 1:
                        raise SystemExit(f"{f}: no item {item['key']!r}")
                    lines.append(f"  {zim}: {item['key']} -> {e['id']}")
            db.commit()
            db.execute("VACUUM")
        finally:
            db.close()
    return lines


def put_set(catalog, cfg, eds, today=None):
    today = today or datetime.date.today().isoformat()
    books = [{"id": e["id"], "title": e["title"], "author": e["author"], "description": e["description"],
              "category": e["category"], "language": cfg["set"]["language"], "sizeBytes": e["sizeBytes"],
              "sha256": e["sha256"], "urls": [e["url"]], "releaseDate": today, "license": e["license"],
              "collection": cfg["set"]["id"], "published": e.get("published", 0)} for e in eds]
    s = dict(cfg["set"], books=books, presets=[{"id": cfg["set"]["id"], "title": cfg["set"]["title"],
                                                  "items": [b["id"] for b in books]}])
    sets = [x for x in catalog.get("bookSets", []) if x["id"] != s["id"]]
    catalog["bookSets"] = sets + [s]
    return s


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=["stage", "gbooks", "catalog"])
    ap.add_argument("folder", help="stage: the built EPUBs; gbooks/catalog: the staged folder")
    ap.add_argument("rest", nargs="+", help="stage: OUT_DIR; gbooks: GBOOKS files; catalog: CATALOG")
    ap.add_argument("--base-url")
    ap.add_argument("--config", default=str(HERE / "editions.json"))
    a = ap.parse_args(argv)
    cfg = json.loads(pathlib.Path(a.config).read_text(encoding="utf-8"))
    if a.command == "stage":
        stage(cfg, a.folder, a.rest[0])
        return 0
    if not a.base_url:
        raise SystemExit("--base-url is needed")
    eds = staged(cfg, a.folder, a.base_url)
    if a.command == "gbooks":
        for line in write_gbooks(eds, a.rest):
            print(line)
    else:
        path = pathlib.Path(a.rest[0])
        catalog = json.loads(path.read_text(encoding="utf-8"))
        s = put_set(catalog, cfg, eds)
        catalog["generatedAt"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        path.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"{path}: set {s['id']} with {len(s['books'])} books")
    return 0


if __name__ == "__main__":
    sys.exit(main())
