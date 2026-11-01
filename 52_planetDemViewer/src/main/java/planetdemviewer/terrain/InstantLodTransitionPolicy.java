package planetdemviewer.terrain;

/** Preserves the viewer's current LOD behavior: replacements complete immediately. */
public final class InstantLodTransitionPolicy implements LodTransitionPolicy {
    @Override
    public LodTransition begin(LodReplacement replacement, FrameTime frameTime) {
        return new LodTransition(replacement, frameTime.monotonicNanos(), 0.0, TransitionMode.INSTANT);
    }

    @Override
    public double progress(LodTransition transition, FrameTime frameTime) {
        return 1.0;
    }
}
