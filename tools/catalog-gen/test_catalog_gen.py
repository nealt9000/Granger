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


def test_non_commercial_flag_passes_through():
    plain = cg.pack_entry(CURATED_MED, fake_fetch(ROUTES))
    assert "nonCommercial" not in plain
    flagged = cg.pack_entry({**CURATED_MED, "nonCommercial": True}, fake_fetch(ROUTES))
    assert flagged["nonCommercial"] is True


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


BOOKS = {"releaseDate": "2026-10-17", "collection": "granger-classics", "books": [
    {"id": f"gc-{slug}", "title": title, "author": "W. Shakespeare", "translator": None, "published": 1603,
     "section": "Drama", "language": "en", "sizeBytes": 10 + i, "sha256": "ab" * 32,
     "urls": [f"https://github.com/nealt9000/Granger/releases/download/classics-2026-10-17/gc-{slug}_2026-10-17.epub"]}
    for i, (slug, title) in enumerate([("hamlet", "Hamlet"), ("macbeth", "Macbeth")])]}
BOOK_ROW = {"bookRelease": "books.json", "license": "Public domain (US)"}


def test_book_release_adds_books_and_preset(tmp_path, monkeypatch):
    (tmp_path / "books.json").write_text(json.dumps(BOOKS))
    monkeypatch.setattr(cg, "REPO_ROOT", tmp_path)
    sizes = {b["urls"][0]: b["sizeBytes"] for b in BOOKS["books"]}
    curated = {"packs": [], "models": [], "books": [BOOK_ROW], "presets": []}
    catalog = cg.build(curated, fake_fetch(ROUTES), head=lambda url: sizes[url])
    hamlet = catalog["books"][0]
    assert hamlet == {"id": "gc-hamlet", "title": "Hamlet", "author": "W. Shakespeare", "description": "",
                      "category": "Drama", "language": "en", "sizeBytes": 10, "sha256": "ab" * 32,
                      "urls": BOOKS["books"][0]["urls"], "releaseDate": "2026-10-17",
                      "license": "Public domain (US)", "collection": "granger-classics", "published": 1603,
                      "domain": "literature"}
    assert [b["id"] for b in catalog["books"]] == ["gc-hamlet", "gc-macbeth"]
    assert {"id": "granger-classics", "title": "Granger Classics: all books",
            "items": ["gc-hamlet", "gc-macbeth"]} in catalog["presets"]


def test_book_size_mismatch_fails(tmp_path, monkeypatch):
    (tmp_path / "books.json").write_text(json.dumps(BOOKS))
    monkeypatch.setattr(cg, "REPO_ROOT", tmp_path)
    with pytest.raises(SystemExit, match="serves"):
        cg.build({"packs": [], "models": [], "books": [BOOK_ROW], "presets": []}, fake_fetch(ROUTES),
                 head=lambda url: 1)


def test_starter_preset_swaps_classics_zim_for_books():
    curated = json.loads((cg.REPO_ROOT / "tools/catalog-gen/curated.json").read_text())
    assert not any(p.get("release", "").endswith("granger_classics_en.json") for p in curated["packs"])
    assert any("bookRelease" in b for b in curated["books"])
    starter = next(p for p in curated["presets"] if p["id"] == "starter")
    assert "granger_classics_en" not in starter["items"]
    assert "@granger-classics" in starter["items"]


def test_collection_token_expands_to_book_ids_in_presets(tmp_path, monkeypatch):
    (tmp_path / "books.json").write_text(json.dumps(BOOKS))
    monkeypatch.setattr(cg, "REPO_ROOT", tmp_path)
    curated = {"packs": [], "models": [], "books": [BOOK_ROW],
               "presets": [{"id": "starter", "title": "Starter", "items": ["@granger-classics"]}]}
    catalog = cg.build(curated, fake_fetch(ROUTES), head=lambda url: 10 if "hamlet" in url else 11)
    starter = next(p for p in catalog["presets"] if p["id"] == "starter")
    assert starter["items"] == ["gc-hamlet", "gc-macbeth"]


def feed_entry(name, flavour="", language="eng", category="", updated="2026-10-01", title=None):
    return f"""
  <entry>
    <id>urn:uuid:{name}-{flavour}-{updated}</id>
    <title>{title or name}</title>
    <updated>{updated}T00:00:00Z</updated>
    <summary>About {name}</summary>
    <language>{language}</language>
    <name>{name}</name>
    <flavour>{flavour}</flavour>
    <category>{category}</category>
    <tags>_ftindex:yes</tags>
    <link rel="http://opds-spec.org/acquisition/open-access" type="application/x-zim"
          href="https://lb.download.kiwix.org/zim/x/{name}_{flavour}_{updated}.zim.meta4" length="1000" />
  </entry>"""


