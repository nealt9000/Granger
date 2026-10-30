#!/usr/bin/env python3
"""Builds, signs and verifies Granger's catalog of packs and models.

Packs come from Kiwix's OPDS catalog plus the hand-picked list in curated.json; each pack is pinned to
one snapshot by its sha256 from Kiwix's metalink file. Models are listed in curated.json as-is.

  catalog_gen.py keygen --out ~/.config/granger/catalog-signing.key   # once; prints the public key
  catalog_gen.py build  --curated tools/catalog-gen/curated.json --out catalog/catalog.json
  catalog_gen.py check-links catalog/catalog.json                       # HEADs every download URL (Plan DL)
  catalog_gen.py sign   --key ~/.config/granger/catalog-signing.key catalog/catalog.json
  catalog_gen.py verify --public-key catalog/public-key.txt catalog/catalog.json
  catalog_gen.py vectors --releases tools/vectors/releases --release-url URL catalog/catalog.json
                                         # puts the search vectors on an already-built catalog (Plan R1)

With a "kiwix" setting in curated.json, every pack in Kiwix's catalog in one of its languages is listed too, when its
family has a row in the family table (kiwix_families.json) that gives its license and no hold. The curated rows win
for their own packs and are marked featured. `build` writes what it kept and left out to catalog/kiwix-report.txt.

Packs we build ourselves (tools/pack-builder) are listed with a "release" row in curated.json that names
their release descriptor; the hosted file's size is checked against it. A "books" row names the descriptor
of an e-book collection (`pack_builder.py describe-epubs`): every book becomes a catalog entry and the
collection gets a preset. "@<collection>" in a preset's items expands to that collection's book ids.

Search vectors (Plan R1): a "vectors" setting in curated.json names the folder of `tools/vectors/build.py`'s release
descriptors and the release URL their files are uploaded under; each descriptor's vectors go on the entries built from
its exact source file. `vectors` does the same to a catalog that's already built, with a fresh generatedAt.

Dead links (Plan DL): `check-links` asks every download URL, mirrors included, whether it is still there, and fails
listing the dead ones. It leaves `<file>.links-ok` holding the checked file's sha256; `sign` refuses a file without a
matching one, so a catalog is never signed with a link Kiwix has withdrawn (WikEM, 2026-10).

The app trusts only catalogs signed by the key whose public half is in catalog/public-key.txt.
"""
import argparse
import base64
import concurrent.futures
import datetime
import hashlib
import json
import os
import pathlib
import re
import sys
import time
import urllib.error
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
# Readiness (Plan H1): a Classics book's domain comes from its section; what isn't scripture or philosophy is literature.
BOOK_DOMAINS = {"Scripture & religion": "thought", "Philosophy": "thought"}
BOOK_DOMAIN_DEFAULT = "literature"


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
        # The readiness domain (spec §3.2); blank means storage only.
        "domain": row.get("domain") or "",
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
                  "nonCommercial": row.get("nonCommercial", False), "domain": row.get("domain")}
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
    by_domain = {}
    for p in packs:
        by_domain[p["domain"] or "(storage only)"] = by_domain.get(p["domain"] or "(storage only)", 0) + 1
    report += ["", "Listed, by readiness domain:"] + [f"  {d}: {n}" for d, n in sorted(by_domain.items())]
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
        "domain": curated.get("domain", ""),
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
            "domain": BOOK_DOMAINS.get(book["section"], BOOK_DOMAIN_DEFAULT),
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
            packs.append(pack_entry({**c, "featured": True, "family": row["family"] if row else "",
                                     "domain": c.get("domain", row.get("domain") if row else "")}, fetch, feed))
        curated_keys = {(c["name"], c.get("flavour", "")) for c in curated["packs"] if "release" not in c}
        bulk, lines = kiwix_entries(kiwix, families, feed, curated_keys, fetch)
        packs += bulk
        if report is not None:
            report += lines
    models = curated["models"]
    voices = curated.get("voices", [])       # Plan V1: one zip per voice, hand-written like models
    books, collections = [], {}
    for row in curated.get("books", []):
        collection, entries = book_entries(row, head)
        books += entries
        collections[collection] = [e["id"] for e in entries]
    for item in packs + models + voices:
        for key in ("id", "fileName"):
            if key in item and not SAFE_NAME.match(item[key]):
                raise SystemExit(f"unsafe {key}: {item[key]!r}")
    for book in books:
        if not SAFE_NAME.match(book["id"]):
            raise SystemExit(f"unsafe id: {book['id']!r}")
    ids = [item["id"] for item in packs + models + books + voices]
    if len(ids) != len(set(ids)):
        raise SystemExit(f"duplicate ids in {ids}")
    # "@<collection>" in a preset stands for every book of that collection.
    presets = [{**p, "items": [i for item in p["items"] for i in
                               (collections.get(item[1:], [item]) if item.startswith("@") else [item])]}
               for p in curated["presets"]]
    presets += [{"id": name, "title": "Granger Classics: all books", "items": ids_}
                for name, ids_ in collections.items()]
    vector_lines = []
    if "vectors" in curated:
        v = curated["vectors"]
        _, vector_lines = attach_vectors({"packs": packs, "books": books, "models": models},
                                         vector_descriptors(pathlib.Path(root or REPO_ROOT) / v["releases"]), v.get("releaseUrl"))
        if report is not None:
            report += ["", "Search vectors left out:"] + (vector_lines or ["  none"])
    readiness = None
    if "readiness" in curated:
        readiness, tier_presets = readiness_block(curated["readiness"], packs, books, collections)
        presets += tier_presets
    onboarding = onboarding_block(curated["onboarding"], packs, books, collections) if "onboarding" in curated else None
    for preset in presets:
        unknown = set(preset["items"]) - set(ids)
        if unknown:
            raise SystemExit(f"preset {preset['id']} lists unknown items {sorted(unknown)}")
    catalog = {
        "formatVersion": FORMAT_VERSION,
        "generatedAt": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "packs": packs,
        "models": models,
        "books": books,
        "presets": presets,
        "voices": voices,
    }
    if readiness is not None:
        catalog["readiness"] = readiness
    if onboarding is not None:
        catalog["onboarding"] = onboarding
    return catalog


