package dumpanalyzer;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;

import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.environment.Camera;
import vsdk.toolkit.gui.AwtSystem;
import vsdk.toolkit.gui.CameraControllerOrbiter;
import vsdk.toolkit.render.jogl.Jogl4CameraRenderer;
import vsdk.toolkit.render.jogl.Jogl4MatrixRenderer;
import vsdk.toolkit.render.jogl.Jogl4MinMaxRenderer;
import vsdk.toolkit.render.jogl.Jogl4Renderer;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

public class Jogl4DumpAnalyzerRenderer implements
    GLEventListener,
    MouseListener,
    MouseMotionListener,
    MouseWheelListener {

    private final Runnable shutdownHook;
    private final DumpAnalyzerModel model;
    private final Jogl4HudRenderer hudRenderer;
    private final Camera camera;
    private final CameraControllerOrbiter cameraController;
    private volatile boolean closing;
    private GLCanvas canvas;
    private JFrame frame;
    private int lastSelectedFrameIndex = -1;
    private int lastSelectedTileIndex = -1;

    public Jogl4DumpAnalyzerRenderer(DumpAnalyzerModel model, Runnable shutdownHook) {
        this.model = model;
        this.shutdownHook = shutdownHook;
        this.hudRenderer = new Jogl4HudRenderer();
        this.camera = new Camera();
        this.cameraController = new CameraControllerOrbiter(camera);
        this.cameraController.setDeltaMovement(0.2);
    }

    public void start() {
        if (!Jogl4Renderer.verifyOpenGLAvailability()) {
            System.out.println("Can not start OpenGL/JOGL renderer.");
            return;
        }

        GLProfile profile = GLProfile.get(GLProfile.GL4bc);
        GLCapabilities caps = new GLCapabilities(profile);
        canvas = new GLCanvas(caps);
        canvas.addGLEventListener(this);
        canvas.setFocusable(true);
        canvas.addMouseListener(this);
        canvas.addMouseMotionListener(this);
        canvas.addMouseWheelListener(this);

        KeyListener keyboard = new KeyboardInteractionTechnique(
            model,
            this::requestClose,
            cameraController,
            this::requestRedraw
        );
        canvas.addKeyListener(keyboard);

        frame = new JFrame("dumpAnalyzer HUD - ESC to exit");
        frame.add(canvas, BorderLayout.CENTER);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(900, 540));
        frame.setSize(new Dimension(1200, 720));
        frame.addKeyListener(keyboard);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                requestClose();
            }
        });

        model.addListener(this::requestRedraw);

        frame.setVisible(true);
        canvas.requestFocusInWindow();
        canvas.display();
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        DumpAnalyzerModel.HudState state = model.snapshotHudState();
        List<Frame> frames = model.snapshotFrames();
        recenterCameraIfSelectionChanged(state, frames);
        GL4 gl = drawable.getGL().getGL4();
        gl.glEnable(GL4.GL_DEPTH_TEST);
        gl.glClearColor(0, 0, 0, 1);
        gl.glClear(GL4.GL_COLOR_BUFFER_BIT | GL4.GL_DEPTH_BUFFER_BIT);
        Matrix4x4 projection = Jogl4CameraRenderer.activate(gl, camera);
        drawObjectsGL(gl, projection);
        if (state.selectedFrameIndex() > 0 && state.selectedFrameIndex() <= frames.size()) {
            drawSelectedTile(gl, drawable.getGL().getGL2(), frames.get(state.selectedFrameIndex() - 1), state.selectedTileIndex(), projection);
        }
        hudRenderer.render(drawable, state, camera, model.getTexturePath(state.selectedTextureId()));
    }

    private void recenterCameraIfSelectionChanged(DumpAnalyzerModel.HudState state, List<Frame> frames) {
        int frameIndex = state.selectedFrameIndex();
        int tileIndex = state.selectedTileIndex();
        if (frameIndex == lastSelectedFrameIndex && tileIndex == lastSelectedTileIndex) {
            return;
        }
        lastSelectedFrameIndex = frameIndex;
        lastSelectedTileIndex = tileIndex;

        if (frameIndex <= 0 || frameIndex > frames.size()) {
            return;
        }
        Frame frameData = frames.get(frameIndex - 1);
        if (tileIndex <= 0 || tileIndex > frameData.getTiles().size()) {
            return;
        }
        TileInstance tile = frameData.getTiles().get(tileIndex - 1);
        if (tile.getMin() == null || tile.getMax() == null) {
            return;
        }

        Vector3D min = tile.getMin();
        Vector3D max = tile.getMax();
        Vector3D center = new Vector3D(
            (min.x() + max.x()) * 0.5,
            (min.y() + max.y()) * 0.5,
            (min.z() + max.z()) * 0.5
        );
        double dx = max.x() - min.x();
        double dy = max.y() - min.y();
        double dz = max.z() - min.z();
        double diagonal = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double distance = Math.max(1e-6, 1.5 * diagonal);

        Vector3D front = camera.getFront().normalized();
        Vector3D newPosition = center.subtract(front.multiply(distance));
        cameraController.setPointOfInterest(center);
        camera.setPosition(newPosition);
        camera.setFocusedPositionMaintainingOrthogonality(center);
        camera.setNearPlaneDistance(Math.max(1e-6, distance / 10.0));
        camera.setFarPlaneDistance(Math.max(1e-5, distance * 3.0));
    }

    private void drawObjectsGL(GL4 gl, Matrix4x4 projection) {
        Jogl4MatrixRenderer.draw(gl, projection, Matrix4x4.identityMatrix());
    }

    private void drawSelectedTile(GL4 gl, GL2 gl2, Frame frameData, int selectedTileIndex1Based, Matrix4x4 projection) {
        if (selectedTileIndex1Based <= 0 || selectedTileIndex1Based > frameData.getTiles().size()) {
            return;
        }
        TileInstance tile = frameData.getTiles().get(selectedTileIndex1Based - 1);
        if (tile.getMin() != null && tile.getMax() != null) {
            double[] mm = {
                tile.getMin().x(), tile.getMin().y(), tile.getMin().z(),
                tile.getMax().x(), tile.getMax().y(), tile.getMax().z()
            };
            Jogl4MinMaxRenderer.draw(gl, mm, camera);
        }
        float[] mvp = projection.exportToFloatArrayColumnOrder();
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glPushMatrix();
        gl2.glLoadMatrixf(mvp, 0);
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glPushMatrix();
        gl2.glLoadIdentity();

        gl2.glDisable(GL2.GL_LIGHTING);
        gl2.glPointSize(3.0f);
        gl2.glColor3d(1.0, 1.0, 1.0);
        gl2.glBegin(GL2.GL_POINTS);
        for (Vector3D p : tile.getPoints()) {
            gl2.glVertex3d(p.x(), p.y(), p.z());
        }
        gl2.glEnd();

        gl2.glPopMatrix();
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glPopMatrix();
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        int[] major = new int[1];
        int[] minor = new int[1];
        gl.glGetIntegerv(GL4.GL_MAJOR_VERSION, major, 0);
        gl.glGetIntegerv(GL4.GL_MINOR_VERSION, minor, 0);
        if (major[0] < 4 || (major[0] == 4 && minor[0] < 1)) {
            throw new IllegalStateException("OpenGL 4.1+ required. Current: " + major[0] + "." + minor[0]);
        }
        hudRenderer.initializeIfNeeded();
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        hudRenderer.dispose(gl);
        Jogl4MinMaxRenderer.dispose(gl);
        Jogl4CameraRenderer.dispose(gl);
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        GL4 gl = drawable.getGL().getGL4();
        gl.glViewport(0, 0, width, height);
        camera.updateViewportResize(width, height);
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        if (cameraController.processMouseClickedEvent(AwtSystem.awt2vsdkEvent(e))) requestRedraw();
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (cameraController.processMousePressedEvent(AwtSystem.awt2vsdkEvent(e))) requestRedraw();
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        if (cameraController.processMouseReleasedEvent(AwtSystem.awt2vsdkEvent(e))) requestRedraw();
    }

    @Override
    public void mouseEntered(MouseEvent e) {
        if (canvas != null) canvas.requestFocusInWindow();
    }

    @Override
    public void mouseExited(MouseEvent e) {
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        if (cameraController.processMouseDraggedEvent(AwtSystem.awt2vsdkEvent(e))) requestRedraw();
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        if (cameraController.processMouseMovedEvent(AwtSystem.awt2vsdkEvent(e))) requestRedraw();
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        if (cameraController.processMouseWheelEvent(AwtSystem.awt2vsdkEvent(e))) requestRedraw();
    }

    private void requestRedraw() {
        if (canvas != null && !closing) {
            SwingUtilities.invokeLater(canvas::display);
        }
    }

    private void requestClose() {
        if (closing) return;
        closing = true;

        if (canvas != null) canvas.destroy();
        if (frame != null) frame.dispose();

        shutdownHook.run();
    }
}
