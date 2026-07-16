package frametexturenormalizer.gui;

// Java classes
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

// JOGL classes
import com.jogamp.opengl.GL4;

// Vitral classes
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.gui.AwtSystem;
import vsdk.toolkit.gui.CameraControllerOrbiter;

public final class MouseOrbiterInteraction implements MouseListener, MouseMotionListener, MouseWheelListener {
    private final CameraControllerOrbiter cameraController;
    private final Runnable repaintAction;
    private final Runnable focusAction;
    private final TileSelectionHandler tileSelectionHandler;

    public MouseOrbiterInteraction(
        CameraControllerOrbiter cameraController,
        Runnable repaintAction,
        Runnable focusAction,
        TileSelectionHandler tileSelectionHandler
    ) {
        this.cameraController = cameraController;
        this.repaintAction = repaintAction;
        this.focusAction = focusAction;
        this.tileSelectionHandler = tileSelectionHandler;
    }

    public static void processReshape(GL4 gl, Camera camera, int width, int height) {
        gl.glViewport(0, 0, width, height);
        camera.updateViewportResize(width, height);
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        if (e.getButton() == MouseEvent.BUTTON1 && tileSelectionHandler != null) {
            Boolean changed = tileSelectionHandler.select(e.getX(), e.getY(), e.isShiftDown());
            if (Boolean.TRUE.equals(changed)) {
                repaintAction.run();
                return;
            }
        }
        if (cameraController.processMouseClickedEvent(AwtSystem.awt2vsdkEvent(e))) repaintAction.run();
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (cameraController.processMousePressedEvent(AwtSystem.awt2vsdkEvent(e))) repaintAction.run();
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (cameraController.processMouseReleasedEvent(AwtSystem.awt2vsdkEvent(e))) repaintAction.run();
    }

    @Override
    public void mouseEntered(MouseEvent e) {
        if (focusAction != null) focusAction.run();
    }

    @Override
    public void mouseExited(MouseEvent e) {
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (cameraController.processMouseDraggedEvent(AwtSystem.awt2vsdkEvent(e))) repaintAction.run();
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        if (cameraController.processMouseMovedEvent(AwtSystem.awt2vsdkEvent(e))) repaintAction.run();
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        if (cameraController.processMouseWheelEvent(AwtSystem.awt2vsdkEvent(e))) repaintAction.run();
    }

    @FunctionalInterface
    public interface TileSelectionHandler {
        boolean select(int x, int y, boolean expandConnected);
    }
}
