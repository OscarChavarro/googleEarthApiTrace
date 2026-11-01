#!/usr/bin/env python3
"""The deliberately small, human-reviewed world10 continental repair plan."""

import json
import os
import shlex
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# Only the three northernmost North American regions remain in the viewer plan.
TARGETS = [
    (60, -124), (60, -132), (60, -140),
    (36, 44), (36, 52),
]

# Validate available imagery; coastal rectangles may remain incomplete.
# Route arguments: generator lat lon step max_distance altitude lat_span lon_span.
CAPTURE_STEP = 180000
CAPTURE_HEIGHT = 100000
NORTH_CAPTURE_HEIGHT = 90000  # 10% closer for the 60–68 N recapture.


def main() -> int:
    arguments = sys.argv[1:]
    limit = None
    if arguments == ["--emit-jobs"]:
        pass
    elif len(arguments) == 3 and arguments[:2] == ["--emit-jobs", "--limit"] and arguments[2].isdigit():
        limit = int(arguments[2])
    else:
        print(f"Usage: {Path(sys.argv[0]).name} --emit-jobs [--limit N]", file=sys.stderr)
        return 2
    raw = subprocess.check_output(
        [str(ROOT / "scripts" / "runWorld10.sh"), "--emit-jobs"],
        text=True,
        env=os.environ | {"WORLD10_ORDER": "america-first"},
    )
    by_coordinate = {}
    for line in raw.splitlines():
        job = json.loads(line)
        by_coordinate[(job["latitude"], job["longitude"])] = job
    for latitude, longitude in TARGETS[:limit]:
        if (latitude, longitude) in by_coordinate:
            job = dict(by_coordinate[(latitude, longitude)])
        elif latitude == 60 and longitude in (-124, -132, -140):
            # The baseline catalog stops south of this explicitly reviewed band.
            sequence = max(j["sequence"] for j in by_coordinate.values()) + 1 + (-124 - longitude) // 8
            job = {
                "sequence": sequence,
                "total": max(j["total"] for j in by_coordinate.values()) + 3,
                "latitude": latitude, "longitude": longitude,
                "latitudeSpan": 8, "longitudeSpan": 8,
                "center": f"{latitude + 4},{longitude + 4}",
                "completionLevel": 10,
                "routeCommand": f"./run.sh zigzag {latitude} {longitude} 180000 3200 100000 8 8",
            }
        else:
            raise ValueError(f"unreviewed region absent from baseline catalog: {(latitude, longitude)}")
        job["jobId"] = f"world10-priority-continental-{job['sequence']:06d}-{latitude}-{longitude}"
        job["force"] = True
        job["mustCommit"] = True
        job["mergeLevelOnly"] = 10
        route = shlex.split(job["routeCommand"])
        if len(route) != 9 or route[1] != "zigzag":
            raise ValueError(f"unexpected repair route command: {job['routeCommand']!r}")
        capture_step = CAPTURE_STEP
        capture_height = NORTH_CAPTURE_HEIGHT if latitude == 60 else CAPTURE_HEIGHT
        route[4] = str(capture_step)
        route[6] = str(capture_height)
        route[5] = "3200"
        route[7:] = ["8", "8"]
        job["routeCommand"] = shlex.join(route)
        job["captureHeight"] = capture_height
        job["captureStep"] = capture_step
        print(json.dumps(job, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
