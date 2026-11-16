#!/usr/bin/env python3
"""
African Storybook as Granger-published book sets, one per language (Plan KB2 Task 3.1, Decision 2).

The Kiwix pack `africanstorybook.org_mul_all` (Kolibri, 8.3 GB, every language) is read by HTTP range requests: the
book list comes from `tools/bookpacks/build.py`'s `kolibri` extractor, and each chosen book's EPUB is read out of its
stored cluster. Nothing is downloaded whole.

  africanstorybook.py build --date 2026-11-16 [--langs en,sw] [--src URL] [--out-root DIR] [--epubcheck]
      pulls, fixes and checks the EPUBs into <out-root>/<lang>/<id>.epub (originals in <out-root>/source/<lang>/),
      and writes releases/african_storybook_<lang>_books.json. Resumable: files already there are kept.
  africanstorybook.py describe --date 2026-11-16 [--langs en,sw] [--out-root DIR]
      rewrites the release descriptors from the files already built (no network).

Ids are `asb-<lang>-<node>`: the Kolibri node id (32 hex) is the stable key the manifest already uses, kept whole
(Classics ids have no length rule; the longest is 63 characters, these are 39). File names are `<id>.epub`.

Each language is its own GitHub release, `asb-<lang>-<date>`: a release holds at most 1,000 assets, and English alone
has 693 books. `upload_release.sh` uploads one, resumably.

The EPUBs are African Storybook's own (cover, one page per spread, a back cover that credits author, illustrator,
licence and source). They are changed only as far as epubcheck and Granger need (`fix_epub`):
- the OPF's misspelt "africanstroybook.org" (and "African Stroybook Initiative") are corrected, which also makes the
  NCX identifier match the OPF's (NCX-001);
- `direction` and `unicode-bidi` are dropped from the CSS (CSS-001; only the unused right-to-left class has them);
- the cover page becomes `cover.xhtml` and the back cover `colophon.xhtml`, the names the Linked Library's Related
  skips as front matter;
- a `dc:rights` line carries the back cover's attribution into the metadata;
- XML that doesn't parse is mended (control characters dropped, bare `&` escaped, `<br>` closed) and empty
  `dc:creator`s removed (some books have no illustrator);
- a 0-byte cover image (46 of the 1,120 books) takes the first page's picture.
"""
import argparse
import collections
import concurrent.futures
import datetime
import hashlib
import io
import json
import pathlib
import re
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT / "bookpacks"), str(ROOT / "catalog-gen"), str(ROOT / "sidecar")]

ZIM_NAME = "africanstorybook.org_mul_all"
SRC = "https://download.kiwix.org/zim/other/africanstorybook.org_mul_all_2025-01.zim"
REPO = "nealt9000/Granger"
RELEASES = pathlib.Path(__file__).resolve().parent / "releases"
OUT_ROOT = pathlib.Path.home() / "Developer" / "Granger" / "vectors-work"
NODE = re.compile(r"^[0-9a-f]{32}$")
LEVEL = re.compile(r"^Level ([1-5])$")
LICENSE = "CC BY 4.0, African Storybook Initiative"
PROVIDER = "African Storybook Initiative"
NAMES = {"en": "English", "sw": "Kiswahili"}
# Set names: "African Storybook · English". The Spanish UI copy lives in curated.json (Task 3.2); the books' own text
# stays in its own language.
TITLE = "African Storybook"


# ---------- ids, levels, release names ----------

def book_id(lang, node):
    """`asb-en-<node>`: the Kolibri node id whole, so the id follows the book across snapshots."""
    if not re.fullmatch(r"[a-z]{2,3}", lang or ""):
        raise ValueError(f"bad language {lang!r}")
    if not NODE.match(node or ""):
        raise ValueError(f"not a Kolibri node id: {node!r}")
    return f"asb-{lang}-{node}"


def level_of(group):
    """1–5 from the Kolibri breadcrumb's "Level 3"; None for anything else."""
    m = LEVEL.match((group or "").strip())
    return int(m.group(1)) if m else None


def collection_of(lang):
    return f"african-storybook-{lang}"


def release_tag(lang, date):
    return f"asb-{lang}-{date}"


def release_url(lang, date, file_name):
    return f"https://github.com/{REPO}/releases/download/{release_tag(lang, date)}/{file_name}"


