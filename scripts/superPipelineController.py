#!/usr/bin/env python3

import argparse
import hashlib
import json
import math
import os
import re
import shlex
import shutil
import signal
import subprocess
import sys
import time
from concurrent.futures import Future, ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent
PATH_PLANNER_DIR = PROJECT_ROOT / "11_pathPlanner"
MY_PLACES_DIR = Path.home() / ".googleearth"
DEFAULT_DESTINATION = Path("/samples/datasets/googleEarth/toplevel")
DEFAULT_SLOT_A = Path("/media/ramdisk/output")
DEFAULT_SLOT_B = Path("/media/ramdisk/output_incoming")
DEFAULT_TMP_DIR = Path(os.environ.get("PIPELINE_TMP_DIR", "/media/ramdisk"))
# A full capture and merge may be slow, but a block which emits no completion
# within an hour is considered unhealthy.  It is recorded and the batch moves
# on when --continue-on-errors is selected.
JOB_TIMEOUT_SECONDS = int(os.environ.get("PIPELINE_JOB_TIMEOUT_SECONDS", "3600"))
DEFAULT_MATRIX_DIR = DEFAULT_TMP_DIR / "matrix"
DEFAULT_LOG_ROOT = Path("/media/ramdisk/logs/superPipeline")
LOCK_PATH = DEFAULT_TMP_DIR / "google-earth-superpipeline.lock"
# World-capture batches always have coverage-43 on the local X11 display.  Keep
# these defaults so a detached controller cannot silently stop refreshing b.png
# merely because its launcher omitted the optional environment variables.
COVERAGE_SNAPSHOT_PATH = os.environ.get("PIPELINE_COVERAGE_SNAPSHOT", "/tmp/b.png")
COVERAGE_WINDOW_ID = os.environ.get("PIPELINE_COVERAGE_WINDOW_ID", "")


class SuperPipelineError(RuntimeError):
    pass


