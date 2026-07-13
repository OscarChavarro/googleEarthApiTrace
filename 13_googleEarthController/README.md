# googleEarthController

`googleEarthController` is a Java desktop controller that automates session progression in Google Earth.

## What it does

- Starts/stops the `12_fileSystemChangesDetector` process.
- Monitors detector output (activity in tracer output folders).
- Keeps a timer-based inactivity cycle.
- When no new writes are detected for a timeout window, sends keyboard actions (`DOWN`, then `ENTER`) to continue navigation.

![Panel](doc/controllerPanel.png)

## How to use

On Google Earth, prepare the path built with `11_pathPlanner` and double click on its first point. Then click in the start
button from the panel and leave the session alone. The controller will run over the points by pressing <Down> and <Enter>
on the keyboard, so no other applications can be used on the session while downloading data in the controlled Google Earth
session.

## Recomended sync with 12_fileSystemChangesDetector

It is recommended to use this panel in sync with `12_fileSystemChangesDetector` for optimal
speed operation in current system. The current implementation expects the detector binary
at `../12_fileSystemChangesDetector/build/fileSystemChangesDetector`; if it cannot be
executed, the automatic navigation session does not start.

## Purpose in the pipeline

The controller simulates user interaction and keeps the session moving at the highest safe speed while avoiding data loss, based on tracer write activity.

## Panel controls

This program is driven by its Swing panel buttons (there are no keyboard shortcuts of its own):

| Control | Action |
|---|---|
| `START` / `STOP` button | Starts or pauses the automatic advance cycle |
| `QUIT` button | Stops the detector process and exits |

The progress label shows how many route placemarks have been advanced out of the total
counted in the `turtle` folder of `~/.googleearth/myplaces.kml`. When that count can be
read successfully, the controller also stops automatically once the number of completed
advances reaches that total, and the button returns to `START`.

## Notes for agentic coding agents

- There are no command-line options and no offline/headless mode.
- The controller requires a live desktop session: it uses `java.awt.Robot` to inject
  `DOWN` + `ENTER` key presses into the focused application (Google Earth), so it cannot
  run under a headless display and will interfere with any other focused window.
- It spawns and consumes `12_fileSystemChangesDetector` output to decide when to advance.
- Before each `DOWN` + `ENTER` sequence it also runs `sync`, then waits the built-in key
  timing windows (`430 ms` hold, `1000 ms` between keys).
