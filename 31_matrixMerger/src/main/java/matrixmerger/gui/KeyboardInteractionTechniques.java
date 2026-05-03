package matrixmerger.gui;

import java.awt.event.KeyListener;
import vsdk.toolkit.gui.AwtSystem;
import vsdk.toolkit.gui.CameraControllerOrbiter;
import vsdk.toolkit.gui.KeyEvent;

public final class KeyboardInteractionTechniques implements KeyListener {
    private final Runnable closeAction;
    private final Runnable repaintAction;
    private final CameraControllerOrbiter cameraController;

    public KeyboardInteractionTechniques(
        Runnable closeAction,
        CameraControllerOrbiter cameraController,
        Runnable repaintAction
    ) {
        this.closeAction = closeAction;
        this.cameraController = cameraController;
        this.repaintAction = repaintAction;
    }

    @Override
    public void keyPressed(java.awt.event.KeyEvent e) {
        KeyEvent event = AwtSystem.awt2vsdkEvent(e);
        if (event.keycode == KeyEvent.KEY_ESC && closeAction != null) {
            closeAction.run();
            return;
        }
        if (cameraController != null && cameraController.processKeyPressedEvent(event) && repaintAction != null) {
            repaintAction.run();
        }
    }

    @Override
    public void keyReleased(java.awt.event.KeyEvent e) {
        if (cameraController != null
            && cameraController.processKeyReleasedEvent(AwtSystem.awt2vsdkEvent(e))
            && repaintAction != null) {
            repaintAction.run();
        }
    }

    @Override
    public void keyTyped(java.awt.event.KeyEvent e) {
    }
}
