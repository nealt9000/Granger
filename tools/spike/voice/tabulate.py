#!/usr/bin/env python3
"""Turns tools/spike/voice/*.log into markdown tables for the V0 results doc.

Each log holds one launch: "pass=1" (cold, fresh process) then "pass=2" (warm, same process).
Rows: engine, threads, pass, passage -> load ms, first audio of sentence 0, max first audio, rtf, peak rss.
"""
import glob
import re
import sys
from collections import OrderedDict

root = sys.argv[1] if len(sys.argv) > 1 else "tools/spike/voice"
rows = OrderedDict()   # (engine, threads, pass, passage) -> dict
loads = {}             # (engine, threads, pass) -> (loadMs, rssMb, info)
sent = {}              # (engine, threads, pass, passage) -> list of sentence dicts
extra = {}             # (engine, threads, pass) -> {llm, music}

kv = re.compile(r"(\w+)=([^ ]+)")

for path in sorted(glob.glob(f"{root}/*.log")):
    cur_pass = None
    llm = music = "false"
    summaries = 0  # three passages per pass; the pass number comes from the count, since logcat's ring buffer can drop the start lines of long runs
    for line in open(path, encoding="utf-8", errors="replace"):
        if "Voice:" not in line:
            continue
        body = line.split("Voice:", 1)[1].strip()
        d = dict(reversed(kv.findall(body)))  # first occurrence wins (the system load line has two engine= fields)
        if "auto start" in body:
            llm, music = d.get("llm", "false"), d.get("music", "false")
        elif " start " in body and "pass" in d:
            cur_pass = int(d["pass"])
            llm, music = d.get("llmResident", llm), d.get("music", music)
        elif " load " in body:
            cur_pass = summaries // 3 + 1
            info = body.split("rssMb=", 1)[1].split(" ", 1)
            loads[(d["engine"], int(d["threads"]), cur_pass, llm == "true")] = (int(d["loadMs"]), int(d["rssMb"]), info[1] if len(info) > 1 else "")
        elif "sentence=" in body and "summary" not in body:
            key = (d["engine"], int(d["threads"]), cur_pass, d["passage"], llm == "true", music == "true")
            sent.setdefault(key, []).append(d)
        elif "summary" in body:
            cur_pass = summaries // 3 + 1
            summaries += 1
            key = (d["engine"], int(d["threads"]), cur_pass, d["passage"], llm == "true", music == "true")
            s = sent.get(key, [])
            first0 = s[0]["firstAudioMs"] if s else "-"
            rows[key] = dict(
                load=loads.get(key[:3] + (llm == "true",), ("-", "-", ""))[0],
                first0=first0,
                firstMax=d["firstAudioMaxMs"],
                rtf=d["rtf"],
                rss=d["rssMaxMb"],
                synth=d["synthMs"],
                audio=d["audioMs"],
                errors=d.get("errors", "0"),
                llm=llm,
                music=music,
            )

engines = OrderedDict()
for key in rows:
    engines.setdefault(key[0], []).append(key)

for engine, keys in engines.items():
    print(f"\n### {engine}\n")
    print("| threads | pass | E2B | passage | load ms | first audio s0 ms | first audio max ms | RTF | peak RSS MB |")
    print("|---|---|---|---|---|---|---|---|---|")
    for key in keys:
        r = rows[key]
        p = "cold" if key[2] == 1 else "warm"
        print(f"| {key[1]} | {p} | {'yes' if r['llm'] == 'true' else ''} | {key[3]} | {r['load']} | {r['first0']} | {r['firstMax']} | {r['rtf']} | {r['rss']} |")

# Per-engine best (warm, all passages pooled) per thread count.
print("\n### Warm totals per thread count (three passages pooled)\n")
print("| engine | threads | E2B | synth ms | audio ms | RTF | first audio max ms | peak RSS MB |")
print("|---|---|---|---|---|---|---|---|")
pool = OrderedDict()
for key, r in rows.items():
    if key[2] != 2:
        continue
    k = (key[0], key[1], r["llm"])
    a = pool.setdefault(k, dict(synth=0, audio=0, fmax=0, rss=0))
    a["synth"] += int(r["synth"]); a["audio"] += int(r["audio"])
    if r["firstMax"] != "-": a["fmax"] = max(a["fmax"], int(r["firstMax"]))
    a["rss"] = max(a["rss"], int(r["rss"]))
for (e, t, llm), a in pool.items():
    rtf = a["synth"] / a["audio"] if a["audio"] else float("nan")
    print(f"| {e} | {t} | {'yes' if llm == 'true' else ''} | {a['synth']} | {a['audio']} | {rtf:.2f} | {a['fmax']} | {a['rss']} |")
