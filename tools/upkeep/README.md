# Upkeep

The upkeep loop keeps Granger's catalog current with as little of Neal's time as possible
(design: `docs/superpowers/specs/2026-11-09-upkeep-loop.md`). Step 1 is `upkeep.py check`, which changes nothing:

```sh
~/.venvs/granger-catalog/bin/python tools/upkeep/upkeep.py check          # ~15 min: a catalog build + every link
```

It writes `~/granger-upkeep/<date>/`:
- `report.md`: what to read.
- `findings.json`: the same findings, for an agent.
- `candidate.json`: the candidate catalog. It is unsigned and keeps the published pins of packs that aren't ready to move.

## Rules for an agent running the loop

1. Read `findings.json`. Work on **one finding at a time**, following its kind's playbook below and nothing else.
2. Never sign, upload a release, push, use the Mac GPU, touch a phone, or reply to a tester. Those are Neal's. When a
   playbook reaches one of them, stop and write what is ready and what Neal needs to do.
3. Every change goes on a new branch (`tools/dev/new-worktree.sh upkeep-<date> upkeep-<date>`), never on main.
4. A playbook is done only when its check passes. If the check fails, stop and report the output; don't improvise a fix.
5. Finish with a short summary: what you did, what each check said, and what is left for Neal.

## Finding kinds

| Kind | Group | Playbook |
|---|---|---|
| `snapshot-ready` | ready | apply-candidate |
| `pack-added` | ready | apply-candidate |
| `copy-changed` | review | review-copy |
| `snapshot-held-mirror` | waiting | none (a later run takes it once every mirror has the file) |
| `snapshot-held-vectors` | needs Neal | rebuild-vectors |
| `snapshot-held-timeindex` | needs Neal | rebuild-timeindex |
| `snapshot-forced` | needs Neal | rebuild-vectors / rebuild-timeindex (the old file is gone, so the pack moved anyway) |
| `derived-dropped` | needs Neal | rebuild-vectors / rebuild-timeindex |
| `pack-removed` | needs Neal | replace-or-drop |
| `dead-link` | needs Neal | replace-or-drop |
| `candidate-dead-link` | needs Neal | replace-or-drop |
| `budget-over` | needs Neal | budget-call |
| `repo-change` | review | review-repo-change |
| `build-failed` | needs Neal | fix-build |

## Playbooks

### apply-candidate

For all `snapshot-ready` and `pack-added` findings together, and only when the report has no `build-failed`,
`budget-over` or `candidate-dead-link` findings.
1. On a new branch, copy `candidate.json` over `catalog/catalog.json`, and copy `candidate-report.txt` over
   `catalog/kiwix-report.txt`.
2. Check: `~/.venvs/granger-catalog/bin/python tools/catalog-gen/catalog_gen.py check-links $PWD/catalog/catalog.json`
   ends with "all N links answer", and `cd tools/catalog-gen && ~/.venvs/granger-catalog/bin/python -m pytest -q`
   passes. (`:core:catalog` tests read the signature, so they run after Neal signs.)
3. Commit with the message `Catalog: Kiwix refresh <date> (unsigned)`, listing the moved and added packs from the
   report.
4. Stop. Neal signs, then `./gradlew :core:catalog:testDebugUnitTest`, merge and push.

### review-copy

Kiwix changed a pack's title or description. Copy the old and new text into the summary. If the new text reads worse
(wrong language, lower case, machine-sounding), propose a `kiwix.copy` entry in `tools/catalog-gen/curated.json`,
keyed by the pack's ZIM name, that restores the old text. Neal decides; apply the entry only when he says to.

### rebuild-vectors

Needs Neal's yes, because it uses the Mac GPU. With his yes: download the new ZIM into
`~/Developer/Granger/vectors-work/sources` (its URL is in `candidate.json`), then run `tools/vectors/build.py` with
`--only <pack id>` and `--catalog <candidate.json>` (BUILD.md in `tools/vectors`). The check is a new descriptor in
`tools/vectors/releases/` whose `sourceSha256` equals the candidate's `sha256`. Neal uploads the `.gvec`; the next
`check` then moves the pack.

### rebuild-timeindex

Run `tools/timeindex/build.py` for the new ZIM (its docstring has the arguments; it uses `~/.venvs/granger-pack`). The
check is a descriptor in `tools/timeindex/releases/` whose `sourceSha256` equals the candidate's `sha256`. Neal
uploads it.

### replace-or-drop

A link is dead or a pack has left Kiwix. List the pack, which presets and readiness lists name it
(`grep -n <id> tools/catalog-gen/curated.json tools/catalog-gen/pack_sets.json`), and any newer pack of the same
family in Kiwix. Propose a replacement or a removal. Neal decides.

### budget-call

A readiness tier is over its budget (`TIER_BUDGETS` in `catalog_gen.py`). Report the tier's size and the packs that grew
since the published catalog. Neal either accepts it (raise `TIER_BUDGETS`, with his words and the date in the comment)
or trims the tier in `curated.json`.

### review-repo-change

Parts of the catalog other than the packs differ from the published catalog, so something in the repo changed after
the last signing (books, presets, models, readiness). List the changed keys and the commits since the last
`Catalog:` commit (`git log --oneline -- catalog tools/catalog-gen tools/pack-builder/releases tools/vectors/releases`).
It is usually intended. Say so in the summary rather than undoing anything.

### fix-build

The candidate build stopped with a message (an unknown preset item, an unsafe id, a family missing a license). Quote
the message and the curated row it names. Don't edit anything until Neal agrees on the fix.
