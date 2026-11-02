"""Plan A0: scores the replay lines (`Asr: replay {...}`) in a logcat dump or a pulled asr-spike-*.log.

    python3 tools/asr/score.py run.log [more.log ...]

One table per biasing condition: word error rate on questions and commands, name hits (exact, and with spaces
ignored, which a title matcher after recognition would also catch), and latency from the end of the audio.
"""
import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

HERE = Path(__file__).resolve().parent

ONES = "zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split()
TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()


def cardinal(n: int) -> str:
    if n < 20:
        return ONES[n]
    if n < 100:
        return TENS[n // 10] + ("" if n % 10 == 0 else " " + ONES[n % 10])
    if n < 1000:
        return ONES[n // 100] + " hundred" + ("" if n % 100 == 0 else " " + cardinal(n % 100))
    if n < 1_000_000:
        return cardinal(n // 1000) + " thousand" + ("" if n % 1000 == 0 else " " + cardinal(n % 1000))
    return str(n)


def year(n: int) -> str:
    """1066 → 'ten sixty six'; 1900 → 'nineteen hundred'; 2005 → 'two thousand five'."""
    if 1000 <= n <= 1999 or 2010 <= n <= 2099:
        hi, lo = divmod(n, 100)
        return cardinal(hi) + (" hundred" if lo == 0 else " oh " + ONES[lo] if lo < 10 else " " + cardinal(lo))
    return cardinal(n)


def words(text: str) -> list[str]:
    """Lowercase, apostrophes dropped inside words, other punctuation as spaces."""
    t = text.lower().replace("’", "'")
    t = re.sub(r"(\w)'(\w)", r"\1\2", t)
    return re.sub(r"[^\w\s]", " ", t).split()


def edits(ref: list[str], hyp: list[str]) -> int:
    prev = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        cur = [i]
        for j, h in enumerate(hyp, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (r != h)))
        prev = cur
    return prev[-1]


def errors(ref_text: str, hyp_text: str) -> tuple[int, int]:
    """(word errors, reference words); a number in either text may be read as a cardinal or a year, whichever fits."""
    ref, hyp = words(ref_text), words(hyp_text)

    def variants(ws: list[str]) -> list[list[str]]:
        out = [[]]
        for w in ws:
            forms = [w] if not w.isdigit() else list(dict.fromkeys([cardinal(int(w)), year(int(w))]))
            out = [o + f.split() for o in out for f in forms][:16]
        return out

    best = min(edits(r, h) for r in variants(ref) for h in variants(hyp))
    return best, len(ref)


def name_hit(name: str, accept: list[str], hyp_text: str) -> tuple[bool, bool]:
    """(exact, spaces ignored): the name or an accepted form appears whole in the transcript."""
    hyp = " " + " ".join(words(hyp_text)) + " "
    forms = [" " + " ".join(words(f)) + " " for f in [name, *accept]]
    exact = any(f in hyp for f in forms)
    loose = exact or any(f.replace(" ", "") in hyp.replace(" ", "") for f in forms)
    return exact, loose


def transcript(row: dict) -> str:
    segs = [s for s in row.get("segments") or [] if s]
    if segs:
        return " ".join(s.strip() for s in segs)
    if row.get("results"):
        return row["results"][0]
    return row.get("lastPartial") or ""


def rows(paths: list[str]) -> list[dict]:
    out = []
    for p in paths:
        for line in Path(p).read_text(errors="replace").splitlines():
            m = re.search(r"Asr: replay (\{.*\})\s*$", line)
            if m:
                out.append(json.loads(m.group(1)))
    return out


def pct(n: int, d: int) -> str:
    return f"{100 * n / d:.1f}%" if d else "—"


def main() -> None:
    phrases = {p["id"]: p for p in json.loads((HERE / "phrases.json").read_text())["phrases"]}
    by_cond = defaultdict(list)
    for r in rows(sys.argv[1:]):
        pid = r.get("file", "").removesuffix(".wav")
        if pid in phrases:
            by_cond[r.get("biasName", "none")].append((phrases[pid], r))
    for cond, items in by_cond.items():
        err = defaultdict(lambda: [0, 0])
        hits, loose, n_names, lat, fails = 0, 0, 0, [], []
        for p, r in items:
            hyp = transcript(r)
            if r.get("error"):
                fails.append(f"{p['id']} {r['error']}")
            e, n = errors(p["text"], hyp)
            err[p["kind"]][0] += e
            err[p["kind"]][1] += n
            if p["kind"] == "name":
                n_names += 1
                x, l = name_hit(p["name"], p.get("accept", []), hyp)
                hits += x
                loose += l
                if not x:
                    print(f"  [{cond}] miss {p['id']}: {p['name']!r} → {hyp!r}")
            elif e:
                print(f"  [{cond}] {p['id']} ({e}): {p['text']!r} → {hyp!r}")
            if "finalAfterAudioMs" in r:
                lat.append(r["finalAfterAudioMs"])
        print(f"\n## bias={cond} ({len(items)} phrases)")
        for kind in ("question", "command", "name"):
            if err[kind][1]:
                print(f"  WER {kind}: {pct(*err[kind])} ({err[kind][0]}/{err[kind][1]})")
        if n_names:
            print(f"  names: exact {hits}/{n_names} ({pct(hits, n_names)}), spaces ignored {loose}/{n_names}")
        if lat:
            q = statistics.quantiles(lat, n=10) if len(lat) > 1 else [lat[0]] * 9
            print(f"  final after audio end: median {statistics.median(lat):.0f} ms, p90 {q[8]:.0f} ms")
        if fails:
            print(f"  errors: {', '.join(fails)}")
        print()


if __name__ == "__main__":
    main()
