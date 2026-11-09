# Granger Classics: missing works audit (2026-11-09)

Baseline: `tools/pack-builder/classics/manifest.json`, 372 works.

How each id was checked:
- **Gutenberg:** every `PG #` below was read from the live bibliographic record on gutenberg.org on 2026-11-09. Each record's Copyright field says "Public domain in the USA." unless I flag otherwise. The catalog CSV I searched was current to 2026-11-08 (newest #79956).
- **Standard Ebooks (SE):** each SE link was checked as a live, downloadable page. SE also has placeholder pages for future public-domain books (for example *The Grapes of Wrath*, "enters PD 2035"); I rejected those.
- **Faded Page (FP):** pids come from its search, and I only list books whose first publication was 1930 or earlier.
- **Wikisource (WS):** page titles come from its search.

★ marks a top pick.

---

## 1. Martin Luther

What is on Gutenberg (all records say PD in USA):

| Work | PG # | Edition / translator | Note |
|---|---|---|---|
| 95 Theses ("Disputation … on the Power and Efficacy of Indulgences") | **274** | Philadelphia Edition (Jacobs) | short |
| *Works of Martin Luther*, Vol. I (Philadelphia, A. J. Holman, 1915) | **31604** | Jacobs et al. | holds the 95 Theses with the three explanatory letters, the Treatise on Baptism, A Discussion of Confession and The Fourteen of Consolation |
| *Works of Martin Luther*, Vol. II (Philadelphia, 1916) | **34904** | Jacobs et al. | holds the **Open Letter to the Christian Nobility**, the **Prelude on the Babylonian Captivity of the Church** and **A Treatise on Christian Liberty** with the letter to Leo X, plus two minor treatises |
| Concerning Christian Liberty, with Letter to Pope Leo X | 1911 | (Wace & Buchheim) | standalone; duplicates Vol. II |
| Large Catechism | **1722** | Bente & Dau, *Triglot Concordia* 1921 | clean PD |
| Small Catechism ("Luther's Little Instruction Book") | 1670 | Robert E. Smith (Project Wittenberg, modern) | Gutenberg marks it PD, but it is a modern volunteer translation "under continuous revision", not a historical edition. Acceptable, but flag it. |
| Selections from the Table Talk | 9841 | Capt. Henry Bell (1652), Morley intro | optional |
| Treatise on Good Works | 418 | Reu | optional |
| Smalcald Articles | 273 | Bente & Dau | optional |
| Commentary on Galatians | 1549 | Graebner | optional |
| **Bondage of the Will** | — | **not found** on Gutenberg, SE, Wikisource or Faded Page | skip |

**Recommendation: two books.**
1. ★ **Works of Martin Luther, Vol. II (PG 34904)** as the main book, since it holds the three great treatises of 1520 in one PD translation. Add **PG 274 (95 Theses)** as its own small book, or use Vol. I (31604) if you'd rather ship whole volumes; Vol. I opens with the Theses and the letters.
2. ★ **Large Catechism (PG 1722)**. Add the Small Catechism (1670) only if you accept the modern-translation caveat.

## 2. US founding documents

| Doc | PG # | Note |
|---|---|---|
| Declaration of Independence | **1** (also 16780, 300) | confirmed #1 |
| Constitution | **5** | Articles 1–7 only, **no amendments** |
| Bill of Rights | **2** | Amendments I–X |
| Amendments XI–XXVII | — | PG 19581/19582 are **audio files only** (their text is a 404 stub). Use NARA or Wikisource text instead (US government works, PD). |
| Articles of Confederation | — | not on PG; WS "Articles of Confederation" |
| Washington's Farewell Address | — | not on PG as its own book; WS "George Washington's Farewell Address" |
| Anti-Federalist Papers | — | no PD compiled volume exists; the familiar collections are modern editor compilations (Ketcham, Storing). **Skip.** |
| Lincoln | 4 (Gettysburg), 8 (2nd Inaugural), 9 (1st Inaugural), 22082 (Emancipation Proclamation), 26250 (Cooper Union); ★ **14721** *Speeches & Letters of Abraham Lincoln, 1832–1865* (ed. Merwin Roe) | 14721 is the best one-volume pick |
| Franklin, Autobiography | ★ **20203** (ed. Pine, 1916, from the manuscript) or 148 (Harvard Classics, Eliot) | 20203 is fuller |

