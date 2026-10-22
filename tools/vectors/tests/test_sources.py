import io
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import sources  # noqa: E402


def test_resolve_matches_epub_inspector():
    assert sources._resolve("OEBPS/content.opf", "c001.xhtml") == "OEBPS/c001.xhtml"
    assert sources._resolve("OEBPS/content.opf", "Text/ch%201.xhtml") == "OEBPS/Text/ch 1.xhtml"
    assert sources._resolve("OEBPS/pkg/content.opf", "../Text/./a.xhtml") == "OEBPS/Text/a.xhtml"
    assert sources._resolve("content.opf", "a.xhtml#frag") == "a.xhtml"


def test_spine_in_order_and_unknown_idrefs_skipped():
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("META-INF/container.xml", '<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>')
        z.writestr("OEBPS/content.opf", '<package><metadata><dc:title>Walden</dc:title></metadata><manifest>'
                   '<item id="b" href="c002.xhtml"/><item id="a" href="c001.xhtml"/></manifest>'
                   '<spine><itemref idref="a"/><itemref idref="zz"/><itemref idref="b"/></spine></package>')
    with zipfile.ZipFile(buf) as z:
        assert sources.epub_spine(z) == ("Walden", ["OEBPS/c001.xhtml", "OEBPS/c002.xhtml"])
