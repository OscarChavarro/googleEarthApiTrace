package demresampler.processing;

import demresampler.io.RawTileIO;

public final class TargetLevelSelector {
    private TargetLevelSelector() {
    }

    public static int finestLevelWithoutUpsampling(double sourcePixelDegrees) {
        if (!Double.isFinite(sourcePixelDegrees) || sourcePixelDegrees <= 0.0) {
            throw new IllegalArgumentException("Source pixel resolution must be positive");
        }
        double exactLevel = Math.log(360.0 / (RawTileIO.CORE_SIDE * sourcePixelDegrees))
            / Math.log(2.0);
        int level = (int) Math.floor(exactLevel + 1e-12);
        if (level < 0 || level > 30) {
            throw new IllegalArgumentException("Derived quadtree level is outside [0, 30]: " + exactLevel);
        }
        return level;
    }

    public static double pixelDegrees(int level) {
        return 360.0 / ((1L << level) * RawTileIO.CORE_SIDE);
    }
}
