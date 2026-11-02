# Granger Classics: verse formatting audit (2026-10-30)

Scope: 347 EPUBs in `tools/eval/runs/r0-books/books/granger/` (build 2026-10-17). I looked closely at the 135 books in the poetry (30), drama (80), libretti (13) and scripture (12) sections, and rendered philosophy (51) as well. Novels were only scanned statically. No repo files were changed. Scripts, data and screenshots are in `docs/superpowers/spikes/2026-10-30-classics-audit/` (scripts and five screenshot pairs; data files stayed in the session scratchpad).

## 0. Three facts that shape everything else

1. **The reader runs with `publisherStyles = false`** (`app/.../ebook/ReadingPrefs.kt:79`). I read Readium CSS from the 3.4.0 AAR (`ReadiumCSS-after.css`, v1.0.0-beta.3). With the reader's advanced settings switched on, it does three things that matter here:
   - `body { margin: 0 auto !important; padding: 0 20px !important; max-width: 40rem }`. Gutenberg's `body { margin-left: 10–20% }` and `body { width: 85% }` are therefore **neutralised**. That affects 253 books, 91 of them in the verse sections, and it's harmless. The text column on a 390 dp phone is 350 px.
   - `:root[…textAlign] :not(blockquote):not(figcaption) p { text-align: inherit !important }`. Body-level justify is also neutralised. But **every centred or right-aligned `<p>` falls back to the left**, including stage directions, `Exit.`/`Exeunt.`, centred titles and right-aligned attributions. Inline `style="text-align:center"` on a `<p>` loses too.
   - `p, div, li, dd, pre { font-size: 1rem !important }` and the font override target `body, p, div, li, dd, dt` only. Because **`<pre>` is not on that list, it stays in the system monospace font**.
   - Not neutralised: per-element margins and paddings (`p.poem { margin-left: 30% }`), `text-indent`, floats, `position: absolute`, and inline style widths and margins.
2. **No verse in the collection has a hanging indent.** Gutenberg verse is a `<p>` with `<br/>` lines in about 100 books, `<pre>` in 44 books, and one element per line in 8 books. Any line too long for the column wraps flush to the left margin and reads as a new line of verse.
3. **Chapter splitting drops wrapper elements.** `chapters._flatten` and `_by_size` open a wrapper by taking its children and discarding the wrapper. Rules that depend on the wrapper then stop matching. Faerie Queene is the clear case: `.poem p {padding-left:3em; text-indent:-3em}`, `.poem p.i2 … i20` and `.poem .stanza` match only 9 of 641 stanzas.

## 1. How it was measured

- `audit.py` does static analysis of every chapter: markup patterns, CSS risks, leftovers and headings. Output is `audit.json`.
- `render.py` builds a harness for each book: ReadiumCSS-before, then the book's `book.css`, then ReadiumCSS-after, with the root style the Kotlin toolkit writes. The settings are advanced-on, font-on, `textAlign: start`, `lineHeight` 1.5 and `pageMargins` 1. The font is **Noto Serif** (Android's serif, downloaded as a web font). The harness runs in an iframe 390 or 360 px wide in headless Chrome, in scroll mode. Every verse line is wrapped in a marker span and measured:
  - line rows, from `Range.getClientRects`
  - whether the continuation row is indented (a hanging indent) or flush
  - the block's available width
  - lines that run off screen
  - `<pre>` source lines against rendered rows
  - paragraphs whose author alignment (centre or right) is overridden. This is measured by toggling Readium's after-sheet.
- Settings measured: 390 px at 100%, 360 px at 100%, and 390 px at 125% (users who pick larger text).
- `fixproto.py` prototypes the proposed builder rules, and `render.py` with `FIX=1` re-measures with them applied. `compare.py` prints the before and after numbers to `compare.txt`.
- Caveats:
  - Chrome is used, not the Android WebView.
  - Scroll mode is used, not Readium's paginated columns.
  - Georgia gave about 30% fewer wraps than Noto Serif, so the font matters.
  - I assumed the toolkit emits `--USER__textAlign: start` for `TextAlign.START`. Confirm this on a device.

