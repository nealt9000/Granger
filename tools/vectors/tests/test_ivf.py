import struct

import numpy as np

import gvec
import ivf


def write_file(path, n, dims=8, seed=0):
    rng = np.random.default_rng(seed)
    q, scales = gvec.quantize(gvec.truncate(rng.normal(size=(n, dims)), dims))
    hashes = ["%016x" % i for i in range(n)]
    keys = [("Article_%d" % i, i % 3) for i in range(n)]
    gvec.write(path, q, scales, hashes, keys, chunker_version=1, embedder_sha256="00" * 32, source_sha256="11" * 32)
    return q, scales, hashes, keys


def test_rows_keep_their_keys_and_hashes_and_lists_cover_every_row(tmp_path):
    src, dst = tmp_path / "a.gvec", tmp_path / "b.gvec"
    q, scales, hashes, keys = write_file(src, 600)
    count, n = ivf.convert(src, dst, list_size=50, small=100)
    assert (count, n) == (600, 12)
    ivf.check(dst)
    data = dst.read_bytes()
    version, _, dims, count = struct.unpack_from("<HHII", data, 4)
    assert version == 2
    new_q = np.frombuffer(data, np.int8, count * dims, gvec.HEADER).reshape(count, dims)
    new_hashes = [data[gvec.HEADER + count * dims + 4 * count + 8 * i:][:8].hex() for i in range(count)]
    (keys_at,) = struct.unpack_from("<Q", data, 80)
    offsets = np.frombuffer(data, "<u4", count, keys_at)
    blob = keys_at + 4 * count + 4
    for row in range(count):
        old = int(new_hashes[row], 16)  # hashes were each row's original index
        assert np.array_equal(new_q[row], q[old])
        ordinal, klen = struct.unpack_from("<IH", data, blob + int(offsets[row]))
        assert data[blob + int(offsets[row]) + 6:][:klen].decode() == keys[old][0]
        assert ordinal == keys[old][1]


def test_a_small_file_is_copied_unchanged(tmp_path):
    src, dst = tmp_path / "a.gvec", tmp_path / "b.gvec"
    write_file(src, 40)
    assert ivf.convert(src, dst, list_size=50, small=100) == (40, 0)
    assert dst.read_bytes() == src.read_bytes()
