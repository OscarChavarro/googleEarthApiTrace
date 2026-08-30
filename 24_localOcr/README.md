# Local OCR

Native OCR bridge for `22_dumpAnalyzer`.

The shared library exports:

- `char *local_ocr_png_to_text(const char *png_filename)`
- `void local_ocr_free_string(char *value)`
- JNI method used by `dumpanalyzer.ocr.LocalOcrEngine`

It embeds the system Python runtime and loads `paddleocr` lazily on the first OCR call.
If `paddleocr`/`paddle` are not installed, the function returns an empty string so the
dump analyzer can continue processing frames.

Build:

```bash
cmake -S 24_localOcr -B 24_localOcr/build
cmake --build 24_localOcr/build
```

Runtime defaults:

- Library path: `24_localOcr/build/liblocalOcr.so`
- OCR language: `en`

The Java side can override these with `localOcr.library.path` and `localOcr.lang`
in `22_dumpAnalyzer/src/main/resources/application.properties`, Java system
properties, or the `LOCAL_OCR_LIBRARY` / `LOCAL_OCR_LANG` environment variables.
