#!/usr/bin/env python3
"""Keeps only what export_voice.py needs from training checkpoints: the generator and the hyperparameters (V4).

    slim_ckpt.py <out dir> <checkpoint …>

A full checkpoint is ~850 MB, ~90% of it the discriminators and the optimizer state; the slim one is ~95 MB. Run on
the pod before copying back (the pods' download ran at ~1.6 MB/s). Slim checkpoints export but don't resume.
"""
import os
import sys

import torch

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
for path in sys.argv[2:]:
    try:
        c = torch.load(path, map_location="cpu", weights_only=False)
    except FileNotFoundError:  # RunPod's network volume can still list a checkpoint Lightning deleted hours ago
        print(f"{path}: listed but gone, skipped", flush=True)
        continue
    slim = {k: c[k] for k in ("epoch", "global_step", "pytorch-lightning_version", "hparams_name", "hyper_parameters")
            if k in c}
    slim["state_dict"] = {k: v for k, v in c["state_dict"].items() if k.startswith("model_g.")}
    target = os.path.join(out, os.path.basename(path))
    torch.save(slim, target + ".tmp")
    os.replace(target + ".tmp", target)
    print(f"{target}: {os.path.getsize(target) / 1e6:.0f} MB", flush=True)
