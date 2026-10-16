#!/usr/bin/env bash
# Downloads spike model files to $MODELS_DIR (default ~/granger-models) and verifies them.
# EmbeddingGemma is gated: accept the license at https://huggingface.co/litert-community/embeddinggemma-300m
# while signed in, create a read token at https://huggingface.co/settings/tokens, then run with HF_TOKEN=<token>.
set -euo pipefail

MODELS_DIR="${MODELS_DIR:-$HOME/granger-models}"
mkdir -p "$MODELS_DIR"
cd "$MODELS_DIR"

E2B=https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main
E4B=https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main
EMB=https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main

fetch() { # url file
  echo "Fetching $2"
  curl -fL -C - -o "$2" ${HF_TOKEN:+-H "Authorization: Bearer $HF_TOKEN"} "$1/$2"
}

fetch "$E2B" gemma-4-E2B-it.litertlm
fetch "$E2B" gemma-4-E2B-it-gpu.litertlm
fetch "$E4B" gemma-4-E4B-it.litertlm
fetch "$E4B" gemma-4-E4B-it-gpu.litertlm

sha256sum -c <<'SUMS'
181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c  gemma-4-E2B-it.litertlm
a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5  gemma-4-E2B-it-gpu.litertlm
0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0  gemma-4-E4B-it.litertlm
4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff  gemma-4-E4B-it-gpu.litertlm
SUMS

if [[ -z "${HF_TOKEN:-}" ]]; then
  echo "HF_TOKEN not set: skipping gated EmbeddingGemma files." >&2
  exit 1
fi
fetch "$EMB" embeddinggemma-300M_seq512_mixed-precision.tflite
fetch "$EMB" sentencepiece.model
echo "Record these hashes in the spike results:"
sha256sum embeddinggemma-300M_seq512_mixed-precision.tflite sentencepiece.model
