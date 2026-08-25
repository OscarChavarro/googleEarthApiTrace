#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$script_dir"

if (($# > 0)) && [[ "$1" != -* ]]; then
    canonical_output="$(realpath -m "$1")"
    lock_key="$(printf '%s' "$canonical_output" | cksum | awk '{print $1}')"
    lock_file="${TMPDIR:-/tmp}/google-earth-matrix-${lock_key}.lock"
    exec 8>"$lock_file"
    if ! flock -n 8; then
        echo "ERROR: Matrix folder is already being produced or consumed: $canonical_output" >&2
        echo "Wait for the other program 31/32 execution to finish before retrying." >&2
        exit 1
    fi
fi

if (($# == 0)); then
    exec gradle --console=plain run --quiet --args="--mode auto"
fi

exec gradle --console=plain run --quiet --args="--mode auto $*"
