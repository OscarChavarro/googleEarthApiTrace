package pyramidalimagebuilder.gui;

import java.awt.event.KeyListener;
import pyramidalimagebuilder.config.Configuration;
import pyramidalimagebuilder.model.PyramidalImageModel;

import vsdk.toolkit.gui.KeyEvent;
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
    public void keyPressed(java.awt.event.KeyEvent e) {
        KeyEvent event = AwtSystem.awt2vsdkEvent(e);

        if (event.keycode == KeyEvent.KEY_ESC && closeAction != null) {
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
        switch (event.keycode) {
            case KeyEvent.KEY_1 -> {
                if (model.selectPreviousTileMatrix()) {
                    System.out.println("TileMatrix size (columns x rows): " + model.selectedTileMatrixSizeText());
                    if (repaintAction != null) {
                        repaintAction.run();
                    }
                }
            }
            case KeyEvent.KEY_2 -> {
                if (model.selectNextTileMatrix()) {
                    System.out.println("TileMatrix size (columns x rows): " + model.selectedTileMatrixSizeText());
                    if (repaintAction != null) {
                        repaintAction.run();
                    }
                }
            }
            default -> {
                if (cameraController != null && cameraController.processKeyPressedEvent(event)) {
                    if (repaintAction != null) {
                        repaintAction.run();
                    }
                }
            }
        }
    }

    @Override
    public void keyReleased(java.awt.event.KeyEvent e) {
        if (cameraController != null && cameraController.processKeyReleasedEvent(AwtSystem.awt2vsdkEvent(e))) {
            if (repaintAction != null) {
                repaintAction.run();
            }
        }
    }

    @Override
    public void keyTyped(java.awt.event.KeyEvent e) {
    }
}
