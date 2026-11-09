"""drama_check's static measures, on small pages built the way the EPUB writer builds them."""
import sys
from pathlib import Path

from bs4 import BeautifulSoup

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "drama_check"))
import static  # noqa: E402


def soup(html):
    return BeautifulSoup(f"<html><body>{html}</body></html>", "lxml")


def test_a_speech_whose_name_is_a_line_of_its_gloss_counts_as_a_misfire():
    page = soup('<div class="gv"><span class="gv-l"><span class="gv-n">Never, my lord.</span> CARIOLA.</span>'
                '<span class="gv-l"><span class="gv-n">*sweet</span> swoot*</span>'
                '<span class="gv-l">hand <span class="gv-n">20</span></span></div>')
    assert static.gloss_misfires(page) == 1


def test_hidden_starts_are_column_0_lines_with_an_indented_one():
    page = soup("<p>        CHAP. II. 1. The philosopher Yu said\nfilial and fraternal\n"
                "        2. 'The superior man</p><p>\n      pretty printed\n      every line\n</p>")
    assert static.hidden_starts(page) == 1


def test_a_link_target_after_the_last_words_is_stranded_unless_a_page_anchor():
    page = soup('<p>The end.</p><span id="link2H_4_0003"></span><span id="page_36"></span>')
    targets = {"OEBPS/c001.xhtml#link2H_4_0003", "OEBPS/c001.xhtml#page_36"}
    assert static.stranded("OEBPS/c001.xhtml", page, targets) == 1


def test_hard_wraps_in_a_pre_born_block():
    page = soup('<div class="gv gv-pre"><span class="gv-l">Come now, tell me your own turn of mind; in order</span>'
                '<span class="gv-l">that, when I know of what sort it is</span></div>')
    assert static.hard_wraps(page) == 1


def test_bars_fail_a_play_and_spare_a_book():
    play = {"speeches": 100, "coverage": 0.9, "sd_missed": 0.0, "act_text": 0, "gloss": 2, "nav_act": True}
    assert static.failures("x", play, True) == ["gloss 2", "coverage 0.9"]
    assert static.failures("x", {"gloss": 40, "stranded": 0, "hidden": 0}, False) == []
