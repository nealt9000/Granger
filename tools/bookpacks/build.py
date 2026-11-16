#!/usr/bin/env python3
"""
Builds a book manifest (`.gbooks`, Plan KB2 design §3) for one Kiwix pack, by range reads: nothing is downloaded whole.

  build.py PACK_NAME ZIM_URL_OR_FILE --out FILE [--config tools/bookpacks/book_packs.json] [--previous FILE]

The pack's row in book_packs.json names its extractor and settings:
- `kolibri`: one HTML page per item (kolibri2zim); title, summary, author, licence, language and level come from the
  page (h1, first paragraph, "Author:", "License:", breadcrumb).
- `zimit-index`: a scraped website whose pages link to PDF/EPUB files. Each file's title is the best of: an override,
  its link text (when it isn't "Download PDF" and the like), the text of the list item / table row / paragraph around
  the link, the nearest heading plus a generic link text ("Special Forces Handbook · Part 1"), the PDF's own Title,
  and its file name. `titleSource` says which won, for grading and for the drift check.

Item keys are stable across snapshots: a Kolibri node id, or the file's path without scheme and query. With
--previous, an item whose path moved keeps its old key when its title and size match.
"""
import argparse
import collections
import concurrent.futures
import html
import html.parser
import json
import pathlib
import posixpath
import random
import re
import struct
import sys
import urllib.parse

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "catalog-gen"))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "sidecar"))
import meta as sidecar_meta  # noqa: E402
import zim_probe as zp  # noqa: E402

VERSION = 1          # builderVersion: bump when a builder fix changes what it writes
SCHEMA_VERSION = 1   # the file layout (schema.md); the app's SidecarEntry.KNOWN["gbooks"]
BUILDER = "tools/bookpacks/build.py"
DOC_MIMES = {"application/pdf": "pdf", "application/epub+zip": "epub"}
BUDGET = 1 << 30
GENERIC = re.compile(
    r"^\W*(download|télécharger|descargar|pdf|epub|here|click here|full text( in one file)?|link|view|open|read|"
    r"read online|file|document|\([a-z]+\)|everything|all|complete|full|whole book|english|german|french|spanish|"
    r"español|deutsch|français|portuguese|part \d+|vol(ume)?\.? ?\d+|\d+|(pdf|epub)?\s*\d+(\.\d+)?\s*[kmg]i?b)(\W*\(?(pdf|epub|english|german|"
    r"french|spanish|español|deutsch)\)?)*\W*$",
    re.I)
BOILER = re.compile(r"click here|start it manually|download (will|should) (start|begin)|if your download|"
                    r"right[- ]click|save (link|target) as|^\W*download(ing)?( page| now)?\W*$", re.I)
LANGUAGES = {
    "english": "en", "kiswahili": "sw", "swahili": "sw", "french": "fr", "español": "es", "spanish": "es",
    "afrikaans": "af", "isizulu": "zu", "isixhosa": "xh", "amharic": "am", "hausa (nigeria)": "ha",
    "hausa (niger)": "ha", "igbo": "ig", "yoruba": "yo", "luganda": "lg", "kinyarwanda": "rw", "somali": "so",
    "sesotho": "st", "setswana": "tn", "chichewa": "ny", "chishona": "sn", "portuguese": "pt", "arabic": "ar",
    "afaan oromo": "om", "lingala": "ln", "ewe": "ee", "akuapem twi": "ak", "asante twi": "ak", "isindebele": "nr",
    "sepedi": "nso", "xitsonga": "ts", "tshivenda": "ve", "siswati": "ss", "kikamba": "kam", "gikuyu": "ki",
    "dholuo": "luo", "bahasa indonesia": "id", "tigrinya": "ti",
}


# ---------- the ZIM: every entry with its title, and batched content reads ----------

def _parse_dirent(buf, pos):
    if len(buf) - pos < 8:
        return None
    mime, extra, ns = struct.unpack_from("<HBB", buf, pos)
    p = pos + (12 if mime == 0xFFFF else 8 if mime in (0xFFFE, 0xFFFD) else 16)
    z = buf.find(b"\x00", p)
    if z < 0:
        return None
    z2 = buf.find(b"\x00", z + 1)
    if z2 < 0 or z2 + 1 + extra > len(buf):
        return None
    if mime == 0xFFFF:
        cl, bl, target = None, None, struct.unpack_from("<I", buf, pos + 8)[0]
    elif p == pos + 16:
        (cl, bl), target = struct.unpack_from("<II", buf, pos + 8), None
    else:
        cl = bl = target = None
    return {"ns": chr(ns), "path": buf[p:z].decode("utf-8", "replace"),
            "title": buf[z + 1:z2].decode("utf-8", "replace"), "mime": mime, "cluster": cl, "blob": bl,
            "redirect": target}, z2 + 1 + extra


