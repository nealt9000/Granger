"""Self-contained HTML pages: the run report and the dataset-only review page."""
from html import escape as e

import metrics as M
from grading import current_grade

CSS = """body{font:15px/1.5 system-ui,sans-serif;max-width:60rem;margin:2rem auto;padding:0 1rem;color:#1b1b1b;background:#fff}
table{border-collapse:collapse}td,th{border:1px solid #ccc;padding:.25rem .6rem;text-align:left}
.pass{color:#0a6b2a;font-weight:600}.fail{color:#b00020;font-weight:600}.q{border-top:1px solid #ccc;margin-top:1.5rem;padding-top:.5rem}
pre{white-space:pre-wrap;background:#f4f4f4;padding:.5rem}.badge{background:#0a6b2a;color:#fff;border-radius:3px;padding:0 .4rem;font-size:.8em}.picked{background:#e6f4ea}details{margin:.25rem 0}button{margin:0 .25rem .5rem 0}.dim{color:#666}small{color:#666}
@media(prefers-color-scheme:dark){.picked{background:#1d3324}.badge{background:#2f8f4f}body{background:#161616;color:#e8e8e8}pre{background:#242424}td,th,.q{border-color:#444}.dim,small{color:#aaa}.pass{color:#6fdc8c}.fail{color:#ff7a8a}}"""


def pct(x):
    return "n/a" if x is None else "%.0f%%" % (x * 100)


def page(title, body):
    return '<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>%s</title><style>%s</style></head><body>%s</body></html>' % (e(title), CSS, body)


def totals_rows(m):
    def row(label, value, bar, ok):
        cls = "" if ok is None else (' class="pass"' if ok else ' class="fail"')
        return "<tr><td>%s</td><td%s>%s</td><td>%s</td></tr>" % (e(label), cls, value, bar)
    u, a, r, f, d = m["unanswerable"], m["answerable"], m["retrieval"], m["medicalFlag"], m["deeper"]
    rows = [
        row("Unanswerable declined (%d of %d)" % (u["handled"], u["n"]), pct(u["rate"]), "bar 90%", m["pass"]["unanswerable"]),
        row("Answerable grounded (%d of %d, %d answered but ungraded)" % (a["grounded"], a["n"], a["ungraded"]), pct(a["rate"]), "bar 80%", m["pass"]["answerable"]),
        row("Retrieval recall@4", pct(r["recallAt4"]), "not gating", None),
        row("Answer found but not chosen / never found", "%d / %d" % (r["foundNotChosen"], r["neverFound"]), "not gating", None),
        row("Medical flag accuracy / recall", "%s / %s" % (pct(f["accuracy"]), pct(f["recall"])), "recall aim 95%", None if f["recall"] is None else f["recall"] >= 0.95),
    ]
    if d["n"]:  # Think deeper, in runs from before it was removed (Plan R5 Task 8)
        rows.append(row("Think deeper grounded (%d of %d)" % (d["grounded"], d["n"]), pct(d["rate"]), "E4B bar 80%", d["rate"] >= 0.8))
    if "media" in m:
        c, i = m["media"]["cited"], m["media"]["intrusion"]
        rows.append(row("Media cited (%d of %d)" % (c["hit"], c["n"]), pct(c["rate"]), "bar 80% (8 of 10)", m["pass"]["mediaCited"]))
        rows.append(row("Media intrusion on controls (%d of %d)" % (i["hit"], i["n"]), pct(i["rate"]), "bar 0", m["pass"]["mediaIntrusion"]))
    for model, g in m["guesses"].items():
        rows.append(row("Best guess %s (%d graded of %d): confidently wrong / right or partly" % (model, g["graded"], g["n"]),
                        "%s / %s" % (pct(g["confidentlyWrongRate"]), pct(g["rightOrPartlyRate"])), "bar at most 10% / at least 60%", g["goodEnough"] if g["graded"] else None))
    t = m["timings"]
    rows.append(row("Send to first words, median / p90 (ms)", "%s / %s" % (t["firstWords"]["median"], t["firstWords"]["p90"]), "target 9000", None))
    rows.append(row("Total, median / p90 (ms)", "%s / %s" % (t["total"]["median"], t["total"]["p90"]), "", None))
    return "<table><tr><th>Metric</th><th>Value</th><th>Bar</th></tr>%s</table>" % "".join(rows)


def totals_text(m):
    import re
    return re.sub(r"<[^>]+>|</tr>", lambda x: "\n" if x.group(0) == "</tr>" else "\t", totals_rows(m)).replace("\t\t", "\t").strip()


def _key(p):
    return (p.get("source", ""), p.get("location", ""), p.get("heading") or "")


