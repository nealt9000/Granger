#!/usr/bin/env python3
"""Builds, signs and verifies Granger's catalog of packs and models.

Packs come from Kiwix's OPDS catalog plus the hand-picked list in curated.json; each pack is pinned to
one snapshot by its sha256 from Kiwix's metalink file. Models are listed in curated.json as-is.

  catalog_gen.py keygen --out ~/.config/granger/catalog-signing.key   # once; prints the public key
  catalog_gen.py build  --curated tools/catalog-gen/curated.json --out catalog/catalog.json
  catalog_gen.py sign   --key ~/.config/granger/catalog-signing.key catalog/catalog.json
  catalog_gen.py verify --public-key catalog/public-key.txt catalog/catalog.json

With a "kiwix" setting in curated.json, every pack in Kiwix's catalog in one of its languages is listed too, when its
family has a row in the family table (kiwix_families.json) that gives its license and no hold. The curated rows win
for their own packs and are marked featured. `build` writes what it kept and left out to catalog/kiwix-report.txt.

Packs we build ourselves (tools/pack-builder) are listed with a "release" row in curated.json that names
their release descriptor; the hosted file's size is checked against it. A "books" row names the descriptor
of an e-book collection (`pack_builder.py describe-epubs`): every book becomes a catalog entry and the
collection gets a preset. "@<collection>" in a preset's items expands to that collection's book ids.

The app trusts only catalogs signed by the key whose public half is in catalog/public-key.txt.
"""
import argparse
import base64
import concurrent.futures
import datetime
import json
import os
import pathlib
import re
import sys
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

FORMAT_VERSION = 1
OPDS_ENTRIES = "https://library.kiwix.org/catalog/v2/entries"
ACQUISITION = "http://opds-spec.org/acquisition/open-access"
ATOM = "{http://www.w3.org/2005/Atom}"
METALINK = "{urn:ietf:params:xml:ns:metalink}"
# Mirrors that have been around for years; the canonical download.kiwix.org URL already picks a nearby one.
STABLE_MIRRORS = ("ftpmirror.your.org", "dumps.wikimedia.org")
MAX_URLS = 3
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
RELEASE_KEYS = ("id", "description", "language", "sizeBytes", "sha256", "urls", "snapshotDate",
                "hasFulltextIndex", "zimUuid", "zimName")