def feed(*entries):
    return '<?xml version="1.0" encoding="UTF-8"?>\n<feed xmlns="http://www.w3.org/2005/Atom">' + "".join(entries) + "\n</feed>"


def meta4_for(url):
    name = url.rsplit("/", 1)[1].removesuffix(".meta4")
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<metalink xmlns="urn:ietf:params:xml:ns:metalink"><file name="{name}"><size>1000</size>
<hash type="sha-256">{"ab" * 32}</hash>
<url priority="1">https://ftpmirror.your.org/pub/kiwix/zim/x/{name}</url></file></metalink>"""


FAMILIES = [
    {"family": "wikipedia", "match": "^wikipedia_", "category": "Reference", "license": "CC BY-SA 4.0"},
    {"family": "stack_exchange", "match": {"kiwixCategory": "stack_exchange"}, "category": "Q&A",
     "license": "CC BY-SA 4.0"},
    {"family": "libretexts", "match": "^libretexts\\.org_", "category": "Science & Math", "license": "CC BY-NC-SA 4.0",
     "nonCommercial": True},
    {"family": "ted", "match": "^ted_", "category": "Other", "license": "CC BY-NC-ND 4.0", "hold": "video"},
    {"family": "devdocs", "match": "^devdocs_", "category": "Programming", "license": None},
]


def feed_fetch(xml, broken=()):
    def fetch(url):
        if url.startswith(cg.OPDS_ENTRIES + "?count=-1"):
            return xml.encode()
        if url.endswith(".meta4"):
            if any(b in url for b in broken):
                raise OSError("connection reset")
            return meta4_for(url).encode()
        raise AssertionError(f"unexpected fetch {url}")
    return fetch


def kiwix(xml, curated_keys=frozenset(), broken=()):
    return cg.kiwix_entries({"languages": ["eng"]}, FAMILIES, cg.kiwix_feed(feed_fetch(xml, broken)), set(curated_keys),
                            feed_fetch(xml, broken), workers=2, sleep=lambda s: None)


def test_kiwix_keeps_licensed_english_families():
    xml = feed(feed_entry("wikipedia_en_physics", "maxi"),
               feed_entry("wikipedia_fr_physique", "maxi", language="fra"),
               feed_entry("wikipedia_mul_x", "maxi", language="fra,eng"),
               feed_entry("tezos.stackexchange.com_en_all", category="stack_exchange"),
               feed_entry("libretexts.org_en_math"),
               feed_entry("ted_en_science"),
               feed_entry("devdocs_en_python"),
               feed_entry("xkcd.com_en_all"))
    packs, report = kiwix(xml)
    by_id = {p["id"]: p for p in packs}
    assert set(by_id) == {"wikipedia_en_physics_maxi", "wikipedia_mul_x_maxi", "tezos.stackexchange.com_en_all",
                          "libretexts.org_en_math"}
    assert by_id["tezos.stackexchange.com_en_all"]["category"] == "Q&A"
    assert by_id["tezos.stackexchange.com_en_all"]["family"] == "stack_exchange"
    assert by_id["libretexts.org_en_math"]["nonCommercial"] is True
    assert "nonCommercial" not in by_id["wikipedia_en_physics_maxi"]
    assert all(p["featured"] is False for p in packs)
    text = "\n".join(report)
    assert "Held back (video): 1\n  ted: 1" in text
    assert "No license found: 1\n  devdocs: 1" in text
    assert "No family-table row: 1\n  xkcd.com: 1" in text


def test_kiwix_takes_the_newest_snapshot_and_sorts():
    xml = feed(feed_entry("wikipedia_en_b", updated="2026-09-01", title="Beta"),
               feed_entry("wikipedia_en_b", updated="2026-10-01", title="Beta"),
               feed_entry("wikipedia_en_a", title="alpha"),
               feed_entry("serverfault.com_en_all", category="stack_exchange", title="Server Fault"))
    packs, _ = kiwix(xml)
    assert [p["id"] for p in packs] == ["serverfault.com_en_all", "wikipedia_en_a", "wikipedia_en_b"]
    assert packs[2]["snapshotDate"] == "2026-10-01"