def found_table(found, chosen):
    """The candidates as a table (rank, source, title, place, lexical score); the ones sent to the model are marked."""
    if not found:
        return "<p class=dim>none</p>"
    picked = {_key(p) for p in chosen}
    rows = "".join('<tr%s><td>%d</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>' % (
        ' class="picked"' if _key(p) in picked else "", i, e(p.get("source", "")), e(p.get("title", "")), e(p.get("heading") or p.get("location", "")),
        "%.2f" % p["lexical"] if "lexical" in p else "", "chosen" if _key(p) in picked else "") for i, p in enumerate(found, 1))
    return "<table><tr><th>#</th><th>Source</th><th>Title</th><th>Heading / location</th><th>Lexical</th><th></th></tr>%s</table>" % rows


def chosen_list(chosen, cited):
    """The passages as the model saw them, numbered from 1; cited ones are badged and open."""
    if not chosen:
        return "<p class=dim>none</p>"
    out = []
    for n, p in enumerate(chosen, 1):
        is_cited = n in cited
        out.append('<details%s><summary><b>[%d]</b> %s / %s / %s%s</summary><pre>%s</pre></details>' % (
            " open" if is_cited else "", n, e(p.get("source", "")), e(p.get("title", "")), e(p.get("heading") or p.get("location", "")),
            ' <span class="badge">cited</span>' if is_cited else "", e(p.get("text", ""))))
    return "".join(out)


def timings_line(ms):
    names = (("rewrite", "rewrite"), ("search", "search"), ("rank", "rank"), ("firstWords", "first words"), ("total", "total"))
    parts = ["%s %.1f s" % (label, ms[k] / 1000) for k, label in names if isinstance(ms.get(k), (int, float))]
    return "<p><b>Timings:</b> %s</p>" % " · ".join(parts) if parts else ""


def prompts_block(prompts):
    return "".join("<details><summary>Prompt: %s</summary><p><b>Prompt</b></p><pre>%s</pre><p><b>Output</b></p><pre>%s</pre></details>" % (
        e(p.get("stage", "")), e(p.get("prompt", "")), e(p.get("output", ""))) for p in prompts or [])


def grade_text(grades, id, kind, reply):
    g = current_grade(grades, id, kind, reply)
    return "<b>%s</b> %s" % (e(g["grade"]), e(g.get("reason", ""))) if g else "<span class=dim>ungraded</span>"


def grade_summary(item, rec, grades):
    """(label for the index, set of filter flags) from the answer grade, or the guess grades."""
    flags, labels = set(), []
    if item.get("answerable") and rec["outcome"] == "answered":
        g = current_grade(grades, rec["id"], "answer", rec.get("reply", ""))
        labels.append(g["grade"] if g else "ungraded")
        if g and g["grade"] == "not-grounded":
            flags.add("not-grounded")
    d = rec.get("deeper")
    if d:
        g = current_grade(grades, rec["id"], "deeper", d.get("reply", ""))
        if g and g["grade"] == "not-grounded":
            flags.add("not-grounded")
    for model, gu in (rec.get("guesses") or {}).items():
        g = current_grade(grades, rec["id"], "guess-" + model, gu.get("text", ""))
        labels.append("%s: %s" % (model, g["grade"] if g else "ungraded"))
        if g and g["grade"] == "confidently-wrong":
            flags.add("confidently-wrong")
    return ", ".join(labels), flags


def question_block(item, rec, grades):
    id = rec["id"]
    _, flags = grade_summary(item, rec, grades)
    h = ['<div class="q" id="q-%s" data-outcome="%s" data-flags="%s"><h3>%s <small>%s, %s%s</small></h3><p>%s</p>' % (
        e(id), e(rec["outcome"]), e(" ".join(sorted(flags))), e(id), e(item.get("group", "")), e(item.get("split", "")), ", medical" if item.get("medical") else "", e(item["question"]))]
    h.append("<p><b>Reference:</b> %s</p>" % e(item.get("reference") or ""))
    h.append(timings_line(rec.get("ms") or {}))
    h.append("<p><b>Search words:</b> %s%s</p>" % (e(", ".join(rec.get("keywords", []))), " (rewrite fell back)" if rec.get("rewriteFallback") else ""))
    found, chosen = rec.get("found", []), rec.get("chosen", [])
    h.append("<details><summary><b>Found</b> (%d)</summary>%s</details>" % (len(found), found_table(found, chosen)))
    h.append("<p><b>Chosen (sent to the model):</b></p>%s" % chosen_list(chosen, rec.get("cited", [])))
    h.append("<p><b>Outcome:</b> %s, cited %s</p><pre>%s</pre>" % (e(rec["outcome"]), e(str(rec.get("cited", []))), e(rec.get("reply", ""))))
    if item.get("answerable") and rec["outcome"] == "answered":
        h.append("<p><b>Grade:</b> %s</p>" % grade_text(grades, id, "answer", rec.get("reply", "")))
    d = rec.get("deeper")
    if d:
        h.append("<p><b>Think deeper:</b> %s</p><pre>%s</pre><p>%s</p>" % (e(d["outcome"]), e(d.get("reply", "")), grade_text(grades, id, "deeper", d.get("reply", ""))))
    for model, g in (rec.get("guesses") or {}).items():
        h.append("<p><b>Best guess (%s):</b></p><pre>%s</pre><p>%s</p>" % (e(model), e(g.get("text", "")), grade_text(grades, id, "guess-" + model, g.get("text", ""))))
    if rec.get("prompts"):
        h.append("<p><b>Prompts</b></p>%s" % prompts_block(rec["prompts"]))
    return "".join(h) + "</div>"


