# Strategy: Minimal apitrace fork to extract texture blobs without full tracing

## Goal

Modify the current apitrace-based tracer so that:
- Other than writing its standard .trace file, exports large binary objects (BLOBS) to a given folder, such as /tmp/output
- Raw binary data sent to image operations are exported as .ppm if uncompressed, or .dds if compressed.

---

# 1. High-Level Approach

Convert tracer into a selective interceptor:

- Hook relevant OpenGL calls (image transfers to GPU)
- Extract texture data directly from GL calls in DSS format when compressed

---

# 2. Key OpenGL Calls to Intercept

Focus on:

- glTexImage2D
- glTexSubImage2D
- glCompressedTexImage2D
- glCompressedTexSubImage2D

---

# 3. Frame Detection

THE_FrameNumber global variable contains the frame number.

---

# 4. Generate Unique Names

THE_TextureId contains the image id used with GPU

---

# 5. Care on using current implementation

Do not base query response in previous knowledge about apitrace tool. We are working in an specific version that is self contained. So, base desicions on analysis over current implementation.