class Pack:
    """A ZIM read through zim_probe: its entries (path order), docs, HTML pages, and content in batches."""

    def __init__(self, src, budget=BUDGET):
        self.r = zp.Reader(src, budget)
        self.z = zp.Zim(self.r)
        start, = struct.unpack("<Q", self.r.read(self.z.path_ptr, 8))
        self.entries = []
        buf, pos = b"", 0
        for chunk in self.r.stream(start, self.z.path_ptr, chunk=1 << 20):
            buf += chunk
            while (got := _parse_dirent(buf, pos)) is not None:
                e, pos = got
                self.entries.append(e)
            buf, pos = buf[pos:], 0
        if len(self.entries) != self.z.entry_count:
            raise ValueError(f"read {len(self.entries)} dirents, header says {self.z.entry_count}")
        n = self.z.cluster_count
        self.cluster_ptrs = struct.unpack(f"<{n}Q", self.r.read(self.z.cluster_ptr, 8 * n)) + (self.z.checksum_pos,)
        mimes = self.z.mimes
        for e in self.entries:
            e["mimeType"] = mimes[e["mime"]] if e["mime"] < len(mimes) else None
        self.by_path = {(e["ns"], e["path"]): i for i, e in enumerate(self.entries)}
        self.docs = [e for e in self.entries if e["mimeType"] in DOC_MIMES and e["ns"] in "CAI"]
        self.pages = [e for e in self.entries if (e["mimeType"] or "").startswith("text/html") and e["ns"] in "CA"
                      and e["redirect"] is None]
        per_cluster = collections.Counter(d["cluster"] for d in self.docs)
        for d in self.docs:
            c = d["cluster"]
            d["bytes"] = (self.cluster_ptrs[c + 1] - self.cluster_ptrs[c]) // per_cluster[c]
            if per_cluster[c] > 1:  # small files share a cluster: a stored one's offset table gives each its size
                d["bytes"] = self.blob_size(c, d["blob"]) or d["bytes"]

    def blob_size(self, c, blob):
        """A blob's exact size in a stored cluster; None when the cluster is compressed."""
        a = self.cluster_ptrs[c]
        info = self.r.read(a, 1)[0]
        if info & 0x0F not in (0, 1):
            return None
        size = 8 if info & 0x10 else 4
        fmt = "<2Q" if size == 8 else "<2I"
        o0, o1 = struct.unpack(fmt, self.r.read(a + 1 + size * blob, 2 * size))
        return o1 - o0

    def follow(self, e, hops=5):
        while e["redirect"] is not None and hops:
            e = self.entries[e["redirect"]]
            hops -= 1
        return e

    def lookup(self, path):
        for ns in ("C", "A", "I"):
            i = self.by_path.get((ns, path))
            if i is not None:
                return self.follow(self.entries[i])
        return None

    def contents(self, entries):
        """Yields (entry, bytes) for [entries], each cluster read once."""
        by_cluster = collections.defaultdict(list)
        for e in entries:
            by_cluster[e["cluster"]].append(e)
        for c in sorted(by_cluster):
            es = by_cluster[c]
            got = self.z.blobs(c, sorted({e["blob"] for e in es}))
            for e in es:
                yield e, got[e["blob"]]

    def head_tail(self, e, n=65536):
        """The first and last [n] bytes of a doc in a stored cluster (b"" when its cluster is compressed)."""
        a, _ = self.z.cluster_bounds(e["cluster"])
        info = self.r.read(a, 1)[0]
        if info & 0x0F not in (0, 1):
            return b"", b""
        size = 8 if info & 0x10 else 4
        fmt = "<Q" if size == 8 else "<I"
        o0 = struct.unpack(fmt, self.r.read(a + 1 + size * e["blob"], size))[0]
        o1 = struct.unpack(fmt, self.r.read(a + 1 + size * (e["blob"] + 1), size))[0]
        start, length = a + 1 + o0, o1 - o0
        head = self.r.raw(start, min(n, length))
        tail = self.r.raw(start + max(0, length - n), min(n, length)) if length > n else b""
        return head, tail

    def close(self):
        self.r.close()


# ---------- small helpers ----------

def text_of(fragment):
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", fragment))).strip()


INFO_KEYS = rb"/(Producer|Creator|CreationDate|ModDate|Author|Subject|Keywords)\b"


def _pdf_string(raw, hexed=False):
    if hexed:
        raw = bytes.fromhex(re.sub(rb"\s", b"", raw).decode())
    elif raw:
        raw = re.sub(rb"\\([()\\])", rb"\1", raw)
    if raw.startswith(b"\xfe\xff"):
        return raw[2:].decode("utf-16-be", "replace").strip()
    return raw.decode("latin-1", "replace").strip()


def _pdf_literal(blob, start):
    """The bytes of the PDF literal string opening just before [start]: balanced brackets, backslash escapes."""
    depth, i, out = 1, start, bytearray()
    while i < len(blob):
        ch = blob[i:i + 1]
        if ch == b"\\" and i + 1 < len(blob):
            out += blob[i:i + 2]
            i += 2
            continue
        if ch == b"(":
            depth += 1
        elif ch == b")":
            depth -= 1
            if depth == 0:
                return bytes(out)
        out += ch
        i += 1
    return None


