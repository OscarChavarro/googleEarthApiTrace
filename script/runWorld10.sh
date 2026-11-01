#!/usr/bin/env python3
"""Run the frozen selection of yellow mostly-empty groups from coverage viewer 43."""

import argparse
import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLAN = ROOT / "script" / "world10EmptyGroups.jsonl"


def positive_integer(value):
    number = int(value)
    if number < 1:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--emit-jobs", action="store_true", help="Print the saved selection without running captures")
    parser.add_argument("--start-from", type=positive_integer)
    parser.add_argument("--limit", type=positive_integer)
    args = parser.parse_args()
    if args.emit_jobs:
        jobs = [json.loads(line) for line in PLAN.read_text().splitlines() if line.strip()]
        start = (args.start_from or 1) - 1
        jobs = jobs[start:]
        if args.limit is not None:
            jobs = jobs[:args.limit]
        for job in jobs:
            print(json.dumps(job, separators=(",", ":")))
        return

    command = [str(ROOT / "scripts" / "runSuperPipeline.sh"),
               "--job-source", "world10-empty-groups",
               "--slot-a", "/media/ramdisk/output",
               "--slot-b", "/media/ramdisk/output_incoming",
               "--matrix-dir", "/media/ramdisk/matrix",
               "--log-root", os.environ.get("WORLD10_LOG_ROOT", "/media/ramdisk/logs/world10-empty-groups"),
               "--destination", "/samples/datasets/googleEarth/toplevel",
               "--continue-on-errors"]
    for option, value in (("--start-from", args.start_from), ("--limit", args.limit)):
        if value is not None:
            command.extend([option, str(value)])
    # The controller overlaps capture and processing by default (no --sequential).
    os.execv(command[0], command)


if __name__ == "__main__":
    main()
