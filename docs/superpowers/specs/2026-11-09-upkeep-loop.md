# Upkeep loop: design notes (draft, 2026-11-09)

Neal, 2026-11-09: find "a sustainable and automated way to maintain the kiwix packs long-term, along with any other
upkeep the app will need", run by "a free agent like OpenCode's free tier or a local model on the Mac", with the loop
eventually covering all of the app's upkeep. Not solved here; these notes come from doing one Kiwix refresh by hand.

## What the refresh taught

The first refresh (2026-11-09) found three ways that "take Kiwix's latest" breaks things without any error:

1. **Mirror lag.** download.kiwix.org redirects to a mirror. Files published 2–7 days earlier were 404 on some mirrors
   (6 of 10 new files). A catalog built that day would have shipped downloads that fail on some phones.
2. **Silently dropped derived data.** Search vectors (48 packs) and time indexes (39) are tied to one exact file
   (sha256). When a pack moves to a new snapshot, they disappear from the catalog, and the only trace is a line in the
   build output (based.cooking lost its vectors). A Wikipedia snapshot bump would drop dozens of them at once.
3. **Budget drift.** English Wiktionary nopic went from 9.2 to 14.8 GB, which takes the Comprehensive tier to 105 GB,
   over its 103 GB target. A test caught this; a human has to decide what to do. (Neal, 2026-11-09: accept the bigger tier; the target is now 107 GB.)

Smaller lessons:
- Kiwix's own copy drifts (the Spanish mini Wikipedia's description); `kiwix.copy` pins it.
- A new ZIM can change its HTML. The Wiktionary scraper added inline scripts and a categories footer. A check reading
  three entries from each file over HTTP ranges (`zim_probe.py entry`), passed through `DictionaryEntry`, showed the
  cards unchanged. It is cheap enough to run on every refresh of a pack the app parses (Wiktionary, devdocs).
- `library.kiwix.org` now puts a bot check in front of its pages, so probes go to the ZIM files themselves.

**Built from this:** `catalog_gen.py build --previous <published catalog>` holds a pack at its published snapshot when
its new file isn't on every mirror yet, or when it would lose its vectors or time index. Flavours of one ZIM move
together. The report says why each pack is held. The loop's default is therefore safe: each run takes only what is
ready, and everything else waits for a later run or for a rebuild.

## Principles for the loop

- **Scripts decide; the agent does one small, checkable job.** In line with the small-model principles
  (2026-11-07): deterministic tools produce a findings file with a fixed set of kinds. The agent picks one finding,
  runs that finding's playbook, and must leave a check passing (tests green, links answering, a diff limited to known
  fields). A free-tier or local model can do that reliably; it can't be trusted to judge an open-ended diff.
- **Safe by default.** Every step either holds the previous state or opens a change for review. Nothing destructive
  happens on its own.
- **Human gates stay human:** signing the catalog, uploading releases, pushing to main, using the Mac GPU, anything on
  the phones, budget calls (like Wiktionary's size), and replies to testers.
- **One report per run**, short enough to read on a phone: what moved, what is held and why, what needs Neal.

## Upkeep inventory

| Area | Cadence | Automatable now | Gate |
|---|---|---|---|
| Kiwix packs: new snapshots | weekly | `build --previous` + `check-links` + tests | sign + push (Neal) |
| Dead links (withdrawn packs, e.g. WikEM) | weekly | `check-links` on the published catalog | decide replacement (Neal) |
| Pack vectors after a snapshot moves | when held | `tools/vectors/build.py` for the held packs | Mac GPU (ask), upload (Neal) |
| Time indexes after a Wikipedia move | when held | `tools/timeindex/build.py` | upload (Neal) |
| Pack contents / Wikipedia sets (`zim_probe.py probe`, `titles`) | with refreshes | yes | review diff |
| HTML shape of parsed packs (Wiktionary, devdocs) | with refreshes | `zim_probe.py entry` + parser fixture | fix code if changed |
| Tier budgets (Efficient < 18 GB, Comprehensive < 107 GB) | with refreshes | the test exists | budget call (Neal) |
| Classics (Gutenberg/SE/Wikisource corrections) | quarterly at most | pack-builder rebuild | release upload (Neal) |
| Models, voices | rare, by decision | no | Neal |
| App dependencies, Android SDK / Play target-API deadlines | monthly check | report only | Neal |
| Tester feedback mailbox | daily | `tools/feedback/pull.py` + triage summary | replies (Neal) |
| Site (opengranger.org) links | monthly | link check | Neal |

## Shape (proposal, to settle with Neal)

```
tools/upkeep/upkeep.py check   → upkeep/findings.json + upkeep/report.md
tools/upkeep/upkeep.py run KIND → runs one finding's playbook on a branch, leaves its check result
```

Finding kinds to start with: `snapshot-ready` (moved packs, already safe), `snapshot-held-vectors`,
`snapshot-held-timeindex`, `snapshot-held-mirror` (wait; no action), `dead-link`, `budget-over`, `html-shape-changed`,
`feedback-new`. Each kind has a playbook that is a script call plus a check. The agent writes the commit message and
the report summary, and does nothing else.

**Where it runs.** On the Mac, from launchd (weekly, plus daily for feedback). The Mac is where the signing key, GPU
and venvs live; a cloud runner could only do the link checks. The agent is whatever is cheapest that day (OpenCode
free tier, or a local model through its OpenAI-compatible endpoint). The scripts don't depend on which agent runs.

**Neal's part each week:** read the report; approve signing and pushing; say yes or no to GPU rebuilds; make budget
calls. Over time, `snapshot-ready` with no other findings could be signed automatically. That needs a decision about
the key's custody first.

## Next steps

1. Use `build --previous` for every refresh (done in this branch).
2. ~~Write `upkeep.py check`~~ done 2026-11-09: `tools/upkeep/upkeep.py check` (findings.json, report.md, candidate.json; playbooks in `tools/upkeep/README.md`). First real run: 13 min, 1 needs Neal (based.cooking vectors), 5 waiting on mirrors.
3. ~~Run it weekly~~ done 2026-11-10: launchd agent `org.opengranger.upkeep`, Mondays 07:00 (`tools/upkeep/schedule.sh`); `weekly.sh` checks origin/main from a temp export and posts a notification with the counts.
4. Add the playbooks for vectors and time indexes (GPU-gated), then feedback triage.
5. Do a dry run with a local model, then with OpenCode's free tier, on a real week's findings.
