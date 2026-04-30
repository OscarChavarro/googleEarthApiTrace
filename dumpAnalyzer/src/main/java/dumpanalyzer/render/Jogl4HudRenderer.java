package dumpanalyzer.render;

import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.io.IOException;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.util.awt.TextRenderer;
import dumpanalyzer.model.DumpAnalyzerModel;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.environment.Camera;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.Image;
import vsdk.toolkit.render.jogl.Jogl4ImageRenderer;

public final class Jogl4HudRenderer {
    private TextRenderer textRenderer;
    private String loadedTexturePath;
    private int loadedTextureWidth;
    private int loadedTextureHeight;
    private Image loadedImage;

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
        int textureId = loadedImage == null ? 0 : Jogl4ImageRenderer.activate(gl, loadedImage);
        if (textureId <= 0 || loadedTextureWidth <= 0 || loadedTextureHeight <= 0) {
            return 0;
        }
        return textureId;
    }

    private void loadTexture(GL4 gl, String texturePath) {
        try {
            loadedImage = ImagePersistence.importRGB(new File(texturePath));
            if (loadedImage != null) {
                loadedTextureWidth = loadedImage.getXSize();
                loadedTextureHeight = loadedImage.getYSize();
            }
        } catch (IOException e) {
            loadedImage = null;
        } catch (Exception e) {
            loadedImage = null;
        }
    }

    private void unloadTexture(GL4 gl) {
        if (loadedImage != null) {
            Jogl4ImageRenderer.unload(gl, loadedImage);
            loadedImage = null;
        }
        loadedTextureWidth = 0;
        loadedTextureHeight = 0;
    }

    private void drawTextureOn2DWindowScaled(GL4 gl, Camera c, int x, int y, double scale, double alpha) {
        if (c == null || scale <= 0.0 || loadedImage == null) {
            return;
        }
        int textureId = Jogl4ImageRenderer.activate(gl, loadedImage);
        if (textureId <= 0) {
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
            textureId,
            Matrix4x4.identityMatrix(),
            positions,
            uv,
            (float)alpha,
            (float)alpha,
            (float)alpha
        );
    }
}
