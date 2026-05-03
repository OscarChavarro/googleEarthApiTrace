# 31_matrixMerger

`31_matrixMerger` es un visor/procesador de matrices de tiles (`matrix.json`) generado en etapas previas, orientado a fusionar matrices solapadas y visualizar el resultado.

## Qué hace

- Lee matrices desde carpetas de frame dentro de `output.directory` (por defecto `/media/ramdisk/output`).
- Muestra una matriz activa por vez como quads en el plano `Z=0`.
- Permite navegación de matriz y merges interactivos.
- Implementa culling por frustum para no pintar quads fuera de cámara.
- Implementa LOD por distancia:
  - Cerca: quad completo con textura (pixel-perfect con `NEAREST` + `CLAMP_TO_EDGE`).
  - Lejos: quad sin textura, escalado al `98%` para dejar separación visual.
- Gestiona memoria de texturas GPU con presupuesto máximo y expulsión FIFO.

## Entradas esperadas

Cada frame debe contener `matrix.json` (fallback: `matrix.txt`) con estructura compatible con:

- `frameId`
- `rows`
- `cols`
- `tiles[]` con:
  - `id` (string, recomendado)
  - `i`, `j`
  - `textureFile`

También acepta legado con `tileId` numérico al deserializar.

## Controles en visor

- `1`: matriz anterior
- `2`: matriz siguiente
- `m`: merge entre matriz actual (A) y la siguiente (B)
- `M`: merge sobre todo el conjunto (algoritmo completo)
- `t`: toggle de textura (delegado al `RendererConfigurationController`)
- `ESC`: salir

HUD:

- Siempre: `Matrix [1, 2]: i/N`
- Si existe matriz siguiente: `Merge current matrix with next one [m]`
- Si el último merge local falló (sin cambiar selección):
  - `ERROR: Could not merge with next matrix!` (en rojo)

## Algoritmos de merge

### `processor.MatrixMerger`

Opera sobre dos matrices `A` y `B`:

1. Busca celdas coincidentes por `id` para calcular un único desplazamiento (`MatrixOffset`) de `B` sobre `A`.
2. Si el desplazamiento no es consistente para todos los `id` compartidos, falla.
3. Si al superponer hay conflictos de contenido en la misma celda, falla.
4. Si es válido, agrega a `A` las celdas de `B` que no estaban en `A`.
5. Normaliza coordenadas de `A` para que comiencen en `0` y recalcula `rows/cols`.

### `processor.FullSetMerger`

Recorre la lista completa:

1. Toma `A = matrices[i]`, `B = matrices[i+1]`.
2. Si mergea, elimina `B` y vuelve a intentar con la nueva siguiente sobre la misma `A`.
3. Si falla, avanza `i`.
4. Termina al quedar una sola matriz o no existir más pares.

## Ejecución

### Modo interactivo

Desde el root del repo:

```bash
./gradlew :31_matrixMerger:run
```

### Modo offline

Ejecuta solo el merge global del conjunto y termina sin GUI:

```bash
./gradlew :31_matrixMerger:run --args="--ofline"
```

(Se acepta también `--offline` por compatibilidad.)

## Configuración

En `matrixmerger.config.Configuration`:

- `MAX_GPU_TEXTURE_MEMORY`: límite de memoria de texturas en GPU.
- `MAX_TEXTURED_QUAD_DISTANCE`: umbral de distancia para usar textura.
- `FAR_QUAD_SCALE`: escala del quad lejano (sin textura).

## Estructura de paquetes

- `io`: lectura/deserialización de matrices.
- `model`: estado de visualización y selección.
- `processor`: merge local y merge del conjunto completo.
- `render`: renderer JOGL, culling y LOD.
- `gui`: teclado/ratón.
