# 52_planetDemViewer

Visor interactivo Java 17 + Gradle basado funcionalmente en `41_planetViewer`, pero para
pirámides DEM raw producidas por `51_demResampler`. Usa JOGL para dibujar y Vitral 1.3
para cámaras, exportación offline y lectura/evaluación de paletas GIMP.

## Entrada

Cada tesela `<quadkey>.bin` debe medir exactamente 133128 bytes y contener 258×258
enteros Int16 little-endian, por filas norte-sur. La aplicación conserva en RAM los
258×258 valores de cada tesela cargada, incluido el halo, dentro de una caché acotada.
Solo las celdas internas `[1..256] × [1..256]` se convierten en una textura ARGB de
256×256. `-32768` (NoData) se representa transparente.

La topología de carpetas es la misma que en el resto del repositorio: `0.bin` es la
raíz y cada dígito posterior ocupa una carpeta. También se acepta el formato histórico
de carpetas acumulativas.

El arranque es *lazy*: solo se valida y lee `0.bin`. El árbol restante se descubre en
un hilo de metadatos de baja prioridad a medida que los niveles entran en pantalla.
Cada carpeta se consulta una sola vez y el resultado, incluida la ausencia de hijos,
permanece en RAM durante toda la sesión. El perfil predeterminado `slow` usa un único
hilo lector de tiles y un único explorador para evitar búsquedas concurrentes en discos
lentos.

## Ejecución

```bash
./run.sh /media/extra/FABDEM/02_rawPyramidal
```

Para un almacenamiento rápido puede seleccionarse `--storage-profile fast`. En la
configuración habitual de `/media/extra` debe conservarse el valor predeterminado:

```bash
./run.sh --storage-profile slow /media/extra/FABDEM/02_rawPyramidal
```

La caché DEM utiliza hasta el 80 % del heap disponible. Para aprovechar una máquina
con mucha RAM hay que ampliar explícitamente el heap del proceso, por ejemplo después
de desmontar un ramdisk que esté consumiendo esa memoria:

```bash
PLANET_DEM_MAX_HEAP=180g ./run.sh /media/extra/FABDEM/02_rawPyramidal
```

Opcionalmente `PLANET_DEM_RAM_CACHE_BYTES` fija un límite explícito, siempre acotado
por el 80 % del heap.
No se configura un heap de 180 GB automáticamente para evitar comprometer memoria que
pueda estar asignada a `/media/ramdisk`.

Sin argumentos, `run.sh` usa esa misma ruta por defecto. Las opciones offline heredadas
del visor de imágenes siguen disponibles:

```bash
./run.sh --offline /media/extra/FABDEM/02_rawPyramidal \
  --output /tmp/planetDemViewer.png --width 1024 --height 1024
```

El modo offline necesita conocer todas las hojas y, por tanto, realiza explícitamente
un recorrido completo antes de exportar. Esa excepción no afecta al visor interactivo.

## Paletas y escala global

Al arrancar se leen mediante `RGBColorPalettePersistence.importGimpPalette` las 40
paletas de `../etc/palettes`, ordenadas por nombre. La paleta inicial es
`Topographic.gpl`. La elevación se normaliza globalmente en el intervalo 0..12000 m;
los valores fuera del intervalo se saturan en sus extremos.

Las teclas `3` y `4` seleccionan la paleta anterior/siguiente de forma circular. El HUD
muestra su nombre y el intervalo de alturas. Al cambiarla se liberan todas las texturas
JOGL y las imágenes de color pendientes; los DEM completos permanecen cacheados y se
recolorean bajo demanda con la nueva paleta.

## Controles

Conserva los controles de `41_planetViewer`: flechas/ratón, rueda y `z`/`Z`, `r`/`R`,
`l`, selección `1`/`2`, opacidad `o`/`O`, orden con PageUp/PageDown, vistas con
`.`/`,`/`w`/`v`/`V`, F1..F9 y Escape. Se añaden `3`/`4` para las paletas,
`SPACE` para ciclar el modo de operación y `5`/`6` para dividir/multiplicar por dos
la exageración vertical.

## Arquitectura para terreno

`model.DemTile` es la representación canónica con halo. `io.TileImageLoader` separa la
caché de elevaciones de las imágenes derivadas. `PALETTE_BASED_IMAGE` conserva el dibujo
2D sin iluminación. `BASIC_TRIANGULATION` genera los 256×256 vértices centrales y dos
triángulos por celda; el halo completo participa en las normales de vértice ponderadas
por ángulo. La elevación en metros se lleva al dominio mundial normalizado usando la
circunferencia ecuatorial y después se multiplica por `heightExagerationFactor`.
`terrain.TerrainMeshGenerator<T>` permanece como punto de extensión para las futuras
triangulaciones adaptativas.

Después del culling, `TerrainSeamStitcher` analiza la frontera de quadtree que se va a
dibujar. Cuando un borde fino toca un tile de menor nivel, sus alturas se sustituyen por
la interpolación lineal del borde grueso. El halo exterior usado para las normales se
muestrea bilinealmente sobre ese mismo DEM grueso, evitando una discontinuidad de luz
en la costura. El análisis y la generación de variantes se ejecutan en un pool de hasta
ocho hilos. Un LRU conserva tanto fronteras completas como relaciones individuales de
pares, por lo que también se reutiliza la parte estable cuando cambia solo una zona del
frame. Las variantes ya subidas se conservan en la caché GPU hasta alcanzar su presupuesto.

## Verificación

```bash
gradle test
```