def pdf_title(head, tail):
    """
    A PDF's own title: XMP's dc:title, or /Title in the document Info dictionary (one that also names a Producer,
    Creator, date or Author), when stored uncompressed near either end. Other /Title keys (outline items, structure
    elements, annotations: "Table 1", "Total") are ignored.
    """
    for blob in (tail, head):
        m = re.search(rb"<dc:title>\s*<rdf:Alt>\s*<rdf:li[^>]*>([^<]*)</rdf:li>", blob)
        if m:
            t = html.unescape(m.group(1).decode("utf-8", "replace")).strip()
            if t:
                return t
        for d in re.finditer(rb"<<((?:(?!<<|>>).){0,2000}?)>>", blob, re.S):
            body = d.group(1)
            if b"/Title" not in body or not re.search(INFO_KEYS, body):
                continue
            m = re.search(rb"/Title\s*\(", body)
            if m:
                raw = _pdf_literal(body, m.end())
                if raw is not None:
                    return _pdf_string(raw) or None
            m = re.search(rb"/Title\s*<([0-9A-Fa-f\s]+)>", body)
            if m:
                return _pdf_string(m.group(1), hexed=True) or None
    return None


def plausible_pdf_title(t):
    if not t:
        return None
    t = re.sub(r"^Microsoft (Word|PowerPoint) - ", "", t).strip()
    t = re.sub(r"\.(docx?|pptx?|pdf|indd|qxd)$", "", t, flags=re.I).strip()
    if len(t) < 4 or re.fullmatch(r"(untitled|title|document\d*|slide \d+|\S+\.[a-z]\w{1,7}|[\W\d_]+)", t, re.I):
        return None
    if re.fullmatch(r"[A-Za-z0-9_-]+", t) and ("_" in t or not " " in t and len(t) > 25):
        return None  # a file name, not a title
    return t


def pdf_kind(head, tail):
    """A first guess from the bytes at the ends: "text" (fonts), "scan" (images, no fonts) or "unknown"."""
    if not head:
        return "unknown"
    blob = head + tail
    fonts = blob.count(b"/Font")
    images = blob.count(b"/Image")
    if fonts:
        return "text"
    if images:
        return "scan"
    return "unknown"


def file_title(path):
    stem = urllib.parse.unquote(posixpath.basename(path.split("?")[0]))
    stem = re.sub(r"\.(pdf|epub)$", "", stem, flags=re.I)
    stem = re.sub(r"[_]+", " ", stem)
    stem = re.sub(r"(?<=[a-z])-(?=[a-z])", " ", stem)
    stem = re.sub(r"\s+", " ", stem).strip(" -")
    if stem and stem == stem.lower():
        stem = stem[0].upper() + stem[1:]
    return stem


VOLATILE = re.compile(r"^(tmstv|ver|v|t|ts|timestamp|cache|_|last|.*nonce.*|.*force.*|utm_.*)$", re.I)


def key_of(path):
    """A file's stable key: its path without scheme, keeping only the query parameters that name the file."""
    path = re.sub(r"^https?://", "", path)
    base, _, query = path.partition("?")
    keep = [(k, v) for k, v in urllib.parse.parse_qsl(query, keep_blank_values=True) if not VOLATILE.match(k)]
    return base + ("?" + urllib.parse.urlencode(keep) if keep else "")


# ---------- extractor: kolibri ----------

def extract_kolibri(pack, cfg):
    epubs = {d["path"]: d for d in pack.docs}
    items = []
    for page, body in pack.contents(pack.pages):
        s = body.decode("utf-8", "replace")
        if "Main document" not in s:
            continue
        m = re.search(r'href="\.?/?([^"]+\.(?:epub|pdf))"', s)
        if not m:
            continue
        doc = epubs.get(posixpath.normpath(posixpath.join(posixpath.dirname(page["path"]), m.group(1))))
        if doc is None:
            continue
        crumbs = [text_of(c) for c in re.findall(r'<li class="breadcrumb-item[^"]*"[^>]*>(.*?)</li>', s, re.S)]
        h1 = re.search(r"<h1>(.*?)</h1>(.*?)<ul", s, re.S)
        paras = [text_of(p) for p in re.findall(r"<p>(.*?)</p>", h1.group(2), re.S)] if h1 else []
        author = next((p[len("Author:"):].strip() for p in paras if p.startswith("Author:")), "")
        licence = next((p[len("License:"):].strip() for p in paras if p.startswith("License:")), "")
        desc = next((p for p in paras if not p.startswith(("Author:", "License:"))), "")
        lang_name = crumbs[1] if len(crumbs) > 1 else ""
        items.append({
            "key": page["path"], "path": doc["path"], "format": DOC_MIMES[doc["mimeType"]],
            "title": text_of(h1.group(1)) if h1 else page["title"], "titleSource": "page",
            "author": author, "description": desc, "licence": licence,
            "language": LANGUAGES.get(lang_name.lower(), lang_name), "languageName": lang_name,
            "group": crumbs[2] if len(crumbs) > 3 else "", "bytes": doc["bytes"],
        })
    items.sort(key=lambda i: (i["languageName"], i["group"], i["title"].lower()))
    return items


