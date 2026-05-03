#!/usr/bin/env bash
set -euo pipefail
gradle run --quiet --args="--offline --start-frame 3 --output output/frame0003.png"
