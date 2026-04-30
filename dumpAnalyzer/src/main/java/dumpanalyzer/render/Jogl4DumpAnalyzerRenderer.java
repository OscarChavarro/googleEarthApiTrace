package dumpanalyzer.render;

import java.awt.BorderLayout;
import java.awt.Dimension;
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
import dumpanalyzer.model.DumpAnalyzerModel;
import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;

import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.environment.Camera;
import vsdk.toolkit.gui.CameraControllerOrbiter;
import vsdk.toolkit.render.jogl.Jogl4CameraRenderer;
import vsdk.toolkit.render.jogl.Jogl4MatrixRenderer;
import vsdk.toolkit.render.jogl.Jogl4MinMaxRenderer;
import vsdk.toolkit.render.jogl.Jogl4Renderer;
import vsdk.toolkit.common.linealAlgebra.Vector3D;

public class Jogl4DumpAnalyzerRenderer implements
    GLEventListener {

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
    private static final Vector3D DEFAULT_FRONT = new Vector3D(0.0, 0.0, -1.0);
    private static final double MAX_ABS_COORD = 1.0e6;
    private static final double MIN_DIAGONAL = 1.0e-6;
    private static final double MAX_DIAGONAL = 1.0e6;
    private static final double DIAGONAL_MIN_RATIO = 1.0e-3;
    private static final double DIAGONAL_MAX_RATIO = 1.0e3;

    public Jogl4DumpAnalyzerRenderer(DumpAnalyzerModel model, Runnable shutdownHook) {
        this.model = model;
        this.shutdownHook = shutdownHook;
        this.hudRenderer = new Jogl4HudRenderer();
        this.camera = new Camera();
        this.camera.setName("ViewingCamera");
        this.cameraController = new CameraControllerOrbiter(camera);
        this.cameraController.setDeltaMovement(0.2);
    }

    public interface InteractionInstaller {
        void install(GLCanvas canvas, CameraControllerOrbiter cameraController, Runnable closeAction, Runnable repaintAction);
    }

    public void start(InteractionInstaller interactionInstaller) {
        if (!Jogl4Renderer.verifyOpenGLAvailability()) {
            System.out.println("Can not start OpenGL/JOGL renderer.");
            return;
        }

        GLProfile profile = GLProfile.get(GLProfile.GL4bc);
        GLCapabilities caps = new GLCapabilities(profile);
        caps.setDepthBits(64);
        canvas = new GLCanvas(caps);
        canvas.addGLEventListener(this);
        canvas.setFocusable(true);

        frame = new JFrame("dumpAnalyzer HUD - ESC to exit");
        frame.add(canvas, BorderLayout.CENTER);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(900, 540));
        frame.setSize(new Dimension(1200, 720));
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                requestClose();
            }
        });

        if (interactionInstaller != null) {
            interactionInstaller.install(canvas, cameraController, this::requestClose, this::requestRedraw);
        }

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
        if (state.selectedFrameIndex() >= 0 && state.selectedFrameIndex() < frames.size()) {
            drawSelectedTile(gl, drawable.getGL().getGL2(), frames.get(state.selectedFrameIndex()), state.selectedTileIndex(), projection);
        }
        String hudTexturePath = model.getRendererConfiguration().isTextureSet()
            ? null
            : model.getTexturePath(state.selectedTextureId());
        hudRenderer.render(drawable, state, camera, hudTexturePath);
    }

    private void recenterCameraIfSelectionChanged(DumpAnalyzerModel.HudState state, List<Frame> frames) {
        int frameIndex = state.selectedFrameIndex();
        int tileIndex = state.selectedTileIndex();
        if (frameIndex == lastSelectedFrameIndex && tileIndex == lastSelectedTileIndex) {
            return;
        }
        lastSelectedFrameIndex = frameIndex;
        lastSelectedTileIndex = tileIndex;

        if (frameIndex < 0 || frameIndex >= frames.size()) {
            return;
        }
        Frame frameData = frames.get(frameIndex);
        AabbStats frameStats = computeAabbStats(frameData);
        if (tileIndex == -1) {
            recenterCameraToAllTiles(frameData, frameStats);
            logSelectedTile(frameData, tileIndex);
            return;
        }
        if (tileIndex < 0 || tileIndex >= frameData.getTiles().size()) {
            return;
        }
        TileInstance tile = frameData.getTiles().get(tileIndex);
        logSelectedTile(frameData, tileIndex);
        if (!isValidAndConsistentAabb(tile.getMin(), tile.getMax(), frameStats)) {
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

        Vector3D front = safeFront();
        Vector3D newPosition = center.subtract(front.multiply(distance));
        if (!isFiniteVector(newPosition) || !isFinite(distance)) {
            return;
        }
        cameraController.setPointOfInterest(center);
        camera.setPosition(newPosition);
        camera.setFocusedPositionMaintainingOrthogonality(center);
        setSafePlanes(distance);
    }

    private void drawObjectsGL(GL4 gl, Matrix4x4 projection) {
        Jogl4MatrixRenderer.draw(gl, projection, Matrix4x4.identityMatrix());
    }

    private void drawSelectedTile(GL4 gl, GL2 gl2, Frame frameData, int selectedTileIndex, Matrix4x4 projection) {
        if (!model.getRendererConfiguration().isTextureSet()) {
            gl2.glActiveTexture(GL2.GL_TEXTURE0);
            gl2.glBindTexture(GL2.GL_TEXTURE_2D, 0);
            gl2.glDisable(GL2.GL_TEXTURE_2D);
        }

        if (selectedTileIndex == -1) {
            int tileOrdinal = 0;
            for (TileInstance tile : frameData.getTiles()) {
                drawTileWireframe(gl, gl2, frameData.getId(), tileOrdinal, tile, projection, false);
                tileOrdinal++;
            }
            return;
        }
        if (selectedTileIndex < 0 || selectedTileIndex >= frameData.getTiles().size()) {
            return;
        }
        TileInstance tile = frameData.getTiles().get(selectedTileIndex);
        drawTileWireframe(gl, gl2, frameData.getId(), selectedTileIndex, tile, projection, true);
    }

    private void drawTileWireframe(
        GL4 gl,
        GL2 gl2,
        int frameId,
        int tileIndex,
        TileInstance tile,
        Matrix4x4 projection,
        boolean drawAabb
    ) {
        Jogl4TileRenderer.drawTile(gl, gl2, tile, projection, drawAabb, model, hudRenderer, camera);
    }

    private void recenterCameraToAllTiles(Frame frameData, AabbStats frameStats) {
        Vector3D min = null;
        Vector3D max = null;
        for (TileInstance tile : frameData.getTiles()) {
            if (!isValidAndConsistentAabb(tile.getMin(), tile.getMax(), frameStats)) {
                continue;
            }
            if (min == null) {
                min = tile.getMin();
                max = tile.getMax();
                continue;
            }
            min = new Vector3D(
                Math.min(min.x(), tile.getMin().x()),
                Math.min(min.y(), tile.getMin().y()),
                Math.min(min.z(), tile.getMin().z())
            );
            max = new Vector3D(
                Math.max(max.x(), tile.getMax().x()),
                Math.max(max.y(), tile.getMax().y()),
                Math.max(max.z(), tile.getMax().z())
            );
        }
        if (min == null || max == null) {
            return;
        }
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

        Vector3D front = safeFront();
        Vector3D newPosition = center.subtract(front.multiply(distance));
        if (!isFiniteVector(newPosition) || !isFinite(distance)) {
            return;
        }
        cameraController.setPointOfInterest(center);
        camera.setPosition(newPosition);
        camera.setFocusedPositionMaintainingOrthogonality(center);
        setSafePlanes(distance);
    }

    private Vector3D safeFront() {
        Vector3D raw = camera.getFront();
        if (raw == null || !isFiniteVector(raw)) {
            return DEFAULT_FRONT;
        }
        double len = raw.length();
        if (!isFinite(len) || len < 1e-12) {
            return DEFAULT_FRONT;
        }
        Vector3D n = raw.normalized();
        if (!isFiniteVector(n)) {
            return DEFAULT_FRONT;
        }
        return n;
    }

    private void setSafePlanes(double distance) {
        double d = isFinite(distance) ? distance : 1.0;
        d = Math.max(1e-3, d);
        double near = Math.max(1e-4, d / 10.0);
        double far = Math.max(near + 1e-3, d * 3.0);
        camera.setNearPlaneDistance(near);
        camera.setFarPlaneDistance(far);
    }

    private void logSelectedTile(Frame frameData, int tileIndex) {
        if (frameData == null || tileIndex < 0 || tileIndex >= frameData.getTiles().size()) {
            return;
        }
        TileInstance tile = frameData.getTiles().get(tileIndex);
        String base =
            "[dumpAnalyzer] Frame " + frameData.getId() +
            ", Tile " + tileIndex +
            ", Call: " + tile.getGlCall() +
            ", Primitive " + tile.getPrimitive() +
            ", ParserCall " + tile.getParserCall() +
            "";
        if (tile.isSkipped()) {
            String reason = tile.getSkipReason();
            if (reason == null || reason.isBlank()) {
                reason = "unknown";
            }
            System.out.println(base + ", Skipped true, Reason " + reason);
        }
        else {
            System.out.println(
                base +
                ", VertexArraySize " + tile.getVertexArraySize() +
                ", IndexArraySize " + tile.getIndexArraySize()
            );
        }
    }

    private AabbStats computeAabbStats(Frame frameData) {
        double minDiagonal = Double.POSITIVE_INFINITY;
        double maxDiagonal = 0.0;
        double sumDiagonal = 0.0;
        int count = 0;
        for (TileInstance tile : frameData.getTiles()) {
            Vector3D min = tile.getMin();
            Vector3D max = tile.getMax();
            if (!isValidAabb(min, max)) {
                continue;
            }
            double diag = diagonal(min, max);
            minDiagonal = Math.min(minDiagonal, diag);
            maxDiagonal = Math.max(maxDiagonal, diag);
            sumDiagonal += diag;
            count++;
        }
        if (count <= 0) {
            return AabbStats.EMPTY;
        }
        return new AabbStats(minDiagonal, maxDiagonal, sumDiagonal / count, count);
    }

    private boolean isValidAndConsistentAabb(Vector3D min, Vector3D max, AabbStats frameStats) {
        if (!isValidAabb(min, max)) {
            return false;
        }
        double diag = diagonal(min, max);
        if (!frameStats.hasValues()) {
            return true;
        }
        double lower = Math.max(MIN_DIAGONAL, frameStats.averageDiagonal() * DIAGONAL_MIN_RATIO);
        double upper = Math.min(MAX_DIAGONAL, frameStats.averageDiagonal() * DIAGONAL_MAX_RATIO);
        return diag >= lower && diag <= upper;
    }

    private static boolean isValidAabb(Vector3D min, Vector3D max) {
        if (!isFiniteVector(min) || !isFiniteVector(max)) {
            return false;
        }
        if (Math.abs(min.x()) > MAX_ABS_COORD || Math.abs(min.y()) > MAX_ABS_COORD || Math.abs(min.z()) > MAX_ABS_COORD) {
            return false;
        }
        if (Math.abs(max.x()) > MAX_ABS_COORD || Math.abs(max.y()) > MAX_ABS_COORD || Math.abs(max.z()) > MAX_ABS_COORD) {
            return false;
        }
        if (min.x() > max.x() || min.y() > max.y() || min.z() > max.z()) {
            return false;
        }
        double diagonal = diagonal(min, max);
        return isFinite(diagonal) && diagonal >= MIN_DIAGONAL && diagonal <= MAX_DIAGONAL;
    }

    private static double diagonal(Vector3D min, Vector3D max) {
        double dx = max.x() - min.x();
        double dy = max.y() - min.y();
        double dz = max.z() - min.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean isFiniteVector(Vector3D v) {
        return v != null && isFinite(v.x()) && isFinite(v.y()) && isFinite(v.z());
    }

    private static boolean isFinite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private record AabbStats(double minDiagonal, double maxDiagonal, double averageDiagonal, int count) {
        private static final AabbStats EMPTY = new AabbStats(0.0, 0.0, 0.0, 0);

        private boolean hasValues() {
            return count > 0 && isFinite(averageDiagonal) && averageDiagonal > 0.0;
        }
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
