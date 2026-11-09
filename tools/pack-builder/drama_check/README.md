# Drama check

Measures how the Classics' plays (and the text-file and scripture books Plan GC2 repaired) are structured in
the built EPUBs: who speaks, what is a direction, where an act starts. It grew out of the 2026-11-09
formatting audit (`docs/superpowers/spikes/2026-11-09-classics-round-2/formatting-audit.md`, §5) and sits next
to `verse_check`, whose harness (Readium CSS 3.4.0, Noto Serif, headless Chrome) its render checks reuse.

## Running it

Build the books first (`pack_builder.py build-epubs ... --out-dir DIR`, or `--only slug,slug`), then:

```sh
cd tools/pack-builder
PY=~/.venvs/granger-pack/bin/python
$PY drama_check/static.py DIR                 # every drama and libretti row, plus the text-file books
$PY drama_check/static.py DIR --all           # also gloss, stranded targets and hidden starts in every book
$PY drama_check/static.py DIR hamlet --debug  # print each stray label, missed direction, act line, hard wrap
$PY drama_check/render.py DIR                 # speaker weight and order, direction italics (needs Chrome)
```

`static.py` reads the EPUBs only; its measures and bars are in its docstring (speech coverage 98%, 0 gloss
misfires in plays, directions missed 2%, 0 act or scene labels outside headings, 0 hard wraps from a `<pre>`,
0 stranded link targets, 0 hidden paragraph starts). Names seen once are listed for review, not counted.
`render.py` needs the verse_check setup (`verse_check/harness.py setup`). Both exit 1 on a failure.

The unit tests (`tests/test_drama_check.py`) cover the static measures.
