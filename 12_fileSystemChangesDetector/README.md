# fileSystemChangesDetector

This utility helps `googleEarthController` decide when to advance.

It monitors a target output folder (currently `/tmp/output`) using Linux `fanotify` and prints updates when new files are added.

## Why this exists

While the tracer is still writing image files, the controller should wait.

As long as recent filesystem updates keep happening in the monitored folder, `googleEarthController` treats that as "tracer is still filling images" and does not continue.

If no one writes anything to the monitored folder for some time, `googleEarthController` can continue the interaction.

## Runtime behavior

- Watches for new entries in the target directory.
- On each add event, prints:
  - `Updated at <date>`
- Date format example:
  - `2026_05may01_18:48.57`

## Run

Use:

```bash
./run.sh
```

`run.sh` builds the project and runs the detector with `sudo`, because `fanotify` operations require elevated privileges.
