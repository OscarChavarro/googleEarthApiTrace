#!/usr/bin/env bash

set -Eeuo pipefail

readonly PROJECT_DIR="/paradigmas/master/algoritmos_basicos_3d/googleEarthApiTrace"
readonly PATH_PLANNER_DIR="$PROJECT_DIR/11_pathPlanner"
readonly MY_PLACES_DIR="$HOME/.googleearth"

for ((LON = -3; LON <= 5; LON++)); do
    LAT_START=37

    for ((LAT = LAT_START; LAT <= 43; LAT++)); do
        cp -- "$MY_PLACES_DIR/myplaces.kml.bak" "$MY_PLACES_DIR/myplaces.kml"

        cd "$PATH_PLANNER_DIR"
        ./run.sh zigzag "$LAT" "$LON" 12000 400 12000 1 1

        cd "$PROJECT_DIR"
        ./scripts/runFullProcess.sh \
            --route-command "./run.sh zigzag $LAT $LON 12000 400 12000 1 1"
    done
done
