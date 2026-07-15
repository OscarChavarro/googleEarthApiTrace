package frametexturenormalizer.gui;

// Java classes
import java.awt.event.KeyListener;
import java.util.Set;

// Vitral classes
import vsdk.toolkit.gui.KeyEvent;
import vsdk.toolkit.gui.AwtSystem;
import vsdk.toolkit.gui.CameraControllerOrbiter;
import vsdk.toolkit.gui.RendererConfigurationController;

// App classes
import frametexturenormalizer.animation.AnimationController;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.state.FrameTextureNormalizerState;
import frametexturenormalizer.processing.neighborhood.TileCutter;

public final class KeyboardInteractionTechniques implements KeyListener {
    private final FrameTextureNormalizerState model;
    private final Runnable closeAction;
    private final Runnable repaintAction;
    private final CameraControllerOrbiter cameraController;
    private final RendererConfigurationController renderingConfigurationController;
    private final AnimationController animationController;

    public KeyboardInteractionTechniques(
        FrameTextureNormalizerState model,
        Runnable closeAction,
        CameraControllerOrbiter cameraController,
        Runnable repaintAction,
        AnimationController animationController
    ) {
        this.model = model;
        this.closeAction = closeAction;
        this.cameraController = cameraController;
        this.repaintAction = repaintAction;
        this.animationController = animationController;
        this.renderingConfigurationController = model == null
            ? null
            : new RendererConfigurationController(model.getRenderingConfiguration());
    }

    private void redraw() {
        if (repaintAction != null) {
            repaintAction.run();
        }
    }

    @Override
    public void keyPressed(java.awt.event.KeyEvent e) {
        KeyEvent event = AwtSystem.awt2vsdkEvent(e);
        char keyChar = e.getKeyChar();

        if (event.keycode == KeyEvent.KEY_ESC && closeAction != null) {
            closeAction.run();
            return;
        }
        if (model == null) {
            return;
        }
        if (renderingConfigurationController != null
            && renderingConfigurationController.processKeyPressedEvent(event)) {
            redraw();
            return;
        }
        if (e.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE) {
            if (animationController != null) {
                animationController.toggleForwardAnimation();
                redraw();
            }
            return;
        }
        if (e.getKeyCode() == java.awt.event.KeyEvent.VK_BACK_SPACE) {
            if (animationController != null) {
                animationController.toggleBackwardAnimation();
                redraw();
            }
            return;
        }
        if (keyChar == 't') {
            event.keycode = KeyEvent.KEY_F8;
            if (renderingConfigurationController != null
                && renderingConfigurationController.processKeyPressedEvent(event)) {
                redraw();
                return;
            }
        }
        if (keyChar == 'c' || keyChar == 'C') {
            FrameData frame = model.getSelectedFrame();
            Set<String> selectedIds = TileCutter.selectedTileIdsAcrossFrames(model.getFrames());
            Set<String> expandedIds = TileCutter.expandWestCutScopedIdsAcrossFrames(model.getFrames(), selectedIds);
            if (frame != null && !expandedIds.isEmpty()) {
                model.addWestCutterTileIds(expandedIds);
                redraw();
                return;
            }
        }

        switch (event.keycode) {
            case KeyEvent.KEY_1 -> {
                if (animationController != null) {
                    animationController.stopAnimation();
                }
                if (model.selectPreviousFrame()) {
                    redraw();
                }
            }
            case KeyEvent.KEY_2 -> {
                if (animationController != null) {
                    animationController.stopAnimation();
                }
                if (model.selectNextFrame()) {
                    redraw();
                }
            }
            case KeyEvent.KEY_3 -> {
                if (model.selectPreviousTile()) redraw();
            }
            case KeyEvent.KEY_4 -> {
                if (model.selectNextTile()) redraw();
            }
            default -> {
                if (cameraController != null && cameraController.processKeyPressedEvent(event)) {
                    redraw();
                }
            }
        }
    }

    @Override
    public void keyReleased(java.awt.event.KeyEvent e) {
        if (cameraController != null && cameraController.processKeyReleasedEvent(AwtSystem.awt2vsdkEvent(e))) {
            redraw();
        }
    }

    @Override
    public void keyTyped(java.awt.event.KeyEvent e) {
    }
}
