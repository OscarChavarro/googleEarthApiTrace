package planetviewer.render;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.util.awt.TextRenderer;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import planetviewer.io.TileImageLoader;
import planetviewer.merge.MergeAnalysis;
import planetviewer.merge.PyramidalImageMergeAnalyzer;
import planetviewer.merge.PyramidalImageMerger;
import planetviewer.model.PlanetViewerModel;
import planetviewer.model.PyramidalImage;
import planetviewer.model.PyramidalImageInstance;
import planetviewer.model.QuadtreeNode;
import planetviewer.io.PyramidalImageFolderReader;
import planetviewer.processing.DrawCommand;
import planetviewer.processing.PscUpdater;
import planetviewer.processing.QuadtreeDrawPlanner;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.common.linealAlgebra.Vector4Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.gui.CameraControllerAquynza;

public final class Jogl4PyramidalImageMergerRenderer implements GLEventListener {
    private final PlanetViewerModel model;
    private final PyramidalImageInstance destinationInstance;
    private final PyramidalImageInstance deltaInstance;
    private final CameraControllerAquynza cameraController;
    private final Jogl4QuadtreeRenderer quadtreeRenderer = new Jogl4QuadtreeRenderer();
    private final TileImageLoader tileImageLoader = new TileImageLoader();
    private final PyramidalImageMergeAnalyzer mergeAnalyzer = new PyramidalImageMergeAnalyzer();
    private final PyramidalImageMerger merger = new PyramidalImageMerger();
    private final View[] views;
    private Map<String, QuadtreeNode> destinationNodeIndex;
    private Map<String, QuadtreeNode> deltaNodeIndex;
    private TextRenderer hudTextRenderer;
    private MergeAnalysis mergeAnalysis;
    private volatile int mouseX = -1;
    private volatile int mouseYFromTop = -1;
    private volatile String hoveredTileLabel;
    private final Set<String> pendingTextureInvalidations = ConcurrentHashMap.newKeySet();

    public Jogl4PyramidalImageMergerRenderer(
        PlanetViewerModel model,
        PyramidalImageInstance destinationInstance,
        PyramidalImageInstance deltaInstance
    ) {
        this.model = model;
        this.destinationInstance = destinationInstance;
        this.deltaInstance = deltaInstance;
        Camera sharedCamera = model.getViewingCamera();
        this.cameraController = new CameraControllerAquynza(sharedCamera);
        this.cameraController.setDeltaMovement(0.2);
        this.views = new View[] {
            new View("Destination", sharedCamera),
            new View("Delta", sharedCamera),
        };
        this.destinationNodeIndex = indexNodes(destinationInstance.getImage().getRoot());
        this.deltaNodeIndex = indexNodes(deltaInstance.getImage().getRoot());
        this.mergeAnalysis = new MergeAnalysis(
            0, 0, 0, java.util.Set.of(), java.util.Set.of(), java.util.List.of(), java.util.Set.of(), java.util.Set.of(), 0
        );
        model.setHudStatus("Press 'm' to validate the merge.");
    }

    public void setRepaintOnTileReady(Runnable repaintAction) {
        tileImageLoader.setOnTileReady(repaintAction);
    }

    public GLCanvas createCanvas() {
        GLProfile profile = GLProfile.get(GLProfile.GL4bc);
        GLCapabilities caps = new GLCapabilities(profile);
        caps.setDepthBits(24);
        GLCanvas canvas = new GLCanvas(caps);
        canvas.addGLEventListener(this);
        return canvas;
    }

    public CameraControllerAquynza getCameraController() {
        return cameraController;
    }

    public void updateMousePosition(int xPixel, int yPixelFromTop, int canvasWidth, int canvasHeight) {
        mouseX = xPixel;
        mouseYFromTop = yPixelFromTop;
    }