VECTOR_KEYS = ("id", "kind", "fileName", "sizeBytes", "sha256", "dims", "dtype", "chunkerVersion", "embedderSha256",
               "sourceSha256")


def vector_descriptors(folder):
    """The release descriptors `tools/vectors/build.py` wrote, one per vector file."""
    out = []
    for path in sorted(pathlib.Path(folder).glob("*.json")):
        d = json.loads(path.read_text(encoding="utf-8"))
        missing = [key for key in VECTOR_KEYS if key not in d]
        if missing:
            raise SystemExit(f"{path}: missing {missing}")
        out.append(d)
    return out


def attach_vectors(catalog, descriptors, release_url=None):
    """
    Puts each descriptor's vectors on the catalog entries built from its exact source file (sha256), replacing any
    earlier ones, and returns (the uploads, report lines). A pack's vectors also go on its other flavours of the same
    snapshot, which share its text, except `_mini` (lead sections only). A source the catalog no longer lists (another
    snapshot) is reported, not attached: vectors are valid for one file only. The uploads are the descriptors used.
    """
    embedders = {m["sha256"] for m in catalog["models"] if m.get("role") == "embedder"}
    packs, books = catalog["packs"], catalog.get("books", [])
    for entry in packs + books:
        entry.pop("vectors", None)
    used, lines = [], []
    for d in descriptors:
        if d["embedderSha256"] not in embedders:
            raise SystemExit(f"{d['id']}: embedded by {d['embedderSha256'][:12]}, which is no embedder in the catalog")
        url = d.get("url") or (release_url.rstrip("/") + "/" + d["fileName"] if release_url else None)
        if not url or not url.startswith("https://"):
            lines.append(f"  {d['id']}: no https URL, left out")
            continue
        if d["kind"] == "book":
            targets = [b for b in books if b["id"] == d["id"] and b["sha256"] == d["sourceSha256"]]
        else:
            source = next((p for p in packs if p["sha256"] == d["sourceSha256"]), None)
            targets = [] if source is None else [
                p for p in packs if p["zimName"] == source["zimName"] and p["snapshotDate"] == source["snapshotDate"]
                and not p["id"].endswith("_mini")]
        if not targets:
            now = [e["snapshotDate"] if "snapshotDate" in e else e.get("releaseDate") for e in packs + books if e["id"] == d["id"]]
            lines.append(f"  {d['id']}: built from {d['fileName']}, but the catalog has {now[0] if now else 'no such entry'}; rebuild it")
            continue
        vectors = {"url": url, "sha256": d["sha256"], "sizeBytes": d["sizeBytes"], "embedderSha256": d["embedderSha256"],
                   "dims": d["dims"], "dtype": d["dtype"], "chunkerVersion": d["chunkerVersion"]}
        for entry in targets:
            entry["vectors"] = dict(vectors)
        used.append(d)
    return used, lines


