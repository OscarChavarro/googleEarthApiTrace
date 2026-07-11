# FrameTextureNormalizer

`FrameTextureNormalizer` is a Java 17 + Gradle utility that consumes preprocessed frame data from `/media/ramdisk/output` (or value configured at [src/main/resources/application.properties](src/main/resources/application.properties) ).

## Input

This program expects input produced previously by:

- `tracer` (texture image exports as `.png` and trace artifacts)
- `dumpAnalyzer` (per-frame `frame.json` files with tile and neighbor metadata)

Expected layout example:

- `/media/ramdisk/output/00003/frame.json`
- `/media/ramdisk/output/00003/*.png`

## Output behavior

The program writes image results into an output directory.

- If the directory does not exist or is empty, it creates/builds it.
- If the directory already exists and has files, it complements it by adding only new generated images.

## Current skeleton status

At this stage, this project contains a minimal JOGL/Vitral interactive skeleton similar to
`/tmp/vitral/testsuite/Jogl4Examples/CameraExample`, with these changes:

- No corridor geometry.
- Only the coordinate frame (X/Y/Z axes) is rendered.
- Camera interaction uses the Orbiter controller (`CameraControllerOrbiter`).

## Requirements

- Java 17
- Gradle
- Vitral artifacts available (same dependency approach as `dumpAnalyzer`)

## Run

From this directory:

```bash
gradle run
```

### CLI options

- `--offline`: renders one frame to an image and exits.
- `--start-frame <id>` or `--start-frame=<id>`: minimum frame id to load (inclusive).
- `--end-frame <id>` or `--end-frame=<id>`: maximum frame id to load (inclusive).
- `--width=<px>` and `--height=<px>`: output image size in offline mode.
- `--output=<path>`: output image path in offline mode.

### Offline example (frame 150)

```bash
gradle run --args="--offline --start-frame 150 --end-frame 150 --output=/tmp/frame150.png"
```
