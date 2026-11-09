# Granger Classics formatting audit (2026-11-09)

Scope: all 372 EPUBs in `~/granger-build/classics-2026-10-30/`, unzipped into `scratchpad/epub/` (the originals are untouched). Gutenberg sources were read from the builder's cache in `~/.cache/granger/gutenberg/<id>/pg<id>-images.html`, which is the HTML the build used.

Method:
- **Static scans** of every page in all 372 books, plus a pass over the drama and libretti markup. Scripts are in `scratchpad/scripts/`: `drama.py`, `sd.py`, `broad.py`, `hidden.py`, `snip.py`. Data is in `scratchpad/drama.json`, `sd.json` and `broad.json`.
- **Phone renders** with the repo's `verse_check/shot.py`: Readium CSS 3.4.0 from the AAR, Noto Serif, 390 px wide, the app's root style. I set the harness up once with `harness.py setup`, which writes to `~/.cache/granger/verse-check/`. The shots are in `scratchpad/shots/`. There are 41 of them: 11 of plays and the Analects, and 30 of other books across all six sections, gathered on `sheet-00…09.png`.
- **Already covered elsewhere, not repeated here:** the 2026-10-30 verse audit and Plan GC fixed verse wrapping (R1–R8), alignment and line numbers. This audit is about *structure*: who speaks, what is a direction, where a passage starts.

---

## 0. Summary: the top causes, ranked

