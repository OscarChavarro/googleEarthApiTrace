#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

printf 'Cleaning build directories under %s\n' "$ROOT_DIR"
find "$ROOT_DIR" -type d -name build -prune -print -exec rm -rf {} +

printf 'Cleaning IntelliJ directories (.idea)\n'
find "$ROOT_DIR" -type d -name .idea -prune -print -exec rm -rf {} +

printf 'Removing editor backup files (*~ and #...#)\n'
find "$ROOT_DIR" -type f \( -name '*~' -o -name '#*#' \) -print -delete

printf 'Done.\n'
