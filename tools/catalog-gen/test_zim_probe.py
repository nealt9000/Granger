"""zim_probe.py (Plan KB): a ZIM's metadata and entries by range reads, and pack_contents.json from a catalog."""
import http.server
import json
import pathlib
import threading

import pytest

import zim_probe as zp

HERE = pathlib.Path(__file__).resolve().parent
FIXTURE = HERE / "testdata" / "probe-test.zim"  # tools/test-zim/make_probe_zim.py
GRANGER_TEST = HERE.parents[1] / "testdata" / "granger-test.zim"  # zstd clusters


def test_metadata_and_main_page_of_a_local_zim():
    result = zp.probe(str(FIXTURE))
    assert result.get("error") is None
    md = result["metadata"]
    assert md["Scraper"] == "nautiluszim 1.1.1"
    assert md["Name"] == "zimgit-water_en" and md["Title"] == "Water"
    assert zp.parse_counter(md["Counter"]) == {"application/pdf": 2, "text/html": 1, "text/javascript": 1}
    assert result["mainPage"] == {"path": "home", "mime": "text/html", "title": "Water"}
    assert "application/pdf" in result["mimes"]


def test_an_entry_is_read_by_its_path():
    database = zp.read_entry(str(FIXTURE), "database.js").decode()
    assert database.startswith("var DATABASE = [")
    assert "'fp': ['Water (1).pdf']" in database
    assert zp.read_entry(str(FIXTURE), "files/Water (2).pdf").startswith(b"%PDF-1.4")


def test_a_redirect_is_followed_and_a_missing_entry_raises():
    assert b"<h1>Water</h1>" in zp.read_entry(str(FIXTURE), "index")
    with pytest.raises(KeyError):
        zp.read_entry(str(FIXTURE), "no/such/entry")


def test_a_file_that_isnt_a_zim_is_an_error(tmp_path):
    bad = tmp_path / "bad.zim"
    bad.write_bytes(b"not a zim" * 20)
    assert "bad magic" in zp.probe(str(bad))["error"]


def test_the_budget_stops_a_big_read():
    assert zp.probe(str(FIXTURE), budget=1000)["error"].startswith("budget")


def test_zstd_clusters_are_read():
    pytest.importorskip("zstandard")
    md = zp.probe(str(GRANGER_TEST))["metadata"]
    assert md["Name"] == "granger_test_en" and md["Title"] == "Granger Test Library"


def test_parse_counter_is_lenient():
    assert zp.parse_counter("a/b=1;bad;c/d=x;e/f=3") == {"a/b": 1, "e/f": 3}
    assert zp.parse_counter(None) == {}


@pytest.fixture
def range_server():
    """Serves the fixture ZIM with Range support at /pack.zim, through a redirect at /zim (as download.kiwix.org does)."""
    data = FIXTURE.read_bytes()
    ranges = []

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/zim":
                self.send_response(302)
                self.send_header("Location", "/pack.zim")
                self.end_headers()
                return
            a, b = self.headers["Range"].removeprefix("bytes=").split("-")
            a, b = int(a), min(int(b), len(data) - 1)
            ranges.append((a, b))
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {a}-{b}/{len(data)}")
            self.send_header("Content-Length", str(b - a + 1))
            self.end_headers()
            self.wfile.write(data[a:b + 1])

        def log_message(self, *args):
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{server.server_port}", ranges, len(data)
    server.shutdown()


def test_http_reads_follow_the_redirect_and_ask_only_for_ranges(range_server):
    base, ranges, size = range_server
    result = zp.probe(f"{base}/zim")
    assert result.get("error") is None, result
    assert result["metadata"]["Scraper"] == "nautiluszim 1.1.1"
    assert result["mirror"] == f"{base}/pack.zim"
    assert ranges and all(b - a < size for a, b in ranges)
    assert zp.read_entry(f"{base}/zim", "database.js").startswith(b"var DATABASE")


def test_probe_catalog_uses_its_cache_and_probes_the_rest(tmp_path):
    catalog = {"packs": [
        {"id": "wikipedia_en_medicine_maxi", "zimName": "wikipedia_en_medicine", "urls": ["u-maxi"]},
        {"id": "wikipedia_en_medicine_nopic", "zimName": "wikipedia_en_medicine", "urls": ["u-nopic"]},
        {"id": "zimgit-water_en", "zimName": "zimgit-water_en", "urls": ["dead", "u-water"]},
    ]}
    cache = tmp_path / "cache.jsonl"
    cache.write_text(json.dumps({"id": "wikipedia_en_medicine_maxi", "metadata": {"Scraper": "mwoffliner 2.1.1"},
                                 "error": None}) + "\n")
    asked = []

    def probe_one(url):
        asked.append(url)
        if url == "dead":
            return {"error": "IOError: giving up"}
        return {"metadata": {"Scraper": "nautiluszim 1.1.1" if url == "u-water" else "mwoffliner 2.1.1"}}

    contents, failed = zp.probe_catalog(catalog, cache, workers=2, probe_one=probe_one)
    assert contents == {"wikipedia_en_medicine": "mwoffliner 2.1.1", "zimgit-water_en": "nautiluszim 1.1.1"}
    assert failed == []
    assert sorted(asked) == ["dead", "u-nopic", "u-water"]  # the cached pack isn't asked again
    assert len(cache.read_text().splitlines()) == 3
    zp.write_contents(tmp_path / "pack_contents.json", contents)
    assert json.loads((tmp_path / "pack_contents.json").read_text())["packs"] == contents


def test_a_pack_that_fails_is_reported_and_left_out(tmp_path):
    catalog = {"packs": [{"id": "x_en_all", "zimName": "x_en_all", "urls": ["dead"]}]}
    contents, failed = zp.probe_catalog(catalog, tmp_path / "c.jsonl", probe_one=lambda u: {"error": "HTTP 404"})
    assert contents == {} and failed == ["x_en_all: HTTP 404"]


def test_real_pack_contents_cover_every_catalog_pack():
    catalog = json.loads((HERE.parents[1] / "catalog" / "catalog.json").read_text(encoding="utf-8"))
    contents = json.loads((HERE / "pack_contents.json").read_text(encoding="utf-8"))["packs"]
    assert {p["zimName"] for p in catalog["packs"]} == set(contents)
