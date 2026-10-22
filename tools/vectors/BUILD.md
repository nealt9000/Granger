# Building the vector set (Plan R1)

Everything large lives outside the repo:

- **Python:** `~/.venvs/granger-vectors` (Python 3.14; libzim, numpy, sentence-transformers, torch with MPS, pytest).
  Recreate it with `python3 -m venv ~/.venvs/granger-vectors && ~/.venvs/granger-vectors/bin/pip install libzim numpy sentence-transformers pytest`.
- **Sources:** `~/Developer/Granger/vectors-work/sources/` (ZIMs and EPUBs, pulled from the phone or downloaded from the catalog).
- **Chunker:** `./gradlew :tools:chunker:installDist` (JDK 17), then `tools/chunker/build/install/chunker/bin/chunker`.
- **Tests:** `~/.venvs/granger-vectors/bin/python -m pytest tools/vectors/tests`.

## Parity (Task 1)

`parity.py RUN --books DIR --zim PACK=FILE ...` checks every passage the phone recorded in an eval run against the Mac's
chunks of the same files. 2026-10-21, `r0-combined`: books 6,369/6,369, IEP 2,898/2,898, Wikipedia chemistry 1,871/1,871.
