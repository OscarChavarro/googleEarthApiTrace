package frametexturenormalizer.render;

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
import com.jogamp.opengl.glu.GLU;
import com.jogamp.opengl.util.awt.TextRenderer;
import com.jogamp.common.nio.Buffers;
import java.awt.Font;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.nio.file.Files;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import frametexturenormalizer.gui.MouseOrbiterInteraction;
import frametexturenormalizer.model.FrameData;
import frametexturenormalizer.model.PyramidalImageModel;
import frametexturenormalizer.model.TileInstance;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.gui.CameraControllerOrbiter;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBImageUncompressed;

public final class Jogl4PyramidalImageBuilderRenderer implements GLEventListener {
    private static final int PICK_REGION_PIXELS = 5;
    private static final int SELECT_BUFFER_SIZE = 4096;
    private final PyramidalImageModel model;
    private final CameraControllerOrbiter cameraController;
    private final Jogl4TileMatrixRenderer tileRenderer = new Jogl4TileMatrixRenderer();
    private final Jogl4NeighborhoodRenderer neighborhoodRenderer = new Jogl4NeighborhoodRenderer();
    private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
    private TextRenderer hudTextRenderer;
    private boolean offlineMode;
    private boolean offlineCaptureDone;
    private String offlineOutputPath;

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

