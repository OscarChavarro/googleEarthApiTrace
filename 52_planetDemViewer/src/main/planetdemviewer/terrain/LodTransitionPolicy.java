package planetdemviewer.terrain;

/**
 * Defines the temporal presentation of an already-decided LOD replacement.
 * Planning owns this state; a renderer only consumes its immutable result.
 */
public interface LodTransitionPolicy {
    LodTransition begin(LodReplacement replacement, FrameTime frameTime);

    /** Returns a clamped transition progress in the inclusive range [0, 1]. */
    double progress(LodTransition transition, FrameTime frameTime);
}
