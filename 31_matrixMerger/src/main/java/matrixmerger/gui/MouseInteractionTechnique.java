package matrixmerger.gui;

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.function.Consumer;
import vsdk.toolkit.gui.AwtSystem;
import vsdk.toolkit.gui.CameraControllerOrbiter;

public final class MouseInteractionTechnique implements MouseListener, MouseMotionListener, MouseWheelListener {
    private final CameraControllerOrbiter cameraController;
    private final Runnable repaintAction;
    private final Runnable focusAction;
    private final Consumer<MouseEvent> tileSelectionAction;

    public MouseInteractionTechnique(
        CameraControllerOrbiter cameraController,
        Runnable repaintAction,
        Runnable focusAction,
        Consumer<MouseEvent> tileSelectionAction
    ) {
        this.cameraController = cameraController;
        this.repaintAction = repaintAction;
        this.focusAction = focusAction;
        this.tileSelectionAction = tileSelectionAction;
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getButton() == MouseEvent.BUTTON1 && tileSelectionAction != null) {
            tileSelectionAction.accept(e);
            repaintAction.run();
        }
        if (cameraController.processMouseClickedEvent(AwtSystem.awt2vsdkEvent(e))) {
            repaintAction.run();
        }
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (cameraController.processMousePressedEvent(AwtSystem.awt2vsdkEvent(e))) {
            repaintAction.run();
        }
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
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (cameraController.processMouseDraggedEvent(AwtSystem.awt2vsdkEvent(e))) {
            repaintAction.run();
        }
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        if (cameraController.processMouseMovedEvent(AwtSystem.awt2vsdkEvent(e))) {
            repaintAction.run();
        }
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        if (cameraController.processMouseWheelEvent(AwtSystem.awt2vsdkEvent(e))) {
            repaintAction.run();
        }
    }
}
