#!/usr/bin/env bash

set -Eeuo pipefail

if [[ "$#" -ne 1 || "$1" != "--emit-jobs" ]]; then
    printf 'Usage: %s --emit-jobs\n' "$0" >&2
    exit 2
fi

# The coverage viewer identifies two rectangular gaps worth addressing before
# returning to the broader Americas traversal: the Great Lakes and southern
# Brazil/northern Argentina.  These are world10 catalog sequence identifiers;
# runWorld10 remains the single source of their job contracts.
# 673 and 634 have already reached a no-op merge; 632 failed its level-10
# validation and 634's later recapture was interrupted.  Do not revisit any
# of those attempts: continue at the next large North-American gap.
readonly TARGET_SEQUENCES='503 501 499 377 341 334 625 426 340 332 299 158 157 152 151 145 144 143'

WORLD10_ORDER=america-first "$PWD/scripts/runWorld10.sh" --emit-jobs |
    awk -v targets="$TARGET_SEQUENCES" '
        BEGIN {
            count = split(targets, ordered, / /)
            for (orderIndex = 1; orderIndex <= count; orderIndex++) {
                wanted[ordered[orderIndex]] = orderIndex
            }
        }
        match($0, /"sequence":([0-9]+)/, matchData) {
            sequence = matchData[1]
            if (sequence in wanted) {
                records[wanted[sequence]] = $0
            }
        }
        END {
            for (orderIndex = 1; orderIndex <= count; orderIndex++) {
                if (orderIndex in records) {
                    print records[orderIndex]
                }
            }
        }
    '
