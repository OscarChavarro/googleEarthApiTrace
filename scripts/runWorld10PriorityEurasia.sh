#!/usr/bin/env bash

set -Eeuo pipefail

if [[ "$#" -ne 1 || "$1" != "--emit-jobs" ]]; then
    printf 'Usage: %s --emit-jobs\n' "$0" >&2
    exit 2
fi

# Continental Eurasia candidates visible as broad red regions in coverage 43.
# The destination inventory in the controller removes any extent already
# complete; landLikeCells filters the surrounding ocean before capture.
WORLD10_ORDER=america-first "$PWD/scripts/runWorld10.sh" --emit-jobs |
    awk '
        match($0, /"sequence":([0-9]+)/, seq) &&
        match($0, /"latitude":(-?[0-9]+)/, latMatch) &&
        match($0, /"longitude":(-?[0-9]+)/, lonMatch) &&
        match($0, /"landLikeCells":([0-9]+)/, landMatch) {
            lat = latMatch[1] + 0
            lon = lonMatch[1] + 0
            land = landMatch[1] + 0
            if (lat >= 12 && lat <= 52 && lon >= 4 && lon <= 140 && land >= 12) {
                # North-to-south, west-to-east traversal of the continental gaps.
                printf "%03d %04d %04d %s\n", 60 - lat, lon + 180, seq[1], $0
            }
        }
    ' | sort -n -k1,1 -k2,2 -k3,3 | cut -d ' ' -f4-