## 2. Verse markup patterns by book (verse sections)

| Pattern | Books | Examples (lines) |
|---|---|---|
| **`<p>` + `<br/>` per line**, mostly `p.drama` (Shakespeare HTML: speaker caps on line 1), `p.noindent`, `p.poem`, plain `p` | ~100 | All 36 Shakespeare plays, sonnets (2,309), Paradise Lost (10,546, `p.poem` with margin-left 30%), Aeneid (13,699, the same 30%), Dante ×3 (~4,700 each), Canterbury Tales (24,860), Kalevala (22,652), Don Juan (16,055), Essay on Man, Odes, Song of Roland, Beowulf, Tartuffe, Cyrano, Oedipus, Koran (Sale and Rodwell), Gita, Rhinegold/Siegfried |
| **`<pre>` verse** (monospace, hard-wrapped, space-indented) | 44 (105 across all sections) | Leaves of Grass (400 blocks, 13,757 lines), G&S (142 blocks, 25,198 lines, 11,785 of them indented ≥10 spaces), Idylls of the King (10,373), Medea (255), Rubaiyat (185), Phaedra (2,058 of 2,068 lines indented ≥10), Tamburlaine 1 and 2, Volpone, Alchemist, Bacchae, Frogs, Clouds, Doctor Faustus, Duchess of Malfi, Rime of the Ancient Mariner, Tao Te Ching, School for Scandal, Hedda Gabler |
| **One element per line** (`div.stanza > p.iN`, `div.tei-l`, `div.line`) | 8 | Ramayan (53,203 `div.tei-l`), Faerie Queene (7,339 `p.i2`/`p.i20` in `div.stanza`), Hiawatha (5,555), Faust (Gounod, 3,075), Martha (744), Castle of Andalusia (365), Hansel and Gretel (1,319 `div.line`), Consolation of Philosophy |
| **Indentation as leading `&nbsp;` runs** | 42 | Dante ×3 (~3,150 lines each), Don Juan (6,019), Song of Roland (4,174 = every line), Odes (3,110), Tartuffe (2,868 = every line), Spanish Tragedy (2,520), sonnets (2,309), Koran (Sale) (1,812), House of Atreus (1,099), Everyman (794), Oedipus (625), Essay on Man (440) |
| **Indentation as ASCII spaces (collapsed, so lost)** | 13 | Faust Part One (3,172 lines), Poems 1817 (1,657), Gita (497), Lysistrata (281), Aeneid (234), Lyrical Ballads (75), Blue Bird (69), Playboy (52) |
| **Line numbers in the text** | 6 | Faerie Queene (1,108 `span.linenum`, `position:absolute; left:60%`), Metamorphoses (507 `span.linenum`), Paradise Regained (205: `…hand&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;20`), sonnets (154 numbers as a line of 21 nbsp + "1"), Koran (Sale) (251 tabbed note numbers) |
| **Marginal glosses as space runs** | 9 | Canterbury Tales (6,349: `swoot*,           *sweet`, which collapses to `swoot*, *sweet`), Spanish Tragedy (30), Everyman (19), Duenna (12), La Bohème (9) |
| **Stanza breaks as `<br/><br/>`** (they render as a blank line, so not lost) | 65 | Don Juan (1,993), Rhinegold (1,186), Faust Part One (493), Lyrical Ballads (406), Cavalleria (194) |
| Dropcap span (`style="font-size:4em"` + float) | 3 | Aeneid (12), Paradise Lost (10), House of Atreus (3). Also 8 novels (487). |
| Verse in layout tables or nested `blockquote`×3 | 1 | Poems 1817 (Word-export HTML: `<td><blockquote><div><blockquote>…<span class="xhtml_font_face">` with `<br/>` inside the span) |

