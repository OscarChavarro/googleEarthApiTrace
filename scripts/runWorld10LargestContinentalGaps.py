#!/usr/bin/env python3
"""Emit the largest coverage-43 red gaps within continental envelopes.

Unlike the broad recovery queue, these jobs retain level-10 completion checks:
the coverage viewer itself reports level-10 availability, so an already full
level-10 cell is never recaptured merely for a level-11 refinement.
"""

import json
import os
import subprocess
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import runWorld10PriorityMissingCoverage as coverage43


def continental(latitude: int, longitude: int) -> bool:
    return (
        (12 <= latitude <= 60 and -124 <= longitude <= -60)  # North America
        or (-60 <= latitude <= 12 and -76 <= longitude <= -44)  # South America
        or (-28 <= latitude <= 28 and -12 <= longitude <= 44)  # Africa
        or (28 <= latitude <= 52 and 4 <= longitude <= 132)  # Europe / Asia band
        or (-36 <= latitude <= -12 and 116 <= longitude <= 148)  # Australia
    )


def main() -> int:
    if sys.argv[1:] != ["--emit-jobs"]:
        print(f"Usage: {Path(sys.argv[0]).name} --emit-jobs", file=sys.stderr)
        return 2
    if not coverage43.SNAPSHOT.is_file():
        print(f"Missing coverage snapshot: {coverage43.SNAPSHOT}", file=sys.stderr)
        return 1
    image = Image.open(coverage43.SNAPSHOT)
    emitted = subprocess.check_output(
        [str(ROOT / "scripts" / "runWorld10.sh"), "--emit-jobs"],
        text=True,
        env=os.environ | {"WORLD10_ORDER": "america-first"},
    )
    jobs = []
    for line in emitted.splitlines():
        job = json.loads(line)
        latitude, longitude = job["latitude"], job["longitude"]
        if not continental(latitude, longitude):
            continue
        red = coverage43.red_fraction(image, latitude, longitude)
        # An 8x8-degree cell that is at least half red is a genuine large gap.
        if red < 0.50:
            continue
        job["jobId"] = f"world10-largest-continental-gaps-{job['sequence']:06d}-{latitude}-{longitude}"
        job["completionLevel"] = 10
        jobs.append((red, job["landLikeCells"], job))
    for _, _, job in sorted(jobs, key=lambda item: (item[0], item[1], item[2]["sequence"]), reverse=True):
        print(json.dumps(job, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