def check_token(scope, token, pack_ids, book_ids, families, collections):
    """A readiness-style token names a catalog item, "@<collection>" or "family:<name>"; anything else fails the build."""
    if token.startswith("@"):
        if token[1:] not in collections:
            raise SystemExit(f"{scope}: unknown collection {token}")
    elif token.startswith("family:"):
        if token[len("family:"):] not in families:
            raise SystemExit(f"{scope}: unknown family {token}")
    elif token not in pack_ids and token not in book_ids:
        raise SystemExit(f"{scope}: unknown item {token}")


def onboarding_block(block, packs, books, collections):
    """
    The catalog's `onboarding` object (Plan H3, spec §6): the use cases with their items (readiness tokens) and the
    maps packs that are continents or multi-country areas (`larger`), so the region picker lists countries first.
    """
    pack_ids = {p["id"] for p in packs}
    book_ids = {b["id"] for b in books}
    families = {p["family"] for p in packs if p.get("family")}
    maps = {p["id"] for p in packs if p.get("family") == "maps"}
    ids, use_cases = set(), []
    for u in block.get("useCases", []):
        uid, title = u.get("id") or "", u.get("title") or ""
        if not uid or not title or uid in ids:
            raise SystemExit(f"onboarding: bad or duplicate use case id {uid!r}")
        ids.add(uid)
        items = list(dict.fromkeys(u.get("items", [])))
        if not items:
            raise SystemExit(f"onboarding {uid}: no items")
        for token in items:
            check_token(f"onboarding {uid}", token, pack_ids, book_ids, families, collections)
        use_cases.append({"id": uid, "title": title, "blurb": u.get("blurb") or "", "items": items})
    regions = block.get("regions", {})
    larger = list(dict.fromkeys(regions.get("larger", [])))
    for pack_id in larger:
        if pack_id not in maps:
            raise SystemExit(f"onboarding regions.larger: {pack_id!r} is not a maps pack")
    covered = {}
    for pack_id, places in regions.get("covered", {}).items():
        if pack_id not in maps:
            raise SystemExit(f"onboarding regions.covered: {pack_id!r} is not a maps pack")
        names = [str(n).strip() for n in places if str(n).strip()]
        if names:
            covered[pack_id] = list(dict.fromkeys(names))
    return {"useCases": use_cases, "larger": larger, "covered": covered}


def readiness_block(block, packs, books, collections):
    """
    The catalog's `readiness` object (Plan H1, spec §3.2) and the two presets it generates. Every tier token is checked:
    a catalog id, "@<collection>" (that collection's books of the domain) or "family:<name>" (any pack of the family).
    Essential items are folded into comprehensive. A pack or book whose domain isn't one of the block's ids fails the build.
    `contains` (Plan H4) maps a ZIM name to the ZIM names it holds ("prefix*" allowed); the presets carry one pack per ZIM
    (the flavour the bigger tier names) and leave out a ZIM held by another in the same preset.
    """
    zim_names = {p["zimName"] for p in packs if p.get("zimName")}
    contains = block.get("contains", {})
    for key, held in contains.items():
        for name in [key] + [h for h in held if not h.endswith("*")]:
            if name not in zim_names:
                raise SystemExit(f"readiness contains: unknown ZIM {name!r}")
    pack_ids = {p["id"] for p in packs}
    book_ids = {b["id"] for b in books}
    families = {p["family"] for p in packs if p.get("family")}
    books_by_domain = {}
    for b in books:
        books_by_domain.setdefault((b["collection"], b["domain"]), []).append(b["id"])
    ids, domains, efficient, comprehensive = set(), [], [], []
    for d in block.get("domains", []):
        did, title = d.get("id") or "", d.get("title") or ""
        if not did or not title or did in ids:
            raise SystemExit(f"readiness: bad or duplicate domain id {did!r}")
        ids.add(did)
        essential = list(dict.fromkeys(d.get("essential", [])))
        full = list(dict.fromkeys(essential + list(d.get("comprehensive", []))))
        for token in full:
            check_token(f"readiness {did}", token, pack_ids, book_ids, families, collections)
        domains.append({"id": did, "title": title, "essential": essential, "comprehensive": full})

        def expand(tokens):
            out = []
            for token in tokens:
                if token.startswith("@"):
                    out += books_by_domain.get((token[1:], did), [])
                elif not token.startswith("family:"):  # the region's map is picked in onboarding
                    out.append(token)
            return out

        efficient += expand(essential)
        comprehensive += expand(full)
    for item in packs + books:
        if item.get("domain") and item["domain"] not in ids:
            raise SystemExit(f"{item['id']}: domain {item['domain']!r} is not in the readiness block")
    zim_of = {p["id"]: p.get("zimName") for p in packs}
    presets = [{"id": "efficient", "title": "Efficient start", "items": one_per_zim(efficient, zim_of, contains)},
               {"id": "comprehensive", "title": "Comprehensive library", "items": one_per_zim(comprehensive, zim_of, contains)}]
    out = {"domains": domains}
    if contains:
        out["contains"] = contains
    return out, presets


