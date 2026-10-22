#!/usr/bin/env python3
"""`python -m piper.train fit …` with the Granger voice's checkpoints (Plan V3); train.sh runs it.

piper1-gpl's entry point also checkpoints on "val_mos" (UTMOS, which it fetches through torch.hub with
trust_repo=True); with `--model.mos_metric none` that callback raises at the end of the first epoch, and LightningCLI
always appends `trainer_defaults` callbacks, so they can't be swapped from the command line. This is the same CLI
with these callbacks instead: the five best by val_mel plus `last.ckpt` (what a resume needs), and a snapshot every
100 epochs that is never deleted, for listening at ~200, ~300, … (Task 3).
"""
import logging

import torch
from lightning.pytorch.callbacks import ModelCheckpoint
from piper.train.__main__ import VitsLightningCLI
from piper.train.vits.dataset import VitsDataModule
from piper.train.vits.lightning import VitsModel


def main():
    logging.basicConfig(level=logging.INFO)
    torch.backends.cuda.matmul.allow_tf32 = True
    torch.backends.cudnn.allow_tf32 = True
    callbacks = [
        ModelCheckpoint(
            monitor="val_mel",
            mode="min",
            save_top_k=5,
            save_last=True,
            filename="epoch={epoch}-val_mel={val_mel:.4f}",
            auto_insert_metric_name=False,
        ),
        ModelCheckpoint(
            every_n_epochs=100,
            save_top_k=-1,
            filename="snapshot-epoch={epoch}",
            auto_insert_metric_name=False,
        ),
    ]
    VitsLightningCLI(VitsModel, VitsDataModule, trainer_defaults={"max_epochs": -1, "callbacks": callbacks})


if __name__ == "__main__":
    main()
