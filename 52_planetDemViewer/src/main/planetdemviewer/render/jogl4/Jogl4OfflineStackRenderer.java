package planetdemviewer.render.jogl4;

import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLProfile;
import java.util.List;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.render.PlanetViewerSession;
import vsdk.toolkit.media.RGBImageUncompressed;
import vsdk.toolkit.render.jogl.Jogl4Renderer;

/** Renders the whole stack, top view, on an offscreen surface without opening a window. */
final class Jogl4OfflineStackRenderer {
    private RGBImageUncompressed captured;

    /**
     * Drives one frame of the listener on an offscreen surface, whose display() calls
     * {@link #drawFrame}. Returns that frame's image, or null when OpenGL is not available.
     */
    RGBImageUncompressed run(GLEventListener listener, int width, int height) {
        if (!Jogl4Renderer.verifyOpenGLAvailability()) {
            return null;
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
        return captured;
    }

    void drawFrame(GLAutoDrawable drawable, GL4 gl, List<PyramidalImageInstance> stack,
                   Jogl4QuadtreeRenderer quadtreeRenderer, boolean wires) {
        var projection = PlanetViewerSession.offlineTopProjection(
            drawable.getSurfaceWidth(), drawable.getSurfaceHeight());
        for (PyramidalImageInstance instance : stack) {
            quadtreeRenderer.drawAll(gl, instance, 1.0, projection, wires);
        }
        if (captured == null) {
            captured = Jogl4FramebufferReaderHelper.readColorBuffer(drawable, gl);
        }
    }
}
