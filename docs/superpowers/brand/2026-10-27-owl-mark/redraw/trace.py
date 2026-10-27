"""Step 0: threshold the sketch at 4x, trace it with potrace, label its parts and fit the rim and inner circles.

    python -I trace.py <workdir> <sketch.png>     then smooth.py, fit_edges.py, construct.py, export.py (each takes <workdir>)
"""
import subprocess, sys
import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage, optimize

S, src = sys.argv[1:3]
im = Image.open(src).convert("L")
im = im.resize((im.width * 4, im.height * 4), Image.LANCZOS).filter(ImageFilter.GaussianBlur(5))
im.point(lambda v: 0 if v < 120 else 255).convert("1").save(S + "/wing-bw.pbm")
im.point(lambda v: 0 if v < 120 else 255).save(S + "/wing-bw.png")
W, H = im.size
subprocess.run(["/opt/homebrew/bin/potrace", S + "/wing-bw.pbm", "-b", "pgm", "-o", S + "/wing-trace.pgm",
                "-t", "40", "-a", "1.2", "-O", "0.4", "-W", str(W), "-H", str(H)], check=True)
a = np.array(Image.open(S + "/wing-trace.pgm").convert("L")) < 128
lab, n = ndimage.label(a)
np.save(S + "/lab.npy", lab)
rng = np.random.default_rng(1)


def boundary(mask):
    ys, xs = np.where(mask & ~ndimage.binary_erosion(mask))
    return np.c_[xs, ys].astype(float)


def fit(p, q0):
    f = lambda q: np.hypot(p[:, 0] - q[0], p[:, 1] - q[1]) - q[2]
    return optimize.least_squares(f, q0, loss="soft_l1", f_scale=3).x


def ransac(p, tol=6):
    best = None
    for _ in range(3000):
        s = p[rng.choice(len(p), 3, replace=False)]
        A = np.c_[2 * s[:, 0], 2 * s[:, 1], np.ones(3)]
        try:
            cx, cy, c = np.linalg.solve(A, (s ** 2).sum(1))
        except np.linalg.LinAlgError:
            continue
        r = np.sqrt(c + cx * cx + cy * cy)
        inl = np.abs(np.hypot(p[:, 0] - cx, p[:, 1] - cy) - r) < tol
        if 200 < r < 2000 and (best is None or inl.sum() > best[1].sum()):
            best = ((cx, cy, r), inl)
    return fit(p[best[1]], best[0])


B = boundary(a)
outer = ransac(B)
crescent = lab == lab[300, 1000]
Bc = boundary(crescent)
Bc = Bc[np.abs(np.hypot(Bc[:, 0] - outer[0], Bc[:, 1] - outer[1]) - outer[2]) > 10]
inner = ransac(Bc)
# refit the rim to the whole outline (crescent and bands), leaving out the G's mouth and the spur's straight edge
ys, xs = np.where(a)
cx, cy = xs.mean(), ys.mean()
ang, rad = np.arctan2(ys - cy, xs - cx), np.hypot(xs - cx, ys - cy)
idx = np.digitize(ang, np.linspace(-np.pi, np.pi, 361))
pts = np.array([(xs[k], ys[k]) for b in range(1, 361) if (idx == b).any() for k in [np.argmax(np.where(idx == b, rad, -1))]], float)
d = np.hypot(pts[:, 0] - outer[0], pts[:, 1] - outer[1])
th = np.degrees(np.arctan2(pts[:, 1] - outer[1], pts[:, 0] - outer[0]))
keep = (np.abs(d - outer[2]) < 60) & ~((th > -45) & (th < 35))
p = pts[keep]
outer = optimize.least_squares(lambda q: np.hypot(p[:, 0] - q[0], p[:, 1] - q[1]) - q[2], outer).x
np.save(S + "/circles.npy", np.array([outer, inner]))
print("parts", n, "rim", np.round(outer, 1), "inner", np.round(inner, 1))
