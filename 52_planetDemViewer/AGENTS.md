# Agent guidelines for Planet DEM Viewer

Read `README.md` first, in particular
[Graphics backend layering](README.md#graphics-backend-layering) and
[Bibliography](README.md#bibliography). They explain why the rules below exist.
For the larger design, see `doc/ArchitecturePlan.md`.

## Keep the graphics backend thin

`src/main/planetdemviewer/render/jogl4` only controls the GPU. When you change
rendering:

1. Every method in `render/jogl4` must depend, directly or indirectly, on OpenGL.
   If a method would still make sense under Vulkan or Metal, move it to `render`
   and have the backend delegate to it.
2. No input/output in `render/jogl4`: no files, paths, logging or console output,
   and no calls to the tile loaders. The backend returns a captured image as a
   Vitral image, and the application saves it (`ViewerSnapshotWriter`).
3. No decisions or bookkeeping in `render/jogl4`: LOD selection, cache and budget
   policies, GPU usage counters, picking, geometry, text content and layout,
   materials and viewer state all belong in `render`, even when they describe the
   GPU: they are part of the render model, and other backends reuse them. The backend receives their results as plain data.
4. No graphics API in `render`: no `com.jogamp` or `render.jogl4` imports. Hide
   backend handles behind a type parameter, as `PaletteFramePlanner<T>` does, and
   put GPU operations behind an interface, as `TextureUploader<T>` does.
5. The application talks to `PlanetViewerSession` for anything that is not about
   creating or drawing on a GL surface.
6. Keep `RenderBackendBoundaryTest` passing. Do not weaken it to make a change
   fit; move the code instead.

## Images

Use Vitral's image classes (`RGBAImageUncompressed`, `ImagePersistence`), never
`BufferedImage` or `ImageIO`. Their convention is the one OpenGL uses: raw pixels go
from the bottom row to the top one, so a raw buffer can be uploaded as it is and
`v = 0` is the bottom of the image. Pixel coordinates start on the top row, so
`getPixel(x, 0)` is on the top row.

- `setRawImage` copies pixels in, and `getRawImage` copies them out.
- `getRawImageDirectBuffer` gives direct access, without a copy.
- `getRawImageSizeInBytes` is the size of the pixels alone.

If Vitral lacks something or has a defect, fix it in its own repository
(`/home/jedilink/VITRAL/vitral`, with `./gradlew :base:publishToMavenLocal` from its
`java` folder) instead of working around it here.

## Naming and layout

- A class with JOGL dependencies is named `Jogl4<Name>Renderer`, or
  `Jogl4<Name>Helper` when it supports a renderer, and lives in `render/jogl4`.
- A class without JOGL dependencies has a plain name, without that prefix.
- A class that depends on AWT for rendering work is named `Awt<Name>Helper` and lives
  in `gui/awt`. An interface with a plain name keeps `render` unaware of it, and the
  application injects it, as with `TextRasterizer` and `AwtTextRasterizerHelper`. Use
  one only when Vitral has no alternative.
- `src/test/planetdemviewer` mirrors `src/main/planetdemviewer`. When a class
  moves, its test moves with it. Test extracted logic in `render` without a GL
  context.

## Use the bibliography

The papers in `doc/references` at the repository root (`../doc/references` from
this project) guide the implementation. Before implementing or changing one of
their algorithms, read the relevant part of the paper.

Name code after the concepts in the literature, so a reader who knows the paper
can recognize them. If a name has to differ from the paper's term, or the paper's
term is misleading, say so in a comment.

### Citation keys

Write a citation as `[AUTHYYYYs]`:

- `AUTH`: the first four letters of the first author's surname, in capitals;
- `YYYY`: the year of publication;
- `s`: an optional lowercase letter that tells apart works with the same author
  and year.

The key must match the prefix of the PDF in `doc/references`. For example,
`[MILL1986a]` is `[MILL1986a]_TheDefinitionAndRenderingOfTerrainMaps.pdf`.

### Where citations go

List each work a file relies on in a `References:` block in its header or class
comment, with its full citation:

```java
/**
 * Diamond-square subdivision as a recursive walk over a tile's quadrants.
 *
 * References:
 * - [MILL1986a] Miller, G. S. P. "The Definition and Rendering of Terrain Maps",
 *   SIGGRAPH 1986
 *
 * This class implements the triangulation of figure [MILL1986a].2 at the DEM
 * spacing. Subdividing below that spacing is not implemented.
 */
```

Then cite the exact place next to the code that implements it, with these forms:

| Cited item | Form |
| --- | --- |
| Equation | `// Equation [TUMB1999b](17): exponent gw/gd` |
| Section | `// Section [ARVO1995b].2: square-to-sphere mapping` |
| Figure | `// See figure [HAIN1991].2` |
| Book section | `// See [COHE1993] section 5.3.2` |
| Whole work | `// Static adaptation after [TUMB1999b]` |

### Citation rules

- Cite specific algorithms, formulas and constants taken from a work. Ordinary
  code needs no citation.
- Say what the code takes from the work in the code's own terms, not just the
  key, and give the equation, section or figure whenever one applies.
- State which parts of a cited algorithm are not implemented, or where the code
  departs from it.
- Check every equation, section and figure number against the PDF. Never cite
  from memory, and never cite a work you have not read.
- If you rely on a work that is not in `doc/references`, give its full citation
  and tell the user so that it can be added.

## Verify changes

Run `gradle test`. For rendering changes, also run the real program, and compare
captures from before and after when the change should not alter the image.

### Test data

These folders may be used for agent tests when they are available. Check that they
exist first, and if they are not mounted, say that the test was not run:

| Folder | Content |
| --- | --- |
| `/media/extra/FABDEM/02_rawPyramidal/` | Elevation (DEM) pyramid |
| `/samples/datasets/googleEarth/toplevel/` | Color imagery pyramid |

```bash
DISPLAY=:1 ./run.sh /media/extra/FABDEM/02_rawPyramidal/ /samples/datasets/googleEarth/toplevel/
```

Treat both folders as read-only:

- Never press `Delete` in the viewer. It permanently deletes imagery PNG files
  from the real dataset.
- Do not run processing tools on them unless the user asks you to.

### Driving the viewer

- Prefer the MCP tools listed in the README. `run.sh` serves them on port 42817.
  Close the viewer with `key_event` and key code 27.
- Before sending operating-system input such as a synthetic click, confirm that
  the viewer started and is the active window. Otherwise the input reaches
  whatever other application the user has open.
- `state.json` is user state, and the viewer restores it at startup. Do not leave
  it changed after a test.
