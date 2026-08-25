#!/usr/bin/env bash
set -euo pipefail
gradle --console=plain run --quiet --args="--offline --start-frame 3 --output output/frame0003.png"
