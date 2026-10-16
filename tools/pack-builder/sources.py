"""Cached, rate-limited fetches from a Project Gutenberg mirror.

Gutenberg asks automated tools to use mirrors rather than www.gutenberg.org. Every file is fetched once,
written atomically to the cache, and read from there on every later build.
"""
import os
import time
import urllib.request
from pathlib import Path
from typing import Callable

MIRROR = "https://aleph.pglaf.org/cache/epub"


class FetchError(RuntimeError):
    pass


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
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(path.name + ".tmp")
        tmp.write_bytes(data)
        os.replace(tmp, path)
        return data
