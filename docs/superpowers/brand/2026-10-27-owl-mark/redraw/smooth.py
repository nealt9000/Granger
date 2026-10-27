"""Step 1: corner-preserving smoothing of every traced contour. Writes smooth.npz (list of polygons) and smooth.png."""
import sys
import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage
from skimage import measure

S = sys.argv[1]
lab = np.load(S + "/lab.npy")
SIGMA = float(sys.argv[2]) if len(sys.argv) > 2 else 6  # in samples of STEP px
STEP = 2.0
CORNER_DEG = 55


def resample(p, step=STEP):
    p = np.vstack([p, p[:1]])
    d = np.r_[0, np.cumsum(np.hypot(*np.diff(p, axis=0).T))]
    t = np.arange(0, d[-1], step)
    return np.c_[np.interp(t, d, p[:, 0]), np.interp(t, d, p[:, 1])]


def corners(p, w=10):
    n = len(p)
    a = p[(np.arange(n) - w) % n] - p
    b = p[(np.arange(n) + w) % n] - p
    cos = (a * b).sum(1) / (np.hypot(*a.T) * np.hypot(*b.T))
    turn = 180 - np.degrees(np.arccos(np.clip(cos, -1, 1)))  # 0 = straight
    idx = [i for i in range(n) if turn[i] > CORNER_DEG and turn[i] == max(turn[(i + k) % n] for k in range(-w, w + 1))]
    return idx


def smooth_open(seg, sigma):
    """Gaussian smoothing with the two endpoints pinned (odd reflection)."""
    if len(seg) < 5:
        return seg
    pad = int(3 * sigma) + 1
    pad = min(pad, len(seg) - 1)
    a, b = seg[0], seg[-1]
    pre = 2 * a - seg[1:pad + 1][::-1]
    post = 2 * b - seg[-pad - 1:-1][::-1]
    ext = np.vstack([pre, seg, post])
    sm = np.c_[ndimage.gaussian_filter1d(ext[:, 0], sigma), ndimage.gaussian_filter1d(ext[:, 1], sigma)]
    out = sm[pad:pad + len(seg)]
    out[0], out[-1] = a, b
    return out


def smooth(p, sigma=SIGMA):
    p = resample(p)
    cs = corners(p)
    if not cs:
        return np.c_[ndimage.gaussian_filter1d(p[:, 0], sigma, mode="wrap"), ndimage.gaussian_filter1d(p[:, 1], sigma, mode="wrap")], []
    out = []
    for k, i in enumerate(cs):
        j = cs[(k + 1) % len(cs)]
        idx = np.arange(i, j + 1 if j > i else j + 1 + len(p)) % len(p)
        out.append(smooth_open(p[idx], sigma)[:-1])
    return np.vstack(out), [p[i] for i in cs]


polys = {}
for i in range(1, lab.max() + 1):
    m = np.pad(lab == i, 1)
    for c in measure.find_contours(m.astype(float), 0.5):
        if len(c) < 40:
            continue
        pts = c[:, ::-1] - 1  # (x, y)
        sm, cs = smooth(pts)
        polys.setdefault(i, []).append((sm, np.array(cs)))

np.save(S + "/smooth.npy", np.array([(i, sm, cs) for i, v in polys.items() for sm, cs in v], dtype=object), allow_pickle=True)

H, W = lab.shape
img = Image.fromarray(np.where(lab > 0, 200, 255).astype(np.uint8)).convert("RGB")
d = ImageDraw.Draw(img)
for i, v in polys.items():
    for sm, cs in v:
        d.line([tuple(q) for q in sm] + [tuple(sm[0])], fill=(0, 0, 0), width=3)
        for q in cs:
            d.ellipse([q[0] - 9, q[1] - 9, q[0] + 9, q[1] + 9], outline=(220, 0, 0), width=3)
img.resize((W // 2, H // 2), Image.LANCZOS).save(S + "/smooth.png")
print({i: [len(sm) for sm, _ in v] for i, v in polys.items()})