**Recommendation: one compiled book, not six tiny ones.** Call it **"Founding Documents of the United States"** and build it from the PD texts: the Declaration (PG 1), the Articles of Confederation (WS), the Constitution (PG 5), the Bill of Rights (PG 2), Amendments XI–XXVII (NARA/WS) and Washington's Farewell Address (WS). Optional extras: the Gettysburg Address (4), the Emancipation Proclamation (22082) and the Second Inaugural (8). The manifest already supports non-Gutenberg entries (`gutenberg: null`, as with the World English Bible), so a compiled source fits. Separate 3–30 KB books would clutter the shelf and give a poor reading experience. Ship Lincoln (14721) and Franklin (20203) as their own books.

## 3. Hemingway (all published 1930 or earlier)

| Work | Source |
|---|---|
| ★ The Sun Also Rises (1926) | **PG 67138** (Scribner 1926); also SE |
| ★ A Farewell to Arms (1929) | **PG 75201** (Scribner 1929); also SE |
| ★ Men Without Women (1927) | **PG 69683** (Scribner) |
| In Our Time (1925 story collection) | **Not on PG.** PG 61085 is the 1924 Paris *in our time* (18 vignettes only, Three Mountains Press); it is a curiosity, not the book. Use FP pid 20201139 (1925), or SE *Short Fiction* (standardebooks.org/ebooks/ernest-hemingway/short-fiction, which bundles In Our Time and Men Without Women). |
| The Torrents of Spring (1926) | Not on PG or SE as a standalone; FP pid 20210135 (1926). SE *Short Fiction* also says it includes the novellas. |
| Three Stories & Ten Poems (1923) | PG 59603 (optional) |

## 4. Faulkner

| Work | Source |
|---|---|
| ★ The Sound and the Fury (1929) | **PG 75170** (Random House 1929); also SE |
| ★ As I Lay Dying (1930) | not on PG; **SE** standardebooks.org/ebooks/william-faulkner/as-i-lay-dying |
| Soldiers' Pay (1926) | SE william-faulkner/soldiers-pay; FP 20181197 |
| Mosquitoes (1927) | FP 20191201 only (first pub. 1927, so US-PD) |
| Sartoris (1929) | **not found** on PG, SE or FP |

## 5. Agatha Christie, everything through 1930

Every one is on PG now. Murder at the Vicarage (78220) is already in the collection.

