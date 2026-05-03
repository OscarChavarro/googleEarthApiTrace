#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

mapfile -t GRADLE_PROJECTS < <(
  find "$ROOT_DIR" -type f -name build.gradle -printf '%h\n' | sort -u
)

mapfile -t CMAKE_CANDIDATES < <(
  find "$ROOT_DIR" -type f -name CMakeLists.txt -printf '%h\n' | sort -u
)

CMAKE_PROJECTS=()
for candidate in "${CMAKE_CANDIDATES[@]}"; do
  include=1
  for existing in "${CMAKE_PROJECTS[@]}"; do
    if [[ "$candidate" == "$existing"/* ]]; then
      include=0
      break
    fi
  done
  if [[ $include -eq 1 ]]; then
    CMAKE_PROJECTS+=("$candidate")
  fi
done

if [[ ${#GRADLE_PROJECTS[@]} -eq 0 && ${#CMAKE_PROJECTS[@]} -eq 0 ]]; then
  echo 'No Gradle/CMake projects found.'
  exit 0
fi

if [[ ${#GRADLE_PROJECTS[@]} -gt 0 ]]; then
  echo 'Building Gradle projects...'
  for project in "${GRADLE_PROJECTS[@]}"; do
    echo "[Gradle] $project"
    if [[ -x "$project/gradlew" ]]; then
      (cd "$project" && ./gradlew build)
    else
      (cd "$project" && gradle build)
    fi
  done
fi

if [[ ${#CMAKE_PROJECTS[@]} -gt 0 ]]; then
  echo 'Building CMake projects...'
  for project in "${CMAKE_PROJECTS[@]}"; do
    echo "[CMake] $project"
    build_dir="$project/build"
    cmake -S "$project" -B "$build_dir"
    cmake --build "$build_dir"
  done
fi

echo 'All builds finished.'
