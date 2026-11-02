# Granger Classics: more readable translations we can legally ship

Research date 2026-10-30. Manifest read: `tools/pack-builder/classics/manifest.json` (347 works). No repo files were edited.

How things were checked:
- **Gutenberg:** every id below was checked against its `pg<id>.rdf`. That gives the title, the translator (marcrel:trl), and the rights line ("Public domain in the USA" or "Copyrighted"). Most were also checked against the front matter of `pg<id>-images.html`, and some against the full Gutenberg catalogue CSV (`pg_catalog.csv`).
- **Other sources:** the licence text was fetched from the source page itself: eBible.org detail pages, berean.bible terms, Wikisource raw wikitext and licence templates, Perseus page footers, and archive.org metadata.
- **Not checked:** anything not verified is marked **UNVERIFIED**. sacred-texts.com blocked us (Cloudflare), so nothing from that site was checked.

**Builder safety note:** two Gutenberg ebooks that look like good modern translations are copyrighted. #49965 (Linnell, *Dao De Jing*) is CC BY-NC-ND. #2488 (F. P. Walter, *Twenty Thousand Leagues*) is "Copyright 1999" with no licence. The pack builder should refuse any RDF whose `dcterms:rights` is not "Public domain in the USA."

---

## A. Ranked recommendations