def test_kiwix_skips_curated_packs():
    xml = feed(feed_entry("wikipedia_en_top", "nopic"), feed_entry("wikipedia_en_top", "maxi"))
    packs, _ = kiwix(xml, curated_keys={("wikipedia_en_top", "nopic")})
    assert [p["id"] for p in packs] == ["wikipedia_en_top_maxi"]


def test_kiwix_reports_a_pack_that_wont_pin():
    xml = feed(feed_entry("wikipedia_en_a"), feed_entry("wikipedia_en_b"))
    packs, report = kiwix(xml, broken=("wikipedia_en_b",))
    assert [p["id"] for p in packs] == ["wikipedia_en_a"]
    assert "Failed to pin: 1" in "\n".join(report)


def test_kiwix_skips_unsafe_ids():
    packs, report = kiwix(feed(feed_entry("wikipedia_en_a b")))
    assert packs == []
    assert "Unsafe ids: 1" in "\n".join(report)


def test_build_with_kiwix_features_the_curated_rows(tmp_path):
    (tmp_path / "families.json").write_text(json.dumps({"families": FAMILIES}))
    xml = feed(feed_entry("wikipedia_en_top", "nopic"), feed_entry("wikipedia_en_physics", "maxi"),
               feed_entry("ifixit_en_all"))
    curated = {"kiwix": {"languages": ["eng"], "families": "families.json"},
               "packs": [{"name": "wikipedia_en_top", "flavour": "nopic", "category": "Reference",
                          "license": "CC BY-SA 4.0", "title": "Wikipedia: best articles", "description": "Curated"},
                         {"name": "ifixit_en_all", "category": "How-to", "license": "CC BY-NC-SA 3.0",
                          "nonCommercial": True}],
               "models": [], "presets": []}
    report = []
    catalog = cg.build(curated, feed_fetch(xml), report=report, root=tmp_path)
    packs = catalog["packs"]
    assert [p["id"] for p in packs] == ["wikipedia_en_top_nopic", "ifixit_en_all", "wikipedia_en_physics_maxi"]
    top, ifixit, physics = packs
    assert top["featured"] and top["family"] == "wikipedia"
    assert top["title"] == "Wikipedia: best articles" and top["description"] == "Curated"
    # A curated pack needs no family-table row of its own.
    assert ifixit["featured"] and ifixit["family"] == "" and ifixit["nonCommercial"]
    assert not physics["featured"] and physics["description"] == "About wikipedia_en_physics"
    assert report and report[0].startswith("Kiwix packs in eng: 3; listed 1, curated 2")


def test_build_with_kiwix_fails_on_a_missing_curated_pack(tmp_path):
    (tmp_path / "families.json").write_text(json.dumps({"families": FAMILIES}))
    curated = {"kiwix": {"languages": ["eng"], "families": "families.json"},
               "packs": [{"name": "wikipedia_en_gone", "category": "Reference", "license": "CC BY-SA 4.0"}],
               "models": [], "presets": []}
    with pytest.raises(SystemExit, match="not in the Kiwix catalog"):
        cg.build(curated, feed_fetch(feed(feed_entry("wikipedia_en_a"))), root=tmp_path)


# --- Plan H1: domains and the readiness tiers ---

DOMAIN_FAMILIES = [
    {"family": "wikipedia", "match": "^wikipedia_en_medicine", "category": "Medical", "license": "CC BY-SA 4.0",
     "domain": "medicine"},
    {"family": "wikipedia", "match": "^wikipedia_", "category": "Reference", "license": "CC BY-SA 4.0",
     "domain": "reference"},
    {"family": "stack_exchange", "match": "^(cooking|gardening)\\.stackexchange\\.com_", "category": "Q&A",
     "license": "CC BY-SA 4.0", "domain": "food"},
    {"family": "stack_exchange", "match": {"kiwixCategory": "stack_exchange"}, "category": "Q&A",
     "license": "CC BY-SA 4.0", "domain": "technology"},
    {"family": "maps", "match": "^maps_", "category": "Maps", "license": "ODbL", "domain": "maps"},
    {"family": "xkcd.com", "match": "^xkcd\\.com_", "category": "Literature & Drama", "license": "CC BY-NC 2.5",
     "nonCommercial": True, "domain": None},
]


def domain_kiwix(xml):
    return cg.kiwix_entries({"languages": ["eng"]}, DOMAIN_FAMILIES, cg.kiwix_feed(feed_fetch(xml)), set(),
                            feed_fetch(xml), workers=2, sleep=lambda s: None)[0]


