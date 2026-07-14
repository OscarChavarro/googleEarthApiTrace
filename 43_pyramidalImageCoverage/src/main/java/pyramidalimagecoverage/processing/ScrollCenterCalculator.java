package pyramidalimagecoverage.processing;

import pyramidalimagecoverage.model.PixelSize;
import pyramidalimagecoverage.model.TileBounds;
import pyramidalimagecoverage.model.ViewPosition;

public final class ScrollCenterCalculator {
    private ScrollCenterCalculator() {
    }

    public static ViewPosition viewPosition(
        TileBounds activeTiles,
        int matrixSide,
        int pixelsPerTile,
        PixelSize viewport,
        PixelSize content
    ) {
        long centerX = ((long) activeTiles.minimumColumn() + activeTiles.maximumColumn() + 1L)
            * pixelsPerTile / 2L;
        long minimumNorthRow = (long) matrixSide - 1L - activeTiles.maximumSouthRow();
        long maximumNorthRow = (long) matrixSide - 1L - activeTiles.minimumSouthRow();
        long centerY = (minimumNorthRow + maximumNorthRow + 1L) * pixelsPerTile / 2L;
        int x = clamp(centerX - viewport.width() / 2L, 0L, Math.max(0L, (long) content.width() - viewport.width()));
        int y = clamp(centerY - viewport.height() / 2L, 0L, Math.max(0L, (long) content.height() - viewport.height()));
        return new ViewPosition(x, y);
    }

    private static int clamp(long value, long minimum, long maximum) {
        return (int) Math.max(minimum, Math.min(maximum, value));
    }
}