def holds(contains, container, zim):
    """Whether ZIM [container] holds ZIM [zim]'s articles by the `contains` table (Plan H4, Decision 5)."""
    return zim != container and any(zim == h or (h.endswith("*") and zim.startswith(h[:-1])) for h in contains.get(container, []))


def one_per_zim(items, zim_of, contains):
    """
    [items] with one pack per ZIM, the last listed (comprehensive's flavour comes after essential's) at the first one's
    place, and without a ZIM another of them holds. Books and packs without a ZIM name pass through.
    """
    last = {zim_of[i]: i for i in items if zim_of.get(i)}
    names = set(last)
    out = []
    for i in dict.fromkeys(items):
        zim = zim_of.get(i)
        if not zim:
            out.append(i)
        elif last[zim] not in out and not any(holds(contains, c, zim) for c in names):
            out.append(last[zim])
    return out


def tier_sizes(catalog):
    """
    Per readiness domain and for "All", (essential, comprehensive) bytes as the presets count them: one pack per ZIM,
    a held ZIM left out, `family:` tokens (the region's map) left out.
    """
    readiness = catalog.get("readiness") or {}
    contains = readiness.get("contains", {})
    packs = {p["id"]: p for p in catalog["packs"]}
    books = catalog.get("books", [])

    def expand(tokens, domain):
        out = []
        for t in tokens:
            if t.startswith("@"):
                out += [b["id"] for b in books if b["collection"] == t[1:] and b["domain"] == domain]
            elif not t.startswith("family:"):
                out.append(t)
        return out

    # An item's size counts its search vectors, as the app shows it (Plan R1).
    def size(e):
        return e["sizeBytes"] + (e.get("vectors") or {}).get("sizeBytes", 0)

    sizes_of = {**{b["id"]: size(b) for b in books}, **{i: size(p) for i, p in packs.items()}}
    zim_of = {i: p.get("zimName") for i, p in packs.items()}

    def total(items):
        return sum(sizes_of[i] for i in one_per_zim(items, zim_of, contains))

    sizes, all_e, all_c = {}, [], []
    for d in readiness.get("domains", []):
        e, c = expand(d["essential"], d["id"]), expand(d["comprehensive"], d["id"])
        sizes[d["id"]] = (total(e), total(c))
        all_e += e
        all_c += c
    sizes["All"] = (total(all_e), total(all_c))
    return sizes


def catalog_urls(catalog):
    """Every URL the app may download from: each entry's URLs (mirrors included), models' and voices' URL, vectors."""
    urls = []
    for kind in ("packs", "models", "books", "voices"):
        for entry in catalog.get(kind, []):
            urls += entry.get("urls", [])
            if entry.get("url"):
                urls.append(entry["url"])
            if entry.get("vectors", {}).get("url"):
                urls.append(entry["vectors"]["url"])
    return list(dict.fromkeys(urls))


