package pyramidalimagebuilder.render;

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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import pyramidalimagebuilder.gui.MouseOrbiterInteraction;
import pyramidalimagebuilder.model.FrameData;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.model.TileInstance;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.gui.CameraControllerOrbiter;

public final class Jogl4PyramidalImageBuilderRenderer implements GLEventListener {
    private final PyramidalImageModel model;
    private final CameraControllerOrbiter cameraController;
    private final Jogl4TileMatrixRenderer tileRenderer = new Jogl4TileMatrixRenderer();
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
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
            drawHud(drawable, 0, 0, -1, -1, 0, null);
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
        drawTileIdsAtCenter(drawable, gl2, selected.getTiles(), model.getRenderingConfiguration().isBoundingVolumeSet());
        String selectedTextureId = selectedTextureId(selected.getTiles(), model.getSelectedTileIndex());
        drawHud(
            drawable,
            model.getSelectedFrameIndex() + 1,
            model.getFrames().size(),
            selected.getId(),
            model.getSelectedTileIndex(),
            selected.getTiles().size(),
            selectedTextureId
        );
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        MouseOrbiterInteraction.processReshape(drawable.getGL().getGL4(), model.getViewingCamera(), width, height);
    }

    private void drawHud(
        GLAutoDrawable drawable,
        int selectedFrameOneBased,
        int totalFrames,
        int frameId,
        int selectedTileIndex,
        int tileCount,
        String selectedTextureId
    ) {
        if (hudTextRenderer == null) {
            return;
        }
        String frameText = frameId < 0 ? "n/a" : Integer.toString(frameId);
        String selection = totalFrames <= 0 ? "0/0" : selectedFrameOneBased + "/" + totalFrames;
        String tileText;
        if (tileCount <= 0) {
            tileText = "0/0";
        } else {
            int tileOrdinal = selectedTileIndex < 0 ? 1 : Math.min(selectedTileIndex + 1, tileCount);
            tileText = tileOrdinal + "/" + tileCount;
        }

        int w = drawable.getSurfaceWidth();
        int h = drawable.getSurfaceHeight();
        drawable.getGL().getGL2().glDisable(GL2.GL_DEPTH_TEST);
        hudTextRenderer.beginRendering(w, h);
        hudTextRenderer.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        hudTextRenderer.draw("Frame [1,2]: " + selection + " (id " + frameText + ")", 16, h - 28);
        hudTextRenderer.draw("Tile [3,4]: " + tileText, 16, h - 50);
        hudTextRenderer.draw("Texture id: " + (selectedTextureId == null ? "n/a" : selectedTextureId), 16, h - 72);
        hudTextRenderer.endRendering();
        drawable.getGL().getGL2().glEnable(GL2.GL_DEPTH_TEST);
    }

    private void drawTileIdsAtCenter(GLAutoDrawable drawable, GL2 gl2, List<TileInstance> tiles, boolean enabled) {
        if (!enabled || hudTextRenderer == null || tiles == null || tiles.isEmpty()) {
            return;
        }

        double[] modelView = new double[16];
        double[] projection = new double[16];
        int[] viewport = new int[4];
        gl2.glGetDoublev(GL2.GL_MODELVIEW_MATRIX, modelView, 0);
        gl2.glGetDoublev(GL2.GL_PROJECTION_MATRIX, projection, 0);
        gl2.glGetIntegerv(GL2.GL_VIEWPORT, viewport, 0);

        int h = drawable.getSurfaceHeight();
        GLU glu = GLU.createGLU(gl2);

        drawable.getGL().getGL2().glDisable(GL2.GL_DEPTH_TEST);
        hudTextRenderer.beginRendering(drawable.getSurfaceWidth(), h);
        hudTextRenderer.setColor(1.0f, 1.0f, 0.4f, 1.0f);

        for (TileInstance tile : tiles) {
            if (tile == null) {
                continue;
            }
            String label = extractFrameAndTextureId(tile.getTextureFile());
            if (label == null) {
                continue;
            }

            double[] center = centerOf(tile);
            if (center == null) {
                continue;
            }

            double[] win = new double[3];
            if (!glu.gluProject(center[0], center[1], center[2], modelView, 0, projection, 0, viewport, 0, win, 0)) {
                continue;
            }

            int sx = (int)Math.round(win[0]);
            int sy = (int)Math.round(win[1]);
            Rectangle2D bounds = hudTextRenderer.getBounds(label);
            int centeredX = sx - (int)Math.round(bounds.getWidth() * 0.5);
            hudTextRenderer.draw(label, centeredX, sy);
        }

        hudTextRenderer.endRendering();
        drawable.getGL().getGL2().glEnable(GL2.GL_DEPTH_TEST);
    }

    private static String extractFrameAndTextureId(String textureFile) {
        if (textureFile == null || textureFile.isBlank()) {
            return null;
        }
        Matcher m = NUMBER_PATTERN.matcher(textureFile);
        Integer first = null;
        Integer last = null;
        while (m.find()) {
            int value = Integer.parseInt(m.group(1));
            if (first == null) {
                first = value;
            }
            last = value;
        }
        if (first == null || last == null) {
            return null;
        }
        return first + "/" + last;
    }

    private static String selectedTextureId(java.util.List<TileInstance> tiles, int selectedTileIndex) {
        if (tiles == null || selectedTileIndex < 0 || selectedTileIndex >= tiles.size()) {
            return null;
        }
        TileInstance tile = tiles.get(selectedTileIndex);
        if (tile == null) {
            return null;
        }
        String frameAndTexture = extractFrameAndTextureId(tile.getTextureFile());
        if (frameAndTexture == null) {
            return null;
        }
        int slash = frameAndTexture.indexOf('/');
        if (slash < 0 || slash + 1 >= frameAndTexture.length()) {
            return frameAndTexture;
        }
        return frameAndTexture.substring(slash + 1);
    }

    private static double[] centerOf(TileInstance tile) {
        TileInstance.TriangleStripGeometry strip = tile.getTriangleStrip();
        if (strip == null || strip.vertices() == null || strip.vertices().isEmpty()) {
            return null;
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (TileInstance.TriangleStripVertex v : strip.vertices()) {
            if (v == null) {
                continue;
            }
            if (v.x() < minX) minX = v.x();
            if (v.y() < minY) minY = v.y();
            if (v.z() < minZ) minZ = v.z();
            if (v.x() > maxX) maxX = v.x();
            if (v.y() > maxY) maxY = v.y();
            if (v.z() > maxZ) maxZ = v.z();
        }

        if (!Double.isFinite(minX) || !Double.isFinite(maxX)) {
            return null;
        }
        return new double[] {(minX + maxX) * 0.5, (minY + maxY) * 0.5, (minZ + maxZ) * 0.5};
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
