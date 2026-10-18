"""Dataset loading and checking for the Research eval.

A dataset file is a JSON array of items (see docs/superpowers/specs/2026-10-17-research-eval-design.md, section 5).
`check` validates structure only. `validate_answers` checks that each answer's location and quote really exist:

* Article sources are the pack's ZIM name (e.g. `wikipedia_en_top`); the file is `<PACKS>/<name>.zim`. The location
  must equal an entry's path exactly, case-insensitively, ignoring any `#fragment` on either side (see `location_matches`),
  and the quote must appear in that entry's text (HTML stripped, whitespace and case folded).
* Book sources are ids like `book:granger:<ref>`; the file is `<BOOKS>/<ref>.epub`. The location is matched as a
  substring of a spine item's href, and the quote must appear in that item's text.
"""
import html
import json
import posixpath
import re
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

SPLITS = ("tune", "heldout")
BOOK_PREFIX = "book:granger:"


def load(path):
    items = json.loads(Path(path).read_text(encoding="utf-8"))
    check(items)
    return items


def check(items):
    seen = set()
    for it in items:
        i = it.get("id")
        if not i:
            raise ValueError("item without an id: %r" % (it,))
        if i in seen:
            raise ValueError("duplicate id: %s" % i)
        seen.add(i)
        if it.get("split") not in SPLITS:
            raise ValueError("%s: unknown split %r (want one of %s)" % (i, it.get("split"), ", ".join(SPLITS)))
        if not it.get("question"):
            raise ValueError("%s: no question" % i)
        if it.get("answerable"):
            answers = it.get("answers") or []
            if not answers or any(not a.get("source") or not a.get("location") for a in answers):
                raise ValueError("%s: an answerable question needs an answer with a source and a location" % i)


def location_matches(source, want, have):
    """Does a passage at location `have` (from source `source`) satisfy an answer's location `want`?

    Book sources (`book:...`) match `want` as a case-insensitive substring of `have`. Article sources match exactly,
    case-insensitively, after dropping any `#fragment` from both sides.
    """
    if source.startswith("book:"):
        return want.lower() in have.lower()
    return _bare(want) == _bare(have)


def _bare(loc):
    return (loc or "").split("#", 1)[0].lower()


def _norm(s):
    return re.sub(r"\s+", " ", html.unescape(s)).strip().lower()


def _text(markup):
    markup = re.sub(r"<(script|style)\b.*?</\1>", " ", markup, flags=re.S | re.I)
    return _norm(re.sub(r"<[^>]+>", " ", markup))


def _zim_problem(archive, a):
    matched = False
    for n in range(archive.all_entry_count):
        e = archive._get_entry_by_id(n)
        if e.is_redirect or not location_matches(a["source"], a["location"], e.path):
            continue
        item = e.get_item()
        matched = matched or item.mimetype.startswith("text/html")
        if not item.mimetype.startswith("text/html"):
            continue
        if _norm(a["quote"]) in _text(bytes(item.content).decode("utf-8", "replace")):
            return None
    return "quote not found in entry matching %r" % a["location"] if matched else "no entry matches %r" % a["location"]


def _epub_problem(path, a):
    with zipfile.ZipFile(path) as z:
        opf = ET.fromstring(z.read("META-INF/container.xml")).find(".//{*}rootfile").get("full-path")
        root = ET.fromstring(z.read(opf))
        manifest = {m.get("id"): m.get("href") for m in root.iter() if m.tag.endswith("}item") or m.tag == "item"}
        base = posixpath.dirname(opf)
        hrefs = [manifest[r.get("idref")] for r in root.iter() if r.tag.endswith("itemref") and r.get("idref") in manifest]
        loc = a["location"].lower()
        matched = [h for h in hrefs if loc in h.lower()]
        for h in matched:
            body = z.read(posixpath.normpath(posixpath.join(base, h))).decode("utf-8", "replace")
            if _norm(a["quote"]) in _text(body):
                return None
    return "quote not found in spine item matching %r" % a["location"] if matched else "no spine item matches %r" % a["location"]


def validate_answers(items, packs=None, books=None):
    """Returns a list of problem strings (empty when every answer checks out). Sources without a directory are skipped."""
    problems, archives = [], {}
    for it in items:
        for a in it.get("answers") or []:
            src = a["source"]
            try:
                if src.startswith(BOOK_PREFIX):
                    if books is None:
                        continue
                    f = Path(books) / (src[len(BOOK_PREFIX):] + ".epub")
                    p = "book file missing: %s" % f if not f.exists() else _epub_problem(f, a)
                else:
                    if packs is None:
                        continue
                    f = Path(packs) / (src + ".zim")
                    if not f.exists():
                        p = "pack file missing: %s" % f
                    else:
                        if f not in archives:
                            from libzim.reader import Archive
                            archives[f] = Archive(str(f))
                        p = _zim_problem(archives[f], a)
            except (OSError, KeyError, ET.ParseError, zipfile.BadZipFile) as ex:
                p = "cannot read %s: %s" % (src, ex)
            if p:
                problems.append("%s: %s (%s)" % (it["id"], p, src))
    return problems
