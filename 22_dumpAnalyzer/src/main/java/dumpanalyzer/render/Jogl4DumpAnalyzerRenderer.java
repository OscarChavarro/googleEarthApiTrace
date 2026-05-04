package dumpanalyzer.render;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import dumpanalyzer.model.DumpAnalyzerModel;
import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;

import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.environment.Camera;
import vsdk.toolkit.gui.CameraControllerOrbiter;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBImageUncompressed;
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
    private final Jogl4AxisAlignedBoundingBoxRenderer axisAlignedBoundingBoxRenderer;
    private final Jogl4NeighborhoodRenderer neighborhoodRenderer;
    private final Camera viewingCamera;
    private final CameraControllerOrbiter cameraController;
    private volatile boolean closing;
    private GLCanvas canvas;
    private JFrame frame;
    private boolean offlineMode;
    private boolean offlineCaptureDone;
    private String offlineOutputPath;
    private int lastSelectedFrameIndex = -1;
    private int lastSelectedTileIndex = -1;
    private static final Vector3D DEFAULT_FRONT = new Vector3D(0.0, 0.0, -1.0);
    private static final Vector3D WORLD_ORIGIN = new Vector3D(0.0, 0.0, 0.0);
    private static final double MAX_ABS_COORD = 1.0e6;
    private static final double MIN_DIAGONAL = 1.0e-6;
    private static final double MAX_DIAGONAL = 1.0e6;
    private static final double DIAGONAL_MIN_RATIO = 1.0e-3;
    private static final double DIAGONAL_MAX_RATIO = 1.0e3;

    public Jogl4DumpAnalyzerRenderer(DumpAnalyzerModel model, Runnable shutdownHook) {
        this.model = model;
        this.shutdownHook = shutdownHook;
        this.hudRenderer = new Jogl4HudRenderer();
        this.axisAlignedBoundingBoxRenderer = new Jogl4AxisAlignedBoundingBoxRenderer();
        this.neighborhoodRenderer = new Jogl4NeighborhoodRenderer();
        this.viewingCamera = model.getViewingCamera();
        this.cameraController = new CameraControllerOrbiter(viewingCamera);
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

    public void startOffscreen(String outputPath, int width, int height) {
        if (!Jogl4Renderer.verifyOpenGLAvailability()) {
            System.out.println("Can not start OpenGL/JOGL renderer.");
            return;
        }
        offlineMode = true;
        offlineCaptureDone = false;
        offlineOutputPath = outputPath;

        GLProfile profile = GLProfile.get(GLProfile.GL4bc);
        GLCapabilities caps = new GLCapabilities(profile);
        caps.setDoubleBuffered(false);

        GLDrawableFactory creator = GLDrawableFactory.getFactory(profile);
        GLOffscreenAutoDrawable pbuffer = creator.createOffscreenAutoDrawable(
            null, caps, null, Math.max(1, width), Math.max(1, height)
        );
        pbuffer.addGLEventListener(this);
        pbuffer.display();
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        DumpAnalyzerModel.HudState state = model.snapshotHudState();
        List<Frame> frames = model.snapshotFrames();
        if (model.isUsingGoogleCameraAsView()) {
            recenterCameraIfSelectionChanged(state, frames);
        }
        GL4 gl = drawable.getGL().getGL4();
        gl.glEnable(GL4.GL_DEPTH_TEST);
        gl.glClearColor(0, 0, 0, 1);
        gl.glClear(GL4.GL_COLOR_BUFFER_BIT | GL4.GL_DEPTH_BUFFER_BIT);
        Camera activeCamera = model.getActiveCamera();
        Matrix4x4 projection = projectionForCurrentState(state, frames, activeCamera, model.isUsingGoogleCameraAsView());
        drawObjectsGL(gl, projection, model.isUsingGoogleCameraAsView());
        List<Jogl4HudRenderer.ScreenLabel> aabbLabels = List.of();
        if (state.selectedFrameIndex() >= 0 && state.selectedFrameIndex() < frames.size()) {
            Frame selectedFrame = frames.get(state.selectedFrameIndex());
            drawSelectedTile(
                gl,
                drawable.getGL().getGL2(),
                selectedFrame,
                state.selectedTileIndex(),
                projection,
                model.isUsingGoogleCameraAsView()
            );
            if (model.getRendererConfiguration().isBoundingVolumeSet()) {
                neighborhoodRenderer.drawForSelection(
                    drawable.getGL().getGL2(),
                    selectedFrame,
                    state.selectedTileIndex(),
                    projection,
                    model.isUsingGoogleCameraAsView(),
                    viewingCamera,
                    drawable.getSurfaceWidth(),
                    drawable.getSurfaceHeight()
                );
            }
            aabbLabels = buildAabbLabelsForHud(
                selectedFrame,
                state.selectedTileIndex(),
                projection,
                model.isUsingGoogleCameraAsView(),
                drawable.getSurfaceWidth(),
                drawable.getSurfaceHeight()
            );
        }
        String hudTexturePath = null;
        if (!model.getRendererConfiguration().isTextureSet()
            && state.selectedFrameIndex() >= 0
            && state.selectedFrameIndex() < frames.size()) {
            hudTexturePath = model.getTexturePath(
                frames.get(state.selectedFrameIndex()).getId(),
                state.selectedTextureId()
            );
        }
        hudRenderer.render(drawable, model, state, activeCamera, hudTexturePath, aabbLabels);
        if (offlineMode && !offlineCaptureDone) {
            captureOffscreen(drawable, gl);
            offlineCaptureDone = true;
        }
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
        if (tileIndex == DumpAnalyzerModel.SELECT_ALL_TILES) {
            recenterCameraToAllTiles(frameData, frameStats);
            return;
        }
        if (tileIndex < 0 || tileIndex >= frameData.getTiles().size()) {
            return;
        }
        TileInstance tile = frameData.getTiles().get(tileIndex);
        Vector3D[] transformed = CoordinatesTransforms.transformAabb(
            tile.getMin(), tile.getMax(), frameData.getModelViewMatrix()
        );
        if (!isValidAndConsistentAabb(transformed[0], transformed[1], frameStats)) {
            return;
        }

        Vector3D min = transformed[0];
        Vector3D max = transformed[1];
        double dx = max.x() - min.x();
        double dy = max.y() - min.y();
        double dz = max.z() - min.z();
        double diagonal = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double distance = Math.max(1e-6, 1.5 * diagonal);

        Vector3D front = safeFront();
        Vector3D newPosition = WORLD_ORIGIN.subtract(front.multiply(distance));
        if (!isFiniteVector(newPosition) || !isFinite(distance)) {
            return;
        }
        cameraController.setPointOfInterest(WORLD_ORIGIN);
        viewingCamera.setPosition(newPosition);
        viewingCamera.setFocusedPositionMaintainingOrthogonality(WORLD_ORIGIN);
        setSafePlanes(distance);
    }

    private void drawObjectsGL(GL4 gl, Matrix4x4 projection, boolean useGoogleCameraView) {
        Matrix4x4 helperProjection = projection;
        if (!useGoogleCameraView) {
            Matrix4x4 view = viewingCamera.calculateTransformationMatrix();
            helperProjection = projection.multiply(view);
        }
        Jogl4MatrixRenderer.draw(gl, helperProjection, Matrix4x4.identityMatrix());
        Camera googleCamera = model.getGoogleCamera();
        if (googleCamera != null) {
            Jogl4CameraRenderer.draw(gl, googleCamera, helperProjection);
        }
    }

    private void drawSelectedTile(
        GL4 gl,
        GL2 gl2,
        Frame frameData,
        int selectedTileIndex,
        Matrix4x4 projection,
        boolean useGoogleCameraView
    ) {
        if (!model.getRendererConfiguration().isTextureSet()) {
            gl2.glActiveTexture(GL2.GL_TEXTURE0);
            gl2.glBindTexture(GL2.GL_TEXTURE_2D, 0);
            gl2.glDisable(GL2.GL_TEXTURE_2D);
        }

        if (selectedTileIndex == DumpAnalyzerModel.SELECT_ALL_TILES) {
            int tileOrdinal = 0;
            for (TileInstance tile : frameData.getTiles()) {
                drawTileWireframe(gl, gl2, frameData, tileOrdinal, tile, projection, false, useGoogleCameraView);
                tileOrdinal++;
            }
            if (model.getRendererConfiguration().isBoundingVolumeSet()) {
                axisAlignedBoundingBoxRenderer.drawForSelection(
                    gl2,
                    frameData,
                    selectedTileIndex,
                    projection,
                    useGoogleCameraView,
                    viewingCamera
                );
            }
            return;
        }
        if (selectedTileIndex < 0 || selectedTileIndex >= frameData.getTiles().size()) {
            return;
        }
        TileInstance tile = frameData.getTiles().get(selectedTileIndex);
        drawTileWireframe(gl, gl2, frameData, selectedTileIndex, tile, projection, false, useGoogleCameraView);
        if (model.getRendererConfiguration().isBoundingVolumeSet()) {
            axisAlignedBoundingBoxRenderer.drawForSelection(
                gl2,
                frameData,
                selectedTileIndex,
                projection,
                useGoogleCameraView,
                viewingCamera
            );
        }
    }

    private void drawTileWireframe(
        GL4 gl,
        GL2 gl2,
        Frame frameData,
        int tileIndex,
        TileInstance tile,
        Matrix4x4 projection,
        boolean drawAabb,
        boolean useGoogleCameraView
    ) {
        double[] combinedModelView = CoordinatesTransforms.geometryModelView(
            useGoogleCameraView, viewingCamera, frameData
        );
        if (useGoogleCameraView && tile.getModelViewMatrix() != null && tile.getModelViewMatrix().length == 16) {
            combinedModelView = tile.getModelViewMatrix();
        }
        Jogl4TileRenderer.drawTile(
            gl,
            gl2,
            tile,
            frameData.getId(),
            projection,
            combinedModelView,
            drawAabb,
            model,
            hudRenderer,
            model.getActiveCamera()
        );
    }

    private List<Jogl4HudRenderer.ScreenLabel> buildAabbLabelsForHud(
        Frame frameData,
        int selectedTileIndex,
        Matrix4x4 projection,
        boolean useGoogleCameraView,
        int viewportWidth,
        int viewportHeight
    ) {
        if (!model.getRendererConfiguration().isBoundingVolumeSet()) {
            return List.of();
        }
        return axisAlignedBoundingBoxRenderer.buildLabelsForSelection(
            frameData,
            selectedTileIndex,
            projection,
            useGoogleCameraView,
            viewingCamera,
            viewportWidth,
            viewportHeight
        );
    }

    private void recenterCameraToAllTiles(Frame frameData, AabbStats frameStats) {
        Vector3D min = null;
        Vector3D max = null;
        for (TileInstance tile : frameData.getTiles()) {
            Vector3D[] transformed = CoordinatesTransforms.transformAabb(
                tile.getMin(), tile.getMax(), frameData.getModelViewMatrix()
            );
            Vector3D tileMin = transformed[0];
            Vector3D tileMax = transformed[1];
            if (!isValidAndConsistentAabb(tileMin, tileMax, frameStats)) {
                continue;
            }
            if (min == null) {
                min = tileMin;
                max = tileMax;
                continue;
            }
            min = new Vector3D(
                Math.min(min.x(), tileMin.x()),
                Math.min(min.y(), tileMin.y()),
                Math.min(min.z(), tileMin.z())
            );
            max = new Vector3D(
                Math.max(max.x(), tileMax.x()),
                Math.max(max.y(), tileMax.y()),
                Math.max(max.z(), tileMax.z())
            );
        }
        if (min == null || max == null) {
            return;
        }
        double dx = max.x() - min.x();
        double dy = max.y() - min.y();
        double dz = max.z() - min.z();
        double diagonal = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double distance = Math.max(1e-6, 1.5 * diagonal);

        Vector3D front = safeFront();
        Vector3D newPosition = WORLD_ORIGIN.subtract(front.multiply(distance));
        if (!isFiniteVector(newPosition) || !isFinite(distance)) {
            return;
        }
        cameraController.setPointOfInterest(WORLD_ORIGIN);
        viewingCamera.setPosition(newPosition);
        viewingCamera.setFocusedPositionMaintainingOrthogonality(WORLD_ORIGIN);
        setSafePlanes(distance);
    }

    private Matrix4x4 projectionForCurrentState(
        DumpAnalyzerModel.HudState state,
        List<Frame> frames,
        Camera activeCamera,
        boolean useGoogleCameraView
    ) {
        if (useGoogleCameraView) {
            int frameIndex = state.selectedFrameIndex();
            if (frameIndex >= 0 && frameIndex < frames.size()) {
                Matrix4x4 fromFrame = matrixFromColumnMajor(frames.get(frameIndex).getProjectionMatrix());
                if (fromFrame != null) {
                    return fromFrame;
                }
            }
        }
        // Fallback for viewing camera or missing frame projection.
        return activeCamera.calculateViewVolumeMatrix();
    }

    private Matrix4x4 matrixFromColumnMajor(double[] m) {
        if (m == null || m.length != 16) {
            return null;
        }
        Matrix4x4 out = new Matrix4x4();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                out = out.withVal(row, col, m[col * 4 + row]);
            }
        }
        return out;
    }

    private Vector3D safeFront() {
        Vector3D raw = viewingCamera.getFront();
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
        viewingCamera.setNearPlaneDistance(near);
        viewingCamera.setFarPlaneDistance(far);
    }

    private AabbStats computeAabbStats(Frame frameData) {
        double minDiagonal = Double.POSITIVE_INFINITY;
        double maxDiagonal = 0.0;
        double sumDiagonal = 0.0;
        int count = 0;
        for (TileInstance tile : frameData.getTiles()) {
            Vector3D[] transformed = CoordinatesTransforms.transformAabb(
                tile.getMin(), tile.getMax(), frameData.getModelViewMatrix()
            );
            Vector3D min = transformed[0];
            Vector3D max = transformed[1];
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
        viewingCamera.updateViewportResize(width, height);
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

    private void captureOffscreen(GLAutoDrawable drawable, GL4 gl) {
        int width = Math.max(1, drawable.getSurfaceWidth());
        int height = Math.max(1, drawable.getSurfaceHeight());
        ByteBuffer bb = ByteBuffer.allocateDirect(3 * width * height);
        gl.glPixelStorei(GL.GL_PACK_ALIGNMENT, 1);
        gl.glReadPixels(0, 0, width, height, GL.GL_RGB, GL.GL_UNSIGNED_BYTE, bb);

        RGBImageUncompressed image = new RGBImageUncompressed();
        image.init(width, height);
        int pos = 0;
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                image.putPixel(x, y, bb.get(pos), bb.get(pos + 1), bb.get(pos + 2));
                pos += 3;
            }
        }
        File out = new File(offlineOutputPath == null || offlineOutputPath.isBlank()
            ? "output.png"
            : offlineOutputPath);
        ImagePersistence.exportPNG(out, image);
        if (drawable instanceof GLOffscreenAutoDrawable offscreen) {
            offscreen.destroy();
        }
    }

    private static double[] multiplyColumnMajor(double[] a, double[] b) {
        if (a == null || a.length != 16) {
            return b;
        }
        if (b == null || b.length != 16) {
            return a;
        }
        double[] out = new double[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                out[col * 4 + row] =
                    a[0 * 4 + row] * b[col * 4 + 0] +
                    a[1 * 4 + row] * b[col * 4 + 1] +
                    a[2 * 4 + row] * b[col * 4 + 2] +
                    a[3 * 4 + row] * b[col * 4 + 3];
            }
        }
        return out;
    }
}
