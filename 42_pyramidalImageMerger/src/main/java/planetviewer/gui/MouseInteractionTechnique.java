package planetviewer.gui;

import java.awt.Component;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import planetviewer.processing.CameraZoom;
import vsdk.toolkit.gui.AwtSystem;
import vsdk.toolkit.gui.CameraControllerAquynza;

public final class MouseInteractionTechnique implements MouseListener, MouseMotionListener, MouseWheelListener {
    /** Routes a canvas pixel to the view under it and retargets the camera controller, before the event is processed. */
    @FunctionalInterface
    public interface ViewSelector {
        void selectAt(int xPixel, int yPixelFromTop, int canvasWidth, int canvasHeight);
    }

    private final CameraControllerAquynza cameraController;
    private final ViewSelector viewSelector;
    private final Runnable repaintAction;
    private final Runnable focusAction;

    public MouseInteractionTechnique(
        CameraControllerAquynza cameraController,
        ViewSelector viewSelector,
        Runnable repaintAction,
        Runnable focusAction
    ) {
        this.cameraController = cameraController;
        this.viewSelector = viewSelector;
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
        viewSelector.selectAt(e.getX(), e.getY(), width, height);
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        if (cameraController.processMouseClickedEvent(AwtSystem.awt2vsdkEvent(e))) {
            repaintAction.run();
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {
        routeToView(e);
        repaintAction.run();
        cameraController.processMousePressedEvent(AwtSystem.awt2vsdkEvent(e));
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (cameraController.processMouseReleasedEvent(AwtSystem.awt2vsdkEvent(e))) {
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
        if (viewSelector != null) {
            viewSelector.selectAt(-1, -1, 0, 0);
            repaintAction.run();
        }
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        routeToView(e);
        if (cameraController.processMouseDraggedEvent(AwtSystem.awt2vsdkEvent(e))) {
            repaintAction.run();
        }
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        routeToView(e);
        cameraController.processMouseMovedEvent(AwtSystem.awt2vsdkEvent(e));
        repaintAction.run();
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        routeToView(e);
        if (e.getWheelRotation() < 0) {
            CameraZoom.zoomIn(cameraController.getCamera());
        }
        else if (e.getWheelRotation() > 0) {
            CameraZoom.zoomOut(cameraController.getCamera());
        }
        repaintAction.run();
    }
}
