#!/usr/bin/env python3
"""Emit current red continental priority gaps with level-11 completion checks."""

import json
import os
import subprocess
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))
import runWorld10NamedGaps as named_gaps


def main() -> int:
    if sys.argv[1:] != ["--emit-jobs"]:
        print(f"Usage: {Path(sys.argv[0]).name} --emit-jobs", file=sys.stderr)
        return 2
    if not named_gaps.SNAPSHOT.is_file():
        print(f"Missing coverage snapshot: {named_gaps.SNAPSHOT}", file=sys.stderr)
        return 1
    image = Image.open(named_gaps.SNAPSHOT)
    emitted = subprocess.check_output(
        [str(ROOT / "scripts" / "runWorld10.sh"), "--emit-jobs"],
        text=True,
        env=os.environ | {"WORLD10_ORDER": "america-first"},
    )
    jobs = []
    for line in emitted.splitlines():
        job = json.loads(line)
        latitude, longitude = job["latitude"], job["longitude"]
        if not named_gaps.selected(latitude, longitude):
            continue
        red = named_gaps.red_fraction(image, latitude, longitude)
        # The Moroccan coastal/Atlantic cells and the 36 N Eurasian band have
        # no useful land-mask score in this catalogue despite being visibly
        # continental in coverage-43.
        morocco_atlantic = latitude in {28, 36} and longitude in {-20, -12}
        eurasian_band = latitude in {28, 36, 44} and 4 <= longitude <= 116
        if red >= 0.20 and (job["landLikeCells"] >= 8 or morocco_atlantic or eurasian_band):
            job["jobId"] = f"world11-priority-red-gaps-{job['sequence']:06d}-{latitude}-{longitude}"
            job["completionLevel"] = 11
            jobs.append((red, job["landLikeCells"], job))
    for _, _, job in sorted(jobs, key=lambda item: (item[0], item[1], item[2]["sequence"]), reverse=True):
        print(json.dumps(job, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
