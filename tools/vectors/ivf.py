#!/usr/bin/env python3
"""Plan SQ Task 2.2: a clustered (IVF) index inside the vector file, so Search reads a few lists, not every row.

    ~/.venvs/granger-vectors/bin/python -I tools/vectors/ivf.py convert IN.gvec OUT.gvec [--list 250] [--small 500]
    ~/.venvs/granger-vectors/bin/python -I tools/vectors/ivf.py convert-dir IN_DIR OUT_DIR [--list 250] [--small 500]
    ~/.venvs/granger-vectors/bin/python -I tools/vectors/ivf.py check OUT.gvec

The vector file, version 2: version 1's layout (gvec.py) with the rows reordered so each list's rows are contiguous
(hashes and keys reordered with them: a row is its position), the header's u64 at offset 88 giving the lists section's
offset, and the lists section after the key blob:

    u32 n lists
    n × dims int8 centroids, then n × f32 centroid scales (quantized as rows are)
    (n + 1) × u32 first row of each list, the last = count

A file under [--small] rows is copied unchanged (version 1): the phone reads it whole. Clustering is spherical k-means
over the dequantized, normalized rows, into about count / [--list] lists (ivf_spike.py: lists of 250 with files under 500
rows whole keep 96% of brute force's top 24 reading 6% of rows, over the eval library's 5.2 million rows).
Empty chunks' all-zero rows (written with a NaN scale by older builds) are kept but given scale 0.
"""
import argparse
import shutil
import struct
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gvec  # noqa: E402

VERSION = 2
LISTS_AT = 88


def read_raw(path):
    """(header bytes, dims, count, q, scales, hash bytes, keys table + blob bytes) of a version 1 file."""
    data = Path(path).read_bytes()
    assert data[:4] == gvec.MAGIC, path
    version, _, dims, count = struct.unpack_from("<HHII", data, 4)
    assert version == 1, f"{path}: version {version}"
    o = gvec.HEADER
    q = np.frombuffer(data, np.int8, count * dims, o).reshape(count, dims); o += count * dims
    scales = np.frombuffer(data, "<f4", count, o).copy(); o += 4 * count
    hashes = data[o:o + 8 * count]; o += 8 * count
    (keys_at,) = struct.unpack_from("<Q", data, 80)
    assert keys_at == o, path
    offsets = np.frombuffer(data, "<u4", count, keys_at)
    blob_at = keys_at + 4 * count + 4
    (blob_len,) = struct.unpack_from("<I", data, keys_at + 4 * count)
    blob = data[blob_at:blob_at + blob_len]
    return data[:gvec.HEADER], dims, count, q, scales, hashes, offsets, blob


def kmeans(x, k, iters=12, seed=0):
    import torch
    torch.set_num_threads(8)
    x = torch.tensor(x)
    g = torch.Generator().manual_seed(seed)
    sample = x if len(x) <= 80_000 else x[torch.randperm(len(x), generator=g)[:80_000]]
    c = sample[torch.randperm(len(sample), generator=g)[:k]].clone()
    for _ in range(iters):
        a = (sample @ c.T).argmax(1)
        sums = torch.zeros_like(c).index_add_(0, a, sample)
        counts = torch.bincount(a, minlength=k)
        keep = counts > 0
        c[keep] = sums[keep] / counts[keep, None]
        c = torch.nn.functional.normalize(c, dim=1)
    assign = torch.cat([(x[i:i + 200_000] @ c.T).argmax(1) for i in range(0, len(x), 200_000)])
    return c.numpy(), assign.numpy()


def convert(src, dst, list_size, small):
    head, dims, count, q, scales, hashes, offsets, blob = read_raw(src)
    if count < small:
        shutil.copyfile(src, dst)
        return count, 0
    scales = np.where(np.isfinite(scales), scales, 0.0).astype(np.float32)
    x = q.astype(np.float32)
    x /= np.maximum(np.linalg.norm(x, axis=1, keepdims=True), 1e-12)
    n = max(2, count // list_size)
    cents, assign = kmeans(x, n)
    order = np.argsort(assign, kind="stable")
    starts = np.searchsorted(assign[order], np.arange(n + 1)).astype("<u4")
    # Keys: each row's (ordinal, key) record moves with its row; the blob is rebuilt in the new order.
    records = [blob[offsets[i]:offsets[i + 1]] if i + 1 < count else blob[offsets[i]:] for i in range(count)]
    new_blob, new_offsets = bytearray(), []
    for i in order:
        new_offsets.append(len(new_blob))
        new_blob += records[i]
    cq, cs = gvec.quantize(cents)
    body = q[order].tobytes() + scales[order].astype("<f4").tobytes() + b"".join(hashes[8 * i:8 * i + 8] for i in order)
    keys_at = gvec.HEADER + len(body)
    keys = np.asarray(new_offsets, "<u4").tobytes() + struct.pack("<I", len(new_blob)) + bytes(new_blob)
    lists_at = keys_at + len(keys)
    lists = struct.pack("<I", n) + cq.astype(np.int8).tobytes() + cs.astype("<f4").tobytes() + starts.tobytes()
    header = bytearray(head)
    struct.pack_into("<H", header, 4, VERSION)
    struct.pack_into("<Q", header, 80, keys_at)
    struct.pack_into("<Q", header, LISTS_AT, lists_at)
    with open(dst, "wb") as f:
        f.write(header)
        f.write(body)
        f.write(keys)
        f.write(lists)
    return count, n


def check(path):
    """Reads a version 2 file back and checks its lists cover every row once, in order."""
    data = Path(path).read_bytes()
    version, _, dims, count = struct.unpack_from("<HHII", data, 4)
    if version == 1:
        print(f"{path}: version 1, {count} rows (read whole)")
        return
    (lists_at,) = struct.unpack_from("<Q", data, LISTS_AT)
    (n,) = struct.unpack_from("<I", data, lists_at)
    starts = np.frombuffer(data, "<u4", n + 1, lists_at + 4 + n * dims + 4 * n)
    assert starts[0] == 0 and starts[-1] == count and np.all(np.diff(starts.astype(np.int64)) >= 0), path
    assert lists_at + 4 + n * (dims + 4) + 4 * (n + 1) == len(data), path
    print(f"{path}: version 2, {count} rows in {n} lists (mean {count / n:.0f}, max {int(np.diff(starts).max())})")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    for name in ("convert", "convert-dir"):
        c = sub.add_parser(name)
        c.add_argument("src")
        c.add_argument("dst")
        c.add_argument("--list", type=int, default=250)
        c.add_argument("--small", type=int, default=500)
    k = sub.add_parser("check")
    k.add_argument("path")
    a = ap.parse_args()
    if a.cmd == "check":
        check(a.path)
    elif a.cmd == "convert":
        print(convert(a.src, a.dst, a.list, a.small))
    else:
        out = Path(a.dst)
        out.mkdir(parents=True, exist_ok=True)
        rows = lists = 0
        for f in sorted(Path(a.src).glob("*.gvec")):
            r, n = convert(f, out / f.name, a.list, a.small)
            rows += r
            lists += n
        print(f"{rows:,} rows, {lists:,} lists -> {out}")


if __name__ == "__main__":
    main()
