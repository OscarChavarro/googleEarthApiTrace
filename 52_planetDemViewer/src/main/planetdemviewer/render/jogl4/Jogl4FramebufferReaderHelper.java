package planetdemviewer.render.jogl4;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import vsdk.toolkit.media.RGBImageUncompressed;

/** Reads a drawable's color buffer into a Vitral image. */
final class Jogl4FramebufferReaderHelper {
    private Jogl4FramebufferReaderHelper() {
    }

    /** Must run with the GL context current (for example through GLCanvas.invoke). */
    static RGBImageUncompressed readColorBuffer(GLAutoDrawable drawable, GL4 gl) {
        int width = Math.max(1, drawable.getSurfaceWidth());
        int height = Math.max(1, drawable.getSurfaceHeight());
        RGBImageUncompressed image = new RGBImageUncompressed();
        image.initNoFill(width, height);
        gl.glPixelStorei(GL.GL_PACK_ALIGNMENT, 1);
        // OpenGL returns the bottom row first, which is also the order of a Vitral image in memory.
        gl.glReadPixels(0, 0, width, height, GL.GL_RGB, GL.GL_UNSIGNED_BYTE, image.getRawImageDirectBuffer());
        return image;
    }
}
