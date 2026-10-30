# The Spanish eval (Plan L4, first step)

The Spanish Research eval (languages spec §6.1, `docs/superpowers/specs/2026-10-30-languages-design.md`). It is written
the way `AUTHORING.md` says for English, in the same file formats, so `eval.py validate`, `eval.py run` and
`rewrite.py medical` read it unchanged. Questions are in neutral Latin American Spanish (tú, no vosotros); references and
good guesses are in Spanish; `notes` are in English for reviewers.

## Files

| File | What | Items |
|---|---|---|
| `dataset/tune-es.json` | Tuning split (`split: "tune"`), ids `es-<group>-NNN` | 104 |
| `dataset/heldout-es.json` | Held-out split (`split: "heldout"`), ids `h-es-<group>-NNN`. Written by a separate author who didn't see the tuning split. **Don't tune on it, and if you write tuning items, don't open it.** | 55 |
| `dataset/mixed-es.json` | Bilingual check (`split: "tune"`): group `mixed-es-en` is a Spanish question over the English packs, `mixed-en-es` an English question over the Spanish packs. Ids `mixed-es-en-NNN`, `mixed-en-es-NNN`. | 30 |
| `sets/medical-labels-es.jsonl` | The Spanish counterpart of `sets/medical-labels.jsonl` (same format) for the medical flag. | 141: 95 medical, 46 not |

### Groups

The English groups that fit carry over: `unanswerable`, `medical`, `multi-step`, `follow-up`, `who-wrote`,
`vocab-gap`, `words` (Wiktionary) and subject groups (`history`, `science`, `literature`, `geography-general`). New:
`travel` (Wikivoyage). There is no `bible` group and no Classics source: Clásicos Granger isn't built yet (§5.3). Add
`book:granger:gc-es-*` items once it is, with ids continuing each group's numbering.

**Spanish traps** are marked in `notes`: accents and ñ (also questions typed without accents), "a. C." years,
"de … a" and "entre … y" ranges, inflected verbs whose definition lives under the infinitive (Wiktionary), and regional
words (papa/patata, palta/aguacate, el DF, guagua) where the encyclopedia uses another word.

### Medical labels

One JSON object per line, `{"q", "medical", "kind"}`, as in the English file (JSONL has no room for a header, so the
meaning is here). `medical: true` means the question must raise the medical banner; `kind` says why:

- `general`: a health fact (symptoms, causes, how a disease spreads).
- `personal`: about the asker's own (or their child's, parent's) body or medicines: must also trip the personal-medical
  detection (`PersonalMedical` in `core/research/.../Keywords.kt`). "me duele…", "mi hijo tiene…", "estoy embarazada…".
- `drug`: drugs and doses ("¿cuánto ibuprofeno…?", jarabe, pastilla, gotas).
- `first-aid`: an emergency or first aid, including overdoses.
- `historical`: an old disease name (tisis, garrotillo, hidropesía): still medical, as in English.
- `vet`: an animal's health.

`medical: false` must not raise it: `near-miss` uses medical-looking words in another sense (jarabe tapatío, pastillas de
freno, receta de mole, Trópico de Cáncer, el cura) or is history of medicine and anatomy trivia ("¿quién inventó la
penicilina?", "¿cuántos huesos tiene el cuerpo humano?"); `other` is anything else.

One departure from the English file: human anatomy trivia (bones, teeth) is labelled `near-miss`, not medical, per
Neal's L4 brief. The English labels count "what does the thyroid gland do" as medical (`general`); the Spanish ones keep
that kind of question medical too ("para qué sirve la tiroides").

## Packs

The Spanish ZIMs sit beside the English ones in `~/Developer/Granger/vectors-work/sources/`, downloaded from
download.kiwix.org and checked against Kiwix's `.sha256`. `~/.cache/granger/eval-packs/` holds one symlink per pack named
by the ZIM's `Name` metadata (`wikipedia_es_top.zim -> …/wikipedia_es_top_nopic_2026-10.zim`), which is what
`--packs` wants.

| Pack | `source` value | File | Snapshot (ZIM `Date`) | sha256 |
|---|---|---|---|---|
| Wikipedia en español, lo mejor (nopic) | `wikipedia_es_top` | `wikipedia_es_top_nopic_2026-10.zim` (1.21 GB) | 2026-10-10 | `88a4cf19f77552b001c8daef26ec760bb70f65869d08808a0679500426274822` |
| Wikiviajes (nopic) | `wikivoyage_es_all` | `wikivoyage_es_all_nopic_2026-09.zim` (43 MB) | 2026-09-17 | `6f39fcea4dcb213c8a42302e548349d0f835330b74c9e7253daa4821611a289c` |
| Wikcionario (nopic) | `wiktionary_es_all` | `wiktionary_es_all_nopic_2026-09.zim` (656 MB) | 2026-09-20 | `8942ade424ee61e9d8ba83fdb16eddd3475033519495d56bacc0d565fe3408ae` |

URLs: `https://download.kiwix.org/zim/{wikipedia,wikivoyage,wiktionary}/<file>` (from the Kiwix OPDS catalog,
2026-10-30). Kiwix's `wikipedia_es_top` is the best 50,000 articles (58k HTML entries with redirects excluded), so many
topics have no article of their own: check before writing a question.

For the mixed set the English packs are the ones on this Mac: `wikipedia_en_top` (nopic 2026-09-14),
`wikipedia_en_medicine` (2026-10-10), `wikipedia_en_chemistry` (2026-10-15), `wikipedia_en_physics` (2026-10-14),
`wikipedia_en_mathematics` (2026-09-15) and `wikipedia_en_knots` (2026-07-20). English Wiktionary and Geography aren't
on this Mac, so no mixed item uses them. Mixed answers name the other language's pack; where the same fact is also in a
same-language pack, that location is listed too, so retrieval recall counts either.

The snapshot rule for after-snapshot questions is the English one: the newest snapshot of any pack is 2026-10-15
(`wikipedia_en_chemistry`), so these questions are about things after that date.

## Running it

```
cd tools/eval
python3 eval.py validate dataset/tune-es.json --packs ~/.cache/granger/eval-packs
python3 eval.py validate dataset/heldout-es.json --packs ~/.cache/granger/eval-packs
python3 eval.py validate dataset/mixed-es.json --packs ~/.cache/granger/eval-packs
python3 eval.py run dataset/tune-es.json --run-id es-base-1        # the phone needs the Spanish packs installed
python3 eval.py run dataset/mixed-es.json --run-id mixed-base-1 --groups mixed-es-en   # one direction at a time
python3 rewrite.py medical RUN --labels sets/medical-labels-es.jsonl
```

The metrics don't split a run by group, so report the two mixed directions apart by running each with `--groups`. A
Spanish run needs the Spanish packs on the phone (and, for the mixed set, the English ones too), and Spanish dense
vectors (§6.1) for a fair dense-on run; without them the run measures keyword search only.

Use python-libzim 3.13 (`~/.venvs/granger-vectors` has it). The validator scans every entry of a ZIM for each answer,
so `wikipedia_es_top` (348k entries) takes a while.
