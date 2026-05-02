# googleEarthController

`googleEarthController` is a Java desktop controller that automates session progression in Google Earth.

## What it does

- Starts/stops the `12_fileSystemChangesDetector` process.
- Monitors detector output (activity in tracer output folders).
- Keeps a timer-based inactivity cycle.
- When no new writes are detected for a timeout window, sends keyboard actions (`DOWN`, then `ENTER`) to continue navigation.

## Purpose in the pipeline

The controller simulates user interaction and keeps the session moving at the highest safe speed while avoiding data loss, based on tracer write activity.
