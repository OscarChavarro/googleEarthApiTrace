# fileSystemChangesDetector

`fileSystemChangesDetector` is a Linux utility used by `13_googleEarthController` to decide when Google Earth navigation can continue.

It watches a target output directory (typically `/tmp/output`) with `fanotify` and reports new file activity.

## Why it exists

The tracer writes texture/frame artifacts asynchronously. While writes are still happening, the controller should not advance. When writes stop for a configured time window, the controller can safely continue.

## Runtime behavior

- Watches one directory for `FAN_CREATE` and `FAN_MOVED_TO` events.
- Prints one line per detected update:
  - `Updated at <timestamp>`
- Accepts `exit` on stdin for graceful shutdown.

## Important privilege requirement (fanotify)

Due to `fanotify` privilege limitations, this executable must run with elevated privileges.

In this project workflow, it is expected to be:

- Owned by `root`
- Marked with the `setuid` (`suid`) bit

Without that, `fanotify_init` can fail with a permission error.

## Set owner and `suid`

From this folder, after building:

```bash
sudo chown root:root ./build/fileSystemChangesDetector
sudo chmod 4755 ./build/fileSystemChangesDetector
```

Optional check:

```bash
ls -l ./build/fileSystemChangesDetector
```

You should see owner `root root` and permissions similar to `-rwsr-xr-x`.

## Build and run

From this folder:

```bash
./run.sh
```

`run.sh` builds and runs the detector with `sudo`.
