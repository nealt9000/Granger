#!/usr/bin/env python3
"""Exports a trained checkpoint as a sherpa-onnx Piper voice directory (Plan V3).

    export_voice.py --checkpoint <run>/…/last.ckpt --config <dataset>/config.json \\
        --base <work>/base --out <dir> [--name en_US-granger_libritts-medium] [--speakers 3922=Name …]

Writes `<out>/vits-piper-<name>/` with what `SherpaVoice.detect` and `pack_voice.py` want:

- `<name>.onnx`: the generator, exported as piper1-gpl's `export_onnx` does (opset 15; inputs `input`,
  `input_lengths`, `scales`, and `sid` when multi-speaker) but with the TorchScript exporter (`dynamo=False`), the
  path Piper's own voices came from; plus the metadata sherpa-onnx reads (`model_type=vits`, `comment=piper`,
  `language`, `voice`, `has_espeak`, `n_speakers`, `sample_rate`), as on sherpa's `en_US-ljspeech-medium.onnx`.
- `<name>.onnx.json`: the training config (`--config`), with speaker names if given.
- `tokens.txt`: `<phoneme> <id>` from the config's `phoneme_id_map`, as sherpa-onnx's Piper conversion writes it,
  single-codepoint phonemes only (sherpa-onnx rejects the file otherwise).
- `espeak-ng-data/`: copied from sherpa's ljspeech voice (`<base>/sherpa/…`).
- `MODEL_CARD`: what it was trained from and under which licences (Decision 3).

Run it with the training venv's Python.
"""
import argparse
import json
import os
import shutil
import sys

LJSPEECH = "vits-piper-en_US-ljspeech-medium"
OPSET_VERSION = 15

MODEL_CARD = """# Model card for {name}

* Language: en_US (English, United States)
* Speakers: {speakers}
* Quality: medium
* Samplerate: 22,050Hz

## Dataset

* LibriTTS-R (https://www.openslr.org/141/), readers {readers}
* License: CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/)

## Training

Fine-tuned for Granger from Piper's `en_US-ljspeech-medium` checkpoint (`lj-med_1000.ckpt`, trained from scratch on
the LJ Speech dataset, public domain) with piper1-gpl's trainer (GPL-3.0), {epochs}. No `lessac` or `libritts_r`
voice data or weights were used. The voice is distributed under CC BY 4.0's terms: attribution to LibriTTS-R
(Koizumi et al., 2023) and its LibriVox readers.
"""


def export_onnx(checkpoint, target):
    import torch
    from piper.train.vits.lightning import VitsModel

    torch.manual_seed(1234)
    model_g = VitsModel.load_from_checkpoint(checkpoint, map_location="cpu").model_g
    model_g.eval()
    with torch.no_grad():
        model_g.dec.remove_weight_norm()

    def infer_forward(text, text_lengths, scales, sid=None):
        return model_g.infer(
            text, text_lengths, noise_scale=scales[0], length_scale=scales[1], noise_scale_w=scales[2], sid=sid
        )[0].unsqueeze(1)

    model_g.forward = infer_forward
    sequences = torch.randint(low=0, high=model_g.n_vocab, size=(1, 50), dtype=torch.long)
    sid = torch.LongTensor([0]) if model_g.n_speakers > 1 else None
    names = ["input", "input_lengths", "scales"] + (["sid"] if sid is not None else [])
    torch.onnx.export(
        model=model_g,
        args=(sequences, torch.LongTensor([sequences.size(1)]), torch.FloatTensor([0.667, 1.0, 0.8]), sid),
        f=target,
        opset_version=OPSET_VERSION,
        input_names=names,
        output_names=["output"],
        dynamic_axes={
            "input": {0: "batch_size", 1: "phonemes"},
            "input_lengths": {0: "batch_size"},
            "output": {0: "batch_size", 2: "time"},
        },
        dynamo=False,
    )
    return model_g.n_speakers


def add_metadata(target, config):
    import onnx

    model = onnx.load(target)
    meta = {
        "model_type": "vits",
        "comment": "piper",
        "language": config.get("language", {}).get("name_english", "English"),
        "voice": config["espeak"]["voice"],
        "has_espeak": "1",
        "n_speakers": str(config["num_speakers"]),
        "sample_rate": str(config["audio"]["sample_rate"]),
    }
    del model.metadata_props[:]
    for key, value in meta.items():
        model.metadata_props.add(key=key, value=value)
    onnx.save(model, target)
    return meta


def write_tokens(config, target):
    """sherpa-onnx refuses a token longer than one codepoint (piper1-gpl's map has diphthongs such as `aɪ` for
    vowel-cluster voices); without vowel clusters training never produces them, so they are left out."""
    with open(target, "w", encoding="utf-8") as f:
        for phoneme, ids in config["phoneme_id_map"].items():
            if len(phoneme) == 1:
                f.write(f"{phoneme} {ids[0]}\n")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--checkpoint", required=True)
    p.add_argument("--config", required=True, help="the config.json piper1-gpl wrote during training")
    p.add_argument("--base", required=True, help="fetch.py's <work>/base")
    p.add_argument("--out", required=True)
    p.add_argument("--name", default="en_US-granger_libritts-medium")
    p.add_argument("--epochs", default="", help="for the model card, e.g. '1000 epochs from the base'")
    p.add_argument("--speakers", nargs="*", default=[], help="reader=Name, renaming speaker_id_map keys")
    a = p.parse_args()

    with open(a.config, encoding="utf-8") as f:
        config = json.load(f)
    if config["espeak"]["voice"] != "en-us":
        print(f"warning: espeak voice is {config['espeak']['voice']!r}, not 'en-us'", file=sys.stderr)
    if any(len(k) > 1 for k in config["phoneme_id_map"]) and config.get("vowel_clusters"):
        raise SystemExit("trained with vowel clusters: sherpa-onnx tokenises per codepoint, so this won't work")
    renames = dict(s.split("=", 1) for s in a.speakers)
    readers = list(config.get("speaker_id_map") or {})
    if renames:
        config["speaker_id_map"] = {renames.get(k, k): v for k, v in config["speaker_id_map"].items()}
    config["dataset"] = "granger_libritts"

    voice = os.path.join(a.out, "vits-piper-" + a.name)
    os.makedirs(voice, exist_ok=True)
    model_path = os.path.join(voice, a.name + ".onnx")
    n_speakers = export_onnx(a.checkpoint, model_path)
    if n_speakers != config["num_speakers"]:
        raise SystemExit(f"checkpoint has {n_speakers} speakers, config says {config['num_speakers']}")
    meta = add_metadata(model_path, config)
    with open(model_path + ".json", "w", encoding="utf-8") as f:
        json.dump(config, f, ensure_ascii=False, indent=2)
    write_tokens(config, os.path.join(voice, "tokens.txt"))
    espeak = os.path.join(voice, "espeak-ng-data")
    if os.path.exists(espeak):
        shutil.rmtree(espeak)
    shutil.copytree(os.path.join(a.base, "sherpa", LJSPEECH, "espeak-ng-data"), espeak)
    named = [f"{r} ({renames[r]})" if r in renames else r for r in readers] or ["see config"]
    with open(os.path.join(voice, "MODEL_CARD"), "w", encoding="utf-8") as f:
        f.write(
            MODEL_CARD.format(
                name=a.name,
                speakers=n_speakers,
                readers=", ".join(named),
                epochs=a.epochs or "see tools/voices/train/README.md for the epochs",
            )
        )
    print(json.dumps(meta), file=sys.stderr)
    print(voice)


if __name__ == "__main__":
    main()
