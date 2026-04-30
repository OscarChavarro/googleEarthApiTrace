package dumpanalyzer.render;

import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.util.awt.TextRenderer;
import dumpanalyzer.model.DumpAnalyzerModel;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.environment.Camera;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBImage;
import vsdk.toolkit.render.jogl.Jogl4ImageRenderer;

public final class Jogl4HudRenderer {
    private static final int GL_COMPRESSED_RGBA_S3TC_DXT1_EXT = 0x83F1;
    private static final int GL_COMPRESSED_RGBA_S3TC_DXT3_EXT = 0x83F2;
    private static final int GL_COMPRESSED_RGBA_S3TC_DXT5_EXT = 0x83F3;
    private TextRenderer textRenderer;
    private String loadedTexturePath;
    private int loadedTextureGlId;
    private int loadedTextureWidth;
    private int loadedTextureHeight;
    private RGBImage loadedRgbImage;
    private boolean loadedTextureFromDds;

    public void initializeIfNeeded() {
        if (textRenderer == null) {
            textRenderer = new TextRenderer(new Font("SansSerif", Font.BOLD, 18));
        }
    }

    public void dispose(GL4 gl) {
        unloadTexture(gl);
        Jogl4ImageRenderer.dispose(gl);
    }

    public void render(GLAutoDrawable drawable, DumpAnalyzerModel.HudState state, Camera camera, String texturePath) {
        initializeIfNeeded();

        GL2 gl = drawable.getGL().getGL2();
        GL4 gl4 = drawable.getGL().getGL4();
        int w = drawable.getSurfaceWidth();
        int h = drawable.getSurfaceHeight();

        gl.glPushAttrib(
            GL2.GL_ENABLE_BIT |
            GL2.GL_COLOR_BUFFER_BIT |
            GL2.GL_DEPTH_BUFFER_BIT |
            GL2.GL_TRANSFORM_BIT |
            GL2.GL_VIEWPORT_BIT
        );
        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glPushMatrix();
        gl.glMatrixMode(GL2.GL_PROJECTION);
        gl.glPushMatrix();

        try {
            textRenderer.beginRendering(w, h);
            textRenderer.setColor(Color.WHITE);
            textRenderer.draw(
                "Frame [1, 2]: " + state.selectedFrameIndex() + "/" + Math.max(0, state.processedFrames() - 1),
                20,
                h - 40
            );
            textRenderer.draw(
                "Number of tiles in frame: " + state.tilesInSelectedFrame(),
                20,
                h - 70
            );
            textRenderer.draw(
                "Selected tile [3, 4]: " + state.selectedTileIndex() + "/" + Math.max(-1, state.tilesInSelectedFrame() - 1),
                20,
                h - 100
            );
            textRenderer.draw(
                "Texture: " + state.selectedTextureId(),
                Math.max(20, w - 200),
                h - 40
            );
            textRenderer.endRendering();
            drawSelectedTexturePreview(gl4, camera, texturePath);
        }
        finally {
            gl.glMatrixMode(GL2.GL_PROJECTION);
            gl.glPopMatrix();
            gl.glMatrixMode(GL2.GL_MODELVIEW);
            gl.glPopMatrix();
            gl.glPopAttrib();
        }
    }

    private void drawSelectedTexturePreview(GL4 gl, Camera camera, String texturePath) {
        int textureId = activateTexture(gl, texturePath);
        if (textureId == 0 || camera == null) {
            return;
        }
        drawTextureOn2DWindowScaled(gl, camera, 10, 10, 2.0, 1.0);
    }

    public int activateTexture(GL4 gl, String texturePath) {
        if (texturePath == null || texturePath.isBlank()) {
            unloadTexture(gl);
            loadedTexturePath = null;
            return 0;
        }
        if (!texturePath.equals(loadedTexturePath)) {
            unloadTexture(gl);
            loadedTexturePath = texturePath;
            loadTexture(gl, texturePath);
        }
        if (loadedRgbImage != null) {
            int textureId = Jogl4ImageRenderer.activate(gl, loadedRgbImage);
            if (textureId > 0) {
                loadedTextureGlId = textureId;
                loadedTextureWidth = loadedRgbImage.getXSize();
                loadedTextureHeight = loadedRgbImage.getYSize();
                loadedTextureFromDds = false;
            }
        }
        if (loadedTextureGlId == 0 || loadedTextureWidth <= 0 || loadedTextureHeight <= 0) {
            return 0;
        }
        return loadedTextureGlId;
    }

    private void loadTexture(GL4 gl, String texturePath) {
        String lower = texturePath.toLowerCase();
        if (lower.endsWith(".png")) {
            loadPngTexture(texturePath);
            return;
        }
        loadDdsTexture(gl, texturePath);
    }

