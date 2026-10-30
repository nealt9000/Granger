"""Builds tools/catalog-gen/testdata/probe-test.zim, the tiny nautilus-style ZIM zim_probe.py's tests read (Plan KB).

    /tmp/zimenv/bin/python tools/test-zim/make_probe_zim.py tools/catalog-gen/testdata/probe-test.zim

Its clusters are stored uncompressed so the tests need no zstandard; an LZMA or zstd cluster is read the same way
after decompression.
"""
import sys
from pathlib import Path

from libzim.writer import Compression, Creator, Hint

from make_test_zim import Entry, page, tiny_pdf

DATABASE = """var DATABASE = [
{'_id': '00000', 'ti': 'Distillation For Home Water Treatment', 'dsc': 'For people with a water quality problem ', 'aut': 'Michigan State University', 'fp': ['Water (1).pdf']},
{'_id': '00001', 'ti': 'Purifying Water During an Emergency', 'dsc': '', 'aut': '-', 'fp': ['Water (2).pdf']},
];
"""


def build(out: Path) -> None:
    out.unlink(missing_ok=True)
    with Creator(out).config_compression(Compression.none) as creator:
        creator.set_mainpath("home")
        creator.add_item(Entry("home", "Water", "text/html", page("Water", "<p>Documents.</p>"), True))
        creator.add_item(Entry("database.js", "", "text/javascript", DATABASE, False))
        creator.add_item(Entry("files/Water (1).pdf", "", "application/pdf", tiny_pdf("Boil water."), False))
        creator.add_item(Entry("files/Water (2).pdf", "", "application/pdf", tiny_pdf("Filter water."), False))
        creator.add_redirection("index", "", "home", {Hint.FRONT_ARTICLE: False})
        for name, value in [
            ("Title", "Water"),
            ("Description", "Water treatment documents"),
            ("Language", "eng"),
            ("Creator", "Various"),
            ("Publisher", "openZIM"),
            ("Name", "zimgit-water_en"),
            ("Date", "2024-08-01"),
            ("Scraper", "nautiluszim 1.1.1"),
            ("Tags", "_category:other;preppers"),
        ]:
            creator.add_metadata(name, value)


if __name__ == "__main__":
    build(Path(sys.argv[1]))
