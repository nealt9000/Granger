"""Wordmark candidates beside the owl, Night and Field. Usage: board.py S out.png"""
import re, sys
sys.path.insert(0, sys.argv[1] + "/owl")
from PIL import Image, ImageDraw, ImageFont
from render_svg import mask

S, OUT = sys.argv[1], sys.argv[2]
F = S + "/fonts2/"
svg = open(S + "/owl/granger-mark.svg").read()
D = float(re.search(r'viewBox="0 0 ([\d.]+)', svg).group(1))
eyes_d, pupils_d, mark_d = re.findall(r' d="([^"]+)"', svg)
OWL = 170
masks = [mask(d, D, OWL) for d in (eyes_d, pupils_d, mark_d)]
hx = lambda c: tuple(int(c[i:i + 2], 16) for i in (0, 2, 4))
THEMES = {"Night": dict(ground="181C1D", eyes="F0A35E", pupils="181C1D", mark="EFE4D0", word="EFE4D0", sub="91C4BA"),
          "Field": dict(ground="EFE4D0", eyes="F0A35E", pupils="2E4A45", mark="2E4A45", word="1F1813", sub="2E4A45")}

def font(path, size, axes=None):
    f = ImageFont.truetype(path, size)
    if axes: f.set_variation_by_axes(axes)
    return f

CANDS_ALL = [  # label, font, tracking (em), note
    ("now: Big Shoulders Stencil", font(S + "/font/BigShouldersStencil.ttf", 120, [800, 72]), 0.04),
    ("A  Big Shoulders Display (same bones, solid)", font(F + "BigShouldersDisplay.ttf", 120, [800]), 0.04),
    ("B  Barlow Semi Condensed (field equipment)", font(F + "BarlowSemiCondensed-SemiBold.ttf.ttf", 104), 0.06),
    ("C  Chakra Petch (cut corners, like the owl)", font(F + "ChakraPetch-SemiBold.ttf.ttf", 92), 0.06),
    ("D  Archivo Expanded (wide, planted)", font(F + "Archivo.ttf", 82, [700, 125]), 0.04),
    ("E  Space Grotesk (modern, a little punk)", font(F + "SpaceGrotesk.ttf", 96, [700]), 0.02),
    ("F  Spectral caps (the library voice)", font(F + "Spectral-SemiBold.ttf.ttf", 96), 0.12),
]
CANDS = [CANDS_ALL[-1]] * 4
TAGS = ["The world's knowledge. Yours to keep.", "Knowledge is a human right.", "Knowledge sovereignty.", "The world's knowledge, kept."]
tag = font(F + "Spectral-Italic.ttf.ttf", 34)
lab = font(F + "CourierPrime-Regular.ttf.ttf", 22)

def tracked(d, xy, text, f, track, fill):
    x, y = xy
    for ch in text:
        d.text((x, y), ch, font=f, fill=fill, anchor="ls")
        x += f.getlength(ch) + track * f.size
    return x

CW, CH, PAD = 980, 250, 24
img = Image.new("RGB", (PAD + 2 * (CW + PAD), 60 + len(CANDS) * (CH + 40)), hx("2A2F30"))
dr = ImageDraw.Draw(img)
for r, (name, f, tr) in enumerate(CANDS):
    y0 = 50 + r * (CH + 40)
    dr.text((PAD, y0 - 12), TAGS[r], font=lab, fill=hx("EFE4D0"), anchor="ls")
    for c, t in enumerate(THEMES.values()):
        x0 = PAD + c * (CW + PAD)
        cell = Image.new("RGB", (CW, CH), hx(t["ground"]))
        oy = (CH - OWL) // 2
        for m, k in zip(masks, ("eyes", "pupils", "mark")):
            cell.paste(Image.new("RGB", (OWL, OWL), hx(t[k])), (50, oy), m)
        cd = ImageDraw.Draw(cell)
        base = oy + 112
        tracked(cd, (50 + OWL + 40, base), "GRANGER", f, tr, hx(t["word"]))
        cd.text((50 + OWL + 44, base + 50), TAGS[r], font=tag, fill=hx(t["sub"]), anchor="ls")
        img.paste(cell, (x0, y0))
img.save(OUT)
print(img.size)
