#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

printf 'Cleaning build directories under %s\n' "$ROOT_DIR"
find "$ROOT_DIR" -type d -name build -prune -print -exec rm -rf {} +

printf 'Cleaning Gradle state directories (.gradle, .gradle-user-home)\n'
find "$ROOT_DIR" -type d \( -name .gradle -o -name .gradle-user-home \) -prune -print -exec rm -rf {} +

printf 'Cleaning in-source CMake state (CMakeFiles, CMakeCache.txt, cmake_install.cmake, compile_commands.json, Makefile)\n'
find "$ROOT_DIR" -type d -name CMakeFiles -prune -print -exec rm -rf {} +
find "$ROOT_DIR" -type f \( \
  -name CMakeCache.txt -o \
  -name cmake_install.cmake -o \
  -name compile_commands.json -o \
  -name Makefile \
\) -print -delete

printf 'Cleaning IntelliJ directories (.idea)\n'
find "$ROOT_DIR" -type d -name .idea -prune -print -exec rm -rf {} +

printf 'Removing editor backup files (*~ and #...#)\n'
find "$ROOT_DIR" -type f \( -name '*~' -o -name '#*#' \) -print -delete

printf 'Done.\n'
