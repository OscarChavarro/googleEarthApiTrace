package pyramidalimagecoverage.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import pyramidalimagecoverage.model.PixelSize;
import pyramidalimagecoverage.model.TileBounds;
import pyramidalimagecoverage.model.ViewPosition;

class ScrollCenterCalculatorTest {
    @Test
    void centersViewportOnSparseActiveTileBounds() {
        ViewPosition position = ScrollCenterCalculator.viewPosition(
            new TileBounds(1200, 800, 1299, 899),
            2048,
            1,
            new PixelSize(1000, 600),
            new PixelSize(2048, 2048)
        );
        assertEquals(new ViewPosition(750, 898), position);
    }

    @Test
    void clampsPositionAtContentEdges() {
        ViewPosition position = ScrollCenterCalculator.viewPosition(
            new TileBounds(0, 2047, 1, 2047),
            2048,
            1,
            new PixelSize(1000, 600),
            new PixelSize(2048, 2048)
        );
        assertEquals(new ViewPosition(0, 0), position);
    }
}
