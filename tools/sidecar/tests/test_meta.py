import datetime
import json
import sqlite3
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import meta  # noqa: E402


def make(tmp_path, kind="gqid", name=None, source_bytes=b"zim"):
    source = tmp_path / "wikipedia_en_top_maxi_2026-09-14.zim"
    source.write_bytes(source_bytes)
    path = tmp_path / (name or f"wikipedia_en_top_maxi_2026-09-14.{kind}")
    db = sqlite3.connect(path)
    meta.write_meta(db, kind, 1, source, "tools/qindex/build.py", 3,
                    now=datetime.datetime(2026, 11, 11, tzinfo=datetime.timezone.utc))
    db.commit()
    db.close()
    return path, source


def test_round_trip_and_check(tmp_path):
    path, source = make(tmp_path)
    m = meta.read_meta(path)
    assert m["kind"] == "gqid" and m["schemaVersion"] == "1" and m["builderVersion"] == "3"
    assert m["sourceZim"] == source.name and m["sourceSha256"] == meta.sha256(source)
    assert m["builtAt"] == "2026-11-11T00:00:00Z"
    assert meta.check(path, source) == []


def test_check_finds_a_wrong_source_a_wrong_name_and_no_meta(tmp_path):
    path, source = make(tmp_path)
    source.write_bytes(b"another snapshot")
    assert "built from another file" in meta.check(path, source)[0]
    misnamed, _ = make(tmp_path, name="x.glink")
    assert "its kind is gqid" in meta.check(misnamed)[0]
    bare = tmp_path / "bare.gqid"
    sqlite3.connect(bare).close()
    assert "no meta table" in meta.check(bare)[0]


def test_descriptor_has_the_catalog_keys(tmp_path):
    path, source = make(tmp_path)
    d = meta.descriptor(path, "wikipedia_en_top_maxi")
    pytest.importorskip("cryptography")   # catalog_gen signs; run this one in ~/.venvs/granger-catalog
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "catalog-gen"))
    import catalog_gen  # noqa: E402
    assert all(k in d for k in catalog_gen.SIDECAR_KEYS)
    assert d["sidecar"] == "gqid" and d["kind"] == "pack" and d["sourceSha256"] == meta.sha256(source)
    json.dumps(d)