Scripture prose (KJV, Apocrypha, Upanishads, Analects, Dhammapada, Imitation of Christ) is ordinary `<p>` with `1:1` verse numbers. It renders fine. The KJV has no verse-per-line layout, and none is needed.

## 3. What a phone shows today (measured)

The metric is the share of verse source lines that **wrap with a flush (non-hanging) continuation**, plus the extra rows from `<pre>` wrapping. It covers the 106 verse-section books with at least 100 verse lines.

| Setting | ≥10% bad | ≥25% | ≥50% | ≥90% |
|---|---|---|---|---|
| 390 px, 100% | 94 | 40 | 26 | 12 |
| 360 px, 100% | 96 | 91 | 37 | 17 |
| 390 px, 125% text | 101 | 97 | 84 | 28 |
| **With the proposed rules (all three settings)** | **1** | **1** | **1** | – |

The one book left after the fix is Poems 1817, which needs a manual fix (§6).

Flush continuation rows across the verse sections: **158,597 at 390/100%, 217,817 at 360/100% and 305,816 at 390/125%**, out of 425,534 source lines. With the prototype rules that falls to 1,766, all of them in Poems 1817.

Only 0.3% of wrapped lines hang today. The few that do are Hiawatha, Hansel and Gretel and Faust (Gounod), whose own CSS has `text-indent:-` rules.

### Worst books (390 px, 100%; percentages are flush rows per verse line, so over 100% means more than one extra row per line)

| Book | Now | Main cause |
|---|---|---|
| Medea | 197% | `<pre>`, monospace, 279 centred speaker names (`p.char`) shown left-aligned |
| Doctor Faustus | 128% | `<pre>` |
| Leaves of Grass | 118% | `<pre>` monospace + `pre { margin-left:10% }` |
| Hedda Gabler | 117% | `<pre>` |
| G&S complete plays | 102% | `<pre>`; second-voice lines indented 27 spaces wrap into single words |
| Tamburlaine 1 and 2 | 100% | `<pre>` |
| **Aeneid** | 99% | `p.poem { margin-left: 30% }` leaves 245 px for verse, so every line wraps; also the dropcap |
| Idylls of the King | 99% | `<pre>` |
| **Paradise Lost** | 98% | The same 30% margin (10,440 of 10,546 lines wrap); dropcap `O` floated beside the first lines |
| Rubaiyat | 98% | `<pre>`, quatrains in monospace |
| Poems 1817 | 96% | Verse in a table inside nested blockquotes (189 px wide); indentation collapsed |
| Frogs, Duchess of Malfi, Clouds, Phaedra, Bacchae, Volpone, Tao Te Ching, School for Scandal, Alchemist | 63–88% | `<pre>` |
| Koran (Rodwell) 67%, Analects 66%, Koran (Sale) 60% | | Long prose-like lines in br-blocks, flush |
| Pilgrim's Progress 55%, Gita 48%, Song of Roland 36%, Spanish Tragedy 34%, Dante 25–31%, Don Juan 31%, Paradise Regained 28%, sonnets 28%, Canterbury 27% | | br-blocks, no hang |
| All 36 Shakespeare plays | 12–22% (34–47% at 360; 40–75% at 125%) | `p.drama` br-blocks, no hang |
| **Faerie Queene** | 24% (72% at 125%) | Lost wrapper rules (no hang, no `i2` indents, every line spaced as a paragraph); **line numbers drawn over the text** (`Lad5ies`, `ch10efe`, `Brito15 Prince`) |
| Hansel and Gretel | 83% | Lines in `div.line` with a large left offset (verse starts at 50% of the column) |
| Martha 64%, Castle of Andalusia 34%, Faust (Gounod) 25% | | Stanza `%`/em margins narrow the column (available width 149–256 px) |

