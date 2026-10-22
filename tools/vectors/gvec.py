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


# The vector file, version 1. Little-endian throughout.
#
#   0    magic "GVEC"
#   4    u16 version (1), u16 chunker version
#   8    u32 dims, u32 count
#   16   32 bytes: sha256 of the embedder file
#   48   32 bytes: sha256 of the source file (ZIM or EPUB)
#   80   u64 offset of the keys table; then zeros to HEADER (128)
#   128  count × dims int8 vectors
#        count × f32 scales
#        count × 8-byte chunk text hashes (Chunkers.hash, as bytes)
#        keys table: count × u32 offsets into the key blob, then u32 blob length, then the blob:
#        per chunk, u32 ordinal, u16 key length, key UTF-8 (article path or chapter href)
MAGIC = b"GVEC"
VERSION = 1
HEADER = 128


def write(path, q, scales, hashes, keys, *, chunker_version, embedder_sha256, source_sha256):
    """Writes a vector file. keys: [(key, ordinal)], hashes: 16-hex strings, both in the order of q's rows."""
    import struct
    count, dims = q.shape
    assert len(scales) == len(hashes) == len(keys) == count
    blob, offsets = bytearray(), []
    for key, ordinal in keys:
        k = key.encode("utf-8")
        offsets.append(len(blob))
        blob += struct.pack("<IH", ordinal, len(k)) + k
    body = q.astype(np.int8).tobytes() + np.asarray(scales, dtype="<f4").tobytes() + b"".join(bytes.fromhex(h) for h in hashes)
    keys_offset = HEADER + len(body)
    header = MAGIC + struct.pack("<HHII", VERSION, chunker_version, dims, count) + bytes.fromhex(embedder_sha256) + \
        bytes.fromhex(source_sha256) + struct.pack("<Q", keys_offset)
    header += b"\0" * (HEADER - len(header))
    with open(path, "wb") as f:
        f.write(header)
        f.write(body)
        f.write(np.asarray(offsets, dtype="<u4").tobytes())
        f.write(struct.pack("<I", len(blob)))
        f.write(bytes(blob))


def read(path):
    """(header dict, q, scales, hashes, keys) from a vector file; for tests and checks."""
    import struct
    data = open(path, "rb").read()
    assert data[:4] == MAGIC
    version, chunker, dims, count = struct.unpack_from("<HHII", data, 4)
    (keys_offset,) = struct.unpack_from("<Q", data, 80)
    head = {"version": version, "chunkerVersion": chunker, "dims": dims, "count": count,
            "embedderSha256": data[16:48].hex(), "sourceSha256": data[48:80].hex()}
    o = HEADER
    q = np.frombuffer(data, np.int8, count * dims, o).reshape(count, dims); o += count * dims
    scales = np.frombuffer(data, "<f4", count, o); o += 4 * count
    hashes = [data[o + 8 * i:o + 8 * i + 8].hex() for i in range(count)]
    offsets = np.frombuffer(data, "<u4", count, keys_offset)
    blob = keys_offset + 4 * count + 4
    keys = []
    for off in offsets:
        ordinal, n = struct.unpack_from("<IH", data, blob + int(off))
        keys.append((data[blob + int(off) + 6:blob + int(off) + 6 + n].decode("utf-8"), ordinal))
    return head, q, scales, hashes, keys
