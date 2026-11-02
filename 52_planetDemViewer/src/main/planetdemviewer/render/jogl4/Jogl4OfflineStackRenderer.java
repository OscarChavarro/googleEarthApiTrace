package planetdemviewer.render.jogl4;

import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLProfile;
import java.nio.file.Path;
import java.util.List;
import planetdemviewer.io.TileImageLoader;
import planetdemviewer.model.PyramidalImageInstance;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.render.jogl.Jogl4Renderer;

/** Renders the whole stack, top view, to an image file without opening a window. */
final class Jogl4OfflineStackRenderer {
    private static final String DEFAULT_OUTPUT = "/tmp/planetDemViewer_offline.png";

    private final String outputPath;
    private boolean captured;

    Jogl4OfflineStackRenderer(String outputPath) {
        this.outputPath = outputPath;
    }

    /** Drives one frame of the listener on an offscreen surface, whose display() calls {@link #drawFrame}. */
    void run(GLEventListener listener, int width, int height) {
        if (!Jogl4Renderer.verifyOpenGLAvailability()) {
            System.out.println("Can not start OpenGL/JOGL renderer.");
            return;
        }
        GLProfile profile = GLProfile.get(GLProfile.GL4);
        GLCapabilities caps = new GLCapabilities(profile);
        caps.setDoubleBuffered(false);

        GLDrawableFactory factory = GLDrawableFactory.getFactory(profile);
        GLOffscreenAutoDrawable pBuffer = factory.createOffscreenAutoDrawable(
            null, caps, null, Math.max(1, width), Math.max(1, height)
        );
        try {
            pBuffer.addGLEventListener(listener);
            pBuffer.display();
        }
        finally {
            try {
                pBuffer.removeGLEventListener(listener);
            }
            catch (Exception ignored) {
            }
            try {
                pBuffer.destroy();
            }
            catch (Exception ignored) {
            }
        }
    }

    void drawFrame(GLAutoDrawable drawable, GL4 gl, List<PyramidalImageInstance> stack,
                   Jogl4QuadtreeRenderer quadtreeRenderer, boolean wires, TileImageLoader tileImageLoader) {
        Matrix4x4d projection = topProjection(drawable);
        for (PyramidalImageInstance instance : stack) {
            quadtreeRenderer.drawAll(gl, instance, 1.0, projection, wires, tileImageLoader);
        }
        if (!captured) {
            captured = true;
            Jogl4FramebufferCaptureRenderer.writePng(drawable, gl, Path.of(outputPath == null || outputPath.isBlank()
                ? DEFAULT_OUTPUT : outputPath));
        }
    }

    private static Matrix4x4d topProjection(GLAutoDrawable drawable) {
        double aspect = (double) Math.max(1, drawable.getSurfaceWidth()) / Math.max(1, drawable.getSurfaceHeight());
        double halfHeight = 0.55;
        double halfWidth = halfHeight * aspect;
        return new Matrix4x4d().orthogonalProjection(
            -halfWidth, halfWidth, -halfHeight, halfHeight, -10.0, 10.0);
    }
}
