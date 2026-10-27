"""Fit a circle to each side of each cut between the crescent and the bands, using the edge within 380 px of the rim."""
import sys, numpy as np
from scipy import optimize
from shapely.geometry import Polygon, Point
S = sys.argv[1]
src = open(__file__.rsplit('/', 1)[0] + '/construct.py').read().split('# The crescent and the wing')[0]
sys.argv = ['x', S]; exec(src)


def fitc(p):
    f = lambda q: np.hypot(p[:, 0] - q[0], p[:, 1] - q[1]) - q[2]
    m = p.mean(0); best = None
    for dx, dy in [(0, 1), (0, -1), (1, 0), (-1, 0), (.7, .7), (-.7, .7), (.7, -.7), (-.7, -.7)]:
        r = optimize.least_squares(f, [m[0] + dx * 1500, m[1] + dy * 1500, 1500])
        e = np.abs(f(r.x))
        if best is None or e.mean() < best[1]: best = (r.x, e.mean(), e.max())
    return best


pieces = [crescent_id] + band_ids
edges = {}
for k, (a, b) in enumerate(zip(pieces, pieces[1:])):
    A, B = by[a][0], by[b][0]
    pa, pb = Polygon(B).buffer(0), Polygon(A).buffer(0)
    facing = [np.array([q for q in pts if other.exterior.distance(Point(q)) < 80]) for pts, other in [(A, pa), (B, pb)]]
    both = np.vstack(facing)
    mouth = both[np.argmax(np.hypot(*(both - oc).T))]  # where the cut reaches the rim
    for f0, key in zip(facing, [(a, 'low'), (b, 'up')]):
        rr = np.hypot(*(f0 - oc).T)
        f = f0[(orr - rr > 40) & (np.hypot(*(f0 - mouth).T) < 320)]
        c = fitc(f); edges[key] = c[0]
        print(k, key[1], len(f), np.round(c[0]), f'err {c[1]:.1f}/{c[2]:.1f}')
# the crescent's tip: its inner edge where it turns out to meet the rim
p = by[crescent_id][0]
rr = np.hypot(*(p - oc).T)
nn = normals(p)
inward = ((p - oc) / rr[:, None] * nn).sum(1) < -0.3
f = p[(p[:, 0] > 1150) & (p[:, 1] < 660) & inward & (orr - rr > 25)]
c = fitc(f); edges['tip'] = c[0]; print('tip', len(f), np.round(c[0]), f'err {c[1]:.1f}/{c[2]:.1f}')
np.save(S + '/edges.npy', edges, allow_pickle=True)
