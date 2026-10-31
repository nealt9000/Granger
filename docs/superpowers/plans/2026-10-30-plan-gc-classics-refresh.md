# Plan GC: Granger Classics refresh — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a new Classics release, `classics-<date>`, that:
- **looks like Granger:** covers in the new brand;
- **reads well on a phone:** verse lines hang when they wrap, keep their indents and alignment, and use the reader's font;
- **holds better translations:** added beside the old ones, plus a few new works.

Neal, 2026-10-30: "Let's do a round of improvements to the Granger Classics book collection: rebranding update; titles with more updated translations that can be included and/or updated (such as the Bible and Meditations); formatting audit/clean-up pass (primarily with poetic works)."

**Settled with Neal, 2026-10-30:**
- **Covers: direction D.**
  - The layout: the section name in spaced caps at the top, the title in Spectral, a lantern rule, the author in italic, then "translated by …".
  - At the foot: the owl in the G above GRANGER CLASSICS in spaced caps.
  - Each section has its own ground colour:
    - scripture Leather `#5B3322`
    - philosophy Dusk `#354059`
    - poetry Enamel `#2E4A45`
    - drama `#6B2A2A`
    - libretti `#4A3A5A`
    - novels Gunmetal `#24292A`
  - Boards and the draft scripts are in `docs/superpowers/brand/2026-10-30-classics-covers/`.