| # | Work | Current | Proposed (year) | Source | Licence evidence | Add or replace |
|---|---|---|---|---|---|---|
| 1 | **Meditations** | #2680, which is actually **Meric Casaubon 1634** (not Long; see §B) | **George Long, 1862** | **Gutenberg #15877** (*Thoughts of Marcus Aurelius Antoninus*, the improved edition of #6920; complete, Book XII present) | RDF: trl Long, George (1800–1879); "Public domain in the USA" | **REPLACE.** Long is what the owner thought we shipped. It is far plainer than 1634 English, and it is a one-line manifest change. |
| 1b | Meditations (optional second) | — | **C. R. Haines, Loeb 1916** | Wikisource: https://en.wikisource.org/wiki/Marcus_Aurelius_(Haines_1916). All 12 books are there, plus introduction, glossary and index. HTML; EPUB via ws-export. | Page licence: `{{translation-license|original=PD-old|translation=PD/US}}`. Published 1916, so PD in the USA. | ADD only if a Wikisource adapter is built. It is no more readable than Long (both use "thou"), so it is low value. |
| 2 | **Bible (modern)** | KJV #10 and KJV Apocrypha #124 | **World English Bible (WEB)**, modern English, **with Deuterocanon** | eBible.org `eng-web` (uses "Yahweh"): https://ebible.org/find/details.php?id=eng-web. There is also `engwebu`, identical text but with "LORD": https://ebible.org/find/details.php?id=engwebu. Formats: HTML zip, EPUB 3, USFM, USFX, VPL. | The page says the WEB "is in the Public Domain… we… freely give it to you by dedicating it to the Public Domain". "World English Bible" is a trademark: if we change the text, we may not call it WEB. Detail page: "Includes Apocrypha/Deuterocanon." | **ADD**, and keep KJV. This is the strongest scripture add. Fallback is Gutenberg #8294 (WEB Complete, 2005, "Public domain in the USA"). #8294 is an **older draft with only 66 books, no Deuterocanon**, and verse numbers run inline. Prefer eBible, which needs a USFM or HTML reader in the builder. |
| 3 | **Bible (modern, smoothest)** | — | **Berean Standard Bible (BSB)** | eBible.org `engbsb`: https://ebible.org/find/details.php?id=engbsb. Same formats (USFM, HTML, EPUB). Also available from https://berean.bible. | https://berean.bible/terms.htm: "The Berean Bible and Majority Bible texts are officially dedicated to the public domain as of April 30, 2023. All uses are freely permitted." The only request is that altered texts not use the Berean name. | **ADD.** It reads more naturally than WEB, but has the 66 Protestant books only. If only one modern Bible fits, choose **WEB**, because it covers the Apocrypha we already ship. |
| 4 | **Koran** | Rodwell #2800 (1861) and Sale #7440 (1734) | **Marmaduke Pickthall, *The Meaning of the Glorious Koran*, 1930** | Gutenberg **#16955** (*Three translations of the Koran side by side*). Pickthall's text is every line prefixed `P:`: 6,237 P-lines, 114 chapters, so it is complete. It is not a standalone ebook, so the builder must extract the `P:` lines. Wikisource is marked {{incomplete}} (about 80 of 114 surahs). Archive.org has full 1930 scans (e.g. `dli.ministry.16944`), but those are OCR only. | Published 1930, so **PD in the USA since 2026-01-01**; Wikisource tags it PD-US. The #16955 RDF also says "Public domain in the USA". | **REPLACE Sale** with Pickthall (Sale is 1734 and archaic) and keep Rodwell. **Do not** take the Yusuf Ali (1934) or Shakir lines from the same file. Gutenberg labels the whole file PD-US, but those translations fail the pre-1931 test, so they need the owner's sign-off. |
| 5 | **Metamorphoses: missing half** | #21765 = Riley, **Books I–VII only** | Riley, Books VIII–XV | **Gutenberg #26073** | RDF: trl Riley, Henry T.; "Public domain in the USA" | **ADD (fix).** Today we ship half the poem. |
| 5b | Metamorphoses (verse) | Riley prose 1851 | **Brookes More, blank verse, 1922** (Boston: Cornhill) | Perseus: https://www.perseus.tufts.edu/hopper/text?doc=Perseus:text:1999.02.0028. TEI XML is also in Perseus's GitHub. | Published 1922, so PD in the USA. The Perseus digitisation is **CC BY-SA 3.0 US**, with an extra request to share XML changes back with Perseus. | ADD, but only once a Perseus or TEI importer exists. Frank Justus Miller's Loeb 1916 on Wikisource is **incomplete** (Books I–VIII only), so it is not usable yet. |
| 6 | **Aeneid** | Dryden 1697 (heroic couplets, hard going) | **J. W. Mackail, prose, 1885** | **Gutenberg #22456** | RDF: trl Mackail, J. W. (1859–1945); "Public domain in the USA" | **ADD** and keep Dryden as the literary classic. It is an easy Gutenberg win, in clear prose. |
| 6b | Aeneid (modern verse) | — | **Rolfe Humphries, 1951** (Scribner) | **Gutenberg #61596** | RDF: "Public domain in the USA". The work is post-1930, so this rests on **Gutenberg's copyright-renewal clearance**, not on age. | The most readable option, but it **fails the literal "before 1931" rule**. Owner's call; I lean ADD, given Gutenberg's clearance. |
| 6c | Aeneid (verse, pre-1931) | — | **Theodore C. Williams, 1908/1910** | Perseus: https://www.perseus.tufts.edu/hopper/text?doc=Perseus:text:1999.02.0054. Wikisource has only Books 1–4. **Not on Gutenberg.** | Published 1910 (Houghton Mifflin), so PD; Perseus says CC BY-SA 3.0 US. | Optional, only with a Perseus importer. |
| 7 | **Nicomachean Ethics** | #8438, translator **null**; it is actually **D. P. Chase** (Everyman edition, J. A. Smith introduction; see §B) | **W. D. Ross**, Oxford Works vol. IX (1908; 1925 edition) | Wikisource: https://en.wikisource.org/wiki/Nicomachean_Ethics_(Ross). All 10 books, transcribed from scans. | Wikisource tag `translation = PD/US` (published 1925, before 1930). | ADD, or REPLACE Chase. Ross is the standard modern-ish rendering. This needs a Wikisource import (ws-export EPUB/HTML). At minimum, **fix the translator to D. P. Chase.** |
| 8 | **Mahabharata** (missing) | — | **Romesh C. Dutt, *Maha-bharata* condensed into English verse, 1899** (Dent) | **Gutenberg #19630** (12 books plus epilogue) | RDF: trl Dutt, Romesh Chunder (1848–1909); "Public domain in the USA" | **ADD.** It is a major missing epic and readable at about 320k characters. The full Ganguli prose (#15474–15477, 4 volumes) is too large and dense for the first pass. |
| 9 | **Rámáyan** | Griffith 1870–74, archaic verse | R. C. Dutt condensed verse, 1899 | **Not on Gutenberg.** Only archive.org scans and sacred-texts (blocked). **UNVERIFIED.** | 1899, so PD by date | Defer. A Gutenberg alternative is **M. N. Dutt's complete prose, 1891–94**: #57265, #57826, #60188, #62496, all "Public domain in the USA". It is plainer than Griffith but four large volumes. Low priority. |
| 10 | **Divine Comedy** | Longfellow 1867 (#1001–1003, Latinate blank verse) | **Charles Eliot Norton, prose, 1891–92** | **Gutenberg #1995, #1996, #1997** | RDF: trl Norton, Charles Eliot; "Public domain in the USA" | ADD alongside Longfellow. The plain prose is much easier going. |
| 11 | **Tale of Genji: the rest** | #66057, Waley vol. 1 (1925) only | Waley: *The Sacred Tree* (1926), *A Wreath of Cloud* (1927), *Blue Trousers* (1928) | **Gutenberg #67111, #75852, #77408** | Catalogue lists trl Waley, Arthur; issued 2022, 2025 and 2025-12. These are Gutenberg PD-US releases; publication years are from memory. | ADD. This continues the work we already ship. Vols 5–6 (1932–33) are not PD. |
| 12 | **Proust: the rest** | #7178 *Swann's Way* only | Scott-Moncrieff: *Within a Budding Grove* (1924), *The Guermantes Way* (1925) | **Gutenberg #63532, #73425** | Catalogue: trl Scott-Moncrieff. PD-US releases. | ADD. *Cities of the Plain* (1927), *The Captive* (1929) and *The Sweet Cheat Gone* (1930) are **not on Gutenberg yet** (searched). |
| 13 | **Epic of Gilgamesh** (missing) | — | R. Campbell Thompson, *The Epic of Gilgamish*, prose, London 1928 | archive.org `thompson-1928-gilgamesh` (PDF, EPUB, OCR txt). **Not on Gutenberg.** | 1928, so PD in the USA by date. The archive item carries **no** licence field, and it **also bundles N. K. Sandars's copyrighted 1960 translation**: take only the Thompson PDF. | ADD later, after OCR clean-up. Gutenberg has only fragments: #11000 (Jastrow & Clay 1920, Old Babylonian tablet, scholarly) and #18897 (Langdon 1917). Not recommended. |
| 14 | **Bible (Catholic)** | — | Douay-Rheims, Challoner revision | **Gutenberg #8300** (complete; #1581 is "substantially the same") | RDF: "Public domain in the USA" | Optional ADD for Catholic readers. It is **not** more readable than KJV. |
| 15 | **Tanakh (Jewish)** | — | **JPS 1917** | eBible.org `engjps`: https://ebible.org/find/details.php?id=engjps (HTML, EPUB, USFM). **Not on Gutenberg.** | Page: "Public Domain… published by the Jewish Publication Society in 1917" | Optional ADD for coverage. KJV-like diction, so it does not help readability. |
| 16 | **Analects** | Legge 1861 | Leonard A. Lyall, *The Sayings of Confucius* (complete, Books I–XX) | **Gutenberg #24055** | RDF: trl Lyall; "Public domain in the USA". **Edition year UNVERIFIED** (1909 first edition; this scan's Longmans imprint looks like a later edition). | Low-priority ADD. #46389 (Lionel Giles 1907) is a topical **selection** only. |
| 17 | **Utopia / Politics** (optional) | Ellis's *Politics* (#6762) | Jowett's *Politics* (1885) | Wikisource: https://en.wikisource.org/wiki/Politics_(Jowett). Not on Gutenberg. | Pre-1930, so PD by date | Low priority; only with a Wikisource importer. |

### Checked and not recommended (or not usable)
- **Tao Te Ching: J. H. McDonald 1996.** "Released to the public domain" appears only in third-party listings (archive.org uploads, audiobook sites). I found no statement from the translator, and commercial editions exist under his name. **UNVERIFIED: do not ship.**
- **Tao Te Ching: Linnell (Gutenberg #49965).** Copyrighted, **CC BY-NC-ND 3.0** (stated in the ebook). **Unusable.**
- **Tao Te Ching: Dwight Goddard 1919** (*Laotzu's Tao and Wu Wei*). Not on Gutenberg and not on Wikisource; sacred-texts blocked us. **UNVERIFIED source.** It is PD by date if a clean text turns up.
- **Tao Te Ching: Lionel Giles, *The Sayings of Lao Tzŭ* 1904/05.** On Wikisource (https://en.wikisource.org/wiki/The_Sayings_of_Lao_Tzŭ), PD by date, but it is an **excerpted and rearranged** selection, not a full translation. Wikisource also has a community `Translation:Tao_Te_Ching` (CC BY-SA, completeness not checked).

  **Conclusion:** Legge stays for now. No better complete, verified-legal Tao is available on Gutenberg.
- **Iliad / Odyssey.** Butler's prose (#2199 and #1727) is already the most readable pre-1931 option. Lang-Leaf-Myers (#3059) and Butcher-Lang (#1728) are deliberately archaic. **No change.**
- **Twenty Thousand Leagues: F. P. Walter (#2488).** Excellent, but "Copyright (C) 1999" with no licence. **Unusable.** Note that #164 (current) is the notoriously poor Mercier Lewis 1872 translation; #20000 is another option, translator not checked.
- **Canterbury Tales.** #2383 is **D. Laing Purves's 1870 "popular perusal" edition**: Chaucer's own words with spelling modernised ("WHEN that Aprilis, with his showers swoot"), plus glosses. It is not Middle English proper, and not a translation. The PD modern rendering is Tatlock & MacKaye's *Modern Reader's Chaucer* (1912), but it is not on Gutenberg or Wikisource. Archive.org has only 1940 and 1961 reprints, likely lending-restricted. **Not actionable now.**
- **Popol Vuh: Lewis Spence (#56550, 1908).** A retelling and study, not a full translation. Skip, or add only as "study".
- **Bible: American Standard Version 1901** (eBible `eng-asv`, PD). Uses "Jehovah" and KJV-era diction, so it adds little over KJV. Skip.
- **Bhagavad Gita.** No complete, verified, more readable pre-1931 Gita found on Gutenberg (only Arnold #2388). Ryder (1929) is **UNVERIFIED** (not on Gutenberg).

---

## B. Manifest metadata errors found

| id | Manifest says | Actually (evidence) |
|---|---|---|
| **2680** Meditations | translator null, 1862 (owner believes Long) | **Meric Casaubon's 1634 translation.** The text opens "HIS FIRST BOOK concerning HIMSELF… Of my grandfather Verus I have learned to be gentle and meek". The editor's notes say "nor an emended edition of Casaubon's translation". Long is #15877 or #6920. |
| **8438** Nicomachean Ethics | translator null, 1847 | **D. P. Chase** (1847, revised 1861), in the Everyman edition with an introduction by J. A. Smith. Wikisource files this same text under "Nicomachean Ethics (Chase)", with an identical J. A. Smith introduction. |
| **21765** Metamorphoses | presented as the whole work | **Books I–VII only.** Books VIII–XV are #26073. |
| **7998** The Frogs | translator null, "1908 not confirmed" | **B. B. Rogers** (Harvard Classics vol. 8, 1909; the front matter credits "Morshead, Plumptre, Murray and B B Rogers", and the Frogs is Rogers's). Use 1902 (Rogers's translation) or 1909 (this edition). |
| **2130** Utopia | translator null, 1551 | **Gilbert Burnet's 1684 translation** in Henry Morley's edition ("Burnet's is the translation given in this volume"). 1551 is the Robinson translation, which is not this text. |
| **2383** Canterbury Tales | translator null, 1400 | Fine as the composition date, but the edition is **D. Laing Purves (ed.)** with modernised spelling. Worth an editor credit or note. |
| **19942** Candide | translator null, 1918 | Anonymous Modern Library translation (Boni & Liveright 1918, introduction by Philip Littell). The date is right; a note "anonymous translation" would help. |
| **6762** Politics | William Ellis, 1776 | Sources disagree: Gutenberg says Ellis 1730–1801; Wikisource says Ellis 1794–1872, Everyman 1912. **UNVERIFIED**; flagging only. |
| **10** KJV | Gutenberg #10 | OK. Note #30 is a second KJV copy. |

---

## C. 1930 works now PD in the USA and already on Gutenberg (released in 2026)

All were found in the Gutenberg catalogue with 2026 issue dates. The 1930 publication years are from knowledge and are consistent with Gutenberg releasing them after 2026-01-01.

- **Philosophy:**
  - Freud, *Civilization and Its Discontents*, tr. Joan Riviere: **#78221**
  - Bertrand Russell, *The Conquest of Happiness*: **#77894**
- **Novels:**
  - Dashiell Hammett, *The Maltese Falcon*: **#77600**
  - Evelyn Waugh, *Vile Bodies*: **#77900**
  - Agatha Christie, *Murder at the Vicarage*: **#78220**
  - W. Somerset Maugham, *Cakes and Ale*: **#78723**
  - Thornton Wilder, *The Woman of Andros*: **#78024**
  - J. B. Priestley, *Angel Pavement*: **#78329**
  - Dorothy L. Sayers, *Strong Poison*: **#78157**
- **Poetry:** T. S. Eliot, *Ash-Wednesday*: **#78023**
- **Scripture:** Pickthall's Koran (1930); see A4.
- **Searched for but not on Gutenberg:** Faulkner's *As I Lay Dying*, Kafka's *The Castle* (Muir), and Weber's *Protestant Ethic* (Parsons).

Not 1930, but worth knowing because they fit the "to 1930" range and are on Gutenberg:
- *The Sound and the Fury*: #75170
- *A Farewell to Arms*: #75201
- *The Sun Also Rises*: #67138
- *All Quiet on the Western Front* (Wheen 1929): #75011
- *Steppenwolf* (Creighton 1929): #75756
- *Buddenbrooks* (Lowe-Porter 1924): #72961 and #72962

---

## D. Builder implications
1. **Gutenberg-only, no builder change:** #15877, #26073, #22456, #19630, #1995–1997, #67111, #75852, #77408, #63532 and #73425. Optionally #61596, #8300 and #24055.
2. **Needs a small extractor:** Pickthall from #16955. Keep `P:` lines, group by `Chapter N:` headers, and drop the Y: and S: lines.
3. **Needs a new source adapter:**
   - eBible USFM or HTML zip: WEB, BSB, JPS 1917.
   - Wikisource (ws-export EPUB/HTML): Ross, Haines, Jowett's *Politics*.
   - Perseus TEI: Williams, Brookes More.
4. Add a rights guard. Reject a work when its RDF `dcterms:rights` is not "Public domain in the USA", unless it is allow-listed by hand, as for eBible PD dedications.
