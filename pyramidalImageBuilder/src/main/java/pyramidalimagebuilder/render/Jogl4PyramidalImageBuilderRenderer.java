package pyramidalimagebuilder.render;

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
import pyramidalimagebuilder.gui.MouseOrbiterInteraction;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.model.TileMatrix;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.gui.CameraControllerOrbiter;

public final class Jogl4PyramidalImageBuilderRenderer implements GLEventListener {
    private final PyramidalImageModel model;
    private final CameraControllerOrbiter cameraController;
    private final Jogl4TileMatrixRenderer tileMatrixRenderer = new Jogl4TileMatrixRenderer();
    private TextRenderer hudTextRenderer;

    public Jogl4PyramidalImageBuilderRenderer(PyramidalImageModel model) {
        this.model = model;
        this.cameraController = new CameraControllerOrbiter(model.getViewingCamera());
        this.cameraController.setDeltaMovement(0.2);
    }

    public GLCanvas createCanvas() {
        GLProfile profile = GLProfile.get(GLProfile.GL4bc);
        GLCapabilities caps = new GLCapabilities(profile);
        caps.setDepthBits(24);
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
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        GL2 gl2 = drawable.getGL().getGL2();
        gl.glClearColor(0.05f, 0.06f, 0.08f, 1.0f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

        Matrix4x4 projection = model.getViewingCamera().calculateViewVolumeMatrix();
        Matrix4x4 view = model.getViewingCamera().calculateTransformationMatrix();

        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glLoadMatrixf(projection.exportToFloatArrayColumnOrder(), 0);
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glLoadMatrixf(view.exportToFloatArrayColumnOrder(), 0);

        TileMatrix selected = model.getSelectedTileMatrix();
        tileMatrixRenderer.draw(gl2, selected);
        drawCoordinateFrame(gl2);
        drawHud(drawable);
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        MouseOrbiterInteraction.processReshape(drawable.getGL().getGL4(), model.getViewingCamera(), width, height);
    }

    private static void drawCoordinateFrame(GL2 gl2) {
        gl2.glLineWidth(2.5f);
        gl2.glBegin(GL2.GL_LINES);

        gl2.glColor3d(1.0, 0.2, 0.2);
        gl2.glVertex3d(0.0, 0.0, 0.0);
        gl2.glVertex3d(1.5, 0.0, 0.0);

        gl2.glColor3d(0.2, 1.0, 0.2);
        gl2.glVertex3d(0.0, 0.0, 0.0);
        gl2.glVertex3d(0.0, 1.5, 0.0);

        gl2.glColor3d(0.2, 0.6, 1.0);
        gl2.glVertex3d(0.0, 0.0, 0.0);
        gl2.glVertex3d(0.0, 0.0, 1.5);

        gl2.glEnd();
    }

    private void drawHud(GLAutoDrawable drawable) {
        if (hudTextRenderer == null) {
            return;
        }
        int selected = model.getSelectedTileMatrixIndex();
        int total = model.getTileMatrices().size();
        String selectionText = total <= 0 || selected < 0 ? "0/0" : (selected + 1) + "/" + total;
        String text = "Selected set [1, 2]: " + selectionText;
        int w = drawable.getSurfaceWidth();
        int h = drawable.getSurfaceHeight();
        drawable.getGL().getGL2().glDisable(GL2.GL_DEPTH_TEST);
        hudTextRenderer.beginRendering(w, h);
        hudTextRenderer.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        hudTextRenderer.draw(text, 16, h - 28);
        hudTextRenderer.endRendering();
        drawable.getGL().getGL2().glEnable(GL2.GL_DEPTH_TEST);
    }
}
