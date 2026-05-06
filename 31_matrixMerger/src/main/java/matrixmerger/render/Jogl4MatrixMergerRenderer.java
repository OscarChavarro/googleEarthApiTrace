package matrixmerger.render;

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
import java.awt.geom.Rectangle2D;
import matrixmerger.io.TileMatrix;
import matrixmerger.model.MatrixMergerModel;
import com.jogamp.opengl.glu.GLU;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.gui.CameraControllerOrbiter;

public final class Jogl4MatrixMergerRenderer implements GLEventListener {
    private final MatrixMergerModel model;
    private final CameraControllerOrbiter cameraController;
    private final Jogl4TileMatrixRenderer tileMatrixRenderer;
    private TextRenderer hudTextRenderer;

    public Jogl4MatrixMergerRenderer(MatrixMergerModel model) {
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

        TileMatrix selected = model.getSelectedMatrix();
        if (selected != null) {
            Matrix4x4 projection = model.getViewingCamera().calculateViewVolumeMatrix();
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

    private void drawHud(GLAutoDrawable drawable) {
        if (hudTextRenderer == null) {
            return;
        }
        int w = drawable.getSurfaceWidth();
        int h = drawable.getSurfaceHeight();
        int i = model.getSelectedMatrixOrdinal();
        int total = model.getFrameCount();
        boolean hasNext = model.hasNextMatrixForSelection();
        TileMatrix nextMatrix = model.getNextMatrixForSelection();
        boolean mergeFailed = model.hasLastMergeFailedForCurrentSelection();
        String selectedFrameLabel = model.getSelectedFrameLabel();
        String nextFrameLabel = model.getNextFrameLabelForSelection();
        boolean selectedFrameInvalid = model.isSelectedFrameInvalid();

        GL2 gl2 = drawable.getGL().getGL2();
        gl2.glDisable(GL2.GL_DEPTH_TEST);
        hudTextRenderer.beginRendering(w, h);
        hudTextRenderer.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        hudTextRenderer.draw(
            "Frame [1, 2]: " + i + "/" + total + " | id " + selectedFrameLabel,
            16,
            h - 28
        );
        if (!selectedFrameInvalid && hasNext && nextMatrix != null) {
            hudTextRenderer.draw(
                "Split by west cutters [c], merge next frame [m], retry cycle [n], next frame: " + nextFrameLabel,
                16,
                h - 50
            );
        }
        else if (!selectedFrameInvalid) {
            hudTextRenderer.draw("Split by west cutters [c]", 16, h - 50);
        }
        if (!selectedFrameInvalid && hasNext && mergeFailed) {
            hudTextRenderer.setColor(1.0f, 0.15f, 0.15f, 1.0f);
            hudTextRenderer.draw("ERROR: Could not merge with next frame!", 16, h - 72);
        }
        if (selectedFrameInvalid) {
            hudTextRenderer.setColor(1.0f, 0.15f, 0.15f, 1.0f);
            hudTextRenderer.draw(model.getSelectedFrameInvalidReason(), 16, h - 50);
        }
        hudTextRenderer.endRendering();
        gl2.glEnable(GL2.GL_DEPTH_TEST);
    }

    private void drawTileIdsAtCenter(GLAutoDrawable drawable, GL2 gl2, TileMatrix matrix, boolean enabled) {
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
        for (TileMatrix.TileCoord tile : matrix.getTiles()) {
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
}
