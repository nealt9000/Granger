"""Plan O0, question 1: how close is Wikimedia's HTML to the Kiwix (mwoffliner) HTML the reader is built for?

For each title, fetch Wikimedia's mobile-html and Parsoid HTML and read the same article from a ZIM, then compare what
the reader's code reads: blocks (ArticleText.BLOCKS minus NOISE), sections, collapsible <details>, links, pictures, and
anything that needs a script to show (mobile-html's lazy picture placeholders).

  ~/.venvs/granger-pack/bin/python html_shape.py /Volumes/X10\\ Pro/granger-ll/sources/wikipedia_en_100_2026-08.zim Photosynthesis Moon
"""
import sys, re, json, urllib.request, urllib.parse
from bs4 import BeautifulSoup
from libzim.reader import Archive

UA = {"User-Agent": "Granger/spike (https://opengranger.org; feedback@opengranger.org)"}
NOISE = "script, style, sup.reference, .mw-editsection, .navbox, table, nav, footer"
BLOCKS = "h1, h2, h3, h4, p, li, dd, blockquote, pre"


def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
        return r.read().decode("utf-8")


def shape(html):
    doc = BeautifulSoup(html, "html.parser")
    noise = set()
    for n in doc.select(NOISE):
        noise.add(id(n))
        for d in n.descendants:
            noise.add(id(d))
    blocks = [b for b in doc.select(BLOCKS) if id(b) not in noise and b.get_text(" ", strip=True)]
    words = sum(len(b.get_text(" ", strip=True).split()) for b in blocks)
    hrefs = [a.get("href", "") for a in doc.select("a[href]")]
    return {
        "bytes": len(html),
        "blocks": len(blocks),
        "words": words,
        "h2": [h.get_text(" ", strip=True) for h in doc.select("h2")][:30],
        "details": len(doc.select("details")),
        "sections": len(doc.select("section")),
        "img": len(doc.select("img[src]")),
        "lazy_placeholders": len(doc.select(".pcs-lazy-load-placeholder")),
        "links": len(hrefs),
        "link_forms": sorted({re.sub(r"[A-Za-z0-9_%().,'-]+", "T", h)[:18] for h in hrefs})[:12],
        "scripts": len(doc.select("script")),
        "first_p": next((b.get_text(" ", strip=True)[:160] for b in blocks if b.name == "p"), ""),
    }


def main():
    zim = Archive(sys.argv[1])
    for title in sys.argv[2:]:
        t = urllib.parse.quote(title)
        out = {}
        try:
            e = zim.get_entry_by_path(title)
            out["zim"] = shape(bytes(e.get_item().content).decode("utf-8"))
        except KeyError:
            out["zim"] = None
        out["mobile_html"] = shape(get(f"https://en.wikipedia.org/api/rest_v1/page/mobile-html/{t}"))
        out["parsoid"] = shape(get(f"https://en.wikipedia.org/w/rest.php/v1/page/{t}/html"))
        print(f"\n=== {title}")
        keys = ["bytes", "blocks", "words", "details", "sections", "img", "lazy_placeholders", "links", "scripts"]
        print(f"{'':20s}" + "".join(f"{k:>11s}" for k in ["zim", "mobile_html", "parsoid"]))
        for k in keys:
            print(f"{k:20s}" + "".join(f"{(out[s] or {}).get(k, '-')!s:>11s}" for s in ["zim", "mobile_html", "parsoid"]))
        for s in ["zim", "mobile_html", "parsoid"]:
            if out[s]:
                print(f"  {s} link forms: {out[s]['link_forms']}")
                print(f"  {s} h2: {out[s]['h2'][:8]}")
                print(f"  {s} first p: {out[s]['first_p'][:120]!r}")


if __name__ == "__main__":
    main()
