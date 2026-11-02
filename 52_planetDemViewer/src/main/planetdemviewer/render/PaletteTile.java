package planetdemviewer.render;

/** The texture to show on a tile, and the triangle strip that shows it, as in {@link TileQuadGeometry#texturedStrip}. */
public record PaletteTile<T>(T texture, float[] vertices) {
}