def chosen(items, lang):
    """The manifest's books in [lang], in its order (language, level, title), each with its id and level."""
    out = []
    for i in items:
        if i.get("language") != lang or i.get("format") != "epub":
            continue
        level = level_of(i.get("group"))
        if level is None:
            raise ValueError(f"{i['key']} ({i['title']!r}) has no level: group {i.get('group')!r}")
        out.append(dict(i, id=book_id(lang, i["key"]), level=level))
    ids = [i["id"] for i in out]
    if len(ids) != len(set(ids)):
        raise ValueError(f"duplicate ids in {lang}")
    return out


# ---------- the EPUB fix ----------

TYPO = ((b"africanstroybook.org", b"africanstorybook.org"), (b"African Stroybook Initiative", b"African Storybook Initiative"))
BANNED_CSS = re.compile(rb"[ \t]*(direction|unicode-bidi)\s*:[^;}]*;?[ \t]*\r?\n?", re.I)
BAD_XML_CHARS = re.compile(rb"[\x00-\x08\x0b\x0c\x0e-\x1f]")
BARE_AMP = re.compile(rb"&(?!#\d+;|#x[0-9A-Fa-f]+;|[A-Za-z][A-Za-z0-9]*;)")
OPEN_BR = re.compile(rb"<br\s*>", re.I)
EMPTY_CREATOR = re.compile(rb'<dc:creator id="([^"]+)">\s*</dc:creator>')


def _opf_path(z):
    container = z.read("META-INF/container.xml").decode("utf-8")
    return re.search(r'full-path="([^"]+)"', container).group(1)


def _text(fragment):
    import html
    t = re.sub(r"<br\s*/?>", " · ", fragment)
    t = re.sub(r"<[^>]+>", " ", t)
    return re.sub(r"\s+", " ", html.unescape(t)).strip(" ·")


def back_cover_rights(page):
    """The back cover's attribution, one line: "© Text: …; Illustration: … 2014 · Creative Commons: Attribution 4.0 …"."""
    m = re.search(rb'<div class="backcover_copyright">(.*?)</div>', page, re.S)
    if not m:
        return None
    t = _text(m.group(1).decode("utf-8", "replace"))
    t = re.sub(r"\s*·\s*", " · ", t)
    return re.sub(r"\b(Source|Original source) ", r"\1: ", t)


def _spine_pages(opf):
    """[(id, href)] of the spine's pages, in order."""
    hrefs = {m.group("id"): m.group("href") for m in re.finditer(
        rb'<item\b(?=[^>]*\bid="(?P<id>[^"]+)")(?=[^>]*\bhref="(?P<href>[^"]+)")[^>]*>', opf)}
    return [(i, hrefs[i]) for i in re.findall(rb'<itemref\b[^>]*\bidref="([^"]+)"', opf) if i in hrefs]


