#!/usr/bin/env bash

set -Eeuo pipefail

readonly PYRAMID_DIR="${1:-/samples/datasets/googleEarth/toplevel}"
readonly MIN_LAT="36.0"
readonly MAX_LAT="44.0"
readonly MIN_LON="-9.5"
readonly MAX_LON="4.5"
readonly TILE_LAT_SPAN="0.25"
readonly TILE_LON_SPAN="0.25"
readonly TILE_CENTER_OFFSET="0.125"
readonly COMPLETION_CHECK_DEPTH=16

die() {
    printf '[countLevel16Centers][ERROR] %s\n' "$*" >&2
    exit 1
}

usage() {
    cat <<'EOF'
Usage: ./scripts/countLevel16Centers.sh [pyramid-dir]

Counts Spain 0.25-degree candidate-region centers that already have their
containing level-16 PNG in the pyramidal image tree.

Default pyramid-dir: /samples/datasets/googleEarth/toplevel
EOF
}

case "${1:-}" in
    -h|--help)
        usage
        exit 0
        ;;
esac

[[ -d "$PYRAMID_DIR" ]] || die "Pyramid directory does not exist: $PYRAMID_DIR"

awk \
    -v min_lat="$MIN_LAT" \
    -v max_lat="$MAX_LAT" \
    -v min_lon="$MIN_LON" \
    -v max_lon="$MAX_LON" \
    -v region_lat_span="$TILE_LAT_SPAN" \
    -v region_lon_span="$TILE_LON_SPAN" \
    -v center_offset="$TILE_CENTER_OFFSET" \
    -v completion_depth="$COMPLETION_CHECK_DEPTH" \
    -v pyramid_dir="$PYRAMID_DIR" '
    function floor_value(value) {
        return value < int(value) ? int(value) - 1 : int(value)
    }

    function clamp(value, minimum, maximum) {
        if (value < minimum) return minimum
        if (value > maximum) return maximum
        return value
    }

    function quadkey_from_coordinates(depth, column, south_row,    bit, east, north, quadkey) {
        quadkey = "0"
        for (bit = depth - 1; bit >= 0; bit--) {
            east = int(column / (2 ^ bit)) % 2
            north = int(south_row / (2 ^ bit)) % 2
            if (north == 0 && east == 0) {
                quadkey = quadkey "0"
            } else if (north == 0 && east == 1) {
                quadkey = quadkey "1"
            } else if (north == 1 && east == 0) {
                quadkey = quadkey "3"
            } else {
                quadkey = quadkey "2"
            }
        }
        return quadkey
    }

    ARGIND == 1 {
        if (length($0) == 21 && substr($0, 1, 1) == "0") {
            present[substr($0, 1, 17)] = 1
            level16_files++
        }
        next
    }

    ARGIND == 2 && length($0) == 20 && substr($0, 1, 1) == "0" {
        quadkey = substr($0, 1, 16)
        column = 0
        south_row = 0

        for (digit_index = 2; digit_index <= 16; digit_index++) {
            quadrant = substr(quadkey, digit_index, 1)
            if (quadrant !~ /^[0-3]$/) {
                next
            }
            column = column * 2 + ((quadrant == 1 || quadrant == 2) ? 1 : 0)
            south_row = south_row * 2 + ((quadrant == 2 || quadrant == 3) ? 1 : 0)
        }

        level15_files++
        source_span = 360.0 / 32768.0
        west = -180.0 + column * source_span
        east = west + source_span
        south = -180.0 + south_row * source_span
        north = south + source_span
        base_x = floor_value((west - min_lon) / region_lon_span)
        base_y = floor_value((south - min_lat) / region_lat_span)

        for (dx = -1; dx <= 1; dx++) {
            for (dy = -1; dy <= 1; dy++) {
                x = base_x + dx
                y = base_y + dy
                region_west = min_lon + x * region_lon_span
                region_south = min_lat + y * region_lat_span
                if (region_west >= min_lon && region_west < max_lon &&
                    region_south >= min_lat && region_south < max_lat &&
                    east > region_west && west < region_west + region_lon_span &&
                    north > region_south && south < region_south + region_lat_span) {
                    covered[y SUBSEP x] = 1
                }
            }
        }
    }

    END {
        for (key in covered) {
            split(key, coordinates, SUBSEP)
            center_lat = min_lat + coordinates[1] * region_lat_span + center_offset
            center_lon = min_lon + coordinates[2] * region_lon_span + center_offset
            matrix_side = 2 ^ completion_depth
            source_span = 360.0 / matrix_side
            column = clamp(floor_value((center_lon + 180.0) / source_span), 0, matrix_side - 1)
            south_row = clamp(floor_value((center_lat + 180.0) / source_span), 0, matrix_side - 1)
            center_quadkey = quadkey_from_coordinates(completion_depth, column, south_row)

            total_centers++
            if (center_quadkey in present) {
                downloaded_centers++
            } else {
                pending_centers++
            }
        }

        printf "pyramidDir=%s\n", pyramid_dir
        printf "sourceLevel15Files=%d\n", level15_files
        printf "existingLevel16Files=%d\n", level16_files
        printf "expectedCenters=%d\n", total_centers
        printf "downloadedCenters=%d\n", downloaded_centers
        printf "pendingCenters=%d\n", pending_centers
    }
' \
    <(find "$PYRAMID_DIR" -type f -name '?????????????????.png' -printf '%f\n') \
    <(find "$PYRAMID_DIR" -type f -name '????????????????.png' -printf '%f\n')
