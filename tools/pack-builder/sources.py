"""Cached, rate-limited fetches from a Project Gutenberg mirror and from eBible.org.

Gutenberg asks automated tools to use mirrors rather than www.gutenberg.org. Every file is fetched once,
written atomically to the cache, and read from there on every later build.
"""
import io
import os
import re
import time
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
