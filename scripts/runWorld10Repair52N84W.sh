#!/usr/bin/env bash

set -Eeuo pipefail

if [[ "$#" -ne 1 || "$1" != "--emit-jobs" ]]; then
    printf 'Usage: %s --emit-jobs\n' "$0" >&2
    exit 2
fi

# 52–60 N / 84–76 W previously mixed level-10 and level-11 imagery at
# 100 km. Capture from 120 km to stabilize the level-10 LOD.
printf '%s\n' \
  '{"sequence":632,"total":1,"jobId":"world10-repair-52--84-alt120","latitude":52,"longitude":-84,"latitudeSpan":8,"longitudeSpan":8,"completionLevel":10,"center":"56,-80","landLikeCells":56,"routeCommand":"./run.sh zigzag 52 -84 180000 3200 120000 8 8"}'
