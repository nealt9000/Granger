#!/usr/bin/env python3
"""Builds, signs and verifies Granger's catalog of packs and models.

Packs come from Kiwix's OPDS catalog plus the hand-picked list in curated.json; each pack is pinned to
one snapshot by its sha256 from Kiwix's metalink file. Models are listed in curated.json as-is.

  catalog_gen.py keygen --out ~/.config/granger/catalog-signing.key   # once; prints the public key
  catalog_gen.py build  --curated tools/catalog-gen/curated.json --out catalog/catalog.json
  catalog_gen.py sign   --key ~/.config/granger/catalog-signing.key catalog/catalog.json
  catalog_gen.py verify --public-key catalog/public-key.txt catalog/catalog.json

Packs we build ourselves (tools/pack-builder) are listed with a "release" row in curated.json that names
their release descriptor; the hosted file's size is checked against it.

The app trusts only catalogs signed by the key whose public half is in catalog/public-key.txt.
"""
import argparse
import base64
import datetime
import json
import os
import pathlib
import re
import sys
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


def pack_entry(curated, fetch=http_get):
    name, flavour = curated["name"], curated.get("flavour", "")
    query = urllib.parse.urlencode({"name": name, "count": 50})
    matches = [e for e in opds_entries(fetch(f"{OPDS_ENTRIES}?{query}"))
               if e["name"] == name and e["flavour"] == flavour]
    if not matches:
        raise SystemExit(f"{name} ({flavour or 'no flavour'}): not in the Kiwix catalog")
    entry = max(matches, key=lambda e: e["updated"])
    meta4_url = urllib.parse.urljoin(OPDS_ENTRIES, entry["meta4"])
    meta = metalink(fetch(meta4_url))
    # OPDS rounds the length to whole KiB; the metalink size is exact. Only a big gap means a mix-up.
    if abs(meta["size"] - entry["length"]) > entry["length"] // 100:
        raise SystemExit(f"{name}: OPDS says {entry['length']} bytes, metalink says {meta['size']}")
    canonical = meta4_url.removesuffix(".meta4").replace("://lb.download.kiwix.org/", "://download.kiwix.org/")
    stable = [u for u in meta["mirrors"]
              if u.startswith("https://") and urllib.parse.urlparse(u).hostname in STABLE_MIRRORS]
    return {
        "id": f"{name}_{flavour}" if flavour else name,
        "title": curated.get("title", entry["title"]),
        "description": entry["summary"],
        "category": curated["category"],
        "language": entry["language"],
        "sizeBytes": meta["size"],
        "sha256": meta["sha256"],
        "urls": ([canonical] + stable)[:MAX_URLS],
        "snapshotDate": entry["updated"][:10],
        "license": curated["license"],
        "hasFulltextIndex": "_ftindex:yes" in entry["tags"],
        "topics": [],
        "zimUuid": entry["uuid"],
        "zimName": entry["name"],
    }


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
    }


def build(curated, fetch=http_get, now=None, head=http_head):
    now = now or datetime.datetime.now(datetime.timezone.utc)
    packs = [release_entry(c, head) if "release" in c else pack_entry(c, fetch) for c in curated["packs"]]
    models = curated["models"]
    for item in packs + models:
        for key in ("id", "fileName"):
            if key in item and not SAFE_NAME.match(item[key]):
                raise SystemExit(f"unsafe {key}: {item[key]!r}")
    ids = [item["id"] for item in packs + models]
    if len(ids) != len(set(ids)):
        raise SystemExit(f"duplicate ids in {ids}")
    for preset in curated["presets"]:
        unknown = set(preset["items"]) - set(ids)
        if unknown:
            raise SystemExit(f"preset {preset['id']} lists unknown items {sorted(unknown)}")
    return {
        "formatVersion": FORMAT_VERSION,
        "generatedAt": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "packs": packs,
        "models": models,
        "presets": curated["presets"],
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
        catalog = build(json.loads(pathlib.Path(args.curated).read_text()))
        pathlib.Path(args.out).write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"{args.out}: {len(catalog['packs'])} packs, {len(catalog['models'])} models")
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
