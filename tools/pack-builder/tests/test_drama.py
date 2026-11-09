"""The play pass on cuts from the real sources, one per speaker pattern (formatting audit 2026-11-09, §2)."""
import re

from bs4 import BeautifulSoup

import drama
import epubpack
import verse
from conftest import FIXTURES


def structured(name, hints=None, min_speeches=3):
    soup = BeautifulSoup(f"<div id='body'>{(FIXTURES / 'drama' / name).read_text()}</div>", "lxml")
    body = soup.find(id="body")
    report = drama.structure(body, hints, min_speeches)
    return body, report


def page(body):
    """The body as the EPUB writer prepares one page of a play."""
    return epubpack._prepare(str(body), reads_as_verse=True, is_drama=True)


def whos(tag):
    return [" ".join(w.get_text().split()) for w in tag.select(".gd-who")]


def test_a_name_on_its_own_line_becomes_the_head_of_every_speech_whatever_its_breaks():
    body, report = structured("hamlet-a.html")
    speeches = body.select(".gd-speech")
    assert whos(body)[:4] == ["First Clown.", "Second Clown.", "First Clown.", "Second Clown."]
    assert all("gd-above" in s["class"] for s in speeches)
    # a one-line prose speech: the name line's <br> is gone, the words stay prose
    first = speeches[0]
    assert first.find("br") is None and "Is she to be buried" in first.get_text()
    assert body.find(class_="gd-sd").get_text().strip().startswith("Enter two")  # p.scenedesc
    assert "scenedesc" not in body.find(class_="gd-sd")["class"]
    assert body.find("h3").get_text().strip() == "Scene I. A churchyard."


def test_a_verse_speech_keeps_its_head_out_of_the_verse_lines():
    soup = page(BeautifulSoup('<p class="drama gd-speech gd-above"><span class="gd-head"><span class="gd-who">'
                              'Polonius.</span></span>Give him this money<br/>and these notes, Reynaldo.</p>',
                              "lxml").body.decode_contents())
    p = soup.p
    assert "gv" in p["class"]
    assert [c.get("class") for c in p.children if getattr(c, "name", None)] == [["gd-head"], ["gv-l"], ["gv-l"]]


def test_a_name_in_a_block_of_its_own_joins_the_speech_after_it():
    body, _ = structured("lysistrata-b.html")
    speeches = body.select(".gd-speech")
    assert whos(speeches[0]) == ["Lysistrata"]
    assert "If they were trysting" in speeches[0].get_text()
    assert not any(" ".join(p.get_text().split()) == "LYSISTRATA" for p in body.find_all("p"))


def test_a_speaker_set_as_a_heading_is_no_heading():
    body, _ = structured("trojan-women-b-h5.html")
    assert body.find("h5") is None
    assert whos(body)[:3] == ["Poseidon.", "Pallas.", "Poseidon."]


def test_run_in_capitals_and_bracketed_directions():
    body, _ = structured("ghosts-c.html")
    speech = next(s for s in body.select(".gd-speech") if "Good-morning" in s.get_text())
    assert whos(speech) == ["Manders."] and "gd-run" in speech["class"]
    regina = next(s for s in body.select(".gd-speech") if s.select_one(".gd-who").get_text() == "Regina.")
    assert regina.select_one(".gd-sdi").get_text() == "[In a low voice.]"
    assert body.find("h2").get_text().strip() == "ACT FIRST."  # the play's own heading: left to _quiet_headings
    assert "gd-sd" in body.find("p")["class"]  # "[A spacious garden-room, …]"


def test_a_name_in_italics_and_a_parenthesis_with_italics_inside():
    body, _ = structured("dolls-house-f.html")
    assert whos(body)[:3] == ["Nora.", "Porter.", "Nora."]
    helmer = next(s for s in body.select(".gd-speech") if "Helmer" in s.select_one(".gd-who").get_text())
    assert helmer.select_one(".gd-who").find("i") is None


def test_a_colon_and_a_direction_after_the_name():
    body, _ = structured("cyrano-a-colon.html")
    heads = body.select(".gd-head")
    assert whos(body)[:2] == ["Cyrano:", "The Duenna:"]
    cyrano = next(h for h in heads if h.select_one(".gd-sdi"))
    assert cyrano.select_one(".gd-sdi").get_text() == "(catching up some of the paper bags from the counter)"


def test_a_pre_play_splits_into_speeches_a_name_alone_joins_its_speech():
    body, report = structured("frogs-d-pre.html")
    assert report.pre_plays == 1 and body.find("pre") is None
    assert whos(body)[:3] == ["Dionysus.", "Xan.", "Dio."]
    lone = next(s for s in body.select(".gd-speech") if "What! must I bear" in s.get_text())
    assert whos(lone) == ["Xan."]  # "XAN." / blank line / the speech


def test_a_name_and_its_speech_set_apart_are_not_a_line_and_its_gloss():
    body, _ = structured("duchess-d-gloss.html")
    soup = page(body)
    assert not soup.select(".gv-n")
    never = next(s for s in soup.select(".gd-speech") if "Never, my lord." in s.get_text())
    assert whos(never) == ["Cariola."]
    kisses = soup.find(string=re.compile(r"She kisses him again"))
    assert kisses.find_parent(class_="gd-sdi") is not None
    assert soup.find(string=re.compile(r"\[flight,\]")).find_parent(class_="gd-sdi") is None  # an editor's word


def test_a_prose_play_in_a_pre_reads_as_prose_paragraphs():
    body, _ = structured("clouds-e-prose.html", drama.Hints(titlecase=True, prose=True))
    soc = next(s for s in body.select(".gd-speech") if s.get_text().strip().startswith("Soc. Come now"))
    assert soc.name == "p" and "gd-run" in soc["class"]
    assert " ".join(soc.get_text().split()).startswith("Soc. Come now, tell me your own turn of mind; in order that, when")


def test_off_and_no_cast_leave_the_work_alone():
    body, report = structured("hamlet-a.html", drama.Hints.parse(False))
    assert report.skipped == "off" and not body.select(".gd-speech")
    sonnet = BeautifulSoup("<div><p>Shall I compare thee to a summer's day?<br/>Thou art more lovely</p></div>",
                           "lxml").div
    assert drama.structure(sonnet).skipped.startswith("only")


def test_labels_display_and_act_lines():
    assert drama.display("FIRST CLOWN.") == "First Clown." and drama.display("KING OF FRANCE") == "King of France"
    assert drama.display("Nora") == "Nora" and drama.display("mrs. cheveley") == "Mrs. Cheveley"
    assert drama.act_scene("ACT II.") == "act" and drama.act_scene("SCENE I. A room in the castle.") == "scene"
    assert drama.act_scene("FIRST ACT") == "act" and drama.act_scene("[Act I. Scene 2.]") == "scene"
    assert drama.act_scene("SCENE.—Elsinore.") is None and drama.act_scene("Actvs Primvs") == "act"
    assert drama.quiet_label("SCENE I. A churchyard.") == "Scene I. A churchyard."
    assert drama.in_cast("RUD., MAR. and COL.", {"RUD", "MAR", "COL"})


def test_the_gloss_rule_wants_a_gloss():
    assert verse.is_gloss("*sweet") and verse.is_gloss("crowd")
    assert not verse.is_gloss("Never, my lord. I have told you") and not verse.is_gloss("CARIOLA")
