import base64
import datetime
import json

import pytest

import catalog_gen as cg

RFC_SEED = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
RFC_PUBLIC = bytes.fromhex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
RFC_EMPTY_SIG = bytes.fromhex(
    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
    "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
)

OPDS = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <entry>
    <id>urn:uuid:1f8cf2fe-e076-e018-2e09-004387350cba</id>
    <title>WikiMed Medical Encyclopedia</title>
    <updated>2026-10-10T00:00:00Z</updated>
    <summary>The largest medical encyclopedia, from Wikipedia</summary>
    <language>eng</language>
    <name>wikipedia_en_medicine</name>
    <flavour>nopic</flavour>
    <tags>medical;wikipedia;_pictures:no;_ftindex:yes</tags>
    <link rel="http://opds-spec.org/acquisition/open-access" type="application/x-zim"
          href="https://lb.download.kiwix.org/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim.meta4" length="1047708672" />
  </entry>
  <entry>
    <id>urn:uuid:f0902c7c-81ad-0490-6f91-fecc14810992</id>
    <title>WikiMed Medical Encyclopedia</title>
    <updated>2026-10-10T00:00:00Z</updated>
    <summary>The largest medical encyclopedia, from Wikipedia</summary>
    <language>eng</language>
    <name>wikipedia_en_medicine</name>
    <flavour>maxi</flavour>
    <tags>medical;wikipedia;_pictures:yes</tags>
    <link rel="http://opds-spec.org/acquisition/open-access" type="application/x-zim"
          href="https://lb.download.kiwix.org/zim/wikipedia/wikipedia_en_medicine_maxi_2026-10.zim.meta4" length="1864210432" />
  </entry>
</feed>"""

META4 = """<?xml version="1.0" encoding="UTF-8"?>
<metalink xmlns="urn:ietf:params:xml:ns:metalink">
  <file name="wikipedia_en_medicine_nopic_2026-10.zim">
    <size>1047708672</size>
    <hash type="md5">5c2bd306a75683d1c2bc5ff4858ed170</hash>
    <hash type="sha-256">74f9d80ad6183ede02e5c6f4a023676444c0c212889ed0dd9496f3738f72b976</hash>
    <url location="us" priority="2">https://ftpmirror.your.org/pub/kiwix/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim</url>
    <url location="us" priority="1">https://ny.mirror.driftle.ss/kiwix/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim</url>
    <url location="us" priority="5">https://dumps.wikimedia.org/kiwix/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim</url>
    <url location="de" priority="6">http://insecure.example/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim</url>
  </file>
