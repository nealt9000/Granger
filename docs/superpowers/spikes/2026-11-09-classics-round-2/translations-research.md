# Granger Classics: more readable PD/CC0 translations

Researched 2026-11-09. All ids checked live: Project Gutenberg via its own catalog feed (`gutenberg.org/cache/epub/feeds/pg_catalog.csv.gz`) plus the ebook pages and texts; Wikisource via its API (page text, subpage lists, Index proofread categories); Standard Ebooks (SE) ebook pages; Perseus via the PerseusDL GitHub repos.

**SE warning:** many SE search hits are *placeholders* (works SE wants to produce, with no ebook yet). I checked every SE link for the placeholder marker. Only links marked **released** have an ebook.

"Archaic density" below means thou/thee/thy per 10,000 words, measured on the actual text. It is a rough proxy for "tedious older English". Butler's Odyssey scores 0.8, Butcher & Lang 154.

---

## 1. Homer: the main question

### What exists

| Option | Names | Archaic density | Source | Licence |
|---|---|---|---|---|
| Butler Odyssey 1900 (current) | Latin (Ulysses 635, Jove 188, Minerva 152) | 0.8 | PG #1727 | PD |
| Butler Iliad 1898 (current) | Latin (Jove 475, Minerva 162, Juno 124, Mars 116) | 0.5 | PG #2199 | PD |
| Butcher & Lang Odyssey 1879 (prose) | Greek | 154 (King James style) | PG #1728 | PD |
| Lang, Leaf & Myers Iliad 1883 (prose) | Greek | 133 | PG #3059 | PD |
| A. T. Murray Odyssey 1919 (Loeb, prose) | Greek | about 169 | Perseus `tlg0012.tlg002.perseus-eng3.xml` (complete, 133k words). Not on Wikisource or PG | Text PD; Perseus XML CC BY-SA 4.0 |
| A. T. Murray Iliad 1924 (Loeb, prose) | Greek | about 126 | Wikisource [The Iliad (Murray)](https://en.wikisource.org/wiki/The_Iliad_(Murray)): all 24 books, **no scan** (copied from theoi.com). Also Perseus `tlg0012.tlg001.perseus-eng3.xml` | Text PD |
| Butler **revised** by Timothy Power & Gregory Nagy (Odyssey and Iliad) | **Greek** (Odysseus 580, Athena 148, Zeus 186) | 0 | Perseus `tlg0012.tlg002.perseus-eng4.xml` / `tlg0012.tlg001.perseus-eng4.xml` (also on Harvard's Center for Hellenic Studies site) | **Not PD and not CC0.** Repo licence is CC BY-SA 4.0 and the revision is modern work |
| Bryant 1870/71 (blank verse) | Latin (Ulysses, Jove) | about 145 | SE released | CC0, but no gain |

Samples:
- **Butcher & Lang:** "Tell me, Muse, of that man, so ready at need, who wandered far and wide, after he had sacked the sacred citadel of Troy... yea, and many the woes he suffered in his heart upon the deep... Nay, but even so he saved not his company, though he desired it sore."
- **Lang/Leaf/Myers:** "Sing, goddess, the wrath of Achilles Peleus' son, the ruinous wrath that brought on the Achaians woes innumerable, and hurled down into Hades many strong souls of heroes..."
- **Murray Odyssey:** "Tell me, O Muse, of the man of many devices... they perished—fools, who devoured the kine of Helios Hyperion... Of these things, goddess, daughter of Zeus, beginning where thou wilt, tell thou even unto us."
- **Murray Iliad:** "The wrath sing, goddess, of Peleus' son, Achilles, that destructive wrath which brought countless woes upon the Achaeans..."
- **Butler revised (Nagy):** "Tell me, O Muse, of that many-sided hero who traveled far and wide after he had sacked the famous town of Troy. Many cities did he visit, and many were the people with whose customs and thinking [noos] he was acquainted..." It has 479 (Odyssey) and 660 (Iliad) bracketed Greek glosses like this.

### Does any PD/CC0 edition already put Greek names into Butler?
**No.** The only Greek-names Butler I found is the Power/Nagy revision. It is CC BY-SA, a modern revision (so not PD), and full of bracketed glosses. SE has no Butler Homer (only Bryant). Wikisource's Butler texts keep the Latin names.

Murray and the Lang-group translations give you Greek names, but the cost is the very archaism the user complained about: 125–170 thou/thee/thy per 10k words, plus "aye", "kine", "yea", "nay".

### Recommendation: Butler with names mapped Latin→Greek (our own edit)
Butler is the only PD Homer in plain modern English. The fix is narrow and mechanical. A whole-word replacement of about 15 names covers almost every case:

Ulysses→Odysseus, Jove→Zeus, Minerva→Athena (and "Pallas Minerva"→"Pallas Athena"), Juno→Hera, Venus→Aphrodite, Mars→Ares, Mercury→Hermes, Neptune→Poseidon, Vulcan→Hephaestus, Diana→Artemis, Saturn→Cronus ("son of Saturn" appears 55× in the Iliad and 7× in the Odyssey), Ceres→Demeter, Proserpine→Persephone, Bacchus→Dionysus, Latona→Leto, Hercules→Heracles, Aurora→Dawn. Optionally Ilius→Ilion. Leave Ajax and Achilles as they are.

Example from Butler Odyssey Book 1: "Then Minerva said, 'Father, son of Saturn, King of kings, it served Aegisthus right... it is for Ulysses that my heart bleeds'" becomes "Then Athena said, 'Father, son of Cronus, King of kings... it is for Odysseus that my heart bleeds'".

A mapped edition of a PD text is itself free to publish. Caveats:
- Match capitalised whole words only ("Mars" vs "mars"), and handle possessives with both straight and curly apostrophes.
- Add a short editor's note ("names changed to their Greek forms").
- Butler's own preface and notes talk about his name choices, so leave the front matter alone.
- Spot-check the output with counts afterwards. The counts above came from a script in the scratchpad.

**Replace** #1727 and #2199 with the mapped versions. Optionally **add** Butcher & Lang (#1728) and Lang/Leaf/Myers (#3059) alongside for readers who want a "traditional" Greek-names version. I would not use Murray: he is no more readable than the Lang group, and the Odyssey exists only as Perseus XML.

---

## 2. Work-by-work verdicts

Ancient and early-modern works are listed first. "Replace" means swap the default; "Add" means offer it alongside the current one.

### Clear wins (recommend acting)

**Epictetus — Long 1877 → W. A. Oldfather, Loeb 1925/1928 (prose). Replace.**
- Source: Wikisource [Epictetus, the Discourses as reported by Arrian, the Manual, and Fragments](https://en.wikisource.org/wiki/Epictetus,_the_Discourses_as_reported_by_Arrian,_the_Manual,_and_Fragments). 103 subpages: all four books of Discourses, Manual, Fragments.
- Both volume Indexes are **Proofread** and built from scans. PD in the US (published 1925 and 1928).
- Sample (Manual): "Some things are under our control, while others are not under our control. Under our control are conception, choice, desire, aversion, and, in a word, everything that is our own doing..."
- Caveat: Loeb footnotes are present (bracketed note markers). Almost no "thou".
- Also worth knowing: T. W. Higginson's *Enchiridion*, PG #45109 (plain, 0.7/10k): "There are things which are within our power, and there are things which are beyond our power."

**Canterbury Tales — Middle English (Purves, PG #2383) → Tatlock & MacKaye, *The Complete Poetical Works of Geoffrey Chaucer, Now First Put Into Modern English* (1912; modern prose, a few lyrics in verse). Add, and make it the default.**
- Source: **PG #79952**. Released on PG **2026-11-08** (yesterday). The live page lists Chaucer, Percy MacKaye, John S. P. Tatlock and illustrator Warwick Goble.
- Sample: "When the sweet showers of April have pierced to the root the dryness of March, and bathed every vine in moisture whose quickening brings forth the flowers; when Zephyr also with his sweet breath has quickened the tender new shoots in holt and moor..."
- Caveats:
  - Lightly bowdlerised. The editors say small coarse passages were dropped without marking, and whole "gross" episodes are marked with asterisks.
  - Melibeus and the Parson's Tale appear only as extracts.
  - It contains all of Chaucer's poetry, not only the Tales. You may want to extract the Tales or ship the whole volume.
- Keep Purves alongside for readers who want Middle English.

**Meditations — Long 1862 (159 thou/10k) → G. W. Chrystal 1902, "a new rendering based on the Foulis translation of 1742" (prose). Add, and consider making it the default.**
- Source: PG #55317. Live page confirms translator Chrystal, George W.
- Archaic density 6.7/10k.
- Sample: "I learned from my grandfather, Verus, to use good manners, and to put restraint on anger. In the famous memory of my father I had a pattern of modesty and manliness."
- Caveat: a lesser-known version, and a revision of an 18th-century translation rather than fresh from the Greek.
- Haines 1916 (Loeb) is on Wikisource ([Marcus Aurelius (Haines 1916)](https://en.wikisource.org/wiki/Marcus_Aurelius_(Haines_1916)), all 12 books, Index not proofread). It still uses "thou" heavily ("Say to thyself at daybreak..."), so it is **not** a readability gain.

**Aeschylus Oresteia — Morshead 1881 verse → H. W. Smyth, Loeb 1922/1926 (prose). Add or replace.**
- Two sources:
  - (a) Wikisource [Aeschylus (Smyth 1926) v2](https://en.wikisource.org/wiki/Aeschylus_(Smyth_1926)_v2) has Agamemnon, Libation-Bearers and Eumenides. Its Index is *Not-Proofread*. v1 (the other four plays) is Proofread. These are the original scan texts and still use thou (about 240 in Agamemnon).
  - (b) Perseus `tlg0085.tlg005/006/007` (all seven plays). These are "**Modernized by Perseus**": thou→you, and zero thou remain. That modernised layer is CC BY-SA, not CC0.
- Sample (Perseus): "Release from this weary task of mine has been my plea to the gods throughout this long year's watch, in which, lying upon the palace roof of the Atreidae, upon my bent arm, like a dog, I have learned to know well the gathering of the night's stars..."
- Also on SE (released, CC0): Gilbert Murray's rhymed verse Agamemnon, Libation Bearers and Eumenides. These are well formatted, but rhymed Edwardian verse is not easier than Morshead.

**Nicomachean Ethics — D. P. Chase 1847 (PG #8438) → one of three. Replace.**
- **F. H. Peters (SE, released, CC0)**: zero cleanup, prose. Sample: "Every art and every kind of inquiry, and likewise every act and purpose, seems to aim at some good: and so it has been well said that the good is that at which everything aims."
- **W. D. Ross 1925**: the standard. Wikisource [Nicomachean Ethics (Ross)](https://en.wikisource.org/wiki/Nicomachean_Ethics_(Ross)) has all 10 books transcluded from the *Works of Aristotle* vol. 9 scan, but the Index is Not-Proofread. Bekker numbers (1094a…) and note markers are inline and would need stripping. Sample: "Every art and every inquiry, and similarly every action and pursuit, is thought to aim at some good..."
- **H. Rackham 1926 Loeb**: Perseus `tlg0086.tlg010.perseus-eng2.xml`, complete, text PD, XML CC BY-SA.
- My pick is **Peters via SE** (cleanest), with Ross as the alternative if you want the canonical name.

### Worth adding (modest gain)

**Aeneid — Dryden 1697 + Mackail 1885 prose → add Theodore C. Williams 1908/1910 (blank verse).**
- Replace Dryden as the verse option; keep Mackail.
- Sources: Perseus `phi0690.phi003.perseus-eng2.xml` (complete) and Wikisource [The Aeneid of Virgil (Williams 1908)](https://en.wikisource.org/wiki/The_Aeneid_of_Virgil_(Williams_1908)).
- Sample: "Arms and the man I sing, who first made way, predestined exile, from the Trojan shore to Italy, the blest Lavinian strand. Smitten of storms he was on land and sea by violence of Heaven, to satisfy stern Juno's sleepless wrath..."
- Caveat: there is still some thou (about 97/10k). The Latin names are correct here, since it is a Roman poem.
- Mackail sample, for comparison: "I sing of arms and the man who of old from the coasts of Troy came, an exile of fate, to Italy and the shore of Lavinium..."
- Fairclough's Loeb 1916 is PD but has no clean digital text (Internet Archive scans only).

**Metamorphoses — Riley 1851 prose → add Brookes More 1922 (blank verse).**
- Source: Perseus `phi0959.phi006.perseus-eng3.xml` (complete). PD in the US.
- Sample: "My soul is wrought to sing of forms transformed to bodies new and strange! Immortal Gods inspire my heart, for ye have changed yourselves and all things you have changed!"
- Caveat: a slightly florid style, about 24 thou/10k.
- F. J. Miller's Loeb 1916 prose would be ideal, but Wikisource has only the introduction and "Pyramus and Thisbe" (marked incomplete).

**Analects — Legge 1861.**
- Legge's English is actually plain (0.9/10k): **keep**.
- Optionally add Lionel Giles, *The Sayings of Confucius* 1907 (PG #46389). It is very readable ("A virtuous ruler is like the Pole-star, which keeps its place, while all the other stars do homage to it"), but it is a **selection, rearranged by topic**.
- L. A. Lyall 1909 (PG #24055) is complete, but it is not clearly better and uses more thou (28/10k).

### Keep (no clearly more readable PD/CC0 text with a clean source)

- **Tao Te Ching (Legge, PG #216)**: keep. Legge has almost no thou. Lionel Giles' *Sayings of Lao Tzŭ* (1904, Wikisource) is a selection only. Wikisource's own "Translation:Tao Te Ching" is CC BY-SA.
- **Bhagavad Gita (Arnold)**: keep. Besant (1895/1922, Wikisource) is no plainer.
- **Upanishads (Paramananda)**: keep.
- **Dhammapada (Müller, PG #2017)**: keep ("All that we are is the result of what we have thought"). Woodward 1921 (PG #35185) is in stilted verse.
- **Koran**: keep Rodwell/Sale. For the record, Pickthall 1930 on Wikisource ([The Meaning of the Glorious Koran (1930)](https://en.wikisource.org/wiki/The_Meaning_of_the_Glorious_Koran_(1930))) is **incomplete** (84 subpages including front matter, against 114 suras).
- **Imitation of Christ (Benham)**: keep. Also on SE, released.
- **Plato (Jowett)**: keep. SE has a released, well-formatted Jowett Dialogues if you want better formatting.
- **Politics (Ellis 1776, PG #6762)**: keep. Ellis has 0 thou and reads fine. Jowett on Wikisource has only Book 1. Rackham on Perseus is 1944 and not PD.
- **Poetics (Butcher)**: keep. Bywater (PG #6763) is a sideways move, and the SE Poetics is only a placeholder.
- **Lucretius (Leonard 1916 verse)**: keep for now. Munro's prose on SE is a placeholder. Rouse's Loeb 1924 is PD but scans only.
- **Boethius (H. R. James)**: keep. SE has a released, better-formatted copy of the same translation.
- **Confessions (Pusey)**: keep. Pilkington on SE is a placeholder.
- **Montaigne (Cotton)**: keep. Ives 1925 on Wikisource is incomplete (part of vol. 1). SE Cotton is a placeholder.
- **Utopia (Burnet)**: keep. It is already plain (0.2/10k).
- **Art of War (Giles)**: keep. Also on SE, released.
- **Beowulf (Gummere)**: keep.
  - J. L. Hall (SE, released) is just as archaic.
  - Kirtlan's 1913 prose (PG #50742) is odd ("withheld the drinking-stools from many a tribe").
  - Clark Hall's prose has no clean text.
- **Divine Comedy (Longfellow + Norton prose)**: keep. Norton is the readable one.
- **Faust (Taylor)**: keep. The SE Faust is a placeholder.
- **Song of Roland (Scott-Moncrieff)**: keep. A proofread Wikisource copy exists if you want a cleaner source. Isabel Butler's 1904 prose has no clean text.
- **Kalevala (Crawford)**: keep. Also on SE, released.
- **Mahabharata (Dutt) / Ramayana (Griffith)**: keep. No clean Dutt Ramayana found on PG.
- **Horace Odes (Conington)**: keep. The SE Odes is a placeholder.
- **Sir Gawain (Weston 1898 prose, PG #66084)**: keep. S. O. Andrew 1929 (SE, released) is alliterative verse and more archaic: "When the burg had been broken, and burnt to ashes, The wight that the trains of treason there laid...".
- **Sophocles (Storr)**: keep. Also on SE, released.
- **Euripides (Gilbert Murray)**: keep. E. P. Coleridge's prose versions on SE are placeholders.
- **Aristophanes**: keep. The SE Rogers Clouds and Frogs are placeholders. Perseus Clouds is the same Hickie 1853.
- **Golden Ass (Adlington 1566)**: no clean alternative. H. E. Butler's 1910 prose is PD and would be a big gain, but it exists only as Internet Archive scans (listed on Wikisource, not transcribed). It would be worth a transcription project.
- **Decameron (Payne)**: keep. Rigg 1903 (PG #3726/#13102) is similar.
- **Don Quixote (Ormsby)**, **Arabian Nights (Lang)**, **Candide (1918, SE released)**: keep.
- **Tale of Genji (Waley)**: keep. Caveat: only Waley parts 1–4 (1925–28; PG #66057, #67111, #75852, #77408) are US-PD. Parts 5–6 (1932–33) are not yet PD.

---

## 3. Source notes
- **Perseus:** `github.com/PerseusDL/canonical-greekLit` and `canonical-latinLit`, as TEI XML. The underlying old translations are PD, but the repo licence is CC BY-SA 4.0. That is fine for PD text; anything marked "Modernized by Perseus" or "Revised" is a CC BY-SA derivative.
- **Wikisource:** "Proofread" Index means a second person checked the text against the scan. "Not-Proofread" texts are usually OCR-plus-one-pass and need a spot check.
- **SE placeholders confirmed (no ebook yet):** Aristotle Poetics and Short Works, Montaigne Cotton, Lucretius Munro, Augustine Pilkington, Horace Odes, Goethe Faust, Epictetus Higginson, Euripides (all checked), and Aristophanes Rogers (all except the released Athenian Society *Birds*).
- **SE also flagged placeholder:** Waley *Tale of Genji*. Its page also showed the placeholder marker, and its text URL returned 404.
