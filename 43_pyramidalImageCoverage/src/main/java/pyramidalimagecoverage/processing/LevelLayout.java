package pyramidalimagecoverage.processing;

import pyramidalimagecoverage.model.PixelSize;
import pyramidalimagecoverage.model.RenderMode;
import pyramidalimagecoverage.model.TileBounds;

public record LevelLayout(
    RenderMode mode,
    int matrixSide,
    TileBounds visibleTiles,
    int contentWidth,
    int contentHeight,
    int pixelsPerTile,
    int imagePixelsPerTile,
    boolean scrollable,
    boolean focused
) {
    public static final int FOCUSED_VIEW_MINIMUM_DEPTH = 10;
    private static final int[] IMAGE_SIDES = {256, 128, 64, 32, 16, 8, 4, 2};

    public static LevelLayout choose(int depth, PixelSize viewport) {
        int matrixSide = 1 << Math.min(depth, 30);
        return chooseForBounds(matrixSide, fullMatrixBounds(matrixSide), viewport, false);
    }

    public static LevelLayout choose(int depth, PixelSize viewport, TileBounds availableTiles) {
        int matrixSide = 1 << Math.min(depth, 30);
        if (depth < FOCUSED_VIEW_MINIMUM_DEPTH || availableTiles == null) {
            return chooseForBounds(matrixSide, fullMatrixBounds(matrixSide), viewport, false);
        }
        return chooseForBounds(matrixSide, availableTiles, viewport, true);
    }

    private static LevelLayout chooseForBounds(
        int matrixSide,
        TileBounds visibleTiles,
        PixelSize viewport,
        boolean focused
    ) {
        int columns = visibleTiles.columnCount();
        int rows = visibleTiles.rowCount();
        for (int imageSide : IMAGE_SIDES) {
            int footprint = imageSide + 2;
            if (fits(columns, rows, footprint, viewport)) {
                RenderMode mode = imageSide == 256 ? RenderMode.NATIVE : RenderMode.SCALED;
                return create(mode, matrixSide, visibleTiles, footprint, imageSide, false, focused);
            }
        }
        boolean scrollable = !fits(columns, rows, 1, viewport);
        return create(RenderMode.COVERAGE, matrixSide, visibleTiles, 1, 1, scrollable, focused);
    }

    public String description() {
        if (mode == RenderMode.COVERAGE) {
            return "Coverage tiles (1 x 1 px)";
        }
        String prefix = mode == RenderMode.NATIVE ? "Native" : "Scaled";
        return prefix + " tiles (" + imagePixelsPerTile + " x " + imagePixelsPerTile
            + " px + 1 px border)";
    }

    private static LevelLayout create(
        RenderMode mode,
        int matrixSide,
        TileBounds visibleTiles,
        int pixelsPerTile,
        int imagePixelsPerTile,
        boolean scrollable,
        boolean focused
    ) {
        int width = boundedPixels(visibleTiles.columnCount(), pixelsPerTile);
        int height = boundedPixels(visibleTiles.rowCount(), pixelsPerTile);
        return new LevelLayout(
            mode, matrixSide, visibleTiles, width, height,
            pixelsPerTile, imagePixelsPerTile, scrollable, focused
        );
    }

    private static boolean fits(int columns, int rows, int pixelsPerTile, PixelSize viewport) {
        return (long) columns * pixelsPerTile <= viewport.width()
            && (long) rows * pixelsPerTile <= viewport.height();
    }

    private static int boundedPixels(int tiles, int pixelsPerTile) {
        return (int) Math.min(Integer.MAX_VALUE - 8L, (long) tiles * pixelsPerTile);
    }

    private static TileBounds fullMatrixBounds(int matrixSide) {
        return new TileBounds(0, 0, matrixSide - 1, matrixSide - 1);
    }
}
