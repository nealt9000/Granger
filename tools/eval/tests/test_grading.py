import json

import pytest

import grading


def rec(reply="hello", id="q1"):
    return {"id": id, "outcome": "answered", "reply": reply, "cited": [1], "chosen": [], "found": []}


DS = [{"id": "q1", "answerable": True, "question": "Q?", "reference": "R", "answers": []}]


def test_worksheet_lists_only_ungraded_replies():
    g = {("q1", "answer", grading.reply_hash("hello")): {"id": "q1", "kind": "answer", "replyHash": grading.reply_hash("hello"), "grade": "grounded", "reason": "ok"}}
    assert "q1" not in grading.worksheet(DS, [rec()], g)
    assert "q1" in grading.worksheet(DS, [rec()], {})


def test_changed_reply_needs_a_new_grade():
    g = {("q1", "answer", grading.reply_hash("hello")): {"id": "q1", "kind": "answer", "replyHash": grading.reply_hash("hello"), "grade": "grounded", "reason": "ok"}}
    text = grading.worksheet(DS, [rec("hello, changed")], g)
    assert "q1" in text and "hello, changed" in text


def line(**kw):
    d = {"id": "a", "kind": "answer", "replyHash": "h", "grade": "grounded", "reason": ""}
    d.update(kw)
    return json.dumps(d)


def test_load_grades_last_line_wins_for_the_same_reply(tmp_path):
    p = tmp_path / "g.jsonl"
    p.write_text(line(grade="not-grounded") + "\n" + line() + "\n")
    assert grading.load_grades(p)[("a", "answer", "h")]["grade"] == "grounded"


def test_two_replies_for_one_id_both_keep_their_grades(tmp_path):
    p = tmp_path / "g.jsonl"
    h1, h2 = grading.reply_hash("run one"), grading.reply_hash("run two")
    p.write_text(line(replyHash=h1, grade="grounded") + "\n" + line(replyHash=h2, grade="not-grounded") + "\n")
    g = grading.load_grades(p)
    assert grading.current_grade(g, "a", "answer", "run one")["grade"] == "grounded"
    assert grading.current_grade(g, "a", "answer", "run two")["grade"] == "not-grounded"


def test_bad_grade_value_names_file_and_line(tmp_path):
    p = tmp_path / "g.jsonl"
    p.write_text(line() + "\n" + line(grade="groundd") + "\n")
    with pytest.raises(SystemExit, match=r"g.jsonl:2.*groundd"):
        grading.load_grades(p)
    p.write_text(line(kind="guess-small", grade="grounded") + "\n")
    with pytest.raises(SystemExit, match=r"g.jsonl:1"):
        grading.load_grades(p)


def test_malformed_grade_line_names_file_and_line(tmp_path):
    p = tmp_path / "g.jsonl"
    p.write_text(line() + "\n{not json\n")
    with pytest.raises(SystemExit, match=r"g.jsonl:2"):
        grading.load_grades(p)
