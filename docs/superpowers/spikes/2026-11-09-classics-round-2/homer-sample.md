# Butler's Homer with Greek names: sample for Neal

GC2 Task 9. Samuel Butler's prose Odyssey (PG #1727) and Iliad (PG #2199) with the Roman names mapped to Greek,
taken from the trial build (`~/granger-build/gc2-trial-covers/`, 2026-11-09). Only the text from Book I on is
changed; the Odyssey's two prefaces and its footnotes keep Butler's own names. Both books open with the editor's note
"Samuel Butler’s translation, with the gods’ and heroes’ names given in their Greek forms." (under the title, and in
the colophon) and credit "Samuel Butler (Greek names)".

## The Odyssey

**Book I, the opening and the council of the gods**

> Tell me, O Muse, of that ingenious hero who travelled far and wide after he had sacked the famous
> town of Troy. Many cities did he visit, and many were the nations with whose manners and customs he
> was acquainted; moreover he suffered much by sea while trying to save his own life and bring his men
> safely home; but do what he might he could not save his men, for they perished through their own
> sheer folly in eating the cattle of the Sun-god Hyperion; so the god prevented them from ever
> reaching home. Tell me, too, about all these things, oh daughter of Zeus, from whatsoever source you
> may know them.
>
> So now all who escaped death in battle or by shipwreck had got safely home except Odysseus, and he,
> though he was longing to return to his wife and country, was detained by the goddess Calypso, who
> had got him into a large cave and wanted to marry him. But as years went by, there came a time when
> the gods settled that he should go back to Ithaca; even then, however, when he was among his own
> people, his troubles were not yet over; nevertheless all the gods had now begun to pity him except
> Poseidon, who still persecuted him without ceasing and would not let him get home.
>
> Then Athena said, “Father, son of Cronus, King of kings, it served Aegisthus right, and so it would
> any one else who does as he did; but Aegisthus is neither here nor there; it is for Odysseus that my
> heart bleeds, when I think of his sufferings in that lonely sea-girt island, far away, poor man,
> from all his friends. It is an island covered with forest, in the very middle of the sea, and a
> goddess lives there, daughter of the magician Atlas, who looks after the bottom of the ocean, and
> carries the great columns that keep heaven and earth asunder. This daughter of Atlas has got hold of
> poor unhappy Odysseus, and keeps trying by every kind of blandishment to make him forget his home,
> so that he is tired of life, and thinks of nothing but how he may once more see the smoke of his own
> chimneys. You, sir, take no heed of this, and yet when Odysseus was before Troy did he not
> propitiate you with many a burnt sacrifice? Why then should you keep on being so angry with him?”

**Book IX, the Cyclops**

> “But I would not listen to them, and shouted out to him in my rage, ‘Cyclops, if any one asks you
> who it was that put your eye out and spoiled your beauty, say it was the valiant warrior Odysseus,
> son of Laertes, who lives in Ithaca.’

## The Iliad

**Book I, the opening**

> Sing, O goddess, the anger of Achilles son of Peleus, that brought countless ills upon the Achaeans.
> Many a brave soul did it send hurrying down to Hades, and many a hero did it yield a prey to dogs
> and vultures, for so were the counsels of Zeus fulfilled from the day on which the son of Atreus,
> king of men, and great Achilles, first fell out with one another.
>
> And which of the gods was it that set them on to quarrel? It was the son of Zeus and Leto; for he
> was angry with the king and sent a pestilence upon the host to plague the people, because the son of
> Atreus had dishonoured Chryses his priest. Now Chryses had come to the ships of the Achaeans to free
> his daughter, and had brought with him a great ransom: moreover he bore in his hand the sceptre of
> Apollo wreathed with a suppliant’s wreath, and he besought the Achaeans, but most of all the two
> sons of Atreus, who were their chiefs.

**Book VI, Hector and Andromache**

