# dumpAnalyzer

`dumpAnalyzer` is a Java 17 + Gradle tool that scans OpenGL trace dumps under `/media/ramdisk/output` and counts OpenGL function calls found in each `gl.txt` file.

## What It Does

- Scans `/media/ramdisk/output` for frame folders named as numbers (for example `00413`).
- For each frame folder containing `gl.txt`, calls frame processing with:
  - `frame`: numeric value from the folder name (for example `413`)
  - `filename`: absolute path to the file (for example `/media/ramdisk/output/00413/gl.txt`)
- Normalizes multiline logical calls (for example long `glShaderSource` payloads split across multiple physical lines).
- Parses content with ANTLR grammar (`GlTrace.g4`) tailored to the current trace format.
- Collects a global map: `OpenGL function name -> total call count` across all frames.
- Prints the final sorted function-count map.

## Error Behavior

Parsing/lexing failures are treated as fatal:

- The tool prints the absolute path of the failing file.
- The tool exits immediately with `System.exit(666)`.

## Project Layout

- `src/java/Main.java`: application entry point and orchestration.
- `src/java/FrameScanner.java`: scans frame folders and locates `gl.txt` files.
- `src/java/GlTraceProcessor.java`: per-frame processing and ANTLR parse execution.
- `src/java/LogicalLineNormalizer.java`: converts physical lines to logical lines.
- `src/java/FunctionCounter.java`: global function token counting.
- `src/main/java/dumpanalyzer/logger/FatalErrorHandler.java`: fatal error reporting and exit.
- `src/main/antlr/GlTrace.g4`: ANTLR grammar for trace lines.

## Requirements

- Java 17
- Gradle

## Build

From the `dumpAnalyzer` directory:

```bash
gradle build
```

## Run

From the `dumpAnalyzer` directory:

```bash
gradle run
```

The application always processes `/media/ramdisk/output` in the current implementation.

## Execution Modes

The program supports two execution modes:

- Interactive mode: runs the JOGL viewer and lets you inspect tiles, AABBs, and neighborhood links visually.
- Offline mode: renders one frame directly to an image file without opening the interactive window.

Helper scripts:

- `./run.sh` runs the interactive mode (`gradle run`).
- `./runOffline.sh` runs offline mode and exports frame `00003` to `output/frame0003.png`.

Besides visual debugging of the neighborhood calculation, neighbor values are also exported into per-frame files under the input directory tree `/media/ramdisk/output/<frame>/`.
Note: the current file format is `frame.json`.

## Command-Line Options

`dumpAnalyzer` supports these runtime options:

- `--offline`: render a single frame to an image and exit (no interactive window).
- `--start-frame <id>`: initial selected frame id in the viewer/offline run.
- `--end-frame <id>`: highest frame id to load from `/media/ramdisk/output` (inclusive).
- `--width <px>`: viewport/output width.
- `--height <px>`: viewport/output height.
- `--output <path>`: output image path used in offline mode.

### Example: load only first 500 frames

If `/media/ramdisk/output` has 9634 frames and you want faster test cycles:

```bash
gradle run --args="--end-frame 500"
```

# Recognized OpenGL API calls

| Category | Functions |
|---|---|
| Shaders / Programs | `glAttachShader`, `glBindAttribLocation`, `glCompileShader`, `glCreateProgram`, `glCreateShader`, `glDeleteProgram`, `glDeleteShader`, `glGetActiveAttrib`, `glGetActiveUniform`, `glGetProgramiv`, `glGetShaderSource`, `glGetShaderiv`, `glGetUniformLocation`, `glLinkProgram`, `glShaderSource`, `glUniform1fv`, `glUniform1i`, `glUniform3fv`, `glUniform4fv`, `glUniformMatrix4fv`, `glUseProgram` |
| Textures | `glActiveTextureARB`, `glBindTexture`, `glClientActiveTextureARB`, `glCompressedTexImage2DARB`, `glDeleteTextures`, `glGenTextures`, `glPixelStorei`, `glTexEnvi`, `glTexImage2D`, `glTexParameterf`, `glTexParameteri`, `glTexSubImage2D` |
| Geometry / Buffers / Draw | `glBindBuffer`, `glBufferData`, `glBufferSubData`, `glDisableVertexAttribArray`, `glDrawArrays`, `glDrawElements`, `glEnableVertexAttribArray`, `glGenBuffers`, `glLineStipple`, `glLineWidth`, `glPointSize`, `glPolygonMode`, `glPolygonOffset`, `glVertexAttribPointer` |
| Transformations / Camera | `glDepthRange`, `glGetFloatv`, `glLoadIdentity`, `glLoadMatrixf`, `glMatrixMode`, `glUniformMatrix4fv`, `glViewport` |
| State / Framebuffer / Tests | `glAlphaFunc`, `glBlendFunc`, `glClear`, `glClearColor`, `glClearDepth`, `glClearStencil`, `glClipPlane`, `glColor4ub`, `glColorMask`, `glColorMaterial`, `glCullFace`, `glDepthFunc`, `glDepthMask`, `glDisable`, `glEnable`, `glFogf`, `glFogfv`, `glFogi`, `glFrontFace`, `glGetIntegerv`, `glScissor`, `glShadeModel`, `glStencilFunc`, `glStencilMask`, `glStencilOp` |
| Lighting / Material | `glLightModelfv`, `glLightModeli`, `glLightf`, `glLightfv`, `glMaterialf`, `glMaterialfv` |
| Context / Window (GLX) | `glXChooseVisual`, `glXCreateContext`, `glXDestroyContext`, `glXMakeCurrent`, `glXSwapBuffers`, `glXSwapIntervalSGI` |
