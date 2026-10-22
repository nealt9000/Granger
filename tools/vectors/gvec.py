"""Vector math and the vector file format (Plan R1 Tasks 3–4).

Vectors are truncated to `dims` (EmbeddingGemma is Matryoshka-trained), renormalized, and stored as int8 with one float32
scale each: v ≈ q * scale, scale = max|v| / 127. The phone quantizes the query the same way.
"""
import numpy as np


def truncate(vecs, dims):
    v = np.asarray(vecs, dtype=np.float32)[:, :dims]
    return v / np.maximum(np.linalg.norm(v, axis=1, keepdims=True), 1e-12)


def quantize(vecs):
    v = np.asarray(vecs, dtype=np.float32)
    scale = np.maximum(np.abs(v).max(axis=1), 1e-12) / 127.0
    q = np.clip(np.rint(v / scale[:, None]), -127, 127).astype(np.int8)
    return q, scale.astype(np.float32)


def int8_scores(query, q, scale):
    """Scores as the phone computes them: int8 · int8 summed in int32, times both scales."""
    qq, qs = quantize(query[None, :])
    dots = q.astype(np.int32) @ qq[0].astype(np.int32)
    return dots.astype(np.float32) * scale * qs[0]