FILTERS = (("All", "all"), ("Answered", "answered"), ("Declined", "declined"), ("Not grounded", "not-grounded"), ("Confidently wrong", "confidently-wrong"))

FILTER_JS = """document.querySelectorAll('#filters button').forEach(function(b){b.onclick=function(){var f=b.dataset.f;
document.querySelectorAll('.q').forEach(function(q){var ok=f==='all'||q.dataset.outcome===f||(q.dataset.flags||'').split(' ').indexOf(f)>=0;q.style.display=ok?'':'none'})}})"""


def index_table(items_recs, grades):
    rows = []
    for item, rec in items_recs:
        label, _ = grade_summary(item, rec, grades)
        rows.append('<tr><td><a href="#q-%s">%s</a></td><td>%s</td><td>%s</td><td>%s</td></tr>' % (e(rec["id"]), e(rec["id"]), e(item.get("group", "")), e(rec["outcome"]), e(label)))
    buttons = "".join('<button type="button" data-f="%s">%s</button> ' % (f, e(t)) for t, f in FILTERS)
    return '<div id="filters">%s</div><table><tr><th>Question</th><th>Group</th><th>Outcome</th><th>Grade</th></tr>%s</table><script>%s</script>' % (buttons, "".join(rows), FILTER_JS)


def _state(item, rec, grades):
    """What a comparison looks at: the outcome and the answer grade."""
    g = current_grade(grades, item["id"], "answer", rec.get("reply", "")) if rec["outcome"] == "answered" else None
    return rec["outcome"], g["grade"] if g else None


def compare_block(items, a_recs, b_recs, grades):
    by_id = {i["id"]: i for i in items}
    a = {r["id"]: r for r in a_recs}
    rows = []
    for r in b_recs:
        o = a.get(r["id"])
        if o and r["id"] in by_id and _state(by_id[r["id"]], o, grades) != _state(by_id[r["id"]], r, grades):
            rows.append("<tr><td>%s</td><td>%s</td><td>%s</td></tr>" % (e(r["id"]), e("%s / %s" % _state(by_id[r["id"]], o, grades)), e("%s / %s" % _state(by_id[r["id"]], r, grades))))
    return "<h2>Changed since the comparison run (%d)</h2><table><tr><th>Question</th><th>Before</th><th>Now</th></tr>%s</table>" % (len(rows), "".join(rows))


def run_report(run_id, items, records, grades, compare=None):
    """compare = (other_run_id, other_records, other_grades) or None."""
    m = M.compute(items, records, grades)
    by_id = {i["id"]: i for i in items}
    body = ["<h1>Research eval: %s</h1><p>%d questions</p><h2>Against the bar</h2>%s" % (e(run_id), m["n"], totals_rows(m))]
    if compare:
        body.append(compare_block(items, compare[1], records, grades))
    pairs = [(by_id[r["id"]], r) for r in records if r["id"] in by_id]
    body.append("<h2>Questions</h2>")
    body.append(index_table(pairs, grades))
    body += [question_block(i, r, grades) for i, r in pairs]
    return page("Research eval %s" % run_id, "".join(body))


def dataset_report(names_and_items):
    body = ["<h1>Research eval: dataset review</h1>"]
    for name, items in names_and_items:
        body.append("<h2>%s (%d)</h2>" % (e(name), len(items)))
        for it in items:
            ans = "".join("<li>%s / %s: <i>%s</i></li>" % (e(a["source"]), e(a["location"]), e(a.get("quote", ""))) for a in it.get("answers") or [])
            flags = ", ".join(k for k in ("answerable", "medical", "multiStep") if it.get(k)) or "unanswerable"
            body.append('<div class="q"><h3>%s <small>%s, %s, %s</small></h3><p>%s</p>%s<p><b>Reference:</b> %s</p>%s%s</div>' % (
                e(it["id"]), e(it.get("group", "")), e(it.get("split", "")), e(flags), e(it["question"]),
                "<p><b>After:</b> %s</p>" % e(it["priorQuestion"]) if it.get("priorQuestion") else "",
                e(it.get("reference") or ""), "<ul>%s</ul>" % ans if ans else "",
                "<p><b>Good guess:</b> %s</p>" % e(it["goodGuess"]) if it.get("goodGuess") else ""))
    return page("Research eval dataset", "".join(body))
