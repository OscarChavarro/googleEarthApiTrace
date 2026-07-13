package matrixmerger.render;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.util.awt.TextRenderer;
import java.awt.Font;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import matrixmerger.model.contract.FrameTileMatrix;
import matrixmerger.model.state.MatrixMergerState;
import com.jogamp.opengl.glu.GLU;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.gui.CameraControllerOrbiter;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBImageUncompressed;

public final class Jogl4MatrixMergerRenderer implements GLEventListener {
    private final MatrixMergerState model;
    private final CameraControllerOrbiter cameraController;
    private final Jogl4TileMatrixRenderer tileMatrixRenderer;
    private TextRenderer hudTextRenderer;
    private String lastPrintedUncleSignature;
    private boolean offlineMode;
    private boolean offlineCaptureDone;
    private String offlineOutputPath;

    public Jogl4MatrixMergerRenderer(MatrixMergerState model) {
        this.model = model;
        this.cameraController = new CameraControllerOrbiter(model.getViewingCamera());
        this.cameraController.setDeltaMovement(0.2);
        this.tileMatrixRenderer = new Jogl4TileMatrixRenderer();
    }

    public GLCanvas createCanvas() {
        GLProfile profile = GLProfile.get(GLProfile.GL4bc);
        GLCapabilities caps = new GLCapabilities(profile);
        caps.setDepthBits(64);
        GLCanvas canvas = new GLCanvas(caps);
        canvas.addGLEventListener(this);
        return canvas;
    }

    public CameraControllerOrbiter getCameraController() {
        return cameraController;
    }

    public void startOffscreen(String outputPath, int width, int height) {
        offlineMode = true;
        offlineCaptureDone = false;
        offlineOutputPath = outputPath;

        GLProfile profile = GLProfile.get(GLProfile.GL4bc);
        GLCapabilities caps = new GLCapabilities(profile);
        caps.setDoubleBuffered(false);
        GLDrawableFactory creator = GLDrawableFactory.getFactory(profile);
        GLOffscreenAutoDrawable drawable = creator.createOffscreenAutoDrawable(
            null, caps, null, Math.max(1, width), Math.max(1, height)
        );
        try {
            drawable.addGLEventListener(this);
            drawable.display();
        }
        finally {
            drawable.removeGLEventListener(this);
            drawable.destroy();
        }
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GL2.GL_LINE_SMOOTH);
        gl.glHint(GL2.GL_LINE_SMOOTH_HINT, GL2.GL_NICEST);
        hudTextRenderer = new TextRenderer(new Font("SansSerif", Font.BOLD, 18));
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        if (hudTextRenderer != null) {
            hudTextRenderer.dispose();
            hudTextRenderer = null;
        }
        tileMatrixRenderer.dispose(drawable.getGL().getGL2(), model);
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        GL2 gl2 = drawable.getGL().getGL2();
        gl.glClearColor(0.05f, 0.06f, 0.08f, 1.0f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

        FrameTileMatrix selected = model.getSelectedMatrix();
        if (offlineMode) {
            if (selected != null) {
                applyOrthographicMatrixFraming(gl2, drawable, selected);
                tileMatrixRenderer.drawAllTilesTextured(gl2, selected, model);
            }
            if (!offlineCaptureDone) {
                offlineCaptureDone = true;
                captureOffscreen(drawable, gl2);
            }
            return;
        }
        if (selected != null) {
            Matrix4x4d projection = model.getViewingCamera().calculateViewVolumeMatrix();
            float[] modelView = model.getViewingCamera().calculateTransformationMatrix().exportToFloatArrayColumnOrder();

            gl2.glMatrixMode(GL2.GL_PROJECTION);
            gl2.glLoadMatrixf(projection.exportToFloatArrayColumnOrder(), 0);
            gl2.glMatrixMode(GL2.GL_MODELVIEW);
            gl2.glLoadMatrixf(modelView, 0);

            tileMatrixRenderer.draw(gl2, selected, model.getRenderingConfiguration(), model);
            drawTileIdsAtCenter(drawable, gl2, selected, model.getRenderingConfiguration().isBoundingVolumeSet());
        }

        drawHud(drawable);
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int xSize, int ySize) {
        GL4 gl = drawable.getGL().getGL4();
        gl.glViewport(0, 0, xSize, ySize);
        model.getViewingCamera().updateViewportResize(xSize, ySize);
    }

    private static void applyOrthographicMatrixFraming(
        GL2 gl2,
        GLAutoDrawable drawable,
        FrameTileMatrix matrix
    ) {
        double neededHalfWidth = Math.max(1, matrix.getCols()) * 0.5 + 0.5;
        double neededHalfHeight = Math.max(1, matrix.getRows()) * 0.5 + 0.5;
        double aspect = (double) Math.max(1, drawable.getSurfaceWidth()) / Math.max(1, drawable.getSurfaceHeight());
        double halfHeight = Math.max(neededHalfHeight, neededHalfWidth / aspect);
        double halfWidth = halfHeight * aspect;
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glLoadIdentity();
        gl2.glOrtho(-halfWidth, halfWidth, -halfHeight, halfHeight, -10.0, 10.0);
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glLoadIdentity();
    }

