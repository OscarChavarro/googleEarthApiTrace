#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$script_dir"

if (($# == 0)); then
    exec gradle --console=plain run --quiet
fi

exec gradle --console=plain run --quiet --args="$*"
