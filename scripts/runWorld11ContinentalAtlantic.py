#!/usr/bin/env python3
"""Emit visibly red coverage-43 blocks over land and the Atlantic basin."""

import json
import os
import subprocess
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import runWorld10PriorityMissingCoverage as coverage43


def selected(latitude: int, longitude: int) -> bool:
    continental = (
        (-60 <= latitude <= 60 and -132 <= longitude <= -36)  # Americas
        or (-36 <= latitude <= 60 and -20 <= longitude <= 140)  # Africa/Europe/Asia
        or (-44 <= latitude <= 4 and 108 <= longitude <= 156)  # Australia
        or (12 <= latitude <= 60 and 140 <= longitude <= 156)  # far east Asia
    )
    atlantic = -60 <= latitude <= 52 and -76 <= longitude <= 12
    return continental or atlantic


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
        if not selected(latitude, longitude):
            continue
        red = coverage43.red_fraction(image, latitude, longitude)
        if red < 0.25:
            continue
        job["jobId"] = f"world11-continental-atlantic-{job['sequence']:06d}-{latitude}-{longitude}"
        job["completionLevel"] = 11
        jobs.append((red, job["landLikeCells"], job))
    for _, _, job in sorted(jobs, key=lambda item: (item[0], item[1], item[2]["sequence"]), reverse=True):
        print(json.dumps(job, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
