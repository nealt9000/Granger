import report

ITEMS = [{"id": "q1", "group": "g", "split": "tune", "answerable": False, "medical": False, "question": "Wi-Fi <password>?", "reference": "", "answers": [], "goodGuess": "can't know"}]
RECS = [{"id": "q1", "medical": False, "outcome": "declined", "reply": "Not in your library.", "found": [], "chosen": [], "keywords": ["wifi"], "ms": {"total": 5}}]


def test_run_report_is_self_contained_and_escaped():
    html = report.run_report("r1", ITEMS, RECS, {})
    assert "<link" not in html and "Wi-Fi &lt;password&gt;?" in html and "90%" in html


def test_dataset_report_lists_questions():
    html = report.dataset_report([("tune.json", ITEMS)])
    assert "q1" in html and "can&#x27;t know" in html
