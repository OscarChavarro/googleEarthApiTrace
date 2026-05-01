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
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import pyramidalimagebuilder.gui.MouseOrbiterInteraction;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.model.TileInstance;
import pyramidalimagebuilder.model.TileMatrix;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.common.linealAlgebra.Vector3D;
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

        Matrix4x4 projection = model.getViewingCamera().calculateViewVolumeMatrix();
        Matrix4x4 view = model.getViewingCamera().calculateTransformationMatrix();

        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glLoadMatrixf(projection.exportToFloatArrayColumnOrder(), 0);
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glLoadMatrixf(view.exportToFloatArrayColumnOrder(), 0);

        TileMatrix selected = model.getSelectedTileMatrix();
        tileMatrixRenderer.draw(gl2, selected, model.getRenderingConfiguration(), model);
        drawCoordinateFrame(gl2);
        drawHud(drawable, selected, projection, view);
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

    private void drawHud(GLAutoDrawable drawable, TileMatrix selectedMatrix, Matrix4x4 projection, Matrix4x4 view) {
        if (hudTextRenderer == null) {
            return;
        }
        int selected = model.getSelectedTileMatrixIndex();
        int total = model.getTileMatrices().size();
        String selectionText = total <= 0 || selected < 0 ? "0/0" : (selected + 1) + "/" + total;
        String text = "Selected set [1, 2]: " + selectionText;
        int tilesInCurrent = 0;
        if (selectedMatrix != null && selectedMatrix.getGraph() != null) {
            tilesInCurrent = selectedMatrix.getGraph().vertexSet().size();
        }
        int totalTiles = 0;
        for (TileMatrix matrix : model.getTileMatrices()) {
            if (matrix == null || matrix.getGraph() == null) {
                continue;
            }
            totalTiles += matrix.getGraph().vertexSet().size();
        }
        String stats = "Tiles in current matrix: " + tilesInCurrent + ", total tiles: " + totalTiles;
        String thresholdText = "Image border distance threshold [3, 4]: " + model.getImageBorderThreshold();
        String lastFrameText = "Last frame to include: " + model.getLastFrameToInclude();
        int w = drawable.getSurfaceWidth();
        int h = drawable.getSurfaceHeight();
        drawable.getGL().getGL2().glDisable(GL2.GL_DEPTH_TEST);
        hudTextRenderer.beginRendering(w, h);
        hudTextRenderer.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        hudTextRenderer.draw(text, 16, h - 28);
        hudTextRenderer.draw(stats, 16, h - 50);
        hudTextRenderer.draw(thresholdText, 16, h - 72);
        hudTextRenderer.draw(lastFrameText, 16, h - 94);
        if (model.getRenderingConfiguration().isBoundingVolumeSet()) {
            List<ScreenLabel> labels = buildTextureLabels(selectedMatrix, projection, view, w, h);
            for (ScreenLabel label : labels) {
                hudTextRenderer.draw(label.text(), label.x(), label.y());
            }
        }
        hudTextRenderer.endRendering();
        drawable.getGL().getGL2().glEnable(GL2.GL_DEPTH_TEST);
    }

    private List<ScreenLabel> buildTextureLabels(
        TileMatrix selectedMatrix,
        Matrix4x4 projection,
        Matrix4x4 view,
        int viewportWidth,
        int viewportHeight
    ) {
        List<ScreenLabel> labels = new ArrayList<>();
        if (selectedMatrix == null || selectedMatrix.getM() == null) {
            return labels;
        }
        TileInstance[][] matrix = selectedMatrix.getM();
        for (int row = 0; row < matrix.length; row++) {
            for (int col = 0; col < matrix[row].length; col++) {
                TileInstance tile = matrix[row][col];
                if (tile == null) {
                    continue;
                }
                String textureNumber = textureNumberFromFilename(tile.getTextureFile());
                if (textureNumber == null || textureNumber.isBlank()) {
                    continue;
                }
                Vector3D center = new Vector3D(col + 0.5, -row - 0.5, 0.0);
                int[] pixel = projectToViewportPixel(center, view, projection, viewportWidth, viewportHeight);
                if (pixel == null) {
                    continue;
                }
                labels.add(new ScreenLabel(textureNumber, pixel[0], pixel[1]));
            }
        }
        return labels;
    }

    private static String textureNumberFromFilename(String textureImageFilename) {
        if (textureImageFilename == null || textureImageFilename.isBlank()) {
            return null;
        }
        String name = new File(textureImageFilename).getName();
        if (name.startsWith("256x256_")) {
            name = name.substring("256x256_".length());
        }
        if (name.endsWith(".png")) {
            name = name.substring(0, name.length() - ".png".length());
        }
        return name;
    }

    private static int[] projectToViewportPixel(
        Vector3D p,
        Matrix4x4 view,
        Matrix4x4 projection,
        int viewportWidth,
        int viewportHeight
    ) {
        if (p == null || view == null || projection == null || viewportWidth <= 0 || viewportHeight <= 0) {
            return null;
        }
        double[] model = view.exportToDoubleArrayColumnOrder();
        double[] proj = projection.exportToDoubleArrayColumnOrder();
        if (model == null || model.length != 16 || proj == null || proj.length != 16) {
            return null;
        }
        double vx = p.x();
        double vy = p.y();
        double vz = p.z();

        double cx = model[0] * vx + model[4] * vy + model[8] * vz + model[12];
        double cy = model[1] * vx + model[5] * vy + model[9] * vz + model[13];
        double cz = model[2] * vx + model[6] * vy + model[10] * vz + model[14];
        double cw = model[3] * vx + model[7] * vy + model[11] * vz + model[15];

        double qx = proj[0] * cx + proj[4] * cy + proj[8] * cz + proj[12] * cw;
        double qy = proj[1] * cx + proj[5] * cy + proj[9] * cz + proj[13] * cw;
        double qw = proj[3] * cx + proj[7] * cy + proj[11] * cz + proj[15] * cw;
        if (Math.abs(qw) < 1.0e-12) {
            return null;
        }

        double ndcX = qx / qw;
        double ndcY = qy / qw;
        int px = (int)Math.round((ndcX * 0.5 + 0.5) * (viewportWidth - 1));
        int py = (int)Math.round((ndcY * 0.5 + 0.5) * (viewportHeight - 1));
        if (px < 0 || px >= viewportWidth || py < 0 || py >= viewportHeight) {
            return null;
        }
        return new int[] {px, py};
    }

    private record ScreenLabel(String text, int x, int y) {
    }
}