    public void startOffscreen(String outputPath, int width, int height) {
        if (!vsdk.toolkit.render.jogl.Jogl4Renderer.verifyOpenGLAvailability()) {
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
        try {
            pbuffer.addGLEventListener(this);
            pbuffer.display();
        }
        finally {
            try {
                pbuffer.removeGLEventListener(this);
            }
            catch (Exception ignored) {
            }
            try {
                pbuffer.destroy();
            }
            catch (Exception ignored) {
            }
        }
    }

    public boolean toggleTileSelectionAt(GLAutoDrawable drawable, int mouseX, int mouseY) {
        if (drawable == null) {
            return false;
        }
        FrameData selected = model.getSelectedFrame();
        if (selected == null || selected.getTiles() == null || selected.getTiles().isEmpty()) {
            return false;
        }
        GL2 gl2 = drawable.getGL().getGL2();
        Integer pickedTextureId = pickTextureId(gl2, drawable, selected.getTiles(), mouseX, mouseY);
        if (pickedTextureId == null) {
            return false;
        }
        for (TileInstance tile : selected.getTiles()) {
            if (tile == null || tile.getTileId() != pickedTextureId.intValue()) {
                continue;
            }
            if (tile.isWestCuttingCell()) {
                return false;
            }
            tile.setSelected(!tile.isSelected());
            return true;
        }
        return false;
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
            drawHud(drawable, 0, 0, -1, -1, 0, null, 0);
            return;
        }

        Matrix4x4 projection = projectionForFrame(selected);
        double[] modelViewForLines = modelViewForFrame(selected);
        float[] modelViewForDraw = toFloat16(modelViewForLines);
        if (modelViewForDraw == null) {
            modelViewForDraw = model.getViewingCamera().calculateTransformationMatrix().exportToFloatArrayColumnOrder();
            modelViewForLines = toDouble16(modelViewForDraw);
        }

        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glLoadMatrixf(projection.exportToFloatArrayColumnOrder(), 0);
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glLoadMatrixf(modelViewForDraw, 0);

        tileRenderer.draw(
            gl2,
            selected.getTiles(),
            selected.getLines(),
            projection,
            modelViewForLines,
            model.getRenderingConfiguration(),
            model,
            model.getSelectedTileIndex()
        );
        if (model.getRenderingConfiguration().isBoundingVolumeSet()) {
            neighborhoodRenderer.drawForSelection(
                gl2,
                selected.getTiles(),
                model.getSelectedTileIndex(),
                projection,
                modelViewForDraw,
                drawable.getSurfaceWidth(),
                drawable.getSurfaceHeight()
            );
        }
        drawTileIdsAtCenter(drawable, gl2, selected.getTiles(), model.getRenderingConfiguration().isBoundingVolumeSet());
        String selectedTextureId = selectedTextureId(selected.getTiles(), model.getSelectedTileIndex());
        drawHud(
            drawable,
            model.getSelectedFrameIndex() + 1,
            model.getFrames().size(),
            selected.getId(),
            model.getSelectedTileIndex(),
            selected.getTiles().size(),
            selectedTextureId,
            selectedTilesCount(selected.getTiles())
        );
        if (selected.isWithMatrixErrors()) {
            drawMatrixErrorOverlay(drawable);
        }
        if (offlineMode && !offlineCaptureDone) {
            captureOffscreen(drawable, gl);
            offlineCaptureDone = true;
        }
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
        String selectedTextureId,
        int selectedTilesCount
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
        if (selectedTilesCount > 0) {
            hudTextRenderer.draw("West cut selected tiles [c]", 16, h - 94);
        }
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

        for (TileInstance tile : tiles) {
            if (tile == null) {
                continue;
            }
            String textureLabel = extractFrameAndTextureId(tile.getTextureFile());
            if (textureLabel == null) {
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
            String coordsLabel = matrixCoordsLabel(tile);
            if (coordsLabel == null) {
                hudTextRenderer.setColor(1.0f, 0.2f, 0.2f, 1.0f);
            } else {
                hudTextRenderer.setColor(1.0f, 1.0f, 0.4f, 1.0f);
            }
            Rectangle2D bounds = hudTextRenderer.getBounds(textureLabel);
            int centeredX = sx - (int)Math.round(bounds.getWidth() * 0.5);
            hudTextRenderer.draw(textureLabel, centeredX, sy);
            if (coordsLabel != null) {
                Rectangle2D cBounds = hudTextRenderer.getBounds(coordsLabel);
                int cX = sx - (int)Math.round(cBounds.getWidth() * 0.5);
                hudTextRenderer.draw(coordsLabel, cX, sy - 18);
            }
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

    private static int selectedTilesCount(List<TileInstance> tiles) {
        if (tiles == null || tiles.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (TileInstance tile : tiles) {
            if (tile != null && tile.isSelected()) {
                count++;
            }
        }
        return count;
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

    private static String matrixCoordsLabel(TileInstance tile) {
        if (tile == null || tile.getMatrixI() == null || tile.getMatrixJ() == null) {
            return null;
        }
        if (tile.getMatrixI() == -1 && tile.getMatrixJ() == -1) {
            return null;
        }
        return "(" + tile.getMatrixI() + ", " + tile.getMatrixJ() + ")";
    }

    private void drawMatrixErrorOverlay(GLAutoDrawable drawable) {
        if (hudTextRenderer == null) {
            return;
        }
        int w = drawable.getSurfaceWidth();
        int h = drawable.getSurfaceHeight();
        String msg = "MATRIX CONVERSION ERROR IN THIS FRAME";
        Rectangle2D bounds = hudTextRenderer.getBounds(msg);
        int x = Math.max(12, (int)Math.round((w - bounds.getWidth()) * 0.5));
        int y = Math.max(20, (int)Math.round(h * 0.75));
        drawable.getGL().getGL2().glDisable(GL2.GL_DEPTH_TEST);
        hudTextRenderer.beginRendering(w, h);
        hudTextRenderer.setColor(1.0f, 0.1f, 0.1f, 1.0f);
        hudTextRenderer.draw(msg, x, y);
        hudTextRenderer.endRendering();
        drawable.getGL().getGL2().glEnable(GL2.GL_DEPTH_TEST);
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

    private Integer pickTextureId(GL2 gl2, GLAutoDrawable drawable, List<TileInstance> tiles, int mouseX, int mouseY) {
        if (gl2 == null || drawable == null || tiles == null || tiles.isEmpty()) {
            return null;
        }

        int[] viewport = new int[4];
        gl2.glGetIntegerv(GL2.GL_VIEWPORT, viewport, 0);
        if (viewport[2] <= 0 || viewport[3] <= 0) {
            return null;
        }

        if (!gl2.isFunctionAvailable("glSelectBuffer") || !gl2.isFunctionAvailable("glRenderMode")) {
            return null;
        }
        IntBuffer selectBuffer = Buffers.newDirectIntBuffer(SELECT_BUFFER_SIZE);
        gl2.glSelectBuffer(SELECT_BUFFER_SIZE, selectBuffer);
        gl2.glRenderMode(GL2.GL_SELECT);
        gl2.glInitNames();
        gl2.glPushName(0);

        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glPushMatrix();
        gl2.glLoadIdentity();
        GLU glu = GLU.createGLU(gl2);
        glu.gluPickMatrix(mouseX, viewport[3] - mouseY, PICK_REGION_PIXELS, PICK_REGION_PIXELS, viewport, 0);
        FrameData selected = model.getSelectedFrame();
        Matrix4x4 projection = projectionForFrame(selected);
        gl2.glMultMatrixf(projection.exportToFloatArrayColumnOrder(), 0);

        float[] modelViewForDraw = toFloat16(modelViewForFrame(selected));
        if (modelViewForDraw == null) {
            modelViewForDraw = model.getViewingCamera().calculateTransformationMatrix().exportToFloatArrayColumnOrder();
        }
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glPushMatrix();
        gl2.glLoadMatrixf(modelViewForDraw, 0);

        tileRenderer.drawForSelection(gl2, tiles, model.getSelectedTileIndex());

        gl2.glPopMatrix();
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glPopMatrix();
        gl2.glFlush();
        int hits = gl2.glRenderMode(GL2.GL_RENDER);
        gl2.glMatrixMode(GL2.GL_MODELVIEW);

        if (hits <= 0) {
            return null;
        }
        return parseNearestHitTextureId(selectBuffer, hits);
    }

    private static Integer parseNearestHitTextureId(IntBuffer selectBuffer, int hits) {
        int offset = 0;
        long nearestDepth = Long.MAX_VALUE;
        Integer selectedName = null;
        for (int i = 0; i < hits; i++) {
            if (offset + 3 > selectBuffer.limit()) {
                break;
            }
            int nameCount = selectBuffer.get(offset++);
            long minDepth = Integer.toUnsignedLong(selectBuffer.get(offset++));
            offset++; // max depth
            if (nameCount <= 0 || offset + nameCount > selectBuffer.limit()) {
                offset += Math.max(0, nameCount);
                continue;
            }
            int hitName = selectBuffer.get(offset + nameCount - 1);
            if (minDepth < nearestDepth) {
                nearestDepth = minDepth;
                selectedName = hitName;
            }
            offset += nameCount;
        }
        return selectedName;
    }

    private void captureOffscreen(GLAutoDrawable drawable, GL4 gl) {
        int width = Math.max(1, drawable.getSurfaceWidth());
        int height = Math.max(1, drawable.getSurfaceHeight());
        ByteBuffer bb = ByteBuffer.allocateDirect(3 * width * height);
        gl.glPixelStorei(GL.GL_PACK_ALIGNMENT, 1);
        gl.glReadPixels(0, 0, width, height, GL.GL_RGB, GL.GL_UNSIGNED_BYTE, bb);

        RGBImageUncompressed image = new RGBImageUncompressed();
        image.init(width, height);
        int k = 0;
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                image.putPixel(x, y, bb.get(k++), bb.get(k++), bb.get(k++));
            }
        }
        File out = new File(offlineOutputPath == null || offlineOutputPath.isBlank()
            ? "/tmp/frameTextureNormalizer_offline.png"
            : offlineOutputPath);
        try {
            Path parent = out.toPath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ImagePersistence.exportJPG(out, image);
            System.out.println("Offline image written to: " + out.getAbsolutePath());
        }
        catch (Exception e) {
            System.out.println("Could not write offline image: " + e.getMessage());
        }
    }

    private Matrix4x4 projectionForFrame(FrameData frame) {
        Matrix4x4 fromFrame = matrixFromColumnMajor(frame == null ? null : frame.getProjectionMatrix());
        if (fromFrame != null) {
            return fromFrame;
        }
        return model.getViewingCamera().calculateViewVolumeMatrix();
    }

    private static double[] modelViewForFrame(FrameData frame) {
        if (frame == null) {
            return null;
        }
        double[] fromFrame = frame.getModelViewMatrix();
        if (fromFrame != null && fromFrame.length == 16) {
            return fromFrame;
        }
        return frame.getCameraState() == null ? null : frame.getCameraState().getModelViewMatrix();
    }

    private static Matrix4x4 matrixFromColumnMajor(double[] m) {
        if (m == null || m.length != 16) {
            return null;
        }
        Matrix4x4 out = new Matrix4x4();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                out = out.withVal(row, col, m[col * 4 + row]);
            }
        }
        return out;
    }
}