Good today: Kalevala (0%, short trochaic lines), Siegfried, Rhinegold, Ramayan, Poems Three Series and Lyrical Ballads are all at 0–7%. They still go to 4–37% at 125%, which the hanging indent covers.

## 4. Other defects (counts)

**Alignment lost to Readium's `p` override.** 78 of 135 verse-section books, 8,496 paragraphs; 96 of 186 non-novel books, 8,926 paragraphs. Novels are affected too, by the same `p.center`/`p.right` classes.
- Shakespeare ×36 (`p.scenedesc` centred stage directions about 60–115 per play; `p.right` `Exit.`/`Exeunt.` about 70–125 per play)
- Hebraic Literature (1,070 `p.source` right-aligned)
- Hansel and Gretel (418 `p.character`)
- Medea (257 `p.char`)
- Castle of Andalusia (211)
- Fontainbleau (188)
- Imitation of Christ (140)
- Way of the World (87)

The prototype rule takes this to 0.

**`<pre>` in monospace.** 44 verse-section books. Readium's font override doesn't cover `pre`.

**Lost indentation.**
- Faerie Queene: `.poem p.i2…i20` are dead in 632 of 641 stanzas.
- 13 books with collapsed ASCII-space indentation: 5,900+ lines, worst Faust Part One with 3,172. The source text intended these indents, but Gutenberg's HTML never displayed them either.
- 42 books indent with nbsp. The indents show, but the continuation doesn't hang.

**Line numbers inline or overlapping.** Faerie Queene (1,108 absolute spans overlapping text), Paradise Regained (205 glued to line ends, which forces wraps), Metamorphoses (507 range labels such as `I. 6-26`), sonnets (154 numbers indented 21 nbsp).

**Glosses collapsed into the line.** Canterbury Tales has 6,349 lines like `swoot*, *sweet`. The prototype moves the gloss to a small right-floated note. See `pair-canterbury-tales-c004.png`.

**Speaker names in Shakespeare.** Speaker names are the first `<br>` line of each `p.drama` (`POLONIUS.`), set in roman caps and not distinguished from the verse. 38 books use `p.drama`, with 34,875 speeches. The `span.charname` small caps are used only in stage directions.

**Empty or junk blocks.**
- Empty `<header class="pg-boilerplate pgheader" id="pg-header"/>` and `<footer …>` remain in **all 347 books**, along with an empty `<div style="text-align:center"/>`.
- 2,088 empty divs in 272 books, many of them figure wrappers left when images were removed: `<div class="figcenter" style="width: 500px"></div>` in Rhinegold, `<div class="fig" style="width:45%">` in Faust Part One.
- 3,021 empty `<p>` in 41 books: Faerie Queene 626 `<p>&nbsp;</p>` between each stanza numeral and its first line; Martha 32; Poems Three Series 29.

**Transcriber's notes.** 110 notes in 81 books, 77 of them in 52 verse-section books.
- Every Shakespeare play ends with a boxed note: "Transcriber's Notes – New original cover art included with this eBook is granted to the public domain." It is irrelevant here.
- Canterbury Tales has 24 inline notes, which are real content.

**`[Illustration]` text.** Zero. Images are removed upstream, but their empty wrappers are not.

**Headings.**
- 1,530 all-caps headings ending in a full stop in 57 verse-section books (3,318 in 133 books overall). Examples: `BOOK I.`, `THE ARGUMENT.`, `CANTO THE SECOND.`, `RUNE I.`; worst are Koran (Sale) (298), Odes (228), Trojan Women (176) and Metamorphoses (152).
- Nav titles show the same pattern in 42 verse-section books (107 overall).
- Long run-on nav titles appear in 18 verse-section books, e.g. Faerie Queene: `ELIZABETH — Queen of England, Fraunce and Ireland, … — EDMVND SPENSER — THE LEGENDE OF THE — …`, and Beowulf: `Translated by Gummere — BEOWULF PRELUDE OF THE FOUNDER OF THE DANISH HOUSE (part 1)`.
- `(part N)` titles appear in 11 books (KJV 20, Poems Three Series 7, Hebraic 5, Faerie Queene 4).

