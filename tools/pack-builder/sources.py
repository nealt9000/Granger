"""Cached, rate-limited fetches from a Project Gutenberg mirror, eBible.org, Standard Ebooks and Wikisource.

Gutenberg asks automated tools to use mirrors rather than www.gutenberg.org. Every file is fetched once,
written atomically to the cache, and read from there on every later build.
"""
import hashlib
import io
import json
import os
import re
import time
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path
from typing import Callable

MIRROR = "https://aleph.pglaf.org/cache/epub"


class FetchError(RuntimeError):
    pass


def _write_atomic(path: Path, data: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_bytes(data)
    os.replace(tmp, path)


def http_get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "granger-pack-builder"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


class GutenbergSource:
    def __init__(self, cache_dir: Path = Path("~/.cache/granger/gutenberg").expanduser(),
                 fetch: Callable[[str], bytes] = http_get, delay_s: float = 1.0):
        self.cache_dir, self.fetch, self.delay_s = Path(cache_dir), fetch, delay_s

    def html(self, book_id: int) -> bytes:
        return self._get(book_id, f"pg{book_id}-images.html")

    def rdf(self, book_id: int) -> bytes:
        return self._get(book_id, f"pg{book_id}.rdf")

    def _get(self, book_id: int, name: str) -> bytes:
        path = self.cache_dir / str(book_id) / name
        if path.exists():
            return path.read_bytes()
        url = f"{MIRROR}/{book_id}/{name}"
        time.sleep(self.delay_s)
        try:
            data = self.fetch(url)
        except Exception as cause:
            raise FetchError(f"book {book_id}: {url}: {cause}") from cause
        _write_atomic(path, data)
        return data


EBIBLE = "https://eBible.org"
EBIBLE_ID = re.compile(r"^[a-z0-9-]+$")
# eBible names each book file `<sort>-<BOOK><id>.usfm`, e.g. `02-GENengwebu.usfm`; the sort number is its order.
USFM_MEMBER = re.compile(r"^(\d{2,3})-([A-Z0-9]{3})[a-z0-9-]*\.(usfm|sfm)$")
MAX_MEMBER_BYTES = 20_000_000


def ebible_details_url(ebible_id: str) -> str:
    return f"{EBIBLE}/find/details.php?id={ebible_id}"


class EBibleSource:
    """A translation's USFM zip and its details page from eBible.org, cached under ~/.cache/granger/ebible/."""

    def __init__(self, cache_dir: Path = Path("~/.cache/granger/ebible").expanduser(),
                 fetch: Callable[[str], bytes] = http_get, delay_s: float = 1.0):
        self.cache_dir, self.fetch, self.delay_s = Path(cache_dir), fetch, delay_s

    def usfm_zip(self, ebible_id: str) -> bytes:
        return self._get(ebible_id, f"{ebible_id}_usfm.zip", f"{EBIBLE}/Scriptures/{ebible_id}_usfm.zip")

    def details(self, ebible_id: str) -> bytes:
        return self._get(ebible_id, "details.html", ebible_details_url(ebible_id))

    def books(self, ebible_id: str) -> list[tuple[str, str]]:
        """(book code, USFM text) for each book file, in eBible's order.

        The zip is untrusted: only flat `NN-BOOK<id>.usfm` members are read, straight into memory, so no path
        from the archive ever reaches the file system.
        """
        found = []
        with zipfile.ZipFile(io.BytesIO(self.usfm_zip(ebible_id))) as z:
            for info in z.infolist():
                match = USFM_MEMBER.match(info.filename)
                if not match or info.is_dir():
                    continue
                if info.file_size > MAX_MEMBER_BYTES:
                    raise FetchError(f"ebible {ebible_id}: {info.filename} is {info.file_size} bytes")
                text = z.read(info).decode("utf-8-sig")
                found.append((int(match.group(1)), match.group(2), text))
        if not found:
            raise FetchError(f"ebible {ebible_id}: no USFM books in the zip")
        return [(code, text) for _, code, text in sorted(found)]

    def _get(self, ebible_id: str, name: str, url: str) -> bytes:
        if not EBIBLE_ID.match(ebible_id):
            raise FetchError(f"ebible id {ebible_id!r} isn't lowercase letters, digits and hyphens")
        path = self.cache_dir / ebible_id / name
        if path.exists():
            return path.read_bytes()
        time.sleep(self.delay_s)
        try:
            data = self.fetch(url)
        except Exception as cause:
            raise FetchError(f"ebible {ebible_id}: {url}: {cause}") from cause
        _write_atomic(path, data)
        return data


STANDARD_EBOOKS = "https://standardebooks.org"
# An SE id is the path after /ebooks/: "author/title" or "author/title/translator", lowercase words and hyphens.
SE_ID = re.compile(r"^[a-z0-9]+([-_][a-z0-9]+)*(/[a-z0-9]+([-_][a-z0-9]+)*){1,3}$")
MAX_EPUB_BYTES = 60_000_000


def se_page_url(se_id: str) -> str:
    return f"{STANDARD_EBOOKS}/ebooks/{se_id}"


def se_epub_url(se_id: str, page: bytes) -> str:
    """The "compatible" EPUB the book's page links (not the kepub or "advanced" one); `?source=download` is what
    SE's own download link adds."""
    prefix = f"/ebooks/{se_id}/downloads/"
    links = re.findall(r'href="(' + re.escape(prefix) + r'[A-Za-z0-9_.-]+\.epub)"', page.decode("utf-8", "replace"))
    plain = [link for link in links if not link.endswith(("_advanced.epub", ".kepub.epub"))]
    if not plain:
        raise FetchError(f"standardebooks {se_id}: the page links no EPUB (a placeholder?)")
    return f"{STANDARD_EBOOKS}{plain[0]}?source=download"


class StandardEbooksSource:
    """A Standard Ebooks book page and its compatible EPUB, cached under ~/.cache/granger/standardebooks/<id>/."""

    def __init__(self, cache_dir: Path = Path("~/.cache/granger/standardebooks").expanduser(),
                 fetch: Callable[[str], bytes] = http_get, delay_s: float = 1.0):
        self.cache_dir, self.fetch, self.delay_s = Path(cache_dir), fetch, delay_s

    def page(self, se_id: str) -> bytes:
        return self._get(se_id, "page.html", se_page_url(se_id))

    def epub(self, se_id: str) -> bytes:
        path = self.cache_dir / se_id / "book.epub"
        data = path.read_bytes() if path.exists() else self._get(se_id, "book.epub", se_epub_url(se_id, self.page(se_id)))
        if not data.startswith(b"PK"):
            raise FetchError(f"standardebooks {se_id}: the download isn't an EPUB")
        return data

    def _get(self, se_id: str, name: str, url: str) -> bytes:
        if not SE_ID.match(se_id):
            raise FetchError(f"standardebooks id {se_id!r} isn't author/title[/translator] in lowercase words")
        path = self.cache_dir / se_id / name
        if path.exists():
            return path.read_bytes()
        time.sleep(self.delay_s)
        try:
            data = self.fetch(url)
        except Exception as cause:
            raise FetchError(f"standardebooks {se_id}: {url}: {cause}") from cause
        if len(data) > MAX_EPUB_BYTES:
            raise FetchError(f"standardebooks {se_id}: {url} is {len(data)} bytes")
        _write_atomic(path, data)
        return data


WIKISOURCE_API = "https://en.wikisource.org/w/api.php"
# Wikimedia asks for a User-Agent that names the tool and where to learn about it; no personal address.
WIKISOURCE_AGENT = "GrangerPackBuilder/1.0 (https://opengranger.org; offline reader pack builder)"


def wikisource_url(title: str) -> str:
    return "https://en.wikisource.org/wiki/" + urllib.parse.quote(title.replace(" ", "_"), safe="/(),:'!")


def _wikisource_get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": WIKISOURCE_AGENT})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


class WikisourceSource:
    """English Wikisource pages as the MediaWiki API renders them (`action=parse`), one JSON file per page, cached
    under ~/.cache/granger/wikisource/ by a hash of the title, and fetched at most once a second."""

    def __init__(self, cache_dir: Path = Path("~/.cache/granger/wikisource").expanduser(),
                 fetch: Callable[[str], bytes] = _wikisource_get, delay_s: float = 1.0):
        self.cache_dir, self.fetch, self.delay_s = Path(cache_dir), fetch, delay_s

    def parse(self, title: str) -> dict:
        """{"title", "revid", "text", "templates", "categories"} for the page, following redirects."""
        title = " ".join(title.replace("_", " ").split())
        if not title or any(c in title for c in "#<>[]{}|"):
            raise FetchError(f"wikisource title {title!r} isn't a page title")
        path = self.cache_dir / (hashlib.sha256(title.encode()).hexdigest()[:24] + ".json")
        if path.exists():
            return json.loads(path.read_text(encoding="utf-8"))
        query = urllib.parse.urlencode({"action": "parse", "page": title, "prop": "text|templates|categories|revid",
                                        "format": "json", "formatversion": "2", "disableeditsection": "1",
                                        "redirects": "1"})
        url = f"{WIKISOURCE_API}?{query}"
        time.sleep(self.delay_s)
        try:
            data = json.loads(self.fetch(url))
        except Exception as cause:
            raise FetchError(f"wikisource {title!r}: {url}: {cause}") from cause
        if "error" in data or "parse" not in data:
            raise FetchError(f"wikisource {title!r}: {data.get('error', {}).get('info', 'no page')}")
        parsed = data["parse"]
        page = {"asked": title, "title": parsed["title"], "revid": parsed.get("revid"), "text": parsed["text"],
                "templates": [t["title"] for t in parsed.get("templates", [])],
                "categories": [c["category"] for c in parsed.get("categories", [])]}
        _write_atomic(path, json.dumps(page, ensure_ascii=False).encode())
        return page
