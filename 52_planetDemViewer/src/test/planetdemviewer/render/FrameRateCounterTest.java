package planetdemviewer.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FrameRateCounterTest {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void reportsNothingUntilTheFirstWindowEnds() {
        FrameRateCounter counter = new FrameRateCounter(0L);
        counter.frameRendered(SECOND / 2);
        assertEquals(0, counter.framesPerSecond());
    }

    @Test
    void dividesTheFramesByTheRealWindowLength() {
        FrameRateCounter counter = new FrameRateCounter(0L);
        for (int frame = 1; frame <= 29; frame++) {
            counter.frameRendered(frame * SECOND / 40);
        }
        counter.frameRendered(2 * SECOND);
        assertEquals(15, counter.framesPerSecond());
    }

    @Test
    void eachWindowStartsAgainFromZeroFrames() {
        FrameRateCounter counter = new FrameRateCounter(0L);
        counter.frameRendered(SECOND);
        counter.frameRendered(SECOND + SECOND / 2);
        counter.frameRendered(2 * SECOND);
        assertEquals(2, counter.framesPerSecond());
    }
}
