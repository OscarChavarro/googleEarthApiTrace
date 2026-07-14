package planetviewer.render;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.util.awt.TextRenderer;
import java.awt.Font;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import planetviewer.io.TileImageLoader;
import planetviewer.model.PlanetViewerModel;
import planetviewer.model.PyramidalImageInstance;
import planetviewer.processing.PscUpdater;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.gui.CameraControllerAquynza;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBImageUncompressed;
import vsdk.toolkit.render.jogl.Jogl4CameraRenderer;

/**
 * Owns the canvas's viewport layout: 1 to 4 Views (ported from the old
 * prototype's JoglView/ViewOrganizer). One shared CameraControllerAquynza is
 * retargeted (via setCamera) to whichever view is currently selected, so
 * keyboard/mouse camera interaction always applies to the view under
 * interaction. PSC renormalization always tracks views.get(0)'s camera,
 * matching the old prototype's display() loop, so the whole stack shares a
 * single "universe" scale regardless of which view is being manipulated.
 */
public final class Jogl4PlanetViewerRenderer implements GLEventListener {
    private static final int MAX_VIEWS = 4;

    private final PlanetViewerModel model;
    private final CameraControllerAquynza cameraController;
    private final Jogl4QuadtreeRenderer quadtreeRenderer = new Jogl4QuadtreeRenderer();
    private final TileImageLoader tileImageLoader = new TileImageLoader();
    private final List<View> views = new ArrayList<>();
    private int layoutStyle;
    private int fullScreenViewIndex = -1;
    private boolean cameraFrustumsVisible = true;
    private TextRenderer hudTextRenderer;
    private boolean offlineMode;
    private boolean offlineCaptureDone;
    private String offlineOutputPath;

    public Jogl4PlanetViewerRenderer(PlanetViewerModel model) {
        this.model = model;
        View mainView = new View("View 1", model.getViewingCamera());
        mainView.setSelected(true);
        views.add(mainView);
        this.cameraController = new CameraControllerAquynza(model.getViewingCamera());
        this.cameraController.setDeltaMovement(0.2);
    }

    /** Repaints are requested whenever a background-loaded tile becomes ready, so the image sharpens progressively. */
    public void setRepaintOnTileReady(Runnable repaintAction) {
        tileImageLoader.setOnTileReady(repaintAction);
    }

    public GLCanvas createCanvas() {
        GLProfile profile = GLProfile.get(GLProfile.GL4bc);
        GLCapabilities caps = new GLCapabilities(profile);
        caps.setDepthBits(24);
        GLCanvas canvas = new GLCanvas(caps);
        canvas.addGLEventListener(this);
        return canvas;
    }

    public CameraControllerAquynza getCameraController() {
        return cameraController;
    }

    public long getGpuBytesAssigned() {
        return quadtreeRenderer.getGpuBytesAssigned();
    }

    public int getResidentTextureCount() {
        return quadtreeRenderer.getResidentTextureCount();
    }

    // --- View management (mirrors the old prototype's '.', ',', Alt+w, and an add/remove view pair) ---

    public void addView() {
        if (views.size() >= MAX_VIEWS) {
            return;
        }
        Camera camera = new Camera();
        Vector3Dd sourcePosition = views.get(0).getCamera().getPosition();
        camera.setPosition(new Vector3Dd(sourcePosition.x(), sourcePosition.y(), sourcePosition.z()));
        camera.setFocusedPositionDirect(new Vector3Dd(0.0, 0.0, 0.0));
        camera.setUpDirect(new Vector3Dd(0.0, 1.0, 0.0));
        camera.updateVectors();
        View newView = new View("View " + (views.size() + 1), camera);
        views.add(newView);
        selectView(views.size() - 1);
    }

    public void removeView() {
        if (views.size() <= 1) {
            return;
        }
        views.remove(views.size() - 1);
        if (fullScreenViewIndex >= views.size()) {
            fullScreenViewIndex = -1;
        }
        selectView(0);
    }

