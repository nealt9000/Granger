"""Step 2: the owl in the G as clean geometry, from the smoothed trace (smooth.npy) and the fitted circles.

Writes mark.npy (shapely geometry as WKB), redraw.png, overlay.png (red = sketch only, blue = redraw only),
small.png (96/48/32/24 px, paper on gunmetal and ink on paper). Coordinates are the 1952x2048 trace frame.
"""
import sys
import numpy as np
from PIL import Image, ImageDraw
from shapely import affinity, wkb
from shapely.geometry import Point, Polygon, box
from shapely.ops import unary_union

S = sys.argv[1]
GAP = float(sys.argv[2]) if len(sys.argv) > 2 else 0  # extra white added to each cut, trace px
lab = np.load(S + "/lab.npy")
H, W = lab.shape
(ocx, ocy, orr), (icx, icy, irr) = np.load(S + "/circles.npy")
AXIS = 1090.0  # the beak tip and the midpoint of the ears

items = np.load(S + "/smooth.npy", allow_pickle=True)
by, corners_of = {}, {}
for i, sm, cs in items:
    by.setdefault(int(i), []).append(sm)
    corners_of.setdefault(int(i), []).append(cs)


def normals(p):
    """Unit normals pointing out of the closed contour p."""
    t = np.roll(p, -3, 0) - np.roll(p, 3, 0)
    n = np.c_[t[:, 1], -t[:, 0]]
    n = n / np.maximum(np.hypot(*n.T), 1e-9)[:, None]
    shape = Polygon(p).buffer(0)
    probe = p[:: max(1, len(p) // 60)] + 6 * n[:: max(1, len(p) // 60)]
    inside = np.mean([shape.contains(Point(*q)) for q in probe])
    return -n if inside > 0.5 else n


def snap(p, c, r, tol=45, blend=30, outward=True):
    """Pull points near the circle onto it, where the edge faces the same way as the circle."""
    p = p.copy()
    v = p - c
    d = np.hypot(*v.T)
    n = normals(p)
    radial = (v / d[:, None] * n).sum(1)
    facing = radial > 0.6 if outward else radial < -0.6
    err = np.abs(d - r)
    w = np.clip(1 - (err - tol) / blend, 0, 1) * facing
    target = c + v / d[:, None] * r
    return p + (target - p) * w[:, None]


def arc_replace(p, c, r, inward=False, tol=150, close=40, stops=()):
    """Replace the longest run of edge facing along the circle (within tol) with the true arc, so the edge is exact."""
    n = len(p)
    v = p - c
    d = np.hypot(*v.T)
    nn = normals(p)
    radial = (v / d[:, None] * nn).sum(1)
    m = ((radial < -0.5) if inward else (radial > 0.5)) & (np.abs(d - r) < tol)
    # bridge short breaks (notches in the drawing)
    for i in range(n):
        if not m[i] and m[(i - 1) % n]:
            j = next((k for k in range(1, close) if m[(i + k) % n]), None)
            if j:
                for k in range(j):
                    m[(i + k) % n] = True
    # an arc never runs past a corner (a band's tip, the crescent's points)
    for q in stops:
        k = int(np.argmin(np.hypot(*(p - q).T)))
        m[k] = False
    # the longest circular run
    best, start = (0, 0), None
    for i in range(2 * n):
        if m[i % n]:
            if start is None:
                start = i
            if i - start + 1 > best[0] and i - start + 1 <= n:
                best = (i - start + 1, start)
        else:
            start = None
    length, a = best
    b = a + length - 1
    idx = np.arange(a, b + 1) % n
    th = np.unwrap(np.arctan2(v[idx, 1], v[idx, 0]))
    arc_t = np.linspace(th[0], th[-1], max(8, int(abs(th[-1] - th[0]) * r / 3)))
    arc = c + r * np.c_[np.cos(arc_t), np.sin(arc_t)]
    rest = p[np.arange(b + 1, a + n) % n]
    return np.vstack([arc, rest])


def poly(i, k=0):
    return Polygon(by[i][k])


def comp_at(x, y):
    return int(lab[int(y), int(x)])


oc, ic = np.array([ocx, ocy]), np.array([icx, icy])
disk = Point(ocx, ocy).buffer(orr, resolution=256)
inner = Point(icx, icy).buffer(irr, resolution=256)

crescent_id = comp_at(1000, 300)
spur_id = comp_at(1700, 1100)
owl_id = comp_at(1090, 1300)
# the three bands, top to bottom: the components left of the owl below the crescent, by their top edge
band_ids = sorted([i for i in by if i not in (crescent_id, spur_id, owl_id) and len(by[i][0]) > 400], key=lambda i: by[i][0][:, 1].min())
pupil_ids = sorted([i for i in by if i not in (crescent_id, spur_id, owl_id, *band_ids)], key=lambda i: by[i][0][:, 0].mean())

def raster(geom, scale=1.0, size=None, ox=0, oy=0):
    w, h = size or (W, H)
    im = Image.new("L", (int(w), int(h)), 0)
    d = ImageDraw.Draw(im)
    geoms = getattr(geom, "geoms", [geom])
    for g in geoms:
        d.polygon([((x - ox) * scale, (y - oy) * scale) for x, y in g.exterior.coords], fill=255)
        for hole in g.interiors:
            d.polygon([((x - ox) * scale, (y - oy) * scale) for x, y in hole.coords], fill=0)
    # pupils inside eye holes are separate polygons drawn after: redraw any polygon lying inside another's hole
    for g in geoms:
        for g2 in geoms:
            if g2 is not g and any(Polygon(hh).contains(g2) for hh in g.interiors):
                d.polygon([((x - ox) * scale, (y - oy) * scale) for x, y in g2.exterior.coords], fill=255)
    return im



# The crescent and the wing, as drawn (smoothed), filled out to the rim along rays so the outline is the true circle,
# then each cut re-carved near the rim between the two arcs fitted to its edges (edges.npy), so it meets the rim cleanly.
pieces = [crescent_id] + band_ids
drawn = unary_union([Polygon(by[i][0]).buffer(0) for i in pieces])
mask = np.array(raster(drawn)) > 127


def rmax(theta):
    rs = np.arange(orr - 160, orr + 60, 1.0)
    xs = (ocx + rs * np.cos(theta)).astype(int)
    ys = (ocy + rs * np.sin(theta)).astype(int)
    ok = (xs >= 0) & (xs < W) & (ys >= 0) & (ys < H)
    hit = np.zeros_like(rs, bool)
    hit[ok] = mask[ys[ok], xs[ok]]
    return rs[hit].max() if hit.any() else None


ring = []
for t in np.radians(np.arange(-180, 180, 0.1)):
    r0 = rmax(t)
    if r0 is not None and orr - r0 < 150:
        ring.append((t, r0))
fill = []
for (t0, r0), (t1, r1) in zip(ring, ring[1:]):
    if t1 - t0 > np.radians(0.15):
        continue
    fill.append(Polygon([(ocx + r0 * np.cos(t0) * 0.999, ocy + r0 * np.sin(t0) * 0.999),
                         (ocx + (orr + 5) * np.cos(t0), ocy + (orr + 5) * np.sin(t0)),
                         (ocx + (orr + 5) * np.cos(t1), ocy + (orr + 5) * np.sin(t1)),
                         (ocx + r1 * np.cos(t1) * 0.999, ocy + r1 * np.sin(t1) * 0.999)]).buffer(0.3))
wing = unary_union([drawn, *fill])

edges = np.load(S + "/edges.npy", allow_pickle=True).item()
rimzone = disk.buffer(10).difference(Point(ocx, ocy).buffer(orr - 200))
for a_id, b_id in zip(pieces, pieces[1:]):
    ca, cb = edges[(a_id, "low")], edges[(b_id, "up")]
    A = Point(ca[0], ca[1]).buffer(ca[2], resolution=512)
    B = Point(cb[0], cb[1]).buffer(cb[2], resolution=512)
    channel = unary_union([Polygon(by[a_id][0]).buffer(0), Polygon(by[b_id][0]).buffer(0)]).buffer(45).buffer(-45)
    channel = channel.difference(Polygon(by[a_id][0]).buffer(0)).difference(Polygon(by[b_id][0]).buffer(0))
    mouth = channel.intersection(rimzone)
    mouth = max(getattr(mouth, "geoms", [mouth]), key=lambda g: g.area).representative_point()
    # the gap is the region on the mouth's side of both arcs
    sideA = A if A.contains(mouth) else box(-5000, -5000, 9000, 9000).difference(A)
    sideB = B if B.contains(mouth) else box(-5000, -5000, 9000, 9000).difference(B)
    gap = sideA.intersection(sideB).intersection(rimzone).intersection(mouth.buffer(260))
    gap = max(getattr(gap, "geoms", [gap]), key=lambda g: g.area)
    wing = wing.difference(gap)
# The crescent's inner edge: the true inner circle wherever the drawing follows it (within 6 px, from 178 degrees round to where the tip curve takes over).
fan = Polygon([(icx, icy)] + [(icx + 3000 * np.cos(t), icy + 3000 * np.sin(t)) for t in np.radians(np.arange(178, 255.1, 1))])
inner_ring = Point(icx, icy).buffer(irr + 10, resolution=512).difference(inner)
wing = unary_union([wing.difference(inner.intersection(fan)), inner_ring.intersection(fan)])

# The crescent's tip: from where it leaves the inner circle, its inner edge is one fitted curve (all but tangent to the
# inner circle, so the edge, which keeps clear of both, hands over smoothly) carried on until it meets the rim.
tx, ty, tr = edges["tip"]
tipzone = box(900, 250, 1800, 660)
wing = unary_union([wing.difference(tipzone),
                    disk.intersection(tipzone).difference(Point(tx, ty).buffer(tr, resolution=512)).difference(inner)])
# ...ending where the sketch's tip ends (35 degrees above the centre line), cut square to the rim
TIP_END = np.radians(-35)
beyond = Polygon([(ocx, ocy), (ocx + 2000 * np.cos(TIP_END), ocy + 2000 * np.sin(TIP_END)), (ocx + 2000, ocy)])
wing = wing.difference(beyond).buffer(4).buffer(-4)  # seal the hairline seams where the pieces join

# The G's spur: bar top on the circle's centre line, a square bar, the inner edge as drawn.
sp = by[spur_id][0]
# the inner edge is the leftmost point of the spur at each height below the bar
rows = {}
for x, y in sp[sp[:, 1] > 1180]:
    k = int(y // 6)
    rows[k] = min(rows.get(k, (1e9, 0)), (x, y))
inner_edge = np.array([rows[k] for k in sorted(rows)])
BAR_TOP, BAR_BOT, BAR_L, STEM_R = ocy, 1166.0, 1484.0, 1740.0
stem_l = inner_edge[0, 0]
spur = Polygon([(BAR_L, BAR_TOP), (STEM_R, BAR_TOP), (STEM_R, 2100), (inner_edge[-1, 0] - 200, 2100),
                *map(tuple, inner_edge[::-1]), (stem_l, BAR_BOT), (BAR_L, BAR_BOT)]).buffer(0)

# The owl: the head mirrored from its left half about the beak, the body as drawn.
owl = Polygon(by[owl_id][0], [h for h in by[owl_id][1:]]).buffer(0)
NECK = 1090.0
head_l = owl.intersection(box(0, 0, AXIS + 0.5, NECK + 30))
head = unary_union([head_l, affinity.scale(head_l, xfact=-1, origin=(AXIS, 0))])
body = owl.intersection(box(0, NECK, W, H))
owl = unary_union([head, body]).buffer(6).buffer(-6)
pl = Polygon(by[pupil_ids[0]][0])
pupils = unary_union([pl, affinity.scale(pl, xfact=-1, origin=(AXIS, 0))])

ink = wing
ink = unary_union([ink, spur, owl, pupils]).intersection(disk).buffer(0.5).buffer(-0.5)
if GAP:
    ink = ink.buffer(-GAP / 2).buffer(GAP / 2 - 0)
open(S + "/mark.wkb", "wb").write(wkb.dumps(ink))


red = raster(ink)
red.resize((W // 2, H // 2), Image.LANCZOS).point(lambda v: 255 - v).save(S + "/redraw.png")
sk = np.array(Image.open(S + "/wing-bw.png")) < 128
me = np.array(red) > 127
ov = np.full((H, W, 3), 255, np.uint8)
ov[sk & me] = (150, 150, 150)
ov[sk & ~me] = (220, 30, 30)
ov[~sk & me] = (30, 80, 220)
Image.fromarray(ov).resize((W // 2, H // 2), Image.LANCZOS).save(S + "/overlay.png")
print("sketch-only px", int((sk & ~me).sum()), "redraw-only px", int((~sk & me).sum()))

# small sizes: the mark square-centred on its bounding box
x0, y0, x1, y1 = ink.bounds
side = max(x1 - x0, y1 - y0) * 1.04
big = raster(ink, 1024 / side, (1024, 1024), (x0 + x1) / 2 - side / 2, (y0 + y1) / 2 - side / 2)
sizes = [96, 48, 32, 24]
SW = sum(sizes) + 10 * 5
sheet = Image.new("RGB", (SW * 2 + 20, 116), (239, 228, 208))
x = 10
for n in sizes:
    t = big.resize((n, n), Image.LANCZOS)
    for k, (fg, bg) in enumerate([((239, 228, 208), (36, 41, 42)), ((31, 24, 19), (239, 228, 208))]):
        tile = Image.composite(Image.new("RGB", (n, n), fg), Image.new("RGB", (n, n), bg), t)
        sheet.paste(tile, (x + k * (SW + 10), 10))
    x += n + 10
sheet.resize((sheet.width * 3, sheet.height * 3), Image.NEAREST).save(S + "/small.png")
print("bounds", [round(v) for v in ink.bounds])
