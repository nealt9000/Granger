"""Rasterise granger-mark.svg (even-odd, Béziers flattened) to check the exported curves. Usage: render_svg.py S size out.png [eyes pupils mark ground, hex]"""
import re, sys
import numpy as np
from PIL import Image, ImageDraw, ImageChops
from fontTools.pens.basePen import BasePen
from fontTools.svgLib.path import parse_path


class Flat(BasePen):
    def __init__(self):
        super().__init__(None); self.subs = []; self.cur = []
    def _moveTo(self, p): self.cur = [p]; self.subs.append(self.cur)
    def _lineTo(self, p): self.cur.append(p)
    def _curveToOne(self, a, b, c):
        p0 = self.cur[-1]
        for t in np.linspace(0, 1, 13)[1:]:
            m = 1 - t
            self.cur.append(tuple(m**3 * np.array(p0) + 3 * m * m * t * np.array(a) + 3 * m * t * t * np.array(b) + t**3 * np.array(c)))
    def _closePath(self): pass


def mask(d, D, n, ss=4):
    pen = Flat(); parse_path(d, pen)
    acc = Image.new("1", (n * ss, n * ss), 0)
    k = n * ss / D
    for sub in pen.subs:
        m = Image.new("1", acc.size, 0)
        ImageDraw.Draw(m).polygon([(x * k, y * k) for x, y in sub], fill=1)
        acc = ImageChops.logical_xor(acc, m)
    return acc.convert("L").resize((n, n), Image.LANCZOS)


if __name__ == "__main__":
    S, n, out = sys.argv[1], int(sys.argv[2]), sys.argv[3]
    svg = open(S + "/granger-mark.svg").read()
    D = float(re.search(r'viewBox="0 0 ([\d.]+)', svg).group(1))
    eyes_d, pupils_d, mark_d = re.findall(r' d="([^"]+)"', svg)
    cols = [tuple(int(c[i:i + 2], 16) for i in (0, 2, 4)) for c in (sys.argv[4:8] or ["EFE4D0", "1F1813", "1F1813", "EFE4D0"])]
    img = Image.new("RGB", (n, n), cols[3])
    img.paste(Image.new("RGB", (n, n), cols[0]), (0, 0), mask(eyes_d, D, n))
    img.paste(Image.new("RGB", (n, n), cols[1]), (0, 0), mask(pupils_d, D, n))
    img.paste(Image.new("RGB", (n, n), cols[2]), (0, 0), mask(mark_d, D, n))
    img.save(out)
