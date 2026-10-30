"""Measures how each book's verse lines wrap on a phone, under Readium CSS in headless Chrome.

  python verse_check/render.py BOOKS_DIR WIDTH SCALE [slug ...] [--out FILE] [--jobs N]

BOOKS_DIR holds `gc-<slug>_<date>.epub` files from `build-epubs`. With no slugs, every book whose manifest
row reads as verse (`WorkSpec.reads_as_verse`: poetry, drama, libretti, scripture, or `"verse": true`) is
measured. Writes `render-<WIDTH>-<SCALE>.json` (or --out): per book, its verse lines, how many wrapped, how
many of those hang (the continuation is indented) and how many wrap flush, paragraphs whose centring or right
alignment Readium overrode, and blocks that run off the screen.

A verse line is a `.gv-l` span (the builder's verse pass), a `<br>`-separated line in a block whose lines
average at most 90 characters, or one short element per line in a poem wrapper (`div.stanza > p` and the
like). A `<pre>` is counted by its source lines.
"""
import argparse
import concurrent.futures
import html
import json
import re
import sys
import warnings
import zipfile
from pathlib import Path

from bs4 import BeautifulSoup, Tag, XMLParsedAsHTMLWarning

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import harness  # noqa: E402
import manifest  # noqa: E402

warnings.filterwarnings("ignore", category=XMLParsedAsHTMLWarning)
MANIFEST = Path(__file__).resolve().parent.parent / "classics" / "manifest.json"
BLOCKS = {"p", "div", "table", "ul", "ol", "dl", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6", "hr"}
ELEMENT_LINES = ("div.stanza > p, div.stanza > div, div.stanza > span, div.poem > p, div.poem > div, div.poetry > p, "
                 "div.poetry > div, div.verse > p, div.verse > div, div.tei-l, div.line, p.line, p.verse")


def _br_block(block: Tag) -> bool:
    brs = block.find_all("br", recursive=False)
    if len(brs) < 2 or block.find(BLOCKS - {"p"}) is not None:
        return False
    segments, current = [], []
    for child in block.children:
        if isinstance(child, Tag) and child.name == "br":
            segments.append("".join(current))
            current = []
        else:
            current.append(child.get_text() if isinstance(child, Tag) else str(child))
    segments.append("".join(current))
    lines = [" ".join(s.split()) for s in segments if s.strip()]
    return bool(lines) and sum(map(len, lines)) / len(lines) <= 90


def mark(soup: BeautifulSoup) -> int:
    """Wraps each verse line in a `data-vl` span and marks its block `data-vb`; returns the line count."""
    count = 0
    for line in soup.select("span.gv-l"):
        line["data-vl"] = "1"
        line.parent["data-vb"] = "gv"
        count += 1
    for block in soup.find_all(["p", "div", "td", "blockquote", "span"]):
        if not _br_block(block):
            continue
        block["data-vb"] = "br"
        groups, current = [], []
        for child in list(block.children):
            if isinstance(child, Tag) and child.name == "br":
                groups.append(current)
                current = []
            else:
                current.append(child)
        groups.append(current)
        for nodes in groups:
            if not any((n.get_text() if isinstance(n, Tag) else str(n)).strip() for n in nodes):
                continue
            span = soup.new_tag("span", attrs={"data-vl": "1"})
            nodes[0].insert_before(span)
            for node in nodes:
                span.append(node.extract())
            count += 1
    for element in soup.select(ELEMENT_LINES):
        if element.find(BLOCKS) is not None or element.has_attr("data-vb") or element.find(attrs={"data-vl": True}):
            continue
        text = " ".join(element.get_text().split())
        if not text or len(text) > 120:
            continue
        span = soup.new_tag("span", attrs={"data-vl": "1"})
        for child in list(element.children):
            span.append(child.extract())
        element.append(span)
        element["data-vb"] = "el"
        count += 1
    for pre in soup.find_all("pre"):
        pre["data-vb"] = "pre"
        pre["data-n"] = str(sum(1 for line in pre.get_text().split("\n") if line.strip()))
    return count


MEASURE = r"""<script>
window.addEventListener('load', () => document.fonts.ready.then(() => {
  const W = document.documentElement.getBoundingClientRect().width;
  const body = document.body.getBoundingClientRect(), bs = getComputedStyle(document.body);
  const colL = body.left + parseFloat(bs.paddingLeft), col = body.right - parseFloat(bs.paddingRight) - colL;
  const res = {W, col, lines: 0, wrapped: 0, wrapped_hang: 0, wrapped_flush: 0, rows: 0, narrow: 0, offscreen: 0,
               pre_blocks: 0, pre_src: 0, pre_rows: 0, pre_offscreen: 0, mono: 0, examples: []};
  function rowsOf(rects) {
    const rows = [];
    for (const r of rects) {
      if (r.width < 1 || r.height < 1) continue;
      const row = rows.find(t => Math.abs(t.top - r.top) < r.height * 0.5);
      if (!row) rows.push({top: r.top, left: r.left, right: r.right});
      else { row.left = Math.min(row.left, r.left); row.right = Math.max(row.right, r.right); }
    }
    return rows.sort((a, b) => a.top - b.top);
  }
  document.querySelectorAll('[data-vl]').forEach(s => {
    const range = document.createRange(); range.selectNodeContents(s);
    const rows = rowsOf([...range.getClientRects()].filter(r => r.width < W));
    if (!rows.length) return;
    res.lines++; res.rows += rows.length;
    const block = s.closest('[data-vb]') || s.parentElement, cs = getComputedStyle(block);
    const br = block.getBoundingClientRect();
    const avail = br.width - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
    if (avail < col * 0.7) res.narrow++;
    if (rows.some(r => r.right > W + 1)) res.offscreen++;
    if (/mono/i.test(cs.fontFamily)) res.mono++;
    if (rows.length > 1) {
      res.wrapped++;
      if (rows[1].left > rows[0].left + 8 || rows[1].left > br.left + parseFloat(cs.paddingLeft) + 8) res.wrapped_hang++;
      else {
        res.wrapped_flush++;
        if (res.examples.length < 3) res.examples.push(s.textContent.trim().replace(/\s+/g, ' ').slice(0, 90));
      }
    }
  });
  document.querySelectorAll('pre[data-vb]').forEach(p => {
    const range = document.createRange(); range.selectNodeContents(p);
    const rows = rowsOf(range.getClientRects());
    res.pre_blocks++; res.pre_src += +p.dataset.n; res.pre_rows += rows.length;
    if (rows.some(r => r.right > W + 1)) res.pre_offscreen++;
    if (/mono/i.test(getComputedStyle(p).fontFamily)) res.mono++;
  });
  // Alignment the author asked for (centre, right) that Readium's after-sheet takes away.
  const ps = [...document.querySelectorAll('p')], after = document.styleSheets[document.styleSheets.length - 1];
  const now = ps.map(p => getComputedStyle(p).textAlign);
  after.disabled = true; const want = ps.map(p => getComputedStyle(p).textAlign); after.disabled = false;
  res.align_lost = 0; res.align_lost_cls = {};
  ps.forEach((p, i) => {
    if (/center|right|end/.test(want[i]) && !/center|right|end/.test(now[i])) {
      res.align_lost++;
      const k = 'p.' + [...p.classList].join('.') + ':' + want[i];
      res.align_lost_cls[k] = (res.align_lost_cls[k] || 0) + 1;
    }
  });
  res.docW = document.documentElement.scrollWidth;
  const out = parent.document.createElement('script'); out.type = 'application/json'; out.id = 'verse-check';
  out.textContent = JSON.stringify(res); parent.document.body.appendChild(out);
}));
</script>"""


def book_html(epub: Path, scale: float) -> str:
    with zipfile.ZipFile(epub) as z:
        css = z.read("OEBPS/book.css").decode()
        pages = sorted(n for n in z.namelist() if re.fullmatch(r"OEBPS/c\d+\.xhtml", n))
        parts = []
        for name in pages:
            soup = BeautifulSoup(z.read(name), "lxml")
            mark(soup)
            parts.append(soup.body.decode_contents())
    return harness.document(css, "".join(parts), scale, MEASURE)


def measure(epub: Path, width: int, scale: float, root: Path, base: str) -> dict:
    name = f"{epub.stem}-{width}-{scale}"
    (root / f"{name}.html").write_text(book_html(epub, scale))
    (root / f"{name}-frame.html").write_text(harness.frame(f"{name}.html", width, 900))
    run = harness.chrome(f"{base}/{name}-frame.html", "--window-size=500,900", "--virtual-time-budget=120000",
                         "--dump-dom")
    found = re.search(r'<script type="application/json" id="verse-check">(.*?)</script>', run.stdout, re.S)
    return json.loads(html.unescape(found.group(1))) if found else {"error": run.stderr[-400:]}


def verse_slugs(m: manifest.Manifest) -> list[str]:
    return [w.slug for w in m.works if w.reads_as_verse]


def main(argv: list[str]) -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("books")
    parser.add_argument("width", type=int)
    parser.add_argument("scale", type=float)
    parser.add_argument("slugs", nargs="*")
    parser.add_argument("--out")
    parser.add_argument("--jobs", type=int, default=6)
    parser.add_argument("--manifest", default=str(MANIFEST))
    args = parser.parse_args(argv)
    harness.check_ready()
    books = Path(args.books).expanduser()
    slugs = args.slugs or verse_slugs(manifest.load(Path(args.manifest)))
    epubs = {}
    for slug in slugs:
        found = sorted(books.glob(f"gc-{slug}_????-??-??.epub"))
        if not found:
            raise SystemExit(f"{slug}: no EPUB in {books}")
        epubs[slug] = found[-1]
    out = Path(args.out or f"render-{args.width}-{args.scale}.json")
    results = {}
    with harness.server() as (root, base), concurrent.futures.ThreadPoolExecutor(args.jobs) as pool:
        jobs = {slug: pool.submit(measure, epub, args.width, args.scale, root, base) for slug, epub in epubs.items()}
        for slug, job in jobs.items():
            results[slug] = r = job.result()
            print(slug, {k: r.get(k) for k in ("lines", "wrapped", "wrapped_flush", "align_lost", "error")},
                  file=sys.stderr)
    out.write_text(json.dumps(results, indent=1))
    print(f"{out}: {len(results)} books")


if __name__ == "__main__":
    main(sys.argv[1:])
