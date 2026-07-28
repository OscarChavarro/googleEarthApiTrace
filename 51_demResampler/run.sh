#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -lt 2 ]; then
    echo 'Usage: ./run.sh <inputFabdemFolder> <outputPyramidFolder> [--threads <n>] [--resume]' >&2
    exit 1
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-${script_dir}/../.gradle}"

cd "$script_dir"
gradle run --quiet --args="$*"
