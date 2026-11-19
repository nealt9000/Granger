"""Plan O0, question 3: the index is built from a September snapshot; live Wikipedia moves. For a sample of broad topics,
how many of the index's sections (by anchor id) and figures (by file name) are still in the live article?

  ~/.venvs/granger-pack/bin/python drift.py /Volumes/X10\\ Pro/granger-ll/gqid-v6/wikipedia_en_top_nopic_2026-09-14.gqid 40
"""
import random, sqlite3, sys, urllib.parse, urllib.request
from bs4 import BeautifulSoup

UA = {"User-Agent": "Granger/spike (https://opengranger.org; feedback@opengranger.org)"}


def live(title):
    url = f"https://en.wikipedia.org/w/rest.php/v1/page/{urllib.parse.quote(title)}/html"
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
        return r.read().decode("utf-8")


def main():
    db = sqlite3.connect(sys.argv[1])
    n = int(sys.argv[2])
    topics = [r[0] for r in db.execute("SELECT a.path FROM topics t JOIN articles a ON a.qid = t.qid")]
    random.seed(3)
    sample = random.sample(topics, min(n, len(topics)))
    sec_all = sec_ok = fig_all = fig_ok = moved = 0
    for path in sample:
        qid = db.execute("SELECT qid FROM articles WHERE path = ?", (path,)).fetchone()[0]
        parts = db.execute("SELECT part, note FROM topic_parts WHERE topic = ? AND part IN (3, 5)", (qid,)).fetchall()
        try:
            html = live(path)
        except Exception as e:
            print(f"{path}: {e}"); moved += 1; continue
        doc = BeautifulSoup(html, "html.parser")
        ids = {h.get("id") for h in doc.select("h2[id], h3[id], h4[id]")}
        files = {urllib.parse.unquote(img.get("resource", "") or img.get("src", "")).rsplit("/", 1)[-1].split(":", 1)[-1] for img in doc.select("img")}
        files |= {f.split("px-", 1)[-1] for f in files}
        s_ok = f_ok = s_n = f_n = 0
        for part, note in parts:
            if part == 3:
                s_n += 1; s_ok += note in ids
            else:
                f_n += 1; base = note.rsplit("/", 1)[-1]
                f_ok += any(base == f or base.replace(" ", "_") == f for f in files)
        sec_all += s_n; sec_ok += s_ok; fig_all += f_n; fig_ok += f_ok
        print(f"{path:40s} sections {s_ok}/{s_n}  figures {f_ok}/{f_n}")
    print(f"\nsections still there: {sec_ok}/{sec_all} ({100*sec_ok/max(sec_all,1):.0f}%)")
    print(f"figures still there:  {fig_ok}/{fig_all} ({100*fig_ok/max(fig_all,1):.0f}%)")
    print(f"titles that failed to load: {moved}")


if __name__ == "__main__":
    main()