</metalink>"""

MODEL = {
    "id": "gemma-4-e2b-gpu", "title": "Gemma 4 E2B", "role": "small", "fileName": "gemma-4-E2B-it-gpu.litertlm",
    "sizeBytes": 2008432640, "minRamGb": 6,
    "sha256": "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5",
    "url": "https://huggingface.co/x/resolve/abc/gemma-4-E2B-it-gpu.litertlm", "license": "Apache-2.0",
    "evalPassed": False,
}


def fake_fetch(routes):
    def fetch(url):
        for prefix, body in routes.items():
            if url.startswith(prefix):
                return body.encode()
        raise AssertionError(f"unexpected fetch {url}")
    return fetch


ROUTES = {
    "https://library.kiwix.org/catalog/v2/entries?name=wikipedia_en_medicine": OPDS,
    "https://lb.download.kiwix.org/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim.meta4": META4,
}
CURATED_MED = {"name": "wikipedia_en_medicine", "flavour": "nopic", "category": "Medical", "license": "CC BY-SA 4.0"}


def test_rfc8032_vector():
    assert cg.public_key(RFC_SEED) == RFC_PUBLIC
    assert cg.sign_bytes(RFC_SEED, b"") == RFC_EMPTY_SIG


def test_opds_entries():
    entries = cg.opds_entries(OPDS.encode())
    assert [(e["name"], e["flavour"]) for e in entries] == [
        ("wikipedia_en_medicine", "nopic"), ("wikipedia_en_medicine", "maxi")]
    assert entries[0]["uuid"] == "1f8cf2fe-e076-e018-2e09-004387350cba"
    assert entries[0]["length"] == 1047708672
    assert "_ftindex:yes" in entries[0]["tags"]


def test_metalink_sorts_mirrors_by_priority():
    m = cg.metalink(META4.encode())
    assert m["size"] == 1047708672
    assert m["sha256"] == "74f9d80ad6183ede02e5c6f4a023676444c0c212889ed0dd9496f3738f72b976"
    assert m["mirrors"][0].startswith("https://ny.mirror.driftle.ss/")


def test_pack_entry_picks_the_flavour_and_builds_urls():
    p = cg.pack_entry(CURATED_MED, fake_fetch(ROUTES))
    assert p["id"] == "wikipedia_en_medicine_nopic"
    assert p["zimName"] == "wikipedia_en_medicine"
    assert p["zimUuid"] == "1f8cf2fe-e076-e018-2e09-004387350cba"
    assert p["snapshotDate"] == "2026-10-10"
    assert p["hasFulltextIndex"] is True
    assert p["category"] == "Medical" and p["license"] == "CC BY-SA 4.0"
    # The canonical Kiwix URL first (it redirects to a nearby mirror), then stable https mirrors only.
    assert p["urls"] == [
        "https://download.kiwix.org/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim",
        "https://ftpmirror.your.org/pub/kiwix/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim",
        "https://dumps.wikimedia.org/kiwix/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim",
    ]


def test_maxi_without_ftindex_tag():
    meta4_maxi = META4.replace("nopic", "maxi").replace("1047708672", "1864210432")
    routes = {**ROUTES, "https://lb.download.kiwix.org/zim/wikipedia/wikipedia_en_medicine_maxi": meta4_maxi}
    p = cg.pack_entry({**CURATED_MED, "flavour": "maxi"}, fake_fetch(routes))
    assert p["id"] == "wikipedia_en_medicine_maxi"
    assert p["hasFulltextIndex"] is False


def test_missing_flavour_fails():
    with pytest.raises(SystemExit):
        cg.pack_entry({**CURATED_MED, "flavour": "mini"}, fake_fetch(ROUTES))


def test_metalink_size_is_exact():
    # Seen live: OPDS 1047708672 (rounded to KiB) vs metalink 1047708415.
    routes = {**ROUTES, ROUTES_META4_KEY: META4.replace("<size>1047708672", "<size>1047708415")}
    assert cg.pack_entry(CURATED_MED, fake_fetch(routes))["sizeBytes"] == 1047708415


def test_size_disagreement_fails():
    routes = {**ROUTES, ROUTES_META4_KEY: META4.replace("<size>1047708672", "<size>5")}
    with pytest.raises(SystemExit):
        cg.pack_entry(CURATED_MED, fake_fetch(routes))


ROUTES_META4_KEY = "https://lb.download.kiwix.org/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim.meta4"


def test_build_checks_presets_and_ids():
    curated = {"packs": [CURATED_MED], "models": [MODEL],
               "presets": [{"id": "starter", "title": "Starter", "items": ["gemma-4-e2b-gpu", "wikipedia_en_medicine_nopic"]}]}
    now = datetime.datetime(2026, 10, 16, 12, 0, tzinfo=datetime.timezone.utc)
    catalog = cg.build(curated, fake_fetch(ROUTES), now)
    assert catalog["formatVersion"] == 1
    assert catalog["generatedAt"] == "2026-10-16T12:00:00Z"
    assert [p["id"] for p in catalog["packs"]] == ["wikipedia_en_medicine_nopic"]
    assert catalog["models"] == [MODEL]

    bad = {**curated, "presets": [{"id": "starter", "title": "Starter", "items": ["nope"]}]}
    with pytest.raises(SystemExit):
        cg.build(bad, fake_fetch(ROUTES), now)


def test_build_rejects_unsafe_ids():
    curated = {"packs": [], "models": [{**MODEL, "fileName": "../evil.litertlm"}], "presets": []}
    with pytest.raises(SystemExit):
        cg.build(curated, fake_fetch(ROUTES), datetime.datetime.now(datetime.timezone.utc))


def test_sign_and_verify_round_trip(tmp_path):
    catalog = tmp_path / "catalog.json"
    catalog.write_text('{"formatVersion": 1}\n')
    cg.sign_file(RFC_SEED, catalog)
    sig = (tmp_path / "catalog.json.sig").read_text()
    assert len(base64.b64decode(sig.strip())) == 64
    assert cg.verify_file(RFC_PUBLIC, catalog)

    catalog.write_text('{"formatVersion": 2}\n')
    assert not cg.verify_file(RFC_PUBLIC, catalog)


def test_keygen_writes_private_seed(tmp_path):
    key = tmp_path / "k.key"
    public_b64 = cg.keygen(key)
    seed = bytes.fromhex(key.read_text().strip())
    assert len(seed) == 32
    assert base64.b64decode(public_b64) == cg.public_key(seed)
    assert oct(key.stat().st_mode & 0o777) == "0o600"


DESCRIPTOR = {
    "id": "granger_classics_en", "description": "Scripture, philosophy, drama, libretti and novels to 1930",
    "language": "eng", "sizeBytes": 123,
    "sha256": "718fadad40b3491be3fa8f95181a948e4d726f97a79a14e0ef1b90c6b2b03625",
    "urls": ["https://github.com/nealt9000/Granger/releases/download/classics-2026-10-16/granger_classics_en_2026-10-16.zim"],
    "snapshotDate": "2026-10-16", "hasFulltextIndex": True,
    "zimUuid": "0b5c1f2e-0000-4000-8000-000000000001", "zimName": "granger_classics_en",
}
RELEASE_ROW = {"release": "rel.json", "category": "Literature & Drama", "license": "Public domain (US)",
               "title": "Granger Classics"}


def write_descriptor(path, **changes):
    path.write_text(json.dumps({**DESCRIPTOR, **changes}))


def test_release_pack_comes_from_its_descriptor(tmp_path):
    write_descriptor(tmp_path / "rel.json")
    entry = cg.release_entry(RELEASE_ROW, head=lambda url: 123, root=tmp_path)
    assert entry["id"] == "granger_classics_en" and entry["category"] == "Literature & Drama"
    assert entry["title"] == "Granger Classics" and entry["license"] == "Public domain (US)"
    assert set(entry) == set(cg.pack_entry(CURATED_MED, fake_fetch(ROUTES)))  # same keys as a Kiwix pack
    assert entry["topics"] == [] and entry["zimUuid"] == DESCRIPTOR["zimUuid"]


def test_release_size_mismatch_fails(tmp_path):
    write_descriptor(tmp_path / "rel.json")
    with pytest.raises(SystemExit, match="123"):
        cg.release_entry(RELEASE_ROW, head=lambda url: 999, root=tmp_path)


def test_release_with_non_https_url_fails(tmp_path):
    write_descriptor(tmp_path / "rel.json", urls=["http://example.com/x.zim"])
    with pytest.raises(SystemExit):
        cg.release_entry(RELEASE_ROW, head=lambda url: 123, root=tmp_path)


def test_build_mixes_kiwix_and_release_packs(tmp_path, monkeypatch):
    write_descriptor(tmp_path / "rel.json")
    monkeypatch.setattr(cg, "REPO_ROOT", tmp_path)
    curated = {"packs": [CURATED_MED, RELEASE_ROW], "models": [],
               "presets": [{"id": "starter", "title": "Starter", "items": ["granger_classics_en"]}]}
    catalog = cg.build(curated, fake_fetch(ROUTES), head=lambda url: 123)
    assert [p["id"] for p in catalog["packs"]] == ["wikipedia_en_medicine_nopic", "granger_classics_en"]
