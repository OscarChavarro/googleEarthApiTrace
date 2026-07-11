package dumpanalyzer.render;

import java.awt.BorderLayout;
import java.awt.Color;
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
import dumpanalyzer.processing.TriangleMeshVertexComparator;
import dumpanalyzer.processing.TriangleStripTileClassifier;
import dumpanalyzer.processing.TriangleStripTileTopology;
import dumpanalyzer.processing.bigtiles.GlobeLevelTileSetsProcessor;
import dumpanalyzer.processing.uncles.ToUncleRelationship;

import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.gui.CameraControllerOrbiter;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBImageUncompressed;
import vsdk.toolkit.render.jogl.Jogl4CameraRenderer;
import vsdk.toolkit.render.jogl.Jogl4MatrixRenderer;
import vsdk.toolkit.render.jogl.Jogl4MinMaxRenderer;
import vsdk.toolkit.render.jogl.Jogl4Renderer;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

public class Jogl4DumpAnalyzerRenderer implements
    GLEventListener {
    private final Runnable shutdownHook;
    private final DumpAnalyzerModel model;
    private final Jogl4HudRenderer hudRenderer;
    private final Jogl4AxisAlignedBoundingBoxRenderer axisAlignedBoundingBoxRenderer;
    private final Jogl4NeighborRelationshipRenderer neighborhoodRenderer;
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
    private static final Vector3Dd DEFAULT_FRONT = new Vector3Dd(0.0, 0.0, -1.0);
    private static final Vector3Dd WORLD_ORIGIN = new Vector3Dd(0.0, 0.0, 0.0);
    private static final double MAX_ABS_COORD = 1.0e6;
    private static final double MIN_DIAGONAL = 1.0e-6;
    private static final double MAX_DIAGONAL = 1.0e6;
    private static final double DIAGONAL_MIN_RATIO = 1.0e-3;
    private static final double DIAGONAL_MAX_RATIO = 1.0e3;
    private static final int SPECIAL_TILE_TRIANGLE_STRIP_COUNT = 320;
    private static final int GROUPED_TILE_TRIANGLE_STRIP_MIN_COUNT = 2;
    private static final int VERTEX_LABEL_X_OFFSET_PIXELS = 6;
    private static final Color SPECIAL_TRIANGLE_STRIP_LABEL_COLOR = Color.CYAN;

    public Jogl4DumpAnalyzerRenderer(DumpAnalyzerModel model, Runnable shutdownHook) {
        this.model = model;
        this.shutdownHook = shutdownHook;
        this.hudRenderer = new Jogl4HudRenderer();
        this.axisAlignedBoundingBoxRenderer = new Jogl4AxisAlignedBoundingBoxRenderer();
        this.neighborhoodRenderer = new Jogl4NeighborRelationshipRenderer();
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
        Matrix4x4d projection = projectionForCurrentState(state, frames, activeCamera, model.isUsingGoogleCameraAsView());
        drawObjectsGL(gl, projection, model.isUsingGoogleCameraAsView());
        List<Jogl4HudRenderer.ScreenLabel> hudLabels = new ArrayList<>();
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
            hudLabels.addAll(buildAabbLabelsForHud(
                selectedFrame,
                state.selectedTileIndex(),
                projection,
                model.isUsingGoogleCameraAsView(),
                drawable.getSurfaceWidth(),
                drawable.getSurfaceHeight()
            ));
            hudLabels.addAll(buildSpecialTriangleStripLabelsForHud(
                selectedFrame,
                projection,
                model.isUsingGoogleCameraAsView(),
                drawable.getSurfaceWidth(),
                drawable.getSurfaceHeight()
            ));
            if (model.getRendererConfiguration().isPointsSet()) {
                hudLabels.addAll(buildSelectedTileVertexLabelsForHud(
                    selectedFrame,
                    state.selectedTileIndex(),
                    projection,
                    model.isUsingGoogleCameraAsView(),
                    drawable.getSurfaceWidth(),
                    drawable.getSurfaceHeight()
                ));
            }
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
        hudRenderer.render(drawable, model, state, activeCamera, hudTexturePath, hudLabels);
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
        List<TileInstance> selectableTiles = frameData.getSelectableTiles();
        if (tileIndex < 0 || tileIndex >= selectableTiles.size()) {
            return;
        }
        TileInstance tile = selectableTiles.get(tileIndex);
        Vector3Dd[] transformed = CoordinatesTransforms.transformAabb(
            tile.getMin(), tile.getMax(), frameData.getModelViewMatrix()
        );
        if (!isValidAndConsistentAabb(transformed[0], transformed[1], frameStats)) {
            return;
        }

        Vector3Dd min = transformed[0];
        Vector3Dd max = transformed[1];
        double dx = max.x() - min.x();
        double dy = max.y() - min.y();
        double dz = max.z() - min.z();
        double diagonal = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double distance = Math.max(1e-6, 1.5 * diagonal);

        Vector3Dd front = safeFront();
        Vector3Dd newPosition = WORLD_ORIGIN.subtract(front.multiply(distance));
        if (!isFiniteVector(newPosition) || !isFinite(distance)) {
            return;
        }
        cameraController.setPointOfInterest(WORLD_ORIGIN);
        viewingCamera.setPosition(newPosition);
        viewingCamera.setFocusedPositionMaintainingOrthogonality(WORLD_ORIGIN);
        setSafePlanes(distance);
    }

    private void drawObjectsGL(GL4 gl, Matrix4x4d projection, boolean useGoogleCameraView) {
        Matrix4x4d helperProjection = projection;
        if (!useGoogleCameraView) {
            Matrix4x4d view = viewingCamera.calculateTransformationMatrix();
            helperProjection = projection.multiply(view);
        }
        Jogl4MatrixRenderer.draw(gl, helperProjection, Matrix4x4d.identityMatrix());
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
        Matrix4x4d projection,
        boolean useGoogleCameraView
    ) {
        if (!model.getRendererConfiguration().isTextureSet()) {
            gl2.glActiveTexture(GL2.GL_TEXTURE0);
            gl2.glBindTexture(GL2.GL_TEXTURE_2D, 0);
            gl2.glDisable(GL2.GL_TEXTURE_2D);
        }

        if (selectedTileIndex == DumpAnalyzerModel.SELECT_ALL_TILES) {
            int tileOrdinal = 0;
            for (TileInstance tile : frameData.getSelectableTiles()) {
                drawTileWireframe(gl, gl2, frameData, tileOrdinal, tile, projection, false, false, useGoogleCameraView);
                tileOrdinal++;
            }
            drawExtractedLinesForFrame(gl2, frameData, projection, useGoogleCameraView);
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
        List<TileInstance> selectableTiles = frameData.getSelectableTiles();
        if (selectedTileIndex < 0 || selectedTileIndex >= selectableTiles.size()) {
            return;
        }
        TileInstance tile = selectableTiles.get(selectedTileIndex);
        drawTileWireframe(gl, gl2, frameData, selectedTileIndex, tile, projection, false, true, useGoogleCameraView);
        for (TileInstance uncleTile : resolveUncleTiles(frameData, tile, selectableTiles)) {
            drawTileWireframe(gl, gl2, frameData, -1, uncleTile, projection, false, true, useGoogleCameraView);
        }
        drawExtractedLinesForFrame(gl2, frameData, projection, useGoogleCameraView);
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
        Matrix4x4d projection,
        boolean drawAabb,
        boolean singleTileMode,
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
            singleTileMode,
            model,
            hudRenderer,
            model.getActiveCamera()
        );
    }

    private void drawExtractedLinesForFrame(
        GL2 gl2,
        Frame frameData,
        Matrix4x4d projection,
        boolean useGoogleCameraView
    ) {
        double[] combinedModelView = CoordinatesTransforms.geometryModelView(
            useGoogleCameraView, viewingCamera, frameData
        );
        Jogl4TileRenderer.drawExtractedLineStrips(gl2, frameData.getLines(), projection, combinedModelView);
    }

    private List<Jogl4HudRenderer.ScreenLabel> buildAabbLabelsForHud(
        Frame frameData,
        int selectedTileIndex,
        Matrix4x4d projection,
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

    private List<Jogl4HudRenderer.ScreenLabel> buildSpecialTriangleStripLabelsForHud(
        Frame frameData,
        Matrix4x4d projection,
        boolean useGoogleCameraView,
        int viewportWidth,
        int viewportHeight
    ) {
        if (frameData == null || projection == null || viewportWidth <= 0 || viewportHeight <= 0) {
            return List.of();
        }

        List<Jogl4HudRenderer.ScreenLabel> labels = new ArrayList<>();
        boolean frameContainsSpecial320Tile = false;
        for (TileInstance tile : frameData.getSelectableTiles()) {
            if (tile != null && tile.getTriangleStripGeometries().size() == SPECIAL_TILE_TRIANGLE_STRIP_COUNT) {
                frameContainsSpecial320Tile = true;
                break;
            }
        }
        for (TileInstance tile : frameData.getSelectableTiles()) {
            if (tile == null) {
                continue;
            }
            List<TileInstance.TriangleStripGeometry> geometries = tile.getTriangleStripGeometries();
            boolean isSpecial320Tile = geometries.size() == SPECIAL_TILE_TRIANGLE_STRIP_COUNT;
            boolean isGroupedGreenTile = isGroupedGlobeLevelTile(tile);
            if (!isSpecial320Tile && (!isGroupedGreenTile || frameContainsSpecial320Tile)) {
                continue;
            }

            double[] modelView = CoordinatesTransforms.geometryModelView(useGoogleCameraView, viewingCamera, frameData);
            if (useGoogleCameraView && tile.getModelViewMatrix() != null && tile.getModelViewMatrix().length == 16) {
                modelView = tile.getModelViewMatrix();
            }

            for (int stripIndex = 0; stripIndex < geometries.size(); stripIndex++) {
                Vector3Dd center = centerOfTriangleStrip(geometries.get(stripIndex));
                if (center == null) {
                    continue;
                }
                Integer identityId = GlobeLevelTileSetsProcessor.findGlobeLevelTileIdentityId(geometries.get(stripIndex));
                if (identityId == null) {
                    if (!isSpecial320Tile) {
                        continue;
                    }
                    identityId = stripIndex;
                }
                int[] pixel = projectToViewportPixel(center, modelView, projection, viewportWidth, viewportHeight);
                if (pixel == null) {
                    continue;
                }
                labels.add(new Jogl4HudRenderer.ScreenLabel(
                    pixel[0],
                    pixel[1],
                    String.valueOf(identityId),
                    SPECIAL_TRIANGLE_STRIP_LABEL_COLOR
                ));
            }
        }
        return labels;
    }

    private List<Jogl4HudRenderer.ScreenLabel> buildSelectedTileVertexLabelsForHud(
        Frame frameData,
        int selectedTileIndex,
        Matrix4x4d projection,
        boolean useGoogleCameraView,
        int viewportWidth,
        int viewportHeight
    ) {
        if (frameData == null
            || selectedTileIndex == DumpAnalyzerModel.SELECT_ALL_TILES
            || selectedTileIndex < 0
            || selectedTileIndex >= frameData.getSelectableTiles().size()
            || projection == null
            || viewportWidth <= 0
            || viewportHeight <= 0) {
            return List.of();
        }

        TileInstance tile = frameData.getSelectableTiles().get(selectedTileIndex);
        TileInstance.TriangleStripGeometry geometry = tile.getTriangleStrip();
        TriangleStripTileClassifier classifier = new TriangleStripTileClassifier();
        TriangleStripTileTopology topology = classifier.classify(geometry);
        if (topology == TriangleStripTileTopology.UNKNOWN) {
            return List.of();
        }

        List<TileInstance.TriangleStripVertex> vertices = classifier.deduplicateVertices(
            geometry.vertices(),
            TriangleMeshVertexComparator.VERTEX_EPSILON
        );
        if (vertices.isEmpty()) {
            return List.of();
        }

        double[] modelView = CoordinatesTransforms.geometryModelView(useGoogleCameraView, viewingCamera, frameData);
        if (useGoogleCameraView && tile.getModelViewMatrix() != null && tile.getModelViewMatrix().length == 16) {
            modelView = tile.getModelViewMatrix();
        }

        List<Jogl4HudRenderer.ScreenLabel> labels = new ArrayList<>();
        for (int i = 0; i < vertices.size(); i++) {
            TileInstance.TriangleStripVertex v = vertices.get(i);
            int[] pixel = projectToViewportPixel(
                new Vector3Dd(v.x(), v.y(), v.z()),
                modelView,
                projection,
                viewportWidth,
                viewportHeight
            );
            if (pixel == null) {
                continue;
            }
            double[] c = Jogl4TileVertexRenderer.colorForIndex(i);
            labels.add(new Jogl4HudRenderer.ScreenLabel(
                pixel[0] + VERTEX_LABEL_X_OFFSET_PIXELS,
                pixel[1],
                String.valueOf(i),
                new Color((float)c[0], (float)c[1], (float)c[2])
            ));
        }
        return labels;
    }

    private List<TileInstance> resolveUncleTiles(Frame frameData, TileInstance tile, List<TileInstance> searchSpace) {
        if (frameData == null || tile == null || tile.getUncles() == null || tile.getUncles().isEmpty()) {
            return List.of();
        }
        List<TileInstance> resolved = new ArrayList<>();
        for (ToUncleRelationship relationship : tile.getUncles()) {
            if (relationship == null || relationship.uncleContentId() == null) {
                continue;
            }
            TileInstance uncleTile = findTileByContentId(searchSpace, relationship.uncleContentId());
            if (uncleTile != null && !resolved.contains(uncleTile)) {
                resolved.add(uncleTile);
            }
        }
        return resolved;
    }

    private TileInstance findTileByContentId(List<TileInstance> tiles, String contentId) {
        if (tiles == null || contentId == null) {
            return null;
        }
        for (TileInstance candidate : tiles) {
            if (candidate != null && contentId.equals(candidate.getContentId())) {
                return candidate;
            }
        }
        return null;
    }

    private static Vector3Dd centerOfTriangleStrip(TileInstance.TriangleStripGeometry geometry) {
        if (geometry == null || geometry.vertices() == null || geometry.vertices().isEmpty()) {
            return null;
        }
        double sx = 0.0;
        double sy = 0.0;
        double sz = 0.0;
        int count = 0;
        for (TileInstance.TriangleStripVertex vertex : geometry.vertices()) {
            if (vertex == null) {
                continue;
            }
            sx += vertex.x();
            sy += vertex.y();
            sz += vertex.z();
            count++;
        }
        if (count <= 0) {
            return null;
        }
        double inv = 1.0 / count;
        return new Vector3Dd(sx * inv, sy * inv, sz * inv);
    }

    private static boolean isGroupedGlobeLevelTile(TileInstance tile) {
        if (tile == null || tile.isSyntheticGlobeLevelTile() || tile.getGlobeLevelTileSet() == null) {
            return false;
        }
        return !tile.getGlobeLevelTileSet().shouldDrawSourceTile()
            && tile.getStrips() != null
            && tile.getStrips().size() >= GROUPED_TILE_TRIANGLE_STRIP_MIN_COUNT;
    }

    private static int[] projectToViewportPixel(
        Vector3Dd p,
        double[] modelView,
        Matrix4x4d projection,
        int viewportWidth,
        int viewportHeight
    ) {
        if (p == null || modelView == null || modelView.length != 16 || projection == null) {
            return null;
        }
        double x = p.x();
        double y = p.y();
        double z = p.z();
        double vx = modelView[0] * x + modelView[4] * y + modelView[8] * z + modelView[12];
        double vy = modelView[1] * x + modelView[5] * y + modelView[9] * z + modelView[13];
        double vz = modelView[2] * x + modelView[6] * y + modelView[10] * z + modelView[14];
        double vw = modelView[3] * x + modelView[7] * y + modelView[11] * z + modelView[15];
        double[] proj = projection.exportToDoubleArrayColumnOrder();
        double cx = proj[0] * vx + proj[4] * vy + proj[8] * vz + proj[12] * vw;
        double cy = proj[1] * vx + proj[5] * vy + proj[9] * vz + proj[13] * vw;
        double cw = proj[3] * vx + proj[7] * vy + proj[11] * vz + proj[15] * vw;
        if (Math.abs(cw) < 1e-12) {
            return null;
        }
        double ndcX = cx / cw;
        double ndcY = cy / cw;
        if (!Double.isFinite(ndcX) || !Double.isFinite(ndcY)) {
            return null;
        }
        int px = (int)Math.round((ndcX * 0.5 + 0.5) * (viewportWidth - 1));
        int py = (int)Math.round((ndcY * 0.5 + 0.5) * (viewportHeight - 1));
        if (px < 0 || px >= viewportWidth || py < 0 || py >= viewportHeight) {
            return null;
        }
        return new int[] {px, py};
    }

    private void recenterCameraToAllTiles(Frame frameData, AabbStats frameStats) {
        Vector3Dd min = null;
        Vector3Dd max = null;
        for (TileInstance tile : frameData.getSelectableTiles()) {
            Vector3Dd[] transformed = CoordinatesTransforms.transformAabb(
                tile.getMin(), tile.getMax(), frameData.getModelViewMatrix()
            );
            Vector3Dd tileMin = transformed[0];
            Vector3Dd tileMax = transformed[1];
            if (!isValidAndConsistentAabb(tileMin, tileMax, frameStats)) {
                continue;
            }
            if (min == null) {
                min = tileMin;
                max = tileMax;
                continue;
            }
            min = new Vector3Dd(
                Math.min(min.x(), tileMin.x()),
                Math.min(min.y(), tileMin.y()),
                Math.min(min.z(), tileMin.z())
            );
            max = new Vector3Dd(
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

        Vector3Dd front = safeFront();
        Vector3Dd newPosition = WORLD_ORIGIN.subtract(front.multiply(distance));
        if (!isFiniteVector(newPosition) || !isFinite(distance)) {
            return;
        }
        cameraController.setPointOfInterest(WORLD_ORIGIN);
        viewingCamera.setPosition(newPosition);
        viewingCamera.setFocusedPositionMaintainingOrthogonality(WORLD_ORIGIN);
        setSafePlanes(distance);
    }

    private Matrix4x4d projectionForCurrentState(
        DumpAnalyzerModel.HudState state,
        List<Frame> frames,
        Camera activeCamera,
        boolean useGoogleCameraView
    ) {
        if (useGoogleCameraView) {
            int frameIndex = state.selectedFrameIndex();
            if (frameIndex >= 0 && frameIndex < frames.size()) {
                Matrix4x4d fromFrame = matrixFromColumnMajor(frames.get(frameIndex).getProjectionMatrix());
                if (fromFrame != null) {
                    return fromFrame;
                }
            }
        }
        // Fallback for viewing camera or missing frame projection.
        return activeCamera.calculateViewVolumeMatrix();
    }

    private Matrix4x4d matrixFromColumnMajor(double[] m) {
        if (m == null || m.length != 16) {
            return null;
        }
        Matrix4x4d out = new Matrix4x4d();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                out = out.withVal(row, col, m[col * 4 + row]);
            }
        }
        return out;
    }

    private Vector3Dd safeFront() {
        Vector3Dd raw = viewingCamera.getFront();
        if (raw == null || !isFiniteVector(raw)) {
            return DEFAULT_FRONT;
        }
        double len = raw.length();
        if (!isFinite(len) || len < 1e-12) {
            return DEFAULT_FRONT;
        }
        Vector3Dd n = raw.normalized();
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
        for (TileInstance tile : frameData.getSelectableTiles()) {
            Vector3Dd[] transformed = CoordinatesTransforms.transformAabb(
                tile.getMin(), tile.getMax(), frameData.getModelViewMatrix()
            );
            Vector3Dd min = transformed[0];
            Vector3Dd max = transformed[1];
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

    private boolean isValidAndConsistentAabb(Vector3Dd min, Vector3Dd max, AabbStats frameStats) {
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

    private static boolean isValidAabb(Vector3Dd min, Vector3Dd max) {
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

    private static double diagonal(Vector3Dd min, Vector3Dd max) {
        double dx = max.x() - min.x();
        double dy = max.y() - min.y();
        double dz = max.z() - min.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean isFiniteVector(Vector3Dd v) {
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
