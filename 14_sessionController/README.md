# 14_sessionController

`14_sessionController` orchestrates a complete unattended Google Earth tracing session.
It starts and monitors:

1. `12_fileSystemChangesDetector`.
2. Google Earth Pro.
3. `13_googleEarthController --offline`.

The controller waits ten seconds after launching Google Earth before starting module 13.
Immediately before launching Google Earth, it removes files matching
`/opt/google/earth/pro/googleearth-bin*trace` so each session starts without stale traces.
If there are no matching files, cleanup is skipped and startup continues normally.
It then monitors module 13 until it prints:

```text
[OK] Finished traversing N points.
```

That message is the only successful completion condition. An `[ERROR]` message, a missing
Google Earth window, a startup timeout, or the unexpected termination of any managed
process marks the session as failed. In every case the script closes module 13 and its
child processes, module 12, and Google Earth before exiting.

The exit status is `0` after a successful traversal, `1` after a runtime or validation
failure, and `130` when interrupted with `Ctrl+C`. Complete process output is retained in
a per-run directory named `/tmp/14_sessionController-PID/`.

## Google Earth accessibility requirement

Google Earth must publish its Qt widget tree through AT-SPI. Edit the Google Earth launcher
used by this project, `/opt/google/earth/pro/google-earth-pro` (or its real target), and add
this export before the Google Earth binary is started:

```bash
export QT_LINUX_ACCESSIBILITY_ALWAYS_ON=1
```

Without this environment variable, module 13 cannot locate `turtle` or calculate the
screen coordinates of its first point.

## Prerequisites

- A live X11 desktop session with `DISPLAY` and `DBUS_SESSION_BUS_ADDRESS` exported.
- Python 3.
- `/opt/google/earth/pro/google-earth-pro` installed and executable.
- `/media/ramdisk/output` available.
- Module 12 already compiled at `12_fileSystemChangesDetector/build/fileSystemChangesDetector`.
- Module 13 and its Gradle runtime dependencies available.

Build module 12 beforehand when necessary:

```bash
cmake -S 12_fileSystemChangesDetector -B 12_fileSystemChangesDetector/build
cmake --build 12_fileSystemChangesDetector/build -j
```

## Run

From the repository root or from this directory:

```bash
./14_sessionController/run.sh
```

Do not use the keyboard or mouse while a session is running. Module 13 uses AWT Robot to
click the first point and send the `ENTER` and `DOWN` events to Google Earth.