# ---------- extractor: zimit-index ----------

class LinkContext(html.parser.HTMLParser):
    """Every <a href> on a page with its link text, the nearest heading before it and its enclosing block's text."""

    BLOCKS = {"li", "tr", "p", "dd", "dt", "td"}
    HEADINGS = {"h1", "h2", "h3", "h4", "h5", "h6", "caption", "legend"}

    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.links, self.heading, self.title = [], "", ""
        self._in_heading = self._in_title = False
        self._head_buf = []
        self._blocks = []  # stack of [tag, text parts, links in it]
        self._link = None

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag in self.HEADINGS:
            self._in_heading, self._head_buf = True, []
        elif tag == "title":
            self._in_title = True
        elif tag in self.BLOCKS:
            self._blocks.append([tag, [], []])
        elif tag == "a" and a.get("href"):
            self._link = {"href": a["href"], "text": [], "heading": self.heading, "attrTitle": a.get("title") or ""}

    def handle_endtag(self, tag):
        if tag in self.HEADINGS and self._in_heading:
            self._in_heading = False
            t = re.sub(r"\s+", " ", "".join(self._head_buf)).strip()
            if t:
                self.heading = t
        elif tag == "title":
            self._in_title = False
        elif tag == "a" and self._link is not None:
            self._link["text"] = re.sub(r"\s+", " ", "".join(self._link["text"])).strip()
            self._link["block"] = None
            self.links.append(self._link)
            if self._blocks:
                self._blocks[-1][2].append(self._link)
            self._link = None
        elif tag in self.BLOCKS:
            while self._blocks:
                t, parts, links = self._blocks.pop()
                text = re.sub(r"\s+", " ", "".join(parts)).strip()
                for link in links:
                    # A cell holding nothing but its link ("PDF 42 mb") says nothing: let the row speak.
                    rest = text.replace(link["text"] if isinstance(link["text"], str) else "", " ")
                    if not link["block"] and len(re.sub(r"\W", "", rest)) >= 4:
                        link["block"] = text
                if self._blocks:  # text and links bubble up to the parent block too
                    self._blocks[-1][1].extend(parts + [" "])
                    self._blocks[-1][2].extend(l for l in links if not l["block"])
                if t == tag:
                    break

    def handle_data(self, data):
        if self._in_heading:
            self._head_buf.append(data)
        if self._in_title:
            self.title += data
        if self._link is not None:
            self._link["text"].append(data)
        if self._blocks:
            self._blocks[-1][1].append(data)


def resolve(page_path, href, pack):
    href = html.unescape(href.strip())
    if href.startswith(("mailto:", "javascript:", "#")):
        return None
    url = urllib.parse.urljoin("https://" + page_path, href)
    p = urllib.parse.urlsplit(url)
    path = urllib.parse.unquote(p.netloc + p.path)
    tries = [path + ("?" + p.query if p.query else ""), path]
    host, _, rest = path.partition("/")
    tries += [("www." + host if not host.startswith("www.") else host[4:]) + "/" + rest]
    for t in tries:
        e = pack.lookup(t)
        if e is not None and e["mimeType"] in DOC_MIMES:
            return e
    return None


def tidy(t, cfg):
    """Row text made readable: no ISO dates or file sizes; Title_With_Underscores_1785 → Title With Underscores (1785)."""
    t = re.sub(r"^\s*\d{4}-\d\d-\d\d\b", " ", t)  # a leading "date added" column, not a date in the title
    t = re.sub(r"\b\d+(\.\d+)?\s*[kmg]i?b\s*$", " ", t.strip(), flags=re.I)
    t = re.sub(r"[ _]pdf\s*$", "", t.strip(), flags=re.I)
    t = re.sub(r"\s+", " ", t).strip(" -–—:|·,()[]")
    if "_" in t:  # a file-style name: underscores are spaces, and a hyphen between words separates title parts
        t = t.replace("_", " ")
        t = re.sub(r"(?<=\w)\s*-\s*(?=\w)", lambda m: m.group(0) if re.fullmatch(r"\d", t[m.start() - 1]) and
                   re.fullmatch(r"\d", t[m.end()]) else " – ", t)
        t = re.sub(r"\s+", " ", t).strip()
    t = re.sub(r"(\d)(St|Nd|Rd|Th|Mm|Cm|Kg|Lb|Hp|Ed)\b", lambda m: m.group(1) + m.group(2).lower(), t)
    if cfg.get("yearSuffix"):
        t = re.sub(r"[ _]((1[4-9]|20)\d\d)$", r" (\1)", t)
    return t


