import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import gvec  # noqa: E402


def test_truncate_renormalizes():
    v = gvec.truncate(np.array([[3.0, 4.0, 12.0]]), 2)
    assert np.allclose(v, [[0.6, 0.8]])


def test_int8_scores_track_float_scores():
    rng = np.random.default_rng(1)
    d = gvec.truncate(rng.normal(size=(500, 256)), 256)
    q = gvec.truncate(rng.normal(size=(1, 256)), 256)[0]
    s8 = gvec.int8_scores(q, *gvec.quantize(d))
    assert np.corrcoef(s8, d @ q)[0, 1] > 0.99
    assert np.argmax(s8) == np.argmax(d @ q)
