package planetdemviewer.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class InstantLodTransitionPolicyTest {
    @Test
    void preservesCurrentImmediateReplacementBehavior() {
        InstantLodTransitionPolicy policy = new InstantLodTransitionPolicy();
        LodTransition transition = policy.begin(new LodReplacement("parent", "children"), new FrameTime(10L));

        assertEquals(TransitionMode.INSTANT, transition.mode());
        assertEquals(1.0, policy.progress(transition, new FrameTime(10L)));
        assertEquals(1.0, policy.progress(transition, new FrameTime(1_000L)));
    }
}
