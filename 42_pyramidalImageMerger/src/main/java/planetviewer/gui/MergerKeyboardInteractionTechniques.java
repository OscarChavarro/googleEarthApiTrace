package planetviewer.gui;

import java.awt.event.KeyListener;
import planetviewer.model.PlanetViewerModel;
import planetviewer.processing.CameraZoom;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.gui.AwtSystem;
import vsdk.toolkit.gui.CameraControllerAquynza;
import vsdk.toolkit.gui.KeyEvent;
import vsdk.toolkit.gui.RendererConfigurationController;

public final class MergerKeyboardInteractionTechniques implements KeyListener {
    private final PlanetViewerModel model;
    private final Runnable closeAction;
    private final CameraControllerAquynza cameraController;
    private final Runnable repaintAction;
    private final Runnable mergeAction;
    private final RendererConfigurationController renderingConfigurationController;

    public MergerKeyboardInteractionTechniques(
        PlanetViewerModel model,
        Runnable closeAction,
        CameraControllerAquynza cameraController,
        Runnable repaintAction,
        Runnable mergeAction
    ) {
        this.model = model;
        this.closeAction = closeAction;
        this.cameraController = cameraController;
        this.repaintAction = repaintAction;
        this.mergeAction = mergeAction;
        this.renderingConfigurationController = new RendererConfigurationController();
    }

    @Override
    public void keyPressed(java.awt.event.KeyEvent e) {
        KeyEvent event = AwtSystem.awt2vsdkEvent(e);
        if (event.keycode == KeyEvent.KEY_ESC && closeAction != null) {
            closeAction.run();
            return;
        }
        if (event.keycode == KeyEvent.KEY_r || event.keycode == KeyEvent.KEY_R) {
            resetCamera(event.keycode == KeyEvent.KEY_R);
            repaintAction.run();
            return;
        }
        if (event.keycode == KeyEvent.KEY_z || event.keycode == KeyEvent.KEY_Z) {
            if (event.keycode == KeyEvent.KEY_z) {
                CameraZoom.zoomIn(cameraController.getCamera());
            }
            else {
                CameraZoom.zoomOut(cameraController.getCamera());
            }
            repaintAction.run();
            return;
        }
        if (event.keycode == KeyEvent.KEY_m || event.keycode == KeyEvent.KEY_M) {
            mergeAction.run();
            repaintAction.run();
            return;
        }
        renderingConfigurationController.setRendererConfiguration(model.getRenderingConfiguration());
        if (renderingConfigurationController.processKeyPressedEvent(event)) {
            repaintAction.run();
            return;
        }
        if (cameraController.processKeyPressedEvent(event)) {
            repaintAction.run();
        }
    }

    @Override
    public void keyReleased(java.awt.event.KeyEvent e) {
        if (cameraController.processKeyReleasedEvent(AwtSystem.awt2vsdkEvent(e))) {
            repaintAction.run();
        }
    }

    @Override
    public void keyTyped(java.awt.event.KeyEvent e) {
    }

    private void resetCamera(boolean oblique) {
        Camera camera = cameraController.getCamera();
        camera.setPosition(oblique ? new Vector3Dd(3.0, -3.0, 4.0) : new Vector3Dd(0.0, 0.0, 5.0));
        camera.setFocusedPositionDirect(new Vector3Dd(0.0, 0.0, 0.0));
        camera.setUpDirect(new Vector3Dd(0.0, 1.0, 0.0));
        camera.updateVectors();
        model.setCurrentPSC(0);
    }
}
