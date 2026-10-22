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


FIXTURE = Path(__file__).resolve().parents[3] / "core/vectors/src/test/resources/tiny.gvec"


def tiny():
    """The shared fixture, also read by core/vectors' VectorFileTest. Regenerate with `python -m pytest` after changes."""
    v = gvec.truncate(np.array([[1.0, 0.0, 0.0, 0.0], [0.6, 0.8, 0.0, 0.0], [0.0, 0.0, -1.0, 0.5]]), 4)
    q, s = gvec.quantize(v)
    keys = [("A/Knot", 0), ("A/Knot", 1), ("OEBPS/c001.xhtml", 7)]
    hashes = ["369fae5d28de7efc", "0000000000000001", "ffffffffffffffff"]
    return q, s, hashes, keys


def test_write_read_round_trip_and_fixture():
    q, s, hashes, keys = tiny()
    FIXTURE.parent.mkdir(parents=True, exist_ok=True)
    gvec.write(FIXTURE, q, s, hashes, keys, chunker_version=1, embedder_sha256="ab" * 32, source_sha256="cd" * 32)
    head, q2, s2, h2, k2 = gvec.read(FIXTURE)
    assert head == {"version": 1, "chunkerVersion": 1, "dims": 4, "count": 3, "embedderSha256": "ab" * 32, "sourceSha256": "cd" * 32}
    assert (q2 == q).all() and np.allclose(s2, s) and h2 == hashes and k2 == keys
