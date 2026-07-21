#!/usr/bin/env python3

import os
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
GOOGLE_EARTH_TRACE_PATTERN = "googleearth-bin*trace"
OUTPUT_DIRECTORY = Path("/media/ramdisk/output")

GOOGLE_EARTH_STARTUP_SECONDS = 10
CONTROLLER_STARTUP_TIMEOUT_SECONDS = 180
POLL_INTERVAL_SECONDS = 0.25
SHUTDOWN_TIMEOUT_SECONDS = 5

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


class ManagedProcess:
    def __init__(
        self,
        name: str,
        command: list[str],
        cwd: Path,
        stdout,
        stdin=None,
    ) -> None:
        self.name = name
        self.command = command
        self.cwd = cwd
        self.stdout = stdout
        self.stdin = stdin
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
    def __init__(self) -> None:
        log_directory = Path("/tmp") / f"14_sessionController-{os.getpid()}"
        log_directory.mkdir(parents=True, exist_ok=False)
        self.log_directory = log_directory
        self.detector_log = (log_directory / "12_fileSystemChangesDetector.log").open("wb")
        self.google_earth_log = (log_directory / "google-earth.log").open("wb")
        self.controller_log = (log_directory / "13_googleEarthController.log").open("wb")

        self.detector = ManagedProcess(
            "12_fileSystemChangesDetector",
            [str(DETECTOR), str(OUTPUT_DIRECTORY)],
            DETECTOR.parent,
            self.detector_log,
            subprocess.PIPE,
        )
        self.google_earth = ManagedProcess(
            "Google Earth",
            [str(GOOGLE_EARTH)],
            PROJECT_ROOT,
            self.google_earth_log,
            subprocess.DEVNULL,
        )
        self.controller = ManagedProcess(
            "13_googleEarthController",
            [str(CONTROLLER_RUNNER), "--offline"],
            CONTROLLER_DIR,
            subprocess.PIPE,
            subprocess.DEVNULL,
        )
        self.controller_output_queue: queue.Queue = queue.Queue()
        self.controller_reader: Optional[ControllerOutputReader] = None

    def run(self) -> int:
        try:
            self.validate_prerequisites()
            print(f"[SESSION] Runtime logs: {self.log_directory}", flush=True)
            self.start_detector()
            self.start_google_earth()
            point_count = self.start_and_monitor_controller()
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
            self.shutdown()

    def validate_prerequisites(self) -> None:
        required_executables = (DETECTOR, GOOGLE_EARTH, CONTROLLER_RUNNER)
        for executable in required_executables:
            if not executable.is_file():
                raise SessionFailure(f"required executable not found: {executable}")
            if not os.access(executable, os.X_OK):
                raise SessionFailure(f"required file is not executable: {executable}")
        if not OUTPUT_DIRECTORY.is_dir():
            raise SessionFailure(f"output directory not found: {OUTPUT_DIRECTORY}")
        if not os.environ.get("DISPLAY"):
            raise SessionFailure("DISPLAY is not set; an X11 desktop session is required")
        if not os.environ.get("DBUS_SESSION_BUS_ADDRESS"):
            raise SessionFailure("DBUS_SESSION_BUS_ADDRESS is not set; AT-SPI requires the desktop D-Bus session")

    def start_detector(self) -> None:
        self.detector.start()
        self.wait_while_checking_processes(1, (self.detector,))
        print("[SESSION] 12_fileSystemChangesDetector is running.", flush=True)

    def start_google_earth(self) -> None:
        self.remove_google_earth_traces()
        self.google_earth.start()
        print(
            f"[SESSION] Waiting {GOOGLE_EARTH_STARTUP_SECONDS} seconds for Google Earth startup.",
            flush=True,
        )
        self.wait_while_checking_processes(
            GOOGLE_EARTH_STARTUP_SECONDS,
            (self.detector, self.google_earth),
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
        started_at = time.monotonic()

        while True:
            self.detector.require_running()
            self.google_earth.require_running()

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

        print("[SESSION] Stopping 12_fileSystemChangesDetector.", flush=True)
        detector_process = self.detector.process
        if detector_process is not None and detector_process.poll() is None and detector_process.stdin:
            try:
                detector_process.stdin.write(b"exit\n")
                detector_process.stdin.flush()
                detector_process.wait(timeout=SHUTDOWN_TIMEOUT_SECONDS)
            except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
                pass
        self.detector.terminate_group()

        print("[SESSION] Stopping Google Earth.", flush=True)
        self.google_earth.terminate_group()

        if self.controller_reader is not None:
            self.controller_reader.join(timeout=1)
        if not self.detector_log.closed:
            self.detector_log.close()
        if not self.google_earth_log.closed:
            self.google_earth_log.close()
        if not self.controller_log.closed:
            self.controller_log.close()
        print("[SESSION] All managed processes have been stopped.", flush=True)


def main() -> int:
    session = SessionController()
    try:
        return session.run()
    except KeyboardInterrupt:
        print("\n[SESSION] Interrupted by user.", file=sys.stderr, flush=True)
        return 130


if __name__ == "__main__":
    sys.exit(main())