| # | Cause | Books | How bad | Where it comes from |
|---|---|---|---|---|
| 1 | **Drama has no speech model.** The builder never marks speaker, speech, stage direction or scene. It only handles Shakespeare's "NAME.`<br>`verse" by accident, and only when a speech has 2 or more `<br>`. | All 92 plays and libretti; 6 source patterns (§2) | High: this is complaint 2 | Missing feature. `verse.py` R9 is the only drama logic. |
| 2 | **The verse gloss rule fires on drama `<pre>`.** A run of 6+ spaces between speaker and speech turns the *speech* into a faint right-floated note, so it reads backwards ("CARIOLA. ......... *Never, my lord.*"). | 9 plays, 2,991 lines. G&S 2,521, Duchess of Malfi 461, Spanish Tragedy 29, She Stoops 10, La Bohème 9, School for Scandal 4, Major Barbara 3, Strindberg 2, Trojan Women 2 | High: our builder causes this | `verse._GLOSS` (R1). It also hits cast lists ("Mr. King") in prose plays. |
| 3 | **Hard-wrapped prose from `<pre>` becomes fixed "verse" lines.** R2 makes every source line a `gv-l`, so prose breaks mid-sentence at the source's 60-character column. | About 20 books. Worst: G&S 3,973 joins, Leaves of Grass 2,155, Koran (Sale) 1,005, Clouds 947 (the whole play is prose), Doctor Faustus 230, Duchess 161, Hedda 152, Frogs 142, Volpone 121, Cyrano 111, Tamburlaine 77+77 | High for Clouds; medium elsewhere | `verse._pre` (R2) has no prose reflow |
| 4 | **Text-file paragraphs merged.** Gutenberg's text→HTML conversion (the GutenMark `id="id00001"` family) set real paragraph starts as newline plus 8 spaces *inside* a `<p>`, and broke `<p>`s at the printed page breaks instead. HTML collapses both. | Analects: 648 hidden breaks and 51 page-split paragraphs. Koran (Sale): 3,546 and 123. The other 15 books in this family are clean. | High: this is complaint 1 | The source. We never repair it. |
| 5 | **Chapter and verse labels are plain text inside the paragraph** ("…actions?' CHAP. III. The Master said…") | Analects 497 `CHAP.` labels (365 mid-paragraph) and 506 verse numbers mid-paragraph. KJV 31,100 `c:v` refs (6,698 mid-paragraph). Apocrypha 5,636. Tao Te Ching ("Ch. 1. 1."). Metamorphoses I–VII 668 and VIII–XV 579 section numbers mid-paragraph. Second Treatise 251. | Medium–high | The source. We never promote labels. |
| 6 | **Act and scene labels are not headings** (they sit inside `<pre>` lines or plain `<p>`), so there's no visual break and no nav entry | Duchess (25 lines, nav: "The Duchess of Malfi (part 1/2)"), Phaedra 30, G&S 25, Volpone 15, Blue Bird 12, Alchemist 6, Siegfried 6, Pygmalion 5, Ideal Husband and Lady Windermere (plain `p`), Everyman, Rhinegold. Frogs and Clouds are one page each, with the nav reading only "Part 1". | Medium–high for drama | Split only sees real `h*`, and `<pre>` hides the labels |
| 7 | **Anchors and spacers stranded at page ends.** The `<p><a id="link2H…"/></p><div style="height:4em"><br>×4</div>` that comes *before* a chapter heading stays on the previous page. A Contents tap then lands at the foot of the previous chapter. | 82 books, 2,713 page ends that hold a link target. 183 books end pages in blank space. | Medium: a navigation bug | `chapters` split (the heading's preamble isn't carried with it) |
| 8 | **Blank spacer blocks.** `<p><br/></p>`, `div.pg_body_wrapper><br>`, `div style="height:4em"`, `<p><a id/></p>`. R7 only drops blocks with *no* children. | `br`-only: 18,209 blocks in 119 books (Poems Three Series 9,956, Aesop 1,520, Prince & Pauper 617, Lysistrata 576). Anchor-only: 6,572 in 124. Height spacers: 2,289 in 64. | Medium: big blank gaps on a phone | `gutenberg._drop_leftovers` is too strict |
| 9 | **Page and section markers left in the text** | TEI `span.tei-pb` "[pg 079]": Varieties 400, World as Will 315, Ramayan ~29. "{203}" section numbers: Pilgrim's Progress 405, Prince & Pauper 29. | Medium for those 5 | `PAGE_NUMBER` lacks `tei-pb`, and there's no rule for `{N}` |
| 10 | **Footnote clutter** | 3 Norton Dantes: 2,755 bare "[1]" markers plus `p.footnote` blocks set between paragraphs. Koran (Sale): letter markers glued to words ("friends,n", "them;o"). Koran (Rodwell): "orphan2", "thee,3". Imitation 360 "(1)". War and Peace "*" notes. 19 books with 200+ linked refs. | Medium–low | Source. We don't style or relocate notes. |
| 11 | **Weak nav titles** | "Part 1" as the first nav entry in 251 books. 2 or fewer nav entries in 11 non-poetry books (Frogs, Clouds, Lysistrata, Salome, Tristan…). Title-page mega-titles (Lysistrata: "Translated from the Greek of — Illustrations by Norman Lindsay"). 55 books have a nav title over 70 characters. | Low–medium | `chapters.clean_title` fallback, no headings in the source |
| 12 | `<pre>` left in non-verse sections, so monospace | 61 books, 1,976 `pre`. Essays (Cotton) 1,215, mostly headings. On the Nature of Things (Lucretius, filed under philosophy, entirely verse). Tom Jones, Rob Roy, Grimm verse. | Medium for Lucretius and Cotton | R2 is gated on `reads_as_verse` |
| 13 | "List of Illustrations" pages kept after images are removed | 17 books (Pride & Prejudice, Huck Finn, Aesop…) | Low | No rule |
| 14 | Very long headings (chapter summaries as `h2`) | 22 books (Tom Jones 38, Don Quixote 27, Pickwick 22, Morte d'Arthur 21) | Low | Source; Readium forces `h*` sizes |

Checked and fine:
- **Italics** are preserved. The only gaps are the Genji volumes, where the "missing" `<i>` were italic page numbers inside `span.pagenum`, which we drop on purpose.
- **Transcriber's notes:** only Canterbury (real content) and Conquest of Happiness (an "Obvious printers' errors…" note) remain.
- **`[Illustration]` text:** none.
- **Medea** (centred `p.char` speakers, numbered strophes) reads well. It's the model for the drama fix.

---

## 1. The Analects (gc-analects, Legge, #3330)

**Source (cache, lines 261–290).** The text→HTML conversion is GutenMark-style: `id="id00009"` ids and no classes. Structure exists **only as whitespace**:

```html
<h5 id="id00009">        CHAPTER I. 1. The Master said, 'Is it not pleasant to learn with
a constant perseverance and application?
        2. 'Is it not delightful to have friends coming from distant
quarters?'
        3. 'Is he not a man of complete virtue, ...'</h5>

<p id="id00010">        CHAP. II. 1. The philosopher Yu said, 'They are few who, being
...
        2. 'The superior man bends his attention to what is radical.</p>

<p id="id00011">That being established, all practical courses naturally grow up. ...
        CHAP. III. The Master said, 'Fine words and an insinuating
appearance are seldom associated with true virtue.'
        CHAP. IV. The philosopher Tsang said, ...</p>
```

- **Newline + 8 spaces** marks every real chapter or verse start. Continuation lines sit at column 0.
- A **`<p>` boundary is a printed page break**: `id00011` starts mid-sentence of Chapter II, verse 2.
- Chapter I's whole text is inside an **`h5`**.

**EPUB (c002.xhtml).** It's identical, because nothing is lost or added: the browser collapses the whitespace. On a phone (`shots/analects.png`):
- Chapter I is a bold run-on heading.
- "CHAP. III." and "CHAP. IV." sit mid-paragraph.
- Chapter II verse 2 is split across two paragraphs.
- Verse numbers "2." "3." run inline.

Counts: 497 `CHAP.` labels, 365 of them mid-paragraph. 648 hidden paragraph starts. 51 page-break paragraphs that start in mid-sentence.

**Fix (a new "text-paragraph restorer", see §4 F4)** would emit:

```html
<h4 class="gx-chap">Chapter II</h4>
<p class="gx-v"><span class="gx-n">1</span> The philosopher Yu said, 'They are few who, ...'</p>
<p class="gx-v"><span class="gx-n">2</span> 'The superior man bends his attention to what is radical. That being established, all practical courses naturally grow up. ...'</p>
<h4 class="gx-chap">Chapter III</h4>
<p class="gx-v">The Master said, 'Fine words ...'</p>
```

It would also move the `h5` that holds Chapter I's body back into the text. **Koran (Sale) #7440** has the same defect: 3,546 hidden starts and the glued letter footnote markers. Its sura headings are already real `h*`, so it needs only the paragraph restorer, not the label promoter. **Tao Te Ching** ("Ch. 1. 1." at a paragraph start) needs only the label promoter.

---

## 2. Drama and libretti: where structure is lost

### 2.1 Speaker patterns (92 works: 80 drama rows minus the Sonnets, plus 13 libretti)

| Pattern | What the source does | Works | Count |
|---|---|---|---|
| **A. Label line in the speech block** | `<p class="drama">HAMLET.<br>line<br>line</p>` (PG's current Shakespeare HTML), or `JACK.<br> prose` | 37 Shakespeare, Oedipus (#31), House of Atreus (`<b>CHORUS</b><br>`), Importance, Arms and the Man, Playboy, Riders to the Sea, Cyrano (`CYRANO (catching up…):<br>`, colon) | **44** |
| **B. Speaker as its own block** | `<p>TESMAN.</p><p>[Picks up…] What are you…</p>`; `<h5>Hecuba</h5>`; `p.char`; `p.character`; a lone line in `<pre>` | Hedda Gabler, Lysistrata, Salome, Medea (`p.char`, already good), Trojan Women (175 speakers as **`h5`**), Rhinegold (`span.char`), Siegfried (`p.char`), Hansel & Gretel (`p.character`), Tartuffe (first line, no full stop), Phaedra and Bacchae (lone caps line in `pre`) | **11** |
| **C. Run-in capitals, prose `<p>`** | `<p>MANDERS. I have chosen…</p>`; `VOLP [ASIDE.]:`; Blue Bird's `THE OAK Your father…` (no punctuation) | Ghosts, Uncle Vanya, Sea Gull, Man and Superman, Pygmalion, Major Barbara, She Stoops, School for Scandal, Way of the World, Strindberg (#8499), Spanish Tragedy, Blue Bird, Rivals (mixed), La Bohème, Tristan | **15** |
| **D. Run-in capitals inside `<pre>`** (now `div.gv-pre`) | `  XAN. Nothing else smart?`, or `  XAN.` alone then a blank line; `CARIOLA.<26 spaces>Never, my lord.`; `3 Men:<12 spaces>For the Maiden…` | Frogs, Doctor Faustus, Tamburlaine 1 and 2, Volpone (`MOS:`), Alchemist, Duchess of Malfi, G&S (title case and colon) | **8** |
| **E. Run-in title-case abbreviation, plain** | `Soc. Come now, tell me…`; `<p class="dialog">Petra. But how…` | Clouds, An Enemy of the People | **2** |
| **F. Run-in label in a tag** | `<i>Nora</i>.`; `<i>Matt</i>.`; `<span class="smcap">Gina.</span>`; `<span class="smcap">mrs. cheveley</span>.` | `<i>`: Doll's House, Beggar's Opera, Duenna, Fontainbleau, Castle of Andalusia, Cavalleria, Everyman, Faust (Gounod). `span.smcap`: Wild Duck, Ideal Husband, Lady Windermere, Martha. | **12** |

**What the builder does with each:**
- **A.** R9 gives the label `gv-sp` (all-small-caps) **only when the block has 2 or more `<br>`**. Prose speeches and many one-line speeches have one `<br>`, so they keep plain roman capitals.
  - Shakespeare + Oedipus: **13,294 labels styled and 17,141 not, 56% unstyled**. One scene therefore mixes two looks (`shots/pair-shakespeare.png`: the gravediggers are all plain "FIRST CLOWN.").
  - Importance, Arms, Playboy, Riders and Cyrano: 0 styled.
  - Where styling applies, small caps of a capitalised name render *smaller* than the text (x-height), so the name reads like a footnote, not a heading (`shots/hamlet-top.png`).
  - False positive: "LADY MACBETH." in Macbeth's cast list gets `gv-sp`.
- **B.** No change. A speaker paragraph and a one-line speech look the same, and both get the 1em first-line indent (`shots/lysistrata.png`, `shots/hedda.png`). Trojan Women's speakers are headings.
- **C, E, F.** No change. Only F shows the label in italic or small caps.
- **D.**
  - Labels stay plain text inside `gv-l` lines.
  - A label alone on a line is followed by a `gv-gap`, so it floats away from its speech (Frogs: "XAN." gap "What! must I bear…").
  - In Duchess and G&S the **gloss rule moves the speech into a right-floated faint note and leaves the label** (`shots/malfi.png`, `shots/gs.png`). This is pipeline-introduced.

### 2.2 Stage directions (from `sd.json`)

| Presentation | Works |
|---|---|
| Own block **with a class** (`p.scenedesc`, `p.right` "Exit", `p.stage`, `p.stagedir`, `p.direct3/4`, `p.regie`, `p.dir-c/-r`, `td.tdr`) | 37 Shakespeare, Medea, Everyman, Enemy, Arms, Riders, Hansel, Faust-G, Cavalleria, Fontainbleau, Castle, Atreus, Martha (partly): about 50 |
| Own block, **no class**: brackets, parentheses or all-italic | Hedda 414, Siegfried 469, Rhinegold 285, Spanish Tragedy 125, Blue Bird 125, Strindberg 134, La Bohème 129, Trojan Women 122, Ideal Husband 116, Wild Duck 99, Lady W 95, Tristan 89, Rivals 67, She Stoops 58, Duenna 56, Importance 51, Salome 34, Ghosts 32… |
| **A line inside `<pre>`**, now a `gv-l`, hard-wrapped | G&S 1,123, Beggar's Opera 391, Cyrano 280, Volpone 247, Cavalleria 246, Alchemist 236, Duchess 203, Oedipus 165, Bacchae 162, Doctor Faustus 134, Tamburlaine 103+121, School for Scandal 110, Frogs 39, Clouds 55 |
| Inline in a speech, `[…]` or `(…)` | Every prose play: Shaw 565–663, Ibsen and Wilde 260–490, Cyrano 546, Tamburlaine 668+698 (mostly footnote refs) |

- **Shakespeare.** Its own-block directions are centred again thanks to R4, and show as italic and centred (`shots/hamlet-top.png`).
- **Hedda's own-block directions** start with "[" and run on in roman, indistinguishable from speech.
- **In `pre` plays,** "Enter BAJAZETH, the KINGS OF FEZ, … with / others, in great pomp." comes out as two hanging lines in the same face as the verse.

### 2.3 Before and after, by producer (source → EPUB → what's wrong)

- **Hamlet** (#1524, PG Shakespeare HTML).
  - Source: `<p class="drama">\nFIRST CLOWN.<br>\nIs she to be buried in Christian burial, when she wilfully seeks her own\nsalvation?\n</p>`
  - EPUB: the same `p.drama`, unmarked, because it has one `<br>`.
  - Where there are 2 or more, the EPUB has `<p class="drama gv"><span class="gv-l gv-sp">POLONIUS.</span>…`.
  - The scene heading is `<h3><b>SCENE I. A room in Polonius's house.</b></h3>`: still in capitals, because `_quiet_headings` skips text under `<b>` mixed with capitals. Readium's h3 is 1.2rem.
  - Wrong: two speaker styles, the styled one smaller than the text, and no extra space between speeches beyond `p.drama {margin-top:.5em}`.
- **Frogs** (#7998, Harvard Classics, `<pre>`).
  - Source: `  DIONYSUS. Aye, what you will, except <i>I'm getting crushed:</i> Fight shy\n  of that: I'm sick of that already.\n\n  XAN. Nothing else smart?\n\n  XAN.\n\n  What! must I bear these burdens…`
  - EPUB: `<span class="gv-l">DIONYSUS. Aye, … Fight shy</span><span class="gv-l">of that: …</span><span class="gv-gap"/>…<span class="gv-l">XAN.</span><span class="gv-gap"/><span class="gv-l">What! must I…`
  - Wrong: labels unmarked, prose hard-wrapped, a lone label cut off from its speech, the whole play on one 165 KB page, nav "Part 1".
- **Clouds** (#2562, Hickie's *prose* translation in `<pre>`).
  - Source lines run about 55 characters: `Soc. Come now, tell me your own turn of mind; in order\nthat, when I know of what sort it is, I may now, after\nthis, apply…`
  - EPUB: every source line becomes a `gv-l`, giving the ragged result in `shots/clouds.png`.
  - Wrong: the structure exists only as the "Soc."/"Strep." prefix. Prose is presented as verse.
- **Lysistrata** (#7700).
  - Source: `<p>\n      CALONICE\n    </p>\n<p>\n      By Pandrosos I if your hand touches her<br> I'll spread you out…\n    </p>\n<p>\n<br>\n</p>`
  - EPUB: the same. Speeches with 2 `<br>` became `gv`, those with 1 did not. 576 `<p><br/></p>` spacers kept.
  - Wrong: speaker paragraph = text paragraph (`shots/lysistrata.png`).
- **Duchess of Malfi** (#2232, `<pre>`).
  - Source: `  CARIOLA.                        Never, my lord.`
  - EPUB: `<span class="gv-l"><span class="gv-n">Never, my lord.</span> CARIOLA.</span>`, rendered as "CARIOLA." with "Never, my lord." small and faint at the right margin.
  - Also: "ACT I" etc. are verse lines, so the nav reads "The Duchess of Malfi (part 1/2)".
- **G&S** (#808): `  3 Men:            For the Maiden fair…` becomes the same misfire, 2,521 times. Prose stage directions hard-wrapped (`shots/gs.png`).
- **Hedda Gabler** (#4093): `<p>\nTESMAN.\n</p>\n<p>\n[Picks up the slippers from the floor.] What are you looking at, Hedda?\n</p>`. Kept as is; labels and directions are plain (`shots/hedda.png`).
- **Tamburlaine 1** (#1094, Marlowe, `<pre>`).
  - `Enter BAJAZETH, the KINGS OF FEZ, … with\n others, in great pomp.` becomes two hanging verse lines.
  - `USUMCASANE. For as, when Jove…` keeps a run-in plain label.
  - Footnote numbers `130` are glued to line ends as links.
- **Medea** (#35451, Murray). `p.char` speakers (centred, small caps "Chorus.") and `p.direct3` directions. **Reads well** (`shots/medea.png`). It's the target look.
- **Importance** (#844, Wilde): `<p>JACK.<br/> I haven't asked you…</p>`. Pattern A with one `<br>`, so the label is unmarked and set as plain capitals.

---

## 3. Other findings across sections

The static scan covered all 372 books. Visual checks covered 30 books (`shots/sheet-*.png`), and every section was looked at.

- **Scripture**
  - **KJV:** verse refs are plain text at the paragraph start, and 6,698 are mid-paragraph, *as in the source*: "duke Kenaz, 36:16 Duke Korah". **Koran (Rodwell):** footnote digits glued ("orphan2"), and the heading "Sura XCIII.1-the Brightness [IV.]". **Koran (Sale):** see §1. **Pilgrim's Progress:** 405 "{203}" markers plus run-in "TALK."/"FAITH." dialogue labels (pattern C in prose).
  - The eBible Bibles (usfm) are clean. Their lowercase continuation lines are correct modern verse layout.
- **Philosophy**
  - **Varieties** and **World as Will:** TEI `[pg 079]` left inline.
  - **Essays (Cotton):** 1,215 `pre` (headings in monospace).
  - **On the Nature of Things:** verse in `<pre>` but filed as philosophy, so R2 never runs (needs `"verse": true`).
  - **Art of War:** the "Contents" heading is followed by the translator's introduction (the heading belongs to a removed list).
  - **Republic, Ethics, Leviathan, Meditations:** fine. Meditations' heading reads "Biographical Sketch / of / Marcus…" on three centred lines.
- **Poetry:** fine after GC, apart from the Norton Dantes' footnotes (§0 #10). Song of Roland has 55 Roman-numeral headings in capitals ("XXXVIII"). They're legitimate, but the heading-capitals rule flags them.
- **Novels**
  - **Tom Jones:** book summaries as huge `h2`.
  - **Pride & Prejudice and 16 others:** a "List of Illustrations" with page links.
  - **Don Quixote, Middlemarch, Jungle Book…:** embedded songs don't hang, because novels are off for R1 (a known decision).
  - **War and Peace:** "*" notes inline.
  - **Stranded anchors** (§0 #7) are worst in novels: Tom Jones 208, Little Dorrit 70, Our Mutual Friend 66.

---

## 4. Proposed pipeline fixes

Order of value: F1 → F2 → F3 → F4 → F5, then F6–F10.

### F1. `drama.py`, a speech normaliser (new module)

**Where it runs:**
- **Pre-split part** (`drama.structure(body, work)`): called in `pack_builder` between `gutenberg.clean` and `chapters.split`, so promoted act and scene headings drive the split and the nav.
- **Per-page part** (`drama.mark(soup)`): called in `epubpack._prepare` *before* `verse.mark`, on works in `drama` or `libretti` or with a new manifest field `drama: true|false`.

**Output vocabulary** (`gd-` prefix, alongside `gv-`):

```html
<h2 class="gd-act">Act II</h2>
<h3 class="gd-scene">Scene I. A room in Polonius's house.</h3>
<div class="gd-sd">Enter Polonius and Reynaldo.</div>              <!-- div, not p: Readium leaves divs' alignment alone -->
<div class="gd-speech gv"><span class="gd-who">Polonius</span>
  <span class="gv-l">Give him this money and these notes, Reynaldo.</span></div>   <!-- verse speech -->
<p class="gd-speech gd-prose"><span class="gd-who">First Clown</span> Is she to be buried …
  <span class="gd-sdi">[Aside.]</span> …</p>                         <!-- prose speech, run-in label -->
```

**CSS** (appended after `verse.CSS`):

```css
.gd-speech { margin: .9em 0 0; text-indent: 0; }
.gd-who { font-weight: 700; font-variant-caps: all-small-caps; letter-spacing: .06em; }
.gd-speech.gv > .gd-who { display: block; margin-bottom: .1em; }      /* label on its own line over verse */
.gd-prose > .gd-who { margin-right: .35em; }                          /* run-in over prose */
.gd-sd { font-style: italic; margin: .6em 0 .6em 1.5em; text-indent: 0; }
.gd-sd i, .gd-sd em, .gd-sdi i { font-style: normal; }               /* print convention: names roman inside italic */
.gd-sdi { font-style: italic; }
.gd-scene { margin-top: 2em; }
```

**Label text:**
- Strip the trailing "." or ":" into a hidden span, or keep it, so Listen still pauses after the name.
- Title-case all-capital labels so small caps render evenly ("Mrs. Alving" → small caps).
- Keep a parenthetical after the name ("CYRANO (catching up…):") as a `gd-sdi`.

**Detection, all per work:**
1. **Cast set first.**
   - Collect candidate labels by pattern: a capitals line alone, run-in capitals before `.`/`:`, a run-in title-case word, a first-child `i`/`b`/`span.smcap` holding a name.
   - Keep labels that occur 3 or more times, cover at least 30% of the work's text blocks, and number at most 60 distinct.
   - Seed from the dramatis personae when one is found (a heading matching `Dramatis Person|Persons|Characters|Cast`).
   - This keeps "O, …", "I …" and "Mr. …" out.
2. **Pattern A:** the first `<br>` segment of a block, or the first `gv-l`, is a cast label. Applies at any `<br>` count, which fixes the 17,141 unstyled Shakespeare labels.
3. **Pattern B:** a block (`p`, `h5`, `p.char`, `p.character`, or a `gv-l` followed by `gv-gap`) whose whole text is a cast label. Merge it into the next speech block. Trojan Women's `h5` speakers must be handled pre-split so they never count as headings.
4. **Patterns C, E, F:** a block or line starting with a cast label then `.`, `:`, `[`/`(`, or (for Blue Bird) a space. Wrap the label.
5. **Pattern D (in `pre`):**
   - Run before `verse._pre` and its gloss rule.
   - Split the `<pre>` text into speeches at blank lines.
   - A speech's first line starting with a cast label becomes `gd-who`. A label-only line joins the next non-blank line.
   - A label followed by 6 or more spaces is a label, never a gloss.
   - Then hand each speech to `verse._pre` logic as its own `div.gd-speech.gv`.
6. **Stage directions:**
   - A block or line whose text is wholly `[…]`/`(…)`, starts with `Enter|Exit|Exeunt|Re-enter|Manet|Flourish|Alarum|Curtain|Scene`, or is at least 85% italic becomes `gd-sd`.
   - Existing classes join it: `scenedesc`, `stage`, `stagedir`, `direct\d`, `regie`, `dir-[cr]`, and `p.right` whose text is Exit/Exeunt.
   - In `pre`, join a direction's continuation lines, deeper-indented or before the closing bracket, into one block (reflow).
   - Inline `[…]`/`(…)` inside a speech becomes `gd-sdi`, unless it's a footnote ref (digits only).
7. **Acts and scenes:**
   - A line or block matching `^\[?(ACT|Act|SCENE|Scene|ACTUS|Scena)\s+([IVXLC]+|\d+|First…Fifth)\b` and under about 100 characters is promoted to `h2.gd-act`/`h3.gd-scene`, pre-split.
   - Inside a `<pre>`, split the `pre` around the line.
   - Also title-case `<h3><b>SCENE I. …</b></h3>`: `_quiet_headings` should look through `<b>`.
8. **No-op guard:** if fewer than 50% of speech-like blocks match one pattern, leave the work alone and log it. Medea passes unchanged. Its `p.char` simply gains `gd-who` styling, or opt it out with `drama: false`.

**Fixes to `verse.py` that go with F1:**
- `_GLOSS` must not fire when the left part is a cast label, or more simply in any drama or libretti work.
- Elsewhere, require the gloss to start with `*` or be at most 25 characters with no sentence punctuation. The 6,091 Canterbury glosses keep working; check its 253 long ones.
- R9's `gv-sp` is replaced by `gd-who` in drama. For non-drama verse, keep R9 but skip blocks under a "Dramatis Personae"/"Persons" heading.

**Per-work manifest hints:**
- `drama: {"labels": "titlecase"}`: Clouds, Enemy of the People, G&S (`Name:`)
- `drama: {"prose": true}`: Clouds (reflow the whole `pre`), possibly Frogs' prose passages
- `drama: {"labelsWithoutStop": true}`: Blue Bird, Rivals
- `drama: false`: Medea if it's preferred untouched, Faust (Gounod) and Cavalleria (bilingual tables)
- Sonnets sits in the drama section but must be skipped: no cast set means a no-op.

### F2. Reflow hard-wrapped prose from `<pre>` (`verse._pre`)

- Join line *i+1* onto line *i* when *i* is within about 15% of the block's longest line, *i* has no closing punctuation or ends in a word, and *i+1* starts lowercase or deeper-indented.
- If 60% or more of a `pre`'s lines join, emit it as `<p>` paragraphs, split at blank lines, not `gv`.
- Covers Clouds, the G&S directions, Leaves of Grass continuation lines (already noted in the GC audit as an R2 follow-up), Hedda's opening, and Doctor Faustus or Tamburlaine directions.
- **Also run R2 in every section**, not just verse ones. A `pre` is never right on a phone. This fixes Lucretius, Cotton and the Grimm songs. Add `"verse": true` for On the Nature of Things.

### F3. Text-paragraph restorer, `textfix.py` (pre-split, GutenMark-family sources)

- **Detect:** a `<p>` whose lines after the first include at least one indented 4+ spaces while others sit at column 0, with at least 20 such in the book. Today this fires only on Analects (648) and Koran (Sale) (3,546), so it's safe to make generic.
- **Split** the `p` at `\n[ ]{4,}`.
- **Merge** a `<p>` that starts at column 0 with a lowercase letter or mid-sentence into the previous paragraph when the previous one doesn't end in `. ? ! ' ” :` (page-break joins).
- **Labels:** `^CHAP(?:TER)?\.\s+([IVXLC]+)\.\s*` at a new paragraph start becomes `h4.gx-chap` "Chapter N". A following `^(\d{1,3})\.\s` becomes `span.gx-n`. Also `^Ch\.\s*\d+\.` for Tao Te Ching. Enable it with a manifest field `labels: "chapter-verse"` on the Analects and Tao Te Ching, since a generic rule risks false hits.
- **Headings:** an `h*` with over 150 characters of body text containing a verse label is demoted to `p` (Analects id00009).
- **Book titles:** "Confucian Analects. Book I. Hsio R" becomes "Book I. Hsio R", via `titleOverrides` or by stripping the repeated work title.

CSS:

```css
.gx-n { font-weight: 700; font-size: .8em; margin-right: .4em; opacity: .75; }
.gx-chap { font-size: 1rem; margin: 1.4em 0 .4em; }
```

### F4. Scripture verse numbers (`kjv`-style refs)

- With manifest `verseRefs: "split"` (KJV, Apocrypha, Douay): split paragraphs at `\s(\d{1,3}:\d{1,3})\s(?=[A-Z‘“(])`.
- Wrap each ref at a paragraph start in `span.gx-n`, the same CSS as F3.
- This turns 6,698 mid-paragraph KJV verses into their own lines and makes all 31k refs visibly labels.

### F5. Carry heading preambles across the split (`chapters`)

- In `_groups`/`_by_size`, when a part starts at a heading, pull the preceding run of textless siblings into the heading's part: anchor-only `p`, `div[style*=height]`, `<p><br>…</p>`, `hr`.
- Better still, move their ids onto the heading and drop them.
- Fixes 2,713 Contents targets in 82 books and the blank page ends in 183.

### F6. Spacer cleanup (`gutenberg._drop_leftovers`)

- Treat a block whose only children are `br`, whitespace, empty anchors or id-spans as empty: keep its ids as spans, then decompose.
- Drop `div[style*="height"]` with no text.
- Keep one `<hr>`-style break where 2 or more consecutive spacers stood. Optional: `hr.gx-gap` so scene breaks survive.
- Effect: 18,209 `br`-only blocks, 6,572 anchor-only blocks, 2,289 height spacers.

### F7. Markers (`chapters.PAGE_NUMBER`, `gutenberg`)

- Add `tei-pb` (and `tei-anchor` when empty) to `PAGE_NUMBER`.
- Drop `^\{\d{1,4}\}\s` at a paragraph start when a book has 50 or more (Pilgrim's Progress). Prince & Pauper's mid-text `{1}` needs checking first: they may be note refs.

### F8. Footnotes

- `p.footnote`/`div.footnote`/`p.foot` interleaved with the text get CSS `.footnote, .foot { font-size: .85em; opacity: .8; margin-left: 1.5em }`.
- Bare `[N]` markers become `<sup class="gx-ref">`.
- Better: emit `epub:type="noteref"`/`aside epub:type="footnote"`. **Check whether Readium Kotlin 3.4 shows footnote popups before relying on it.**
- Koran (Sale) letter markers glued after punctuation (`,n`, `;o`) and Koran (Rodwell) digit markers need a per-work rule: `[,;:.](?=[a-z]\b)` / `(?<=[a-z,;])\d{1,3}\b`. Risky, so gate it on the manifest.

### F9. Navigation titles

- Name a title page "Title page" instead of "Part 1" (251 books).
- Promoted act and scene headings (F1) fix the drama navs.
- Add `titleOverrides` for Lysistrata and Salome's title-page names.

### F10. Small

- Drop a "List of Illustrations"/"Illustrations" heading and its list when the book has no images left (17 books).
- Add `h2.gx-long` styling for headings over 80 characters (Tom Jones, Don Quixote): 1rem, normal weight, with a `:root:root` boost to beat Readium's `!important` sizes.

---

## 5. Verification: `drama_check`, alongside `verse_check`

**Static checks,** in `tools/pack-builder/drama_check/static.py`, unit-tested on fixtures. One fixture per pattern A–F, taken from the snippets above: Hamlet, Lysistrata, Ghosts, Frogs `pre`, Clouds, Doll's House, plus the Duchess gloss case.

| Metric | Pass |
|---|---|
| Speech coverage: speech blocks or lines starting with a cast label ÷ those with a `gd-who` | ≥ 98% per play |
| Stray labels: a cast label at a block start outside `gd-who` | ≤ 1% |
| Suspicious labels: a `gd-who` not in the cast set, or seen fewer than 3 times | listed for review, ≤ 5 per play |
| Gloss misfires: `.gv-n` with 3 or more letters not starting with `*`, in any work | 0 (today 2,991 in drama) |
| Stage-direction coverage: blocks or lines matching the direction patterns without `gd-sd`/`gd-sdi` | ≤ 2% |
| Act/scene labels outside `h*` | 0; nav has an Act entry for every play with acts |
| Hard-wrap pairs (line ≥ 45 characters, no end punctuation, next line lowercase) in `gv`, outside the eBibles | 0 in drama; report the rest |
| Stranded link targets at a page tail | 0 |
| Hidden paragraph starts (`\n {4,}` after a column-0 line inside a `p`) | 0 |

**Render checks,** reusing `verse_check/harness.py` (Readium CSS, Noto Serif):
- for each `gd-who`, its computed `font-weight` is ≥ 600 and its box precedes the speech text;
- `gd-sd` is italic;
- run the existing verse pass rule over the restructured `gd-speech.gv` blocks, so the R1 numbers don't regress.

**Screenshot pairs for Neal:** Hamlet (prose and verse scenes), Lysistrata, Hedda, Frogs, Clouds, Duchess, G&S, Importance, Medea (unchanged), Analects Book I.

**Rebuild size:** all 92 drama and libretti works, the Analects, Koran (Sale), Tao Te Ching, KJV, Apocrypha and Douay. F5–F7 touch about 150 books. Vectors for any rebuilt book need rebuilding, as in Plan GC Task 11. Saved reading positions shift.

## 6. Books needing per-work manifest hints

| Book | Hint |
|---|---|
| analects, tao-te-ching | `labels: "chapter-verse"`; titleOverrides for book titles |
| koran-sale | restorer (auto), plus the footnote-marker rule (F8) |
| koran-rodwell | footnote-marker rule |
| king-james-bible, apocrypha-deuterocanonical-books, douay-rheims-bible | `verseRefs: "split"` |
| clouds | `drama: {labels: "titlecase", prose: true}` |
| enemy-of-the-people, complete-plays-of-gilbert-and-sullivan | `drama: {labels: "titlecase"}` (G&S: colon) |
| blue-bird, rivals | `drama: {labelsWithoutStop: true}` |
| medea, faust-gounod, cavalleria-rusticana | `drama: false`, or verify a no-op |
| sonnets | not a play; `drama: false` |
| on-the-nature-of-things | `verse: true` |
| pilgrim-s-progress | `{N}` stripping; its dialogue labels could take F1 (`drama: true`) |
| lysistrata, salome | titleOverrides (title-page names in nav) |
| frogs, clouds | after F1, check the page split now has acts (Clouds has no acts; consider `splitAt` on the parabasis or leave as is) |

## 7. Files

- Report: `scratchpad/formatting-audit.md`
- Scripts: `scratchpad/scripts/{drama,sd,broad,hidden,snip}.py`
- Data: `scratchpad/{drama,sd,broad}.json`
- Screenshots: `scratchpad/shots/`
  - `analects.png`, `hamlet-top.png`, `hamlet-c003.png`, `pair-shakespeare.png`, `macbeth-top.png`, `frogs.png`, `clouds.png`, `lysistrata.png`, `malfi.png`, `gs.png`, `hedda.png`, `medea.png`
  - `s-<slug>.png` and `sheet-00…09.png` for 30 books across sections
- Unzipped EPUBs: `scratchpad/epub/<slug>/`
