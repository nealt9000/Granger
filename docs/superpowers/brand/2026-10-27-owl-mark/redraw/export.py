"""Step 3: the clean geometry (mark.wkb) as compact curves: rasterise at 2x, potrace with tight settings, and write
granger-mark.svg in a square frame centred on the rim circle (viewBox 0 0 D D). The mark is one even-odd path; the
eye whites (less the pupils) are a path of their own (class="eyes"), and so are the pupils (class="pupils"), so the eyes
can be lantern with dark pupils, or cut out with pupils in the owl's colour. Paths in drawing order: eyes, pupils, mark."""
import subprocess, sys
import numpy as np
from PIL import Image, ImageDraw
from shapely import wkb
from shapely.geometry import Polygon
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.svgLib.path import parse_path

S = sys.argv[1]
ink = wkb.loads(open(S + "/mark.wkb", "rb").read())
(ocx, ocy, orr), _ = np.load(S + "/circles.npy")
D = round(2 * orr)
X0, Y0 = ocx - D / 2, ocy - D / 2
K = 2.0  # raster scale
POTRACE = "/opt/homebrew/bin/potrace"

from shapely.ops import unary_union
polys = list(getattr(ink, "geoms", [ink]))
holes = [Polygon(h) for g in polys for h in g.interiors if Polygon(h).centroid.y < 1100]
pupils = [g for g in polys if any(h.contains(g) for h in holes)]
body = [g for g in polys if not any(g is p for p in pupils)]
eyes = unary_union(holes).difference(unary_union(pupils))
eyes = list(getattr(eyes, "geoms", [eyes]))


def bitmap(geoms, holes=True):
    im = Image.new("1", (int(D * K), int(D * K)), 1)  # PIL "1": 0 is black, which potrace traces as ink
    d = ImageDraw.Draw(im)
    tf = lambda cs: [((x - X0) * K, (y - Y0) * K) for x, y in cs]
    for g in geoms:
        d.polygon(tf(g.exterior.coords), fill=0)
        if holes:
            for h in g.interiors:
                d.polygon(tf(h.coords), fill=1)
    if holes:  # islands inside holes (the pupils)
        for g in geoms:
            for g2 in geoms:
                if g2 is not g and any(Polygon(h).contains(g2) for h in g.interiors):
                    d.polygon(tf(g2.exterior.coords), fill=0)
    return im


def trace(im, name):
    im.save(f"{S}/{name}.pbm")
    subprocess.run([POTRACE, f"{S}/{name}.pbm", "-s", "-o", f"{S}/{name}-potrace.svg", "-t", "4", "-a", "1.0", "-O", "0.15"], check=True)
    svg = open(f"{S}/{name}-potrace.svg").read()
    import re
    ds = re.findall(r'<path d="([^"]+)"', svg)
    # potrace: transform="translate(0,H) scale(0.1,-0.1)" in raster px; map back to the mark frame (/K)
    H = im.height
    pen = SVGPathPen(None, ntos=lambda v: f"{v:.1f}".rstrip("0").rstrip("."))
    tpen = TransformPen(pen, (0.1 / K, 0, 0, -0.1 / K, 0, H / K))
    for d in ds:
        parse_path(d, tpen)
    return pen.getCommands()


mark_d = trace(bitmap(body), "mark")
eyes_d = trace(bitmap(eyes), "eyes")
pupils_d = trace(bitmap(pupils), "pupils")
svg = (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {D} {D}">\n'
       f'<!-- Granger: the owl in the G, from Neal\'s sketch (docs/superpowers/brand/2026-10-27-owl-mark/). -->\n'
       f'<path class="eyes" fill-rule="evenodd" d="{eyes_d}"/>\n'
       f'<path class="pupils" fill-rule="evenodd" d="{pupils_d}"/>\n'
       f'<path fill-rule="evenodd" d="{mark_d}"/>\n</svg>\n')
open(S + "/granger-mark.svg", "w").write(svg)
print("D", D, "mark path chars", len(mark_d), "eyes", len(eyes_d), "commands", mark_d.count("C") + mark_d.count("L"))
