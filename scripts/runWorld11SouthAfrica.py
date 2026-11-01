#!/usr/bin/env python3
"""Emit just the red coverage-43 blocks over southern Africa.

The jobs deliberately advertise level 11 completion.  Level-10 inventory can
already contain a coarse parent while its level-11 uncle-linked refinement is
still absent; filtering on level 10 would incorrectly skip that repair.
"""

import json
import os
import subprocess
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
HELPER = ROOT / "scripts" / "runWorld10PriorityMissingCoverage.py"
sys.path.insert(0, str(HELPER.parent))
import runWorld10PriorityMissingCoverage as coverage43


def in_southern_africa(latitude: int, longitude: int) -> bool:
    return -36 <= latitude <= -20 and 12 <= longitude <= 36


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
    pending = []
    for line in emitted.splitlines():
        job = json.loads(line)
        latitude, longitude = job["latitude"], job["longitude"]
        red = coverage43.red_fraction(image, latitude, longitude)
        if in_southern_africa(latitude, longitude) and red >= 0.15:
            job["jobId"] = f"world11-south-africa-{job['sequence']:06d}-{latitude}-{longitude}"
            job["completionLevel"] = 11
            pending.append((red, job["landLikeCells"], job))
    for _, _, job in sorted(pending, key=lambda item: (item[0], item[1], item[2]["sequence"]), reverse=True):
        print(json.dumps(job, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