- **Add versions rather than replace.** The one replacement is Meditations: George Long (#15877) replaces #2680, which is Meric Casaubon's 1634 translation, not Long's.
- **What gets added:**
  - the Gutenberg translations (Decision 6);
  - the modern Bibles from eBible.org: WEB with the deuterocanon, BSB, and JPS 1917;
  - the 1930 works that became public domain in the USA in 2026.
- **Not this round:** Pickthall's Koran.
- **Saved reading places may shift** when the new release replaces a book. That's acceptable for now.

**Research behind this plan** (committed with it):
- `docs/superpowers/spikes/2026-10-30-classics-verse-audit.md`: the verse audit, with measured wrap rates, rules R1–R9 and per-book notes. Its scripts and screenshot pairs are in `docs/superpowers/spikes/2026-10-30-classics-audit/`. `fixproto.py` is the working prototype of R1–R5.
- `docs/superpowers/spikes/2026-10-30-classics-translations.md`: the translations research. Every Gutenberg id was checked against its RDF; non-Gutenberg licences were checked on the source page.

**Out of this plan:**
- Wikisource and Perseus importers: Ross's Ethics, Haines's Meditations, Brookes More's Metamorphoses, T. C. Williams's Aeneid.
- Humphries's Aeneid, which is post-1930.
- Spanish Clásicos (L0).
- Any app change. The app already shows a newer book as an update, keyed by its unchanged `gc-<slug>` id.

**Tech:**
- **Python:** `tools/pack-builder`, in the venv `~/.venvs/granger-pack` (`pip install -r tools/pack-builder/requirements.txt fonttools numpy`).
- **Vectors:** `tools/vectors` (`~/.venvs/granger-vectors`, MPS).
- **Catalog signing:** `~/.venvs/granger-catalog`, with the key at `~/.config/granger/catalog-signing.key`.
- **Checks:** epubcheck 5.2.1. The audit's `render.py` needs headless Chrome plus Readium CSS 3.4.0 from the app's AAR.

**Worktree:** `.claude/worktrees/gc`, branch `gc-classics-refresh`, off main `7fea1910`.

**Order with other branches:**
- `gb-gutenberg` also changes `catalog/catalog.json`. Task 12 (catalog) waits until gb has merged, then regenerates the catalog from main.
- L1 doesn't touch `tools/pack-builder`.

## What the builder does now (surveyed 2026-10-30, main `7fea1910`)

- **Pipeline:** `pack_builder.py build-epubs` runs, for each manifest work:
  - `GutenbergSource.html` (Gutenberg only, cached in `~/.cache/granger/gutenberg/`, which is empty on this Mac);
  - `gutenberg.clean`;
  - `chapters.split`;
  - `epubpack.write_epub`, with `covers.cover_jpeg`.
- **Covers:** `covers.py` draws the retired neural-flame mark (`assets/flame-mark.png`) in `#E8601C` on `#0E0E0E`, in EB Garamond, with the author only.
- **CSS:** `epubpack.scrub_css` drops only colour, background, font-family and page-number rules. The rest of the work's CSS follows `BOOK_CSS` and wins.
  - The app turns publisher styles off, so Readium already neutralises body margins.
  - Element margins still apply, e.g. `p.poem { margin-left: 30% }`.
  - Readium forces every `p` to the reader's alignment, which removes the centring from 8,926 paragraphs.
  - Readium's font override skips `pre`.
- **Splitting:** `chapters._flatten` and `_by_size` discard wrapper elements, so rules like `div.poem p` stop matching. In Faerie Queene the indents survive in only 9 of 641 stanzas.
- **Measured** (106 verse books, 390 px, 100%): 40 books wrap flush on at least 25% of their lines, and only 0.3% of wrapped lines hang. With R1–R5 every book but one goes to zero. The exception is Keats's Poems 1817.
- **Vectors:** each `gc-*.gvec` was built from the 2026-10-17 EPUB text (`vectors-2026-10-23` release) and listed per book in `catalog.json`. A rebuilt book needs its vectors rebuilt.
- **Metadata errors:**
  - #2680 is Casaubon 1634.
  - #21765 holds Metamorphoses I–VII only.
  - #8438 Nicomachean Ethics should credit D. P. Chase.
  - #7998 The Frogs should credit B. B. Rogers.
  - #2130 Utopia is Burnet's 1684 translation, not a 1551 edition.
  - #2383 Canterbury Tales should credit Purves as editor.
  - #19942 Candide is an anonymous 1918 translation.

## Decisions

1. **Verse rules R1–R8 from the audit go into the builder.**
   - R9 (speaker names in small caps) goes in only if it renders cleanly in Hamlet and Medea; otherwise it waits.
   - A manifest field `verse` (true or false) overrides the section default. The default is on for poetry, drama, libretti and scripture, off for philosophy and novels.
   - A manifest field `lineNumbers` (`"margin"` or `"drop"`) sets how line numbers are shown. The default is `"margin"`.
   - A manifest field `titleOverrides` sets nav titles for a work.
2. **Drop caps are unwrapped inside verse.** The floated initial breaks the first line, as seen in Paradise Lost.
3. **Keats's Poems 1817 gets a generic flattening rule.**
   - The rule unwraps `span.xhtml_*` and flattens single-column layout tables.
   - If the book still wraps flush on more than 10% of its lines afterwards, choose another Gutenberg Keats edition and record why.
4. **Covers follow direction D, as listed above.**
   - The author and translator lines wrap to at most 2 lines each.
   - The section label comes from the manifest section title.
   - EB Garamond and `flame-mark.png` are removed.
   - Spectral (OFL, already in `app/src/main/res/font/` and `docs/superpowers/brand/fonts/`) is copied into `tools/pack-builder/assets/fonts/`.
   - The owl is rasterised from `docs/superpowers/brand/2026-10-27-owl-mark/granger-mark-owl.svg`. The `render_svg.mask` code moves into `covers.py` so the builder has no dependency on `docs/`.
5. **A colophon page at the back of every book.**
   - It shows the owl, "Granger Classics", the title, author, translator and first publication year, and "From Project Gutenberg ebook #N" (or the eBible source).
   - It also carries "Public domain in the USA." and the tagline *The world's knowledge. Yours to keep.*
   - It sits in the spine after the text, so a book still opens on its text.
6. **Additions from Gutenberg.** Each gets a manifest row, and its RDF is checked by `validate`:
   - **Meditations:** slug `meditations` is kept and #2680 becomes #15877, translator George Long, 1862. Keeping the slug makes the change arrive as an update to the installed book.
   - **Metamorphoses:** the current row becomes "Metamorphoses, Books I–VII" and a new row "Metamorphoses, Books VIII–XV" (#26073) is added. This matches how Poe and Malory are shipped in volumes.
   - **Aeneid:** Mackail's prose (#22456), added beside Dryden.
   - **Divine Comedy:** Norton's prose, Hell, Purgatory and Paradise (#1995, #1996, #1997), added beside Longfellow.
   - **The Mahabharata:** Romesh Dutt's condensed verse, 1899 (#19630), filed under poetry.
   - **The Tale of Genji:** Waley's volumes 2–4 (#67111, #75852, #77408). The current #66057 becomes "Volume 1".
   - **Proust:** Within a Budding Grove (#63532) and The Guermantes Way (#73425).
   - **The Douay-Rheims Bible:** #8300 per the research. Confirm the id against its RDF.
   - **1930 works:**
     - Freud, *Civilization and Its Discontents* (#78221)
     - Russell, *The Conquest of Happiness* (#77894)
     - Hammett, *The Maltese Falcon* (#77600)
     - Waugh, *Vile Bodies* (#77900)
     - Christie, *The Murder at the Vicarage* (#78220)
     - Maugham, *Cakes and Ale* (#78723)
     - Wilder, *The Woman of Andros* (#78024)
     - Priestley, *Angel Pavement* (#78329)
     - Sayers, *Strong Poison* (#78157)
     - Eliot, *Ash-Wednesday* (#78023)
     - Each work's 1930 publication year is confirmed from its title page or RDF before it goes in. Any year that can't be confirmed is left out.
   - **Description:** the collection description becomes "Scripture, philosophy, drama, libretti and novels to 1930".
   - **Metadata fixes:** all the errors listed above are corrected.
7. **A rights guard.**
   - `validate` fails any Gutenberg work whose RDF `dcterms:rights` isn't "Public domain in the USA."
   - An eBible work must carry a `licence` field in the manifest naming its public-domain dedication and the URL it came from.
8. **Modern Bibles come from eBible.org USFM, through a new source and converter.**
   - **Source:** `EBibleSource` fetches `https://ebible.org/Scriptures/<id>_usfm.zip`, caches it like Gutenberg and checks the licence. The exact URL is confirmed in Task 9.
   - **Manifest rows:** `"source": "ebible"` with `"ebible": "<id>"`.
   - **Converter:** `usfm.py` makes one chapter page per biblical book.
     - The page title is the book's `\h` name, and each `\c` becomes an `h3` reading "Chapter N".
     - Verse numbers become small superscripts.
     - `\q1`/`\q2` poetry becomes R1 verse lines, so the Psalms hang and indent.
     - `\s` section headings become `h4`.
   - **Dropped:** footnotes (`\f`) and cross-references (`\x`). Footnotes may come back as endnotes later.
   - **Books:**
     - "World English Bible (with the Deuterocanon)", from `engwebu`. This uses "LORD" for the divine name, like the KJV. Neal may prefer `eng-web`, which uses "Yahweh"; switching is a one-line change.
     - "Berean Standard Bible", from `engbsb`.
     - "The Holy Scriptures (JPS 1917)", from `engjps`.
   - **Order:** the books follow each translation's canonical order. In WEB the deuterocanonical books keep the place eBible gives them.
   - **Naming:** the text is not changed, so the names WEB and BSB may be used.
9. **One release replaces the whole collection.**
   - It is `classics-<build date>`, every book is rebuilt, and every Classics `.gvec` is rebuilt.
   - The old `classics-2026-10-17` release and its descriptor stay in place, unreferenced, as was done for the ZIM.

## Tasks

### Part 1: the builder reads verse well

- [x] **Task 1: leftovers and the rights guard** (R7, Decision 7)
  - `gutenberg.clean` and `epubpack._prepare` remove empty `header`/`footer` boilerplate, childless and textless `div`/`p` (their ids are kept as spans), fixed `width` in inline styles on non-media elements, and a Transcriber's Note whose only subject is the cover art.
  - The manifest gains `verse`, `lineNumbers`, `titleOverrides`, `source` and `licence`; `validate` checks RDF rights.
  - Tests are added in `tests/test_gutenberg.py`, `test_epubpack.py` and `test_pack_builder.py`. One fixture is a copyrighted RDF, which must fail validation.
  - **Done 2026-10-30. As built:**
    - `gutenberg.clean` drops the empty `pg-boilerplate` shells, empty `div`/`p` (innermost first, so an emptied figure wrapper goes too; text of only spaces or nbsp counts as empty; blocks directly in a list or table are left alone) and the cover-art note. A note goes only when it is in a box of at most 400 characters and every sentence after its heading mentions cover art/image or the public domain; "Obvious errors corrected. The cover image…" stays. `width` in inline styles is dropped in `epubpack._fix_attributes`, next to the `width` attribute.
    - `titleOverrides` maps a page number as a string (`"3"` is `c003.xhtml`) to its contents title. Task 1 only loads and checks it; Task 5 applies it.
    - `gutenberg` is optional on a row whose `source` isn't `gutenberg`. Such a row skips the RDF checks and must carry a `licence` that says "public domain" and holds an `https://` URL. `WorkSpec.reads_as_verse` gives the verse default (poetry, drama, libretti, scripture).
    - The rights check is now exact ("Public domain in the USA.", whitespace-normalised) rather than a prefix match.
    - `validate` on the current 347-work manifest, with every RDF fetched into the cache, gives 0 errors under the exact check.
- [x] **Task 2: splitting keeps wrappers** (R6)
  - When `_flatten`/`_by_size` open a wrapper, each run of its children is re-wrapped in a shallow copy with the same tag and class and no id.
  - Test with a Faerie Queene-shaped fixture (`div.poem > div.stanza > p.i2`) split across two pages: `deadrules.py` must report the indent rules as live.
  - **Done 2026-10-30. As built:**
    - The copy keeps all the wrapper's attributes (class, `lang`, `style`), not only the class. The first copy also keeps the id, so links to the wrapper still land; later copies have none.
    - `_flatten` starts a new run at each child holding a split heading, so the heading stays inside its wrapper copy. `_by_size` tracks the chain of wrappers open on the current page and counts the copies' tags against the page limit.
    - Checked on real books with the audit's dead-rule test. Faerie Queene (#15272): `.poem p` was live on 35 of 8,249 lines, `.poem p.i2` on 9 of 4,343, `.poem .stanza` on 9 of 641 and `.poem p.i20` on 0 of 619. All are now live everywhere. The rules still "dead" (`.poem p.i16` 13/37, `.poem p.i4` 0/19, `.noflo p.i2` 8/4,343) match exactly what they match in the source HTML. Same 31 pages, largest page 142 KB, no duplicate ids. Paradise Lost and Hamlet are unchanged apart from Task 1's leftovers.
- [x] **Task 3: CSS fixes** (R3, R4, R5)
  - `scrub_css` clamps percentage side margins and paddings above 5% to `min(X%, 2em)`, and em indents above 6em to `min(Xem, 30%)`.
  - It writes `:root:root:root:root <sel> { text-align: X !important }` for each centre, right or end rule that targets a `p` or a bare class, and adds `!important` to inline `text-align` on a `p`.
  - It replaces absolute or fixed positioning with a right float in small, faint type. With `lineNumbers: "drop"`, the line-number spans are removed instead.
  - Unit tests run against the exact rules from Paradise Lost, Hamlet and Faerie Queene that the audit found.
  - **Done 2026-10-30. As built:**
    - The em cap applies to side margins and paddings (longhand and the side positions of `margin`/`padding` shorthands), not to `text-indent`. `!important` on a clamped value is kept.
    - The boost rules go after all the work's rules. The `!important` on inline `text-align` is added in `_fix_attributes`.
    - A positioned rule loses `position`, its offsets and any `float`/`font-size`/`opacity`/`margin-left` of its own, then gets the margin-note declarations, so nothing is stated twice.
    - Line-number classes are `linenum`, `linenumber`, `lineno`, `line-number` and `linenr` (`epubpack.LINE_NUMBER`). With `"drop"`, their rules go from the CSS and the spans go from the pages, keeping their ids as page numbers do. `zimpack.Work` gains `line_numbers` (EPUB only), set from the manifest in `pack_builder._work`.
    - Checked on real books (Readium CSS 3.4.0 from the AAR, Chrome, 390 px iframe, Georgia):
      - Hamlet: 0 → 70 centred `p.scenedesc` and 0 → 115 right-aligned `p.right` (`[Exit.]`).
      - Paradise Lost: `p.poem` is 428 px wide instead of 322 at a 500 px window. The flush wraps and the drop cap remain, which is Task 4's job.
      - Faerie Queene: the 1,108 `.linenum` go from `position: absolute` over the words to small right-floated numbers. With `"drop"` they're gone, 0 left. The `.poem` hanging indent and `i2` indents show.
    - epubcheck isn't installed on this Mac, so `min()` in `book.css` hasn't been through epubcheck yet; Task 10 runs it.
    - **For Task 4/6:** in Faerie Queene, `.poem`'s clamped 2em on both sides plus the 3em hang leaves about 270 px for verse at 390 px, so many lines still wrap (they hang). Task 4's `p.gv` margin reset doesn't cover this, because these lines aren't `<br>` blocks. Consider clamping `.poem`'s right margin to 0 or the 2em cap lower if Task 6 measures it as a problem.
- [x] **Task 4: verse blocks** (R1, R2, Decision 2, and R9 if it passes)
  - Port `fixproto.py` into `epubpack._prepare`, gated on the work's `verse` setting, and append the audit's verse CSS (§5) to `BOOK_CSS`.
  - Tests cover:
    - `<br>` lines becoming `gv-l`, and stanza gaps;
    - `&nbsp;` and leading-space indents becoming margins;
    - trailing line numbers and glosses;
    - a `<pre>` becoming a `gv-pre` div;
    - the drop cap being unwrapped;
    - a prose paragraph with two `<br>` and lines over 90 characters being left alone.
  - **Done 2026-10-30. As built:**
    - The pass is its own module, `verse.py` (`mark`, `CSS`, `WRAPPER_CSS`), called from `epubpack._prepare` when `zimpack.Work.verse` is set (`pack_builder._work` sets it from `WorkSpec.reads_as_verse`, for Gutenberg and eBible rows).
    - **CSS placement:** `verse.CSS` goes into `book.css` *after* the work's rules rather than into `BOOK_CSS`, because it must outrank them at equal weight (`p.poem { text-indent }` against `p.gv`). It is in every book (inert without the classes) and is the one copy: `usfm.CSS` keeps only `div.gv { margin: 0 0 .8em }` and its `qr`/`qc` lines; the ZIM build adds `verse.CSS` to an eBible work's styles. `.gv-pre` gets 1em top and bottom, as a `pre` had.
    - **Verse works only** (`WRAPPER_CSS`): the audit's one-element-per-line hang, plus `div.poem, div.stanza, div.poetry, div.verse { margin-left: 0; margin-right: 0 }`. Measured on Faerie Queene at 390 px: with Task 3's clamped margins 4,613 of 7,391 lines wrapped (all hanging); with the wrapper margins zeroed, 2,822. `div.poem > p` (0,1,2) also outranks FQ's own 3em hang, so its lines hang 1.5em like the rest.
    - **Indents:** ordinary spaces count beyond the least that any line *starting after a newline* carries; a line with no newline before it (Keats's `<span>G</span>lory`) isn't counted, so it isn't outdented against the source's pretty-printing. No-break spaces always count.
    - Lines are joined by a newline text node and a margin note is followed by a space, so a page's plain text (search, vectors, Listen) keeps lines apart; rendering is unchanged.
    - An anchor alone on an empty line moves onto the next line. Blocks already `gv` (usfm) and anything inside them are skipped.
    - **R9 is in:** a verse block's first line matching `^[A-Z][A-Z .,'’-]{0,38}\.$`, when the block has more lines, gets `gv-sp` (`font-variant-caps: all-small-caps`). Clean in Hamlet (`POLONIUS.`) at 100% and 125%; Medea is unaffected (its speakers are separate `p.char`, now centred). It also catches a heading-like first line such as Keats's `DEDICATION.`.
    - **Measured** (138 verse-section books including the 3 eBibles, Noto Serif, Readium CSS 3.4.0, the audit's `render.py`; books with ≥100 lines): flush continuation rows 158,597 → **17** at 390/100%, 217,817 → **37** at 360/100%, 305,816 → **101** at 390/125%. No book is at or above 2% at any setting (worst: G&S 0.4% at 125%, Martha 0.3%, Duchess of Malfi 0.3%). Lost alignment 8,496 → 0. The prototype's 1,766 rows were all Keats; Keats is now 0 (Task 5).
    - epubcheck on all 143 rebuilt books (138 verse + 5 prose): 0 errors and 0 warnings except Ramayan (2 errors: a `dl` cut between `dt` and `dd` at the c556/c557 boundary). The same 2 errors occur at the pre-Task-4 commit `0d12c419`, so they come from Task 2's wrapper copies, not from this task; Task 10 needs a fix.
- [x] **Task 5: headings, the contents and Keats** (R8, Decision 3)
  - `clean_title` takes the full stop off all-caps titles and puts them in title case, e.g. "BOOK I." becomes "Book I".
  - Heading text in the page gets the same treatment.
  - Add `titleOverrides` for the long front-matter titles in Faerie Queene, Beowulf and the Metamorphoses.
  - Add the Word-export flattening rule; tests use a Keats-shaped fixture.
  - **Done 2026-10-30. As built:**
    - `chapters.clean_title` title-cases each segment that is all capitals (a size split's `(part N)` is set aside) and drops its closing full stop, keeping Roman numerals, initials (`U.S.A.`) and an ellipsis; small words stay lower except first or after a full stop. `epubpack._quiet_headings` does the same to `h1`–`h6` text in every work, prose too (`<br>` in a heading starts a new sentence). In novels and philosophy this is the only change apart from `verse.CSS` in `book.css` (checked against the pre-Task-4 build of Republic, Consolation of Philosophy, Alice, Looking-Glass and Ivanhoe).
    - `titleOverrides` replace contents (and page `<title>`) titles; a key naming a page the book doesn't have fails the build, so an override that drifts out of date is caught. Rows added: Faerie Queene (all 31 pages: Introduction, Letter to Raleigh, "Dedication; Cantos I–IV", "Cantos IV–VII", "Cantos VII–X", "Cantos X–XII", then "Notes: Canto N"/"Questions: Canto N", Glossary; the poem's cantos are `p.i16` text, not headings, so its pages are size splits) and Beowulf ("Prelude; I–XXXIV", "XXXV–XLIII", "Footnotes"). Metamorphoses is left to Task 8.
    - **Word exports** (`verse.flatten_word_export`, verse works only, and only on a page with `xhtml_` classes): unwraps spans whose classes are all `xhtml_font*` (not `xhtml_big`, which carries real sizing, e.g. Medea's headings), turns single-column `table.xhtml_*` into a `div` per non-empty cell (keeping its `style`), and unwraps every `blockquote` whose only child is an attribute-less `div` (Word's indent levels). Real quotations stay.
    - **Keats Poems 1817 passes:** 0 flush wraps at all three settings (was 96%), so the edition stays. Lines wrapping (hanging) at 390 px: 460 of 1,819 (1,274 with one blockquote level left).
- [x] **Task 6: the verse check**
  - Move the audit's `render.py` and `compare.py` into `tools/pack-builder/verse_check/`, with a README covering Chrome, Readium CSS taken from the app's AAR, and Noto Serif.
  - Rebuild the 135 verse-section books plus Hamlet and run the check at 390 px and 360 px at 100%, and at 390 px at 125%.
  - **Pass:** no book wraps flush on more than 2% of lines, apart from Keats under Decision 3, and lost alignment is 0.
  - Take screenshot pairs of Paradise Lost, Faerie Queene, G&S, Leaves of Grass, Canterbury Tales, Hamlet, Divine Comedy, the Sonnets, Faust Part One and Hansel and Gretel for Neal's review.
  - Work through the per-book notes in audit §6.
  - **Done 2026-10-30.** `tools/pack-builder/verse_check/`: `harness.py setup` copies Readium CSS out of the readium-navigator 3.4.0 AAR in the Gradle cache and fetches Noto Serif into `~/.cache/granger/verse-check/`; `render.py BOOKS W SCALE` serves the pages itself on a free port and measures every book whose row reads as verse (eBible rows too); `compare.py` applies the pass rule and exits 1 on a failure; `shot.py` takes a phone screenshot of one page. The README covers setup. The line marking and the pass rule have unit tests (`tests/test_verse_check.py`); rendering needs Chrome and stays out of pytest. The audit's own scripts stay in the spike folder as the record.
  - **Measured on the Task 10 build** (147 books: every verse-section row, the three eBibles, the Douay-Rheims and the Consolation; 110 with ≥ 100 lines; 474,835 verse lines): flush continuation rows **17** at 390/100% (101,623 lines wrap, all others hang), **38** at 360/100%, **102** at 390/125%. Worst book: Ash-Wednesday 0.45% (1 of 222) at 360 and 125%; G&S 0.36% and Martha 0.27% at 125%. No book over 2%; Keats needs no exception. Lost alignment **0** at every setting. **PASS.** The screenshot pairs for Neal and the audit §6 per-book notes were not redone this round; Task 4/5's shots are in the session scratchpad.

### Part 2: the brand

- [x] **Task 7: covers and colophon** (Decisions 4 and 5)
  - Rewrite `covers.cover_jpeg(title, author, translator, section_id, section_title)` from `docs/superpowers/brand/2026-10-30-classics-covers/sections.py`, with grounds keyed by section id. Wrap long author and translator lines.
  - Add Spectral and its OFL licence to `assets/fonts/`. Remove `flame-mark.png` and EB Garamond.
  - `epubpack` writes `colophon.xhtml` (spine after the text, landmark `backmatter`) and the OPF lists it.
  - Tests:
    - the cover is deterministic;
    - each section gets its ground colour (sample a pixel);
    - the longest title, author and translator in the manifest fit without clipping;
    - the colophon shows the Gutenberg number.
  - Render a contact sheet of every cover to `build/` and look at it before going on.
  - Done 2026-10-30 on `gc-covers`. Deviations and notes:
    - The owl rasteriser is a small pure-Python port (absolute M/L/H/V/C/Z, Béziers in 12 steps, even-odd), so no fontTools or numpy; its masks match `render_svg.mask` pixel for pixel. The SVG is copied to `assets/granger-mark-owl.svg`.
    - Wrapping goes beyond the draft: a title never leaves "The", "of" and the like alone on a line, prefers not to end a line on an article or preposition ("The Wealth / of Nations"), and is balanced, so "The King James Bible" now reads "The King / James Bible" rather than the board's "The King James / Bible". Names wrap in a wider box (88%) and are balanced over two lines. A long block first rises (to 170 px), then the title shrinks, then the names shrink; nothing is cut and there is no ellipsis.
    - The colophon's owl is inline SVG: the mark in `currentColor` so it follows the reader theme, lantern eyes, ink pupils. Its source line is `BookMeta.source_line`, set by the caller ("From Project Gutenberg ebook #N"), so an eBible work can name its own. The year reads "This edition first published N" (or "This translation…"), since `published` is the reproduced edition's year.
    - The contact sheet went to the session scratchpad rather than `build/`. epubcheck isn't installed on this Mac (no jar, no Java runtime on the path); Task 10 runs it.
    - Manifest data seen on the sheet, for Task 8: many novel titles lack their leading "The" (Jungle Book, Secret Garden, Scarlet Letter, Moonstone, Time Machine, …), and Scarlet Pimpernel's author reads "Emmuska Orczy Orczy".

### Part 3: the collection

- [x] **Task 8: manifest changes** (Decision 6)
  - Apply the replacement, additions, splits into volumes, metadata fixes and the new description.
  - Run `validate`, which runs the rights guard.
  - Build the new works and look at each one's first chapter and contents.
  - **Done 2026-10-30 (gc-manifest).** 372 works (was 350): 22 added, 56 rows changed. `validate` reports 0 errors and the 144 tests pass. The description already read "...to 1930", and `PD_LINE` is 1930.
  - **Added:** Mackail's Aeneid (#22456); Norton's prose Hell, Purgatory and Paradise (#1995–1997); Riley's Metamorphoses VIII–XV (#26073); Dutt's Mahabharata (#19630); Waley's Genji volumes 2–4 (#67111, #75852, #77408); Within a Budding Grove (#63532) and The Guermantes Way (#73425); the Douay-Rheims (#1581); and all ten 1930 works. Each 1930 work's RDF says "Public domain in the USA.", English, and its 1930 year comes from the title page (MARC 260 agrees).
  - **Changed:** Meditations is #15877 (Long, 1862) under the same slug. Metamorphoses #21765 is now "Books I–VII" and Genji #66057 "Volume 1", both under their old slugs. Chase is credited for the Ethics; Rogers for the Frogs, at 1902; Burnet for Utopia, at 1684. The Candide note says the translation is anonymous. The Canterbury Tales note credits Purves as editor; there's no editor field, and the translator field would be wrong. 47 titles get their leading "The"/"A" from the RDF: 46 novels and *A Midsummer Night's Dream*. Robinson Crusoe, Moll Flanders, Tristram Shandy and Strange Case keep their short or original titles. The Scarlet Pimpernel's author is "Baroness Orczy".
  - **Deviations:**
    - **Douay-Rheims is #1581, not #8300.** #8300's own front matter says #1581 is the "improved and more complete edition". #1581 has all 73 books at h3 with Challoner's annotations, so it uses `splitAt: "h3"`, with published set to 1752.
    - **Distinct titles for second versions,** because the app shows only title and author: "The Aeneid (prose, tr. Mackail)" and "The Divine Comedy: Hell/Purgatory/Paradise (prose, tr. Norton)". Dryden's and Longfellow's rows are unchanged. Genji is "The Tale of Genji, Volume N: <part title>". The Mahabharata has no author, so it reads "(tr. Romesh Chunder Dutt)".
    - **`titleCheck` is off** where our title differs from Gutenberg's: Meditations, Mackail, Norton, Dutt, the Genji volumes, and both Metamorphoses halves. `_words` drops the en dash in "I–VII" (it joins the words into "ivii"), so those rows can't match Gutenberg's hyphen. Each row's note gives the Gutenberg title.
    - **`titleOverrides` were added for 17 works** whose contents showed title-page names, "Part N" pages, or a repeated prefix (all 16 Mahabharata pages read "THE EPIC OF ANCIENT INDIA — ..."). The page numbers come from this build's split. Recheck them once the verse rules change splitting, since this branch's builder doesn't apply overrides yet.
  - **Seen in the builds, needing builder work (not done here):**
    - *Cakes and Ale* opens with Faded Page's Canadian boilerplate ("IF THE BOOK IS UNDER COPYRIGHT IN YOUR COUNTRY, DO NOT DOWNLOAD..."), and `later_edition_notices` flags it. `gutenberg.clean` should drop that block.
    - *The Guermantes Way*'s last page ends with about 9 KB of the Modern Library's title catalogue, which should be trimmed.
    - The Douay-Rheims splits into 776 pages, one per chapter, with nav entries like "THE BOOK OF GENESIS — Genesis Chapter 1". eBible packs a book's chapters into even pages instead.
    - *The Woman of Andros* has no headings, so it is a single 121k-character page.
- [x] **Task 9: eBible Bibles** (Decision 8)
  - Add `EBibleSource` (cached) and `usfm.py` with tests on a small USFM fixture: chapters, verse numbers, `\q1`/`\q2` lines, `\s` headings, and footnotes dropped.
  - `build-epubs` and `describe-epubs` send `source: ebible` rows through it.
  - Build WEB, BSB and JPS. Check Genesis 1, Psalm 23, Isaiah 40, Matthew 5 and, in WEB, Tobit 1 and Sirach 1.
  - **Done 2026-10-30 (gc-ebible).** Source: `https://eBible.org/Scriptures/<id>_usfm.zip`, linked from each details page; the zip and the details page are cached in `~/.cache/granger/ebible/<id>/`. Only flat `NN-BOOK<id>.usfm` members are read, straight into memory. `validate` checks that each eBible row's `licence` quotes the dedication with its URL and that the details page still says "Public Domain"; eBible rows skip the RDF and 1930 checks.
  - `engwebu` does include the Deuterocanon (81 books plus the glossary), so the plan's id stands.
  - Built: WEB 1.9 MB, 103 pages; BSB 1.6 MB, 87 pages; JPS 1.1 MB, 52 pages. The largest page is 148 KB. Every `\v` appears as a verse number except 29 WEB verses that are only a footnote (for example Acts 8:37 and Sirach 1:5); these are dropped with the footnotes.
  - **Deviations:**
    - A book over the page budget splits at chapter boundaries into even pages, such as "Psalms 1–39" and "Isaiah 30–47".
    - In the Psalms the chapter heading reads "Psalm N", from the source's `\cl` or a default; elsewhere it reads "Chapter N". A one-chapter book (Obadiah, Jude, Psalm 151) has no chapter heading.
    - `\ms` ("BOOK 1") sits above the chapter heading, and `\d` psalm titles are kept in italics. `\r` parallel-passage lines are dropped, like cross-references.
    - The WEB preface (FRT) is skipped. The WEB glossary (GLO) is kept as the last page.
    - Verse markup is written inline, with the R1 classes and CSS carried in the work's styles, so it doesn't depend on Task 4.
    - JPS sets its poetry as prose, so its Psalms have no verse lines. Its books follow eBible's order, which is the Christian Old Testament order, not the Tanakh's.
    - epubcheck isn't installed on this Mac. The XHTML parses as XML and has no duplicate ids; run epubcheck in Task 10.

### Part 4: release

- [x] **Task 10: full build and checks**
  - Run `build-epubs` for all works into `~/granger-build/classics-<date>/`.
  - epubcheck must report 0 errors for every file, as the 2026-10-17 release did.
  - Run the Task 6 verse check over the whole set.
  - Record the total size against the old 95.2 MB.
  - **Fixups first (2026-10-30)**, from Tasks 2, 5 and 8, each a commit with tests:
    - **Ramayan's split `dl`:** a size split moves a `dt` at a page's foot over with its `dd`, and every `dl` on a page gets an empty `dt`/`dd` if it still starts with a `dd` or ends with a `dt`.
    - **Faded Page notices** (Cakes and Ale): `gutenberg.clean` drops the widest block of ≤ 600 characters around four exact phrases in the first 12,000 characters. None of the other nine 1930 works has them; "these faded pages" in Gawain is untouched. Its "later edition?" warning stays: the Triangle Books 1941 reprint's copyright page, of the 1930 text.
    - **`cutAfter`** (new optional manifest field): the work's last words, which must appear exactly once; everything after goes. The Guermantes Way uses it to drop the Modern Library catalogue and the transcriber's corrections list. No generic rule: a publisher's list can't be told safely from a work's own.
    - **`packSections`** (new optional manifest field): a page over the budget packs its next-level sections into as few even pages as fit (usfm's `even_runs`, now in `chapters`), named "Genesis 1–27", "Psalms 98–150"; a book that fits is named for its chapters ("Genesis", "Lamentations" past its preface). Douay-Rheims: 776 → 93 pages. Left as is: its last two pages are "Additional Books (part 1/2)", and "The New Testament…" heading sits at the foot of 2 Machabees inside a wrapper copy.
    - **Title check:** dashes part words, so both Metamorphoses rows have `titleCheck` back on.
    - **Start of text** landmark: the first page whose title (an override wins) or heading isn't Title page, Contents, Table of contents, Front matter, Dedication or "…'s Dedication".
    - **Title case:** a small word after initials stays small ("The U.S.A. and Its States").
    - **Consolation of Philosophy:** `"verse": true`.
    - **The Woman of Andros** is left as one page: 131 KB, under the 150 KB budget, with seven scene breaks (`hr.tb`) and no headings. Splitting it would need a new rule for one book.
  - **Done 2026-10-30.** `build-epubs` into `~/granger-build/classics-2026-10-30/`: **372 EPUBs, 114.8 MB** (old release 95.2 MB for 350; +19.6 MB, mostly the three eBibles, the Douay-Rheims, Proust and Genji). Largest: the Douay-Rheims, 2.10 MB (93 pages, largest page 146 KB); then WEB 1.91 MB and Ramayan 1.73 MB. 36 minutes, mostly fetching new Gutenberg files. Five "later edition?" warnings, all known (Art of War's 1963 mention, Origin's edition list, Peter Pan's 1991 Fulcrum line, Cakes and Ale's 1941 reprint, Decameron's accent note).
  - **epubcheck 5.2.1: 372 of 372 with 0 fatals, 0 errors, 0 warnings, 0 infos.** `min()` in `book.css` passes.
  - **Verse check:** as Task 6, PASS at all three settings. The Douay-Rheims has 99 verse lines (its text is prose verses), 0 flush; WEB 23,338 lines and BSB 24,335, 0 flush; JPS has none (its poetry is set as prose).
  - **Looked at** (390 × 844 screenshots under Readium CSS and Noto Serif): Douay-Rheims Genesis 1 (book intro, "Genesis Chapter 1", argument, verses, Challoner's notes indented); Cakes and Ale's opening (now the title page, no Faded Page notices); Guermantes's last page (ends "You'll live to bury us all!""); Meditations (Long) Book I; Metamorphoses VIII–XV "Book the Eighth, Fable I"; Maltese Falcon chapter 1; Consolation Song I (lines hang). Mahabharata's contents read "Book I: Astra Darsana (The Tournament)" … "Glossary of Sanskrit Words".
  - **Seen, not fixed:** Long's Meditations heads its first book "The Thoughts / Of / Marcus Aurelius Antoninus" (a `<br>` in a heading starts a new sentence, so "Of" is capitalised) and its contents read "I." with the stop while II–XII don't. Footnote links show in the browser's link blue in these shots; the reader's theme sets link colour.
- [ ] **Task 11: vectors**
  - Rebuild every Classics `.gvec` with `tools/vectors/build.py --only granger-classics` on the Mac, timing it.
  - Upload the vectors under a new `vectors-<date>` tag.
  - Run recall on the R-track eval set to check that dense retrieval over the Classics isn't worse.
  - **Built 2026-10-30** (descriptors committed; `.gvec` files in `~/Developer/Granger/vectors-work/gc-2026-10-30/out/`, not uploaded): 372 books, 286,067 chunks (old 347, 248,383). Paused at 177 for the shared GPU; about 80 minutes each side of the pause at about 3 books a minute.
  - **Recall** (`r5-t7-all`'s 35 book questions, all Classics vectors at once, 256 int8 as on the phone): old top-4 18, top-12 23, top-50 27; new 17, 21, 29. Every drop is a Bible question, and in each the new top hits answer it in another Bible (WEB Matthew 6:34 for "not worrying about tomorrow", BSB and Douay Daniel 6 for the lions' den, BSB "Wine Is a Mocker"). The answers are marked against the KJV only, so these count as misses. Nothing else is worse; Genji authorship and Romans now come back in the top 50.
- [ ] **Task 12: publish** (after gb-gutenberg has merged)
  - Upload the EPUBs to the GitHub release `classics-<date>`. This is outward-facing, so confirm with Neal first.
  - Run `describe-epubs` into `releases/granger_classics_books.json`.
  - Regenerate the catalog from main, run `check-links`, and re-sign.
  - Add the roadmap row GC.
- [ ] **Task 13: phone walkthrough** (when the phone is free; one app on the phone)
  - Update the Classics from Get more → Updates.
  - Look at the covers in the list and grid in Field and Night.
  - Open Paradise Lost, Hamlet, Leaves of Grass, the WEB Psalms and Meditations (Long), at the default size and at a large one.
  - Check that Readium writes text-align when it is left at "start", which audit §1 assumes.
  - Check Listen on a verse page: lines should be read as lines, with no run-ons and no line numbers.
  - Check that Ask finds a new book.
