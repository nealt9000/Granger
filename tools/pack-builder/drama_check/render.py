"""Render checks for the plays, under Readium CSS in headless Chrome (verse_check's harness).

  python drama_check/render.py BOOKS_DIR [slug ...] [--width 390]

Per play: every `gd-who` is bold (computed weight 600 or more) and starts before its speech's words (its box
ends left of or above the first word after it); every `gd-sd` block and `gd-sdi` span is italic, unless marked
`gd-own` (its italics are the source's). The verse pass rule itself is checked by verse_check, which counts
the `gd-speech.gv` blocks' lines like any others. Exits 1 when any play has a failure. With no slugs, every
book in BOOKS_DIR holding `gd-` markup is checked.
"""
import argparse
import html
import json
import re
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "verse_check"))
import harness  # noqa: E402

MEASURE = r"""<script>
window.addEventListener('load', () => document.fonts.ready.then(() => {
  const res = {who: 0, light: 0, after: 0, sd: 0, upright: 0, examples: []};
  document.querySelectorAll('.gd-who').forEach(w => {
    res.who++;
    if (+getComputedStyle(w).fontWeight < 600) { res.light++; if (res.examples.length < 3) res.examples.push('light: ' + w.textContent); }
    const speech = w.closest('.gd-speech'); if (!speech) return;
    const walk = document.createTreeWalker(speech, NodeFilter.SHOW_TEXT); let n, past = false, word = null;
    while ((n = walk.nextNode())) {
      if (w.contains(n)) { past = true; continue; }
      if (past && n.textContent.trim()) { word = n; break; }
    }
    if (!word) return;
    const r = document.createRange(); r.selectNodeContents(word); const a = r.getClientRects()[0];
    const b = w.getBoundingClientRect();
    if (a && !(b.bottom <= a.top + 2 || (Math.abs(b.top - a.top) < a.height && b.right <= a.left + 1))) {
      res.after++; if (res.examples.length < 3) res.examples.push('order: ' + w.textContent);
    }
  });
  document.querySelectorAll('.gd-sd:not(.gd-own), .gd-sdi').forEach(d => {
    res.sd++;
    if (getComputedStyle(d).fontStyle !== 'italic') { res.upright++; if (res.examples.length < 3) res.examples.push('upright: ' + d.textContent.slice(0, 60)); }
  });
  const out = parent.document.createElement('script'); out.type = 'application/json'; out.id = 'drama-check';
  out.textContent = JSON.stringify(res); parent.document.body.appendChild(out);
}));
</script>"""


def book_html(epub: Path) -> str | None:
    with zipfile.ZipFile(epub) as z:
        css = z.read("OEBPS/book.css").decode()
        pages = sorted(n for n in z.namelist() if re.fullmatch(r"OEBPS/c\d+\.xhtml", n))
        bodies = [re.search(r"<body>(.*)</body>", z.read(n).decode(), re.S).group(1) for n in pages]
    if not any('class="gd-' in b for b in bodies):
        return None
    return harness.document(css, "".join(bodies), 1.0, MEASURE)


def main(argv) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("directory")
    parser.add_argument("slugs", nargs="*")
    parser.add_argument("--width", type=int, default=390)
    args = parser.parse_args(argv)
    harness.check_ready()
    failed = 0
    with harness.server() as (root, base):
        for epub in sorted(Path(args.directory).expanduser().glob("*.epub")):
            slug = epub.stem.rsplit("_", 1)[0].split("-", 1)[1]
            if args.slugs and slug not in args.slugs:
                continue
            page = book_html(epub)
            if page is None:
                continue
            (root / "p.html").write_text(page)
            (root / "f.html").write_text(harness.frame("p.html", args.width, 900))
            run = harness.chrome(f"{base}/f.html", "--window-size=500,900", "--virtual-time-budget=120000",
                                 "--dump-dom")
            found = re.search(r'id="drama-check">(.*?)</script>', run.stdout, re.S)
            r = json.loads(html.unescape(found.group(1))) if found else {"error": run.stderr[-300:]}
            bad = "error" in r or r["light"] or r["after"] or r["upright"]
            failed += bool(bad)
            print(f"{slug[:34]:34} names {r.get('who', 0):5} light {r.get('light', 0):3} out-of-order "
                  f"{r.get('after', 0):3} directions {r.get('sd', 0):5} upright {r.get('upright', 0):3} "
                  f"{'FAIL ' + str(r.get('examples') or r.get('error')) if bad else 'ok'}")
    print(f"{failed} failing")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
