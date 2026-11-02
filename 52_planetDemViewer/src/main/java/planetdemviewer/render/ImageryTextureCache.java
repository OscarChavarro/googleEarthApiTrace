package planetdemviewer.render;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.util.texture.Texture;
import com.jogamp.opengl.util.texture.awt.AWTTextureIO;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import planetdemviewer.io.imagery.ImageryTileLoader;
import planetdemviewer.spatial.TileAddress;

/** JOGL-owned texture cache for optional same-depth imagery pages. */
final class ImageryTextureCache {
    private final Map<TileAddress, Texture> textures = new HashMap<>();
    Texture acquire(GL4 gl, TileAddress address, ImageryTileLoader loader) {
        Texture cached = textures.get(address); if (cached != null) return cached;
        BufferedImage image = loader.takeReady(address);
        if (image == null) { loader.request(address); return null; }
        Texture texture = AWTTextureIO.newTexture(gl.getGLProfile(), image, false);
        texture.bind(gl);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
        textures.put(address, texture); return texture;
    }
    /** Frees the GPU textures of a tile and its descendants; needs a current GL context. */
    void release(GL4 gl, TileAddress subtree) {
        textures.entrySet().removeIf(entry -> {
            if (!subtree.contains(entry.getKey())) return false;
            entry.getValue().destroy(gl);
            return true;
        });
    }
    void dispose(GL4 gl) { textures.values().forEach(texture -> texture.destroy(gl)); textures.clear(); }
}
