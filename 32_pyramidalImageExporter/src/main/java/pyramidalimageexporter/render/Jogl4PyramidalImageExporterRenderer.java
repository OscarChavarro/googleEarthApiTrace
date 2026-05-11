package pyramidalimageexporter.render;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.util.awt.TextRenderer;
import java.awt.Font;
import java.awt.geom.Rectangle2D;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.TileCoord;
import pyramidalimageexporter.model.PyramidalImageExporterModel;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.gui.CameraControllerOrbiter;

public final class Jogl4PyramidalImageExporterRenderer implements GLEventListener {
    private final PyramidalImageExporterModel model;
    private final CameraControllerOrbiter cameraController;
    private final Jogl4MatrixLayerRenderer matrixLayerRenderer;
    private TextRenderer hudTextRenderer;

    public Jogl4PyramidalImageExporterRenderer(PyramidalImageExporterModel model) {
        this.model = model;
        this.cameraController = new CameraControllerOrbiter(model.getViewingCamera());
        this.cameraController.setDeltaMovement(0.2);
        this.matrixLayerRenderer = new Jogl4MatrixLayerRenderer();
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
        matrixLayerRenderer.dispose(drawable.getGL().getGL2(), model);
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        GL2 gl2 = drawable.getGL().getGL2();
        gl.glClearColor(0.05f, 0.06f, 0.08f, 1.0f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

        MatrixLayer selected = model.getSelectedMatrixLayer();
        if (selected != null) {
            Matrix4x4 projection = model.getViewingCamera().calculateViewVolumeMatrix();
            float[] modelView = model.getViewingCamera().calculateTransformationMatrix().exportToFloatArrayColumnOrder();

            gl2.glMatrixMode(GL2.GL_PROJECTION);
            gl2.glLoadMatrixf(projection.exportToFloatArrayColumnOrder(), 0);
            gl2.glMatrixMode(GL2.GL_MODELVIEW);
            gl2.glLoadMatrixf(modelView, 0);

            matrixLayerRenderer.draw(gl2, selected, model.getRenderingConfiguration(), model);
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
        MatrixLayer selected = model.getSelectedMatrixLayer();
        GL2 gl2 = drawable.getGL().getGL2();
        gl2.glDisable(GL2.GL_DEPTH_TEST);
        hudTextRenderer.beginRendering(w, h);
        hudTextRenderer.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        hudTextRenderer.draw(
            "Layer [1, 2]: " + model.getSelectedMatrixLayerOrdinal() + "/" + model.getMatrixLayerCount()
                + " | frame " + model.getSelectedFrameLabel()
                + " | Matrix: " + matrixSizeLabel(selected),
            16,
            h - 28
        );
        hudTextRenderer.draw("Toggle textures [t], orbit camera with mouse, source: " + safeInputFolder(), 16, h - 50);
        hudTextRenderer.endRendering();
        gl2.glEnable(GL2.GL_DEPTH_TEST);
    }

    private String safeInputFolder() {
        String inputFolder = model.getInputFolder();
        return inputFolder == null ? "?" : inputFolder;
    }

    private static String matrixSizeLabel(MatrixLayer matrixLayer) {
        if (matrixLayer == null) {
            return "?x?";
        }
        return matrixLayer.getRows() + "x" + matrixLayer.getCols();
    }

    private void drawTileIdsAtCenter(GLAutoDrawable drawable, GL2 gl2, MatrixLayer matrixLayer, boolean enabled) {
        if (!enabled || hudTextRenderer == null || matrixLayer == null || matrixLayer.getTiles() == null || matrixLayer.getTiles().isEmpty()) {
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
        float offsetX = -(Math.max(0, matrixLayer.getCols()) * 0.5f);
        float offsetY = (Math.max(0, matrixLayer.getRows()) * 0.5f);
        GLU glu = GLU.createGLU(gl2);

        gl2.glDisable(GL2.GL_DEPTH_TEST);
        hudTextRenderer.beginRendering(drawable.getSurfaceWidth(), h);
        for (TileCoord tile : matrixLayer.getTiles()) {
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
            String idLabel = tile.getId();
            if (idLabel == null || idLabel.isBlank()) {
                continue;
            }
            int sx = (int)Math.round(win[0]);
            int sy = (int)Math.round(win[1]);
            Rectangle2D idBounds = hudTextRenderer.getBounds(idLabel);
            int idX = sx - (int)Math.round(idBounds.getWidth() * 0.5);
            String coordsLabel = "(" + tile.getI() + ", " + tile.getJ() + ")";
            Rectangle2D coordsBounds = hudTextRenderer.getBounds(coordsLabel);
            int coordsX = sx - (int)Math.round(coordsBounds.getWidth() * 0.5);
            hudTextRenderer.setColor(1.0f, 1.0f, 0.4f, 1.0f);
            hudTextRenderer.draw(idLabel, idX, sy);
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
