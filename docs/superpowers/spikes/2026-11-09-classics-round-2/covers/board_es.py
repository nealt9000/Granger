"""GC2 Task 2 board: one Clásicos Granger cover per section (real titles from manifest_es.json, picked to stress
wrapping, accents and the "traducción de" line), then the learner sections on each candidate ground.

  ~/.venvs/granger-pack/bin/python board_es.py   (from this folder; writes clasicos-sections.png, learner-grounds.png)
"""
import io
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
PACK = HERE.parents[4] / "tools" / "pack-builder"
sys.path.insert(0, str(PACK))
from PIL import Image, ImageDraw, ImageFont  # noqa: E402

import covers  # noqa: E402
import epubpack  # noqa: E402

MANIFEST = json.loads((PACK / "classics" / "manifest_es.json").read_text(encoding="utf-8"))
SECTIONS = {s["id"]: s["title"] for s in MANIFEST["sections"]}
WORKS = {w["title"]: w for w in MANIFEST["works"]}
PICKS = {"lecturas": "Más vale maña que fuerza", "infantil": "La isla del tesoro",
         "cuentos": "Cuentos de Navidad y Reyes; Cuentos de la patria; Cuentos antiguos",
         "poesia": "Las cien mejores poesías (líricas) de la lengua castellana",
         "teatro": "Don Álvaro o la fuerza del sino", "novelas": "Los cuatro jinetes del Apocalipsis",
         "universales": "Comedias de Aristófanes (tomo 1 de 3)", "ensayo": "El tratado de la pintura",
         "historia": "Historia de la guerra del Peloponeso (tomo 1 de 2)",
         "escritura": "Epístola de San Pablo a los romanos"}
WORDMARK = MANIFEST["title"].upper()
CREDIT = epubpack.labels("es")["translated_by"].lower()
CW, CH, PAD, LABEL = 300, 450, 24, 30
FONT = ImageFont.truetype(str(PACK / "assets" / "fonts" / "spectral_regular.ttf"), 17)


def cover(title: str, ground: str | None = None) -> Image.Image:
    w = WORKS[title]
    assert w["section"] in PICKS, w["section"]
    data = covers.cover_jpeg(w["title"], w.get("author", ""), w.get("translator"), w["section"],
                             SECTIONS[w["section"]], wordmark=WORDMARK, translated_by=CREDIT, ground=ground, language="es")
    return Image.open(io.BytesIO(data)).convert("RGB").resize((CW, CH), Image.LANCZOS)


def board(cells: list[tuple[str, Image.Image]], columns: int, out: Path) -> None:
    rows = (len(cells) + columns - 1) // columns
    image = Image.new("RGB", (PAD + columns * (CW + PAD), PAD + rows * (CH + LABEL + PAD)), "#3A3F40")
    draw = ImageDraw.Draw(image)
    for i, (label, im) in enumerate(cells):
        x, y = PAD + (i % columns) * (CW + PAD), PAD + (i // columns) * (CH + LABEL + PAD)
        draw.text((x, y), label, font=FONT, fill="#EFE4D0")
        image.paste(im, (x, y + LABEL))
    image.save(out)
    print(out)


if __name__ == "__main__":
    board([(f"{s}  {covers.ground_for(s)}" + ("  (placeholder)" if s in ("lecturas", "infantil") else ""),
            cover(t)) for s, t in PICKS.items()], 5, HERE / "clasicos-sections.png")
    board([(f"{name} {hex_}: {s}", cover(PICKS[s], hex_))
           for name, hex_ in covers.LEARNER_CANDIDATES.items() for s in ("lecturas", "infantil")],
          6, HERE / "learner-grounds.png")
