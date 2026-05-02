# Google Earth OpenGL Trace Experiments

This repository contains **experimental Linux-only code** to extract Google Earth image data by leveraging the connection between the application and the GPU through **OpenGL API calls**.

The core idea is to observe and process the OpenGL command stream, including large binary payloads (BLOBs) such as textures and geometry-related data.

## Pipeline Overview

The extraction workflow is split into stages. Each stage has separate codebases, which is why projects are numbered (`01`, `02`, `11`, `12`, `13`, `21`, `22`, and planned `31`, etc.).

- Projects starting with `0` are the **Tracer stage**.
  - They capture OpenGL API activity, produce trace logs, and expose BLOB data for textures and geometry.
- Projects starting with `1` are the **Tracing Execution stage**.
  - They include route/path planning and a Google Earth session controller.
  - The controller simulates user interaction and keeps the session moving at the highest safe speed so data is not lost.
  - This is done by monitoring texture-writing activity through the tracer output.
- Projects starting with `2` are the **Data Normalization stage**.
  - They identify and remove repeated textures and represent texture groups as matrix-like structures for downstream processing.
- Projects starting with `3` are the **Final Gigapixel Build stage**.
  - Goal: build a quadtree of tiles ("Pyramidal Image") from normalized data.
  - Status: **not implemented yet** in this repository.

## Projects

### 01_tracer
- README: [01_tracer/README.md](01_tracer/README.md)
- Minimal `apitrace`-based tracer fork. Keeps writing `.trace` while exporting runtime artifacts per frame, including texture blobs and GL operation logs.

### 02_traceLogSplitter
- README: _not present_
- Source summary:
  - Splits a large GL trace text file into per-frame chunks under `./output/%05d/gl.txt`.
  - Uses `glXSwapBuffers` as frame boundary.
  - Includes a utility to compute line statistics (total lines and max line length) for large trace files.

### 11_pathPlanner
- README: _not present_
- Source summary:
  - Generates traversal curves (currently `spiral` and `zigzag`) from a geographic start point.
  - Computes geodesic point sequences and writes them into Google Earth KML (`myplaces.kml`) as routes plus sampled markers.
  - Used to plan the area-scanning path before running active tracing sessions.

### 12_fileSystemChangesDetector
- README: [12_fileSystemChangesDetector/README.md](12_fileSystemChangesDetector/README.md)
- Watches tracer output folders and reports recent filesystem write activity, so the controller can decide when it is safe to continue navigation.

### 13_googleEarthController
- README: _not present_
- Source summary:
  - Java desktop controller that starts/stops the filesystem detector process.
  - Monitors detector output and triggers keyboard actions (`DOWN`, `ENTER`) when no new writes are observed for a timeout window.
  - Simulates user-session progression while adapting speed to tracer activity.

### 21_dumpAnalyzer
- README: [21_dumpAnalyzer/README.md](21_dumpAnalyzer/README.md)
- Parses per-frame GL logs (`gl.txt`) and counts/analyses OpenGL calls with ANTLR-based processing.

### 22_frameTextureNormalizer
- README: [22_frameTextureNormalizer/README.md](22_frameTextureNormalizer/README.md)
- Consumes frame-level artifacts and performs normalization-oriented preprocessing for later composition workflows.

## Notes

- This codebase is experimental and tailored to a specific workflow and environment.
- Current focus is Linux execution and OpenGL/GLX-based capture-processing paths.
