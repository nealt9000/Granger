"""Loads results.jsonl. A bad line stops with the file and line number; a truncated LAST line (a run cut off
mid-write) is skipped with a warning."""
import json
import sys
from pathlib import Path

OUTCOMES = ("answered", "declined", "unverified", "failed")


def load_results(path):
    p = Path(path)
    lines = [(n, l) for n, l in enumerate(p.read_text(encoding="utf-8").splitlines(), 1) if l.strip()]
    out = []
    for i, (n, line) in enumerate(lines):
        where = "%s:%d" % (p, n)
        try:
            r = json.loads(line)
        except ValueError as ex:
            if i == len(lines) - 1:
                print("warning: %s: skipping truncated last line" % where, file=sys.stderr)
                continue
            raise SystemExit("%s: not valid JSON (%s)" % (where, ex))
        if not isinstance(r, dict) or "id" not in r or r.get("outcome") not in OUTCOMES:
            raise SystemExit("%s: record needs an id and an outcome of %s" % (where, "/".join(OUTCOMES)))
        out.append(r)
    return out
