# Plan de implementación del pipeline superescalar

## 1. Objetivo y conclusión de viabilidad

El objetivo es solapar la captura del tile `N+1` con el postprocesamiento del tile `N`,
manteniendo como máximo una captura y un postprocesamiento activos:

```text
tiempo ───────────────────────────────────────────────────────────────►

captura:       A(tile 1)       A(tile 2)       A(tile 3)
                    │               │               │
handoff:            ▼               ▼               ▼
proceso:            B(tile 1)       B(tile 2)       B(tile 3)
```

- **Etapa A (productora):** `11_pathPlanner`, Google Earth bajo `01_tracer`,
  `12_fileSystemChangesDetector`, `13_googleEarthController` y
  `14_sessionController`. Escribe exclusivamente en
  `/media/ramdisk/output_incoming`.
- **Etapa B (consumidora):** dump de la traza y módulos `21`, `22`, `23`, `31`, `32` y
  `42`. Lee y modifica exclusivamente `/media/ramdisk/output` y usa `/tmp/matrix` para
  el delta.
- **Handoff:** solo cuando A ha terminado de publicar todos sus ficheros y B ha dejado
  libre `output`, los dos directorios cambian de rol. A continuación se vacía el nuevo
  `output_incoming` y comienza la captura siguiente.

En régimen estable, el tiempo por tile será aproximadamente
`max(T_A, T_B)`, no `T_A + T_B`. El log real de `/tmp/googleEarthSession.log` del
23 de agosto de 2026 muestra capturas de aproximadamente 11 minutos, mientras que
`32_pyramidalImageExporter` por sí solo emplea habitualmente 24–40 minutos (ejemplos
recientes: 1446, 1453, 1476, 1578, 1735, 2002, 2404 y 3077 segundos). El resto de B
añade aproximadamente 8–11 minutos.

Por ello:

- el solapamiento propuesto es útil y debería eliminar del camino crítico la mayor parte
  de los aproximadamente 11 minutos de captura;
- **menos de 20 minutos por tile no es un objetivo realista con solo dos etapas y el
  código actual**, porque B, y con frecuencia solo el módulo 32, ya supera ese tiempo;
- un primer objetivo realista debe ser medir una cadencia sostenida de aproximadamente
  30–40 minutos en los tiles actuales, con la variación dictada por el módulo 32;
- alcanzar menos de 20 minutos requiere una segunda línea de trabajo sobre el módulo 32
  (perfilado, caché/indexación de la pirámide de referencia, reducción de recorridos y/o
  paralelismo interno), además del pipeline doble.

No se debe cambiar ningún contrato geométrico, de IDs, relaciones, quadkeys ni formato de
pirámide descrito en `doc/README.md`.

## 2. Restricciones que condicionan el diseño

### 2.1. Las rutas absolutas del tracer

No es correcto limitar el cambio a hacer que `01_tracer` escriba en
`output_incoming` y después renombrar el directorio. `manifest.txt` contiene rutas
absolutas en sus campos `file=`, que `22_dumpAnalyzer` abre después. Si esas rutas
conservan el prefijo `output_incoming`, quedan rotas tras el handoff.

La solución recomendada es separar en `01_tracer`:

- el **directorio físico de escritura**, definido en ejecución por una variable como
  `TRACE_OUTPUT_DIRECTORY`, con valor `/media/ramdisk/output_incoming` en A;
- el **directorio lógico publicado en manifests**, definido por
  `TRACE_MANIFEST_OUTPUT_DIRECTORY`, con valor `/media/ramdisk/output`.

Todos los PNG, DDS, blobs, temporales, manifests y carpetas de frame se escriben
físicamente bajo el primer directorio. Solo las rutas `file=` serializadas usan el
segundo. La configuración compilada `OUTPUT_DIRECTORY` queda como valor predeterminado
compatible con ejecuciones antiguas.

No se recomienda un reemplazo textual posterior de manifests: añade otro estado parcial
y puede dejar una captura inconsistente después de un corte. Tampoco se debe alternar un
symlink llamado `output` mientras B trabaja: los consumidores abren rutas repetidamente
y podrían saltar de dataset a mitad de ejecución.

### 2.2. Propiedad exclusiva de la traza de apitrace

Actualmente `14_sessionController` borra las trazas
`/opt/google/earth/pro/googleearth-bin*trace` antes de cada captura, y
`runFullProcess.sh` las selecciona y vuelca después. Si comienza A(tile N+1) mientras
B(tile N) todavía necesita esa traza, la nueva A puede borrarla o hacer ambigua la
selección.

Antes de declarar A como terminada se debe:

