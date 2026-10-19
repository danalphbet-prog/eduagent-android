#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$PROJECT_DIR/third_party/llama.cpp"
PIN="f174553e1621c6a22a64e7bfcbb58d3a5c49e6fb"

if [[ -d "$DEST/.git" ]]; then
  echo "llama.cpp já existe em $DEST. Confira o commit usado antes de compilar."
  git -C "$DEST" rev-parse HEAD
  exit 0
fi

mkdir -p "$PROJECT_DIR/third_party"
git clone --no-checkout --filter=blob:none https://github.com/ggml-org/llama.cpp.git "$DEST"
git -C "$DEST" fetch --depth 1 origin "$PIN"
git -C "$DEST" checkout --detach "$PIN"
echo "llama.cpp pronto em $PIN"
