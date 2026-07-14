package pyramidalimagecoverage.processing;

import pyramidalimagecoverage.model.PyramidCatalog;
import pyramidalimagecoverage.model.TileRecord;

public final class TileSourceResolver {
    private static final int TILE_SIZE = 256;

    private final PyramidCatalog catalog;

    public TileSourceResolver(PyramidCatalog catalog) {
        this.catalog = catalog;
    }

    public SourceRegion resolve(int targetDepth, int column, int southRow, int outputPixels) {
        if (outputPixels < 1 || outputPixels > TILE_SIZE || Integer.bitCount(outputPixels) != 1) {
            throw new IllegalArgumentException("Output tile side must be a power of two from 1 to 256");
        }
        int descentRepresentedBySource = Integer.numberOfTrailingZeros(TILE_SIZE / outputPixels);
        int desiredSourceDepth = Math.max(0, targetDepth - descentRepresentedBySource);
        TileRecord source = catalog.nearestAncestorAtOrAbove(
            desiredSourceDepth, targetDepth, column, southRow
        );
        if (source == null) {
            return null;
        }

        int delta = targetDepth - source.address().depth();
        long subdivisions = 1L << Math.min(delta, 30);
        int localColumn = column - (source.address().column() << delta);
        int localSouthRow = southRow - (source.address().southRow() << delta);
        int x0 = (int) ((long) localColumn * TILE_SIZE / subdivisions);
        int x1 = (int) ((long) (localColumn + 1) * TILE_SIZE / subdivisions);
        int y0 = TILE_SIZE - (int) ((long) (localSouthRow + 1) * TILE_SIZE / subdivisions);
        int y1 = TILE_SIZE - (int) ((long) localSouthRow * TILE_SIZE / subdivisions);

        // At unsupported extreme depths integer source regions collapse; sample one pixel.
        if (x1 <= x0) x1 = Math.min(TILE_SIZE, x0 + 1);
        if (y1 <= y0) y1 = Math.min(TILE_SIZE, y0 + 1);
        return new SourceRegion(source, x0, y0, x1, y1);
    }
}
