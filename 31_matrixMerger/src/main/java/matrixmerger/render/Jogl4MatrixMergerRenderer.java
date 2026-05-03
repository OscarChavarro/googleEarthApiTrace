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
import matrixmerger.io.TileMatrix;
import matrixmerger.model.MatrixMergerModel;
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
        int total = model.getMatrixCount();
        boolean hasNext = model.hasNextMatrixForSelection();
        boolean mergeFailed = model.hasLastMergeFailedForCurrentSelection();

        GL2 gl2 = drawable.getGL().getGL2();
        gl2.glDisable(GL2.GL_DEPTH_TEST);
        hudTextRenderer.beginRendering(w, h);
        hudTextRenderer.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        hudTextRenderer.draw("Matrix [1, 2]: " + i + "/" + total, 16, h - 28);
        if (hasNext) {
            hudTextRenderer.draw("Merge current matrix with next one [m]", 16, h - 50);
        }
        if (hasNext && mergeFailed) {
            hudTextRenderer.setColor(1.0f, 0.15f, 0.15f, 1.0f);
            hudTextRenderer.draw("ERROR: Could not merge with next matrix!", 16, h - 72);
        }
        hudTextRenderer.endRendering();
        gl2.glEnable(GL2.GL_DEPTH_TEST);
    }
}
