import report

ITEMS = [{"id": "q1", "group": "g", "split": "tune", "answerable": False, "medical": False, "question": "Wi-Fi <password>?", "reference": "", "answers": [], "goodGuess": "can't know"}]
RECS = [{"id": "q1", "medical": False, "outcome": "declined", "reply": "Not in your library.", "found": [], "chosen": [], "keywords": ["wifi"], "ms": {"total": 5}}]


def test_run_report_is_self_contained_and_escaped():
    html = report.run_report("r1", ITEMS, RECS, {})
    assert "<link" not in html and "Wi-Fi &lt;password&gt;?" in html and "90%" in html


def test_dataset_report_lists_questions():
    html = report.dataset_report([("tune.json", ITEMS)])
    assert "q1" in html and "can&#x27;t know" in html


TRACE_ITEM = {"id": "q2", "group": "g", "split": "tune", "answerable": True, "medical": False, "question": "Boil?", "reference": "100 C", "answers": [{"source": "book:a", "location": "w1"}]}
P1 = {"source": "book:a", "title": "Water", "location": "w1", "heading": "Boiling", "text": "Boils at <b>100</b>."}
P2 = {"source": "book:a", "title": "Water", "location": "w2", "heading": "Freezing", "text": "Freezes at 0."}
TRACE_REC = {"id": "q2", "outcome": "answered", "reply": "100 [1]", "cited": [1], "keywords": ["water"], "chosen": [P1, P2],
             "found": [dict(P2, lexical=0.5), dict(P1, lexical=0.9), {"source": "book:b", "title": "Other", "location": "o", "lexical": 0.1}],
             "ms": {"rewrite": 10300, "search": 10000, "rank": 0, "firstWords": 23400, "total": 24700},
             "prompts": [{"stage": "rewrite", "prompt": "<script>alert(1)</script>", "output": "keywords: water"}]}


def trace_html():
    return report.run_report("r2", ITEMS + [TRACE_ITEM], [TRACE_REC], {})


def test_cited_chosen_passage_has_badge_and_text():
    html = trace_html()
    assert '<details open><summary><b>[1]</b>' in html and 'class="badge">cited' in html
    assert "Boils at &lt;b&gt;100&lt;/b&gt;." in html
    assert "<details><summary><b>[2]</b>" in html  # not cited: closed


def test_found_rows_that_were_chosen_are_marked():
    html = trace_html()
    assert html.count('<tr class="picked">') == 2 and "Other</td><td>o</td><td>0.10</td><td></td>" in html


def test_timings_render_and_missing_fields_are_fine():
    html = trace_html()
    assert "rewrite 10.3 s · search 10.0 s · rank 0.0 s · first words 23.4 s · total 24.7 s" in html
    old = {"id": "q1", "outcome": "declined", "reply": "no"}
    assert "Timings" not in report.run_report("r0", ITEMS, [old], {}) and "Prompts" not in report.run_report("r0", ITEMS, [old], {})


def test_prompts_render_escaped():
    html = trace_html()
    assert "Prompt: rewrite" in html and "&lt;script&gt;alert(1)&lt;/script&gt;" in html and "<script>alert(1)" not in html


def test_index_links_to_block_anchor():
    html = trace_html()
    assert '<a href="#q-q2">q2</a>' in html and 'id="q-q2"' in html
