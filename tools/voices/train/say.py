#!/usr/bin/env python3
"""Speaks a few sentences with a voice directory through sherpa-onnx, the engine the app uses (Plan V3).

    say.py <voice dir> --out <dir> [--speakers 0 1] [--text "…"]

Loads the directory as `SherpaVoice` does (the one `*.onnx`, `tokens.txt`, `espeak-ng-data/`), writes
`<out>/<voice>-s<speaker>.wav` for each speaker and prints the real-time factor on this machine (not the phone's).
Run it with the training venv's Python (`pip install sherpa-onnx`, the app's 1.13.8).
"""
import argparse
import glob
import os
import time

TEXT = (
    "It was the best of times, it was the worst of times. "
    "Dr. Okafor arrived at 7:45 on March 3rd, 1984, carrying $12.50 and a copy of Middlemarch. "
    "The Rosetta Stone left Egypt long before anyone in Australia had heard of it, and the whole area forgot it. "
    "Would you like me to read the next chapter?"
)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("voice")
    p.add_argument("--out", required=True)
    p.add_argument("--speakers", type=int, nargs="*")
    p.add_argument("--text", default=TEXT)
    p.add_argument("--threads", type=int, default=2)
    p.add_argument("--noise", type=float, default=0.667, help="noise_scale (sherpa's default, as the app)")
    p.add_argument("--noise-w", type=float, default=0.8, help="noise_scale_w: how much durations vary")
    p.add_argument("--length", type=float, default=1.0, help="length_scale: > 1 is slower")
    p.add_argument("--sentences", type=int, default=1,
                   help="max_num_sentences: sentences joined into one sequence (the app reads one at a time)")
    p.add_argument("--tag", default="", help="added to the WAV names")
    a = p.parse_args()

    import sherpa_onnx
    import soundfile

    models = [m for m in glob.glob(os.path.join(a.voice, "*.onnx"))]
    if len(models) != 1:
        raise SystemExit(f"expected one .onnx in {a.voice}, found {models}")
    config = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=models[0],
                tokens=os.path.join(a.voice, "tokens.txt"),
                data_dir=os.path.join(a.voice, "espeak-ng-data"),
                noise_scale=a.noise,
                noise_scale_w=a.noise_w,
                length_scale=a.length,
            ),
            num_threads=a.threads,
        ),
        max_num_sentences=a.sentences,
    )
    tts = sherpa_onnx.OfflineTts(config)
    os.makedirs(a.out, exist_ok=True)
    name = os.path.basename(os.path.normpath(a.voice))
    for sid in a.speakers if a.speakers is not None else range(tts.num_speakers):
        start = time.monotonic()
        audio = tts.generate(a.text, sid=sid, speed=1.0)
        elapsed = time.monotonic() - start
        seconds = len(audio.samples) / audio.sample_rate
        target = os.path.join(a.out, f"{name}-s{sid}{a.tag}.wav")
        soundfile.write(target, audio.samples, audio.sample_rate, subtype="PCM_16")
        print(f"{target}\tspeaker {sid}\t{seconds:.1f} s audio\tRTF {elapsed / seconds:.3f} (this machine)")


if __name__ == "__main__":
    main()
