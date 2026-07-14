package pyramidalimagecoverage.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import pyramidalimagecoverage.model.PixelSize;
import pyramidalimagecoverage.model.RenderMode;

class LevelLayoutTest {
    private final PixelSize fullHd = new PixelSize(1920, 1080);

    @Test
    void usesNativeTilesOnlyWhenWholeSquareMatrixFits() {
        assertEquals(RenderMode.NATIVE, LevelLayout.choose(2, fullHd).mode());
        assertEquals(256, LevelLayout.choose(2, fullHd).imagePixelsPerTile());
        assertEquals(RenderMode.SCALED, LevelLayout.choose(3, fullHd).mode());
        assertEquals(128, LevelLayout.choose(3, fullHd).imagePixelsPerTile());
    }

    @Test
    void progressivelyUsesEveryPowerOfTwoAndThenCoverage() {
        int matrixSide = 8;
        for (int imageSide : new int[] {256, 128, 64, 32, 16, 8, 4, 2}) {
            int availableSide = matrixSide * (imageSide + 2);
            LevelLayout layout = LevelLayout.choose(3, new PixelSize(availableSide, availableSide));
            assertEquals(imageSide, layout.imagePixelsPerTile());
        }
        assertEquals(1, LevelLayout.choose(9, fullHd).imagePixelsPerTile());

        LevelLayout fittingCoverage = LevelLayout.choose(10, fullHd);
        assertEquals(RenderMode.COVERAGE, fittingCoverage.mode());
        assertFalse(fittingCoverage.scrollable());

        LevelLayout scrollingCoverage = LevelLayout.choose(11, fullHd);
        assertEquals(RenderMode.COVERAGE, scrollingCoverage.mode());
        assertTrue(scrollingCoverage.scrollable());
    }

    @Test
    void choosesFourPixelTilesWhenThatIsTheLargestFit() {
        PixelSize viewport = new PixelSize(1536, 1536);
        LevelLayout layout = LevelLayout.choose(8, viewport);
        assertEquals(RenderMode.SCALED, layout.mode());
        assertEquals(4, layout.imagePixelsPerTile());
        assertEquals(6, layout.pixelsPerTile());
    }
}