def fix_epub(data, licence_fallback=LICENSE):
    """
    ([data] fixed, [what changed]). Deterministic: the same EPUB in gives the same bytes out, entries in their order
    with their dates and compression, `mimetype` first and stored.
    """
    src = zipfile.ZipFile(io.BytesIO(data))
    opf_name = _opf_path(src)
    base = opf_name.rsplit("/", 1)[0] + "/" if "/" in opf_name else ""
    opf = src.read(opf_name)
    changes = []
    contents = {info.filename: src.read(info) for info in src.infolist()}

    # 1. The misspelt site name (and with it the NCX/OPF identifier mismatch).
    for name in [opf_name] + [n for n in contents if n.endswith(".ncx")]:
        body = contents[name]
        for bad, good in TYPO:
            if bad in body:
                body = body.replace(bad, good)
                changes.append(f"typo:{name}")
        contents[name] = body
    # 2. CSS properties EPUB style sheets mustn't have.
    for name in [n for n in contents if n.endswith(".css")]:
        body, n = BANNED_CSS.subn(b"", contents[name])
        if n:
            contents[name] = body
            changes.append(f"css:{name}")
    # 2b. XML that doesn't parse (RSC-016) and empty creators (RSC-005), in the OPF, NCX and pages.
    for name in [n for n in contents if n.endswith((".opf", ".ncx", ".xhtml", ".html"))]:
        body = contents[name]
        body, a = BAD_XML_CHARS.subn(b"", body)
        body, b = BARE_AMP.subn(b"&amp;", body)
        body, c = OPEN_BR.subn(b"<br/>", body) if not name.endswith((".opf", ".ncx")) else (body, 0)
        d = 0
        if name == opf_name:
            for ident in EMPTY_CREATOR.findall(body):
                body = re.sub(rb'\s*<dc:creator id="' + re.escape(ident) + rb'">\s*</dc:creator>', b"", body)
                body = re.sub(rb'<meta refines="#' + re.escape(ident) + rb'"[^>]*>[^<]*</meta>', b"", body)
                d += 1
        if a or b or c or d:
            contents[name] = body
            changes += [f"{kind}:{name}" for kind, n in (("xmlchar", a), ("amp", b), ("br", c), ("creator", d)) if n]
    # 2c. An empty cover image (the Kiwix copy of some books has a 0-byte cover.png): the first page's picture.
    cover = re.search(rb'<item\b[^>]*properties="cover-image"[^>]*>', contents[opf_name])
    if cover:
        cover_name = base + re.search(rb'href="([^"]+)"', cover.group(0)).group(1).decode()
        if cover_name in contents and not contents[cover_name]:
            for _, href in _spine_pages(contents[opf_name])[1:]:
                img = re.search(rb'<img src="([^"]+\.png)"', contents.get(base + href.decode(), b""))
                if img and contents.get(base + img.group(1).decode()):
                    contents[cover_name] = contents[base + img.group(1).decode()]
                    changes.append(f"cover:{cover_name}")
                    break
    # 3. Cover and back cover by their standard names.
    opf = contents[opf_name]
    pages = _spine_pages(opf)
    renames = {}
    if pages:
        first, last = pages[0][1].decode(), pages[-1][1].decode()
        first_body = contents.get(base + first, b"")
        if first != "cover.xhtml" and b"images/cover" in first_body and "cover.xhtml" not in contents:
            renames[first] = "cover.xhtml"
        last_body = contents.get(base + last, b"")
        if last != "colophon.xhtml" and b"backcover" in last_body and "colophon.xhtml" not in contents:
            renames[last] = "colophon.xhtml"
    if renames:
        for name in list(contents):
            if name == opf_name or name.endswith((".ncx", ".xhtml", ".html")):
                body = contents[name]
                for old, new in renames.items():
                    body = re.sub(rb'((?:href|src)=")' + re.escape(old.encode()) + rb'(["#])',
                                  rb"\g<1>" + new.encode() + rb"\g<2>", body)
                contents[name] = body
        changes.append("renamed:" + ",".join(f"{o}>{n}" for o, n in renames.items()))
    # 4. The attribution in the metadata.
    opf = contents[opf_name]
    if b"<dc:rights" not in opf:
        back = contents.get(base + pages[-1][1].decode(), b"") if pages else b""  # entries keep old names till written
        rights = back_cover_rights(back) or licence_fallback
        from xml.sax.saxutils import escape
        opf = opf.replace(b"</metadata>", f"<dc:rights>{escape(rights)}</dc:rights></metadata>".encode(), 1)
        contents[opf_name] = opf
        changes.append("rights")

    out = io.BytesIO()
    with zipfile.ZipFile(out, "w") as z:
        infos = sorted(src.infolist(), key=lambda i: i.filename != "mimetype")
        for info in infos:
            old = info.filename
            rel = old[len(base):] if old.startswith(base) else None
            new = base + renames[rel] if rel in renames else old
            zi = zipfile.ZipInfo(new, info.date_time)
            zi.compress_type = zipfile.ZIP_STORED if old == "mimetype" else info.compress_type
            zi.external_attr = info.external_attr
            z.writestr(zi, contents[old])
    return out.getvalue(), changes


def epub_facts(data):
    """Title, language, creators by role, date and cover href from an EPUB's OPF (for the descriptor and checks)."""
    import html
    z = zipfile.ZipFile(io.BytesIO(data))
    opf = z.read(_opf_path(z)).decode("utf-8", "replace")
    roles = dict(re.findall(r'<meta refines="#([^"]+)" property="role"[^>]*>([^<]+)</meta>', opf))
    creators = collections.defaultdict(list)
    for ident, name in re.findall(r'<dc:creator id="([^"]+)">([^<]*)</dc:creator>', opf):
        creators[roles.get(ident, "aut")].append(html.unescape(name).strip())
    one = lambda tag: html.unescape(m.group(1)).strip() if (m := re.search(fr"<dc:{tag}[^>]*>([^<]*)</dc:{tag}>", opf)) else ""  # noqa: E731
    cover = re.search(r'<item\b[^>]*properties="cover-image"[^>]*>', opf)
    href = re.search(r'href="([^"]+)"', cover.group(0)).group(1) if cover else None
    return {"title": one("title"), "language": one("language"), "date": one("date"),
            "authors": creators.get("aut", []), "illustrators": creators.get("ill", []), "cover": href}