1. comprobar que existe exactamente una traza completa, legible y no vacía;
2. copiarla a
   `/media/ramdisk/output_incoming/_pipeline/capture.trace.partial`;
3. esperar y comprobar el resultado de la copia, sincronizar ese fichero y renombrarlo a
   `capture.trace` dentro del mismo tmpfs;
4. guardar tamaño y SHA-256 en el descriptor del job;
5. borrar la traza fuente únicamente después de publicar la copia final.

B hará `apitrace dump` desde `/media/ramdisk/output/_pipeline/capture.trace`, no volverá
a buscar trazas globales en `/opt/google/earth/pro`, y borrará `capture.trace` y
`bigtrace.log` en cuanto el split haya sido validado. Así, la captura siguiente puede
limpiar su directorio de Google Earth sin afectar al job anterior.

### 2.3. Datos de captura que deben sobrevivir durante toda B

Los `frame.json` producidos por 22 no se pueden borrar después de 23: el módulo 32 puede
reabrirlos mediante `DanglingUncleBridge`. Asimismo, `topLevelTiles.json`, las texturas y
los manifests deben permanecer en `/media/ramdisk/output` hasta que 32 haya exportado y
42 haya validado o confirmado el merge. El directorio `output` solo queda libre cuando B
alcanza un estado terminal.

### 2.4. Recursos globales que siguen siendo seriales

- Solo puede existir un Google Earth controlado, y no se puede modificar
  `~/.googleearth/myplaces.kml` mientras esa captura está en curso.
- Solo una B puede usar `/tmp/matrix`.
- Solo una B puede validar/escribir el mismo destino consolidado con el módulo 42.
- El log maestro actual no es apropiado para dos procesos concurrentes; cada job necesita
  logs propios para evitar líneas mezcladas.
- La captura y B comparten CPU, RAM, tmpfs y almacenamiento del destino. Hay que limitar
  los workers de Gradle/JVM y ajustar `TRACE_PNG_THREADS`, `TRACE_PNG_QUEUE`,
  `TRACE_BZ2_THREADS` y `TRACE_BZ2_QUEUE` con benchmarks, porque una B agresiva puede
  alargar la captura o provocar que la cola del tracer bloquee al productor.

## 3. Modelo de slots y estados

Cada slot contiene `_pipeline/job.json`, escrito primero como `job.json.partial` y
publicado mediante `rename`. Campos mínimos:

```json
{
  "contractVersion": 1,
  "jobId": "spain15-000205-36.5--4.0",
  "sequence": 205,
  "routeCommand": "./run.sh zigzag ...",
  "latitude": 36.5,
  "longitude": -4.0,
  "physicalSlot": "incoming",
  "state": "CAPTURING",
  "createdAt": "2026-08-23T12:00:00+02:00",
  "captureTraceBytes": 0,
  "captureTraceSha256": null
}
```

Estados permitidos:

```text
EMPTY -> CAPTURING -> CAPTURE_READY -> PROCESSING
      \-> CAPTURE_FAILED             \-> COMMITTED
                                      \-> PROCESSING_FAILED
```

Los cambios de estado deben registrar fecha, PID, exit status y último paso completado.
El nombre físico del directorio no identifica al job: después de intercambiar los slots,
el descriptor es la autoridad para recuperación.

Sentinelas separados (`CAPTURE_READY`, `PROCESSING`, `COMMITTED`), creados atómicamente,
pueden facilitar la inspección desde shell, pero no deben contradecir `job.json`. En caso
de contradicción el coordinador se detiene y exige recuperación explícita.

## 4. Coordinador del pipeline

Crear un único coordinador, preferiblemente `scripts/superPipelineController.py`, y un
launcher `runSuperPipeline.sh`. El coordinador debe poseer durante todo el batch:

- un lock global de la pipeline;
- el lock del destino consolidado;
- el lock de `/tmp/matrix` solo mientras B lo usa.

No conviene ejecutar dos copias completas del `runFullProcess.sh` actual: ambas intentarían
limpiar `output`, seleccionar la traza global, usar `/tmp/matrix`, adquirir locks y escribir
el mismo log. En su lugar, refactorizar `runFullProcess.sh` en operaciones reutilizables o
añadir dos modos internos:

- `--stage-a --job-file ... --capture-root /media/ramdisk/output_incoming`;
- `--stage-b --job-file ... --capture-root /media/ramdisk/output`.

El modo secuencial sin esas opciones debe seguir funcionando para facilitar rollback y
comparaciones A/B.

### 4.1. Bucle de ejecución

1. Preflight único: compilar/validar dependencias, comprobar los dos slots, locks,
   destino, display, AT-SPI y presupuesto de tmpfs.
