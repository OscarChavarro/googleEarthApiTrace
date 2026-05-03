#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if ! command -v cloc >/dev/null 2>&1; then
  echo 'Error: cloc is not installed or not in PATH.' >&2
  exit 1
fi

exec cloc "$ROOT_DIR" \
  --exclude-dir=.git,.gradle,build \
  --fullpath