**In-book Contents.** 102 verse-section books have a "Contents" heading and table. In Shakespeare these link to scene level, which is finer than the nav (act level), so they are useful. Keep them, or move scene entries into the nav.

**Horizontal overflow (page wider than 390 px).** 14 non-novel books, mainly wide tables and long unbreakable tokens: Faerie Queene chronology table, Poems 1817 tables, Timaeus, Republic, Poems and Songs `sup`. This is minor for verse.

**Other CSS risks still live (element level, verse sections).**

| Risk | Books |
|---|---|
| Margins ≥10% or ≥4em | 70 |
| Justify on non-`p` elements | 39 |
| `position:absolute` | 9 (Faerie Queene `.linenum`, Rhinegold `.illnum left:85%`) |
| Floats | 6 |
| Inline `margin-left: 7.5em` stage directions inside br-blocks (Cavalleria `(Curtain rises.)`) | 452 instances |

## 5. Proposed builder rules (prototyped in `fixproto.py`; measured effect in §3)

All of these are generic. They are listed in order of value.

**R1. Verse lines become hanging blocks.** In `epubpack._prepare`, for verse sections or works with a `verse: true` flag:
- Any `p`/`div`/`td`/`blockquote` with ≥2 direct `<br>` children, no block children and an average line length ≤90 characters is a verse block.
- Split its children at `<br>` into `<span class="gv-l">…</span>`, drop the `<br>`s, and add `class="gv"`.
- Empty segments become `<span class="gv-gap"/>` (stanza breaks).
- Leading nbsp or space beyond the block's minimum becomes `style="margin-left:min(N×0.3em, 35%)"`, and the nbsp characters are removed.
- A trailing `[\s\xa0]{3,}\d{1,4}$` becomes `<span class="gv-n">20</span>`.
- A run of ≥6 internal ASCII spaces followed by text becomes a `gv-n` gloss.
- Unwrap `span.dropcap` inside verse.

Effect: all 106 books go to 0% flush wraps, including Shakespeare speeches (the speaker line stays as line 1). The CSS, appended after the scrubbed work CSS in `write_epub`:

```css
.gv { text-align: start; text-indent: 0; hyphens: manual; }
p.gv, div.gv, td.gv { margin-left: 0; margin-right: 0; }      /* verse sections only */
.gv-l { display: block; padding-left: 1.5em; text-indent: -1.5em; }
.gv-gap { display: block; height: .75em; }
.gv-n { float: right; font-size: .75em; opacity: .65; margin-left: .75em; text-indent: 0; font-style: normal; }
.gv [class*=dropcap] { float: none; font-size: inherit; line-height: inherit; margin: 0; }
div.stanza > p, div.stanza > div, div.poem > p, div.poetry > p, div.verse > p, div.tei-l, div.line
  { padding-left: 1.5em; text-indent: -1.5em; }
```

Note: this changes the DOM, so saved Readium locators and highlights from earlier builds may shift.

**R2. `<pre>` becomes a verse div.** Convert each `<pre>` to `<div class="gv gv-pre">`:
- Split at newlines and keep inline tags.
- Each line becomes a `gv-l`.
- Leading spaces beyond the block minimum become `margin-left:min(N×0.5em, 35%)`.
- Blank lines become `gv-gap`.

This fixes the 44 monospace books, with Leaves of Grass going from 16,332 extra rows to 0. G&S keeps its second-voice indent.

The pre-only CSS fallback is `pre { font-family: inherit; margin-left: 0 }`. It is weaker: wrapped lines still start flush.

Per-book follow-up: G&S and Leaves of Grass have hard-wrapped *prose* stage directions and continuation lines inside `pre`. These become separate hanging lines, which is readable but not reflowed. A later refinement could join a line that starts with deeper indent onto the previous line.

