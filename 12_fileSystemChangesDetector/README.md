# 12_fileSystemChangesDetector

`12_fileSystemChangesDetector` is a Linux utility used by `13_googleEarthController` to decide the best moment to make Google Earth navigation to continue movement, based on disk write
activity.

It watches a target output directory (typically `/media/ramdisk/output`) with `inotify` and reports new file activity.

## Why it exists

The `01_tracer` writes texture/frame artifacts asynchronously. While writes are still happening, the `13_googleEarthController` should not advance in order to avoid machine performance degradation or data being droped. When writes stop for a configured time window, the controller can safely continue.

## Runtime behavior

- Watches one directory tree recursively for `IN_CREATE`, `IN_MOVED_TO`, and
  `IN_CLOSE_WRITE` events.
- Prints and flushes `ready` once all initial watches are installed.
- Prints and flushes `activity` at most once per second while changes continue. A trailing
  line is emitted after a shorter burst, so the consumer never bases inactivity on an
  event older than the last observed change.
- Accepts `exit` on stdin for graceful shutdown.
- Adds watches dynamically for newly created subdirectories.

## Privileges

The current implementation uses `inotify`, so it normally does not require `root`, `sudo`, or `setuid`.
Standard read/execute access to the watched directory tree is sufficient.

## Build and run

From this folder:

```bash
cmake -S . -B build
cmake --build build -j
./build/fileSystemChangesDetector /media/ramdisk/output
```

Or use:

```bash
./run.sh
```

`run.sh` builds and runs the detector without `sudo`, watching the fixed directory
`/media/ramdisk/output`.

## Notes for agentic coding agents

- Fully scriptable, no GUI. One positional argument: the directory to watch.
- `./run.sh` is a fixed-path convenience wrapper; pass a custom directory only when
  invoking the compiled binary directly.
- Output protocol on stdout: one `ready` line followed by coalesced `activity` lines.
  Protocol lines are explicitly flushed because the normal consumer uses a pipe rather
  than a terminal. Diagnostics are written only to stderr.
- Control protocol on stdin: writing `exit` terminates the process gracefully.
- No other command-line options exist.
