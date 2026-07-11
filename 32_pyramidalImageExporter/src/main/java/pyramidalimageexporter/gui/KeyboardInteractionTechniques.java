package pyramidalimageexporter.gui;

import java.awt.event.KeyListener;
import pyramidalimageexporter.model.PyramidalImageExporterModel;
import vsdk.toolkit.gui.AwtSystem;
import vsdk.toolkit.gui.CameraControllerOrbiter;
import vsdk.toolkit.gui.KeyEvent;
import vsdk.toolkit.gui.RendererConfigurationController;

public final class KeyboardInteractionTechniques implements KeyListener {
    private final PyramidalImageExporterModel model;
    private final Runnable closeAction;
    private final Runnable repaintAction;
    private final Runnable exportAction;
    private final CameraControllerOrbiter cameraController;
    private final RendererConfigurationController renderingConfigurationController;

    public KeyboardInteractionTechniques(
        PyramidalImageExporterModel model,
        Runnable closeAction,
        CameraControllerOrbiter cameraController,
        Runnable repaintAction,
        Runnable exportAction
    ) {
        this.model = model;
        this.closeAction = closeAction;
        this.cameraController = cameraController;
        this.repaintAction = repaintAction;
        this.exportAction = exportAction;
        this.renderingConfigurationController = model == null
            ? null
            : new RendererConfigurationController(model.getRenderingConfiguration());
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
        if (keyChar == '1') {
            if (model.selectPreviousLayer()) {
                repaintAction.run();
            }
            return;
        }
        if (keyChar == '2') {
            if (model.selectNextLayer()) {
                repaintAction.run();
            }
            return;
        }
        if (keyChar == 'e') {
            if (exportAction != null) {
                exportAction.run();
                repaintAction.run();
            }
            return;
        }
        if (renderingConfigurationController != null
            && renderingConfigurationController.processKeyPressedEvent(event)) {
            repaintAction.run();
            return;
        }
        if (keyChar == 't') {
            event.keycode = KeyEvent.KEY_F8;
            if (renderingConfigurationController != null
                && renderingConfigurationController.processKeyPressedEvent(event)) {
                repaintAction.run();
                return;
            }
        }
        if (cameraController != null && cameraController.processKeyPressedEvent(event)) {
            repaintAction.run();
        }
    }

    @Override
    public void keyReleased(java.awt.event.KeyEvent e) {
        if (cameraController != null
            && cameraController.processKeyReleasedEvent(AwtSystem.awt2vsdkEvent(e))) {
            repaintAction.run();
        }
    }

    @Override
    public void keyTyped(java.awt.event.KeyEvent e) {
    }
}
