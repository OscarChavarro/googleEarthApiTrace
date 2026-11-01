#!/usr/bin/env python3
"""Fail a stuck capture/processing process after the per-tile deadline.

This is deliberately independent of the controller's subprocess timeout: a
few shell/JVM descendants have kept their parent wait alive past that timeout.
The controller sees the terminated process, records the tile failure, and
continues when invoked with --continue-on-errors.
"""

import json
import os
import signal
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

DEADLINE_SECONDS = int(os.environ.get("PIPELINE_JOB_TIMEOUT_SECONDS", "3600"))
POLL_SECONDS = 60
SLOTS = {
    Path("/media/ramdisk/output"): "PROCESSING",
    Path("/media/ramdisk/output_incoming"): "CAPTURING",
}
LOG = Path("/media/ramdisk/logs/superPipeline/tile-deadline-watchdog.log")


def age_seconds(payload: dict) -> float | None:
    value = payload.get("updatedAt")
    if not isinstance(value, str):
        return None
    try:
        when = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    return (datetime.now(timezone.utc) - when.astimezone(timezone.utc)).total_seconds()


def process_group_for(slot: Path, state: str) -> int | None:
    result = subprocess.run(
        ["ps", "-eo", "pid=,pgid=,args="], capture_output=True, text=True, check=True
    )
    required = "google-earth-runFullProcess.snapshot" if state == "PROCESSING" else "apitrace trace"
    slot_text = str(slot)
    for line in result.stdout.splitlines():
        if required not in line or slot_text not in line:
            continue
        columns = line.split(maxsplit=2)
        if len(columns) == 3:
            return int(columns[1])
    return None


def log(message: str) -> None:
    LOG.parent.mkdir(parents=True, exist_ok=True)
    with LOG.open("a", encoding="utf-8") as stream:
        stream.write(f"{datetime.now(timezone.utc).isoformat()} {message}\n")


def check_once() -> None:
    for slot, watched_state in SLOTS.items():
        descriptor_path = slot / "_pipeline" / "job.json"
        try:
            payload = json.loads(descriptor_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if payload.get("state") != watched_state:
            continue
        age = age_seconds(payload)
        if age is None or age < DEADLINE_SECONDS:
            continue
        pgid = process_group_for(slot, watched_state)
        if pgid is None:
            continue
        try:
            os.killpg(pgid, signal.SIGTERM)
            log(f"terminated pgid={pgid} job={payload.get('jobId')} state={watched_state} age={age:.0f}s")
        except ProcessLookupError:
            continue


def main() -> None:
    log(f"started deadline={DEADLINE_SECONDS}s poll={POLL_SECONDS}s")
    while True:
        check_once()
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    main()
