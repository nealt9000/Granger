"""Sketch C (tape on books) redrawn as clean geometry measured from the trace.

Writes mark.svg, redraw.png (render), overlay.png (red = sketch only, blue = redraw only)
and small-size previews. Coordinates are in the 1080x1000 trace frame.
"""
import math, sys
import numpy as np
from PIL import Image, ImageDraw

W, H = 1080, 1000

def rounded(pts, radii):
    """Corner-rounded closed polygon -> (svg path, raster point list)."""
    n = len(pts)
    if isinstance(radii, (int, float)):
        radii = [radii] * n
    svg, ras = [], []
    for i in range(n):
        p0, p1, p2 = pts[i - 1], pts[i], pts[(i + 1) % n]
        r = radii[i]
        v1 = (p0[0] - p1[0], p0[1] - p1[1]); v2 = (p2[0] - p1[0], p2[1] - p1[1])
        l1 = math.hypot(*v1); l2 = math.hypot(*v2)
        u1 = (v1[0] / l1, v1[1] / l1); u2 = (v2[0] / l2, v2[1] / l2)
        ang = math.acos(max(-1, min(1, u1[0] * u2[0] + u1[1] * u2[1])))
        if r <= 0 or ang < 1e-3 or abs(ang - math.pi) < 1e-3:
            svg.append(("L", p1)); ras.append(p1); continue
        d = min(r / math.tan(ang / 2), l1 / 2.05, l2 / 2.05)
        r = d * math.tan(ang / 2)
        t1 = (p1[0] + u1[0] * d, p1[1] + u1[1] * d); t2 = (p1[0] + u2[0] * d, p1[1] + u2[1] * d)
        din = (-u1[0], -u1[1])
        cross = din[0] * u2[1] - din[1] * u2[0]
        sweep = 1 if cross > 0 else 0
        svg.append(("L", t1)); svg.append(("A", r, sweep, t2))
        # raster: sample the arc
        bis = (u1[0] + u2[0], u1[1] + u2[1]); bl = math.hypot(*bis); bis = (bis[0] / bl, bis[1] / bl)
        hc = r / math.sin(ang / 2)
        c = (p1[0] + bis[0] * hc, p1[1] + bis[1] * hc)
        a1 = math.atan2(t1[1] - c[1], t1[0] - c[0]); a2 = math.atan2(t2[1] - c[1], t2[0] - c[0])
        da = a2 - a1
        while da > math.pi: da -= 2 * math.pi
        while da < -math.pi: da += 2 * math.pi
        for k in range(13):
            a = a1 + da * k / 12
            ras.append((c[0] + r * math.cos(a), c[1] + r * math.sin(a)))
    f = lambda v: f"{v:.1f}".rstrip("0").rstrip(".")
    d = []
    for j, s in enumerate(svg):
        if s[0] == "L":
            d.append(("M" if j == 0 else "L") + f(s[1][0]) + " " + f(s[1][1]))
        else:
            d.append(f"A{f(s[1])} {f(s[1])} 0 0 {s[2]} {f(s[3][0])} {f(s[3][1])}")
    return "".join(d) + "Z", ras

def circle(cx, cy, r):
    ras = [(cx + r * math.cos(t * math.pi / 36), cy + r * math.sin(t * math.pi / 36)) for t in range(72)]
    return f"M{cx - r} {cy}a{r} {r} 0 1 0 {2 * r} 0a{r} {r} 0 1 0 {-2 * r} 0Z", ras

def rrect(x0, y0, x1, y1, rl, rr_=None):
    rr_ = rl if rr_ is None else rr_
    return rounded([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], [rl, rr_, rr_, rl])

def taper(x0, y, x1, h):
    """A page line: pointed at x0, h thick at x1, rounded tip on the right."""
    xm = x0 + (x1 - x0) * 0.4
    return rounded([(x0, y), (xm, y - h / 2), (x1, y - h / 2), (x1, y + h / 2), (xm, y + h / 2)],
                   [0, 0, h / 2.1, h / 2.1, 0])

# Each group is one evenodd path: an outer shape, then holes, then islands inside holes.
groups = []