2. Generar una cola estable de jobs desde `runSpain15.sh` o `runWorld.sh`. Guardar un
   `jobId` determinista y la posición de la cola antes de ejecutar nada.
3. Si `output` contiene un `CAPTURE_READY` recuperado de una ejecución anterior, arrancar
   B sobre él. Si no, comenzar llenando `output_incoming` con A del primer job.
4. Mientras B(tile N) trabaja en `output`, ejecutar A(tile N+1) en
   `output_incoming`.
5. Esperar a que ambas hayan llegado a un estado terminal. No hacer handoff anticipado si
   una termina antes: solo hay dos slots y `output` no puede cambiar mientras B lo abre
   por ruta.
6. Registrar de forma durable el resultado de B. Si fue exitoso, exigir la validación del
   commit de 42. Si falló, registrar el tile para reproceso antes de liberar sus datos.
7. Intercambiar los roles de los slots. En Linux/tmpfs se recomienda un pequeño helper
   probado que use `renameat2(..., RENAME_EXCHANGE)` para que `output` nunca quede ausente.
   Tras el intercambio, verificar que `output/_pipeline/job.json` identifica exactamente
   el job `CAPTURE_READY` esperado.
8. Vaciar de forma segura el nuevo `output_incoming`, que contiene el job B ya terminal,
   recrear su descriptor `EMPTY` y lanzar el siguiente A.
9. Al terminar la entrada, drenar la última B sin iniciar otra A.

Si no se usa `RENAME_EXCHANGE`, el fallback debe llevar un journal de promoción y usar
`output.retired.<jobId>` como paso intermedio. La recuperación debe reconocer todos los
estados después de un corte entre renames; nunca debe borrar un directorio cuyo job no
haya sido identificado y marcado terminal.

### 4.2. Adaptación de los scripts masivos

`runSpain15.sh` y `runWorld.sh` no deben seguir siendo largas secuencias que llaman a
`runFullProcess.sh`. Deben actuar como proveedores de jobs:

- emitir o registrar `sequence`, coordenadas y comando exacto de módulo 11;
- delegar ejecución, concurrencia, reintentos y logs al coordinador;
- conservar el orden y `START_FROM_TILE` actuales;
- permitir `--sequential` para comparar contra el flujo probado;
- no ocultar silenciosamente la salida de un fallo ni usar `pkill -9 google-earth` sin
  comprobar el PID administrado. La limpieza debe seguir apuntando al PID exacto conocido
  por 14.

Para `runWorld.sh`, conviene sustituir el fichero generado con miles de bloques repetidos
por un manifest TSV/JSON generado y un iterador común. Esto reduce el riesgo de que el
estado del script y el checkpoint diverjan, pero puede hacerse después de validar el
coordinador con Spain15.

## 5. Cambios por componente

### `01_tracer`

- Añadir directorio físico y directorio lógico configurables en runtime, con los valores
  compilados actuales como fallback.
- Centralizar la construcción de rutas; no dejar usos directos de `OUTPUT_DIRECTORY` en
  cada exportador.
- Garantizar que la salida normal del proceso espera el vaciado y `join` de los pools PNG
  y bzip2. A no puede publicar `CAPTURE_READY` mientras exista un job asíncrono pendiente.
- Publicar temporales con nombre parcial y rename para PNG/blobs donde aún no se haga.
- Añadir un diagnóstico final con jobs encolados/completados/fallidos.

### `12_fileSystemChangesDetector` y `13_googleEarthController`

- Hacer explícita la raíz a observar mediante argumento o variable de entorno; el
  fallback sigue siendo `/media/ramdisk/output`.
- En superpipeline, 13 debe lanzar 12 apuntando a `output_incoming` y esperar su `ready`.
- Mantener el protocolo actual real (`ready`, `activity`, `exit`) y actualizar la sección
  desactualizada de `doc/README.md` que todavía documenta `Updated at ...`.

### `14_sessionController`

- Aceptar/propagar la raíz de captura y validar `output_incoming`.
- Separar “Google Earth terminó” de “artefactos capturados publicados”. La segunda
  condición incluye pools drenados, traza copiada y validaciones del slot.
- Mantener el cierre dirigido al PID exacto y los códigos de salida actuales.

### `runFullProcess.sh`

- Extraer preflight común, A, B y validaciones a funciones/modos invocables.
- Parametrizar `CAPTURE_ROOT`, `TRACE_DUMP_DIR`, `MATRIX_DIR`, log y job descriptor.
- En B seleccionar `output/_pipeline/capture.trace`, no el glob de `/opt`.
- Mantener todas las validaciones actuales: 320 strips y apariencias, export completo,
  colocación de todas las matrices, dry-run de 42 y validación del commit.
