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

## Extra helper tool

The project also includes a line-analysis utility that reports:

- Total line count
- Maximum line length

This is useful for sizing buffers and validating very large trace logs.
