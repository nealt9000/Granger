"""Where a book's cover page sits on a phone, under the reader's chrome, in Readium's paginated mode.

  python verse_check/cover_check.py BOOK.epub OUT_DIR [--size 390x844] [--size 412x915] [--no-bands]

The page is the book's `cover.xhtml` with its `book.css` between Readium CSS's sheets, paginated (the app never
scrolls a book). It sits in a phone-sized frame where the app puts Readium's page: below the cutout (the status
bar's place) and inside Readium's own 40 dp top and bottom padding. The chrome drawn over it, all in dp (CSS px):

- top: the status bar's place, the mini-player and the reader's top bar (24 + 56 + 64 = 144);
- bottom: the bottom bar, the progress row and the navigation bar (about 100).

Writes `<book>-<W>x<H>.png` (bands shown) and `<book>-<W>x<H>-full.png` (full screen, no bands) and prints, per
size, the image's box on the screen, how far it clears each band (negative: covered), and how many columns the
page took (more than 1: the cover spilled onto a second page).
"""
import argparse
import html
import json
import re
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path

from bs4 import BeautifulSoup

sys.path.insert(0, str(Path(__file__).resolve().parent))
import harness  # noqa: E402

STATUS, MINI_PLAYER, TOP_BAR, BOTTOM = 24, 56, 64, 100
READIUM_PADDING = 40  # readium_navigator_epub_vertical_padding, above and below the page when paginated

MEASURE = r"""<script>
window.addEventListener('load', () => document.fonts.ready.then(() => setTimeout(() => {
  const img = document.querySelector('img'), r = img ? img.getBoundingClientRect() : null;
  const res = {innerWidth, innerHeight, scrollWidth: document.documentElement.scrollWidth,
               img: r && {top: r.top, left: r.left, width: r.width, height: r.height}};
  const out = parent.document.createElement('script'); out.type = 'application/json'; out.id = 'cover-check';
  out.textContent = JSON.stringify(res); parent.document.body.appendChild(out);
}, 200)));
</script>"""


@dataclass
class Phone:
    width: int
    height: int

    @property
    def page_top(self) -> int:
        return STATUS + READIUM_PADDING

    @property
    def page_height(self) -> int:
        return self.height - STATUS - 2 * READIUM_PADDING

    @property
    def top_band(self) -> int:
        return STATUS + MINI_PLAYER + TOP_BAR

    @property
    def bottom_band(self) -> int:
        return self.height - BOTTOM


def clearance(phone: Phone, img: dict) -> tuple[float, float]:
    """How far the image's top sits below the top band and its foot above the bottom band, in dp."""
    top = phone.page_top + img["top"]
    return top - phone.top_band, phone.bottom_band - (top + img["height"])


def frame(page: str, phone: Phone, bands: bool) -> str:
    def band(top: int, height: int, label: str) -> str:
        return (f'<div style="position:absolute;left:0;width:{phone.width}px;top:{top}px;height:{height}px;'
                f'background:rgba(220,40,40,.45);color:#fff;font:12px sans-serif;display:flex;align-items:center;'
                f'justify-content:center">{label}</div>')
    overlay = ""
    if bands:
        overlay = (band(0, phone.top_band, f"status + mini-player + top bar ({phone.top_band} dp)")
                   + band(phone.bottom_band, BOTTOM, f"bottom bar + progress + nav ({BOTTOM} dp)"))
    return (f'<!DOCTYPE html><html><body style="margin:0;background:#fff">'
            f'<div style="position:relative;width:{phone.width}px;height:{phone.height}px;background:#fff">'
            f'<iframe src="{page}" style="position:absolute;border:0;left:0;top:{phone.page_top}px;'
            f'width:{phone.width}px;height:{phone.page_height}px;background:#fff"></iframe>{overlay}</div>'
            f"</body></html>")


def check(epub: Path, out_dir: Path, phones: list[Phone], bands: bool = True) -> list[dict]:
    with zipfile.ZipFile(epub) as z:
        css = z.read("OEBPS/book.css").decode()
        body = BeautifulSoup(z.read("OEBPS/cover.xhtml"), "lxml").body.decode_contents()
        jpeg = z.read("OEBPS/cover.jpg")
    stem = re.sub(r"_\d{4}-\d{2}-\d{2}$", "", epub.stem)
    results = []
    with harness.server() as (root, base):
        (root / "cover.jpg").write_bytes(jpeg)
        (root / "page.html").write_text(harness.document(css, body, 1.0, MEASURE, scroll=False))
        for phone in phones:
            size = f"{phone.width}x{phone.height}"
            for shown in ([True, False] if bands else [False]):
                name = f"frame-{size}-{int(shown)}.html"
                (root / name).write_text(frame("page.html", phone, shown))
                png = out_dir / f"{stem}-{size}{'' if shown else '-full'}.png"
                harness.chrome(f"{base}/{name}", f"--window-size={phone.width},{phone.height}",
                               "--virtual-time-budget=10000", f"--screenshot={png.resolve()}", timeout=300)
            run = harness.chrome(f"{base}/{name}", f"--window-size={phone.width},{phone.height}",
                                 "--virtual-time-budget=10000", "--dump-dom", timeout=300)
            found = re.search(r'<script type="application/json" id="cover-check">(.*?)</script>', run.stdout, re.S)
            measured = json.loads(html.unescape(found.group(1)))
            top, foot = clearance(phone, measured["img"])
            columns = round(measured["scrollWidth"] / measured["innerWidth"])
            results.append({"size": size, "img": measured["img"], "clears_top": round(top, 1),
                            "clears_bottom": round(foot, 1), "columns": columns})
    return results


def main(argv: list[str]) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("epub")
    parser.add_argument("out_dir")
    parser.add_argument("--size", action="append", help="WxH in dp, e.g. 390x844 (repeatable)")
    parser.add_argument("--no-bands", action="store_true")
    args = parser.parse_args(argv)
    harness.check_ready()
    phones = [Phone(*map(int, s.split("x"))) for s in (args.size or ["390x844", "412x915"])]
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    for r in check(Path(args.epub), out_dir, phones, not args.no_bands):
        print(json.dumps(r))


if __name__ == "__main__":
    main(sys.argv[1:])
