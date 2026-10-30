"""A phone-sized screenshot of one page of a book, as the reader shows it, for looking at by eye.

  python verse_check/shot.py BOOK.epub PAGE OUT.png [--find TEXT] [--width 390] [--height 844] [--scale 1.0]

PAGE is the page number (3 is c003.xhtml). With --find, the shot starts a little above the first text node
holding TEXT.
"""
import argparse
import json
import sys
import warnings
import zipfile
from pathlib import Path

from bs4 import BeautifulSoup, XMLParsedAsHTMLWarning

sys.path.insert(0, str(Path(__file__).resolve().parent))
import harness  # noqa: E402

warnings.filterwarnings("ignore", category=XMLParsedAsHTMLWarning)

SCROLL = """<script>window.addEventListener('load', () => document.fonts.ready.then(() => {
  const walk = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT); let n;
  while ((n = walk.nextNode())) if (n.textContent.includes(%s)) {
    const r = document.createRange(); r.selectNode(n);
    window.scrollTo(0, r.getBoundingClientRect().top + window.scrollY - 60); break; }
}));</script>"""


def main(argv: list[str]) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("epub")
    parser.add_argument("page", type=int)
    parser.add_argument("out")
    parser.add_argument("--find")
    parser.add_argument("--width", type=int, default=390)
    parser.add_argument("--height", type=int, default=844)
    parser.add_argument("--scale", type=float, default=1.0)
    args = parser.parse_args(argv)
    harness.check_ready()
    with zipfile.ZipFile(args.epub) as z:
        css = z.read("OEBPS/book.css").decode()
        body = BeautifulSoup(z.read(f"OEBPS/c{args.page:03d}.xhtml"), "lxml").body.decode_contents()
    script = SCROLL % json.dumps(args.find) if args.find else ""
    out = Path(args.out).resolve()
    with harness.server() as (root, base):
        (root / "page.html").write_text(harness.document(css, body, args.scale, script))
        (root / "frame.html").write_text(harness.frame("page.html", args.width, args.height))
        harness.chrome(f"{base}/frame.html", f"--window-size={args.width},{args.height}",
                       "--virtual-time-budget=20000", f"--screenshot={out}", timeout=300)
    print(out)


if __name__ == "__main__":
    main(sys.argv[1:])