QUALIFIER_WORDS = {"lab", "labs", "manual", "workbook", "student", "students", "teacher", "teachers", "solutions",
                   "answers", "answer", "key", "guide", "instructor", "exercises", "appendix", "appendices", "slides",
                   "summary", "handout", "worksheet", "worksheets", "notes", "companion", "edition", "print",
                   "printable", "large", "small", "color", "colour", "grayscale", "greyscale", "mobile", "a4", "letter"}


def qualifier(t):
    """What's left of a link text once "Download", "PDF" and sizes are gone, when it only qualifies the item."""
    rest = re.sub(r"\b(download(s)?|pdf|epub|file|version|here|the|a|of|for)\b", " ", t, flags=re.I)
    rest = re.sub(r"\(?\s*\d+(\.\d+)?\s*[kmg]i?b\s*\)?", " ", rest, flags=re.I)
    rest = re.sub(r"[^\w\s]", " ", rest)
    words = rest.split()
    if words and len(words) <= 4 and all(w.lower() in QUALIFIER_WORDS for w in words):
        return " ".join(words).capitalize()
    return None


def specific(t):
    if not t or not 4 <= len(t) <= 160 or GENERIC.match(t) or BOILER.search(t):
        return False
    stripped = re.sub(r"\b(download(s)?|pdf|epub)\b|\(?\s*\d+(\.\d+)?\s*[kmg]i?b\s*\)?", " ", t, flags=re.I)
    return len(re.sub(r"[^\w]", "", stripped)) >= 3 and qualifier(t) is None


def page_title(c, site):
    """The linking page's title without the site's name, when that page exists to serve this one file."""
    if c.get("pageDocs", 99) > 2:
        return None
    t = c.get("pageTitle", "")
    for sep in (" – ", " — ", " - ", " | "):
        parts = [x.strip() for x in t.split(sep)]
        if len(parts) > 1:
            parts = [x for x in parts if site and x.lower() != site.lower()] or parts
            t = parts[0]
    return t if specific(t) else None


def path_title(path, rules, fallback=False):
    """
    A title made from the file's path by the pack's `pathTitles` rules ({match, title, maps, fallback,
    capitalize}). A `fallback` rule is used only when the pages give no title.
    """
    for rule in rules:
        if bool(rule.get("fallback")) != fallback:
            continue
        m = re.search(rule["match"], path)
        if not m:
            continue
        values = {}
        for k, v in m.groupdict().items():
            v = v or ""
            if k == "yy" and v.isdigit():  # a two-digit year
                v = ("19" if int(v) >= 70 else "20") + v
            values[k] = rule.get("maps", {}).get(k, {}).get(v.lower(), v.replace("-", " "))
        if rule.get("capitalize"):
            values = {k: v[:1].upper() + v[1:] for k, v in values.items()}
        return re.sub(r"\s+", " ", rule["title"].format(**values)).strip(" ·-")
    return None


def qualified(title, cands):
    """
    A title shared by a book and its companion ("X + Lab Manual" on one page) told apart by the link that leads to
    each: "X · Lab manual" for the companion, "X" for the book itself.
    """
    q = next((qualifier(c["text"]) for c in cands if c.get("text") and qualifier(c["text"])), None)
    plus = re.match(r"^(?P<base>.+?)\s*\+\s*(?P<extra>[\w\s]+)$", title)
    if plus and all(w.lower() in QUALIFIER_WORDS for w in plus.group("extra").split()):
        title = plus.group("base")
    if q and q.lower() not in title.lower():
        title = f"{title} · {q}"
    return title


def resolve_page(page_path, href, pack):
    """The HTML page [href] leads to (redirects followed), or None."""
    href = html.unescape(href.strip())
    if href.startswith(("mailto:", "javascript:", "#")) or re.search(r"\.(pdf|epub|jpe?g|png|gif|css|js)(\?|$)", href, re.I):
        return None
    p = urllib.parse.urlsplit(urllib.parse.urljoin("https://" + page_path, href))
    path = urllib.parse.unquote(p.netloc + p.path)
    for t in (path, path.rstrip("/") + "/", path.rstrip("/")):
        e = pack.lookup(t)
        if e is not None and (e["mimeType"] or "").startswith("text/html"):
            return e["path"]
    return None


