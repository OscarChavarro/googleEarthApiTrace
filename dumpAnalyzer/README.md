# dumpAnalyzer

`dumpAnalyzer` is a Java 17 + Gradle tool that scans OpenGL trace dumps under `/tmp/output` and counts OpenGL function calls found in each `gl.txt` file.

## What It Does

- Scans `/tmp/output` for frame folders named as numbers (for example `00413`).
- For each frame folder containing `gl.txt`, calls frame processing with:
  - `frame`: numeric value from the folder name (for example `413`)
  - `filename`: absolute path to the file (for example `/tmp/output/00413/gl.txt`)
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
- `src/java/FatalErrorHandler.java`: fatal error reporting and exit.
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

The application always processes `/tmp/output` in the current implementation.

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
