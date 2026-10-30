"""The page Readium would show: the book's CSS between Readium CSS's before and after sheets, in Noto Serif.

The harness root style is what the Kotlin toolkit writes with publisher styles off (advanced settings on,
font override on, `textAlign: start`, line height 1.5, page margins 1), in scroll mode so columns don't
confuse the measuring. Pages are served over a local HTTP server, because Chrome won't load web fonts
from file:// pages inside an iframe.

  harness.py setup      # Readium CSS from the Gradle cache's readium-navigator AAR, Noto Serif from Google Fonts
"""
import contextlib
import functools
import glob
import http.server
import os
import re
import shutil
import socketserver
import subprocess
import sys
import tempfile
import threading
import urllib.request
import zipfile
from pathlib import Path

HOME = Path(os.environ.get("VERSE_CHECK_HOME", "~/.cache/granger/verse-check")).expanduser()
READIUM = HOME / "readium-css"
FONTS = HOME / "fonts"
CHROME = os.environ.get("CHROME", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
READIUM_VERSION = "3.4.0"
AAR = (f"~/.gradle/caches/modules-2/files-2.1/org.readium.kotlin-toolkit/readium-navigator/{READIUM_VERSION}/*/"
       f"readium-navigator-{READIUM_VERSION}.aar")
NOTO = "https://fonts.googleapis.com/css2?family=Noto+Serif:ital,wght@0,400;0,700;1,400&display=block"
# Google serves woff2 only to a browser it recognises.
BROWSER = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
FAMILY = "GNotoSerif"  # a name of its own, so a locally installed Noto Serif can't stand in


def setup() -> None:
    """Fills ~/.cache/granger/verse-check/ (or $VERSE_CHECK_HOME): Readium CSS and the Noto Serif web fonts."""
    aars = sorted(glob.glob(os.path.expanduser(AAR)))
    if not aars:
        raise SystemExit(f"no readium-navigator {READIUM_VERSION} AAR in the Gradle cache; build the app once "
                         "(./gradlew :app:assembleDebug) so Gradle downloads it")
    with zipfile.ZipFile(aars[0]) as aar:
        _extract(aar, "assets/readium/readium-css/", READIUM)
    css = _get(NOTO).decode()
    FONTS.mkdir(parents=True, exist_ok=True)
    for url in sorted(set(re.findall(r"url\((https://[^)]+\.woff2)\)", css))):
        (FONTS / url.rsplit("/", 1)[1]).write_bytes(_get(url))
    css = re.sub(r"url\(https://[^)]+/([^/)]+\.woff2)\)", r"url(fonts/\1)", css)
    (FONTS / "notoserif.css").write_text(css.replace("'Noto Serif'", f"'{FAMILY}'"))
    print(f"Readium CSS in {READIUM}, Noto Serif in {FONTS}")


def _extract(z: zipfile.ZipFile, prefix: str, to: Path) -> None:
    names = [n for n in z.namelist() if n.startswith(prefix) and not n.endswith("/")]
    if not names:
        raise SystemExit(f"no {prefix} in the AAR")
    for name in names:
        target = to / name[len(prefix):]
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(z.read(name))


def _get(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": BROWSER})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def check_ready() -> None:
    missing = [p for p in (READIUM / "ReadiumCSS-before.css", READIUM / "ReadiumCSS-after.css",
                           FONTS / "notoserif.css") if not p.exists()]
    if missing:
        raise SystemExit(f"missing {', '.join(map(str, missing))}: run `python verse_check/harness.py setup`")
    if not Path(CHROME).exists():
        raise SystemExit(f"no Chrome at {CHROME}; set $CHROME")


def root_style(scale: float) -> str:
    return ("--USER__view: readium-scroll-on; --USER__advancedSettings: readium-advanced-on; "
            f"--USER__fontOverride: readium-font-on; --USER__fontFamily: {FAMILY}, serif; --USER__textAlign: start; "
            f"--USER__lineHeight: 1.5; --USER__pageMargins: 1; --USER__fontSize: {int(round(scale * 100))}%;")


def document(book_css: str, body: str, scale: float, script: str = "") -> str:
    before = (READIUM / "ReadiumCSS-before.css").read_text()
    after = (READIUM / "ReadiumCSS-after.css").read_text()
    return (f'<!DOCTYPE html><html lang="en" style="{root_style(scale)}"><head><meta charset="utf-8"/>'
            '<meta name="viewport" content="width=device-width, initial-scale=1"/>'
            f'<link rel="stylesheet" href="notoserif.css"/><style>{before}</style><style>{book_css}</style>'
            f"<style>{after}</style></head><body>{body}{script}</body></html>")


def frame(page: str, width: int, height: int) -> str:
    """An outer page holding the harness in an iframe of the phone's width."""
    return (f'<!DOCTYPE html><html><body style="margin:0;background:#888"><iframe src="{page}" '
            f'style="border:0;width:{width}px;height:{height}px;background:#fff"></iframe></body></html>')


@contextlib.contextmanager
def server():
    """A temporary web root holding the fonts, served on a free local port: yields (root, base URL)."""
    with tempfile.TemporaryDirectory(prefix="verse-check-") as root:
        shutil.copytree(FONTS, Path(root) / "fonts")
        shutil.copy(FONTS / "notoserif.css", root)
        handler = functools.partial(_Quiet, directory=root)
        with socketserver.ThreadingTCPServer(("127.0.0.1", 0), handler) as httpd:
            thread = threading.Thread(target=httpd.serve_forever, daemon=True)
            thread.start()
            try:
                yield Path(root), f"http://127.0.0.1:{httpd.server_address[1]}"
            finally:
                httpd.shutdown()


class _Quiet(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def chrome(url: str, *args: str, timeout: int = 1200) -> subprocess.CompletedProcess:
    with tempfile.TemporaryDirectory() as profile:
        return subprocess.run([CHROME, "--headless=new", "--disable-gpu", "--hide-scrollbars",
                               f"--user-data-dir={profile}", *args, url],
                              capture_output=True, text=True, timeout=timeout)


if __name__ == "__main__":
    if sys.argv[1:] == ["setup"]:
        setup()
    else:
        raise SystemExit(__doc__)