> He stretched his arms towards his child, but the boy cried and nestled in his nurse’s bosom, scared
> at the sight of his father’s armour, and at the horse-hair plume that nodded fiercely from his
> helmet. His father and mother laughed to see him, but Hector took the helmet from his head and laid
> it all gleaming upon the ground. Then he took his darling child, kissed him, and dandled him in his
> arms, praying over him the while to Zeus and to all the gods. “Zeus,” he cried, “grant that this my
> child may be even as myself, chief among the Trojans; let him be not less excellent in strength, and
> let him rule Ilius with his might. Then may one say of him as he comes from battle, ‘The son is far
> better than the father.’ May he bring back the blood-stained spoils of him whom he has laid low, and
> let his mother’s heart be glad.”

## The name map

`tools/pack-builder/classics/names/homer-greek.json`. Counts are replacements in the trial build (Odyssey / Iliad);
capitals in the Odyssey's book headings ("ATHENA’S VISIT TO ITHACA") count with the name. Possessives keep their
apostrophe ("Zeus’s", "Odysseus’ house"); compounds follow ("Zeus-begotten").

| Butler | Greek | Odyssey | Iliad | Note |
|---|---|---:|---:|---|
| Ulysses | Odysseus | 600 | 128 | |
| Jove | Zeus | 185 | 475 | "son of Saturn" becomes "son of Cronus" (7 / 58) |
| Jupiter | Zeus | 0 | 1 | once, in a book summary ("Juno beguiles Jupiter") |
| Minerva | Athena | 152 | 162 | "Pallas Minerva" becomes "Pallas Athena" (Iliad, 18) |
| Juno | Hera | 6 | 124 | |
| Venus | Aphrodite | 14 | 33 | |
| Mars | Ares | 15 | 116 | all capitalised uses are the god |
| Mercury | Hermes | 29 | 20 | |
| Neptune | Poseidon | 56 | 66 | |
| Vulcan | Hephaestus | 20 | 40 | |
| Diana | Artemis | 15 | 15 | |
| Saturn | Cronus | 8 | 84 | |
| Ceres | Demeter | 1 | 4 | |
| Proserpine | Persephone | 11 | 2 | |
| Bacchus | Dionysus | 2 | 3 | |
| Latona | Leto | 0 | 1 | Butler already writes Leto 15 times |
| Hercules | Heracles | 8 | 16 | |
| Aurora | Dawn | 1 | 0 | **judgment:** Butler's own word everywhere else ("rosy-fingered Dawn", 28 times), not Eos |
| Aesculapius | Asclepius | 0 | 7 | **judgment:** found by the frequency pass, not in the research map |
| Pollux | Polydeuces | 1 | 1 | **judgment:** the Greek name; Castor is the same in both |
| Orcus | Hades | 0 | 1 | **judgment:** Iliad II, "a branch of dread Orcus and of the river Styx"; Homer has *horkos* (oath), Butler's Orcus means the underworld, so Hades |
| Diomed | Diomedes | 3 | 107 | **judgment:** Diomed is an English short form, not Latin, but the note promises Greek forms; Diomede (a woman) is untouched |

### Left as Butler wrote them

- **Ajax** (and "the two Ajaxes"), **Achilles**: the forms every English reader knows (as the research advised).
- **Alexandrus** (Paris, 49 in the Iliad) and **Ilius** (Troy, 99): Butler's Latin-ending spellings of Greek names,
  like Patroclus, Menelaus and Telemachus, which all stay. Ilion / Alexandros would be a one-line change each.
- Already Greek in Butler: Hades, Leto, Phoebus Apollo, Pallas, Gaia, Hebe, Ops (Euryclea's father), Erinyes,
  Hymen, Oceanus, Tartarus, Ilithuia.
- Nothing found for Cupid, Pluto, Dis, Ulyssean, Saturnian, Herculean or Jovian.

### How it was checked

A word-frequency pass over both bodies (every capitalised word not in the system dictionary, about 1,300 names)
read for Roman names, then the stems of each mapped name searched for other forms (possessives, compounds, capitals).
After the build: zero mapped names left in Books I–XXIV of either poem. The Odyssey's prefaces (9 × Ulysses) and
footnotes (Ulysses 46, Neptune 5, Minerva 4, Jove 3, Mercury, Mars, Venus) keep Butler's names, as the plan asks;
dropping the map's `"to": "Footnotes"` would rename the footnotes too.
