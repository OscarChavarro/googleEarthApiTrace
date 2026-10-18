#!/usr/bin/env python3
"""Emit only continental world10 jobs whose coverage-43 cell is red."""

import json
import os
import subprocess
import sys
from pathlib import Path

from PIL import Image

PROJECT_ROOT = Path(__file__).resolve().parent.parent
SNAPSHOT = Path("/tmp/b.png")
# Bounds of coverage 43's equirectangular 1024x336 world matrix in its X11
# window snapshot.  It represents longitude [-180, 180], latitude [-60, 60].
MAP_LEFT, MAP_TOP, MAP_WIDTH, MAP_HEIGHT = 448, 865, 1024, 336


def red_fraction(image: Image.Image, latitude: int, longitude: int) -> float:
    left = MAP_LEFT + round((longitude + 180) * MAP_WIDTH / 360)
    right = MAP_LEFT + round((longitude + 188) * MAP_WIDTH / 360)
    top = MAP_TOP + round((60 - (latitude + 8)) * MAP_HEIGHT / 120)
    bottom = MAP_TOP + round((60 - latitude) * MAP_HEIGHT / 120)
    pixels = image.crop((left, top, right, bottom)).convert("RGB").getdata()
    total = len(pixels)
    if not total:
        return 0.0
    return sum(red > 200 and green < 80 and blue < 80 for red, green, blue in pixels) / total


def continental_eurasia_or_africa(latitude: int, longitude: int) -> bool:
    return (12 <= latitude <= 52 and 4 <= longitude <= 140) or (-36 <= latitude <= 36 and -20 <= longitude <= 52)


def main() -> int:
    if sys.argv[1:] != ["--emit-jobs"]:
        print(f"Usage: {Path(sys.argv[0]).name} --emit-jobs", file=sys.stderr)
        return 2
    if not SNAPSHOT.is_file():
        print(f"Missing coverage snapshot: {SNAPSHOT}", file=sys.stderr)
        return 1
    image = Image.open(SNAPSHOT)
    env = os.environ | {"WORLD10_ORDER": "america-first"}
    emitted = subprocess.check_output([str(PROJECT_ROOT / "scripts/runWorld10.sh"), "--emit-jobs"], text=True, env=env)
    candidates = []
    for line in emitted.splitlines():
        job = json.loads(line)
        latitude, longitude = job["latitude"], job["longitude"]
        if job["landLikeCells"] < 12 or not continental_eurasia_or_africa(latitude, longitude):
            continue
        missing = red_fraction(image, latitude, longitude)
        # A red quarter of an 8x8-degree cell is a substantial uncovered area;
        # sparse red roads and overlay seams do not schedule a capture.
        if missing >= 0.25:
            candidates.append((missing, job["landLikeCells"], latitude, -longitude, job))
    for _, _, _, _, job in sorted(candidates, reverse=True):
        print(json.dumps(job, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
