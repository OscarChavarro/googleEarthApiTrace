package planetdemviewer.render.jogl4;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import planetdemviewer.render.TextureUploader;
import vsdk.toolkit.media.RGBAImageUncompressed;

/**
 * The GPU side of the texture caches, whose handles are OpenGL texture names. It is
 * valid while the GL context it was given is current. The first row of a Vitral image
 * in memory is its bottom row, so it lands where OpenGL expects v = 0.
 */
public final class Jogl4TextureCacheHelper implements TextureUploader<Integer> {
    private final GL4 gl;

    public Jogl4TextureCacheHelper(GL4 gl) {
        this.gl = gl;
    }

    @Override
    public Integer upload(RGBAImageUncompressed image) {
        int[] name = new int[1];
        gl.glGenTextures(1, name, 0);
        gl.glBindTexture(GL.GL_TEXTURE_2D, name[0]);
        gl.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1);
        gl.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL4.GL_RGBA8, image.getXSize(), image.getYSize(), 0,
            GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, image.getRawImageDirectBuffer());
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE);
        return name[0];
    }

    @Override
    public void destroy(Integer texture) {
        gl.glDeleteTextures(1, new int[] {texture}, 0);
    }
}