def best_title(cands, override, pdf_meta, path, rules=(), site="", cfg=None):
    """(title, source) from the candidates gathered for one file."""
    if override:
        return override, "override"
    t = path_title(path, rules)
    if t:
        return t, "path"
    for c in cands:  # a specific link text
        if specific(c["text"]):
            return c["text"], "link"
    for c in cands:  # the list item / row around a generic link
        b = tidy((c.get("block") or "").replace(c["text"], " "), cfg or {})
        if specific(b):
            return b, "block"
    for c in cands:
        t = page_title(c, site)
        if t:
            return t, "page"
    t = path_title(path, rules, fallback=True)
    if t:
        return t, "path"
    t = plausible_pdf_title(pdf_meta)
    if t:
        return t, "pdf"
    for c in cands:  # heading + "Part 1" / "Lab manual"
        if c["heading"] and specific(c["heading"]):
            q = qualifier(c["text"]) if c["text"] else None
            if q:
                return f'{c["heading"]} · {q}', "heading"
            return (f'{c["heading"]} · {c["text"]}' if c["text"] and GENERIC.match(c["text"]) and
                    re.search(r"\d", c["text"]) else c["heading"]), "heading"
    return file_title(path), "filename"


def extract_zimit_index(pack, cfg):
    include = re.compile(cfg["include"]) if cfg.get("include") else None
    exclude = re.compile(cfg["exclude"]) if cfg.get("exclude") else None
    min_bytes = cfg.get("minBytes", 0)
    docs = [d for d in pack.docs if (not include or include.search(d["path"])) and
            not (exclude and exclude.search(d["path"])) and d["bytes"] >= min_bytes]
    wanted = {d["path"] for d in docs}
    cands = collections.defaultdict(list)
    inbound = collections.defaultdict(list)  # page path -> links to it from other pages
    pages = pack.pages[:cfg.get("maxPages", 20000)]
    for page, body in pack.contents(pages):
        parser = LinkContext()
        try:
            parser.feed(body.decode("utf-8", "replace"))
        except Exception:  # noqa: BLE001 - one broken page mustn't stop a pack
            continue
        hits = []
        for link in parser.links:
            target = resolve_page(page["path"], link["href"], pack)
            if target is not None and target != page["path"]:
                link["pageTitle"] = re.sub(r"\s+", " ", parser.title).strip() or page["title"]
                inbound[target].append(dict(link, pageDocs=1))
            e = resolve(page["path"], link["href"], pack)
            if e is not None and e["path"] in wanted:
                link["pageTitle"] = re.sub(r"\s+", " ", parser.title).strip() or page["title"]
                link["page"] = page["path"]
                hits.append((e["path"], link))
        for path, link in hits:
            link["pageDocs"] = len({p for p, _ in hits})
            cands[path].append(link)
    # A file served by a page of its own ("Download Page", "click here to start it manually") is named by the
    # links that lead to that page: those come after the file's own candidates.
    for path, cs in cands.items():
        extra = [l for c in cs if c.get("pageDocs", 99) <= 1 for l in inbound.get(c["page"], [])]
        cs.extend(extra)
    groups = [(g["key"], g["title"], re.compile(g["match"])) for g in cfg.get("groups", [])]
    overrides = cfg.get("overrides", {})
    # A PDF's own bytes are read only when its pages give no title, or when the pack asks for every PDF's kind
    # ("pdfProbe": "all"); in parallel, as each is two small range requests.
    probe_all = cfg.get("pdfProbe") == "all"
    rules, site = cfg.get("pathTitles", []), cfg.get("site", "")
    need = [d for d in docs if d["mimeType"] == "application/pdf" and (probe_all or best_title(
        cands.get(d["path"], []), overrides.get(key_of(d["path"])), None, d["path"], rules, site, cfg)[1]
        in ("heading", "filename"))]
    cap = cfg.get("pdfProbeMax", 3000)
    if len(need) > cap:  # a pack whose pages name nothing: don't read thousands of PDFs, say so in the stats
        print(f"warning: {len(need)} PDFs need their own title; reading the first {cap}", file=sys.stderr)
        need = need[:cap]
    ends = {}
    with concurrent.futures.ThreadPoolExecutor(12) as pool:
        for d, got in zip(need, pool.map(pack.head_tail, need)):
            ends[d["path"]] = got
    items = []
    for d in docs:
        key = key_of(d["path"])
        head, tail = ends.get(d["path"], (b"", b""))
        meta = pdf_title(head, tail) if head else None
        title, source = best_title(cands.get(d["path"], []), overrides.get(key), meta, d["path"], rules, site, cfg)
        if source in ("page", "heading", "pdf"):
            title = qualified(title, cands.get(d["path"], []))
        if title and title == title.lower() and title[0].isalpha():
            title = title[0].upper() + title[1:]
        group = next((title for _, title, rx in groups if rx.search(d["path"])), "")
        if not group and cfg.get("groupBy") == "page" and cands.get(d["path"]):
            t = cands[d["path"]][0]["pageTitle"]
            m = re.search(cfg.get("groupPattern", r"^(?P<g>.+?)(\s+[-|–—]\s+.*)?$"), t)
            group = m.group("g").replace("_", " ").strip() if m else ""
        items.append({"key": key, "path": d["path"], "format": DOC_MIMES[d["mimeType"]], "title": title,
                      "titleSource": source, "pdfTitle": meta, "group": group, "bytes": d["bytes"],
                      "pdfKind": pdf_kind(head, tail) if head else ("unknown" if d["mimeType"].endswith("pdf")
                                                                    else None),
                      "linked": len(cands.get(d["path"], []))})
    fold_parts(items, cfg)
    items.sort(key=lambda i: (i["group"], i["title"].lower()))
    return items


