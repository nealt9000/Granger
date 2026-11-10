#!/bin/zsh
# The weekly upkeep check (launchd runs this; see schedule.sh). It checks the catalog as published, not whatever a
# working tree holds: it fetches origin, exports $UPKEEP_REF (default origin/main) to a temp dir, and runs
# `upkeep.py check` from there. Results go to ~/granger-upkeep/<date>/ (run.log beside report.md), and a macOS
# notification gives the counts. Changes nothing in the repo besides refreshing origin's refs.
set -u
REPO=${0:A:h:h:h}
REF=${UPKEEP_REF:-origin/main}
PY=${UPKEEP_PYTHON:-$HOME/.venvs/granger-catalog/bin/python}
OUT=$HOME/granger-upkeep/$(date +%F)
mkdir -p "$OUT"
LOG=$OUT/run.log

notify() { /usr/bin/osascript -e "display notification \"$1\" with title \"Granger upkeep\"" >/dev/null 2>&1 }

{
  echo "== $(date) weekly check of $REF from $REPO"
  git -C "$REPO" fetch --quiet origin || echo "fetch failed; using the $REF already here"
  echo "$REF is $(git -C "$REPO" log --oneline -1 "$REF")"
  TMP=$(mktemp -d -t granger-upkeep)
  if ! git -C "$REPO" archive "$REF" tools catalog | tar -x -C "$TMP"; then
    echo "could not export $REF"; rm -rf "$TMP"; exit 1
  fi
  if [[ ! -f $TMP/tools/upkeep/upkeep.py ]]; then
    echo "$REF has no tools/upkeep/upkeep.py yet (not pushed?)"; rm -rf "$TMP"; exit 1
  fi
  "$PY" "$TMP/tools/upkeep/upkeep.py" check --out "$OUT"
  STATUS=$?
  rm -rf "$TMP"
  echo "== $(date) done, exit $STATUS"
  exit $STATUS
} >>"$LOG" 2>&1
STATUS=$?

if [[ $STATUS -eq 0 ]]; then
  notify "$(tail -2 "$LOG" | head -1 | sed 's|.*report.md: ||')"
else
  notify "The check failed (exit $STATUS). See $LOG"
fi
exit $STATUS