# 1. The cassette, a C open to the right, with its left leg cut by the tape opening's slant.
cass = rounded([(58, 74), (600, 66), (601, 152), (146, 158), (146, 440), (607, 438), (607, 492),
                (266, 498), (197, 638), (60, 640)],
               [52, 10, 8, 36, 30, 8, 8, 22, 10, 10])
label = rrect(216, 199, 600, 392, 62, 10)
reel = circle(321, 297, 55)
groups.append([cass, label, reel])

# 2. The tape opening: a trapezoid whose slants run parallel to the leg and to the G.
groups.append([rounded([(293, 524), (684, 519), (818, 630), (240, 633)], [20, 26, 14, 12])])

# 3. The G: open at the right, its bar an arrow pointing back into the tape.
g = rounded([(645, 68), (1020, 54), (1020, 204), (934, 205), (932, 146), (746, 142), (727, 160),
             (727, 376), (866, 492), (926, 492), (931, 348), (884, 344), (882, 386), (776, 318),
             (868, 232), (882, 240), (881, 276), (1012, 274), (1022, 290), (1024, 582), (992, 622),
             (874, 622), (648, 436)],
            [36, 26, 8, 8, 22, 30, 4, 12, 12, 26, 6, 6, 12, 6, 12, 6, 6, 14, 6, 10, 24, 14, 10])
groups.append([g])

# 4. Two books. Rounded spine ends on the left, page lines on the right.
def book(y0, y1, bands, cover_x, lines, cb=20, rtop=18, rbot=18):
    r = (y1 - y0) / 2
    outer = rounded([(50, y0), (1022, y0), (1022, y1), (50, y1)], [r, rtop, rbot, r])
    items = [outer, rrect(96, y0 + 34, 167, y1 - 20, 40, 6)]
    for bx in bands:
        items.append(rrect(bx, y0 + 34, bx + 25, y1 - 20, 12))
    items.append(rrect(cover_x, y0 + 26, 986, y1 - cb, 8))
    for (lx, ly) in lines:
        items.append(taper(lx, ly, 982, 15))
    return items

groups.append(book(654, 804, [205], 268, [(540, 697), (680, 725), (590, 753)], cb=26, rbot=46))
groups.append(book(806, 962, [206, 261], 322, [(548, 848), (650, 876), (575, 904)], cb=40, rtop=46))

def render(scale=1.0, size=(W, H)):
    mask = np.zeros((size[1], size[0]), bool)
    for grp in groups:
        gm = np.zeros_like(mask)
        for _, ras in grp:
            im = Image.new("1", size, 0)
            ImageDraw.Draw(im).polygon([(x * scale, y * scale) for x, y in ras], fill=1)
            gm ^= np.array(im, bool)
        mask |= gm
    return mask

if __name__ == "__main__":
    out = sys.argv[1]
    m = render()
    Image.fromarray(np.where(m, 0, 255).astype(np.uint8)).save(f"{out}/redraw.png")
    sk = np.array(Image.open(f"{out}/c_thresh.png").convert("L")) < 128
    ov = np.full((H, W, 3), 255, np.uint8)
    ov[sk & m] = (60, 60, 60)
    ov[sk & ~m] = (220, 40, 40)
    ov[~sk & m] = (40, 90, 230)
    Image.fromarray(ov).save(f"{out}/overlay.png")
    big = Image.fromarray(np.where(render(4, (W * 4, H * 4)), 0, 255).astype(np.uint8))
    sq = Image.new("L", (W * 4, W * 4), 255); sq.paste(big, (0, (W * 4 - H * 4) // 2))
    row = Image.new("L", (96 + 64 + 48 + 32 + 24 + 5 * 16, 112), 255); x = 8
    for s in (96, 64, 48, 32, 24):
        row.paste(sq.resize((s, s), Image.LANCZOS), (x, 104 - s)); x += s + 16
    row.save(f"{out}/sizes.png")
    paths = "".join(f'<path fill-rule="evenodd" d="{"".join(p for p, _ in grp)}"/>' for grp in groups)
    open(f"{out}/mark.svg", "w").write(
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="40 10 1000 1000">{paths}</svg>\n')
    print("svg bytes", len(paths))
