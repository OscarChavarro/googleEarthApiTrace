package planetdemviewer.gui;

import java.awt.event.KeyListener;
import java.util.function.IntConsumer;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.processing.CameraZoom;
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
    private final IntConsumer paletteCycleAction;
    private final Runnable toggleTileBoundsAction;
    private final Runnable toggleUniformLodAction;
    private final Runnable saveStateAction;
    private final Runnable clearStateAction;
    private final Runnable toggleImageryAction;
    private final Runnable deleteSelectedTileAction;
    private final Runnable toggleFullScreenAction;

    public KeyboardInteractionTechniques(
        PlanetViewerModel model,
        Runnable closeAction,
        CameraControllerAquynza cameraController,
        Runnable repaintAction,
        Runnable loadImageAction,
        ViewActions viewActions,
        IntConsumer paletteCycleAction,
        Runnable toggleTileBoundsAction,
        Runnable toggleUniformLodAction,
        Runnable saveStateAction,
        Runnable clearStateAction, Runnable toggleImageryAction, Runnable deleteSelectedTileAction,
        Runnable toggleFullScreenAction
    ) {
        this.model = model;
        this.closeAction = closeAction;
        this.cameraController = cameraController;
        this.repaintAction = repaintAction;
        this.loadImageAction = loadImageAction;
        this.viewActions = viewActions;
        this.paletteCycleAction = paletteCycleAction;
        this.toggleTileBoundsAction = toggleTileBoundsAction;
        this.toggleUniformLodAction = toggleUniformLodAction;
        this.saveStateAction = saveStateAction;
        this.clearStateAction = clearStateAction;
        this.toggleImageryAction = toggleImageryAction;
        this.deleteSelectedTileAction = deleteSelectedTileAction;
        this.toggleFullScreenAction = toggleFullScreenAction;
        this.renderingConfigurationController = model == null
            ? null
            : new RendererConfigurationController();
    }

    @Override
    public void keyPressed(java.awt.event.KeyEvent e) {
        if (model == null) {
            return;
        }
        model.writeLocked(() -> keyPressedLocked(e));
    }

    private void keyPressedLocked(java.awt.event.KeyEvent e) {
        KeyEvent event = AwtSystem.awt2vsdkEvent(e);

        // Checked first: without Ctrl, 'f' belongs to the camera controller.
        if (e.isControlDown() && e.getKeyCode() == java.awt.event.KeyEvent.VK_F) {
            if (toggleFullScreenAction != null) {
                toggleFullScreenAction.run();
            }
            return;
        }
        if (event.keycode == KeyEvent.KEY_ESC && closeAction != null) {
            closeAction.run();
            return;
        }
        if (event.keycode == KeyEvent.KEY_r || event.keycode == KeyEvent.KEY_R) {
            resetCamera(event.keycode == KeyEvent.KEY_R);
            repaintAction.run();
            return;
        }
        if (event.keycode == KeyEvent.KEY_SPACE) {
            model.cycleOperationMode();
            repaintAction.run();
            return;
        }
        if (event.keycode == KeyEvent.KEY_l && loadImageAction != null) {
            loadImageAction.run();
            return;
        }
        if (event.keycode == KeyEvent.KEY_e && saveStateAction != null) {
            saveStateAction.run();
            return;
        }
        if (event.keycode == KeyEvent.KEY_E && clearStateAction != null) {
            clearStateAction.run();
            return;
        }
        if (event.keycode == KeyEvent.KEY_0 && toggleTileBoundsAction != null) {
            toggleTileBoundsAction.run();
            repaintAction.run();
            return;
        }
        if (event.keycode == KeyEvent.KEY_9 && toggleUniformLodAction != null) {
            toggleUniformLodAction.run();
            repaintAction.run();
            return;
        }
        if ((event.keycode == KeyEvent.KEY_t || event.keycode == KeyEvent.KEY_T) && toggleImageryAction != null) {
            toggleImageryAction.run(); repaintAction.run(); return;
        }
        if (e.getKeyCode() == java.awt.event.KeyEvent.VK_DELETE && deleteSelectedTileAction != null) {
            deleteSelectedTileAction.run();
            repaintAction.run();
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
        if (event.keycode == KeyEvent.KEY_3 || event.keycode == KeyEvent.KEY_4) {
            if (paletteCycleAction != null) {
                paletteCycleAction.accept(event.keycode == KeyEvent.KEY_3 ? -1 : 1);
                repaintAction.run();
            }
            return;
        }
        if (event.keycode == KeyEvent.KEY_5 || event.keycode == KeyEvent.KEY_6) {
            if (event.keycode == KeyEvent.KEY_5) {
                model.decreaseHeightExagerationFactor();
            }
            else {
                model.increaseHeightExagerationFactor();
            }
            repaintAction.run();
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
        if (model != null) {
            model.writeLocked(() -> {
                if (cameraController != null
                    && cameraController.processKeyReleasedEvent(AwtSystem.awt2vsdkEvent(e))) {
                    repaintAction.run();
                }
            });
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