def http_status(url):
    """The status a HEAD finally gets, after redirects (Kiwix sends you to a mirror, GitHub to its CDN)."""
    request = urllib.request.Request(url, method="HEAD", headers={"User-Agent": "granger-catalog-gen"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status
    except urllib.error.HTTPError as e:
        return e.code


def dead_links(urls, status=http_status, workers=16, tries=3, sleep=time.sleep):
    """[(url, why)] for every URL that isn't there. A 404 or 410 is dead at once; anything else but 2xx is asked again."""
    def check(url):
        why = None
        for attempt in range(tries):
            try:
                code = status(url)
            except Exception as e:  # a timeout, a reset, DNS: ask again
                why = f"{type(e).__name__}: {e}"
            else:
                if 200 <= code < 300:
                    return None
                why = f"HTTP {code}"
                if code in (404, 410):
                    break
            if attempt < tries - 1:
                sleep(2 ** attempt)
        return url, why

    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        return [r for r in pool.map(check, urls) if r]


def links_ok_path(path):
    return pathlib.Path(f"{path}.links-ok")


def file_sha256(path):
    return hashlib.sha256(pathlib.Path(path).read_bytes()).hexdigest()


def check_links(path, status=http_status, workers=16, sleep=time.sleep):
    """Checks [path]'s URLs; on success writes `<path>.links-ok`, otherwise removes it. Returns (count, dead)."""
    urls = catalog_urls(json.loads(pathlib.Path(path).read_text(encoding="utf-8")))
    dead = dead_links(urls, status, workers, sleep=sleep)
    stamp = links_ok_path(path)
    if dead:
        stamp.unlink(missing_ok=True)
    else:
        stamp.write_text(file_sha256(path) + "\n")
    return len(urls), dead


def links_checked(path):
    """Whether `check-links` passed on [path] as it is now."""
    stamp = links_ok_path(path)
    return stamp.is_file() and stamp.read_text().strip() == file_sha256(path)


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
    p = sub.add_parser("check-links"); p.add_argument("file")
    p = sub.add_parser("sign"); p.add_argument("file")
    group = p.add_mutually_exclusive_group(required=True)
    group.add_argument("--key"); group.add_argument("--key-hex")
    p = sub.add_parser("verify"); p.add_argument("--public-key", required=True); p.add_argument("file")
    p = sub.add_parser("vectors"); p.add_argument("--releases", required=True); p.add_argument("--release-url")
    p.add_argument("file")
    args = parser.parse_args(argv)

    if args.command == "keygen":
        print(keygen(args.out))
    elif args.command == "build":
        report = []
        catalog = build(json.loads(pathlib.Path(args.curated).read_text()), report=report)
        if catalog.get("readiness"):
            report += ["", "Readiness tiers, essential / comprehensive (one pack per ZIM, held ZIMs and the region's map left out):"]
            report += [f"  {d}: {e / 1e9:.1f} GB / {c / 1e9:.1f} GB" for d, (e, c) in tier_sizes(catalog).items()]
        out = pathlib.Path(args.out)
        out.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        if report:
            (out.parent / "kiwix-report.txt").write_text("\n".join(report) + "\n", encoding="utf-8")
        print(f"{args.out}: {len(catalog['packs'])} packs, {len(catalog['models'])} models, "
              f"{out.stat().st_size / 1e6:.2f} MB")
    elif args.command == "check-links":
        count, dead = check_links(args.file)
        if dead:
            for url, why in dead:
                print(f"  DEAD {url}: {why}")
            raise SystemExit(f"{args.file}: {len(dead)} of {count} links dead")
        print(f"{args.file}: all {count} links answer")
    elif args.command == "sign":
        if not links_checked(args.file):
            raise SystemExit(f"{args.file}: run check-links on this file first (it changed since, or never passed)")
        hex_seed = args.key_hex or pathlib.Path(args.key).expanduser().read_text().strip()
        sign_file(bytes.fromhex(hex_seed), args.file)
        print(f"wrote {args.file}.sig")
    elif args.command == "vectors":
        path = pathlib.Path(args.file)
        catalog = json.loads(path.read_text(encoding="utf-8"))
        used, lines = attach_vectors(catalog, vector_descriptors(args.releases), args.release_url)
        # A fresh date: the app refuses a catalog older than the one it has.
        catalog["generatedAt"] = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        path.write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        entries = sum(1 for e in catalog["packs"] + catalog.get("books", []) if "vectors" in e)
        print(f"{path}: vectors on {entries} entries from {len(used)} files, "
              f"{sum(d['sizeBytes'] for d in used) / 1e6:.1f} MB to upload")
        for line in lines:
            print(line)
    elif args.command == "verify":
        public = base64.b64decode(pathlib.Path(args.public_key).read_text().strip())
        if not verify_file(public, args.file):
            raise SystemExit(f"{args.file}: BAD signature")
        print(f"{args.file}: signature OK")


if __name__ == "__main__":
    main(sys.argv[1:])
