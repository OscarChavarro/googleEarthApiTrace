package dumpanalyzer.render;

import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.util.awt.TextRenderer;
import dumpanalyzer.config.Configuration;
import dumpanalyzer.model.DumpAnalyzerModel;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.Image;
import vsdk.toolkit.render.jogl.Jogl4ImageRenderer;

public final class Jogl4HudRenderer {
    private TextRenderer textRenderer;
    private final Map<String, TextureResident> residentsByPath = new HashMap<>();
    private final ArrayDeque<TextureResident> residentsFifo = new ArrayDeque<>();
    private TextureResident activeResident;

    public void initializeIfNeeded() {
        if (textRenderer == null) {
            textRenderer = new TextRenderer(new Font("SansSerif", Font.BOLD, 18));
        }
    }

    public void dispose(GL4 gl) {
        unloadAllTextures(gl, null);
        Jogl4ImageRenderer.dispose(gl);
    }

    public void render(
        GLAutoDrawable drawable,
        DumpAnalyzerModel model,
        DumpAnalyzerModel.HudState state,
        Camera camera,
        String texturePath,
        List<ScreenLabel> aabbLabels
    ) {
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
                "Selected tile [3, 4]: "
                    + (state.selectedTileIndex() == DumpAnalyzerModel.SELECT_ALL_TILES ? "ALL" : state.selectedTileIndex())
                    + "/"
                    + Math.max(DumpAnalyzerModel.SELECT_ALL_TILES, state.tilesInSelectedFrame() - 1),
                20,
                h - 100
            );
            if (state.selectedTileIndex() != DumpAnalyzerModel.SELECT_ALL_TILES) {
                textRenderer.draw(
                    "Geometry: " + formatGeometry(model),
                    20,
                    h - 130
                );
                textRenderer.draw(
                    "Texture: " + state.selectedTextureId(),
                    Math.max(20, w - 200),
                    h - 40
                );
            }
            if (aabbLabels != null && !aabbLabels.isEmpty()) {
                for (ScreenLabel label : aabbLabels) {
                    Color c = label.color() == null ? Color.YELLOW : label.color();
                    textRenderer.setColor(c);
                    textRenderer.draw(label.text(), label.x(), label.y());
                }
                textRenderer.setColor(Color.WHITE);
            }
            textRenderer.endRendering();
            drawSelectedTexturePreview(gl4, model, camera, texturePath);
        }
        finally {
            gl.glMatrixMode(GL2.GL_PROJECTION);
            gl.glPopMatrix();
            gl.glMatrixMode(GL2.GL_MODELVIEW);
            gl.glPopMatrix();
            gl.glPopAttrib();
        }
    }

    private void drawSelectedTexturePreview(GL4 gl, DumpAnalyzerModel model, Camera camera, String texturePath) {
        int textureId = activateTexture(gl, model, texturePath);
        if (textureId == 0 || camera == null) {
            return;
        }
        drawTextureOn2DWindowScaled(gl, camera, 10, 10, 2.0, 1.0);
    }

    public int activateTexture(GL4 gl, DumpAnalyzerModel model, String texturePath) {
        if (texturePath == null || texturePath.isBlank()) {
            activeResident = null;
            return 0;
        }

        TextureResident resident = residentsByPath.get(texturePath);
        if (resident != null) {
            activeResident = resident;
            return resident.glTextureId();
        }

        TextureResident created = createResident(gl, model, texturePath);
        if (created == null) {
            return 0;
        }
        activeResident = created;
        return created.glTextureId();
    }

    private TextureResident createResident(GL4 gl, DumpAnalyzerModel model, String texturePath) {
        Image image;
        try {
            image = ImagePersistence.importRGB(new File(texturePath));
        } catch (IOException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
        if (image == null) {
            return null;
        }

        int width = image.getXSize();
        int height = image.getYSize();
        if (width <= 0 || height <= 0) {
            return null;
        }
        long bytes = estimateTextureBytes(width, height);
        ensureCapacityBeforeAssign(gl, model, bytes);

        int textureId = Jogl4ImageRenderer.activate(gl, image);
        if (textureId <= 0) {
            return null;
        }
        TextureResident resident = new TextureResident(texturePath, image, width, height, textureId, bytes);
        residentsByPath.put(texturePath, resident);
        residentsFifo.addLast(resident);
        if (model != null) {
            model.addGpuRamTextureBytesAssigned(bytes);
        }
        return resident;
    }

    private void ensureCapacityBeforeAssign(GL4 gl, DumpAnalyzerModel model, long incomingBytes) {
        if (model == null) {
            return;
        }
        while (!residentsFifo.isEmpty()
            && model.getGpuRamTextureBytesAssigned() + incomingBytes > Configuration.GPU_RAM_TEXTURE_LIMIT) {
            evictOldest(gl, model);
        }
    }

    private void unloadAllTextures(GL4 gl, DumpAnalyzerModel model) {
        while (!residentsFifo.isEmpty()) {
            evictOldest(gl, model);
        }
        activeResident = null;
    }

    private void evictOldest(GL4 gl, DumpAnalyzerModel model) {
        TextureResident oldest = residentsFifo.pollFirst();
        if (oldest == null) {
            return;
        }
        residentsByPath.remove(oldest.path());
        Jogl4ImageRenderer.unload(gl, oldest.image());
        if (model != null) {
            model.subtractGpuRamTextureBytesAssigned(oldest.bytesAssigned());
        }
        if (activeResident == oldest) {
            activeResident = null;
        }
    }

    private void drawTextureOn2DWindowScaled(GL4 gl, Camera c, int x, int y, double scale, double alpha) {
        if (c == null || scale <= 0.0 || activeResident == null) {
            return;
        }
        int textureId = activeResident.glTextureId();
        if (textureId <= 0) {
            return;
        }
        int width = (int)Math.round(activeResident.width() * scale);
        int height = (int)Math.round(activeResident.height() * scale);
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
            Matrix4x4d.identityMatrix(),
            positions,
            uv,
            (float)alpha,
            (float)alpha,
            (float)alpha
        );
    }

    private static long estimateTextureBytes(int width, int height) {
        return (long)width * (long)height * 4L;
    }

    private static String formatGeometry(DumpAnalyzerModel model) {
        if (model == null) {
            return "n/a";
        }
        var tile = model.getSelectedTile();
        if (tile == null) {
            return "n/a";
        }
        List<List<vsdk.toolkit.common.linealAlgebra.Vector3Dd>> strips = tile.getStrips();
        if (strips == null || strips.isEmpty()) {
            if (tile.getIndexArraySize() > 0) {
                return primitiveDisplayName(tile.getPrimitive()) + "[" + tile.getIndexArraySize() + "]";
            }
            return "n/a";
        }

        String primitive = primitiveDisplayName(tile.getPrimitive());
        StringBuilder sb = new StringBuilder();
        for (List<vsdk.toolkit.common.linealAlgebra.Vector3Dd> strip : strips) {
            if (strip == null || strip.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(primitive).append("[").append(strip.size()).append("]");
        }
        return sb.isEmpty() ? "n/a" : sb.toString();
    }

    private static String primitiveDisplayName(String primitive) {
        if (primitive == null || primitive.isBlank() || "n/a".equalsIgnoreCase(primitive)) {
            return "Geometry";
        }
        String value = primitive.startsWith("GL_") ? primitive.substring(3) : primitive;
        String[] parts = value.toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part == null || part.isBlank()) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1));
            }
        }
        return sb.isEmpty() ? value : sb.toString();
    }

    private record TextureResident(String path, Image image, int width, int height, int glTextureId, long bytesAssigned) {
    }

    public record ScreenLabel(int x, int y, String text, Color color) {
    }
}