PART_IN_TITLE = re.compile(r"\s*[\(\[]?\s*(part|pt\.?|vol(ume)?\.?)\s*\d+(\s*(of|/)\s*\d+)?\s*[\)\]]?\s*$", re.I)


def fold_parts(items, cfg):
    """
    `base-pt1.pdf`, `base-pt2.pdf`… (or a configured pattern) are parts of one work. When `base.pdf` is there too,
    the parts point at it (`partOf`) and it lists them (`parts`): one row, the whole first. When it isn't, the parts
    share a `work` {key, title} (the title without "(Part 1 of 3)") and an `order`: one row with its parts.
    """
    rx = re.compile(cfg.get("partPattern", r"^(?P<base>.+?)[-_ ]?(pt|part)[-_ ]?(?P<n>\d+)$"), re.I)
    stem = lambda i: re.sub(r"\.\w+$", "", posixpath.basename(i["key"]))  # noqa: E731
    by_stem = {stem(i): i for i in items}
    orphans = collections.defaultdict(list)
    for i in items:
        m = rx.match(stem(i))
        if not m:
            continue
        i["order"] = int(m.group("n"))
        whole = by_stem.get(m.group("base"))
        if whole is not None and whole is not i:
            i["partOf"] = whole["key"]
            whole.setdefault("parts", []).append(i["key"])
        else:
            orphans[posixpath.join(posixpath.dirname(i["key"]), m.group("base"))].append(i)
    for base, parts in orphans.items():
        if len(parts) < 2:
            parts[0].pop("order", None)
            continue
        parts.sort(key=lambda p: p["order"])
        title = PART_IN_TITLE.sub("", parts[0]["title"]).strip() or file_title(base)
        for p in parts:
            p["work"] = {"key": base, "title": title}
    order = {i["key"]: i.get("order", 0) for i in items}
    for i in items:
        if "parts" in i:
            i["parts"].sort(key=order.get)


EXTRACTORS = {"kolibri": extract_kolibri, "zimit-index": extract_zimit_index}


def keep_keys(items, previous):
    """An item whose key is new but whose title and size match a lost one keeps the old key (paths move)."""
    if not previous:
        return 0
    old = {i["key"]: i for i in previous["items"]}
    new_keys = {i["key"] for i in items}
    lost = {(o["title"], o["bytes"]): k for k, o in old.items() if k not in new_keys}
    kept = 0
    for i in items:
        if i["key"] not in old and (i["title"], i["bytes"]) in lost:
            i["key"] = lost.pop((i["title"], i["bytes"]))
            kept += 1
    return kept


def build(name, src, cfg, previous=None):
    pack = Pack(src)
    try:
        items = EXTRACTORS[cfg["extractor"]](pack, cfg)
        md = pack.z.metadata(("Title", "Date", "Language", "Creator", "Publisher"))
        kept = keep_keys(items, previous)
        return {
            "kind": "gbooks", "version": VERSION, "zimName": name, "source": src,
            "sourceDate": md.get("Date", ""), "entryCount": pack.z.entry_count,
            "collection": {"title": cfg.get("title") or md.get("Title", name), "noun": cfg.get("noun", "books"),
                           "provider": cfg.get("provider", md.get("Creator", "")),
                           "licence": cfg.get("licence", ""), "shelf": cfg.get("shelf", "")},
            "stats": {"docsInPack": len(pack.docs), "items": len(items), "keysKept": kept,
                      "titleSources": dict(collections.Counter(i["titleSource"] for i in items)),
                      "fetchedBytes": pack.r.fetched, "requests": pack.r.requests},
            "items": items,
        }
    finally:
        pack.close()


ITEM_COLUMNS = ("key", "path", "format", "title", "title_source", "author", "description", "licence", "language",
                "group_name", "ord", "bytes", "pdf_kind", "part_of", "work_key", "work_title", "part_no", "qid")


