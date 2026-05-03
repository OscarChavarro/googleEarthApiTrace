package matrixmerger.gui;

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import matrixmerger.model.MatrixMergerModel;
import vsdk.toolkit.gui.AwtSystem;
import vsdk.toolkit.gui.CameraControllerOrbiter;

public final class MouseInteractionTechnique implements MouseListener, MouseMotionListener, MouseWheelListener {
    private final CameraControllerOrbiter cameraController;
    private final Runnable repaintAction;
    private final Runnable focusAction;

    public MouseInteractionTechnique(
        MatrixMergerModel model,
        Runnable repaintAction,
        Runnable focusAction
    ) {
        this.cameraController = new CameraControllerOrbiter(model.getCamera());
        this.repaintAction = repaintAction;
        this.focusAction = focusAction;
    }

    @Override
    public void mouseClicked(MouseEvent e) {
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
