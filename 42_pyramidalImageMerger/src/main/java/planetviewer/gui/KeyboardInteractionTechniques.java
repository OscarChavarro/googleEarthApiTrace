package planetviewer.gui;

import java.awt.event.KeyListener;
import planetviewer.model.PlanetViewerModel;
import planetviewer.model.PyramidalImageInstance;
import planetviewer.processing.CameraZoom;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.gui.AwtSystem;
import vsdk.toolkit.gui.CameraControllerAquynza;
import vsdk.toolkit.gui.KeyEvent;
import vsdk.toolkit.gui.RendererConfigurationController;

public final class KeyboardInteractionTechniques implements KeyListener {
    private final PlanetViewerModel model;
    private final Runnable closeAction;
    private final Runnable repaintAction;
    private final Runnable loadImageAction;
    private final CameraControllerAquynza cameraController;
    private final RendererConfigurationController renderingConfigurationController;
    private final ViewActions viewActions;

    public KeyboardInteractionTechniques(
        PlanetViewerModel model,
        Runnable closeAction,
        CameraControllerAquynza cameraController,
        Runnable repaintAction,
        Runnable loadImageAction,
        ViewActions viewActions
    ) {
        this.model = model;
        this.closeAction = closeAction;
        this.cameraController = cameraController;
        this.repaintAction = repaintAction;
        this.loadImageAction = loadImageAction;
        this.viewActions = viewActions;
        this.renderingConfigurationController = model == null
            ? null
            : new RendererConfigurationController();
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
        if (event.keycode == KeyEvent.KEY_r || event.keycode == KeyEvent.KEY_R) {
            resetCamera(event.keycode == KeyEvent.KEY_R);
            repaintAction.run();
            return;
        }
        if (event.keycode == KeyEvent.KEY_l && loadImageAction != null) {
            loadImageAction.run();
            return;
        }
        if (event.keycode == KeyEvent.KEY_1) {
            if (model.selectPreviousImage()) {
                repaintAction.run();
            }
            return;
        }
        if (event.keycode == KeyEvent.KEY_2) {
            if (model.selectNextImage()) {
                repaintAction.run();
            }
            return;
        }
        if (event.keycode == KeyEvent.KEY_o || event.keycode == KeyEvent.KEY_O) {
            adjustSelectedOpacity(event.keycode == KeyEvent.KEY_O);
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
        if (event.keycode == KeyEvent.KEY_PAGEUP || event.keycode == KeyEvent.KEY_PAGEDOWN) {
            adjustSelectedZOffset(event.keycode == KeyEvent.KEY_PAGEUP ? 1.0 : -1.0);
            repaintAction.run();
            return;
        }
        if (viewActions != null) {
            if (event.keycode == KeyEvent.KEY_PERIOD) {
                viewActions.cycleSelectedView().run();
                repaintAction.run();
                return;
            }
            if (event.keycode == KeyEvent.KEY_COMMA) {
                viewActions.cycleLayoutStyle().run();
                repaintAction.run();
                return;
            }
            if (event.keycode == KeyEvent.KEY_w || event.keycode == KeyEvent.KEY_W) {
                viewActions.toggleCameraFrustumsVisible().run();
                repaintAction.run();
                return;
            }
            if (event.keycode == KeyEvent.KEY_v) {
                viewActions.addView().run();
                repaintAction.run();
                return;
            }
            if (event.keycode == KeyEvent.KEY_V) {
                viewActions.removeView().run();
                repaintAction.run();
                return;
            }
        }
        if (renderingConfigurationController != null && viewActions != null) {
            renderingConfigurationController.setRendererConfiguration(
                viewActions.selectedRenderingConfiguration().get()
            );
            if (renderingConfigurationController.processKeyPressedEvent(event)) {
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

    private void resetCamera(boolean oblique) {
        Camera camera = cameraController.getCamera();
        camera.setPosition(oblique ? new Vector3Dd(3.0, -3.0, 4.0) : new Vector3Dd(0.0, 0.0, 5.0));
        camera.setFocusedPositionDirect(new Vector3Dd(0.0, 0.0, 0.0));
        camera.setUpDirect(new Vector3Dd(0.0, 1.0, 0.0));
        camera.updateVectors();
        if (camera == model.getViewingCamera()) {
            model.setCurrentPSC(0);
        }
    }

    private void adjustSelectedOpacity(boolean increase) {
        PyramidalImageInstance selected = model.getSelectedInstance();
        if (selected == null) {
            return;
        }
        selected.setOpacity(increase ? selected.getOpacity() * 2.0 : selected.getOpacity() / 2.0);
    }

    private void adjustSelectedZOffset(double delta) {
        PyramidalImageInstance selected = model.getSelectedInstance();
        if (selected == null) {
            return;
        }
        selected.setZOffset(selected.getZOffset() + delta);
    }
}
