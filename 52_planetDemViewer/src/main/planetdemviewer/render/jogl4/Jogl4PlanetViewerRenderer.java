package planetdemviewer.render.jogl4;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import java.util.List;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.processing.DrawCommand;
import planetdemviewer.render.PlanetViewerSession;
import planetdemviewer.render.TextRasterizer;
import planetdemviewer.render.TileLabelLayout;
import planetdemviewer.render.View;
import planetdemviewer.spatial.TileAddress;
import vsdk.toolkit.environment.light.Light;
import vsdk.toolkit.media.RGBImageUncompressed;
import vsdk.toolkit.render.jogl.Jogl4CameraRenderer;
import vsdk.toolkit.render.jogl.Jogl4LineRenderer;

/**
 * Draws each frame of a viewer session with OpenGL: it owns the GL lifecycle and the
 * order of drawing, and delegates to its collaborators.
 */
public final class Jogl4PlanetViewerRenderer implements GLEventListener {
    private final PlanetViewerSession session;
    private final Jogl4QuadtreeRenderer quadtreeRenderer;
    private final Jogl4TerrainRenderer terrainRenderer;
    private final Jogl4TextRenderer text;
    private final Jogl4ViewOverlayRenderer viewOverlays;
    private Jogl4OfflineStackRenderer offlineRenderer;

    public Jogl4PlanetViewerRenderer(PlanetViewerSession session, TextRasterizer textRasterizer) {
        this.session = session;
        this.text = new Jogl4TextRenderer(textRasterizer);
        this.quadtreeRenderer = new Jogl4QuadtreeRenderer(session.newPaletteFramePlanner());
        this.terrainRenderer = new Jogl4TerrainRenderer(session);
        this.viewOverlays = new Jogl4ViewOverlayRenderer(session.viewOverlays(), text);
    }

    public GLCanvas createCanvas() {
        GLProfile profile = GLProfile.get(GLProfile.GL4);
        GLCapabilities caps = new GLCapabilities(profile);
        caps.setDepthBits(24);
        GLCanvas canvas = new GLCanvas(caps);
        canvas.addGLEventListener(this);
        return canvas;
    }

    /** Renders the whole stack, top view, without opening a window. Returns null when OpenGL is not available. */
    public RGBImageUncompressed renderOffscreen(int width, int height) {
        offlineRenderer = new Jogl4OfflineStackRenderer();
        return offlineRenderer.run(this, width, height);
    }

    /** Must run with the canvas GL context current (for example through GLCanvas.invoke). */
    public RGBImageUncompressed readColorBuffer(GLAutoDrawable drawable) {
        return Jogl4FramebufferReaderHelper.readColorBuffer(drawable, drawable.getGL().getGL4());
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        text.dispose(gl);
        quadtreeRenderer.dispose(gl);
        terrainRenderer.dispose(gl);
        Jogl4CameraRenderer.dispose(gl);
        Jogl4LineRenderer.release(gl);
        session.shutdown();
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        session.model().writeLocked(() -> displayLocked(drawable));
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int xSize, int ySize) {
        // Per-view camera aspect ratios are recomputed every frame in drawView, since
        // each view's pixel viewport depends on the whole canvas size.
    }

    /** Runs exclusively on the JOGL animator thread while holding the scene critical section. */
    private void displayLocked(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        int canvasWidth = Math.max(1, drawable.getSurfaceWidth());
        int canvasHeight = Math.max(1, drawable.getSurfaceHeight());
        session.surfaceRendered(canvasWidth, canvasHeight);
        gl.glViewport(0, 0, canvasWidth, canvasHeight);
        gl.glClearColor(0.02f, 0.02f, 0.05f, 1.0f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
        gl.glDisable(GL.GL_DEPTH_TEST);

        releasePendingGpuResources(gl);

        if (offlineRenderer != null) {
            offlineRenderer.drawFrame(drawable, gl, session.orderedByStackHeight(), quadtreeRenderer,
                session.isOfflineWiresVisible());
            gl.glEnable(GL.GL_DEPTH_TEST);
            return;
        }

        session.beginFrame();
        for (View view : session.views()) {
            if (view.isActive()) {
                drawView(gl, view, canvasWidth, canvasHeight);
            }
        }
        gl.glViewport(0, 0, canvasWidth, canvasHeight);
        gl.glEnable(GL.GL_DEPTH_TEST);

        var hud = session.finishFrame(canvasWidth, canvasHeight);
        gl.glDisable(GL.GL_DEPTH_TEST);
        text.draw(gl, hud, canvasWidth, canvasHeight);
    }

    private void releasePendingGpuResources(GL4 gl) {
        if (session.takePaletteInvalidation()) {
            quadtreeRenderer.invalidateTextures(gl);
        }
        for (TileAddress subtree : session.takePendingImageryReleases()) {
            terrainRenderer.releaseImagerySubtree(gl, subtree);
        }
    }

    private void drawView(GL4 gl, View view, int canvasWidth, int canvasHeight) {
        int[] viewport = view.viewportInPixels(canvasWidth, canvasHeight);
        gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        view.getCamera().updateViewportResize(viewport[2], viewport[3]);

        for (PyramidalImageInstance instance : session.orderedByStackHeight()) {
            List<DrawCommand> drawnCommands = session.isTerrainMode()
                ? drawTerrain(gl, instance, view)
                : drawPaletteImage(gl, instance, view);
            if (session.isTerrainTileBoundsVisible() && !drawnCommands.isEmpty()) {
                drawTileLabels(gl, drawnCommands, view, viewport, canvasWidth, canvasHeight);
            }
        }

        Light light = session.model().getTerrainLight();
        viewOverlays.draw(gl, view, light, viewport, canvasWidth, canvasHeight);
    }

    private List<DrawCommand> drawTerrain(GL4 gl, PyramidalImageInstance instance, View view) {
        Light light = session.terrainLightFor(view.getCamera());
        return terrainRenderer.draw(gl, instance, session.terrainFrameSettings(instance), view.getCamera(),
            view.getRenderingConfiguration(), light, session.terrainMaterial());
    }

    private List<DrawCommand> drawPaletteImage(GL4 gl, PyramidalImageInstance instance, View view) {
        return quadtreeRenderer.draw(gl, instance, session.cullingCamera(), session.relativeScale(instance),
            view.getRenderingConfiguration().isWiresSet(),
            session.isTerrainTileBoundsVisible(), session.isUniformLodVisible());
    }

    private void drawTileLabels(GL4 gl, List<DrawCommand> commands, View view, int[] viewport,
                                int canvasWidth, int canvasHeight) {
        var labels = TileLabelLayout.labels(commands, view.getCamera(), viewport);
        gl.glViewport(0, 0, canvasWidth, canvasHeight);
        gl.glDisable(GL.GL_DEPTH_TEST);
        text.draw(gl, labels, canvasWidth, canvasHeight);
        gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }
}
