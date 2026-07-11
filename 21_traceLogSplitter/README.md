# traceLogSplitter

`traceLogSplitter` is a small C++ utility for post-processing OpenGL trace text logs.

## Obtaining big text file from trace session data

```
apitrace dump googleearth-bin.trace > textfile.txt
```

## What it does

- Reads a large input GL trace text file.
- Splits the stream into per-frame files under `./output/%05d/gl.txt`.
- Uses `glXSwapBuffers` as the frame boundary trigger.

## Build and run

```bash
cmake -S . -B build
cmake --build build -j
./build/traceLogSplitter <input_file>
```

## Notes for agentic coding agents

- Pure command-line batch tool, no GUI. Single positional argument: the input trace
  text file.
- Output goes to `./output/` relative to the current working directory (created if
  missing), one numbered folder per frame.
- On success it prints the number of files created and total lines processed, and
  exits with code `0`; any I/O error exits with code `1`.