    private void captureOffscreen(GLAutoDrawable drawable, GL2 gl2) {
        int width = Math.max(1, drawable.getSurfaceWidth());
        int height = Math.max(1, drawable.getSurfaceHeight());
        ByteBuffer pixels = ByteBuffer.allocateDirect(3 * width * height);
        gl2.glPixelStorei(GL.GL_PACK_ALIGNMENT, 1);
        gl2.glReadPixels(0, 0, width, height, GL.GL_RGB, GL.GL_UNSIGNED_BYTE, pixels);

        RGBImageUncompressed image = new RGBImageUncompressed();
        image.init(width, height);
        int offset = 0;
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                image.putPixel(x, y, pixels.get(offset++), pixels.get(offset++), pixels.get(offset++));
            }
        }
        File output = new File(offlineOutputPath);
        try {
            Path parent = output.toPath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ImagePersistence.exportPNG(output, image);
            System.out.println("Offline image written to: " + output.getAbsolutePath());
        }
        catch (Exception e) {
            throw new IllegalStateException("Could not write offline image: " + e.getMessage(), e);
        }
    }

    private void drawHud(GLAutoDrawable drawable) {
        if (hudTextRenderer == null) {
            return;
        }
        int w = drawable.getSurfaceWidth();
        int h = drawable.getSurfaceHeight();
        int i = model.getSelectedMatrixOrdinal();
        int total = model.getFrameCount();
        boolean hasNext = model.hasNextMatrixForSelection();
        FrameTileMatrix nextMatrix = model.getNextMatrixForSelection();
        FrameTileMatrix selectedMatrix = model.getSelectedMatrix();
        boolean mergeFailed = model.hasLastMergeFailedForCurrentSelection();
        String selectedFrameLabel = model.getSelectedFrameLabel();
        String nextFrameLabel = model.getNextFrameLabelForSelection();
        String hierarchyLabel = model.getSelectedHierarchyLabel();
        boolean selectedFrameInvalid = model.isSelectedFrameInvalid();
        MatrixMergerState.UncleHudStatus uncleHudStatus = model.getSelectedMatrixUncleHudStatus();

        GL2 gl2 = drawable.getGL().getGL2();
        gl2.glDisable(GL2.GL_DEPTH_TEST);
        hudTextRenderer.beginRendering(w, h);
        hudTextRenderer.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        hudTextRenderer.draw(
            "Frame [1, 2]: " + i + "/" + total
                + " | id " + selectedFrameLabel
                + " | Matrix: " + matrixSizeLabel(selectedMatrix),
            16,
            h - 28
        );
        if (!selectedFrameInvalid && hasNext && nextMatrix != null) {
            hudTextRenderer.draw(
                "Delete current matrix [d], split by west cutters [c], merge next frame [m], retry cycle [n], next frame: "
                    + nextFrameLabel,
                16,
                h - 50
            );
        }
        else if (!selectedFrameInvalid) {
            hudTextRenderer.draw("Delete current matrix [d], split by west cutters [c]", 16, h - 50);
        }
        if (!selectedFrameInvalid) {
            if (uncleHudStatus.broken()) {
                hudTextRenderer.setColor(1.0f, 0.15f, 0.15f, 1.0f);
                hudTextRenderer.draw("Uncle relations: " + uncleHudStatus.relationCount() + " (BROKEN)", 16, h - 72);
            }
            else if (uncleHudStatus.topLevel()) {
                hudTextRenderer.setColor(0.2f, 0.9f, 0.2f, 1.0f);
                hudTextRenderer.draw("Uncle relations: " + uncleHudStatus.relationCount() + " (TOPLEVEL)", 16, h - 72);
            }
            else {
                hudTextRenderer.setColor(1.0f, 1.0f, 1.0f, 1.0f);
                hudTextRenderer.draw("Uncle relations: " + uncleHudStatus.relationCount(), 16, h - 72);
            }
            printSelectedUncleIds(selectedFrameLabel, uncleHudStatus);
        }
        if (!selectedFrameInvalid && hierarchyLabel != null && !hierarchyLabel.isBlank()) {
            hudTextRenderer.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            hudTextRenderer.draw("LEVEL: " + hierarchyLabel, 16, h - 94);
        }
        if (!selectedFrameInvalid && hasNext && mergeFailed) {
            hudTextRenderer.setColor(1.0f, 0.15f, 0.15f, 1.0f);
            hudTextRenderer.draw("ERROR: Could not merge with next frame!", 16, h - 116);
        }
        if (selectedFrameInvalid) {
            hudTextRenderer.setColor(1.0f, 0.15f, 0.15f, 1.0f);
            hudTextRenderer.draw(model.getSelectedFrameInvalidReason(), 16, h - 50);
        }
        if (model.getOutputFolder() == null) {
            hudTextRenderer.setColor(1.0f, 0.15f, 0.15f, 1.0f);
            hudTextRenderer.draw("NOT EXPORTING RESULTS - REVIEW PROGRAM PARAMETERS", 16, 16);
        }
        hudTextRenderer.endRendering();
        gl2.glEnable(GL2.GL_DEPTH_TEST);
    }

    private static String matrixSizeLabel(FrameTileMatrix matrix) {
        if (matrix == null) {
            return "?x?";
        }
        return matrix.getRows() + "x" + matrix.getCols();
    }

    private void drawTileIdsAtCenter(GLAutoDrawable drawable, GL2 gl2, FrameTileMatrix matrix, boolean enabled) {
        if (!enabled || hudTextRenderer == null || matrix == null || matrix.getTiles() == null || matrix.getTiles().isEmpty()) {
            return;
        }

        double[] projection = model.getViewingCamera().calculateViewVolumeMatrix().exportToDoubleArrayColumnOrder();
        double[] modelView = toDouble16(model.getViewingCamera().calculateTransformationMatrix().exportToFloatArrayColumnOrder());
        if (modelView == null) {
            return;
        }

        int[] viewport = new int[4];
        gl2.glGetIntegerv(GL2.GL_VIEWPORT, viewport, 0);
        int h = drawable.getSurfaceHeight();
        float offsetX = -(Math.max(0, matrix.getCols()) * 0.5f);
        float offsetY = (Math.max(0, matrix.getRows()) * 0.5f);
        GLU glu = GLU.createGLU(gl2);

        gl2.glDisable(GL2.GL_DEPTH_TEST);
        hudTextRenderer.beginRendering(drawable.getSurfaceWidth(), h);
        for (FrameTileMatrix.TileCoord tile : matrix.getTiles()) {
            if (tile == null) {
                continue;
            }

            float x0 = tile.getJ() + offsetX;
            float yTop = -tile.getI() + offsetY;
            float x1 = tile.getJ() + 1.0f + offsetX;
            float yBottom = -(tile.getI() + 1.0f) + offsetY;
            if (!QuadFrustumIntersector.intersectsCameraFrustum(model.getViewingCamera(), x0, yTop, x1, yBottom)) {
                continue;
            }

            double[] win = new double[3];
            double cx = (x0 + x1) * 0.5;
            double cy = (yTop + yBottom) * 0.5;
            if (!glu.gluProject(cx, cy, 0.0, modelView, 0, projection, 0, viewport, 0, win, 0)) {
                continue;
            }

            int sx = (int)Math.round(win[0]);
            int sy = (int)Math.round(win[1]);
            String idLabel = tile.getId();
            if (idLabel == null || idLabel.isBlank()) {
                continue;
            }
            String coordsLabel = "(" + tile.getI() + ", " + tile.getJ() + ")";

            hudTextRenderer.setColor(1.0f, 1.0f, 0.4f, 1.0f);
            Rectangle2D idBounds = hudTextRenderer.getBounds(idLabel);
            int idX = sx - (int)Math.round(idBounds.getWidth() * 0.5);
            hudTextRenderer.draw(idLabel, idX, sy);

            Rectangle2D coordsBounds = hudTextRenderer.getBounds(coordsLabel);
            int coordsX = sx - (int)Math.round(coordsBounds.getWidth() * 0.5);
            hudTextRenderer.draw(coordsLabel, coordsX, sy - 18);
        }
        hudTextRenderer.endRendering();
        gl2.glEnable(GL2.GL_DEPTH_TEST);
    }

    private static double[] toDouble16(float[] matrix) {
        if (matrix == null || matrix.length != 16) {
            return null;
        }
        double[] out = new double[16];
        for (int i = 0; i < 16; i++) {
            out[i] = matrix[i];
        }
        return out;
    }

    private void printSelectedUncleIds(String selectedFrameLabel, MatrixMergerState.UncleHudStatus uncleHudStatus) {
        if (uncleHudStatus == null) {
            lastPrintedUncleSignature = null;
            return;
        }
        String signature = selectedFrameLabel
            + "|"
            + uncleHudStatus.state()
            + "|"
            + uncleHudStatus.uncleTileIds()
            + "|"
            + uncleHudStatus.missingUncleIds()
            + "|"
            + uncleHudStatus.locatedUncleTiles();
        if (signature.equals(lastPrintedUncleSignature)) {
            return;
        }
        lastPrintedUncleSignature = signature;
        System.out.println(
            "Frame " + selectedFrameLabel + " uncle tile ids: "
                + formatUncleTileIds(uncleHudStatus)
                + " [" + uncleHudStatus.state() + "]"
        );
    }

    private static String formatUncleTileIds(MatrixMergerState.UncleHudStatus uncleHudStatus) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (String uncleTileId : uncleHudStatus.uncleTileIds()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            MatrixMergerState.UncleTileLocation location = uncleHudStatus.locatedUncleTiles().get(uncleTileId);
            if (location != null && location.tileId() != null && !location.tileId().isBlank()) {
                sb.append(location.tileId());
                sb.append(" (frame ").append(location.frameIndex()).append(")");
            }
            else {
                sb.append(uncleTileId);
            }
        }
        sb.append(']');
        return sb.toString();
    }
}