| Work (year) | PG # | Pick? |
|---|---|---|
| The Mysterious Affair at Styles (1920) | **863** | ★ |
| The Secret Adversary (1922) | **1155** | ★ |
| The Murder on the Links (1923) | **58866** | ★ |
| The Man in the Brown Suit (1924) | 61168 | yes |
| Poirot Investigates (1924) | **61262** | ★ |
| The Secret of Chimneys (1925) | 65238 | yes |
| The Murder of Roger Ackroyd (1926) | **69087** (Grosset 1926) | ★★ the essential one |
| The Big Four (1927) | 70114 (Dell reprint) | skip: weak book |
| The Mystery of the Blue Train (1928) | 72824 (Pocket reprint) | optional |
| The Seven Dials Mystery (1929) | 75288 (Bantam reprint) | optional |
| Partners in Crime (1929) | 78342 (Dodd Mead 1929) | yes |
| The Mysterious Mr. Quin (1930) | **78084** | ★ |
| Giant's Bread (1930, as Mary Westmacott) | 78262 | skip: romance, not a mystery |
| Short pieces (Plymouth Express 66446, Hunter's Lodge 67160, Missing Will 67173) | — | skip: already in Poirot Investigates or later collections |

**Strongest set:** Roger Ackroyd, Styles, Secret Adversary, Murder on the Links, Poirot Investigates, Mysterious Mr. Quin, plus Partners in Crime or Chimneys if you want 7–8 titles. Several PG sources are later paperback reprints of the 1920s texts; Gutenberg has cleared them all.

## 6. Nancy Drew and Hardy Boys (original texts)

The four 1930 Nancy Drew originals are on PG. Each record says Grosset & Dunlap 1930 with Russell Tandy illustrations, so these are the original texts, not the 1959+ rewrites:
- ★ #1 The Secret of the Old Clock: **PG 77924**
- ★ #2 The Hidden Staircase: **PG 77602**
- ★ #3 The Bungalow Mystery: **PG 78704**
- ★ #4 The Mystery at Lilac Inn: **PG 77873**

The Hardy Boys originals from 1927–1930 are all on PG. #1–6 come from Applewood facsimile reprints of the originals; #7–9 come from Grosset & Dunlap 1929–30:
- #1 The Tower Treasure **70083**, #2 The House on the Cliff **69988**, #3 The Secret of the Old Mill **70236** (1927)
- #4 The Missing Chums 72840, #5 Hunting for Hidden Gold 72958, #6 The Shore Road Mystery 73102 (1928)
- #7 The Secret of the Caves 75310, #8 The Mystery of Cabin Island 75012 (1929)
- #9 The Great Airport Mystery 77595 (1930)

Suggestion: Nancy Drew #1–4 together with Hardy Boys #1–3.

---

## 7. Broader gap audit, by section

### Scripture
- ★ **The Book of Mormon**: PG 17. It is the obvious gap next to the Koran, the Gita and the KJV.
- ★ **Egyptian Book of the Dead** (Budge, 1895): PG 7145.
- Luther set: see §1.
- Gilgamesh: PG has only scholarly fragment editions (11000 Jastrow-Clay; 18897 Langdon), with no reader-quality full text. **Gap; skip for now.**
- Popol Vuh: PG has only Lewis Spence's 1908 retelling and study (56550). Weak; optional.

### Philosophy, history and nonfiction (this section is the collection's nonfiction shelf)
- ★ **Herodotus, Histories** (Macaulay): PG 2707 + 2456 (2 vols)
- ★ **Thucydides, Peloponnesian War** (Crawley): PG 7142
- ★ **Plutarch's Lives** (Dryden, rev. Clough), one volume: PG 674
- ★ **Augustine, City of God** (Dods): PG 45304 + 45305
- ★ **Communist Manifesto**: PG 61
- ★ **Douglass, Narrative of the Life**: PG 23
- ★ **Du Bois, The Souls of Black Folk**: PG 408
- ★ **Booker T. Washington, Up from Slavery**: PG 2376
- ★ **Tocqueville, Democracy in America** (Reeve): PG 815 + 816
- ★ **Franklin, Autobiography**: PG 20203 (see §2)
- ★ **Lincoln, Speeches & Letters**: PG 14721 (see §2)
- **Gibbon, Decline and Fall** (Milman): PG 731–736 (6 vols; large, but canonical)
- **Pascal, Pensées**: pick **PG 46921** (Kegan Paul, 1885). PG 18269 is the Trotter translation scanned from a 1958 Dutton paperback with T. S. Eliot's 1931 introduction. Gutenberg marks it PD, but 46921 avoids the question.
- **Rousseau, Confessions**: PG 3913 (complete)
- **Machiavelli, Discourses on Livy** (Thomson): PG 10827
- **Mill, The Subjection of Women**: PG 27083
- **Clausewitz, On War** (Graham): PG 1946. Pairs with Sun Tzu.
- **Darwin, The Descent of Man**: PG 2300
- **Freud, The Interpretation of Dreams** (Brill, 1913): PG 66048
- **Wittgenstein, Tractatus** (Ogden, 1922): PG 5740. German and English facing pages, so check how the reader handles two languages.
- **Nietzsche, The Genealogy of Morals**: PG 52319
- **Erasmus, The Praise of Folly**: PG 9371
- **T. E. Lawrence, Seven Pillars of Wisdom**: **PG 79877**, added 2026-10-28. The record says "London: Jonathan Cape, 1926, reprint 1935" and PD in the USA.
- Aquinas, *Summa Theologica* (PG 17611, 17897, 18755, 19950): canonical but huge. Optional.

### Poetry
- ★ **T. S. Eliot, The Waste Land**: PG 1321. A glaring gap, since the collection already has *Ash-Wednesday*.
- ★ **Eliot, Prufrock and Other Observations**: PG 1459
- ★ **Shakespeare's narrative poems**: Venus and Adonis PG 1045, The Rape of Lucrece PG 1505 (also Phoenix and the Turtle 1525, A Lover's Complaint 1137). Currently only the Sonnets are in.
- ★ **The Poetic Edda** (Bellows, 1923): PG 73533. Prose Edda (Anderson): PG 18947.
- **Nibelungenlied** (Shumway): PG 1151. **Volsunga Saga** (Morris): PG 1152. **Njal's Saga** (Dasent): PG 597.
- **Hesiod and the Homeric Hymns** (Evelyn-White): PG 348
- **Langston Hughes, The Weary Blues** (1926): PG 74745
- **Tagore, Gitanjali**: PG 7164
- **Gibran, The Prophet** (1923): PG 58585
- **Housman, A Shropshire Lad**: PG 5720
- **Frost, North of Boston**: PG 3026
- **Pushkin, Eugene Onegin** (Spalding, 1881): PG 23997
- **Rumi, The Mesnevi** (Redhouse, Book I): PG 61724
- Shahnameh: PG has only selections (10315). **Gap.**

