#!/usr/bin/env bash

set -Eeuo pipefail

if [[ "$#" -ne 1 || "$1" != "--emit-jobs" ]]; then
    printf 'Usage: %s --emit-jobs\n' "$0" >&2
    exit 2
fi

# Continental Africa, excluding the surrounding Atlantic, Indian Ocean and
# Mediterranean-only blocks. The controller filters extents already complete.
WORLD10_ORDER=america-first "$PWD/scripts/runWorld10.sh" --emit-jobs |
    awk '
        match($0, /"sequence":([0-9]+)/, seq) &&
        match($0, /"latitude":(-?[0-9]+)/, latMatch) &&
        match($0, /"longitude":(-?[0-9]+)/, lonMatch) &&
        match($0, /"landLikeCells":([0-9]+)/, landMatch) {
            lat = latMatch[1] + 0
            lon = lonMatch[1] + 0
            land = landMatch[1] + 0
            if (lat >= -36 && lat <= 36 && lon >= -20 && lon <= 52 && land >= 12) {
                printf "%03d %04d %04d %s\n", 40 - lat, lon + 180, seq[1], $0
            }
        }
    ' | sort -n -k1,1 -k2,2 -k3,3 | cut -d ' ' -f4-
