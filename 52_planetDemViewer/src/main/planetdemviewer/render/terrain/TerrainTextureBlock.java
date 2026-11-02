package planetdemviewer.render.terrain;

import planetdemviewer.spatial.TileAddress;

/**
 * One draw of a part of a tile's mesh: its range of indices and its texture, which is null when
 * it shows no color. The source is the imagery tile that texture holds, null without a texture,
 * which only the application reads: a backend does not need it to draw. The mapping is what the texture matrices of section [LERB2010].4.2 do:
 * u' = u * textureScale + offsetU, and the same for v, from the tile's own texture coordinates.
 *
 * <p>References:</p>
 * <ul>
 * <li>[LERB2010] Lerbour, R., Marvie, J.-E. and Gautron, P. "Adaptive Real-Time Rendering of
 * Planetary Terrains", WSCG 2010.</li>
 * </ul>
 */
public record TerrainTextureBlock<T>(T texture, TileAddress source, float textureScale, float offsetU, float offsetV,
                                     int firstIndex, int indexCount) {
    /** The tile's own image, or none at all. */
    static <T> TerrainTextureBlock<T> unmapped(T texture, TileAddress source, int firstIndex, int indexCount) {
        return new TerrainTextureBlock<>(texture, source, 1.0f, 0.0f, 0.0f, firstIndex, indexCount);
    }

    /** The image of any block of the same quadtree: an ancestor of the tile, or a descendant. */
    static <T> TerrainTextureBlock<T> mapped(T texture, TileAddress tile, TileAddress source,
                                             int firstIndex, int indexCount) {
        return new TerrainTextureBlock<>(texture, source, (float) (tile.size() / source.size()),
            (float) ((tile.x0() - source.x0()) / source.size()),
            (float) ((tile.y0() - source.y0()) / source.size()), firstIndex, indexCount);
    }
}
