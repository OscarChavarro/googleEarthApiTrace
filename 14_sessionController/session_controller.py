#!/usr/bin/env python3

import os
import argparse
import errno
import fcntl
import queue
import re
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import BinaryIO, Optional


MODULE_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = MODULE_DIR.parent
DETECTOR = PROJECT_ROOT / "12_fileSystemChangesDetector" / "build" / "fileSystemChangesDetector"
CONTROLLER_RUNNER = PROJECT_ROOT / "13_googleEarthController" / "run.sh"
CONTROLLER_DIR = CONTROLLER_RUNNER.parent
GOOGLE_EARTH = Path("/opt/google/earth/pro/google-earth-pro")
GOOGLE_EARTH_DIRECTORY = GOOGLE_EARTH.parent
GOOGLE_EARTH_BINARY = GOOGLE_EARTH_DIRECTORY / "googleearth-bin"
APITRACE = Path("/usr/local/bin/apitrace")
GOOGLE_EARTH_TRACE_PATTERN = "googleearth-bin*trace"
GOOGLE_EARTH_INSTANCE_LOCK = Path.home() / ".googleearth" / "instance-running-lock"
DEFAULT_OUTPUT_DIRECTORY = Path(os.environ.get("PIPELINE_OUTPUT_DIRECTORY", "/media/ramdisk/output"))
DEFAULT_LOG_ROOT = Path(os.environ.get("PIPELINE_LOG_ROOT", "/media/ramdisk/logs"))
DEFAULT_TMP_DIR = Path(os.environ.get("PIPELINE_TMP_DIR", "/media/ramdisk"))
GOOGLE_EARTH_CAPTURE_LOCK = DEFAULT_TMP_DIR / "google-earth-capture.lock"

GOOGLE_EARTH_STARTUP_SECONDS = 10
CONTROLLER_STARTUP_TIMEOUT_SECONDS = 180
POLL_INTERVAL_SECONDS = 0.25
SHUTDOWN_TIMEOUT_SECONDS = 5
GOOGLE_EARTH_FINALIZATION_TIMEOUT_SECONDS = 10
GOOGLE_EARTH_FREE_TIMEOUT_SECONDS = int(os.environ.get("GOOGLE_EARTH_FREE_TIMEOUT_SECONDS", "0"))
GOOGLE_EARTH_FREE_STABLE_SECONDS = 2.0

SUCCESS_PATTERN = re.compile(rb"\[OK\] Finished traversing (\d+) points\.")
ERROR_PATTERNS = (
    re.compile(rb"\[ERROR\][^\r\n]*"),
    re.compile(rb"\[WARN\] Google Earth window not found[^\r\n]*"),
    re.compile(rb"FAILURE: Build failed[^\r\n]*"),
    re.compile(rb"Exception in thread[^\r\n]*"),
)
MILESTONES = (
    b"[OK] Google Earth X11 window found:",
    b"[OK] Google Earth screenshot exported",
    b"[OK] Located first turtle point through AT-SPI:",
    b"[OK] Clicked first turtle point",
    b"[OK] Initial ENTER sent",
)


class SessionFailure(RuntimeError):
    pass


