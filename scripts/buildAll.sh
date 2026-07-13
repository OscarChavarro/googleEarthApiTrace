#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

filter_nested_projects() {
  local filtered=()
  local candidate
  local existing
  for candidate in "$@"; do
    local include=1
    for existing in "${filtered[@]}"; do
      if [[ "$candidate" == "$existing" || "$candidate" == "$existing"/* ]]; then
        include=0
        break
      fi
    done
    if [[ $include -eq 1 ]]; then
      filtered+=("$candidate")
    fi
  done
  printf '%s\n' "${filtered[@]}"
}

mapfile -t GRADLE_PROJECTS < <(
  find "$ROOT_DIR" -type f -name build.gradle -printf '%h\n' | sort -u
)

GRADLE_ROOT_BUILD=0
FILTERED_GRADLE_PROJECTS=()
for project in "${GRADLE_PROJECTS[@]}"; do
  if [[ "$project" == "$ROOT_DIR" ]]; then
    GRADLE_ROOT_BUILD=1
    continue
  fi
  FILTERED_GRADLE_PROJECTS+=("$project")
done
GRADLE_PROJECTS=("${FILTERED_GRADLE_PROJECTS[@]}")
if [[ ${#GRADLE_PROJECTS[@]} -gt 0 ]]; then
  mapfile -t GRADLE_PROJECTS < <(filter_nested_projects "${GRADLE_PROJECTS[@]}")
fi

mapfile -t CMAKE_CANDIDATES < <(
  find "$ROOT_DIR" -type f -name CMakeLists.txt -printf '%h\n' | sort -u
)

if [[ ${#CMAKE_CANDIDATES[@]} -gt 0 ]]; then
  mapfile -t CMAKE_PROJECTS < <(filter_nested_projects "${CMAKE_CANDIDATES[@]}")
else
  CMAKE_PROJECTS=()
fi

if [[ ${#GRADLE_PROJECTS[@]} -eq 0 && ${#CMAKE_PROJECTS[@]} -eq 0 ]]; then
  echo 'No Gradle/CMake projects found.'
  exit 0
fi

if [[ $GRADLE_ROOT_BUILD -eq 1 ]]; then
  echo 'Building Gradle root project...'
  echo "[Gradle] $ROOT_DIR"
  if [[ -x "$ROOT_DIR/gradlew" ]]; then
    (cd "$ROOT_DIR" && ./gradlew build)
  else
    (cd "$ROOT_DIR" && gradle build)
  fi
elif [[ ${#GRADLE_PROJECTS[@]} -gt 0 ]]; then
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