class CaptureSessionError(SuperPipelineError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Coordinate Google Earth batch pipeline jobs.")
    parser.add_argument(
        "--job-source",
        default="spain16",
        choices=("spain16", "spain-madrid17", "spain-madrid18", "spain-madrid19", "world10", "world10-priority-americas", "world10-repair-52n84w", "world10-priority-eurasia", "world10-priority-africa", "world10-missing-eurasia-africa", "world10-named-gaps", "world10-remaining-continental", "world11-south-africa", "world11-priority-red-gaps", "world11-continental-atlantic", "world10-largest-continental-gaps", "world10-priority-continental", "world10-empty-groups"),
    )
    parser.add_argument("--destination", default=str(DEFAULT_DESTINATION))
    parser.add_argument("--slot-a", default=str(DEFAULT_SLOT_A))
    parser.add_argument("--slot-b", default=str(DEFAULT_SLOT_B))
    parser.add_argument("--matrix-dir", default=str(DEFAULT_MATRIX_DIR))
    parser.add_argument("--log-root", default=str(DEFAULT_LOG_ROOT))
    parser.add_argument("--start-from", type=int, default=None)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument(
        "--sequential",
        action="store_true",
        help="Run jobs one at a time through runFullProcess.sh.",
    )
    parser.add_argument(
        "--keep-failed-capture",
        action="store_true",
        help="Stop after a stage-B failure and preserve the output slot for diagnosis.",
    )
    parser.add_argument(
        "--continue-on-errors",
        action="store_true",
        help="Record failed jobs and continue processing the rest of the batch.",
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Run stage B validation without committing the delta into the destination.",
    )
    return parser.parse_args()


def resolved_directory(raw_path: str, label: str) -> Path:
    path = Path(raw_path).expanduser()
    if path.is_symlink():
        raise SuperPipelineError(f"{label} must not be a symlink: {path}")
    resolved = path.resolve()
    if not resolved.is_dir():
        raise SuperPipelineError(f"{label} is not an existing directory: {resolved}")
    if not os.access(resolved, os.R_OK | os.W_OK):
        raise SuperPipelineError(f"{label} is not readable and writable: {resolved}")
    return resolved


def ensured_work_directory(raw_path: str, label: str) -> Path:
    path = Path(raw_path).expanduser()
    if path.is_symlink():
        raise SuperPipelineError(f"{label} must not be a symlink: {path}")
    resolved = path.resolve()
    resolved.mkdir(parents=True, exist_ok=True)
    if not resolved.is_dir():
        raise SuperPipelineError(f"{label} is not an existing directory: {resolved}")
    if not os.access(resolved, os.R_OK | os.W_OK):
        raise SuperPipelineError(f"{label} is not readable and writable: {resolved}")
    return resolved


def acquire_lock() -> object:
    import fcntl

    LOCK_PATH.parent.mkdir(parents=True, exist_ok=True)
    handle = LOCK_PATH.open("w")
    try:
        fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError as exc:
        raise SuperPipelineError(f"another superpipeline controller is already running: {LOCK_PATH}") from exc
    return handle


def load_jobs(job_source: str, start_from: int | None, limit: int | None) -> list[dict]:
    provider_scripts = {
        "spain16": SCRIPT_DIR / "runSpain16.sh",
        "spain-madrid17": SCRIPT_DIR / "runSpainMadrid17.sh",
        "spain-madrid18": SCRIPT_DIR / "runSpainMadrid18.sh",
        "spain-madrid19": SCRIPT_DIR / "runMadridSpain19.sh",
        "world10": SCRIPT_DIR / "runWorld10.sh",
        "world10-empty-groups": PROJECT_ROOT / "script" / "runWorld10.sh",
        "world10-priority-americas": SCRIPT_DIR / "runWorld10PriorityAmericas.sh",
        "world10-repair-52n84w": SCRIPT_DIR / "runWorld10Repair52N84W.sh",
        "world10-priority-eurasia": SCRIPT_DIR / "runWorld10PriorityEurasia.sh",
        "world10-priority-africa": SCRIPT_DIR / "runWorld10PriorityAfrica.sh",
        "world10-missing-eurasia-africa": SCRIPT_DIR / "runWorld10PriorityMissingCoverage.py",
        "world10-named-gaps": SCRIPT_DIR / "runWorld10NamedGaps.py",
        "world10-remaining-continental": SCRIPT_DIR / "runWorld10RemainingContinental.py",
        "world11-south-africa": SCRIPT_DIR / "runWorld11SouthAfrica.py",
        "world11-priority-red-gaps": SCRIPT_DIR / "runWorld11PriorityRedGaps.py",
        "world11-continental-atlantic": SCRIPT_DIR / "runWorld11ContinentalAtlantic.py",
        "world10-largest-continental-gaps": SCRIPT_DIR / "runWorld10LargestContinentalGaps.py",
        "world10-priority-continental": SCRIPT_DIR / "runWorld10PriorityContinental.sh",
    }
    command = [str(provider_scripts[job_source]), "--emit-jobs"]
    if start_from is not None:
        command.extend(["--start-from", str(start_from)])
    if limit is not None:
        command.extend(["--limit", str(limit)])
    result = subprocess.run(
        command,
        cwd=PROJECT_ROOT,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    jobs = []
    for line in result.stdout.splitlines():
        if line.strip():
            jobs.append(json.loads(line))
    if not jobs:
        raise SuperPipelineError(f"{job_source} provider returned no jobs")
    return jobs


def write_coverage_grid_plan(job_source: str, jobs: list[dict]) -> None:
    """Publish the reviewed world10 queue for program 43's planned-grid mode."""
    if job_source not in {"world10-priority-continental", "world10-empty-groups"}:
        return
    # The viewer shows the full reviewed queue even when only one job is executed.
    jobs = load_jobs(job_source, None, None)
    target = PROJECT_ROOT / "scripts" / "world10PlannedGroups.jsonl"
    partial = target.with_name(target.name + ".partial")
    with partial.open("w", encoding="utf-8") as handle:
        for job in jobs:
            handle.write(json.dumps(job, sort_keys=True) + "\n")
    partial.replace(target)


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def atomic_write_json(path: Path, payload: dict) -> None:
    partial = path.with_name(path.name + ".partial")
    partial.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    partial.replace(path)


def append_jsonl(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(payload, sort_keys=True) + "\n")
        handle.flush()
        os.fsync(handle.fileno())


def write_job_snapshot(job: dict, job_log_root: Path, state: str, extra: dict | None = None) -> None:
    payload = dict(job)
    payload["contractVersion"] = 1
    payload["state"] = state
    payload["updatedAt"] = now_iso()
    if extra:
        payload.update(extra)
    atomic_write_json(job_log_root / "job.json", payload)


def write_slot_job(slot: Path, job: dict, state: str, extra: dict | None = None) -> None:
    pipeline_dir = slot / "_pipeline"
    pipeline_dir.mkdir(parents=True, exist_ok=True)
    payload = dict(job)
    payload["contractVersion"] = 1
    payload["physicalSlot"] = str(slot)
    payload["state"] = state
    payload["updatedAt"] = now_iso()
    if extra:
        payload.update(extra)
    atomic_write_json(pipeline_dir / "job.json", payload)


def format_job_progress(job: dict, batch_total: int) -> str:
    catalog_total = job.get("total", batch_total)
    return (
        f"{job['jobId']} "
        f"(tile {job['sequence']}/{catalog_total} "
        f"lat={job['latitude']} lon={job['longitude']})"
    )


def read_slot_job(slot: Path) -> dict | None:
    descriptor = slot / "_pipeline" / "job.json"
    if not descriptor.exists():
        return None
    return json.loads(descriptor.read_text(encoding="utf-8"))


def job_identity(job: dict) -> tuple[str, str]:
    center_quadkey = job.get("centerQuadkey")
    if isinstance(center_quadkey, str) and center_quadkey:
        return ("centerQuadkey", center_quadkey)
    center_quadkey = job.get("centerLevel17Quadkey")
    if isinstance(center_quadkey, str) and center_quadkey:
        return ("centerLevel17Quadkey", center_quadkey)
    route_command = job.get("routeCommand")
    if isinstance(route_command, str) and route_command:
        return ("routeCommand", route_command)
    return ("jobId", str(job.get("jobId")))


def quadkey_from_coordinates(level: int, column: int, south_row: int) -> str:
    quadkey = "0"
    for bit in range(level - 1, -1, -1):
        east = (column >> bit) & 1
        south = (south_row >> bit) & 1
        quadkey += ("0", "1", "3", "2")[(south << 1) | east]
    return quadkey


def job_geographic_extent(job: dict) -> tuple[float, float, float, float] | None:
    try:
        south = float(job["latitude"])
        west = float(job["longitude"])
        latitude_span = float(job["latitudeSpan"])
        longitude_span = float(job["longitudeSpan"])
    except (KeyError, TypeError, ValueError):
        try:
            route = shlex.split(str(job["routeCommand"]))
            south = float(job["latitude"])
            west = float(job["longitude"])
            latitude_span = float(route[-2])
            longitude_span = float(route[-1])
        except (KeyError, TypeError, ValueError, IndexError):
            return None
    if latitude_span <= 0 or longitude_span <= 0:
        return None
    return south, west, latitude_span, longitude_span


def destination_contains_job_area(destination: Path, job: dict, delta: Path | None = None) -> bool:
    completion_level = job.get("completionLevel")
    if completion_level is not None:
        if not isinstance(completion_level, int) or completion_level < 0:
            return False
        level = completion_level
    else:
        quadkey = job.get("centerQuadkey", job.get("centerLevel17Quadkey"))
        if not isinstance(quadkey, str) or not quadkey.startswith("0"):
            return False
        level = len(quadkey) - 1
    extent = job_geographic_extent(job)
    if level < 0 or extent is None:
        return False
    south, west, latitude_span, longitude_span = extent
    matrix_side = 1 << level
    world_span = 360.0 / matrix_side
    first_column = min(matrix_side - 1, max(0, math.floor((west + 180.0) / world_span)))
    last_column = min(
        matrix_side - 1,
        math.floor((west + longitude_span + 180.0 - world_span / 1000.0) / world_span),
    )
    first_row = min(matrix_side - 1, max(0, math.floor((south + 180.0) / world_span)))
    last_row = min(
        matrix_side - 1,
        math.floor((south + latitude_span + 180.0 - world_span / 1000.0) / world_span),
    )
    if first_column > last_column or first_row > last_row:
        return False
    for south_row in range(first_row, last_row + 1):
        for column in range(first_column, last_column + 1):
            candidate = quadkey_from_coordinates(level, column, south_row)
            tile = destination.joinpath(*candidate[1:], f"{candidate}.png")
            if not tile.is_file() and (delta is None or not delta.joinpath(*candidate[1:], f"{candidate}.png").is_file()):
                return False
    return True


def discard_completed_jobs(destination: Path, jobs: list[dict]) -> list[dict]:
    """Keep only jobs whose declared geographic extent is not fully committed.

    Providers without a completion-level contract deliberately remain untouched.  This
    preserves the legacy Spain level-16 provider behaviour while allowing world10 and
    newer geographic providers to resume from the production pyramid rather than from
    an old sequence number.
    """
    pending = []
    completed = 0
    for job in jobs:
        # A human-reviewed repair can deliberately retry an apparently complete
        # parent: the coverage viewer may expose a mottled/corrupt child mosaic.
        if not job.get("force") and destination_contains_job_area(destination, job):
            completed += 1
        else:
            pending.append(job)
    if completed:
        print(
            f"[superPipeline][INFO] Skipped {completed} job(s) already fully present "
            "in the destination.",
            flush=True,
        )
    return pending


def clear_directory_contents(directory: Path) -> None:
    if directory.is_symlink() or not directory.is_dir():
        raise SuperPipelineError(f"refusing to clear non-directory slot: {directory}")
    for child in directory.iterdir():
        if child.is_dir() and not child.is_symlink():
            shutil.rmtree(child)
        else:
            child.unlink()


def record_failure(log_root: Path, job: dict, stage: str, error: BaseException, slot: Path | None = None) -> None:
    payload = {
        "jobId": job.get("jobId"),
        "sequence": job.get("sequence"),
        "latitude": job.get("latitude"),
        "longitude": job.get("longitude"),
        "routeCommand": job.get("routeCommand"),
        "stage": stage,
        "error": str(error),
        "slot": str(slot) if slot is not None else None,
        "createdAt": now_iso(),
    }
    append_jsonl(log_root / "errors.jsonl", payload)


def job_must_commit(job: dict) -> bool:
    """Whether this reviewed job must stop the batch instead of being skipped."""
    return job.get("mustCommit") is True


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def capture_coverage_snapshot() -> None:
    """Atomically refresh an optional image of the live coverage viewer.

    Snapshotting is observational: a completed merge remains committed even when
    a desktop capture is unavailable, and the warning is retained in the
    controller log for an operator to inspect.
    """
    if not COVERAGE_SNAPSHOT_PATH:
        return
    target = Path(COVERAGE_SNAPSHOT_PATH)
    partial = target.with_name(target.stem + ".partial" + target.suffix)
    try:
        window_id = coverage_window_id()
        if not window_id:
            raise SuperPipelineError("Pyramidal Image Coverage window is not available")
        subprocess.run(
            ["import", "-display", os.environ.get("DISPLAY", ":1"), "-window", window_id, str(partial)],
            check=True,
            timeout=30,
            env=os.environ.copy(),
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
        )
        if not partial.is_file() or partial.stat().st_size == 0:
            raise SuperPipelineError(f"coverage snapshot is empty: {partial}")
        partial.replace(target)
        print(f"[superPipeline][INFO] Updated coverage snapshot: {target}", flush=True)
    except (OSError, subprocess.SubprocessError, SuperPipelineError) as exc:
        partial.unlink(missing_ok=True)
        print(f"[superPipeline][WARN] Coverage snapshot was not updated: {exc}", flush=True)


def coverage_window_id() -> str | None:
    """Find 43 by title so restarting it never leaves a stale X11 id behind."""
    try:
        tree = subprocess.run(
            ["xwininfo", "-display", os.environ.get("DISPLAY", ":1"), "-root", "-tree"],
            check=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, timeout=10
        ).stdout
    except (OSError, subprocess.SubprocessError):
        return COVERAGE_WINDOW_ID or None
    match = re.search(r'\s(0x[0-9a-fA-F]+) "Pyramidal Image Coverage"', tree)
    return match.group(1) if match else (COVERAGE_WINDOW_ID or None)


def select_completed_trace(trace_dir: Path) -> Path:
    traces = sorted(trace_dir.glob("googleearth-bin*trace"))
    traces = [path for path in traces if path.is_file() and path.stat().st_size > 0]
    if len(traces) != 1:
        raise SuperPipelineError(f"expected exactly one completed Google Earth trace, found {len(traces)}")
    return traces[0]


def publish_private_trace(slot: Path) -> dict:
    source = select_completed_trace(slot)
    pipeline_dir = slot / "_pipeline"
    pipeline_dir.mkdir(parents=True, exist_ok=True)
    partial = pipeline_dir / "capture.trace.partial"
    private_trace = pipeline_dir / "capture.trace"
    shutil.copy2(source, partial)
    os.sync()
    partial.replace(private_trace)
    os.sync()
    source_sha = sha256_file(source)
    private_sha = sha256_file(private_trace)
    if source_sha != private_sha:
        raise SuperPipelineError(f"private trace SHA-256 mismatch after copying {source}")
    (pipeline_dir / "capture.trace.sha256").write_text(
        f"{private_sha}  {private_trace}\n",
        encoding="utf-8",
    )
    source.unlink()
    return {
        "captureTracePath": str(private_trace),
        "captureTraceBytes": private_trace.stat().st_size,
        "captureTraceSha256": private_sha,
    }


def validate_handoff_paths(output_slot: Path, incoming_slot: Path) -> None:
    if output_slot == incoming_slot:
        raise SuperPipelineError(f"slot-a and slot-b must be different directories: {output_slot}")
    if output_slot.parent != incoming_slot.parent:
        raise SuperPipelineError(
            "the current handoff fallback requires both slots to share the same parent directory"
        )
    if output_slot.stat().st_dev != incoming_slot.stat().st_dev:
        raise SuperPipelineError("the current handoff fallback cannot cross filesystems")


def exchange_slots_sequential(output_slot: Path, incoming_slot: Path, job: dict, job_log_root: Path) -> None:
    validate_handoff_paths(output_slot, incoming_slot)
    retired = output_slot.parent / f"{output_slot.name}.retired.{job['jobId']}"
    journal = job_log_root / "handoff.json"
    if retired.exists():
        raise SuperPipelineError(f"handoff retired path already exists: {retired}")
    journal_payload = {
        "from": str(incoming_slot),
        "to": str(output_slot),
        "readyJobId": job["jobId"],
        "retired": str(retired),
        "phase": "STARTED",
        "updatedAt": now_iso(),
    }
    atomic_write_json(journal, journal_payload)
    journal_payload["phase"] = "OUTPUT_RETIRED"
    journal_payload["updatedAt"] = now_iso()
    output_slot.rename(retired)
    atomic_write_json(journal, journal_payload)
    journal_payload["phase"] = "INCOMING_PROMOTED"
    journal_payload["updatedAt"] = now_iso()
    incoming_slot.rename(output_slot)
    atomic_write_json(journal, journal_payload)
    journal_payload["phase"] = "RETIRED_MOVED_TO_INCOMING"
    journal_payload["updatedAt"] = now_iso()
    retired.rename(incoming_slot)
    atomic_write_json(journal, journal_payload)
    descriptor = json.loads((output_slot / "_pipeline" / "job.json").read_text(encoding="utf-8"))
    if descriptor.get("jobId") != job["jobId"] or descriptor.get("state") != "CAPTURE_READY":
        raise SuperPipelineError(f"handoff validation failed for {job['jobId']}")
    journal_payload["phase"] = "COMPLETED"
    journal_payload["updatedAt"] = now_iso()
    atomic_write_json(journal, journal_payload)


def begin_job_timeout(job: dict) -> None:
    job.setdefault("_deadline_monotonic", time.monotonic() + JOB_TIMEOUT_SECONDS)


def remaining_job_timeout(job: dict) -> float:
    begin_job_timeout(job)
    return max(0.0, job["_deadline_monotonic"] - time.monotonic())


def run_command(
    command: list[str],
    cwd: Path,
    log_file: Path,
    env: dict[str, str] | None = None,
    timeout_seconds: float | None = None,
) -> None:
    with log_file.open("ab") as log:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            stdout=log,
            stderr=subprocess.STDOUT,
            env=env,
            start_new_session=True,
        )
        try:
            status = process.wait(timeout=timeout_seconds)
        except subprocess.TimeoutExpired as exc:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            raise SuperPipelineError(
                f"command exceeded the {JOB_TIMEOUT_SECONDS // 60}-minute tile deadline: {' '.join(command)}"
            ) from exc
    if status != 0:
        raise SuperPipelineError(f"command failed with status {status}: {' '.join(command)}")


def prepare_myplaces() -> None:
    myplaces_backup = MY_PLACES_DIR / "myplaces.kml.bak"
    myplaces = MY_PLACES_DIR / "myplaces.kml"
    if not myplaces_backup.is_file():
        raise SuperPipelineError(f"missing Google Earth places backup: {myplaces_backup}")
    myplaces.write_bytes(myplaces_backup.read_bytes())


def run_stage_a(
    job: dict,
    incoming_slot: Path,
    log_root: Path,
    logical_output_slot: Path,
) -> dict:
    begin_job_timeout(job)
    job_log_root = log_root / job["jobId"]
    job_log_root.mkdir(parents=True, exist_ok=True)
    write_job_snapshot(job, job_log_root, "CAPTURING")
    clear_directory_contents(incoming_slot)
    write_slot_job(incoming_slot, job, "CAPTURING")

    prepare_myplaces()
    route_parts = shlex.split(job["routeCommand"])
    run_command(
        route_parts,
        PATH_PLANNER_DIR,
        job_log_root / "11_pathPlanner.log",
        timeout_seconds=remaining_job_timeout(job),
    )
    try:
        run_command(
            [
                str(PROJECT_ROOT / "14_sessionController" / "run.sh"),
                "--capture-root",
                str(incoming_slot),
                "--logical-output-root",
                str(logical_output_slot),
                "--log-root",
                str(log_root),
            ],
            PROJECT_ROOT,
            job_log_root / "14_sessionController.log",
            timeout_seconds=remaining_job_timeout(job),
        )
    except SuperPipelineError as exc:
        raise CaptureSessionError(str(exc)) from exc
    trace_metadata = publish_private_trace(incoming_slot)
    write_slot_job(incoming_slot, job, "CAPTURE_READY", trace_metadata)
    write_job_snapshot(job, job_log_root, "CAPTURE_READY", trace_metadata)
    return trace_metadata


def run_stage_a_with_retries(
    job: dict,
    incoming_slot: Path,
    log_root: Path,
    logical_output_slot: Path,
    # A closed Google Earth session leaves an incomplete trace.  Retrying the
    # same geographic block wastes a slot and can duplicate already-covered
    # imagery, so record that failure and let --continue-on-errors advance.
    attempts: int = 1,
) -> dict:
    for attempt in range(1, attempts + 1):
        try:
            return run_stage_a(job, incoming_slot, log_root, logical_output_slot)
        except CaptureSessionError:
            if attempt >= attempts:
                raise
            print(
                f"[superPipeline][WARN] Capture session failed for {job['jobId']} "
                f"on attempt {attempt}/{attempts}; retrying.",
                flush=True,
            )
            time.sleep(5)
    raise AssertionError("unreachable capture retry state")


def run_stage_b(
    job: dict,
    output_slot: Path,
    matrix_dir: Path,
    destination: Path,
    log_root: Path,
    dry_run: bool,
) -> None:
    begin_job_timeout(job)
    if remaining_job_timeout(job) <= 0:
        raise SuperPipelineError(f"tile exceeded the {JOB_TIMEOUT_SECONDS // 60}-minute deadline before stage B")
    job_log_root = log_root / job["jobId"]
    job_log_root.mkdir(parents=True, exist_ok=True)
    descriptor = read_slot_job(output_slot)
    if descriptor is None:
        raise SuperPipelineError(f"missing slot descriptor before stage B: {output_slot}")
    if descriptor.get("jobId") != job["jobId"] or descriptor.get("state") != "CAPTURE_READY":
        raise SuperPipelineError(
            f"slot {output_slot} is not ready for {job['jobId']}: "
            f"jobId={descriptor.get('jobId')} state={descriptor.get('state')}"
        )
    private_trace = output_slot / "_pipeline" / "capture.trace"
    if not private_trace.is_file() or private_trace.stat().st_size <= 0:
        raise SuperPipelineError(f"missing private trace before stage B: {private_trace}")
    trace_metadata = {
        "captureTracePath": str(private_trace),
        "captureTraceBytes": descriptor.get("captureTraceBytes", private_trace.stat().st_size),
        "captureTraceSha256": descriptor.get("captureTraceSha256"),
    }
    write_job_snapshot(job, job_log_root, "PROCESSING")
    write_slot_job(output_slot, job, "PROCESSING", trace_metadata)
    env = os.environ.copy()
    env["PIPELINE_OUTPUT_DIRECTORY"] = str(output_slot)
    env["PIPELINE_TRACE_DUMP_DIR"] = str(output_slot)
    env["PIPELINE_MATRIX_DIR"] = str(matrix_dir)
    env["PIPELINE_LOG_ROOT"] = str(log_root)
    env["PIPELINE_SESSION_LOG"] = str(job_log_root / "runFullProcess.session.log")
    env["PIPELINE_TMP_DIR"] = str(DEFAULT_TMP_DIR)
    env["TMPDIR"] = str(DEFAULT_TMP_DIR)
    # OCR metadata is not consumed by tile extraction or merging. PaddleOCR is
    # currently unsafe in this long-running embedded-JVM workload (SIGSEGV 139),
    # so keep it out of the production batch pipeline.
    env["LOCAL_OCR_ENABLED"] = "false"
    command = [
        str(SCRIPT_DIR / "runFullProcess.sh"),
        "--destination",
        str(destination),
        "--reuse-capture",
        "--capture-root",
        str(output_slot),
        "--trace-dump-dir",
        str(output_slot),
        "--matrix-dir",
        str(matrix_dir),
        "--session-log",
        str(job_log_root / "runFullProcess.session.log"),
        "--route-command",
        job["routeCommand"],
    ]
    merge_level_only = job.get("mergeLevelOnly")
    if merge_level_only is not None:
        if not isinstance(merge_level_only, int) or merge_level_only < 0:
            raise SuperPipelineError(
                f"invalid mergeLevelOnly for {job['jobId']}: {merge_level_only!r}"
            )
        command.extend(["--merge-level-only", str(merge_level_only)])
    if dry_run:
        command.append("--dry-run")
    run_command(
        command,
        PROJECT_ROOT,
        job_log_root / "runFullProcess.console.log",
        env,
        remaining_job_timeout(job),
    )
    if merge_level_only is not None and not dry_run and not destination_contains_job_area(destination, job):
        print(
            f"[superPipeline][INFO] Merge validated for {job['jobId']}; "
            "rectangular coverage remains partial (sea gaps do not block commit).",
            flush=True,
        )
    terminal_state = "DRY_RUN_COMPLETED" if dry_run else "COMMITTED"
    write_slot_job(output_slot, job, terminal_state, trace_metadata)
    write_job_snapshot(job, job_log_root, terminal_state)
    if not dry_run:
        capture_coverage_snapshot()


def run_sequential_job(
    job: dict,
    output_slot: Path,
    incoming_slot: Path,
    matrix_dir: Path,
    destination: Path,
    log_root: Path,
    dry_run: bool,
) -> None:
    run_stage_a_with_retries(job, incoming_slot, log_root, output_slot)
    exchange_slots_sequential(output_slot, incoming_slot, job, log_root / job["jobId"])
    run_stage_b(job, output_slot, matrix_dir, destination, log_root, dry_run)


def validate_initial_slots(slot_a: Path, slot_b: Path) -> None:
    validate_handoff_paths(slot_a, slot_b)
    for slot in (slot_a, slot_b):
        descriptor = read_slot_job(slot)
        if descriptor is None:
            continue
        state = descriptor.get("state")
        if state in {"COMMITTED", "DRY_RUN_COMPLETED", "CAPTURE_FAILED", "PROCESSING_FAILED", "EMPTY"}:
            continue
        raise SuperPipelineError(
            f"slot {slot} contains non-terminal job {descriptor.get('jobId')} in state {state}; "
            "recover it explicitly before starting new work"
        )


def reconcile_initial_slots(
    jobs: list[dict],
    job_source: str,
    slot_a: Path,
    slot_b: Path,
    matrix_dir: Path,
    destination: Path,
    log_root: Path,
    dry_run: bool,
) -> list[dict]:
    terminal_states = {
        "COMMITTED",
        "DRY_RUN_COMPLETED",
        "CAPTURE_FAILED",
        "PROCESSING_FAILED",
        "EMPTY",
    }
    pending_by_identity = {job_identity(job): job for job in jobs}
    grouped_slots: dict[tuple[str, str], list[tuple[Path, dict]]] = {}

    for slot in (slot_a, slot_b):
        descriptor = read_slot_job(slot)
        if descriptor is None or descriptor.get("state") in terminal_states:
            continue
        job_id = descriptor.get("jobId")
        if not isinstance(job_id, str) or not job_id.startswith(f"{job_source}-"):
            raise SuperPipelineError(
                f"slot {slot} contains non-terminal job {job_id} from another job source"
            )
        grouped_slots.setdefault(job_identity(descriptor), []).append((slot, descriptor))

    recovered_identities: set[tuple[str, str]] = set()
    for identity, slot_records in grouped_slots.items():
        pending_job = pending_by_identity.get(identity)
        if pending_job is None:
            if not all(
                destination_contains_job_area(destination, descriptor)
                for _, descriptor in slot_records
            ):
                slot, descriptor = slot_records[0]
                raise SuperPipelineError(
                    f"slot {slot} contains non-terminal job {descriptor.get('jobId')} "
                    "which is outside the selected pending jobs and is not present in the destination"
                )
            for slot, descriptor in slot_records:
                write_slot_job(
                    slot,
                    descriptor,
                    "COMMITTED",
                    {"recoveryNote": "every tile in the job extent is already present in destination"},
                )
            print(
                f"[superPipeline][INFO] Reconciled {slot_records[0][1].get('jobId')}: "
                "its complete geographic extent is already committed.",
                flush=True,
            )
            continue

        recoverable = []
        for slot, descriptor in slot_records:
            private_trace = slot / "_pipeline" / "capture.trace"
            if (
                descriptor.get("state") in {"CAPTURE_READY", "PROCESSING"}
                and private_trace.is_file()
                and private_trace.stat().st_size > 0
            ):
                recoverable.append((slot, descriptor))

        if recoverable:
            recovery_slot, descriptor = recoverable[0]
            # Stage A traces paths using slot_a as their logical root even
            # while their bytes are being captured in slot_b.  A recovered
            # CAPTURE_READY item still in slot_b must therefore be handed off
            # before stage B; processing it in place makes module 22 look for
            # frames under slot_b and produces an empty frame set.
            if recovery_slot == slot_b:
                exchange_slots_sequential(
                    slot_a,
                    slot_b,
                    pending_job,
                    log_root / pending_job["jobId"],
                )
                recovery_slot = slot_a
                descriptor = read_slot_job(recovery_slot) or descriptor
            print(
                f"[superPipeline][INFO] Recovering completed capture for "
                f"{pending_job['jobId']} from {recovery_slot}.",
                flush=True,
            )
            if descriptor.get("state") != "CAPTURE_READY":
                write_slot_job(recovery_slot, pending_job, "CAPTURE_READY")
            run_stage_b(
                pending_job,
                recovery_slot,
                matrix_dir,
                destination,
                log_root,
                dry_run,
            )
            recovered_identities.add(identity)
            for slot, duplicate in slot_records:
                if slot != recovery_slot:
                    write_slot_job(
                        slot,
                        duplicate,
                        "EMPTY",
                        {"recoveryNote": f"duplicate of recovered slot {recovery_slot}"},
                    )
            continue

        error = "non-terminal slot has no reusable private capture trace"
        for slot, descriptor in slot_records:
            failed_state = (
                "CAPTURE_FAILED"
                if descriptor.get("state") in {"CAPTURING", "CAPTURE_READY"}
                else "PROCESSING_FAILED"
            )
            write_slot_job(slot, descriptor, failed_state, {"error": error})
        print(
            f"[superPipeline][WARN] {pending_job['jobId']} cannot be recovered from its "
            "stale slot and will be captured again.",
            flush=True,
        )

    return [job for job in jobs if job_identity(job) not in recovered_identities]


def run_overlapped_jobs(
    jobs: list[dict],
    output_slot: Path,
    incoming_slot: Path,
    matrix_dir: Path,
    destination: Path,
    log_root: Path,
    keep_failed_capture: bool,
    continue_on_errors: bool,
    dry_run: bool,
) -> None:
    validate_initial_slots(output_slot, incoming_slot)
    ready_job: dict | None = None
    batch_total = len(jobs)

    for index, job in enumerate(jobs):
        if ready_job is None:
            print(
                f"[superPipeline][INFO] Stage A starting "
                f"{format_job_progress(job, batch_total)}.",
                flush=True,
            )
            try:
                run_stage_a_with_retries(job, incoming_slot, log_root, output_slot)
            except Exception as exc:
                record_failure(log_root, job, "A", exc, incoming_slot)
                write_slot_job(incoming_slot, job, "CAPTURE_FAILED", {"error": str(exc)})
                if job_must_commit(job) or not continue_on_errors:
                    raise
                print(
                    f"[superPipeline][WARN] Stage A failed for {job['jobId']}; "
                    "failure was recorded and the next tile will be attempted.",
                    flush=True,
                )
                continue
            exchange_slots_sequential(output_slot, incoming_slot, job, log_root / job["jobId"])
            ready_job = job
            continue

        print(
            f"[superPipeline][INFO] Stage B starting "
            f"{format_job_progress(ready_job, batch_total)} while "
            f"stage A captures {format_job_progress(job, batch_total)}.",
            flush=True,
        )
        with ThreadPoolExecutor(max_workers=2, thread_name_prefix="superpipeline") as executor:
            stage_b_future: Future = executor.submit(
                run_stage_b,
                ready_job,
                output_slot,
                matrix_dir,
                destination,
                log_root,
                dry_run,
            )
            stage_a_future: Future = executor.submit(
                run_stage_a_with_retries,
                job,
                incoming_slot,
                log_root,
                output_slot,
            )
            stage_b_error = None
            stage_a_error = None
            try:
                stage_b_future.result()
            except Exception as exc:
                stage_b_error = exc
                record_failure(log_root, ready_job, "B", exc, output_slot)
                write_slot_job(output_slot, ready_job, "PROCESSING_FAILED", {"error": str(exc)})
            try:
                stage_a_future.result()
            except Exception as exc:
                stage_a_error = exc
                record_failure(log_root, job, "A", exc, incoming_slot)
                write_slot_job(incoming_slot, job, "CAPTURE_FAILED", {"error": str(exc)})

        if stage_b_error is not None:
            if job_must_commit(ready_job) or keep_failed_capture:
                raise SuperPipelineError(
                    f"stage B failed for {ready_job['jobId']}; preserving {output_slot}"
                ) from stage_b_error
            print(
                f"[superPipeline][WARN] Stage B failed for {ready_job['jobId']}; "
                "failure was recorded and the slot will be recycled.",
                flush=True,
            )
        if stage_a_error is not None:
            if job_must_commit(job) or not continue_on_errors:
                raise SuperPipelineError(f"stage A failed for {job['jobId']}") from stage_a_error
            print(
                f"[superPipeline][WARN] Stage A failed for {job['jobId']}; "
                "failure was recorded and the next tile will be attempted.",
                flush=True,
            )
            # Stage B has finished (successfully or otherwise), but there is no
            # valid incoming capture to hand off. The next iteration starts a
            # fresh stage A and then resumes the normal A/B overlap.
            ready_job = None
            continue

        exchange_slots_sequential(output_slot, incoming_slot, job, log_root / job["jobId"])
        ready_job = job

    if ready_job is not None:
        print(
            f"[superPipeline][INFO] Draining final stage B for "
            f"{format_job_progress(ready_job, batch_total)}.",
            flush=True,
        )
        try:
            run_stage_b(ready_job, output_slot, matrix_dir, destination, log_root, dry_run)
        except Exception as exc:
            record_failure(log_root, ready_job, "B", exc, output_slot)
            write_slot_job(output_slot, ready_job, "PROCESSING_FAILED", {"error": str(exc)})
            if job_must_commit(ready_job) or keep_failed_capture or not continue_on_errors:
                raise
            print(
                f"[superPipeline][WARN] Stage B failed for {ready_job['jobId']}; "
                "failure was recorded after draining the batch.",
                flush=True,
            )


def main() -> int:
    args = parse_args()
    lock_handle = None
    try:
        lock_handle = acquire_lock()
        destination = resolved_directory(args.destination, "destination")
        slot_a = resolved_directory(args.slot_a, "slot-a")
        slot_b = resolved_directory(args.slot_b, "slot-b")
        matrix_dir = ensured_work_directory(args.matrix_dir, "matrix-dir")
        log_root = ensured_work_directory(args.log_root, "log-root")

        jobs = load_jobs(args.job_source, args.start_from, args.limit)
        print(f"[superPipeline][INFO] Loaded {len(jobs)} {args.job_source} job(s).", flush=True)
        jobs = discard_completed_jobs(destination, jobs)
        write_coverage_grid_plan(args.job_source, jobs)
        if not jobs:
            print("[superPipeline][INFO] No pending jobs remain after destination inventory.", flush=True)
            return 0
        jobs = reconcile_initial_slots(
            jobs,
            args.job_source,
            slot_a,
            slot_b,
            matrix_dir,
            destination,
            log_root,
            args.dry_run,
        )
        if not jobs:
            print("[superPipeline][INFO] No pending jobs remain after slot recovery.", flush=True)
            return 0
        if args.sequential:
            validate_initial_slots(slot_a, slot_b)
            for job in jobs:
                print(
                    f"[superPipeline][INFO] Starting {job['jobId']} "
                    f"({job['sequence']}/{job['total']}).",
                    flush=True,
                )
                try:
                    run_sequential_job(job, slot_a, slot_b, matrix_dir, destination, log_root, args.dry_run)
                except Exception as exc:
                    record_failure(log_root, job, "SEQUENTIAL", exc)
                    for slot in (slot_a, slot_b):
                        descriptor = read_slot_job(slot)
                        if descriptor is None or descriptor.get("jobId") != job["jobId"]:
                            continue
                        state = descriptor.get("state")
                        failed_state = (
                            "CAPTURE_FAILED"
                            if state in {"CAPTURING", "CAPTURE_READY"}
                            else "PROCESSING_FAILED"
                        )
                        write_slot_job(slot, job, failed_state, {"error": str(exc)})
                    if job_must_commit(job) or not args.continue_on_errors:
                        raise
                    print(
                        f"[superPipeline][WARN] Sequential job {job['jobId']} failed; "
                        "failure was recorded and the next tile will be attempted.",
                        flush=True,
                    )
                    continue
                print(f"[superPipeline][INFO] Finished {job['jobId']}.", flush=True)
        else:
            run_overlapped_jobs(
                jobs,
                slot_a,
                slot_b,
                matrix_dir,
                destination,
                log_root,
                args.keep_failed_capture,
                args.continue_on_errors,
                args.dry_run,
            )
        return 0
    except SuperPipelineError as exc:
        print(f"[superPipeline][ERROR] {exc}", file=sys.stderr, flush=True)
        return 1
    finally:
        if lock_handle is not None:
            lock_handle.close()


if __name__ == "__main__":
    sys.exit(main())
