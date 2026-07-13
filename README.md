# Google Earth OpenGL Trace Experiments

This repository contains **experimental Linux-only code** to extract Google Earth image data by leveraging the connection between the application and the GPU through **OpenGL API calls**.

The core idea is to observe and process the OpenGL command stream, including large binary payloads (BLOBs) such as textures and geometry-related data.

This work started from a minimal fork of the original `apitrace` tool, which makes this interception-based approach possible: [apitrace on GitHub](https://github.com/apitrace/apitrace).

## Pipeline Overview

The extraction workflow is split into stages. Each stage has separate codebases, which is why projects are numbered (`01`, `11`, `12`, `13`, `21`, `22`, `23`, `31`, etc.).

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
  - Status: **in progress**. `31_matrixMerger` merges per-frame matrices into consolidated
    layer matrices and exports them; `32_pyramidalImageExporter` imports those layers plus
    the global top-level tile data, anchors every layer into absolute quadtree coordinates
    (reconstructing levels 0-5 from per-appearance texture coordinates and resolving deeper
    layers through uncle relationships), and writes the session's pyramidal image as a
    quadtree of PNG files inside its own input folder. Each capture session produces its
    own independent pyramid; merging the pyramidal images of different sessions into one
    consolidated pyramid is the responsibility of a separate program (pending).
- Projects starting with `4` are the **Visualization / Inspection stage**.
  - They consume already exported pyramidal images and provide interactive or offline
    inspection tools. `41_planetViewer` is the current viewer.

## Projects

- [01_tracer/README.md](01_tracer/README.md): Minimal `apitrace`-based tracer fork. Keeps writing `.trace` while exporting runtime artifacts per frame, including texture blobs and GL operation logs.
- [21_traceLogSplitter/README.md](21_traceLogSplitter/README.md): Splits large GL trace logs into per-frame `gl.txt` files.
- [11_pathPlanner/README.md](11_pathPlanner/README.md): Generates planned geographic traversal routes (`spiral`/`zigzag`) and writes KML for Google Earth.
- [12_fileSystemChangesDetector/README.md](12_fileSystemChangesDetector/README.md): Monitors tracer output folder activity with `inotify` to support safe controller pacing.
- [13_googleEarthController/README.md](13_googleEarthController/README.md): Automates Google Earth session progression based on detector inactivity.
- [22_dumpAnalyzer/README.md](22_dumpAnalyzer/README.md): Parses per-frame GL logs (`gl.txt`) and counts/analyses OpenGL calls with ANTLR-based processing.
- [23_frameTextureNormalizer/README.md](23_frameTextureNormalizer/README.md): Consumes frame-level artifacts and performs normalization-oriented preprocessing for later composition workflows.
- [31_matrixMerger/README.md](31_matrixMerger/README.md): Loads per-frame tile matrices, visualizes one matrix at a time, supports interactive/automatic matrix merging and west-cutter splitting, and exports consolidated layer matrices.
- [32_pyramidalImageExporter/README.md](32_pyramidalImageExporter/README.md): Imports consolidated layer matrices from `31_matrixMerger` plus global top-level tile data from `22_dumpAnalyzer`, visualizes the quadtree layers, and exports the session's pyramidal image into the input folder itself (`<inputFolder>/pyramidalImage`); it never reads or writes any other pyramidal image — cross-session merging belongs to a separate, future program.
- [41_planetViewer/README.md](41_planetViewer/README.md): Loads one or more exported pyramidal images and visualizes them interactively or offline, including stacked overlays and multi-view inspection.

## Notes

- This codebase is experimental and tailored to a specific workflow and environment.
- Current focus is Linux execution and OpenGL/GLX-based capture-processing paths.