    public void cycleSelectedView() {
        int next = (indexOfSelected() + 1) % views.size();
        selectView(next);
    }

    public void cycleLayoutStyle() {
        layoutStyle++;
        relayout();
    }

    public void toggleCameraFrustumsVisible() {
        cameraFrustumsVisible = !cameraFrustumsVisible;
    }

    /** Routes a click/press at this canvas pixel to the view under it, and retargets the camera controller. */
    public void selectViewAtPixel(int xPixel, int yPixelFromTop, int canvasWidth, int canvasHeight) {
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            return;
        }
        double xPercent = (double) xPixel / canvasWidth;
        double yPercent = 1.0 - (double) yPixelFromTop / canvasHeight;
        for (int i = 0; i < views.size(); i++) {
            if (views.get(i).isActive() && views.get(i).inside(xPercent, yPercent)) {
                selectView(i);
                return;
            }
        }
    }

    public vsdk.toolkit.environment.material.RendererConfiguration getSelectedViewRenderingConfiguration() {
        return views.get(indexOfSelected()).getRenderingConfiguration();
    }

    public String viewSummary() {
        return "views: " + views.size() + (fullScreenViewIndex >= 0 ? " (full-screen)" : " (layout " + (layoutStyle) + ")")
            + " | selected: " + views.get(indexOfSelected()).getTitle();
    }

    private void selectView(int index) {
        for (int i = 0; i < views.size(); i++) {
            views.get(i).setSelected(i == index);
        }
        relayout();
    }

    private int indexOfSelected() {
        for (int i = 0; i < views.size(); i++) {
            if (views.get(i).isSelected()) {
                return i;
            }
        }
        return 0;
    }

    private void relayout() {
        int selected = ViewOrganizer.doLayout(views, fullScreenViewIndex, layoutStyle);
        cameraController.setCamera(views.get(selected).getCamera());
    }

    /** Renders the whole stack, top view, to an image file without opening a window. */
    public void startOffscreen(String outputPath, int width, int height) {
        if (!vsdk.toolkit.render.jogl.Jogl4Renderer.verifyOpenGLAvailability()) {
            System.out.println("Can not start OpenGL/JOGL renderer.");
            return;
        }
        offlineMode = true;
        offlineCaptureDone = false;
        offlineOutputPath = outputPath;

        GLProfile profile = GLProfile.get(GLProfile.GL4bc);
        GLCapabilities caps = new GLCapabilities(profile);
        caps.setDoubleBuffered(false);

        GLDrawableFactory factory = GLDrawableFactory.getFactory(profile);
        GLOffscreenAutoDrawable pBuffer = factory.createOffscreenAutoDrawable(
            null, caps, null, Math.max(1, width), Math.max(1, height)
        );
        try {
            pBuffer.addGLEventListener(this);
            pBuffer.display();
        }
        finally {
            try {
                pBuffer.removeGLEventListener(this);
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

    @Override
    public void init(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        hudTextRenderer = new TextRenderer(new Font("SansSerif", Font.BOLD, 16));
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        if (hudTextRenderer != null) {
            hudTextRenderer.dispose();
            hudTextRenderer = null;
        }
        quadtreeRenderer.dispose(drawable.getGL().getGL2());
        Jogl4CameraRenderer.dispose(drawable.getGL().getGL4());
        tileImageLoader.shutdown();
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        GL2 gl2 = drawable.getGL().getGL2();
        int canvasWidth = Math.max(1, drawable.getSurfaceWidth());
        int canvasHeight = Math.max(1, drawable.getSurfaceHeight());
        gl.glViewport(0, 0, canvasWidth, canvasHeight);
        gl.glClearColor(0.02f, 0.02f, 0.05f, 1.0f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
        gl.glDisable(GL.GL_DEPTH_TEST);

        if (offlineMode) {
            applyOfflineTopFraming(gl2, drawable);
            for (PyramidalImageInstance instance : orderedByStackHeight()) {
                quadtreeRenderer.drawAll(gl2, instance, 1.0, model.getRenderingConfiguration().isWiresSet());
            }
            if (!offlineCaptureDone) {
                offlineCaptureDone = true;
                captureOffscreen(drawable, gl2);
            }
            gl.glEnable(GL.GL_DEPTH_TEST);
            return;
        }

        PscUpdater.update(model, views.get(0).getCamera());

        for (View view : views) {
            if (!view.isActive()) {
                continue;
            }
            drawView(gl, gl2, view, canvasWidth, canvasHeight);
        }
        gl.glViewport(0, 0, canvasWidth, canvasHeight);
        gl.glEnable(GL.GL_DEPTH_TEST);

        drawHud(canvasWidth, canvasHeight);
    }

    private void drawView(GL4 gl, GL2 gl2, View view, int canvasWidth, int canvasHeight) {
        int[] viewport = view.viewportInPixels(canvasWidth, canvasHeight);
        gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        view.getCamera().updateViewportResize(viewport[2], viewport[3]);

        Matrix4x4d projection = view.getCamera().calculateViewVolumeMatrix();
        float[] modelView = view.getCamera().calculateTransformationMatrix().exportToFloatArrayColumnOrder();
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glLoadMatrixf(projection.exportToFloatArrayColumnOrder(), 0);
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glLoadMatrixf(modelView, 0);

        // Culling/LOD selection always runs against the first (main) view's camera, regardless
        // of which view is being rendered, so views other than the first show the first camera's
        // frustum culling from an outside vantage point (visual debugging of the AABB-vs-frustum test).
        Camera cullingCamera = views.get(0).getCamera();
        boolean wires = view.getRenderingConfiguration().isWiresSet();
        for (PyramidalImageInstance instance : orderedByStackHeight()) {
            double relativeScale = model.relativeScale(instance.getPsc());
            quadtreeRenderer.draw(gl2, instance, cullingCamera, relativeScale, wires, tileImageLoader);
        }

        drawOtherCameraFrustums(gl, view);
        drawViewBorder(gl2, view, viewport, canvasWidth, canvasHeight);
    }

    /**
     * Lets viewports other than the first one see the other viewports' cameras as cyan
     * frustum gizmos. By convention, the first (main) view never shows camera frustums.
     */
    private void drawOtherCameraFrustums(GL4 gl, View view) {
        if (!cameraFrustumsVisible || views.size() < 2 || view == views.get(0)) {
            return;
        }
        Matrix4x4d viewerProjection = view.getCamera().calculateProjectionMatrix();
        for (View other : views) {
            if (other == view || !other.isActive()) {
                continue;
            }
            Jogl4CameraRenderer.draw(gl, other.getCamera(), viewerProjection);
        }
    }

    private void drawViewBorder(GL2 gl2, View view, int[] viewport, int canvasWidth, int canvasHeight) {
        if (views.size() < 2) {
            return;
        }
        gl2.glViewport(0, 0, canvasWidth, canvasHeight);
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glLoadIdentity();
        gl2.glOrtho(0, canvasWidth, 0, canvasHeight, -1, 1);
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glLoadIdentity();
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glDisable(GL2.GL_DEPTH_TEST);
        gl2.glColor3f(view.isSelected() ? 1.0f : 0.4f, view.isSelected() ? 0.85f : 0.4f, 0.1f);
        gl2.glLineWidth(view.isSelected() ? 2.5f : 1.0f);
        gl2.glBegin(GL2.GL_LINE_LOOP);
        gl2.glVertex2i(viewport[0] + 1, viewport[1] + 1);
        gl2.glVertex2i(viewport[0] + viewport[2] - 1, viewport[1] + 1);
        gl2.glVertex2i(viewport[0] + viewport[2] - 1, viewport[1] + viewport[3] - 1);
        gl2.glVertex2i(viewport[0] + 1, viewport[1] + viewport[3] - 1);
        gl2.glEnd();
        if (hudTextRenderer != null) {
            hudTextRenderer.beginRendering(canvasWidth, canvasHeight);
            hudTextRenderer.setColor(1.0f, 1.0f, 1.0f, 1.0f);
            hudTextRenderer.draw(view.getTitle(), viewport[0] + 6, viewport[1] + viewport[3] - 18);
            hudTextRenderer.endRendering();
        }
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int xSize, int ySize) {
        // Per-view camera aspect ratios are recomputed every frame in drawView, since
        // each view's pixel viewport depends on the whole canvas size.
    }

    private List<PyramidalImageInstance> orderedByStackHeight() {
        List<PyramidalImageInstance> ordered = new ArrayList<>(model.getStack());
        ordered.sort(Comparator.comparingDouble(PyramidalImageInstance::getZOffset));
        return ordered;
    }

    private void applyOfflineTopFraming(GL2 gl2, GLAutoDrawable drawable) {
        double aspect = (double) Math.max(1, drawable.getSurfaceWidth()) / Math.max(1, drawable.getSurfaceHeight());
        double halfHeight = 0.55;
        double halfWidth = halfHeight * aspect;
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glLoadIdentity();
        gl2.glOrtho(-halfWidth, halfWidth, -halfHeight, halfHeight, -10.0, 10.0);
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glLoadIdentity();
    }

    private void captureOffscreen(GLAutoDrawable drawable, GL2 gl2) {
        int width = Math.max(1, drawable.getSurfaceWidth());
        int height = Math.max(1, drawable.getSurfaceHeight());
        ByteBuffer bb = ByteBuffer.allocateDirect(3 * width * height);
        gl2.glPixelStorei(GL.GL_PACK_ALIGNMENT, 1);
        gl2.glReadPixels(0, 0, width, height, GL.GL_RGB, GL.GL_UNSIGNED_BYTE, bb);

        RGBImageUncompressed image = new RGBImageUncompressed();
        image.init(width, height);
        int k = 0;
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                image.putPixel(x, y, bb.get(k++), bb.get(k++), bb.get(k++));
            }
        }
        File out = new File(offlineOutputPath == null || offlineOutputPath.isBlank()
            ? "/tmp/planetViewer_offline.png"
            : offlineOutputPath);
        try {
            Path parent = out.toPath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            ImagePersistence.exportPNG(out, image);
            System.out.println("Offline image written to: " + out.getAbsolutePath());
        }
        catch (Exception e) {
            System.out.println("Could not write offline image: " + e.getMessage());
        }
    }

    private void drawHud(int canvasWidth, int canvasHeight) {
        if (hudTextRenderer == null) {
            return;
        }
        hudTextRenderer.beginRendering(canvasWidth, canvasHeight);
        hudTextRenderer.setColor(1.0f, 1.0f, 1.0f, 1.0f);
        hudTextRenderer.draw(selectedImageLabel(), 16, canvasHeight - 24);
        hudTextRenderer.draw(
            "PSC: " + model.getCurrentPSC()
                + " | camera z: " + String.format("%.4f", views.get(0).getCamera().getPosition().z())
                + " | GPU tiles: " + quadtreeRenderer.getResidentTextureCount()
                + " (" + (quadtreeRenderer.getGpuBytesAssigned() / (1024 * 1024)) + " MB)"
                + " | " + viewSummary(),
            16, canvasHeight - 46
        );
        hudTextRenderer.draw(
            "Zoom: wheel/z/Z | Reset: r/R | Load: l | Image: 1/2 | Opacity: o/O | Stack z: PgUp/PgDn"
                + " | View: ./,/w/v/V | ESC: exit",
            16, canvasHeight - 68
        );
        hudTextRenderer.endRendering();
    }

    private String selectedImageLabel() {
        PyramidalImageInstance selected = model.getSelectedInstance();
        if (selected == null) {
            return "No pyramidal image loaded. Press 'l' to load one.";
        }
        return "Image " + (model.getSelectedIndex() + 1) + "/" + model.getInstanceCount()
            + ": " + selected.getImage().getSourceFolder()
            + " | opacity " + String.format("%.3f", selected.getOpacity())
            + " | z " + String.format("%.2f", selected.getZOffset());
    }
}
