package planetdemviewer.render.jogl4;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.awt.AWTTextureIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import planetdemviewer.config.Configuration;
import planetdemviewer.io.TileImageLoader;

/**
 * GPU-resident tile textures, keyed by tile file path, with an access-ordered
 * LRU eviction policy bounded by Configuration.MAX_GPU_TEXTURE_MEMORY.
 * Acquiring a resident texture renews its last-viewed timestamp.
 * Interactive rendering acquires asynchronously through a TileImageLoader
 * (returns null and enqueues a background decode while a tile is not ready
 * yet, so the caller keeps using an ancestor's texture meanwhile); the
 * offline snapshot mode acquires synchronously instead.
 */
public final class Jogl4TextureCacheHelper {
    private final LinkedHashMap<String, Texture> textureByPath = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<String, Long> bytesByPath = new HashMap<>();
    private final Map<String, Long> lastViewedNanosByPath = new HashMap<>();
    private long bytesAssigned;

    public Texture acquire(GL4 gl, File tileFile, TileImageLoader loader, boolean synchronous) {
        if (tileFile == null) {
            return null;
        }
        // Keep the source DEM page recent too when its derived texture is displayed.
        if (loader != null) {
            loader.peekElevation(tileFile);
        }
        String path = tileFile.getAbsolutePath();
        Texture cached = textureByPath.get(path);
        if (cached != null) {
            lastViewedNanosByPath.put(path, System.nanoTime());
            return cached;
        }
        Texture texture;
        if (loader == null) {
            return null;
        }
        if (synchronous) {
            try {
                BufferedImage decoded = loader.loadSynchronously(tileFile);
                texture = AWTTextureIO.newTexture(gl.getGLProfile(), decoded, false);
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
            texture = AWTTextureIO.newTexture(gl.getGLProfile(), decoded, false);
        }
        texture.bind(gl);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);

        long bytes = texture.getEstimatedMemorySize();
        if (bytes <= 0L) {
            bytes = (long) Math.max(1, texture.getImageWidth()) * Math.max(1, texture.getImageHeight()) * 4L;
        }
        textureByPath.put(path, texture);
        bytesByPath.put(path, bytes);
        lastViewedNanosByPath.put(path, System.nanoTime());
        bytesAssigned += bytes;

        enforceBudget(gl);
        return texture;
    }

    private void enforceBudget(GL4 gl) {
        Iterator<Map.Entry<String, Texture>> iterator = textureByPath.entrySet().iterator();
        while (bytesAssigned > Configuration.MAX_GPU_TEXTURE_MEMORY && iterator.hasNext()) {
            Map.Entry<String, Texture> oldest = iterator.next();
            String path = oldest.getKey();
            Texture texture = oldest.getValue();
            iterator.remove();
            Long bytes = bytesByPath.remove(path);
            lastViewedNanosByPath.remove(path);
            if (texture != null) {
                texture.destroy(gl);
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
        String path = tileFile.getAbsolutePath();
        Texture texture = textureByPath.get(path);
        if (texture != null) {
            lastViewedNanosByPath.put(path, System.nanoTime());
        }
        return texture;
    }

    public long getBytesAssigned() {
        return bytesAssigned;
    }

    public int getResidentCount() {
        return textureByPath.size();
    }

    public void dispose(GL4 gl) {
        for (Texture texture : textureByPath.values()) {
            texture.destroy(gl);
        }
        textureByPath.clear();
        bytesByPath.clear();
        lastViewedNanosByPath.clear();
        bytesAssigned = 0L;
    }
}