    public void analyzeMerge() {
        mergeAnalysis = mergeAnalyzer.analyze(destinationInstance.getImage(), deltaInstance.getImage());
        if (!mergeAnalysis.isMergePossible()) {
            focusCameraOnFirstConflict();
            model.setHudStatus(mergeAnalysis.summary());
            return;
        }
        try {
            PyramidalImageMerger.MergeResult result = merger.mergeTiles(
                destinationInstance.getImage(),
                deltaInstance.getImage(),
                mergeAnalysis.getHigherResolutionDeltaNodeIds()
            );
            for (String nodeId : mergeAnalysis.getHigherResolutionDeltaNodeIds()) {
                QuadtreeNode destinationNode = destinationNodeIndex.get(nodeId);
                if (destinationNode != null && destinationNode.getTileFile() != null) {
                    pendingTextureInvalidations.add(destinationNode.getTileFile().getAbsolutePath());
                }
            }
            refreshDestinationImage();
            java.util.List<String> missingTileIds = merger.findMissingDeltaTileIds(
                destinationInstance.getImage(),
                deltaInstance.getImage()
            );
            if (!missingTileIds.isEmpty()) {
                String sample = missingTileIds.stream().limit(8).collect(java.util.stream.Collectors.joining(", "));
                throw new IOException(
                    "post-merge verification found " + missingTileIds.size()
                        + " missing delta tile(s); first ids: " + sample
                );
            }
            mergeAnalysis = mergeAnalyzer.markMerged(mergeAnalysis, result.copiedTiles(), result.replacedTiles());
            model.setHudStatus(mergeAnalysis.mergeCompletedSummary());
        }
        catch (IOException ex) {
            model.setHudStatus("Merge stopped: " + ex.getMessage());
        }
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        hudTextRenderer = new TextRenderer(new Font("SansSerif", Font.BOLD, 16));
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        if (hudTextRenderer != null) {
            hudTextRenderer.dispose();
            hudTextRenderer = null;
        }
        quadtreeRenderer.dispose(drawable.getGL().getGL2());
        tileImageLoader.shutdown();
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        GL2 gl2 = drawable.getGL().getGL2();
        int canvasWidth = Math.max(1, drawable.getSurfaceWidth());
        int canvasHeight = Math.max(1, drawable.getSurfaceHeight());
        gl.glViewport(0, 0, canvasWidth, canvasHeight);
        gl.glClearColor(0.02f, 0.02f, 0.05f, 1.0f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
        gl.glDisable(GL.GL_DEPTH_TEST);

        invalidateReplacedTextures(gl2);

        PscUpdater.update(model, views[0].getCamera());
        layoutViews();
        hoveredTileLabel = null;
        drawView(gl, gl2, views[0], destinationInstance, destinationNodeIndex, canvasWidth, canvasHeight);
        drawView(gl, gl2, views[1], deltaInstance, deltaNodeIndex, canvasWidth, canvasHeight);

        gl.glViewport(0, 0, canvasWidth, canvasHeight);
        gl.glEnable(GL.GL_DEPTH_TEST);
        drawHud(canvasWidth, canvasHeight);
    }

    private void invalidateReplacedTextures(GL2 gl2) {
        for (String path : Set.copyOf(pendingTextureInvalidations)) {
            if (!pendingTextureInvalidations.remove(path)) {
                continue;
            }
            File tileFile = new File(path);
            tileImageLoader.invalidate(tileFile);
            quadtreeRenderer.invalidate(gl2, tileFile);
        }
    }

    private void layoutViews() {
        views[0].setActive(true);
        views[0].setViewportStartXPercent(0.0);
        views[0].setViewportStartYPercent(0.0);
        views[0].setViewportSizeXPercent(0.5);
        views[0].setViewportSizeYPercent(1.0);
        views[1].setActive(true);
        views[1].setViewportStartXPercent(0.5);
        views[1].setViewportStartYPercent(0.0);
        views[1].setViewportSizeXPercent(0.5);
        views[1].setViewportSizeYPercent(1.0);
    }

    private void drawView(
        GL4 gl,
        GL2 gl2,
        View view,
        PyramidalImageInstance instance,
        Map<String, QuadtreeNode> nodeIndex,
        int canvasWidth,
        int canvasHeight
    ) {
        int[] viewport = view.viewportInPixels(canvasWidth, canvasHeight);
        gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        view.getCamera().updateViewportResize(viewport[2], viewport[3]);

        Matrix4x4d projection = view.getCamera().calculateViewVolumeMatrix();
        float[] modelView = view.getCamera().calculateTransformationMatrix().exportToFloatArrayColumnOrder();
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glLoadMatrixf(projection.exportToFloatArrayColumnOrder(), 0);
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glLoadMatrixf(modelView, 0);

        double relativeScale = model.relativeScale(instance.getPsc());
        quadtreeRenderer.draw(
            gl2,
            instance,
            view.getCamera(),
            relativeScale,
            model.getRenderingConfiguration().isWiresSet(),
            tileImageLoader
        );
        drawConflictBorders(gl2, instance, nodeIndex, relativeScale);
        drawResolutionMatchBorders(gl2, instance, nodeIndex, relativeScale);
        updateHoveredTile(view, instance, relativeScale, viewport, canvasWidth, canvasHeight);
        drawViewBorder(gl2, view, instance, viewport, canvasWidth, canvasHeight);
    }

    private void drawResolutionMatchBorders(
        GL2 gl2,
        PyramidalImageInstance instance,
        Map<String, QuadtreeNode> nodeIndex,
        double relativeScale
    ) {
        if (mergeAnalysis == null || mergeAnalysis.getResolutionEquivalentNodeIds().isEmpty()) {
            return;
        }
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glColor3f(0.15f, 1.0f, 0.25f);
        gl2.glLineWidth(2.0f);
        for (String nodeId : mergeAnalysis.getResolutionEquivalentNodeIds()) {
            QuadtreeNode node = nodeIndex.get(nodeId);
            if (node == null) {
                continue;
            }
            Vector3Dd[] corners = QuadtreeDrawPlanner.commandForNode(node, instance, relativeScale).corners();
            gl2.glBegin(GL2.GL_LINE_LOOP);
            for (Vector3Dd corner : corners) {
                gl2.glVertex3d(corner.x(), corner.y(), corner.z() + 0.0009);
            }
            gl2.glEnd();
        }
    }

    private void updateHoveredTile(
        View view,
        PyramidalImageInstance instance,
        double relativeScale,
        int[] viewport,
        int canvasWidth,
        int canvasHeight
    ) {
        int glMouseY = canvasHeight - 1 - mouseYFromTop;
        if (mouseX < viewport[0] || mouseX >= viewport[0] + viewport[2]
            || glMouseY < viewport[1] || glMouseY >= viewport[1] + viewport[3]) {
            return;
        }

        List<DrawCommand> commands = QuadtreeDrawPlanner.select(instance, view.getCamera(), relativeScale);
        for (int index = commands.size() - 1; index >= 0; index--) {
            DrawCommand command = commands.get(index);
            double[][] polygon = projectToViewport(command.corners(), view.getCamera(), viewport);
            if (!pointInsideConvexQuad(mouseX, glMouseY, polygon)) {
                continue;
            }
            QuadtreeNode texturedNode = command.node().nearestSelfOrAncestorWithTile();
            if (texturedNode != null && texturedNode.getTileFile() != null) {
                hoveredTileLabel = view.getTitle() + " tile: " + texturedNode.getTileFile().getAbsolutePath();
            }
            return;
        }
    }

    private double[][] projectToViewport(Vector3Dd[] corners, Camera camera, int[] viewport) {
        camera.updateVectors();
        Matrix4x4d canonicalProjection = new Matrix4x4d().canonicalPerspectiveProjection();
        Matrix4x4d normalizing = camera.getNormalizingTransformation();
        double[][] projected = new double[corners.length][2];
        for (int index = 0; index < corners.length; index++) {
            Vector3Dd normalized = normalizing.multiply(corners[index]);
            Vector4Dd ndc = canonicalProjection.multiply(new Vector4Dd(normalized)).dividedByW();
            projected[index][0] = viewport[0] + (ndc.x() + 1.0) * 0.5 * viewport[2];
            projected[index][1] = viewport[1] + (ndc.y() + 1.0) * 0.5 * viewport[3];
        }
        return projected;
    }

    private boolean pointInsideConvexQuad(double x, double y, double[][] polygon) {
        double previousCross = 0.0;
        for (int index = 0; index < polygon.length; index++) {
            double[] a = polygon[index];
            double[] b = polygon[(index + 1) % polygon.length];
            double cross = (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
            if (Math.abs(cross) < 1e-8) {
                continue;
            }
            if (previousCross != 0.0 && Math.signum(cross) != Math.signum(previousCross)) {
                return false;
            }
            previousCross = cross;
        }
        return true;
    }

    private void drawConflictBorders(
        GL2 gl2,
        PyramidalImageInstance instance,
        Map<String, QuadtreeNode> nodeIndex,
        double relativeScale
    ) {
        if (mergeAnalysis == null || mergeAnalysis.getConflictingNodeIds().isEmpty()) {
            return;
        }
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glColor3f(1.0f, 0.15f, 0.15f);
        gl2.glLineWidth(2.0f);
        for (String nodeId : mergeAnalysis.getConflictingNodeIds()) {
            QuadtreeNode node = nodeIndex.get(nodeId);
            if (node == null) {
                continue;
            }
            DrawCommand command = QuadtreeDrawPlanner.commandForNode(node, instance, relativeScale);
            Vector3Dd[] corners = command.corners();
            gl2.glBegin(GL2.GL_LINE_LOOP);
            for (Vector3Dd corner : corners) {
                gl2.glVertex3d(corner.x(), corner.y(), corner.z() + 0.0008);
            }
            gl2.glEnd();
        }
    }

    private void drawViewBorder(
        GL2 gl2,
        View view,
        PyramidalImageInstance instance,
        int[] viewport,
        int canvasWidth,
        int canvasHeight
    ) {
        gl2.glViewport(0, 0, canvasWidth, canvasHeight);
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glLoadIdentity();
        gl2.glOrtho(0, canvasWidth, 0, canvasHeight, -1, 1);
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glLoadIdentity();
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glDisable(GL2.GL_DEPTH_TEST);
        gl2.glColor3f(0.7f, 0.7f, 0.75f);
        gl2.glLineWidth(1.5f);
        gl2.glBegin(GL2.GL_LINE_LOOP);
        gl2.glVertex2i(viewport[0] + 1, viewport[1] + 1);
        gl2.glVertex2i(viewport[0] + viewport[2] - 1, viewport[1] + 1);
        gl2.glVertex2i(viewport[0] + viewport[2] - 1, viewport[1] + viewport[3] - 1);
        gl2.glVertex2i(viewport[0] + 1, viewport[1] + viewport[3] - 1);
        gl2.glEnd();
        if (hudTextRenderer != null) {
            hudTextRenderer.beginRendering(canvasWidth, canvasHeight);
            hudTextRenderer.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            int textX = viewport[0] + 6;
            int titleY = viewport[1] + viewport[3] - 18;
            hudTextRenderer.draw(view.getTitle(), textX, titleY);
            hudTextRenderer.draw(folderDisplayName(instance.getImage()), textX, titleY - 20);
            hudTextRenderer.endRendering();
        }
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
    }

    private void drawHud(int canvasWidth, int canvasHeight) {
        if (hudTextRenderer == null) {
            return;
        }
        hudTextRenderer.beginRendering(canvasWidth, canvasHeight);
        if (mergeAnalysis != null
            && mergeAnalysis.getComparedTiles() == 0
            && mergeAnalysis.getMergeableTiles() == 0
            && mergeAnalysis.getConflictCount() == 0) {
            hudTextRenderer.setColor(1.0f, 0.9f, 0.3f, 1.0f);
        }
        else if (mergeAnalysis != null && mergeAnalysis.isMergePossible()) {
            hudTextRenderer.setColor(0.3f, 1.0f, 0.3f, 1.0f);
        }
        else if (mergeAnalysis != null && mergeAnalysis.getConflictCount() > 0) {
            hudTextRenderer.setColor(1.0f, 0.3f, 0.3f, 1.0f);
        }
        else {
            hudTextRenderer.setColor(1.0f, 0.9f, 0.3f, 1.0f);
        }
        hudTextRenderer.draw(model.getHudStatus(), 16, canvasHeight - 24);
        hudTextRenderer.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        hudTextRenderer.draw(
            "PSC: " + model.getCurrentPSC()
                + " | camera z: " + String.format("%.4f", views[0].getCamera().getPosition().z())
                + " | compared: " + mergeAnalysis.getComparedTiles()
                + " | conflicts: " + mergeAnalysis.getConflictCount()
                + " | resolution matches: " + mergeAnalysis.getResolutionEquivalentNodeIds().size()
                + " | copied: " + mergeAnalysis.getCopiedTiles()
                + " | replaced: " + mergeAnalysis.getReplacedTiles(),
            16, canvasHeight - 46
        );
        hudTextRenderer.draw(
            "Zoom: wheel/z/Z | Reset: r/R | Validate+merge: m | ESC: exit",
            16, canvasHeight - 68
        );
        if (mergeAnalysis.getConflictCount() > 0) {
            hudTextRenderer.draw(
                "Conflict levels: " + mergeAnalysis.getConflictingLevels()
                    + " | conflict tiles: " + mergeAnalysis.getConflictingNodeIds(),
                16, canvasHeight - 90
            );
        }
        if (hoveredTileLabel != null) {
            hudTextRenderer.setColor(0.4f, 1.0f, 0.85f, 1.0f);
            hudTextRenderer.draw(hoveredTileLabel, 16, canvasHeight - 112);
        }
        hudTextRenderer.endRendering();
    }

    private String folderDisplayName(PyramidalImage image) {
        Path path = Path.of(image.getSourceFolder());
        Path fileName = path.getFileName();
        return fileName != null ? fileName.toString() : image.getSourceFolder();
    }

    private void refreshDestinationImage() throws IOException {
        PyramidalImageFolderReader reader = new PyramidalImageFolderReader();
        PyramidalImage refreshed = reader.read(java.nio.file.Path.of(destinationInstance.getImage().getSourceFolder()))
            .orElseThrow(() -> new IOException("Could not rescan destination pyramidal image after merge"));
        destinationInstance.setImage(refreshed);
        destinationNodeIndex = indexNodes(refreshed.getRoot());
    }

    private void focusCameraOnFirstConflict() {
        if (mergeAnalysis == null || mergeAnalysis.getConflictingNodeIds().isEmpty()) {
            return;
        }
        String firstConflictId = mergeAnalysis.getConflictingNodeIds().iterator().next();
        QuadtreeNode conflictNode = deltaNodeIndex.get(firstConflictId);
        if (conflictNode == null) {
            conflictNode = destinationNodeIndex.get(firstConflictId);
        }
        if (conflictNode == null) {
            return;
        }

        double relativeScale = model.relativeScale(deltaInstance.getPsc());
        double x0 = (conflictNode.getX0() - 0.5) * relativeScale + deltaInstance.getOffsetX();
        double x1 = (conflictNode.getX1() - 0.5) * relativeScale + deltaInstance.getOffsetX();
        double y0 = (conflictNode.getY0() - 0.5) * relativeScale + deltaInstance.getOffsetY();
        double y1 = (conflictNode.getY1() - 0.5) * relativeScale + deltaInstance.getOffsetY();
        double centerX = (x0 + x1) * 0.5;
        double centerY = (y0 + y1) * 0.5;
        double tileSize = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        double targetZ = Math.max(1.2, Math.min(9.0, tileSize * 4.0));

        Camera camera = views[0].getCamera();
        camera.setPosition(new Vector3Dd(centerX, centerY, targetZ));
        camera.setFocusedPositionDirect(new Vector3Dd(centerX, centerY, 0.0));
        camera.setUpDirect(new Vector3Dd(0.0, 1.0, 0.0));
        camera.updateVectors();
    }

    private Map<String, QuadtreeNode> indexNodes(QuadtreeNode root) {
        Map<String, QuadtreeNode> out = new HashMap<>();
        indexRecursive(root, out);
        return out;
    }

    private void indexRecursive(QuadtreeNode node, Map<String, QuadtreeNode> out) {
        if (node == null) {
            return;
        }
        out.put(node.getId(), node);
        if (!node.hasChildren()) {
            return;
        }
        for (QuadtreeNode child : node.getChildren()) {
            if (child != null) {
                indexRecursive(child, out);
            }
        }
    }
}