- Mantener `--reuse-capture`, pero exigir que el job descriptor identifique el dataset.

### Módulos `22`, `23`, `31` y `32`

Inicialmente pueden seguir usando `/media/ramdisk/output`, ya que B conserva ese nombre.
No es necesario cambiar sus contratos. Como mejora de testabilidad, aceptar
`PIPELINE_OUTPUT_DIRECTORY` con precedencia sobre `application.properties`, conservando
el default actual. Esto permitirá pruebas con slots temporales sin editar recursos del
proyecto.

### Módulo `42`

Mantener la exclusión mutua sobre el destino. La validación dry-run y el commit del mismo
job forman una transacción lógica: no intercalar otro merge entre ambos. Guardar en el
job el recuento de tiles y el mensaje `Merge completed.` antes de marcar `COMMITTED`.

## 6. Gestión de RAMDISK y admisión de una nueva captura

La capacidad actual no ofrece un margen ilimitado. En la observación del 23 de agosto,
`output` ocupa aproximadamente 34 GiB y la traza aproximadamente 6 GiB; dos capturas de
ese tamaño, la traza copiada y el `bigtrace.log` temporal caben en 110 GiB, pero dos casos
de 50 GiB dejarían poco espacio para la traza, el dump, colas y temporales.

Antes de iniciar A se debe comprobar simultáneamente:

- bytes e inodos libres del tmpfs;
- tamaño actual de `output`;
- estimación conservadora de crecimiento de A basada en el percentil 95 de las últimas
  capturas equivalentes;
- reserva para `capture.trace`, `bigtrace.log.partial` y publicaciones atómicas;
- una reserva fija de emergencia configurable, inicialmente no inferior a 10 GiB.

La condición debe ser algo equivalente a:

```text
freeBytes >= estimatedIncomingP95 + estimatedTraceAndDumpP95 + emergencyReserve
```

Si no se cumple, no se inicia A; B continúa sola hasta borrar `capture.trace`/`bigtrace`
o terminar. Esto degrada de forma segura al pipeline secuencial. No se debe confiar solo
en que “cada output suele ser menor de 50 GB”. Registrar el máximo de bytes usados por
slot y del tmpfs en cada job permitirá ajustar la fórmula.

## 7. Fallos, reanudación y `errors.log`

Reemplazar el texto libre como fuente primaria por un ledger append-only, por ejemplo
`errors.jsonl`; conservar `errors.log` como vista humana compatible. Cada registro debe
incluir `jobId`, secuencia, coordenadas, ruta, etapa, paso, exit status, fecha, destino,
log y si el slot fue preservado o reciclado.

Política recomendada:

- **Fallo de A:** registrar, limpiar solo el `output_incoming` cuyo descriptor coincide
  con ese job y continuar con el siguiente. B, si existe, no se interrumpe.
- **Fallo de B anterior mientras A termina bien:** registrar primero el fallo de B. Por
  defecto, acorde con el batch actual, permitir reciclar sus datos después de sincronizar
  el ledger, promover la A lista y seguir. Una opción `--keep-failed-capture` debe detener
  el pipeline y conservar `output` para diagnóstico.
- **Fallo de commit de 42:** nunca marcar el job como completado. Validar el destino y
  detenerse si no se puede demostrar si el merge quedó completo; no reintentar a ciegas.
- **SIGINT/SIGTERM:** dejar de admitir jobs, terminar ordenadamente el proceso manejado,
  esperar o cancelar A/B con sus PIDs exactos, escribir estados y no hacer handoff.
- **Reinicio:** inspeccionar locks, PIDs, descriptores y sentinelas. Reanudar B desde
  `CAPTURE_READY` es válido; una `CAPTURING` sin proceso vivo se marca fallida. Un
  `PROCESSING` interrumpido se reprocesa desde el principio después de limpiar solo
  `/tmp/matrix`, porque 22/23 modifican el slot en sitio.

La clave de deduplicación del ledger debe ser `jobId + stage + attempt`, y la lista de
reproceso debe poder alimentar el mismo coordinador sin editar a mano los scripts masivos.

## 8. Observabilidad y benchmark

Por job, escribir en un directorio durable fuera de los slots reciclables:

```text
logs/superPipeline/<jobId>/
  job.json
  stage-a.log
  apitrace-dump.log
  21.log ... 42-commit.log
  metrics.json
```

