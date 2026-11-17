"""Probe every en/es/sw/mul pack in Kiwix's OPDS feed: metadata + mime Counter. Resumable jsonl."""
import sys, json, pathlib, concurrent.futures, threading, urllib.parse
REPO = pathlib.Path("/Users/s.nealthompson/Developer/Granger/Granger/tools/catalog-gen")
sys.path.insert(0, str(REPO))
import catalog_gen, zim_probe
out = pathlib.Path(sys.argv[1])
feed = json.load(open(out.with_name("feed.json")))
keep = [e for e in feed if not e["language"] or {l.split("-")[0] for l in e["language"].split(",")} & {"en", "es", "sw", "mul"}]
print(f"feed {len(feed)}, kept {len(keep)}", flush=True)
done = set()
if out.exists():
    for l in out.read_text().splitlines():
        r = json.loads(l)
        if not r.get("error") or r["error"].startswith("budget"): done.add(r["uuid"])
todo = [e for e in keep if e["uuid"] not in done]
print(f"todo {len(todo)}", flush=True)
lock = threading.Lock()
def one(e):
    url = urllib.parse.urljoin(catalog_gen.OPDS_ENTRIES, e["meta4"]).removesuffix(".meta4")
    r = zim_probe.probe(url)
    md = r.get("metadata", {})
    return {"uuid": e["uuid"], "name": e["name"], "flavour": e["flavour"], "language": e["language"],
            "title": e["title"], "summary": e["summary"], "category": e["category"], "tags": e["tags"],
            "length": e["length"], "url": url, "scraper": md.get("Scraper"),
            "counter": zim_probe.parse_counter(md.get("Counter")), "entryCount": r.get("entryCount"),
            "mainPage": r.get("mainPage"), "error": r.get("error")}
n = 0
with out.open("a") as f, concurrent.futures.ThreadPoolExecutor(16) as pool:
    for rec in pool.map(one, todo):
        with lock:
            f.write(json.dumps(rec) + "\n"); f.flush(); n += 1
            if n % 50 == 0: print(n, flush=True)
print("done", n)