**R3. Clamp wide side margins in `scrub_css`.** Rewrite `margin|padding-left|right: X%` with X > 5 (including shorthands) as `min(X%, 2em)`. This touches 247 books with element-level side margins ≥10% or ≥4em (70 of them verse-section).
- Effect: `p.poem {margin-left:30%}` (Paradise Lost, Aeneid), `.poem {margin-left:10%}`, `blockquote {margin-left:20%}`, `p.letter` and stanza margins stop narrowing the column.
- Consider also capping large em indents (`i16`/`i20` = 8–10em) with `min(Xem, 30%)`.

**R4. Restore author alignment over Readium.**
- For every work-CSS rule with `text-align: center|right|end` whose selector targets `p` (or a bare class), emit `:root:root:root:root <sel> { text-align: X !important }`. That has specificity (0,5,x) against Readium's (0,3,3).
- Add `!important` to inline `text-align` on `<p>`.
- Effect: 8,926 → 0 lost alignments. Stage directions centred again, `Exit.` right-aligned (`pair-hamlet-c002.png`).
- Alternative: retag such `<p>` as `<div>` with the same class, rewriting `p.x` selectors.

**R5. Drop absolute positioning in `scrub_css`.** In rules with `position:absolute|fixed`, remove `position`/`left`/`top`/`right`/`bottom` and add `float:right; font-size:.75em; opacity:.65`. This applies to 18 rules in 9 non-novel books. Effect: Faerie Queene line numbers move from on top of words to the right margin (`pair-faerie-queene-c003.png`). You could instead drop `span.linenum` entirely: line numbers have little value on a phone and the reader can't cite them.

**R6. Keep wrappers when splitting** (`chapters._flatten` and `_by_size`). When a wrapper is opened, re-wrap each run of its children in a shallow copy (same tag and class, no id). This brings back Faerie Queene's hanging indent, the `i2…i20` indents, `margin:0` lines and stanza spacing. It's generic and also fixes `div.chapter`/`div.poem`-scoped rules elsewhere.