def write_gbooks(manifest, out, source_name, source_sha256, now=None):
    """Writes [manifest] as a `.gbooks` SQLite sidecar (schema.md), meta contract included."""
    import sqlite3
    out = pathlib.Path(out)
    out.unlink(missing_ok=True)
    db = sqlite3.connect(out)
    try:
        sidecar_meta.write_meta(db, "gbooks", SCHEMA_VERSION, pathlib.Path(source_name), BUILDER, VERSION,
                                source_sha256=source_sha256, now=now)
        db.execute("CREATE TABLE collection(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        coll = dict(manifest["collection"], sourceDate=manifest.get("sourceDate", ""), zimName=manifest["zimName"])
        db.executemany("INSERT INTO collection VALUES (?, ?)", [(k, str(v)) for k, v in coll.items() if v is not None])
        db.execute("CREATE TABLE groups(name TEXT PRIMARY KEY, ord INTEGER NOT NULL)")
        groups = list(dict.fromkeys(i.get("group", "") for i in manifest["items"] if i.get("group")))
        db.executemany("INSERT INTO groups VALUES (?, ?)", [(g, n) for n, g in enumerate(groups)])
        db.execute(f"""CREATE TABLE items(key TEXT PRIMARY KEY, path TEXT NOT NULL, format TEXT NOT NULL,
            title TEXT NOT NULL, title_source TEXT NOT NULL, author TEXT, description TEXT, licence TEXT,
            language TEXT, group_name TEXT NOT NULL DEFAULT '', ord INTEGER NOT NULL, bytes INTEGER NOT NULL,
            pdf_kind TEXT, part_of TEXT, work_key TEXT, work_title TEXT, part_no INTEGER, qid TEXT)""")
        rows = []
        for n, i in enumerate(manifest["items"]):
            work = i.get("work") or {}
            rows.append((i["key"], i["path"], i["format"], i["title"], i["titleSource"], i.get("author") or None,
                         i.get("description") or None, i.get("licence") or None, i.get("language") or None,
                         i.get("group", ""), n, i["bytes"], i.get("pdfKind"), i.get("partOf"), work.get("key"),
                         work.get("title"), i.get("order") if (work or i.get("partOf")) else None, i.get("qid")))
        db.executemany(f"INSERT INTO items VALUES ({','.join('?' * len(ITEM_COLUMNS))})", rows)
        db.commit()
        db.execute("VACUUM")
    finally:
        db.close()


def read_gbooks(path):
    """A `.gbooks` file (or a manifest .json) back in the builder's dict shape: collection, items, meta."""
    path = pathlib.Path(path)
    if path.suffix == ".json":
        return json.loads(path.read_text(encoding="utf-8"))
    import sqlite3
    db = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        meta = dict(db.execute("SELECT key, value FROM meta"))
        coll = dict(db.execute("SELECT key, value FROM collection"))
        items = []
        for row in db.execute(f"SELECT {', '.join(ITEM_COLUMNS)} FROM items ORDER BY ord"):
            r = dict(zip(ITEM_COLUMNS, row))
            item = {"key": r["key"], "path": r["path"], "format": r["format"], "title": r["title"],
                    "titleSource": r["title_source"], "group": r["group_name"], "bytes": r["bytes"]}
            for k, col in (("author", "author"), ("description", "description"), ("licence", "licence"),
                           ("language", "language"), ("pdfKind", "pdf_kind"), ("partOf", "part_of"), ("qid", "qid")):
                if r[col] is not None:
                    item[k] = r[col]
            if r["work_key"]:
                item["work"] = {"key": r["work_key"], "title": r["work_title"]}
            if r["part_no"] is not None:
                item["order"] = r["part_no"]
            items.append(item)
        return {"meta": meta, "zimName": coll.pop("zimName", ""), "sourceDate": coll.pop("sourceDate", ""),
                "collection": coll, "items": items}
    finally:
        db.close()


def main(argv):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("name")
    ap.add_argument("src", help="the ZIM: a local file or its URL")
    ap.add_argument("--out", required=True, help="a .gbooks file (or .json, to review a manifest)")
    ap.add_argument("--source-sha256", help="the ZIM's sha256 (from the catalog), needed when SRC is a URL")
    ap.add_argument("--config", default=str(pathlib.Path(__file__).with_name("book_packs.json")))
    ap.add_argument("--previous", help="the pack's previous .gbooks (or .json): keys of moved files are kept")
    ap.add_argument("--sample", type=int, default=0, help="print N random items for grading")
    a = ap.parse_args(argv)
    cfg = json.loads(pathlib.Path(a.config).read_text(encoding="utf-8"))["packs"][a.name]
    prev = read_gbooks(a.previous) if a.previous else None
    m = build(a.name, a.src, cfg, prev)
    out = pathlib.Path(a.out)
    if out.suffix == ".json":
        out.write_text(json.dumps(m, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    else:
        remote = a.src.startswith(("http://", "https://"))
        if remote and not a.source_sha256:
            raise SystemExit("a URL source needs --source-sha256 (the catalog's sha256 for that file)")
        sha = a.source_sha256 or sidecar_meta.sha256(a.src)
        source_name = urllib.parse.urlsplit(a.src).path.rsplit("/", 1)[-1] if remote else pathlib.Path(a.src).name
        write_gbooks(m, out, source_name, sha)
    print(json.dumps(m["stats"]))
    if a.sample:
        rnd = random.Random(16)
        for i in rnd.sample(m["items"], min(a.sample, len(m["items"]))):
            print(f'  [{i["titleSource"]:8}] {i["title"][:70]!r:72} {i.get("group","")[:20]:20} {i["path"][-45:]}')


if __name__ == "__main__":
    main(sys.argv[1:])
