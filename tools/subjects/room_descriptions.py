"""Writes the app's room descriptions (app/src/main/assets/explore/rooms.json) from lenses.json: `subject.lens` -> en, es.
The app reads them from this asset, so a new wording needs no index rebuild. Run after editing a `desc_en`/`desc_es`."""
import json, os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "..", "app", "src", "main", "assets", "explore", "rooms.json")


def descriptions(lenses):
    return {f"{sid}.{l['id']}": {"en": l["desc_en"], "es": l["desc_es"]} for sid, ls in lenses["subjects"].items() for l in ls}


if __name__ == "__main__":
    out = descriptions(json.load(open(os.path.join(HERE, "lenses.json"))))
    json.dump(out, open(OUT, "w"), ensure_ascii=False, indent=1, sort_keys=True)
    print(len(out), "rooms")