def http_get(url):
    request = urllib.request.Request(url, headers={"User-Agent": "granger-catalog-gen"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def http_head(url):
    """The Content-Length the URL finally serves, after redirects (GitHub releases redirect to a CDN)."""
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "granger-catalog-gen"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return int(response.headers["Content-Length"])


def opds_entries(xml_bytes):
    entries = []
    for entry in ET.fromstring(xml_bytes).findall(f"{ATOM}entry"):
        def text(tag):
            return (entry.findtext(f"{ATOM}{tag}") or "").strip()
        link = next((l for l in entry.findall(f"{ATOM}link") if l.get("rel") == ACQUISITION), None)
        if link is None:
            continue
        entries.append({
            "uuid": text("id").removeprefix("urn:uuid:"),
            "title": text("title"),
            "summary": text("summary"),
            "language": text("language"),
            "name": text("name"),
            "flavour": text("flavour"),
            "updated": text("updated"),
            "tags": text("tags").split(";"),
            "category": text("category"),
            "meta4": link.get("href"),
            "length": int(link.get("length")),
        })
    return entries


def metalink(xml_bytes):
    file = ET.fromstring(xml_bytes).find(f"{METALINK}file")
    sha256 = next(h.text.strip() for h in file.findall(f"{METALINK}hash") if h.get("type") == "sha-256")
    urls = sorted(file.findall(f"{METALINK}url"), key=lambda u: int(u.get("priority", "999")))
    return {
        "name": file.get("name"),
        "size": int(file.findtext(f"{METALINK}size")),
        "sha256": sha256.lower(),
        "mirrors": [u.text.strip() for u in urls],
    }


def kiwix_feed(fetch=http_get):
    """Every entry in Kiwix's catalog, in one request."""
    return opds_entries(fetch(f"{OPDS_ENTRIES}?count=-1"))


def pack_entry(curated, fetch=http_get, feed=None):
    """A curated Kiwix pack: its newest snapshot, from [feed] when given, else from a query for its name."""
    name, flavour = curated["name"], curated.get("flavour", "")
    if feed is None:
        query = urllib.parse.urlencode({"name": name, "count": 50})
        feed = opds_entries(fetch(f"{OPDS_ENTRIES}?{query}"))
    matches = [e for e in feed if e["name"] == name and e["flavour"] == flavour]
    if not matches:
        raise SystemExit(f"{name} ({flavour or 'no flavour'}): not in the Kiwix catalog")
    return pin(max(matches, key=lambda e: e["updated"]), curated, fetch)


def pin(entry, row, fetch=http_get):
    """[entry] pinned to its snapshot's sha256 and mirrors; title, category and license come from [row]."""
    name, flavour = entry["name"], entry["flavour"]
    meta4_url = urllib.parse.urljoin(OPDS_ENTRIES, entry["meta4"])
    meta = metalink(fetch(meta4_url))
    # OPDS rounds the length to whole KiB; the metalink size is exact. Only a big gap means a mix-up.
    if abs(meta["size"] - entry["length"]) > entry["length"] // 100:
        raise SystemExit(f"{name}: OPDS says {entry['length']} bytes, metalink says {meta['size']}")
    canonical = meta4_url.removesuffix(".meta4").replace("://lb.download.kiwix.org/", "://download.kiwix.org/")
    stable = [u for u in meta["mirrors"]
              if u.startswith("https://") and urllib.parse.urlparse(u).hostname in STABLE_MIRRORS]
    result = {
        "id": f"{name}_{flavour}" if flavour else name,
        "title": row.get("title", entry["title"]),
        "description": row.get("description", entry["summary"]),
        "category": row["category"],
        "language": entry["language"],
        "sizeBytes": meta["size"],
        "sha256": meta["sha256"],
        "urls": ([canonical] + stable)[:MAX_URLS],
        "snapshotDate": entry["updated"][:10],
        "license": row["license"],
        "hasFulltextIndex": "_ftindex:yes" in entry["tags"],
        "topics": [],
        "zimUuid": entry["uuid"],
        "zimName": entry["name"],
        "featured": row.get("featured", False),
        "family": row.get("family", ""),
    }
    if row.get("nonCommercial"):
        result["nonCommercial"] = True
    return result


def family_of(entry, families):
    """The first family-table row that matches [entry]'s ZIM name (or its Kiwix category), else None."""
    for row in families:
        match = row["match"]
        if isinstance(match, dict):
            if entry["category"] == match["kiwixCategory"]:
                return row
        elif re.match(match, entry["name"]):
            return row
    return None


def kiwix_entries(config, families, feed, curated_keys, fetch=http_get, workers=8, tries=3, sleep=time.sleep):
    """
    Every pack in [feed] in one of the configured languages whose family has a license and no hold, pinned, except
    the curated ones ([curated_keys], (name, flavour) pairs). Returns (packs, report lines).
    """
    languages = set(config["languages"])
    newest = {}
    for e in feed:
        if languages & set(e["language"].split(",")):
            key = (e["name"], e["flavour"])
            if key not in newest or e["updated"] > newest[key]["updated"]:
                newest[key] = e
    kept, held, no_row, no_license, unsafe = [], {}, {}, {}, []
    for key, e in sorted(newest.items()):
        if key in curated_keys:
            continue
        row = family_of(e, families)
        family = row["family"] if row else e["name"].split("_")[0]
        if row is None:
            no_row[family] = no_row.get(family, 0) + 1
        elif row.get("hold"):
            held.setdefault(row["hold"], {}).setdefault(family, 0)
            held[row["hold"]][family] += 1
        elif not row.get("license"):
            no_license[family] = no_license.get(family, 0) + 1
        elif not SAFE_NAME.match(f"{e['name']}_{e['flavour']}" if e["flavour"] else e["name"]):
            unsafe.append(e["name"])
        else:
            kept.append((e, row))

    def pin_with_retries(item):
        e, row = item
        fields = {"family": row["family"], "category": row["category"], "license": row["license"],
                  "nonCommercial": row.get("nonCommercial", False)}
        for attempt in range(tries):
            try:
                return pin(e, fields, fetch), None
            except SystemExit as error:  # a size mix-up won't fix itself
                return None, f"{e['name']} {e['flavour']}: {error}"
            except Exception as error:
                if attempt == tries - 1:
                    return None, f"{e['name']} {e['flavour']}: {error}"
                sleep(2 ** attempt)

    with concurrent.futures.ThreadPoolExecutor(workers) as pool:
        results = list(pool.map(pin_with_retries, kept))
    packs = sorted((p for p, _ in results if p), key=lambda p: (p["category"], p["title"].lower(), p["id"]))
    failed = [f for _, f in results if f]

    report = [f"Kiwix packs in {', '.join(sorted(languages))}: {len(newest)}; listed {len(packs)}, "
              f"curated {len(curated_keys & set(newest))}", ""]
    by_family = {}
    for p in packs:
        by_family[(p["category"], p["family"])] = by_family.get((p["category"], p["family"]), 0) + 1
    report += ["Listed, by category and family:"] + [f"  {c} / {f}: {n}" for (c, f), n in sorted(by_family.items())]
    for hold, counts in sorted(held.items()):
        report += ["", f"Held back ({hold}): {sum(counts.values())}"] + [f"  {f}: {n}" for f, n in sorted(counts.items())]
    report += ["", f"No license found: {sum(no_license.values())}"] + [f"  {f}: {n}" for f, n in sorted(no_license.items())]
    report += ["", f"No family-table row: {sum(no_row.values())}"] + [f"  {f}: {n}" for f, n in sorted(no_row.items())]
    report += ["", f"Unsafe ids: {len(unsafe)}"] + [f"  {n}" for n in unsafe]
    report += ["", f"Failed to pin: {len(failed)}"] + [f"  {f}" for f in failed]
    return packs, report


def release_entry(curated, head=http_head, root=None):
    """A pack we build and host ourselves, from the descriptor `pack_builder.py describe` wrote."""
    path = pathlib.Path(root or REPO_ROOT) / curated["release"]
    descriptor = json.loads(path.read_text(encoding="utf-8"))
    missing = [key for key in RELEASE_KEYS if key not in descriptor]
    if missing:
        raise SystemExit(f"{path}: missing {missing}")
    if not descriptor["urls"] or not all(u.startswith("https://") for u in descriptor["urls"]):
        raise SystemExit(f"{path}: every URL must be https, got {descriptor['urls']}")
    for url in descriptor["urls"]:
        served = head(url)
        if served != descriptor["sizeBytes"]:
            raise SystemExit(f"{url} serves {served} bytes; the descriptor says {descriptor['sizeBytes']}")
    return {
        "id": descriptor["id"],
        "title": curated["title"],
        "description": descriptor["description"],
        "category": curated["category"],
        "language": descriptor["language"],
        "sizeBytes": descriptor["sizeBytes"],
        "sha256": descriptor["sha256"],
        "urls": descriptor["urls"],
        "snapshotDate": descriptor["snapshotDate"],
        "license": curated["license"],
        "hasFulltextIndex": descriptor["hasFulltextIndex"],
        "topics": [],
        "zimUuid": descriptor["zimUuid"],
        "zimName": descriptor["zimName"],
        "featured": False,
        "family": "",
    }


BOOK_KEYS = ("releaseDate", "collection", "books")
BOOK_FIELDS = ("id", "title", "author", "language", "sizeBytes", "sha256", "urls", "published", "section")


def book_entries(curated, head=http_head, root=None):
    """Every e-book of a release descriptor `pack_builder.py describe-epubs` wrote, as (collection, entries)."""
    path = pathlib.Path(root or REPO_ROOT) / curated["bookRelease"]
    descriptor = json.loads(path.read_text(encoding="utf-8"))
    missing = [key for key in BOOK_KEYS if key not in descriptor]
    if missing:
        raise SystemExit(f"{path}: missing {missing}")
    entries = []
    for book in descriptor["books"]:
        missing = [key for key in BOOK_FIELDS if key not in book]
        if missing:
            raise SystemExit(f"{path}: {book.get('id')}: missing {missing}")
        if not book["urls"] or not all(u.startswith("https://") for u in book["urls"]):
            raise SystemExit(f"{path}: {book['id']}: every URL must be https, got {book['urls']}")
        for url in book["urls"]:
            served = head(url)
            if served != book["sizeBytes"]:
                raise SystemExit(f"{url} serves {served} bytes; the descriptor says {book['sizeBytes']}")
        entries.append({
            "id": book["id"], "title": book["title"], "author": book["author"], "description": "",
            "category": book["section"], "language": book["language"], "sizeBytes": book["sizeBytes"],
            "sha256": book["sha256"], "urls": book["urls"], "releaseDate": descriptor["releaseDate"],
            "license": curated["license"], "collection": descriptor["collection"],
            "published": book["published"],
        })
    return descriptor["collection"], entries


def build(curated, fetch=http_get, now=None, head=http_head, report=None, root=None):
    """The catalog. With a "kiwix" setting, the Kiwix report's lines are appended to [report]."""
    now = now or datetime.datetime.now(datetime.timezone.utc)
    kiwix = curated.get("kiwix")
    if kiwix is None:
        packs = [release_entry(c, head) if "release" in c else pack_entry(c, fetch) for c in curated["packs"]]
    else:
        families = json.loads((pathlib.Path(root or REPO_ROOT) / kiwix["families"]).read_text(encoding="utf-8"))["families"]
        feed = kiwix_feed(fetch)
        by_key = {(e["name"], e["flavour"]): e for e in feed}
        packs = []
        for c in curated["packs"]:
            if "release" in c:
                packs.append(release_entry(c, head))
                continue
            sample = by_key.get((c["name"], c.get("flavour", "")))
            row = family_of(sample, families) if sample else None
            packs.append(pack_entry({**c, "featured": True, "family": row["family"] if row else ""}, fetch, feed))
        curated_keys = {(c["name"], c.get("flavour", "")) for c in curated["packs"] if "release" not in c}
        bulk, lines = kiwix_entries(kiwix, families, feed, curated_keys, fetch)
        packs += bulk
        if report is not None:
            report += lines
    models = curated["models"]
    books, collections = [], {}
    for row in curated.get("books", []):
        collection, entries = book_entries(row, head)
        books += entries
        collections[collection] = [e["id"] for e in entries]
    for item in packs + models:
        for key in ("id", "fileName"):
            if key in item and not SAFE_NAME.match(item[key]):
                raise SystemExit(f"unsafe {key}: {item[key]!r}")
    for book in books:
        if not SAFE_NAME.match(book["id"]):
            raise SystemExit(f"unsafe id: {book['id']!r}")
    ids = [item["id"] for item in packs + models + books]
    if len(ids) != len(set(ids)):
        raise SystemExit(f"duplicate ids in {ids}")
    # "@<collection>" in a preset stands for every book of that collection.
    presets = [{**p, "items": [i for item in p["items"] for i in
                               (collections.get(item[1:], [item]) if item.startswith("@") else [item])]}
               for p in curated["presets"]]
    presets += [{"id": name, "title": "Granger Classics: all books", "items": ids_}
                for name, ids_ in collections.items()]
    for preset in presets:
        unknown = set(preset["items"]) - set(ids)
        if unknown:
            raise SystemExit(f"preset {preset['id']} lists unknown items {sorted(unknown)}")
    return {
        "formatVersion": FORMAT_VERSION,
        "generatedAt": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "packs": packs,
        "models": models,
        "books": books,
        "presets": presets,
    }


def public_key(seed):
    return Ed25519PrivateKey.from_private_bytes(seed).public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)


def sign_bytes(seed, data):
    return Ed25519PrivateKey.from_private_bytes(seed).sign(data)


def sign_file(seed, path):
    path = pathlib.Path(path)
    signature = sign_bytes(seed, path.read_bytes())
    pathlib.Path(f"{path}.sig").write_text(base64.b64encode(signature).decode() + "\n")


def verify_file(public, path):
    path = pathlib.Path(path)
    signature = base64.b64decode(pathlib.Path(f"{path}.sig").read_text().strip())
    try:
        Ed25519PublicKey.from_public_bytes(public).verify(signature, path.read_bytes())
        return True
    except InvalidSignature:
        return False


def keygen(out):
    out = pathlib.Path(out).expanduser()
    if out.exists():
        raise SystemExit(f"{out} exists; refusing to overwrite a signing key")
    out.parent.mkdir(parents=True, exist_ok=True)
    seed = os.urandom(32)
    fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(seed.hex() + "\n")
    return base64.b64encode(public_key(seed)).decode()


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("keygen"); p.add_argument("--out", required=True)
    p = sub.add_parser("build"); p.add_argument("--curated", required=True); p.add_argument("--out", required=True)
    p = sub.add_parser("sign"); p.add_argument("file")
    group = p.add_mutually_exclusive_group(required=True)
    group.add_argument("--key"); group.add_argument("--key-hex")
    p = sub.add_parser("verify"); p.add_argument("--public-key", required=True); p.add_argument("file")
    args = parser.parse_args(argv)

    if args.command == "keygen":
        print(keygen(args.out))
    elif args.command == "build":
        report = []
        catalog = build(json.loads(pathlib.Path(args.curated).read_text()), report=report)
        out = pathlib.Path(args.out)
        out.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        if report:
            (out.parent / "kiwix-report.txt").write_text("\n".join(report) + "\n", encoding="utf-8")
        print(f"{args.out}: {len(catalog['packs'])} packs, {len(catalog['models'])} models, "
              f"{out.stat().st_size / 1e6:.2f} MB")
    elif args.command == "sign":
        hex_seed = args.key_hex or pathlib.Path(args.key).expanduser().read_text().strip()
        sign_file(bytes.fromhex(hex_seed), args.file)
        print(f"wrote {args.file}.sig")
    elif args.command == "verify":
        public = base64.b64decode(pathlib.Path(args.public_key).read_text().strip())
        if not verify_file(public, args.file):
            raise SystemExit(f"{args.file}: BAD signature")
        print(f"{args.file}: signature OK")


if __name__ == "__main__":
    main(sys.argv[1:])
