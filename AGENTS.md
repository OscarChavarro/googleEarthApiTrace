# Strategy: Minimal apitrace fork to extract texture blobs without full tracing

## Goal

Modify the current stripped apitrace-based tracer so that:

- ❌ It does NOT write full .trace logs (avoid performance penalty)
- ✅ It detects when textures are uploaded (tile streaming scenario)
- ✅ It extracts texture image data (BLOBs)
- ✅ It saves them to disk in an organized structure

---

# 1. High-Level Approach

Convert tracer into a selective interceptor:

- Hook only relevant OpenGL calls
- Bypass trace writer entirely
- Extract texture data directly from GL calls

---

# 2. Key OpenGL Calls to Intercept

Focus on:

- glTexImage2D
- glTexSubImage2D
- glCompressedTexImage2D
- glCompressedTexSubImage2D

---

# 3. Disable Trace Writing

Locate usage of:

- trace_writer.*
- writer.writeCall(...)

Replace with no-op or conditional bypass:

```
if (isTextureUploadCall) {
    // custom handling
} else {
    return real_function(...);
}
```

Or global flag:

```
#define TRACE_WRITE_DISABLED 1
```

---

# 4. Modify GL Wrapper Functions

Intercept texture uploads:

```
void glTexImage2D(...) {
    if (shouldCaptureTexture(target, level, internalFormat)) {
        processTexture(...);
    }

    real_glTexImage2D(...);
}
```

---

# 5. Implement Texture Capture Module

Create:

```
texture_capture.hpp
texture_capture.cpp
```

Core function:

```
void processTexture(GLenum target,
                    GLint level,
                    GLint internalFormat,
                    GLsizei width,
                    GLsizei height,
                    GLenum format,
                    GLenum type,
                    const void* data);
```

---

# 6. Detect Valid Tile Textures

Filter irrelevant textures:

```
bool shouldCaptureTexture(...) {
    return width >= 128 && height >= 128;
}
```

Optional filters:
- level == 0
- formats like GL_RGBA, GL_RGB

---

# 7. Dump Texture to Disk

Directory structure:

```
output/
  frame_0001/
    tex_0001.png
```

---

# 8. Frame Detection

Hook:

```
glXSwapBuffers(...)
```

Increment:

```
current_frame++;
```

---

# 9. Convert Raw Data to Image

For uncompressed:

```
stbi_write_png(path, width, height, 4, data, width * 4);
```

Use:
- stb_image_write.h

For compressed:
- dump .bin
- or decode later

---

# 10. Generate Unique Names

```
static int texture_id = 0;
```

Filename format:

```
frame_XXXX_tex_YYYY.png
```

---

# 11. Avoid Performance Bottlenecks

Do not block GL thread.

Use async worker:

```
enqueue_texture_dump(...)
```

---

# 12. Optional Deduplication

```
uint64_t hash = hash_buffer(data, size);
```

Skip duplicates.

---

# 13. Minimal Integration Points

Modify only:

- GL wrappers
- new texture module

Do NOT touch:
- parser
- GUI
- replay

---

# 14. Build Integration

```
add_library(texture_capture texture_capture.cpp)
target_link_libraries(glxtrace texture_capture)
```

---

# 15. Debug Logging

```
printf("Captured texture %dx%d\n", width, height);
```

---

# Result

- No .trace overhead
- Real-time texture extraction
- Organized tile dataset

---

# Future Extensions

- Map tiles to world coordinates
- Reconstruct terrain
- Build offline cache
