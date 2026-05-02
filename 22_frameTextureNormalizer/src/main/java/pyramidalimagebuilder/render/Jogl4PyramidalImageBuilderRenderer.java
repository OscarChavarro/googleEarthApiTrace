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
import pyramidalimagebuilder.model.FrameData;
import pyramidalimagebuilder.model.PyramidalImageModel;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.gui.CameraControllerOrbiter;

public final class Jogl4PyramidalImageBuilderRenderer implements GLEventListener {
    private final PyramidalImageModel model;
    private final CameraControllerOrbiter cameraController;
    private final Jogl4TileMatrixRenderer tileRenderer = new Jogl4TileMatrixRenderer();
    private final Jogl4NeighborhoodRenderer neighborhoodRenderer = new Jogl4NeighborhoodRenderer();
    private TextRenderer hudTextRenderer;

    public Jogl4PyramidalImageBuilderRenderer(PyramidalImageModel model) {
        this.model = model;
        this.cameraController = new CameraControllerOrbiter(model.getViewingCamera());
        this.cameraController.setDeltaMovement(0.2);
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
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        GL2 gl2 = drawable.getGL().getGL2();
        gl.glClearColor(0.05f, 0.06f, 0.08f, 1.0f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);

        FrameData selected = model.getSelectedFrame();
        if (selected == null) {
            drawHud(drawable, 0, 0, -1, -1);
            return;
        }

        Matrix4x4 projection = model.getViewingCamera().calculateViewVolumeMatrix();
        float[] modelViewForDraw = toFloat16(
            selected.getCameraState() == null ? null : selected.getCameraState().getModelViewMatrix()
        );
        if (modelViewForDraw == null) {
            modelViewForDraw = model.getViewingCamera().calculateTransformationMatrix().exportToFloatArrayColumnOrder();
        }

        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glLoadMatrixf(projection.exportToFloatArrayColumnOrder(), 0);
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glLoadMatrixf(modelViewForDraw, 0);

        tileRenderer.draw(
            gl2,
            selected.getTiles(),
            model.getRenderingConfiguration(),
            model,
            model.getSelectedTileIndex()
        );
        if (model.getRenderingConfiguration().isBoundingVolumeSet()) {
            neighborhoodRenderer.drawForSelection(gl2, selected.getTiles(), model.getSelectedTileIndex());
        }
        drawHud(
            drawable,
            model.getSelectedFrameIndex() + 1,
            model.getFrames().size(),
            selected.getId(),
            model.getSelectedTileIndex()
        );
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        MouseOrbiterInteraction.processReshape(drawable.getGL().getGL4(), model.getViewingCamera(), width, height);
    }

    private void drawHud(GLAutoDrawable drawable, int selectedFrameOneBased, int totalFrames, int frameId, int selectedTileIndex) {
        if (hudTextRenderer == null) {
            return;
        }
        String frameText = frameId < 0 ? "n/a" : Integer.toString(frameId);
        String selection = totalFrames <= 0 ? "0/0" : selectedFrameOneBased + "/" + totalFrames;
        String tileText = selectedTileIndex == PyramidalImageModel.SELECT_ALL_TILES ? "ALL" : Integer.toString(selectedTileIndex);

        int w = drawable.getSurfaceWidth();
        int h = drawable.getSurfaceHeight();
        drawable.getGL().getGL2().glDisable(GL2.GL_DEPTH_TEST);
        hudTextRenderer.beginRendering(w, h);
        hudTextRenderer.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        hudTextRenderer.draw("Frame [1,2]: " + selection + " (id " + frameText + ")", 16, h - 28);
        hudTextRenderer.draw("Tile [3,4]: " + tileText, 16, h - 50);
        hudTextRenderer.endRendering();
        drawable.getGL().getGL2().glEnable(GL2.GL_DEPTH_TEST);
    }

    private static float[] toFloat16(double[] values) {
        if (values == null || values.length != 16) {
            return null;
        }
        float[] out = new float[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (float)values[i];
        }
        return out;
    }
}