class CaptureLock:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.handle = None

    def acquire(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.handle = self.path.open("w")
        print(f"[SESSION] Waiting for exclusive Google Earth capture lock: {self.path}", flush=True)
        fcntl.flock(self.handle.fileno(), fcntl.LOCK_EX)
        self.handle.seek(0)
        self.handle.truncate()
        self.handle.write(f"pid={os.getpid()}\n")
        self.handle.flush()
        os.fsync(self.handle.fileno())
        print("[SESSION] Exclusive Google Earth capture lock acquired.", flush=True)

    def release(self) -> None:
        if self.handle is None:
            return
        try:
            fcntl.flock(self.handle.fileno(), fcntl.LOCK_UN)
        finally:
            self.handle.close()
            self.handle = None


def google_earth_processes() -> list[tuple[int, str]]:
    matches: list[tuple[int, str]] = []
    own_pid = os.getpid()
    for proc_entry in Path("/proc").iterdir():
        if not proc_entry.name.isdigit():
            continue
        pid = int(proc_entry.name)
        if pid == own_pid:
            continue
        try:
            comm = (proc_entry / "comm").read_text(encoding="utf-8", errors="replace").strip()
            raw_cmdline = (proc_entry / "cmdline").read_bytes()
        except (FileNotFoundError, ProcessLookupError, PermissionError, OSError):
            continue
        cmdline = raw_cmdline.replace(b"\0", b" ").decode("utf-8", errors="replace").strip()
        searchable = f"{comm} {cmdline}"
        if (
            "googleearth-bin" in searchable
            or "google-earth-pro" in searchable
            or "qgoogleearth" in searchable
        ):
            matches.append((pid, searchable))
    return sorted(matches)


class ManagedProcess:
    def __init__(
        self,
        name: str,
        command: list[str],
        cwd: Path,
        stdout,
        stdin=None,
        env: Optional[dict[str, str]] = None,
    ) -> None:
        self.name = name
        self.command = command
        self.cwd = cwd
        self.stdout = stdout
        self.stdin = stdin
        self.env = env
        self.process: Optional[subprocess.Popen] = None
        self.process_group: Optional[int] = None

    def start(self) -> subprocess.Popen:
        print(f"[SESSION] Starting {self.name}: {' '.join(self.command)}", flush=True)
        self.process = subprocess.Popen(
            self.command,
            cwd=self.cwd,
            stdin=self.stdin,
            stdout=self.stdout,
            stderr=subprocess.STDOUT,
            start_new_session=True,
            env=self.env,
        )
        self.process_group = self.process.pid
        return self.process

    def exit_code(self) -> Optional[int]:
        return None if self.process is None else self.process.poll()

    def require_running(self) -> None:
        exit_code = self.exit_code()
        if exit_code is not None:
            raise SessionFailure(f"{self.name} exited unexpectedly with code {exit_code}")

    def terminate_group(self) -> None:
        if self.process_group is None:
            return

        try:
            os.killpg(self.process_group, signal.SIGTERM)
        except ProcessLookupError:
            return

        if self.process is not None:
            try:
                self.process.wait(timeout=SHUTDOWN_TIMEOUT_SECONDS)
            except subprocess.TimeoutExpired:
                pass

        try:
            os.killpg(self.process_group, 0)
        except ProcessLookupError:
            return

        try:
            os.killpg(self.process_group, signal.SIGKILL)
        except ProcessLookupError:
            pass

    def terminate_process(self) -> None:
        if self.process is None or self.process.poll() is not None:
            return
        self.process.terminate()
        try:
            self.process.wait(timeout=SHUTDOWN_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired:
            self.process.kill()


class ControllerOutputReader(threading.Thread):
    def __init__(self, stream: BinaryIO, output_queue: queue.Queue, log_file: BinaryIO) -> None:
        super().__init__(name="controller-output-reader", daemon=True)
        self.stream = stream
        self.output_queue = output_queue
        self.log_file = log_file

    def run(self) -> None:
        try:
            while True:
                chunk = os.read(self.stream.fileno(), 4096)
                if not chunk:
                    break
                self.log_file.write(chunk)
                self.log_file.flush()
                self.output_queue.put(chunk)
        except OSError:
            pass
        finally:
            self.output_queue.put(None)


class SessionController:
    def __init__(self, output_directory: Path, logical_output_directory: Path, log_root: Path) -> None:
        self.output_directory = output_directory
        self.logical_output_directory = logical_output_directory
        self.capture_lock = CaptureLock(GOOGLE_EARTH_CAPTURE_LOCK)
        log_root.mkdir(parents=True, exist_ok=True)
        log_directory = log_root / f"14_sessionController-{os.getpid()}"
        log_directory.mkdir(parents=True, exist_ok=False)
        self.log_directory = log_directory
        self.google_earth_log = (log_directory / "google-earth.log").open("wb")
        self.controller_log = (log_directory / "13_googleEarthController.log").open("wb")

        google_earth_env = os.environ.copy()
        google_earth_env["TRACE_OUTPUT_DIRECTORY"] = str(output_directory)
        google_earth_env["TRACE_MANIFEST_OUTPUT_DIRECTORY"] = str(logical_output_directory)
        # The installed Google Earth wrapper runs `apitrace trace` without an
        # output argument.  Its binary directory may be read-only, so invoke
        # apitrace directly and place the one trace for this capture in its
        # physical pipeline slot.
        google_earth_env["QT_LINUX_ACCESSIBILITY_ALWAYS_ON"] = "1"
        self.google_earth = ManagedProcess(
            "Google Earth",
            [
                str(APITRACE),
                "trace",
                "--output",
                str(output_directory / "googleearth-bin.trace"),
                str(GOOGLE_EARTH_BINARY),
            ],
            PROJECT_ROOT,
            self.google_earth_log,
            subprocess.DEVNULL,
            google_earth_env,
        )
        controller_env = os.environ.copy()
        controller_env["PIPELINE_OUTPUT_DIRECTORY"] = str(output_directory)
        self.controller = ManagedProcess(
            "13_googleEarthController",
            [str(CONTROLLER_RUNNER), "--offline"],
            CONTROLLER_DIR,
            subprocess.PIPE,
            subprocess.DEVNULL,
            controller_env,
        )
        self.controller_output_queue: queue.Queue = queue.Queue()
        self.controller_reader: Optional[ControllerOutputReader] = None
        self.controller_completed_successfully = False

    def run(self) -> int:
        try:
            self.validate_prerequisites()
            print(f"[SESSION] Runtime logs: {self.log_directory}", flush=True)
            self.capture_lock.acquire()
            self.wait_for_google_earth_to_be_free("before startup")
            self.remove_stale_google_earth_instance_lock()
            self.start_google_earth()
            point_count = self.start_and_monitor_controller()
            self.controller_completed_successfully = True
            print(f"[SESSION] SUCCESS: controller traversed {point_count} points.", flush=True)
            return 0
        except SessionFailure as error:
            print(f"[SESSION] FAILURE: {error}", file=sys.stderr, flush=True)
            return 1
        except Exception as error:
            print(
                f"[SESSION] FAILURE: unexpected {type(error).__name__}: {error}",
                file=sys.stderr,
                flush=True,
            )
            return 1
        finally:
            try:
                self.shutdown()
            finally:
                self.capture_lock.release()

    def validate_prerequisites(self) -> None:
        required_executables = (DETECTOR, GOOGLE_EARTH, GOOGLE_EARTH_BINARY, APITRACE, CONTROLLER_RUNNER)
        for executable in required_executables:
            if not executable.is_file():
                raise SessionFailure(f"required executable not found: {executable}")
            if not os.access(executable, os.X_OK):
                raise SessionFailure(f"required file is not executable: {executable}")
        if not self.output_directory.is_dir():
            raise SessionFailure(f"output directory not found: {self.output_directory}")
        if not os.environ.get("DISPLAY"):
            raise SessionFailure("DISPLAY is not set; an X11 desktop session is required")
        if not os.environ.get("DBUS_SESSION_BUS_ADDRESS"):
            raise SessionFailure("DBUS_SESSION_BUS_ADDRESS is not set; AT-SPI requires the desktop D-Bus session")

    def start_google_earth(self) -> None:
        self.remove_google_earth_traces()
        self.google_earth.start()
        print(
            f"[SESSION] Waiting {GOOGLE_EARTH_STARTUP_SECONDS} seconds for Google Earth startup.",
            flush=True,
        )
        self.wait_while_checking_processes(
            GOOGLE_EARTH_STARTUP_SECONDS,
            (self.google_earth,),
        )
        print("[SESSION] Google Earth startup wait completed.", flush=True)

    def remove_google_earth_traces(self) -> None:
        removed_count = 0
        try:
            for trace_path in GOOGLE_EARTH_DIRECTORY.glob(GOOGLE_EARTH_TRACE_PATTERN):
                try:
                    trace_path.unlink()
                    removed_count += 1
                except FileNotFoundError:
                    # A concurrent cleanup may have removed the same trace.
                    continue
        except OSError as error:
            # apitrace in this project honours TRACE_OUTPUT_DIRECTORY, so the
            # executable directory is only a legacy trace location.  Some
            # installations bind it read-only; do not prevent a fresh trace
            # from being written to the per-slot writable destination.
            if error.errno == errno.EROFS and self.output_directory.is_dir():
                print(
                    "[SESSION] Legacy Google Earth trace directory is read-only; "
                    "using TRACE_OUTPUT_DIRECTORY instead.",
                    flush=True,
                )
                return
            raise SessionFailure(f"could not remove old Google Earth trace files: {error}") from error

        if removed_count == 0:
            print(
                "[SESSION] Google Earth trace cleanup skipped: no old trace files found.",
                flush=True,
            )
        else:
            print(
                f"[SESSION] Removed {removed_count} old Google Earth trace file(s) from "
                f"{GOOGLE_EARTH_DIRECTORY}.",
                flush=True,
            )

    def start_and_monitor_controller(self) -> int:
        process = self.controller.start()
        if process.stdout is None:
            raise SessionFailure("controller stdout pipe was not created")

        self.controller_reader = ControllerOutputReader(
            process.stdout,
            self.controller_output_queue,
            self.controller_log,
        )
        self.controller_reader.start()

        overlap = b""
        milestones_reported: set[bytes] = set()
        initial_enter_seen = False
        google_earth_exit_requested = False
        started_at = time.monotonic()

        while True:
            try:
                chunk = self.controller_output_queue.get(timeout=POLL_INTERVAL_SECONDS)
            except queue.Empty:
                chunk = b""

            if chunk is None:
                exit_code = self.controller.exit_code()
                if exit_code is None:
                    continue
                raise SessionFailure(
                    f"13_googleEarthController exited with code {exit_code} before reporting completion"
                )

            if chunk:
                searchable = overlap + chunk
                failure = self.find_failure(searchable)
                if failure is not None:
                    raise SessionFailure(f"13_googleEarthController reported: {failure}")

                success = SUCCESS_PATTERN.search(searchable)
                if success is not None:
                    return int(success.group(1))

                if (
                    b"[STEP] Requesting Google Earth quit through AT-SPI File, then UP, ENTER."
                    in searchable
                    or b"[OK] Google Earth is already closed." in searchable
                ):
                    google_earth_exit_requested = True

                for milestone in MILESTONES:
                    if milestone in searchable and milestone not in milestones_reported:
                        milestones_reported.add(milestone)
                        print(f"[13] {self.extract_message(searchable, milestone)}", flush=True)
                initial_enter_seen = b"[OK] Initial ENTER sent" in milestones_reported
                overlap = searchable[-512:]

            exit_code = self.controller.exit_code()
            if exit_code is not None:
                raise SessionFailure(
                    f"13_googleEarthController exited with code {exit_code} before reporting completion"
                )

            if not google_earth_exit_requested:
                self.google_earth.require_running()

            if (
                not initial_enter_seen
                and time.monotonic() - started_at > CONTROLLER_STARTUP_TIMEOUT_SECONDS
            ):
                raise SessionFailure(
                    "13_googleEarthController did not send the initial ENTER before the startup timeout"
                )

    def find_failure(self, output: bytes) -> Optional[str]:
        for pattern in ERROR_PATTERNS:
            match = pattern.search(output)
            if match is not None:
                return match.group(0).decode("utf-8", errors="replace").strip()
        return None

    def extract_message(self, output: bytes, marker: bytes) -> str:
        start = output.find(marker)
        end_candidates = [position for position in (
            output.find(b"\n", start),
            output.find(b"\r", start),
        ) if position >= 0]
        end = min(end_candidates) if end_candidates else len(output)
        return output[start:end].decode("utf-8", errors="replace").strip()

    def wait_while_checking_processes(
        self,
        seconds: float,
        processes: tuple[ManagedProcess, ...],
    ) -> None:
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            for process in processes:
                process.require_running()
            time.sleep(min(POLL_INTERVAL_SECONDS, max(0, deadline - time.monotonic())))

    def shutdown(self) -> None:
        print("[SESSION] Stopping 13_googleEarthController.", flush=True)
        self.controller.terminate_group()

        if self.google_earth.exit_code() is None:
            if self.controller_completed_successfully:
                print(
                    "[SESSION] Waiting up to 10 seconds for Google Earth/apitrace finalization.",
                    flush=True,
                )
                try:
                    self.google_earth.process.wait(
                        timeout=GOOGLE_EARTH_FINALIZATION_TIMEOUT_SECONDS
                    )
                    print("[SESSION] Google Earth/apitrace finalized naturally.", flush=True)
                except subprocess.TimeoutExpired:
                    print(
                        "[SESSION] Google Earth/apitrace is still running after 10 seconds; "
                        "stopping only its managed PID.",
                        flush=True,
                    )
                    self.google_earth.terminate_process()
            else:
                print(
                    "[SESSION] Google Earth is still running after a failed session; "
                    "stopping only its managed PID.",
                    flush=True,
                )
                self.google_earth.terminate_process()
        else:
            print("[SESSION] Google Earth already exited; no kill is needed.", flush=True)

        self.wait_for_google_earth_to_be_free("after shutdown")

        if self.controller_reader is not None:
            self.controller_reader.join(timeout=1)
        if not self.google_earth_log.closed:
            self.google_earth_log.close()
        if not self.controller_log.closed:
            self.controller_log.close()
        print("[SESSION] All managed processes have been stopped.", flush=True)

    def wait_for_google_earth_to_be_free(self, reason: str) -> None:
        started = time.monotonic()
        stable_since: Optional[float] = None
        last_reported_pids: tuple[int, ...] | None = None
        while True:
            processes = google_earth_processes()
            if not processes:
                if stable_since is None:
                    stable_since = time.monotonic()
                if time.monotonic() - stable_since >= GOOGLE_EARTH_FREE_STABLE_SECONDS:
                    if last_reported_pids:
                        print(f"[SESSION] Google Earth is free {reason}.", flush=True)
                    return
                time.sleep(POLL_INTERVAL_SECONDS)
                continue

            stable_since = None
            pids = tuple(pid for pid, _ in processes)
            if pids != last_reported_pids:
                summary = ", ".join(f"{pid}:{description[:80]}" for pid, description in processes)
                print(
                    f"[SESSION] Waiting for Google Earth to be free {reason}; "
                    f"active process(es): {summary}",
                    flush=True,
                )
                last_reported_pids = pids

            if (
                GOOGLE_EARTH_FREE_TIMEOUT_SECONDS > 0
                and time.monotonic() - started > GOOGLE_EARTH_FREE_TIMEOUT_SECONDS
            ):
                raise SessionFailure(
                    f"Google Earth did not become free {reason} within "
                    f"{GOOGLE_EARTH_FREE_TIMEOUT_SECONDS} seconds"
                )
            time.sleep(1.0)

    def remove_stale_google_earth_instance_lock(self) -> None:
        if not GOOGLE_EARTH_INSTANCE_LOCK.exists():
            return
        if google_earth_processes():
            return
        try:
            GOOGLE_EARTH_INSTANCE_LOCK.unlink()
        except FileNotFoundError:
            return
        except OSError as error:
            raise SessionFailure(
                f"could not remove stale Google Earth instance lock "
                f"{GOOGLE_EARTH_INSTANCE_LOCK}: {error}"
            ) from error
        print(
            f"[SESSION] Removed stale Google Earth instance lock: {GOOGLE_EARTH_INSTANCE_LOCK}",
            flush=True,
        )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run a managed Google Earth capture session.")
    parser.add_argument(
        "--capture-root",
        default=str(DEFAULT_OUTPUT_DIRECTORY),
        help="Directory where captured files are written, defaulting to PIPELINE_OUTPUT_DIRECTORY or /media/ramdisk/output.",
    )
    parser.add_argument(
        "--logical-output-root",
        default=None,
        help="Directory prefix written into tracer manifests. Defaults to --capture-root.",
    )
    parser.add_argument(
        "--log-root",
        default=str(DEFAULT_LOG_ROOT),
        help="Root directory for per-session logs, defaulting to PIPELINE_LOG_ROOT or /media/ramdisk/logs.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    capture_root = Path(args.capture_root).expanduser().resolve()
    logical_output_root = (
        Path(args.logical_output_root).expanduser().resolve()
        if args.logical_output_root is not None
        else capture_root
    )
    session = SessionController(
        capture_root,
        logical_output_root,
        Path(args.log_root).expanduser().resolve(),
    )
    try:
        return session.run()
    except KeyboardInterrupt:
        print("\n[SESSION] Interrupted by user.", file=sys.stderr, flush=True)
        return 130


if __name__ == "__main__":
    sys.exit(main())
