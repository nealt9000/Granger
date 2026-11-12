#!/usr/bin/env python3
"""Plan SQ Task 2.2 spike, offline: does a clustered (IVF) index keep dense recall while reading a small share of rows?

    ~/.venvs/granger-vectors/bin/python -I tools/vectors/ivf_spike.py VECTORS_DIR PHONE_QUERIES [--list 1000] [--small 4000]

VECTORS_DIR holds the .gvec files (the eval emulator's: ~/.cache/granger/emu-stage/files/vectors). PHONE_QUERIES is a
ResearchBenchmarkActivity `.out.json` (query vectors as the phone embeds them; mode `prior` is used, as hits.py does).

Ground truth is the phone's brute force: the query cut to the files' dims, renormalized and quantized, int8 dot products
times both scales (gvec.int8_scores). The index: each file of [--small] rows or more is clustered by k-means into lists of
about [--list] rows; smaller files are always scanned whole. A query scores every centroid of every file, reads the best P
lists across the library (plus the small files) and keeps the 24 best rows. Reported per P: recall@24 and @4 against brute
force, whether the brute-force best row is kept, and the share of rows read (a stand-in for the phone's reads from flash).
"""
import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import torch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gvec  # noqa: E402

K = 24
DEBUG = False


def load(vdir):
    """(name, int8 rows, scales) per file: gvec's layout, without decoding the keys (gvec.read does, slowly)."""
    import struct
    files = []
    for f in sorted(Path(vdir).glob("*.gvec")):
        data = np.memmap(f, np.uint8, "r")
        head = bytes(data[:gvec.HEADER])
        assert head[:4] == gvec.MAGIC, f
        _, _, dims, count = struct.unpack_from("<HHII", head, 4)
        q = np.frombuffer(data, np.int8, count * dims, gvec.HEADER).reshape(count, dims)
        scales = np.frombuffer(data, "<f4", count, gvec.HEADER + count * dims)
        files.append((f.name, q, np.asarray(scales, np.float32)))
    return files


def quantized_query(v, dims):
    v = np.asarray(v[:dims], np.float32)
    v = v / max(np.linalg.norm(v), 1e-12)
    scale = max(np.abs(v).max(), 1e-12) / 127.0
    return np.clip(np.round(v / scale), -127, 127).astype(np.float32), scale


