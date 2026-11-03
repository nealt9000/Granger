"""The door taglines under the Spectral lockup, Night and Field, with today's line for reference.
Usage (from the repo root): uv run --with pillow --with fonttools --with numpy python tags.py out.png"""
import re, sys
from pathlib import Path
ROOT = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(ROOT / "docs/superpowers/brand/2026-10-27-owl-mark/redraw"))
from PIL import Image, ImageDraw, ImageFont
from render_svg import mask

OUT = sys.argv[1]
FONTS = ROOT / "app/src/main/res/font"
svg = (ROOT / "docs/superpowers/brand/2026-10-27-owl-mark/granger-mark-owl.svg").read_text()
D = float(re.search(r'viewBox="0 0 ([\d.]+)', svg).group(1))
eyes_d, pupils_d, mark_d = re.findall(r' d="([^"]+)"', svg)
OWL = 170
masks = [mask(d, D, OWL) for d in (eyes_d, pupils_d, mark_d)]
hx = lambda c: tuple(int(c[i:i + 2], 16) for i in (0, 2, 4))
# Current palette (brief §8, revised 2026-10-27): Night ground 12211E, Field paper F3EEE4.
THEMES = [dict(ground="12211E", eyes="F0A35E", pupils="12211E", mark="EFE4D0", word="EFE4D0", sub="91C4BA"),
          dict(ground="F3EEE4", eyes="F0A35E", pupils="2E4A45", mark="2E4A45", word="1F1813", sub="2E4A45")]
word = ImageFont.truetype(str(FONTS / "spectral_semibold.ttf"), 96)
tag = ImageFont.truetype(str(FONTS / "spectral_italic.ttf"), 34)
lab = ImageFont.truetype(str(FONTS / "courier_prime_regular.ttf"), 22)
ROWS = [  # label, tagline lines (one line in the lockup; Welcome stacks them)
    ("now: The world’s knowledge. Yours to keep.", ["The world’s knowledge. Yours to keep."]),
    ("A  Your door to knowledge. Always open.", ["Your door to knowledge. Always open."]),
    ("B  The door is always open.", ["The door is always open."]),
]
WELCOME = [["The world’s knowledge.", "Yours to keep."], ["Your door to knowledge.", "Always open."], ["The door is", "always open."]]

def tracked(d, xy, text, f, track, fill):
    x, y = xy
    for ch in text:
        d.text((x, y), ch, font=f, fill=fill, anchor="ls")
        x += f.getlength(ch) + track * f.size

def owl(cell, t, x, y):
    for m, k in zip(masks, ("eyes", "pupils", "mark")):
        cell.paste(Image.new("RGB", (OWL, OWL), hx(t[k])), (x, y), m)

CW, CH, PAD, WW = 980, 250, 24, 420
W = PAD + 2 * (CW + PAD)
img = Image.new("RGB", (W, 60 + len(ROWS) * (CH + 40) + 40 + 560), hx("2A2F30"))
dr = ImageDraw.Draw(img)
for r, (name, lines) in enumerate(ROWS):
    y0 = 50 + r * (CH + 40)
    dr.text((PAD, y0 - 12), name, font=lab, fill=hx("EFE4D0"), anchor="ls")
    for c, t in enumerate(THEMES):
        cell = Image.new("RGB", (CW, CH), hx(t["ground"]))
        oy = (CH - OWL) // 2
        owl(cell, t, 50, oy)
        cd = ImageDraw.Draw(cell)
        base = oy + 112
        tracked(cd, (50 + OWL + 40, base), "GRANGER", word, 0.12, hx(t["word"]))
        cd.text((50 + OWL + 44, base + 50), lines[0], font=tag, fill=hx(t["sub"]), anchor="ls")
        img.paste(cell, (PAD + c * (CW + PAD), y0))
# Welcome, Night: the mark over the tagline as two lines, as the app stacks it.
y0 = 50 + len(ROWS) * (CH + 40) + 30
dr.text((PAD, y0 - 12), "Welcome (Night), two lines: now · A · B", font=lab, fill=hx("EFE4D0"), anchor="ls")
gap = (W - 2 * PAD - 3 * WW) // 2
t = THEMES[0]
for i, pair in enumerate(WELCOME):
    cell = Image.new("RGB", (WW, 520), hx(t["ground"]))
    owl(cell, t, (WW - OWL) // 2, 60)
    cd = ImageDraw.Draw(cell)
    for j, line in enumerate(pair):
        cd.text((WW // 2, 320 + j * 48), line, font=tag, fill=hx(t["word"]), anchor="ms")
    img.paste(cell, (PAD + i * (WW + gap), y0))
img.save(OUT)
print(img.size)