### Drama
- ★ **Chekhov, Three Sisters + The Cherry Orchard**: PG 7986 (*Plays, Second Series*, Julius West, 1916; also has the one-act farces). Completes the Chekhov shelf with Seagull and Vanya.
- ★ **Aeschylus, Prometheus Bound** (with Seven Against Thebes): PG 27458
- ★ **Shaw, Saint Joan** (Brentano's 1924): PG 76323
- ★ **Pirandello, Three Plays** (*Six Characters*, *Henry IV*, *Right You Are*; 1922): PG 42148
- ★ **Čapek, R.U.R.** (Selver/Playfair, 1923): PG 59112
- **Ibsen, Peer Gynt** (and Brand): no standalone; Archer *Collected Works* Vol. IV = Peer Gynt, PG 66239. Also The Master Builder, PG 4070.
- **Calderón, Life Is a Dream** (MacCarthy): PG 6363
- **O'Neill**: Anna Christie 4025 / The Hairy Ape 4015 (optional)
- Goethe, Faust Part Two: **not found** on PG in English. Gap.

### Novels
Modernists and the 1920s:
- ★ **Woolf, To the Lighthouse** (1927) and ★ **Orlando** (1928): SE only (virginia-woolf/to-the-lighthouse, /orlando)
- ★ **Woolf, A Room of One's Own** (1929): WS "A Room of One's Own (Hogarth 1929)", or FP 201410N3. (Nonfiction; could go on the philosophy shelf.)
- ★ **Kafka, The Castle** (Muir, 1930): SE franz-kafka/the-castle/willa-muir_edwin-muir. This is the only US-PD Kafka in English. **The Trial (Muir 1937) and Metamorphosis (Muir 1933) are not PD.** PG 5200 and 7849 (Wyllie) are marked **"Copyrighted"**, and PG 26298 is an audiobook. Do not use them.
- ★ **Thomas Mann, The Magic Mountain** (Lowe-Porter, 1927): SE. **Death in Venice** (Burke, 1925): PG 66073. **Buddenbrooks**: PG 72961 + 72962.
- ★ **Remarque, All Quiet on the Western Front** (Wheen, 1929): PG 75011
- ★ **Forster, A Passage to India**: PG 61221
- ★ **Hesse, Steppenwolf** (Creighton, 1929): PG 75756. **Demian** (1923): PG 74222. **Siddhartha**: PG 2500 is a modern volunteer translation (Olesch et al.) that PG marks PD; the first published English translation (1951) is not PD.
- ★ **Cather, Death Comes for the Archbishop**: PG 69730
- ★ **Wilder, The Bridge of San Luis Rey**: PG 69768 (Woman of Andros is already in)
- **Hammett, Red Harvest** (1929) and **The Dain Curse**: SE only
- **Steinbeck, Cup of Gold** (1929): SE only
- **Waugh, Decline and Fall** (1928): SE only
- **Nella Larsen, Quicksand**: SE; *Passing* is in SE nella-larsen/short-fiction
- **Jean Toomer, Cane**: PG 60093
- **Sherwood Anderson, Winesburg, Ohio**: PG 416
- **Sinclair Lewis, Arrowsmith**: PG 70875. **Dreiser, An American Tragedy**: PG 75181 + 75182.
- **Ford Madox Ford, The Good Soldier**: PG 2775
- **Zamyatin, We** (Zilboorg, 1924): PG 61963
- **D. H. Lawrence, Women in Love**: PG 4240. *Lady Chatterley's Lover* (PG 73144) is on PG too, but its source reprint is undated, so check which text it is before using it.
- **Fitzgerald, The Beautiful and Damned**: PG 9830
- **Sayers, Whose Body?**: PG 58820 (Strong Poison is in; this is the first Wimsey)
- **Wodehouse, Carry On, Jeeves**: PG 65974 (or The Inimitable Jeeves 59254). No Wodehouse yet.
- **Chesterton, The Innocence of Father Brown**: PG 204
- **Lovecraft, The Call of Cthulhu**: PG 68283

Children's and genre:
- ★ **Milne, Winnie-the-Pooh**: PG 67098. **The House at Pooh Corner**: PG 73011.
- ★ **Baum, The Wonderful Wizard of Oz**: PG 55 (Denslow-illustrated edition: 43936)
- ★ **Collodi, Pinocchio** (Della Chiesa): PG 500
- ★ **Spyri, Heidi**: PG 1448
- ★ **Kipling, Just So Stories**: PG 2781
- **Salten, Bambi** (Whittaker Chambers, 1928): PG 72577. Do not use PG 63849, the Wyllie translation.
- **Burroughs, Tarzan of the Apes**: PG 78. **A Princess of Mars**: PG 62.
- Nancy Drew and Hardy Boys: see §6.

19th-century and world gaps:
- ★ **Tolstoy, The Death of Ivan Ilyich**: not on PG as a standalone. SE *Short Fiction* (leo-tolstoy/short-fiction/various-translators) covers it. **Resurrection** (Maude): PG 1938.
- ★ **Chekhov stories, The Lady with the Dog and Other Stories** (Garnett): PG 13415
- **Dostoevsky, The Possessed / Demons** (Garnett): PG 8117
- **Lermontov, A Hero of Our Time**: PG 913
- **Stendhal, The Red and the Black** (Samuel): PG 44747. **The Charterhouse of Parma** (Scott-Moncrieff): PG 66374 + 66375.
- **Balzac, Eugénie Grandet**: PG 1715
- **Zola, L'Assommoir**: PG 8600
- **Dickens, Nicholas Nickleby**: PG 967
- **Walpole, The Castle of Otranto**: PG 696. **Le Fanu, Carmilla**: PG 10007.
- *Romance of the Three Kingdoms* (Brewitt-Taylor, 1925): only Vol. 1 is on PG so far (77416). Wait for Vol. 2.

---

## 8. Prioritised short list (about 45 additions beyond the requested items)

**Tier 1 (★, add first):** Book of Mormon (17), Book of the Dead (7145), Herodotus (2707/2456), Thucydides (7142), Plutarch's Lives (674), City of God (45304/5), Communist Manifesto (61), Douglass (23), Souls of Black Folk (408), Up from Slavery (2376), Democracy in America (815/6), Founding Documents (compiled), Lincoln (14721), Franklin (20203), Waste Land (1321), Prufrock (1459), Venus and Adonis + Lucrece (1045/1505), Poetic Edda (73533), Chekhov Plays II (7986), Prometheus Bound (27458), Saint Joan (76323), Pirandello (42148), R.U.R. (59112), To the Lighthouse (SE), Orlando (SE), A Room of One's Own (WS/FP), The Castle (SE), The Magic Mountain (SE), All Quiet (75011), Passage to India (61221), Steppenwolf (75756), Death Comes for the Archbishop (69730), Bridge of San Luis Rey (69768), Winnie-the-Pooh (67098), Wizard of Oz (55), Pinocchio (500), Heidi (1448), Just So Stories (2781), Ivan Ilyich (SE Tolstoy short fiction), Chekhov Lady with the Dog (13415).

**Tier 2:** Gibbon, Pascal (46921), Rousseau Confessions, Machiavelli Discourses, Subjection of Women, Clausewitz, Descent of Man, Interpretation of Dreams, Tractatus, Genealogy of Morals, Praise of Folly, Seven Pillars, Nibelungenlied, Volsunga, Njal, Hesiod, Weary Blues, Gitanjali, The Prophet, Shropshire Lad, North of Boston, Onegin, Peer Gynt, Life Is a Dream, Death in Venice, Buddenbrooks, Demian, Red Harvest, Cup of Gold, Decline and Fall, Quicksand/Passing, Cane, Winesburg, Arrowsmith, The Good Soldier, We, Whose Body?, Carry On Jeeves, Father Brown, Call of Cthulhu, House at Pooh Corner, Bambi, Tarzan, Princess of Mars, Possessed, Hero of Our Time, Red and Black, Charterhouse, Nicholas Nickleby, Otranto, Carmilla.

**Do not use:** PG 5200 and 7849 (Kafka, Wyllie: copyrighted); PG 63849 (Bambi, Wyllie); PG 19581/19582 (amendments: audio only); PG 61085 used as "In Our Time" (it is the 1924 vignettes).

## 9. Spanish sister collection (quick look only)
- **Fuente Ovejuna** (Lope de Vega): PG 60198, Spanish, PD. Obvious gap; there is no Lope in the collection.
- **Facundo** (Sarmiento): PG 33267, Spanish, PD. A canonical Latin American essay, missing.
- PG 1619 *La Celestina* is marked **"Copyrighted"**: do not use it. PG 5201 *Don Juan Tenorio* is a copyrighted modern bilingual edition (Kline). Neither has a clean PG source.
