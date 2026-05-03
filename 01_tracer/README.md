# Tracer Overview

Este directorio contiene un fork mínimo de `apitrace` orientado a captura + export en runtime.

Durante la ejecución, el tracer:

1. Sigue escribiendo el `.trace` estándar.
2. Exporta blobs/artefactos por frame en `/media/ramdisk/output/%05d/`.

## Objetivo de esta variante

- Mantener compatibilidad con flujo normal de `apitrace` (`.trace`).
- Extraer datos binarios grandes durante el trace (sin depender sólo de post-procesado).
- Exportar texturas cuando aparecen como blobs en llamadas GL relevantes.

## Hooks GL relevantes para texturas

En la implementación actual (ver `wrappers/glxtrace.py` + `lib/trace/trace_writer.cpp`), el estado de textura para export se prepara en:

- `glTexImage2D`
- `glTexSubImage2D`
- `glCompressedTexImage2DARB`
- `glBindTexture` (actualiza `THE_TextureId`)

El número de frame se actualiza en `glXSwapBuffers` usando `THE_FrameNumber`.

## Formato de export de texturas (actual)

La exportación ocurre desde `Writer::writeBlob(...)` cuando hay contexto de textura activo (`THE_TextureFormat/THE_TextureWidth/THE_TextureHeight`):

- Comprimida DXT1 (`GL_COMPRESSED_RGB_S3TC_DXT1_EXT`): se exporta como `.dds` con header DDS.
- No comprimida y decodificable (`type == GL_UNSIGNED_BYTE` y formatos soportados): se exporta como `.png`.

Nombre de archivo:

- `/media/ramdisk/output/%05d/%dx%d_%d.dds`
- `/media/ramdisk/output/%05d/%dx%d_%d.png`

Donde `%d` final corresponde a `THE_TextureId`.

Nota: actualmente el código no exporta `.ppm`; la salida no comprimida implementada es `.png`.

## Otros blobs exportados

Además de texturas, también se exportan blobs para análisis geométrico/buffers:

- `glDrawElements` (índices)
- `glVertexAttribPointer`
- `glBufferData` / `glBufferSubData` (snapshots y metadatos)

Se genera `manifest.txt` por frame con líneas `key=value` describiendo cada export.

## Layout de salida

- `/media/ramdisk/output/%05d/gl.txt`
- `/media/ramdisk/output/%05d/manifest.txt`
- `/media/ramdisk/output/%05d/*.dds|*.png|*.bin|*.meta.txt`

## Variables y runtime flags

- `TRACE_FILE`: ruta del `.trace`.
- `TRACE_TIMESTAMP`: timestamp en nombre de trace.
- `FLUSH_EVERY_MS`: flush periódico del stream de trace.
- `TRACE_WRITE_GLTXT=0`: desactiva escritura de `gl.txt`.
- `TRACE_PNG_THREADS`: número de workers para PNG asíncrono (default: autodetección con tope conservador; rango válido `1..256`).
- `TRACE_PNG_QUEUE`: tamaño máximo de cola de export PNG asíncrona (default `128`, max `4096`).

## Export PNG asíncrono (productor-consumidor)

- El hilo de tracing encola trabajos de PNG y **copia el blob** a memoria propia del job.
- Un pool de workers consume la cola y escribe `.png` en background.
- Dedupe thread-safe por `frame + textureId` con estados `pending/exported`.
- La cola es acotada: si se llena, el productor bloquea hasta tener espacio.

Notas:
- La exportación `.dds` (DXT1) sigue síncrona.
- Si `TRACE_PNG_THREADS` no está definido, el pool se dimensiona automáticamente con límite conservador (hasta 12 workers) para evitar sobrecontención.

## Referencias de implementación

- `wrappers/glxtrace.py`: set/reset de globals `THE_*`, frame boundary, hooks GLX/GL.
- `lib/trace/trace_writer.cpp`: `writeBlob`, `exportPlain`, export de `.dds/.png`, manifests y blobs auxiliares.
