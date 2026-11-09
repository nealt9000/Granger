"""The verse check's line marking and pass rule; rendering needs Chrome, so it isn't run here."""
import sys
from pathlib import Path

from bs4 import BeautifulSoup

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "verse_check"))
import compare  # noqa: E402
import render  # noqa: E402


def test_mark_finds_built_br_and_element_lines():
    soup = BeautifulSoup('<div class="gv"><span class="gv-l">a</span><span class="gv-l">b</span></div>'
                         "<p>one<br/>two<br/>three</p>"
                         '<div class="stanza"><p>line</p><p>line</p></div><pre>x\ny\n</pre>'
                         f"<p>{'long prose ' * 20}<br/>{'more prose ' * 20}<br/>end</p>", "lxml")
    assert render.mark(soup) == 2 + 3 + 2
    assert soup.find("pre")["data-n"] == "2"


def test_a_book_fails_over_the_flush_limit_or_with_lost_alignment(capsys):
    ok = {"lines": 1000, "wrapped": 100, "wrapped_hang": 90, "pre_src": 0, "pre_rows": 0, "align_lost": 0}
    assert compare.stats(ok) == (1000, 10, 100, 0)
    assert compare.report("x", {"a": ok}, 2.0, 100, set())
    assert not compare.report("x", {"a": dict(ok, wrapped_hang=60)}, 2.0, 100, set())
    assert compare.report("x", {"a": dict(ok, wrapped_hang=60)}, 2.0, 100, {"a"})
    assert not compare.report("x", {"a": dict(ok, align_lost=1)}, 2.0, 100, set())


def test_harness_paginates_when_asked():
    import harness
    assert "readium-scroll-on" in harness.root_style(1.0)
    assert "readium-scroll-on" not in harness.root_style(1.0, scroll=False)


def test_cover_check_measures_clearance_from_the_screen_bands():
    import cover_check
    phone = cover_check.Phone(390, 844)
    assert (phone.page_top, phone.page_height, phone.top_band, phone.bottom_band) == (64, 740, 144, 744)
    top, foot = cover_check.clearance(phone, {"top": 100, "height": 500})
    assert (top, foot) == (20, 80)