def test_pin_carries_the_rows_domain():
    packs = domain_kiwix(feed(feed_entry("wikipedia_en_physics", "maxi"), feed_entry("xkcd.com_en_all")))
    by_id = {p["id"]: p for p in packs}
    assert by_id["wikipedia_en_physics_maxi"]["domain"] == "reference"
    # A null domain is written blank: the pack counts in storage only.
    assert by_id["xkcd.com_en_all"]["domain"] == ""


def test_family_sub_rows_split_a_family_across_domains():
    packs = domain_kiwix(feed(feed_entry("wikipedia_en_medicine", "nopic"), feed_entry("wikipedia_en_top", "nopic")))
    by_id = {p["id"]: p for p in packs}
    assert by_id["wikipedia_en_medicine_nopic"]["domain"] == "medicine"
    assert by_id["wikipedia_en_top_nopic"]["domain"] == "reference"
    assert {p["family"] for p in packs} == {"wikipedia"}


def test_stack_exchange_sites_split_by_their_own_rows():
    xml = feed(feed_entry("cooking.stackexchange.com_en_all", category="stack_exchange"),
               feed_entry("python.stackexchange.com_en_all", category="stack_exchange"))
    packs = domain_kiwix(xml)
    by_id = {p["id"]: p for p in packs}
    assert by_id["cooking.stackexchange.com_en_all"]["domain"] == "food"
    assert by_id["python.stackexchange.com_en_all"]["domain"] == "technology"
    assert {p["family"] for p in packs} == {"stack_exchange"}


THOUGHT_BOOKS = {"releaseDate": "2026-10-17", "collection": "granger-classics", "books": [
    {"id": f"gc-{slug}", "title": title, "author": "", "translator": None, "published": 1600,
     "section": section, "language": "en", "sizeBytes": 10 + i, "sha256": "ab" * 32,
     "urls": [f"https://github.com/nealt9000/Granger/releases/download/classics-2026-10-17/gc-{slug}_2026-10-17.epub"]}
    for i, (slug, title, section) in enumerate([("bible", "Bible", "Scripture & religion"),
                                                 ("republic", "Republic", "Philosophy"),
                                                 ("hamlet", "Hamlet", "Drama"),
                                                 ("iliad", "Iliad", "Epic & poetry")])]}

READINESS = {"domains": [
    {"id": "medicine", "title": "Medicine & first aid",
     "essential": ["wikipedia_en_medicine_nopic"], "comprehensive": ["wikipedia_en_medicine_nopic"]},
    {"id": "maps", "title": "Maps, places & travel", "essential": ["family:maps"], "comprehensive": []},
    {"id": "reference", "title": "General reference", "essential": ["wikipedia_en_top_nopic"], "comprehensive": []},
    {"id": "literature", "title": "Literature & the arts", "essential": ["@granger-classics"], "comprehensive": []},
    {"id": "thought", "title": "Religion, philosophy & history", "essential": ["@granger-classics"],
     "comprehensive": ["wikipedia_en_top_nopic"]},
]}


def readiness_build(tmp_path, monkeypatch, readiness=READINESS, families=DOMAIN_FAMILIES, books=THOUGHT_BOOKS, onboarding=None):
    (tmp_path / "families.json").write_text(json.dumps({"families": families}))
    (tmp_path / "books.json").write_text(json.dumps(books))
    monkeypatch.setattr(cg, "REPO_ROOT", tmp_path)
    xml = feed(feed_entry("wikipedia_en_medicine", "nopic"), feed_entry("wikipedia_en_top", "nopic"),
               feed_entry("maps_en_france"), feed_entry("maps_en_spain"))
    curated = {"kiwix": {"languages": ["eng"], "families": "families.json"}, "packs": [], "models": [],
               "books": [BOOK_ROW], "presets": [], "readiness": readiness}
    if onboarding is not None:
        curated["onboarding"] = onboarding
    sizes = {b["urls"][0]: b["sizeBytes"] for b in books["books"]}
    return cg.build(curated, feed_fetch(xml), head=lambda url: sizes[url], root=tmp_path)


def test_books_take_their_domain_from_their_category(tmp_path, monkeypatch):
    catalog = readiness_build(tmp_path, monkeypatch)
    domains = {b["id"]: b["domain"] for b in catalog["books"]}
    assert domains == {"gc-bible": "thought", "gc-republic": "thought", "gc-hamlet": "literature", "gc-iliad": "literature"}


