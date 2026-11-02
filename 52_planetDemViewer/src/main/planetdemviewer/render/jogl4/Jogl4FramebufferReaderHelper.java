package planetdemviewer.render.jogl4;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import java.nio.ByteBuffer;
import vsdk.toolkit.media.RGBImageUncompressed;

/** Reads a drawable's color buffer into a Vitral image. */
final class Jogl4FramebufferReaderHelper {
    private Jogl4FramebufferReaderHelper() {
    }

    /** Must run with the GL context current (for example through GLCanvas.invoke). */
    static RGBImageUncompressed readColorBuffer(GLAutoDrawable drawable, GL4 gl) {
        int width = Math.max(1, drawable.getSurfaceWidth());
        int height = Math.max(1, drawable.getSurfaceHeight());
        ByteBuffer pixels = ByteBuffer.allocateDirect(3 * width * height);
        gl.glPixelStorei(GL.GL_PACK_ALIGNMENT, 1);
        gl.glReadPixels(0, 0, width, height, GL.GL_RGB, GL.GL_UNSIGNED_BYTE, pixels);

        RGBImageUncompressed image = new RGBImageUncompressed();
        image.init(width, height);
        int k = 0;
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                image.putPixel(x, y, pixels.get(k++), pixels.get(k++), pixels.get(k++));
            }
        }
        return image;
    }
}