    private void loadPngTexture(String texturePath) {
        try {
            loadedRgbImage = ImagePersistence.importRGB(new File(texturePath));
            if (loadedRgbImage != null) {
                loadedTextureWidth = loadedRgbImage.getXSize();
                loadedTextureHeight = loadedRgbImage.getYSize();
            }
        } catch (Exception e) {
            loadedRgbImage = null;
        }
    }

    private void loadDdsTexture(GL4 gl, String texturePath) {
        byte[] data;
        try {
            data = Files.readAllBytes(Path.of(texturePath));
        } catch (IOException e) {
            return;
        }
        if (data.length < 128) {
            return;
        }
        ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int magic = bb.getInt(0);
        if (magic != 0x20534444) { // "DDS "
            return;
        }
        int height = bb.getInt(12);
        int width = bb.getInt(16);
        int fourCC = bb.getInt(84);
        int internalFormat;
        if (fourCC == 0x31545844) { // DXT1
            internalFormat = GL_COMPRESSED_RGBA_S3TC_DXT1_EXT;
        }
        else if (fourCC == 0x33545844) { // DXT3
            internalFormat = GL_COMPRESSED_RGBA_S3TC_DXT3_EXT;
        }
        else if (fourCC == 0x35545844) { // DXT5
            internalFormat = GL_COMPRESSED_RGBA_S3TC_DXT5_EXT;
        }
        else {
            return;
        }

        int imageSize = data.length - 128;
        if (width <= 0 || height <= 0 || imageSize <= 0) {
            return;
        }

        int[] ids = new int[1];
        gl.glGenTextures(1, ids, 0);
        int textureId = ids[0];
        if (textureId == 0) {
            return;
        }

        ByteBuffer payload = ByteBuffer.wrap(data, 128, imageSize);
        gl.glBindTexture(GL4.GL_TEXTURE_2D, textureId);
        gl.glTexParameteri(GL4.GL_TEXTURE_2D, GL4.GL_TEXTURE_MAG_FILTER, GL4.GL_LINEAR);
        gl.glTexParameteri(GL4.GL_TEXTURE_2D, GL4.GL_TEXTURE_MIN_FILTER, GL4.GL_LINEAR);
        gl.glTexParameteri(GL4.GL_TEXTURE_2D, GL4.GL_TEXTURE_WRAP_S, GL4.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL4.GL_TEXTURE_2D, GL4.GL_TEXTURE_WRAP_T, GL4.GL_CLAMP_TO_EDGE);
        gl.glCompressedTexImage2D(GL4.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, imageSize, payload);
        gl.glBindTexture(GL4.GL_TEXTURE_2D, 0);

        loadedTextureGlId = textureId;
        loadedTextureWidth = width;
        loadedTextureHeight = height;
        loadedTextureFromDds = true;
    }

    private void unloadTexture(GL4 gl) {
        if (loadedRgbImage != null) {
            Jogl4ImageRenderer.unload(gl, loadedRgbImage);
            loadedRgbImage = null;
        }
        if (loadedTextureGlId != 0 && loadedTextureFromDds) {
            gl.glBindTexture(GL4.GL_TEXTURE_2D, 0);
            int[] ids = new int[] { loadedTextureGlId };
            gl.glDeleteTextures(1, ids, 0);
        }
        loadedTextureGlId = 0;
        loadedTextureWidth = 0;
        loadedTextureHeight = 0;
        loadedTextureFromDds = false;
    }

    private void drawTextureOn2DWindowScaled(GL4 gl, Camera c, int x, int y, double scale, double alpha) {
        if (c == null || scale <= 0.0 || loadedTextureGlId == 0) {
            return;
        }
        int width = (int)Math.round(loadedTextureWidth * scale);
        int height = (int)Math.round(loadedTextureHeight * scale);
        double fx = (((double) width) * 2.0) / c.getViewportXSize();
        double fy = (((double) height) * 2.0) / c.getViewportYSize();
        double dx = ((double) (x) * 2.0 + ((double) width)) / c.getViewportXSize();
        double dy = ((double) (y) * 2.0 + ((double) height)) / c.getViewportYSize();

        float x0 = (float)(dx - 1.0 - fx / 2.0);
        float y0 = (float)(dy - 1.0 - fy / 2.0);
        float x1 = (float)(x0 + fx);
        float y1 = (float)(y0 + fy);
        float[] positions = {
            x0, y0, 0.0f,  x1, y0, 0.0f,  x1, y1, 0.0f,
            x0, y0, 0.0f,  x1, y1, 0.0f,  x0, y1, 0.0f
        };
        float[] uv = {
            0.0f, 0.0f,  1.0f, 0.0f,  1.0f, 1.0f,
            0.0f, 0.0f,  1.0f, 1.0f,  0.0f, 1.0f
        };
        Jogl4ImageRenderer.drawTexturedQuad(
            gl,
            loadedTextureGlId,
            Matrix4x4.identityMatrix(),
            positions,
            uv,
            (float)alpha,
            (float)alpha,
            (float)alpha
        );
    }
}
