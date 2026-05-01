package pyramidalimagebuilder.gui;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import pyramidalimagebuilder.model.PyramidalImageModel;
import vsdk.toolkit.gui.AwtSystem;
import vsdk.toolkit.gui.CameraControllerOrbiter;
import vsdk.toolkit.gui.RendererConfigurationController;

public final class KeyboardInteractionTechniques implements KeyListener {
    private final PyramidalImageModel model;
    private final Runnable closeAction;
    private final Runnable repaintAction;
    private final Runnable reloadMatricesAction;
    private final CameraControllerOrbiter cameraController;
    private final RendererConfigurationController renderingConfigurationController;

    public KeyboardInteractionTechniques(
        PyramidalImageModel model,
        Runnable closeAction,
        CameraControllerOrbiter cameraController,
        Runnable repaintAction,
        Runnable reloadMatricesAction
    ) {
        this.model = model;
        this.closeAction = closeAction;
        this.cameraController = cameraController;
        this.repaintAction = repaintAction;
        this.reloadMatricesAction = reloadMatricesAction;
        this.renderingConfigurationController = model == null
            ? null
            : new RendererConfigurationController(model.getRenderingConfiguration());
    }

    @Override
    public void keyPressed(KeyEvent e) {
        vsdk.toolkit.gui.KeyEvent event = AwtSystem.awt2vsdkEvent(e);

        if (event.keycode == vsdk.toolkit.gui.KeyEvent.KEY_ESC && closeAction != null) {
            closeAction.run();
            return;
        }
        if (model == null) {
            return;
        }
        if (renderingConfigurationController != null
            && renderingConfigurationController.processKeyPressedEvent(event)) {
            if (repaintAction != null) {
                repaintAction.run();
            }
            return;
        }
        if (event.keycode == vsdk.toolkit.gui.KeyEvent.KEY_1) {
            if (model.selectPreviousTileMatrix()) {
                System.out.println("TileMatrix size (columns x rows): " + model.selectedTileMatrixSizeText());
                if (repaintAction != null) {
                    repaintAction.run();
                }
            }
            return;
        }
        if (event.keycode == vsdk.toolkit.gui.KeyEvent.KEY_2) {
            if (model.selectNextTileMatrix()) {
                System.out.println("TileMatrix size (columns x rows): " + model.selectedTileMatrixSizeText());
                if (repaintAction != null) {
                    repaintAction.run();
                }
            }
            return;
        }
        if (event.keycode == vsdk.toolkit.gui.KeyEvent.KEY_3) {
            if (model.decreaseImageBorderThreshold()) {
                System.out.println("Image border distance threshold: " + model.getImageBorderThreshold());
                if (reloadMatricesAction != null) {
                    reloadMatricesAction.run();
                }
                if (repaintAction != null) {
                    repaintAction.run();
                }
            }
            return;
        }
        if (event.keycode == vsdk.toolkit.gui.KeyEvent.KEY_4) {
            if (model.increaseImageBorderThreshold()) {
                System.out.println("Image border distance threshold: " + model.getImageBorderThreshold());
                if (reloadMatricesAction != null) {
                    reloadMatricesAction.run();
                }
                if (repaintAction != null) {
                    repaintAction.run();
                }
            }
            return;
        }
        if (cameraController != null && cameraController.processKeyPressedEvent(event)) {
            if (repaintAction != null) {
                repaintAction.run();
            }
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        if (cameraController != null && cameraController.processKeyReleasedEvent(AwtSystem.awt2vsdkEvent(e))) {
            if (repaintAction != null) {
                repaintAction.run();
            }
        }
    }

    @Override
    public void keyTyped(KeyEvent e) {
    }
}