# ---------- the release descriptor ----------

def describe(lang, books, folder, date):
    """
    The release descriptor (the Classics' `*_books.json` shape, read by catalog_gen's book_entries) for [books] whose
    fixed EPUBs are in [folder]. Extra fields: description, level, illustrator, node, source path and sha256.
    """
    entries = []
    for b in books:
        path = pathlib.Path(folder) / f"{b['id']}.epub"
        data = path.read_bytes()
        facts = epub_facts(data)
        year = int(facts["date"][:4]) if re.match(r"\d{4}", facts["date"]) else None
        entry = {
            "id": b["id"], "title": b["title"], "author": b.get("author") or ", ".join(facts["authors"]),
            "translator": None, "published": year, "section": f"Level {b['level']}", "language": lang,
            "sizeBytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
            "urls": [release_url(lang, date, path.name)],
            "domain": "literature", "description": b.get("description", ""), "level": b["level"],
            "illustrator": ", ".join(facts["illustrators"]), "cover": facts["cover"], "node": b["key"],
            "sourcePath": b["path"],
        }
        if b.get("sourceSha256"):
            entry["sourceSha256"] = b["sourceSha256"]
        entries.append(entry)
    return {"releaseDate": date, "collection": collection_of(lang), "title": f"{TITLE} · {NAMES.get(lang, lang)}",
            "provider": PROVIDER, "license": LICENSE, "source": {"zimName": ZIM_NAME, "url": SRC},
            "levels": dict(sorted(collections.Counter(e["level"] for e in entries).items())), "books": entries}


def descriptor_path(lang):
    return RELEASES / f"african_storybook_{lang}_books.json"


