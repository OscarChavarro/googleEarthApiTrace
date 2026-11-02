package planetdemviewer.render.jogl4;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBImageUncompressed;

/** Saves what is on a drawable's framebuffer as a PNG file. */
final class Jogl4FramebufferCaptureRenderer {
    private Jogl4FramebufferCaptureRenderer() {
    }

    /** Must run with the GL context current (for example through GLCanvas.invoke). */
    static Path writePng(GLAutoDrawable drawable, GL4 gl, Path output) {
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
        try {
            Path parent = output.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ImagePersistence.exportPNG(output.toFile(), image);
            return output.toAbsolutePath();
        }
        catch (Exception e) {
            throw new IllegalStateException("Could not write viewer snapshot: " + e.getMessage(), e);
        }
    }
}