**R7. Leftovers in `gutenberg.clean` / `_prepare`.**
- Decompose empty `header`/`footer.pg-boilerplate` and empty `div style="text-align:center"` (all 347 books).
- Decompose childless, textless `div`/`p` after image removal, keeping their ids as spans (2,088 divs and 3,021 `p`, including Faerie Queene's 624 `<p>&nbsp;</p>`).
- Strip `width` from inline styles on non-media elements: `figcenter style="width:500px"`.
- Drop a block whose heading is "Transcriber's Notes" when its text only mentions cover art (36 Shakespeare plays). Keep notes with real content (Canterbury Tales).

**R8. Headings and nav.**
- In `clean_title` (nav and titles): strip a trailing full stop and convert all-caps to title case (`BOOK I.` → `Book I`, `CANTO THE SECOND.` → `Canto the Second`). This affects 107 books.
- Optionally apply the same to `h1`–`h4` text, or add CSS `h1,h2,h3 { text-transform: none }`. Real case conversion needs the text change.
- Trim front-matter mega-titles (Faerie Queene, Beowulf, Metamorphoses) with per-work `titleOverrides` in the manifest.

**R9. Speaker names (optional polish).** In a `p.drama` verse block whose first line matches `^[A-Z][A-Z .,'’-]+\.$`, give that line `class="gv-sp"` with small caps or bold and a little spacing. This covers 38 books and 34,875 speeches.

**Manifest flags suggested**
- `verse: true|false`, to force R1/R2 on or off for poetry or prose outside the verse sections. Novels have some verse; leave them off by default and allow them per work.
- `lineNumbers: "drop"|"margin"`.
- `titleOverrides`.

## 6. Needs a per-book manual fix

- **Poems 1817 (Keats).** Word-export HTML: verse inside `<table><td><blockquote><div><blockquote><div><blockquote><div><p><span class="xhtml_font_face…">` with `<br/>`s in the span and sonnet indents as collapsed ASCII spaces. Still 96% flush with the prototype. Either:
  - unwrap `span.xhtml_font*` and flatten single-column layout tables (a generic rule for "xhtml_" exports; 15 books carry `span.xhtml_big`/`xhtml_*` classes), or
  - choose a different Gutenberg edition.
- **Faerie Queene.** R6 fixes the indents. The 624 stanza numerals `p.i20` + `<p>&nbsp;</p>` would be better centred. The front matter is a cascade of `h1`/`h2`/`h3` (nav title is 300+ characters).
- **Canterbury Tales.** Glosses `*sweet` go to the margin under R1. Check this reads well at 125%. 24 transcriber's notes are inline and are real.
- **Collapsed-space books** (Faust Part One 3,172 lines, Gita 497, Lysistrata 281, Aeneid 234, sonnets couplets). The intended indentation exists only as source spaces. R1 can recover it only if it treats ASCII leading spaces beyond the block minimum as indent (the prototype does). Verify Faust Part One, whose lines carry varied source indentation from the HTML pretty-printer.
- **Hansel and Gretel and the libretti using `div.line`.** These have German/English or character layouts with large offsets (verse starts at 50% of the column). Check after R3.
- **Sonnets.** Sonnet numbers are a `<br>` line of 21 nbsp. They would be better as `h3` or centred, which can be detected with the pattern "first line is nbsp + digits".
- **Metamorphoses.** `span.linenum.mckay`/`.bell` range labels with `:before`/`:after` rules that are now dead.
- **G&S, Leaves of Grass, Idylls.** Prose stage directions and long-line continuations hard-wrapped inside `<pre>` (see R2 follow-up).

## 7. Screenshots (390×844, Noto Serif, Readium CSS; left = now, right = prototype rules)

All in `docs/superpowers/spikes/2026-10-30-classics-audit/` (the five pairs below; the full set was in the session scratchpad):
- `pair-paradise-lost-c002.png`: 30% margin, one word per row, dropcap; then fixed
- `pair-faerie-queene-c003.png`: line numbers overprinting words, paragraph-spaced lines, no `i2` indents
- `pair-complete-plays-of-gilbert-and-sullivan-c004.png`: monospace `<pre>` against hanging verse with second-voice indent
- `pair-leaves-of-grass-c004.png`: monospace `pre` against proportional hanging lines
- `pair-canterbury-tales-c004.png`: glosses jammed into the line against right-margin glosses
- `pair-hamlet-c002.png`: stage direction centring restored (right)
- `pair-divine-comedy-hell-c002.png` and `-125.png`: tercet indents kept, continuations now hang
- `pair-sonnets-c001.png`, `pair-paradise-regained-c002.png`: inline line numbers moved to the margin
- Single `*-now.png` / `*-fixed.png` files for each of the above

## 8. Files

`docs/superpowers/spikes/2026-10-30-classics-audit/` (data files such as audit.json stayed in the session scratchpad):
- **Scripts**
  - `audit.py`: static analysis, writes `audit.json`
  - `summary.py`: per-book table
  - `deadrules.py`: descendant rules broken by splitting, writes `deadrules.json`
  - `render.py`: Chrome + Readium harness. Usage: `render.py W SCALE [slugs]`, with `FIX=1` for the prototype. Needs `python3 -m http.server 8765` in `www/`.
  - `fixproto.py`: prototype rules
  - `compare.py`: before/after comparison
  - `shots.py`: screenshots
  - `grepbook.py`: snippet search
- **Data**
  - `audit.json`
  - `render-{390-1.0,360-1.0,390-1.25}.json` and the `-fix` variants
  - `table.txt` (current wrap table)
  - `compare.txt` (before/after for each book)
- **Fonts and Readium CSS:** `www/notoserif.css`, `aar/assets/readium/readium-css/`
