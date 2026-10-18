#!/usr/bin/env python3
"""Emit red coverage-43 cells for the user-selected continental gaps."""

import json
import os
import subprocess
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SNAPSHOT = Path("/tmp/b.png")
LEFT, TOP, WIDTH, HEIGHT = 448, 865, 1024, 336


def red_fraction(image, lat, lon):
    x0 = LEFT + round((lon + 180) * WIDTH / 360)
    x1 = LEFT + round((lon + 188) * WIDTH / 360)
    y0 = TOP + round((60 - (lat + 8)) * HEIGHT / 120)
    y1 = TOP + round((60 - lat) * HEIGHT / 120)
    pixels = image.crop((x0, y0, x1, y1)).convert("RGB").getdata()
    return sum(r > 200 and g < 80 and b < 80 for r, g, b in pixels) / len(pixels)


def selected(lat, lon):
    # Amazon mouth; southern Chile; Atlantic off Morocco; southern Africa;
    # Australia; southern India; Mediterranean-to-Mongolia band.
    return (
        (lat == -4 and -60 <= lon <= -44)
        or (lat in {-52, -44} and lon in {-76, -68})
        or (lat in {28, 36} and -20 <= lon <= 116)
        or (lat == 44 and 36 <= lon <= 116)
        or (lat in {-36, -28, -20} and 12 <= lon <= 36)
        or (lat in {-28, -20, -12} and 124 <= lon <= 148)
        or (lat == 4 and lon in {76, 84})
    )


def main():
    if sys.argv[1:] != ["--emit-jobs"]:
        print(f"Usage: {Path(sys.argv[0]).name} --emit-jobs", file=sys.stderr)
        return 2
    image = Image.open(SNAPSHOT)
    payload = subprocess.check_output(
        [str(ROOT / "scripts/runWorld10.sh"), "--emit-jobs"],
        text=True,
        env=os.environ | {"WORLD10_ORDER": "america-first"},
    )
    jobs = []
    for line in payload.splitlines():
        job = json.loads(line)
        lat, lon = job["latitude"], job["longitude"]
        if not selected(lat, lon):
            continue
        missing = red_fraction(image, lat, lon)
        # Permit the explicitly requested Moroccan Atlantic cells even when
        # landLikeCells is zero; all other entries must be continental.
        atlantic_morocco = lat in {28, 36} and lon in {-20, -12}
        if missing >= 0.20 and (job["landLikeCells"] >= 8 or atlantic_morocco):
            jobs.append((missing, job["landLikeCells"], job))
    for _, _, job in sorted(jobs, reverse=True):
        print(json.dumps(job, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
