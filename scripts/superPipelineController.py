#!/usr/bin/env python3

import argparse
import hashlib
import json
import os
import shlex
import shutil
import subprocess
import sys
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
DEFAULT_MATRIX_DIR = DEFAULT_TMP_DIR / "matrix"
DEFAULT_LOG_ROOT = Path("/media/ramdisk/logs/superPipeline")
LOCK_PATH = DEFAULT_TMP_DIR / "google-earth-superpipeline.lock"


class SuperPipelineError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Coordinate Google Earth batch pipeline jobs.")
    parser.add_argument("--job-source", default="spain16", choices=("spain16", "world10"))
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
        "world10": SCRIPT_DIR / "runWorld10.sh",
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
    return (
        f"{job['jobId']} "
        f"(tile {job['sequence']}/{batch_total} "
        f"lat={job['latitude']} lon={job['longitude']})"
    )


def read_slot_job(slot: Path) -> dict | None:
    descriptor = slot / "_pipeline" / "job.json"
    if not descriptor.exists():
        return None
    return json.loads(descriptor.read_text(encoding="utf-8"))


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


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def select_completed_trace() -> Path:
    trace_dir = Path("/opt/google/earth/pro")
    traces = sorted(trace_dir.glob("googleearth-bin*trace"))
    traces = [path for path in traces if path.is_file() and path.stat().st_size > 0]
    if len(traces) != 1:
        raise SuperPipelineError(f"expected exactly one completed Google Earth trace, found {len(traces)}")
    return traces[0]


def publish_private_trace(slot: Path) -> dict:
    source = select_completed_trace()
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


def run_command(command: list[str], cwd: Path, log_file: Path, env: dict[str, str] | None = None) -> None:
    with log_file.open("ab") as log:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            env=env,
        )
        assert process.stdout is not None
        for chunk in iter(lambda: process.stdout.read(4096), b""):
            log.write(chunk)
            log.flush()
            sys.stdout.buffer.write(chunk)
            sys.stdout.buffer.flush()
        status = process.wait()
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
    job_log_root = log_root / job["jobId"]
    job_log_root.mkdir(parents=True, exist_ok=True)
    write_job_snapshot(job, job_log_root, "CAPTURING")
    clear_directory_contents(incoming_slot)
    write_slot_job(incoming_slot, job, "CAPTURING")

    prepare_myplaces()
    route_parts = shlex.split(job["routeCommand"])
    run_command(route_parts, PATH_PLANNER_DIR, job_log_root / "11_pathPlanner.log")
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
    )
    trace_metadata = publish_private_trace(incoming_slot)
    write_slot_job(incoming_slot, job, "CAPTURE_READY", trace_metadata)
    write_job_snapshot(job, job_log_root, "CAPTURE_READY", trace_metadata)
    return trace_metadata


def run_stage_b(
    job: dict,
    output_slot: Path,
    matrix_dir: Path,
    destination: Path,
    log_root: Path,
    dry_run: bool,
) -> None:
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
    if dry_run:
        command.append("--dry-run")
    run_command(
        command,
        PROJECT_ROOT,
        job_log_root / "runFullProcess.console.log",
        env,
    )
    terminal_state = "DRY_RUN_COMPLETED" if dry_run else "COMMITTED"
    write_slot_job(output_slot, job, terminal_state, trace_metadata)
    write_job_snapshot(job, job_log_root, terminal_state)


def run_sequential_job(
    job: dict,
    output_slot: Path,
    incoming_slot: Path,
    matrix_dir: Path,
    destination: Path,
    log_root: Path,
    dry_run: bool,
) -> None:
    run_stage_a(job, incoming_slot, log_root, output_slot)
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
                run_stage_a(job, incoming_slot, log_root, output_slot)
            except Exception as exc:
                record_failure(log_root, job, "A", exc, incoming_slot)
                write_slot_job(incoming_slot, job, "CAPTURE_FAILED", {"error": str(exc)})
                if not continue_on_errors:
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
                run_stage_a,
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
            if keep_failed_capture:
                raise SuperPipelineError(
                    f"stage B failed for {ready_job['jobId']}; preserving {output_slot}"
                ) from stage_b_error
            print(
                f"[superPipeline][WARN] Stage B failed for {ready_job['jobId']}; "
                "failure was recorded and the slot will be recycled.",
                flush=True,
            )
        if stage_a_error is not None:
            if not continue_on_errors:
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
            if keep_failed_capture or not continue_on_errors:
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
        if args.sequential:
            validate_initial_slots(slot_a, slot_b)
            for job in jobs:
                print(
                    f"[superPipeline][INFO] Starting {job['jobId']} "
                    f"({job['sequence']}/{job['total']}).",
                    flush=True,
                )
                run_sequential_job(job, slot_a, slot_b, matrix_dir, destination, log_root, args.dry_run)
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
