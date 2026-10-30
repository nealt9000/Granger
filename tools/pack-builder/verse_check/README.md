# Verse check

Measures how the Granger Classics' verse reads on a phone: whether a line too long for the screen wraps with a
hanging indent (good) or flush to the left edge (it then reads as a new line), and whether Readium's
paragraph alignment override took away a work's centring or right alignment. It renders each built EPUB the
way the app's reader does, in headless Chrome, and counts.

It grew out of the 2026-10-30 verse audit (`docs/superpowers/spikes/2026-10-30-classics-verse-audit.md`; the
audit's own scripts stay in `docs/superpowers/spikes/2026-10-30-classics-audit/` as the record).

## What it renders

- **Readium CSS 3.4.0**, the version the app ships, taken from the `readium-navigator` AAR in the Gradle cache:
  `ReadiumCSS-before.css`, then the book's `book.css`, then `ReadiumCSS-after.css`.
- **The root style the Kotlin toolkit writes** with publisher styles off (`ReadingPrefs.kt`): advanced
  settings on, font override on, `textAlign: start`, line height 1.5, page margins 1. Scroll mode, so
  Readium's columns don't get in the way of measuring.
- **Noto Serif**, Android's serif, as web fonts from Google Fonts, under its own family name so a copy
  installed on the Mac can't stand in.
- **A phone-width iframe** (390 or 360 px) in headless Chrome. The pages are served from a local HTTP server
  on a free port (Chrome won't load web fonts into a `file://` iframe); the scripts start and stop it.

## Setup

1. **Chrome** at `/Applications/Google Chrome.app` (or set `$CHROME` to its binary).
2. **The pack-builder venv**: `~/.venvs/granger-pack` with `requirements.txt` (bs4, lxml).
3. **Readium CSS and Noto Serif**, once:

   ```sh
   cd tools/pack-builder
   ~/.venvs/granger-pack/bin/python verse_check/harness.py setup
   ```

   This copies `assets/readium/readium-css/` out of
   `~/.gradle/caches/modules-2/files-2.1/org.readium.kotlin-toolkit/readium-navigator/3.4.0/*/readium-navigator-3.4.0.aar`
   (build the app once if Gradle hasn't fetched it) and downloads Noto Serif (regular, bold, italic) from
   Google Fonts. Both go to `~/.cache/granger/verse-check/` (`$VERSE_CHECK_HOME` to put them elsewhere).
   When the app moves to a new Readium, change `READIUM_VERSION` in `harness.py` and run setup again.

## Running it

Build the books first (`pack_builder.py build-epubs ... --out-dir DIR`), then:

```sh
cd tools/pack-builder
PY=~/.venvs/granger-pack/bin/python
$PY verse_check/render.py DIR 390 1.0  --out render-390-1.0.json
$PY verse_check/render.py DIR 360 1.0  --out render-360-1.0.json
$PY verse_check/render.py DIR 390 1.25 --out render-390-1.25.json
$PY verse_check/compare.py render-390-1.0.json render-360-1.0.json render-390-1.25.json
```

- With no slugs after the scale, `render.py` measures every book whose manifest row reads as verse (poetry,
  drama, libretti and scripture, plus any row with `"verse": true`), Gutenberg and eBible rows alike. Name
  slugs to measure only those.
- **Pass** (`compare.py`): no book with at least 100 verse lines wraps flush on more than 2% of them, and
  no paragraph loses its alignment. `--limit`, `--min-lines` and `--except SLUG` change that. It exits 1 on
  a failure. A full run over ~140 books takes a few minutes per setting.
- `shot.py BOOK.epub PAGE OUT.png --find "some words"` takes a 390 × 844 screenshot of one page, scrolled
  to the words, for looking at by eye.

## What counts as a verse line

- a `span.gv-l`: the builder's verse pass (`verse.py`) and the eBible converter (`usfm.py`);
- each `<br>`-separated line in a block whose lines average at most 90 characters;
- one short element per line inside a poem wrapper (`div.stanza > p`, `div.poem > div`, `p.line`, ...);
- a `<pre>`, counted by its source lines; each rendered row past those counts as a flush wrap.

A wrapped line hangs when its second row starts at least 8 px right of its first row or of its block's
left edge.

The unit tests (`tests/test_verse_check.py`) cover the line marking and the pass rule only; rendering needs
Chrome, so it isn't part of the pytest run.
