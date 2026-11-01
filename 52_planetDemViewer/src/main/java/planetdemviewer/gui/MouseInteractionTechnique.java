package planetdemviewer.gui;

import java.awt.Component;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import planetdemviewer.processing.CameraZoom;
import planetdemviewer.model.PlanetViewerModel;
import vsdk.toolkit.gui.AwtSystem;
import vsdk.toolkit.gui.CameraControllerAquynza;

public final class MouseInteractionTechnique implements MouseListener, MouseMotionListener, MouseWheelListener {
    /** Routes a canvas pixel to the view under it and retargets the camera controller, before the event is processed. */
    @FunctionalInterface
    public interface ViewSelector {
        void selectAt(int xPixel, int yPixelFromTop, int canvasWidth, int canvasHeight);
    }

    /** Performs terrain picking after the active view has been selected. */
    @FunctionalInterface
    public interface TerrainSelector {
        void selectAt(int xPixel, int yPixelFromTop, int canvasWidth, int canvasHeight, boolean selectTile);
    }

    private final CameraControllerAquynza cameraController;
    private final PlanetViewerModel model;
    private final ViewSelector viewSelector;
    private final TerrainSelector terrainSelector;
    private final Runnable repaintAction;
    private final Runnable focusAction;

    public MouseInteractionTechnique(
        PlanetViewerModel model,
        CameraControllerAquynza cameraController,
        ViewSelector viewSelector,
        TerrainSelector terrainSelector,
        Runnable repaintAction,
        Runnable focusAction
    ) {
        this.model = model;
        this.cameraController = cameraController;
        this.viewSelector = viewSelector;
        this.terrainSelector = terrainSelector;
        this.repaintAction = repaintAction;
        this.focusAction = focusAction;
    }

    private void routeToView(MouseEvent e) {
        if (viewSelector == null) {
            return;
        }
        Component source = e.getComponent();
        int width = source == null ? 0 : source.getWidth();
        int height = source == null ? 0 : source.getHeight();
        mutate(() -> viewSelector.selectAt(e.getX(), e.getY(), width, height));
    }

    private void mutate(Runnable action) {
        if (model == null) {
            action.run();
        }
        else {
            model.writeLocked(action);
        }
    }

    private boolean mutateResult(java.util.function.BooleanSupplier action) {
        boolean[] result = new boolean[1];
        mutate(() -> result[0] = action.getAsBoolean());
        return result[0];
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getButton() == MouseEvent.BUTTON1 && terrainSelector != null) {
            routeToView(e);
            Component source = e.getComponent();
            int width = source == null ? 0 : source.getWidth();
            int height = source == null ? 0 : source.getHeight();
            mutate(() -> terrainSelector.selectAt(e.getX(), e.getY(), width, height, e.isControlDown()));
        }
        if (mutateResult(() -> cameraController.processMouseClickedEvent(AwtSystem.awt2vsdkEvent(e)))) {
            repaintAction.run();
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {
        routeToView(e);
        repaintAction.run();
        mutate(() -> cameraController.processMousePressedEvent(AwtSystem.awt2vsdkEvent(e)));
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (mutateResult(() -> cameraController.processMouseReleasedEvent(AwtSystem.awt2vsdkEvent(e)))) {
            repaintAction.run();
        }
    }

    @Override
    public void mouseEntered(MouseEvent e) {
        if (focusAction != null) {
            focusAction.run();
        }
    }

    @Override
    public void mouseExited(MouseEvent e) {
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (mutateResult(() -> cameraController.processMouseDraggedEvent(AwtSystem.awt2vsdkEvent(e)))) {
            repaintAction.run();
        }
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        if (mutateResult(() -> cameraController.processMouseMovedEvent(AwtSystem.awt2vsdkEvent(e)))) {
            repaintAction.run();
        }
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        routeToView(e);
        mutate(() -> {
            if (e.getWheelRotation() < 0) {
                CameraZoom.zoomIn(cameraController.getCamera());
            }
            else if (e.getWheelRotation() > 0) {
                CameraZoom.zoomOut(cameraController.getCamera());
            }
        });
        repaintAction.run();
    }
}