def kmeans(x, k, iters=12, seed=0):
    """Spherical k-means on rows of x (float32 torch, normalized): centroids (k, d) and each row's list."""
    g = torch.Generator().manual_seed(seed)
    sample = x if len(x) <= 60_000 else x[torch.randperm(len(x), generator=g)[:60_000]]
    c = sample[torch.randperm(len(sample), generator=g)[:k]].clone()
    for _ in range(iters):
        a = (sample @ c.T).argmax(1)
        for j in range(k):
            m = sample[a == j]
            if len(m):
                c[j] = m.mean(0)
        c = torch.nn.functional.normalize(c, dim=1)
    assign = torch.cat([(x[i:i + 200_000] @ c.T).argmax(1) for i in range(0, len(x), 200_000)])
    return c, assign


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("vectors")
    ap.add_argument("queries")
    ap.add_argument("--list", type=int, default=1000)
    ap.add_argument("--small", type=int, default=4000)
    ap.add_argument("--probes", default="32,64,128,256,512,1024")
    a = ap.parse_args()
    torch.set_num_threads(8)

    t0 = time.time()
    files = load(a.vectors)
    dims = files[0][1].shape[1]
    total = sum(len(q) for _, q, _ in files)
    print(f"{len(files)} files, {total:,} rows, {dims} dims, loaded in {time.time() - t0:.0f}s", flush=True)

    qs = [r["vector"] for r in json.loads(Path(a.queries).read_text()) if r["mode"] == "prior"]
    Q = [quantized_query(v, dims) for v in qs]
    Qm = torch.tensor(np.stack([q for q, _ in Q]))
    Qs = torch.tensor([s for _, s in Q])
    print(f"{len(Q)} queries", flush=True)

    # Brute force, as the phone scores: int8 dot × row scale × query scale. Keep each query's 24 best (file, row).
    best_s = torch.full((len(Q), K), -1e9)
    best_id = torch.zeros((len(Q), K, 2), dtype=torch.long)
    # The index, built per file as we go.
    cents, cfile, clist = [], [], []          # every centroid, its file index, its list number
    lists = []                                # per file: (assign tensor or None)
    t0 = time.time()
    for fi, (name, q, scales) in enumerate(files):
        x8 = torch.tensor(q.astype(np.float32))
        s = torch.tensor(scales)
        scores = torch.nan_to_num((x8 @ Qm.T) * s[:, None] * Qs[None, :], nan=-1e9)  # rows × queries; NaN scales (empty chunks) last
        top = scores.topk(min(K, len(q)), dim=0)
        cat_s = torch.cat([best_s, top.values.T], 1)
        cat_id = torch.cat([best_id, torch.stack([torch.full_like(top.indices.T, fi), top.indices.T], -1)], 1)
        keep = cat_s.topk(K, dim=1)
        best_s = keep.values
        best_id = torch.gather(cat_id, 1, keep.indices[..., None].expand(-1, -1, 2))
        if len(q) >= a.small:
            xn = torch.nn.functional.normalize(x8, dim=1)
            c, assign = kmeans(xn, max(2, len(q) // a.list))
            lists.append(assign)
            cents.append(c)
            cfile += [fi] * len(c)
            clist += list(range(len(c)))
        else:
            lists.append(None)
        if fi % 50 == 0:
            print(f"  {fi}/{len(files)} files, {time.time() - t0:.0f}s", flush=True)
    C = torch.cat(cents)
    cfile = torch.tensor(cfile)
    clist = torch.tensor(clist)
    small_rows = sum(len(files[i][1]) for i in range(len(files)) if lists[i] is None)
    print(f"index: {len(C):,} lists over {sum(1 for l in lists if l is not None)} files; "
          f"{small_rows:,} rows in small files ({100 * small_rows / total:.1f}%) always read; built in {time.time() - t0:.0f}s\n")

    truth = [{(int(f), int(r)) for f, r in best_id[i].tolist()} for i in range(len(Q))]
    truth4 = [{tuple(x) for x in best_id[i, :4].tolist()} for i in range(len(Q))]
    first = [tuple(best_id[i, 0].tolist()) for i in range(len(Q))]
    cscore = torch.nn.functional.normalize(Qm, dim=1) @ C.T          # queries × centroids

    print("| Lists probed | Rows read | Recall@24 | Recall@4 | Best kept |")
    print("|---|---|---|---|---|")
    for P in [int(p) for p in a.probes.split(",")]:
        r24 = r4 = kept = read = 0.0
        for i in range(len(Q)):
            chosen = cscore[i].topk(min(P, len(C))).indices
            want = {}
            for ci in chosen.tolist():
                want.setdefault(int(cfile[ci]), set()).add(int(clist[ci]))
            got = []
            n = small_rows
            for fi, (name, q, scales) in enumerate(files):
                if lists[fi] is None:
                    rows = torch.arange(len(q))
                elif fi in want:
                    mask = torch.isin(lists[fi], torch.tensor(sorted(want[fi])))
                    rows = mask.nonzero().flatten()
                    n += len(rows)
                else:
                    continue
                if len(rows) == 0:
                    continue
                x8 = torch.tensor(q[rows.numpy()].astype(np.float32))
                sc = torch.nan_to_num((x8 @ Qm[i]) * torch.tensor(scales[rows.numpy()]) * Qs[i], nan=-1e9)
                t = sc.topk(min(K, len(rows)))
                got += [(float(v), fi, int(rows[j])) for v, j in zip(t.values.tolist(), t.indices.tolist())]
            got = sorted(got, reverse=True)[:K]
            if i == 0 and DEBUG:
                print("truth", sorted(truth[i])[:5], "first", first[i], "got", got[:3], flush=True)
            ids = {(f, r) for _, f, r in got}
            r24 += len(ids & truth[i]) / K
            r4 += len({(f, r) for _, f, r in got[:4]} & truth4[i]) / 4
            kept += first[i] in ids
            read += n / total
        m = len(Q)
        print(f"| {P} | {100 * read / m:.1f}% | {100 * r24 / m:.1f}% | {100 * r4 / m:.1f}% | {100 * kept / m:.0f}% |", flush=True)


if __name__ == "__main__":
    main()
