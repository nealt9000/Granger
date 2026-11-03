#!/usr/bin/env python3
"""The answer prompt's language for Spanish questions, tried by replay (Plan L4, the prompt test).

  answer_language.py variant BASE NEW --mode rule|spanish   # runs/NEW: BASE with each Spanish answer prompt rebuilt
  answerers.py run NEW NEW-e2b --model gemma-4-E2B-it-gpu.litertlm  # replays it on the phone

The L4 baseline (l4-tune-es-1, l4-mixed-es-1) answered about one Spanish question in five in English, when the
passages were English. Two fixes, on the prompts the baseline's ranking built, so only the prompt changes:
- **rule:** today's English instructions plus one rule, "Write the answer in Spanish, even when the passages are in English."
- **spanish:** the whole prompt in Spanish (instructions, labels, the rule), passages untouched. The decline token stays
  `NOT IN LIBRARY`, as the app matches it.

Only questions Spanish leads (`language.of`) change; English questions keep their prompt, and their replies.
"""
import argparse
import json
import shutil
import sys
from pathlib import Path

import answerers
import language

HERE = Path(__file__).resolve().parent
RUNS = HERE / "runs"

DECLINE = "- If the passages don't answer the question, reply exactly: NOT IN LIBRARY\n"
RULE = "- Write the answer in Spanish, even when the passages are in English.\n"

# AnswerPrompt.render's English lines and their Spanish, in order. Each must be found, so a prompt change shows here.
SPANISH = [
    ("You are Granger's research librarian. Answer the question using only the numbered passages from the user's library below.\n",
     "Eres el bibliotecario de investigación de Granger. Responde la pregunta usando solo los pasajes numerados de la biblioteca del usuario que están abajo.\n"),
    ("Rules:\n", "Reglas:\n"),
    ("- Answer the question directly in your first sentence. Keep the whole answer short: two or three sentences, unless the question asks for a list or steps.\n",
     "- Responde la pregunta directamente en tu primera oración. Que toda la respuesta sea breve: dos o tres oraciones, salvo que la pregunta pida una lista o pasos.\n"),
    ("- Put the passage number in square brackets after every claim it supports, like [2]. Cite only the numbers listed.\n",
     "- Pon el número del pasaje entre corchetes después de cada afirmación que respalda, así: [2]. Cita solo los números de la lista.\n"),
    ("- You may combine facts from different passages and work out simple things from them (dates, ages, which came first, how long between), citing every passage you used.\n",
     "- Puedes combinar datos de distintos pasajes y deducir cosas sencillas a partir de ellos (fechas, edades, qué fue primero, cuánto tiempo pasó entre dos hechos), citando cada pasaje que usaste.\n"),
    ("- Use the source details (title, author, date, collection) when they matter to the question.\n",
     "- Usa los datos de la fuente (título, autor, fecha, colección) cuando importen para la pregunta.\n"),
    ("- When passages from different works bear on each other, say how, citing each.\n",
     "- Cuando pasajes de obras distintas se relacionan entre sí, di cómo, citando cada uno.\n"),
    ("- Don't add facts that aren't in the passages.\n", "- No agregues datos que no estén en los pasajes.\n"),
    ("- Passages about a different person, year, place or event than the question asks about don't answer it.\n",
     "- Los pasajes sobre otra persona, año, lugar o hecho distinto del que pregunta la pregunta no la responden.\n"),
    (DECLINE, "- Si los pasajes no responden la pregunta, responde exactamente: NOT IN LIBRARY\n"
              "- Escribe la respuesta en español, aunque los pasajes estén en inglés.\n"),
]
# Lines only some prompts have, and the labels.
SPANISH_OPTIONAL = [
    ("- This is a medical question. Quote the passages closely and add nothing beyond them.\n",
     "- Esta es una pregunta médica. Cita los pasajes de cerca y no agregues nada más allá de ellos.\n"),
    ("\nEarlier in this conversation:\nQ: ", "\nAntes en esta conversación:\nP: "),
    ("The user selected passage [1] and is asking about it.\n", "El usuario seleccionó el pasaje [1] y pregunta sobre él.\n"),
]


def question_of(prompt):
    return prompt[prompt.rindex("\nQuestion: ") + len("\nQuestion: "):prompt.rindex("\nAnswer:")]


def with_rule(prompt):
    if DECLINE not in prompt:
        raise SystemExit("no decline rule in a prompt: has AnswerPrompt changed?")
    return prompt.replace(DECLINE, DECLINE + RULE, 1)


def in_spanish(prompt):
    head, sep, rest = prompt.partition("\nPassages:\n")
    if not sep:
        raise SystemExit("no Passages: label in a prompt: has AnswerPrompt changed?")
    for en, es in SPANISH:
        if en not in head:
            raise SystemExit("a prompt lacks %r: has AnswerPrompt changed?" % en.strip())
        head = head.replace(en, es, 1)
    for en, es in SPANISH_OPTIONAL:
        head = head.replace(en, es, 1)
    if "\nP: " in head:
        head = head.replace("\nA: ", "\nR: ", 1)
    body, q, tail = rest.rpartition("\nQuestion: ")
    tail = tail[:tail.rindex("\nAnswer:")] + "\nRespuesta:"
    for en, es in SPANISH_OPTIONAL[2:]:
        body = body.replace(en, es, 1)
    return head + "\nPasajes:\n" + body + "\nPregunta: " + tail


MODES = {"rule": with_rule, "spanish": in_spanish}


def cmd_variant(a):
    out_dir = RUNS / a.new
    out_dir.mkdir(parents=True, exist_ok=True)
    out, changed = [], 0
    for rec in answerers.read_jsonl(RUNS / a.base / "results.jsonl"):
        ps = [p for p in rec.get("prompts") or [] if p["stage"] == "answer"]
        if ps and language.leads(language.of(question_of(ps[-1]["prompt"]))):
            ps[-1]["prompt"] = MODES[a.mode](ps[-1]["prompt"])
            changed += 1
        out.append(rec)
    shutil.copy(RUNS / a.base / "dataset.json", out_dir / "dataset.json")
    answerers.write_jsonl(out_dir / "results.jsonl", out)
    (out_dir / "variant.json").write_text(json.dumps({"from": a.base, "mode": "language-" + a.mode}), encoding="utf-8")
    print(json.dumps({"variant": a.new, "Spanish prompts rebuilt": changed, "records": len(out)}))
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    s = p.add_subparsers(required=True)
    v = s.add_parser("variant"); v.add_argument("base"); v.add_argument("new")
    v.add_argument("--mode", required=True, choices=sorted(MODES)); v.set_defaults(f=cmd_variant)
    a = p.parse_args(argv)
    return a.f(a)


if __name__ == "__main__":
    sys.exit(main())