def test_readiness_block_is_written_with_essential_folded_into_comprehensive(tmp_path, monkeypatch):
    catalog = readiness_build(tmp_path, monkeypatch)
    domains = {d["id"]: d for d in catalog["readiness"]["domains"]}
    assert [d["id"] for d in catalog["readiness"]["domains"]] == ["medicine", "maps", "reference", "literature", "thought"]
    assert domains["maps"]["comprehensive"] == ["family:maps"]
    assert domains["thought"]["comprehensive"] == ["@granger-classics", "wikipedia_en_top_nopic"]
    # Already there: not doubled.
    assert domains["medicine"]["comprehensive"] == ["wikipedia_en_medicine_nopic"]
    assert domains["literature"]["title"] == "Literature & the arts"


def test_readiness_rejects_an_unknown_token(tmp_path, monkeypatch):
    bad = {"domains": [{"id": "maps", "title": "Maps", "essential": ["maps_en_atlantis"], "comprehensive": []}]}
    with pytest.raises(SystemExit, match="maps_en_atlantis"):
        readiness_build(tmp_path, monkeypatch, readiness=bad)
    bad = {"domains": [{"id": "maps", "title": "Maps", "essential": ["family:atlas"], "comprehensive": []}]}
    with pytest.raises(SystemExit, match="family:atlas"):
        readiness_build(tmp_path, monkeypatch, readiness=bad)
    bad = {"domains": [{"id": "maps", "title": "Maps", "essential": ["@lost-books"], "comprehensive": []}]}
    with pytest.raises(SystemExit, match="@lost-books"):
        readiness_build(tmp_path, monkeypatch, readiness=bad)


def test_readiness_rejects_a_pack_with_an_unknown_domain(tmp_path, monkeypatch):
    families = [dict(DOMAIN_FAMILIES[1], domain="encyclopaedia")] + DOMAIN_FAMILIES[2:]
    with pytest.raises(SystemExit, match="encyclopaedia"):
        readiness_build(tmp_path, monkeypatch, families=families)


def test_readiness_rejects_duplicate_or_blank_domain_ids(tmp_path, monkeypatch):
    bad = {"domains": [{"id": "maps", "title": "Maps", "essential": [], "comprehensive": []},
                       {"id": "maps", "title": "Maps again", "essential": [], "comprehensive": []}]}
    with pytest.raises(SystemExit, match="maps"):
        readiness_build(tmp_path, monkeypatch, readiness=bad)


def test_efficient_and_comprehensive_presets_come_from_the_tiers(tmp_path, monkeypatch):
    catalog = readiness_build(tmp_path, monkeypatch)
    presets = {p["id"]: p for p in catalog["presets"]}
    # The Classics token expands to the domain's own books; family tokens are left out (H3 picks the region).
    assert presets["efficient"]["items"] == ["wikipedia_en_medicine_nopic", "wikipedia_en_top_nopic", "gc-hamlet", "gc-iliad",
                                             "gc-bible", "gc-republic"]
    assert presets["efficient"]["title"] == "Efficient start"
    # A pack listed in two domains' tiers appears once.
    assert presets["comprehensive"]["items"] == ["wikipedia_en_medicine_nopic", "wikipedia_en_top_nopic", "gc-hamlet",
                                                 "gc-iliad", "gc-bible", "gc-republic"]
    assert presets["comprehensive"]["title"] == "Comprehensive library"


def test_build_without_a_readiness_block_writes_none(tmp_path, monkeypatch):
    (tmp_path / "families.json").write_text(json.dumps({"families": DOMAIN_FAMILIES}))
    monkeypatch.setattr(cg, "REPO_ROOT", tmp_path)
    xml = feed(feed_entry("wikipedia_en_top", "nopic"))
    curated = {"kiwix": {"languages": ["eng"], "families": "families.json"}, "packs": [], "models": [], "presets": []}
    catalog = cg.build(curated, feed_fetch(xml), root=tmp_path)
    assert "readiness" not in catalog
    assert {p["id"] for p in catalog["presets"]} == set()


DOMAIN_IDS = ["medicine", "food", "making", "science", "technology", "maps", "reference", "literature", "thought"]


