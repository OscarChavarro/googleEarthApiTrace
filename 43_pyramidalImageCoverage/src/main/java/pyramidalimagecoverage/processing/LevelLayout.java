package pyramidalimagecoverage.processing;

import pyramidalimagecoverage.model.PixelSize;
import pyramidalimagecoverage.model.RenderMode;

public record LevelLayout(
    RenderMode mode,
    int matrixSide,
    int contentSide,
    int pixelsPerTile,
    int imagePixelsPerTile,
    boolean scrollable
) {
    private static final int[] IMAGE_SIDES = {256, 128, 64, 32, 16, 8, 4, 2};

    public static LevelLayout choose(int depth, PixelSize viewport) {
        int matrixSide = 1 << Math.min(depth, 30);
        for (int imageSide : IMAGE_SIDES) {
            int footprint = imageSide + 2;
            if (fits(matrixSide, footprint, viewport)) {
                RenderMode mode = imageSide == 256 ? RenderMode.NATIVE : RenderMode.SCALED;
                return create(mode, matrixSide, footprint, imageSide, false);
            }
        }
        boolean scrollable = !fits(matrixSide, 1, viewport);
        return create(RenderMode.COVERAGE, matrixSide, 1, 1, scrollable);
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
        int pixelsPerTile,
        int imagePixelsPerTile,
        boolean scrollable
    ) {
        long side = (long) matrixSide * pixelsPerTile;
        int boundedSide = (int) Math.min(Integer.MAX_VALUE - 8L, side);
        return new LevelLayout(mode, matrixSide, boundedSide, pixelsPerTile, imagePixelsPerTile, scrollable);
    }

    private static boolean fits(int matrixSide, int pixelsPerTile, PixelSize viewport) {
        long side = (long) matrixSide * pixelsPerTile;
        return side <= viewport.width() && side <= viewport.height();
    }
}
