import json

import grading


def rec(reply="hello", id="q1"):
    return {"id": id, "outcome": "answered", "reply": reply, "cited": [1], "chosen": [], "found": []}


DS = [{"id": "q1", "answerable": True, "question": "Q?", "reference": "R", "answers": []}]


def test_worksheet_lists_only_ungraded_replies():
    g = {("q1", "answer"): {"id": "q1", "kind": "answer", "replyHash": grading.reply_hash("hello"), "grade": "grounded", "reason": "ok"}}
    assert "q1" not in grading.worksheet(DS, [rec()], g)
    assert "q1" in grading.worksheet(DS, [rec()], {})


def test_changed_reply_needs_a_new_grade():
    g = {("q1", "answer"): {"id": "q1", "kind": "answer", "replyHash": grading.reply_hash("hello"), "grade": "grounded", "reason": "ok"}}
    text = grading.worksheet(DS, [rec("hello, changed")], g)
    assert "q1" in text and "hello, changed" in text


def test_load_grades_last_line_wins(tmp_path):
    p = tmp_path / "g.jsonl"
    p.write_text(json.dumps({"id": "a", "kind": "answer", "grade": "wrong"}) + "\n" + json.dumps({"id": "a", "kind": "answer", "grade": "grounded"}) + "\n")
    assert grading.load_grades(p)[("a", "answer")]["grade"] == "grounded"
