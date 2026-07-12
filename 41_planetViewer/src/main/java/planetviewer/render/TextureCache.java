package planetviewer.render;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.TextureIO;
import com.jogamp.opengl.util.texture.awt.AWTTextureIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import planetviewer.config.Configuration;
import planetviewer.io.TileImageLoader;

/**
 * GPU-resident tile textures, keyed by tile file path, with a FIFO eviction
 * policy bounded by Configuration.MAX_GPU_TEXTURE_MEMORY (like
 * 32_pyramidalImageExporter's Jogl4MatrixLayerRenderer#acquireTexture).
 * Interactive rendering acquires asynchronously through a TileImageLoader
 * (returns null and enqueues a background decode while a tile is not ready
 * yet, so the caller keeps using an ancestor's texture meanwhile); the
 * offline snapshot mode acquires synchronously instead.
 */
public final class TextureCache {
    private final Map<String, Texture> textureByPath = new HashMap<>();
    private final Map<String, Long> bytesByPath = new HashMap<>();
    private final ArrayDeque<String> residentFifo = new ArrayDeque<>();
    private long bytesAssigned;

    public Texture acquire(GL2 gl2, File tileFile, TileImageLoader loader) {
        if (tileFile == null) {
            return null;
        }
        String path = tileFile.getAbsolutePath();
        Texture cached = textureByPath.get(path);
        if (cached != null) {
            return cached;
        }
        Texture texture;
        if (loader == null) {
            try {
                texture = TextureIO.newTexture(tileFile, false);
            }
            catch (IOException ex) {
                return null;
            }
        }
        else {
            BufferedImage decoded = loader.takeReady(tileFile);
            if (decoded == null) {
                loader.requestLoad(tileFile);
                return null;
            }
            texture = AWTTextureIO.newTexture(gl2.getGLProfile(), decoded, false);
        }
        texture.bind(gl2);
        gl2.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
        gl2.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl2.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL2.GL_CLAMP_TO_EDGE);
        gl2.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL2.GL_CLAMP_TO_EDGE);

        long bytes = texture.getEstimatedMemorySize();
        if (bytes <= 0L) {
            bytes = (long) Math.max(1, texture.getImageWidth()) * Math.max(1, texture.getImageHeight()) * 4L;
        }
        textureByPath.put(path, texture);
        bytesByPath.put(path, bytes);
        residentFifo.addLast(path);
        bytesAssigned += bytes;

        enforceBudget(gl2);
        return texture;
    }

    private void enforceBudget(GL2 gl2) {
        while (bytesAssigned > Configuration.MAX_GPU_TEXTURE_MEMORY && !residentFifo.isEmpty()) {
            String oldest = residentFifo.pollFirst();
            Texture texture = textureByPath.remove(oldest);
            Long bytes = bytesByPath.remove(oldest);
            if (texture != null) {
                texture.destroy(gl2);
            }
            if (bytes != null) {
                bytesAssigned = Math.max(0L, bytesAssigned - bytes);
            }
        }
    }

    /** Checks GPU residency only; never triggers a load. */
    public Texture peekResident(File tileFile) {
        if (tileFile == null) {
            return null;
        }
        return textureByPath.get(tileFile.getAbsolutePath());
    }

    public long getBytesAssigned() {
        return bytesAssigned;
    }

    public int getResidentCount() {
        return textureByPath.size();
    }

    public void dispose(GL2 gl2) {
        for (Texture texture : textureByPath.values()) {
            texture.destroy(gl2);
        }
        textureByPath.clear();
        bytesByPath.clear();
        residentFifo.clear();
        bytesAssigned = 0L;
    }
}