`metrics.json` debe contener inicio/fin y duración de cada módulo, tiempo esperando el
handoff, tiempo bloqueado por espacio, pico del tmpfs, tamaños de output/traza/dump,
resultado y recuentos de tiles. Mantener además los mensajes de progreso parseables ya
existentes.

Benchmark mínimo:

1. Ejecutar 10–20 tiles representativos secuencialmente para obtener `T_A`, `T_B` y sus
   subpasos.
2. Ejecutar exactamente los mismos tiles con dos etapas.
3. Comparar hashes/quadkeys del delta y el resultado de 42; el resultado debe ser idéntico
   al secuencial.
4. Medir cadencia desde el comienzo de un A hasta el comienzo del siguiente, no solo la
   latencia individual.
5. Repetir con límites de workers distintos y elegir la configuración que minimiza
   `max(T_A,T_B)` sin aumentar fallos ni presión de memoria.

Métricas de aceptación de la primera entrega:

- ningún acceso de A bajo `output` y ninguno de B bajo `output_incoming`;
- cero manifests con rutas inexistentes tras el handoff;
- máximo una A, una B y un commit 42 concurrentes;
- resultados byte/quadkey-equivalentes al modo secuencial;
- recuperación probada después de cortar A, B y cada lado del handoff;
- ninguna pérdida de un job fallido antes de quedar en el ledger;
- mejora demostrable de throughput frente al baseline, sin prometer aún 20 minutos.

## 9. Orden de implementación recomendado

### Fase 0: baseline y pruebas de contrato

- Añadir extracción automática de tiempos del log y medir tamaños máximos.
- Crear fixtures pequeños para manifests y dos slots temporales.
- Congelar mediante tests el resultado secuencial de varios tiles conocidos.

### Fase 1: parametrización sin concurrencia

- Implementar los dos directorios del tracer y la raíz configurable de 12/13/14.
- Hacer que una ejecución secuencial capture en `output_incoming`, promocione y procese
  en `output`.
- Verificar manifests, traza privada y equivalencia completa. Mantener este modo como
  fallback operativo.

### Fase 2: slots, estados y recuperación

- Implementar descriptores, sentinelas, locks, admisión por espacio y handoff.
- Añadir pruebas de crash en cada transición y validación estricta de targets antes de
  vaciar un directorio.

### Fase 3: solapamiento de dos etapas

- Lanzar A(N+1) y B(N), propagar códigos de salida y drenar al final.
- Migrar primero `runSpain15.sh`; tras varias decenas de jobs estables, migrar
  `runWorld.sh`.
- Conservar una opción inmediata para volver a `--sequential`.

### Fase 4: objetivo inferior a 20 minutos

- Perfilar el módulo 32 separando escaneo de referencia, hashing/decodificación,
  resolución de anchors, copia de PNG y render/export.
- Evitar reindexar la pirámide consolidada completa en cada tile mediante una caché
  persistente invalidada por cambios de 42 o un índice incremental mantenido al hacer el
  merge.
- Revisar si el modo usado por automatización hace trabajo de render/diagnóstico que no
  necesita `--export`.
- Paralelizar únicamente los subpasos demostrados como CPU-bound y mantener acotadas las
  colas de I/O.
- Aceptar el objetivo de 20 minutos solo cuando el percentil 95 de **B completa** sea
  inferior a 20 minutos en una corrida larga; la mediana aislada no es suficiente.

## 10. Pruebas que debe incluir la implementación

- Unitarias de resolución físico/lógica de rutas del tracer y serialización de
  `manifest.txt`.
- Integración tracer simulado: escribir en incoming, publicar, intercambiar y abrir cada
  `file=` desde output.
- Validación de que no quedan `.partial`, `.bin` pendientes ni workers activos al marcar
  `CAPTURE_READY`.
- Traza privada: la captura siguiente puede limpiar `/opt/google/earth/pro` mientras B
  vuelca la traza anterior.
- Handoff con ambos órdenes de terminación: A primero y B primero.
- Espacio insuficiente: A espera y B progresa, sin llenar el tmpfs.
- Fallos inyectados en captura, dump, módulos 22/23/31/32, dry-run 42, commit 42 y cada
  punto de rename.
- Reinicio desde `CAPTURE_READY`, `PROCESSING` interrumpido y promoción parcialmente
  journalizada.
- Dos coordinadores: el segundo debe fallar por lock sin modificar datos.
- Regresión end-to-end secuencial frente a superpipeline comparando número de tiles,
  SHA-256 por quadkey, validaciones TOP y resultado final de 42.

Este orden permite obtener primero una promoción segura y compatible, después introducir
concurrencia y solo entonces atacar el cuello de botella que determina si la meta de 20
minutos es alcanzable.
