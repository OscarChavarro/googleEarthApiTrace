package frametexturenormalizer.animation;

import java.util.List;
import org.junit.jupiter.api.Test;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.state.FrameTextureNormalizerState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AnimationControllerTest {
    @Test
    void forwardAnimationAdvancesUntilLastFrameAndStops() {
        FrameTextureNormalizerState model = stateWithFrames(10, 11, 12);
        int[] repaintCount = {0};
        AnimationController controller = new AnimationController(model, () -> repaintCount[0]++, 1_000);

        controller.toggleForwardAnimation();

        assertEquals(1, model.getSelectedFrameIndex());
        assertTrue(controller.isAnimatingForward());

        controller.advanceAnimationStep();

        assertEquals(2, model.getSelectedFrameIndex());
        assertFalse(controller.isRunning());
        assertEquals(2, repaintCount[0]);
    }

    @Test
    void backwardAnimationAdvancesUntilFirstFrameAndStops() {
        FrameTextureNormalizerState model = stateWithFrames(20, 21, 22);
        model.selectNextFrame();
        model.selectNextFrame();
        int[] repaintCount = {0};
        AnimationController controller = new AnimationController(model, () -> repaintCount[0]++, 1_000);

        controller.toggleBackwardAnimation();

        assertEquals(1, model.getSelectedFrameIndex());
        assertTrue(controller.isAnimatingBackward());

        controller.advanceAnimationStep();

        assertEquals(0, model.getSelectedFrameIndex());
        assertFalse(controller.isRunning());
        assertEquals(2, repaintCount[0]);
    }

    @Test
    void togglingSameDirectionStopsAnimation() {
        FrameTextureNormalizerState model = stateWithFrames(30, 31, 32);
        AnimationController controller = new AnimationController(model, () -> {}, 1_000);

        controller.toggleForwardAnimation();
        assertTrue(controller.isRunning());

        controller.toggleForwardAnimation();
        assertFalse(controller.isRunning());
        assertEquals(1, model.getSelectedFrameIndex());
    }

    private static FrameTextureNormalizerState stateWithFrames(int... frameIds) {
        FrameTextureNormalizerState model = new FrameTextureNormalizerState();
        model.setFrames(List.of(
            java.util.Arrays.stream(frameIds)
                .mapToObj(AnimationControllerTest::frame)
                .toArray(FrameData[]::new)
        ));
        return model;
    }

    private static FrameData frame(int id) {
        return new FrameData(id, List.of(), null);
    }
}