def write_descriptor(descriptor, path):
    pathlib.Path(path).write_text(json.dumps(descriptor, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


# ---------- epubcheck ----------

def epubcheck(path):
    """(errors, warnings, messages) from W3C epubcheck (`brew install epubcheck`)."""
    r = subprocess.run(["epubcheck", "-q", str(path)], capture_output=True, text=True)
    lines = [l for l in (r.stdout + r.stderr).splitlines() if re.match(r"(FATAL|ERROR|WARNING)\(", l)]
    errors = sum(1 for l in lines if l.startswith(("FATAL", "ERROR")))
    return errors, len(lines) - errors, lines


# ---------- the build ----------

def pull(pack, books, folder, workers=8):
    """Each book's original EPUB into [folder]/<id>.epub by range reads; ones already there are kept."""
    folder.mkdir(parents=True, exist_ok=True)
    todo = []
    for b in books:
        out = folder / f"{b['id']}.epub"
        if out.exists():
            try:
                zipfile.ZipFile(out).testzip()
                continue
            except (zipfile.BadZipFile, OSError):
                out.unlink()
        e = pack.lookup(b["path"])
        if e is None:
            raise SystemExit(f"{b['id']}: {b['path']} isn't in the pack")
        todo.append((b, e))

    def one(job):
        b, e = job
        data = pack.z.blobs(e["cluster"], [e["blob"]])[e["blob"]]
        tmp = folder / f"{b['id']}.epub.part"
        tmp.write_bytes(data)
        tmp.replace(folder / f"{b['id']}.epub")
        return len(data)

    done = 0
    with concurrent.futures.ThreadPoolExecutor(workers) as ex:
        for n in ex.map(one, todo):
            done += 1
            if done % 50 == 0:
                print(f"  pulled {done}/{len(todo)}", file=sys.stderr)
    return len(todo)


def build_lang(lang, items, pack, root, date, check):
    books = chosen(items, lang)
    src_dir, out_dir = root / "source" / lang, root / lang
    pulled = pull(pack, books, src_dir) if pack is not None else 0
    out_dir.mkdir(parents=True, exist_ok=True)
    changes = collections.Counter()
    mismatches = []
    for b in books:
        raw = (src_dir / f"{b['id']}.epub").read_bytes()
        b["sourceSha256"] = hashlib.sha256(raw).hexdigest()
        fixed, what = fix_epub(raw)
        changes.update(w.split(":")[0] for w in what)
        (out_dir / f"{b['id']}.epub").write_bytes(fixed)
        facts = epub_facts(fixed)
        if facts["language"] != lang:
            mismatches.append(f"{b['id']}: EPUB language {facts['language']!r}")
        if facts["title"].strip() != b["title"].strip():
            mismatches.append(f"{b['id']}: EPUB title {facts['title']!r}, page title {b['title']!r}")
        if not facts["cover"]:
            mismatches.append(f"{b['id']}: no cover-image")
    report = {"lang": lang, "books": len(books), "pulled": pulled, "changes": dict(changes), "mismatches": mismatches}
    if check:
        failures = {}
        with concurrent.futures.ThreadPoolExecutor(8) as ex:
            for b, (errors, warnings, lines) in zip(books, ex.map(lambda b: epubcheck(out_dir / f"{b['id']}.epub"), books)):
                if errors or warnings:
                    failures[b["id"]] = lines
        report["epubcheck"] = {"checked": len(books), "failed": len(failures), "failures": failures}
    descriptor = describe(lang, books, out_dir, date)
    write_descriptor(descriptor, descriptor_path(lang))
    report["sizeBytes"] = sum(e["sizeBytes"] for e in descriptor["books"])
    report["levels"] = descriptor["levels"]
    return report


def load_items(manifest_path, src, budget):
    """(the pack opened for range reads or None, the manifest's items)."""
    import build as bookpacks
    cfg = json.loads((ROOT / "bookpacks" / "book_packs.json").read_text(encoding="utf-8"))["packs"][ZIM_NAME]
    pack = bookpacks.Pack(src, budget) if src else None
    if manifest_path and pathlib.Path(manifest_path).exists():
        items = json.loads(pathlib.Path(manifest_path).read_text(encoding="utf-8"))["items"]
    else:
        if pack is None:
            raise SystemExit("no manifest and no source to build one from")
        items = bookpacks.extract_kolibri(pack, cfg)
        if manifest_path:
            pathlib.Path(manifest_path).write_text(json.dumps({"zimName": ZIM_NAME, "source": src, "items": items},
                                                              ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    return pack, items


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("command", choices=("build", "describe"))
    ap.add_argument("--date", default=datetime.date.today().isoformat())
    ap.add_argument("--langs", default="en,sw")
    ap.add_argument("--src", default=SRC)
    ap.add_argument("--out-root", help="default ~/Developer/Granger/vectors-work/asb-<date>")
    ap.add_argument("--epubcheck", action="store_true", help="run epubcheck on every fixed EPUB")
    ap.add_argument("--no-pull", action="store_true", help="use the originals already in source/ (no network)")
    a = ap.parse_args(argv)
    root = pathlib.Path(a.out_root).expanduser() if a.out_root else OUT_ROOT / f"asb-{a.date}"
    root.mkdir(parents=True, exist_ok=True)
    manifest = root / "manifest.json"
    langs = a.langs.split(",")
    if a.command == "build":
        pack, items = load_items(manifest, None if a.no_pull else a.src, 4 << 30)
        try:
            reports = [build_lang(lang, items, pack, root, a.date, a.epubcheck) for lang in langs]
        finally:
            if pack is not None:
                pack.close()
        (root / "report.json").write_text(json.dumps(reports, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
        for r in reports:
            print(json.dumps({k: v for k, v in r.items() if k not in ("mismatches",)} |
                             {"mismatches": len(r["mismatches"]),
                              "epubcheck": {k: v for k, v in r.get("epubcheck", {}).items() if k != "failures"}}))
    else:
        _, items = load_items(manifest, None, 0)
        for lang in langs:
            books = chosen(items, lang)
            for b in books:
                src = root / "source" / lang / f"{b['id']}.epub"
                if src.exists():
                    b["sourceSha256"] = hashlib.sha256(src.read_bytes()).hexdigest()
            d = describe(lang, books, root / lang, a.date)
            write_descriptor(d, descriptor_path(lang))
            print(f"{descriptor_path(lang)}: {len(d['books'])} books, {sum(e['sizeBytes'] for e in d['books']) / 1e9:.2f} GB")


if __name__ == "__main__":
    main(sys.argv[1:])
