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

- [ ] **Task 1: leftovers and the rights guard** (R7, Decision 7)
  - `gutenberg.clean` and `epubpack._prepare` remove empty `header`/`footer` boilerplate, childless and textless `div`/`p` (their ids are kept as spans), fixed `width` in inline styles on non-media elements, and a Transcriber's Note whose only subject is the cover art.
  - The manifest gains `verse`, `lineNumbers`, `titleOverrides`, `source` and `licence`; `validate` checks RDF rights.
  - Tests are added in `tests/test_gutenberg.py`, `test_epubpack.py` and `test_pack_builder.py`. One fixture is a copyrighted RDF, which must fail validation.
- [ ] **Task 2: splitting keeps wrappers** (R6)
  - When `_flatten`/`_by_size` open a wrapper, each run of its children is re-wrapped in a shallow copy with the same tag and class and no id.
  - Test with a Faerie Queene-shaped fixture (`div.poem > div.stanza > p.i2`) split across two pages: `deadrules.py` must report the indent rules as live.
- [ ] **Task 3: CSS fixes** (R3, R4, R5)
  - `scrub_css` clamps percentage side margins and paddings above 5% to `min(X%, 2em)`, and em indents above 6em to `min(Xem, 30%)`.
  - It writes `:root:root:root:root <sel> { text-align: X !important }` for each centre, right or end rule that targets a `p` or a bare class, and adds `!important` to inline `text-align` on a `p`.
  - It replaces absolute or fixed positioning with a right float in small, faint type. With `lineNumbers: "drop"`, the line-number spans are removed instead.
  - Unit tests run against the exact rules from Paradise Lost, Hamlet and Faerie Queene that the audit found.
- [ ] **Task 4: verse blocks** (R1, R2, Decision 2, and R9 if it passes)
  - Port `fixproto.py` into `epubpack._prepare`, gated on the work's `verse` setting, and append the audit's verse CSS (§5) to `BOOK_CSS`.
  - Tests cover:
    - `<br>` lines becoming `gv-l`, and stanza gaps;
    - `&nbsp;` and leading-space indents becoming margins;
    - trailing line numbers and glosses;
    - a `<pre>` becoming a `gv-pre` div;
    - the drop cap being unwrapped;
    - a prose paragraph with two `<br>` and lines over 90 characters being left alone.
- [ ] **Task 5: headings, the contents and Keats** (R8, Decision 3)
  - `clean_title` takes the full stop off all-caps titles and puts them in title case, e.g. "BOOK I." becomes "Book I".
  - Heading text in the page gets the same treatment.
  - Add `titleOverrides` for the long front-matter titles in Faerie Queene, Beowulf and the Metamorphoses.
  - Add the Word-export flattening rule; tests use a Keats-shaped fixture.
- [ ] **Task 6: the verse check**
  - Move the audit's `render.py` and `compare.py` into `tools/pack-builder/verse_check/`, with a README covering Chrome, Readium CSS taken from the app's AAR, and Noto Serif.
  - Rebuild the 135 verse-section books plus Hamlet and run the check at 390 px and 360 px at 100%, and at 390 px at 125%.
  - **Pass:** no book wraps flush on more than 2% of lines, apart from Keats under Decision 3, and lost alignment is 0.
  - Take screenshot pairs of Paradise Lost, Faerie Queene, G&S, Leaves of Grass, Canterbury Tales, Hamlet, Divine Comedy, the Sonnets, Faust Part One and Hansel and Gretel for Neal's review.
  - Work through the per-book notes in audit §6.

### Part 2: the brand

- [ ] **Task 7: covers and colophon** (Decisions 4 and 5)
  - Rewrite `covers.cover_jpeg(title, author, translator, section_id, section_title)` from `docs/superpowers/brand/2026-10-30-classics-covers/sections.py`, with grounds keyed by section id. Wrap long author and translator lines.
  - Add Spectral and its OFL licence to `assets/fonts/`. Remove `flame-mark.png` and EB Garamond.
  - `epubpack` writes `colophon.xhtml` (spine after the text, landmark `backmatter`) and the OPF lists it.
  - Tests:
    - the cover is deterministic;
    - each section gets its ground colour (sample a pixel);
    - the longest title, author and translator in the manifest fit without clipping;
    - the colophon shows the Gutenberg number.
  - Render a contact sheet of every cover to `build/` and look at it before going on.

### Part 3: the collection

- [ ] **Task 8: manifest changes** (Decision 6)
  - Apply the replacement, additions, splits into volumes, metadata fixes and the new description.
  - Run `validate`, which runs the rights guard.
  - Build the new works and look at each one's first chapter and contents.
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

- [ ] **Task 10: full build and checks**
  - Run `build-epubs` for all works into `~/granger-build/classics-<date>/`.
  - epubcheck must report 0 errors for every file, as the 2026-10-17 release did.
  - Run the Task 6 verse check over the whole set.
  - Record the total size against the old 95.2 MB.
- [ ] **Task 11: vectors**
  - Rebuild every Classics `.gvec` with `tools/vectors/build.py --only granger-classics` on the Mac, timing it.
  - Upload the vectors under a new `vectors-<date>` tag.
  - Run recall on the R-track eval set to check that dense retrieval over the Classics isn't worse.
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
