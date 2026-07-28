package demresampler.processing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetLevelSelectorTest {
    @Test
    void selectsLevelTwelveForOneArcSecondFabdem() {
        int level = TargetLevelSelector.finestLevelWithoutUpsampling(1.0 / 3600.0);
        assertEquals(12, level);
        assertTrue(TargetLevelSelector.pixelDegrees(level) >= 1.0 / 3600.0);
        assertTrue(TargetLevelSelector.pixelDegrees(level + 1) < 1.0 / 3600.0);
    }
}
