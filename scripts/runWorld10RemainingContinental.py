#!/usr/bin/env python3
"""Emit substantial red cells from coverage 43 across all continental regions."""

import importlib.util
import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HELPER_PATH = ROOT / "scripts/runWorld10PriorityMissingCoverage.py"
spec = importlib.util.spec_from_file_location("coverage43", HELPER_PATH)
coverage43 = importlib.util.module_from_spec(spec)
assert spec.loader is not None
spec.loader.exec_module(coverage43)


def continental_region(lat, lon):
    """Broad land envelopes; exclude the polar and open-ocean red fields."""
    return (
        (-36 <= lat <= 52 and -124 <= lon <= -36)  # Americas
        or (-36 <= lat <= 52 and -20 <= lon <= 140)  # Africa, Europe, Asia
        or (-36 <= lat <= 4 and 108 <= lon <= 156)  # Australia/Indonesia
        or (12 <= lat <= 52 and 140 <= lon <= 156)  # Far East
    )


def main():
    if sys.argv[1:] != ["--emit-jobs"]:
        print(f"Usage: {Path(sys.argv[0]).name} --emit-jobs", file=sys.stderr)
        return 2
    image = coverage43.Image.open(coverage43.SNAPSHOT)
    payload = subprocess.check_output(
        [str(ROOT / "scripts/runWorld10.sh"), "--emit-jobs"],
        text=True,
        env=os.environ | {"WORLD10_ORDER": "america-first"},
    )
    jobs = []
    for line in payload.splitlines():
        job = json.loads(line)
        lat, lon = job["latitude"], job["longitude"]
        # Keep to the captured world band and demand a meaningful continental
        # footprint; this rejects the broad red oceans.
        if not (continental_region(lat, lon) and job["landLikeCells"] >= 12):
            continue
        missing = coverage43.red_fraction(image, lat, lon)
        if missing >= 0.25:
            jobs.append((missing, job["landLikeCells"], job))
    for _, _, job in sorted(jobs, key=lambda entry: (entry[0], entry[1], entry[2]["sequence"]), reverse=True):
        print(json.dumps(job, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
