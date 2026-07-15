package frametexturenormalizer.animation;

import javax.swing.Timer;
import frametexturenormalizer.model.state.FrameTextureNormalizerState;

public final class AnimationController {
    private static final int ANIMATION_DELAY_MILLIS = 10;

    private final FrameTextureNormalizerState model;
    private final Runnable repaintAction;
    private final Timer timer;
    private Direction direction = Direction.STOPPED;

    private enum Direction {
        STOPPED,
        FORWARD,
        BACKWARD
    }

    public AnimationController(FrameTextureNormalizerState model, Runnable repaintAction) {
        this(model, repaintAction, ANIMATION_DELAY_MILLIS);
    }

    AnimationController(FrameTextureNormalizerState model, Runnable repaintAction, int animationDelayMillis) {
        this.model = model;
        this.repaintAction = repaintAction;
        this.timer = new Timer(animationDelayMillis, e -> advanceAnimationStep());
        this.timer.setRepeats(true);
    }

    public void toggleForwardAnimation() {
        toggleAnimation(Direction.FORWARD);
    }

    public void toggleBackwardAnimation() {
        toggleAnimation(Direction.BACKWARD);
    }

    public void stopAnimation() {
        direction = Direction.STOPPED;
        timer.stop();
    }

    boolean advanceAnimationStep() {
        if (model == null || direction == Direction.STOPPED) {
            stopAnimation();
            return false;
        }

        boolean changed = switch (direction) {
            case FORWARD -> model.selectNextFrame();
            case BACKWARD -> model.selectPreviousFrame();
            case STOPPED -> false;
        };
        if (!changed) {
            stopAnimation();
            return false;
        }

        if (repaintAction != null) {
            repaintAction.run();
        }
        if (isAtBoundary()) {
            stopAnimation();
        }
        return true;
    }

    boolean isRunning() {
        return timer.isRunning();
    }

    boolean isAnimatingForward() {
        return direction == Direction.FORWARD && timer.isRunning();
    }

    boolean isAnimatingBackward() {
        return direction == Direction.BACKWARD && timer.isRunning();
    }

    private void toggleAnimation(Direction requestedDirection) {
        if (requestedDirection == direction && timer.isRunning()) {
            stopAnimation();
            return;
        }

        stopAnimation();
        direction = requestedDirection;
        if (advanceAnimationStep() && !isAtBoundary()) {
            timer.start();
        }
    }

    private boolean isAtBoundary() {
        if (model == null || model.getFrames().isEmpty()) {
            return true;
        }
        return switch (direction) {
            case FORWARD -> model.getSelectedFrameIndex() >= model.getFrames().size() - 1;
            case BACKWARD -> model.getSelectedFrameIndex() <= 0;
            case STOPPED -> true;
        };
    }
}
