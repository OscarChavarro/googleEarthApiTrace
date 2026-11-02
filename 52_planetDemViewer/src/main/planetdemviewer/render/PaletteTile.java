package planetdemviewer.render;

/** The texture to show on a tile, and the part of it that covers the tile: {left, bottom, right, top} in 0..1. */
public record PaletteTile<T>(T texture, double[] subRect) {
}