def test_real_family_table_has_a_domain_on_every_row():
    table = json.loads((cg.REPO_ROOT / "tools/catalog-gen/kiwix_families.json").read_text())
    rows = table["families"]
    for row in rows:
        assert "domain" in row, row["family"]
        assert row["domain"] is None or row["domain"] in DOMAIN_IDS, (row["family"], row["domain"])
    # The first matching row wins, so each family's catch-all comes after its sub-rows.
    for family in ("stack_exchange", "wikipedia", "libretexts.org"):
        same = [r for r in rows if r["family"] == family]
        assert len(same) > 1, family
        catch_all = same[-1]["match"]
        assert isinstance(catch_all, dict) or catch_all.rstrip("_") == f"^{family.replace('.', chr(92) + '.')}", catch_all


# --- Plan H3: the onboarding block ---

ONBOARDING = {"useCases": [
    {"id": "medical", "title": "Medical work", "blurb": "Clinical references.",
     "items": ["wikipedia_en_medicine_nopic", "wikipedia_en_medicine_nopic", "family:maps"]},
    {"id": "faith", "title": "Faith & philosophy", "items": ["@granger-classics", "wikipedia_en_top_nopic"]},
], "regions": {"larger": ["maps_en_france"], "covered": {"maps_en_france": ["Corsica", " Corsica ", ""]}}}


def test_onboarding_block_is_written_with_tokens_checked(tmp_path, monkeypatch):
    catalog = readiness_build(tmp_path, monkeypatch, onboarding=ONBOARDING)
    block = catalog["onboarding"]
    assert [u["id"] for u in block["useCases"]] == ["medical", "faith"]
    medical = block["useCases"][0]
    assert medical["items"] == ["wikipedia_en_medicine_nopic", "family:maps"]  # de-duplicated, order kept
    assert medical["blurb"] == "Clinical references."
    assert block["useCases"][1]["blurb"] == ""
    assert block["larger"] == ["maps_en_france"]
    assert block["covered"] == {"maps_en_france": ["Corsica"]}


def test_onboarding_rejects_bad_tokens_ids_and_areas(tmp_path, monkeypatch):
    bad = {"useCases": [{"id": "x", "title": "X", "items": ["nope"]}]}
    with pytest.raises(SystemExit, match="onboarding x: unknown item"):
        readiness_build(tmp_path, monkeypatch, onboarding=bad)
    bad = {"useCases": [{"id": "x", "title": "X", "items": ["family:atlas"]}]}
    with pytest.raises(SystemExit, match="unknown family"):
        readiness_build(tmp_path, monkeypatch, onboarding=bad)
    bad = {"useCases": [{"id": "x", "title": "X", "items": []}]}
    with pytest.raises(SystemExit, match="no items"):
        readiness_build(tmp_path, monkeypatch, onboarding=bad)
    bad = {"useCases": [{"id": "x", "title": "X", "items": ["wikipedia_en_top_nopic"]},
                        {"id": "x", "title": "Again", "items": ["wikipedia_en_top_nopic"]}]}
    with pytest.raises(SystemExit, match="duplicate use case"):
        readiness_build(tmp_path, monkeypatch, onboarding=bad)
    bad = {"useCases": [], "regions": {"larger": ["wikipedia_en_top_nopic"]}}
    with pytest.raises(SystemExit, match="not a maps pack"):
        readiness_build(tmp_path, monkeypatch, onboarding=bad)
    bad = {"useCases": [], "regions": {"covered": {"wikipedia_en_top_nopic": ["Nowhere"]}}}
    with pytest.raises(SystemExit, match="regions.covered"):
        readiness_build(tmp_path, monkeypatch, onboarding=bad)


def test_build_without_an_onboarding_block_writes_none(tmp_path, monkeypatch):
    assert "onboarding" not in readiness_build(tmp_path, monkeypatch)


def test_real_curated_onboarding_names_seven_use_cases_and_larger_areas():
    curated = json.loads((cg.REPO_ROOT / "tools/catalog-gen/curated.json").read_text())
    block = curated["onboarding"]
    assert [u["id"] for u in block["useCases"]] == ["offgrid", "travel", "study", "build", "medical", "software", "faith"]
    for u in block["useCases"]:
        assert u["title"] and u["blurb"] and u["items"], u["id"]
    assert len(block["regions"]["larger"]) >= 7
    assert all(p.startswith("maps_en_") for p in block["regions"]["larger"])
    assert "Puerto Rico" in block["regions"]["covered"]["maps_en_central-america"]


def test_real_curated_readiness_names_nine_domains_in_order():
    curated = json.loads((cg.REPO_ROOT / "tools/catalog-gen/curated.json").read_text())
    domains = curated["readiness"]["domains"]
    assert [d["id"] for d in domains] == DOMAIN_IDS
    for d in domains:
        assert d["title"] and d["essential"], d["id"]
