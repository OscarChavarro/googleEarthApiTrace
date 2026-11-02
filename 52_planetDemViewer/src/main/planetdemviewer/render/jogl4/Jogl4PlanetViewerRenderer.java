package planetdemviewer.render.jogl4;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import planetdemviewer.io.TileImageLoader;
import planetdemviewer.io.TileTreeDiscoveryService;
import planetdemviewer.io.imagery.ImageryTileLoader;
import planetdemviewer.logger.Logger;
import planetdemviewer.model.OperationModes;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.processing.DrawCommand;
import planetdemviewer.processing.PscUpdater;
import planetdemviewer.render.HudFrameStats;
import planetdemviewer.render.TerrainPicker;
import planetdemviewer.render.View;
import planetdemviewer.render.ViewportLayout;
import planetdemviewer.scheduler.ResourceTelemetry;
import planetdemviewer.scheduler.ResourceType;
import planetdemviewer.spatial.TileAddress;
import vsdk.toolkit.common.color.ColorRgb;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.material.RendererConfiguration;
import vsdk.toolkit.environment.material.SimpleMaterial;
import vsdk.toolkit.gui.CameraControllerAquynza;
import vsdk.toolkit.render.jogl.Jogl4CameraRenderer;
import vsdk.toolkit.render.jogl.Jogl4LineRenderer;

/**
 * Orchestrates each frame on the canvas: it owns the GL lifecycle and the order
 * of drawing, and delegates the work to its collaborators. PSC renormalization
 * always tracks the first view's camera, matching the old prototype's display()
 * loop, so the whole stack shares a single "universe" scale regardless of which
 * view is being manipulated.
 */
public final class Jogl4PlanetViewerRenderer implements GLEventListener {
    private final PlanetViewerModel model;
    private final Jogl4QuadtreeRenderer quadtreeRenderer = new Jogl4QuadtreeRenderer();
    private final Jogl4TerrainRenderer terrainRenderer = new Jogl4TerrainRenderer();
    private final ResourceTelemetry resourceTelemetry = new ResourceTelemetry();
    private final TileImageLoader tileImageLoader;
    private final TileTreeDiscoveryService treeDiscoveryService;
    private final ImageryTileLoader imageryTileLoader;
    private final ViewportLayout viewports;
    private final TerrainPicker terrainPicker;
    private final Jogl4HudRenderer hud;
    private final Jogl4ViewOverlayRenderer viewOverlays;
    private final Jogl4TileLabelRenderer tileLabels = new Jogl4TileLabelRenderer();
    /** Guarded by the model's critical section; drained by display(), which owns the GL context. */
    private final List<TileAddress> pendingImageryReleases = new ArrayList<>();
    private Jogl4OfflineStackRenderer offlineRenderer;
    private boolean terrainTileBoundsVisible;
    private boolean uniformLodVisible;
    private volatile boolean paletteInvalidationRequested;

    public Jogl4PlanetViewerRenderer(PlanetViewerModel model) { this(model, null); }

    public Jogl4PlanetViewerRenderer(PlanetViewerModel model, Path imageryRoot) {
        this.model = model;
        this.tileImageLoader = new TileImageLoader(model.getPalettes(), model.getStorageProfile(), resourceTelemetry);
        this.treeDiscoveryService = new TileTreeDiscoveryService(model.getStorageProfile());
        this.imageryTileLoader = imageryRoot == null ? null : new ImageryTileLoader(imageryRoot);
        model.setImageryAvailable(imageryTileLoader != null);
        this.viewports = new ViewportLayout(model.getViewingCamera());
        this.terrainPicker = new TerrainPicker(
            model, viewports, terrainRenderer::intersectVisibleTerrain, tileImageLoader);
        this.hud = new Jogl4HudRenderer(model, viewports);
        this.viewOverlays = new Jogl4ViewOverlayRenderer(model, viewports);
    }

    /** Repaints are requested whenever a background-loaded tile becomes ready, so the image sharpens progressively. */
    public void setRepaintOnTileReady(Runnable repaintAction) {
        tileImageLoader.setOnTileReady(repaintAction);
        treeDiscoveryService.setOnTreeChanged(repaintAction);
        if (imageryTileLoader != null) imageryTileLoader.setOnReady(repaintAction);
    }

    public GLCanvas createCanvas() {
        GLProfile profile = GLProfile.get(GLProfile.GL4);
        GLCapabilities caps = new GLCapabilities(profile);
        caps.setDepthBits(24);
        GLCanvas canvas = new GLCanvas(caps);
        canvas.addGLEventListener(this);
        return canvas;
    }

    public CameraControllerAquynza getCameraController() {
        return viewports.cameraController();
    }

    /** Called from the AWT event thread; actual GL destruction runs in display(). */
    public void cyclePalette(int delta) {
        model.cyclePalette(delta);
        tileImageLoader.paletteChanged();
        paletteInvalidationRequested = true;
    }
    public void toggleImageryVisible() { model.toggleImageryVisible(); }

    /**
     * Removes the selected tile's optional PNG and everything below it; DEM .bin
     * pages are never deleted. Runs inside the model's critical section on the AWT
     * event thread, so GL destruction is left to display(), and the files to the loader.
     */
    public void deleteSelectedImageryTile() {
        TileAddress tile = terrainPicker.selectedTile();
        if (tile == null) {
            model.setHudStatus("No terrain tile is selected");
            return;
        }
        if (imageryTileLoader == null) {
            model.setHudStatus("No imagery pyramid is loaded; no PNG was deleted");
            return;
        }
        pendingImageryReleases.add(tile);
        model.setHudStatus("Deleting imagery subtree: " + tile.quadkey());
        imageryTileLoader.deleteSubtree(tile).whenComplete((report, failure) -> {
            String status = failure == null
                ? "Deleted imagery subtree " + tile.quadkey() + ": " + report.summary()
                : "Could not delete imagery subtree " + tile.quadkey() + ": " + failure.getMessage();
            Logger.info(status);
            model.writeLocked(() -> model.setHudStatus(status));
        });
    }

    public void addView() { viewports.addView(); }
    public void removeView() { viewports.removeView(); }
    public void cycleSelectedView() { viewports.cycleSelectedView(); }
    public void cycleLayoutStyle() { viewports.cycleLayoutStyle(); }
    public void toggleCameraFrustumsVisible() { viewOverlays.toggleCameraFrustumsVisible(); }

    /** Routes a click/press at this canvas pixel to the view under it, and retargets the camera controller. */
    public void selectViewAtPixel(int xPixel, int yPixelFromTop, int canvasWidth, int canvasHeight) {
        viewports.selectViewAtPixel(xPixel, yPixelFromTop, canvasWidth, canvasHeight);
    }

    public RendererConfiguration getSelectedViewRenderingConfiguration() {
        return viewports.selectedRenderingConfiguration();
    }

    /** Shows the min/max bounding box of every terrain tile selected for 3D rendering. */
    public void toggleTerrainTileBoundsVisible() {
        terrainTileBoundsVisible = !terrainTileBoundsVisible;
    }

    public void setTerrainTileBoundsVisible(boolean visible) {
        terrainTileBoundsVisible = visible;
    }

    public boolean isTerrainTileBoundsVisible() {
        return terrainTileBoundsVisible;
    }

    /** Keeps all visible tiles at the finest LOD that dynamic selection currently needs. */
    public void toggleUniformLodVisible() {
        uniformLodVisible = !uniformLodVisible;
    }

    public void setUniformLodVisible(boolean visible) {
        uniformLodVisible = visible;
    }

    public boolean isUniformLodVisible() {
        return uniformLodVisible;
    }

    public void setGeometryMode(boolean enabled) {
        model.setOperationMode(enabled ? OperationModes.BASIC_TRIANGULATION : OperationModes.PALETTE_BASED_IMAGE);
    }

    public void selectTerrainAtPixel(int xPixel, int yPixelFromTop, int canvasWidth, int canvasHeight,
                                     boolean selectTile) {
        terrainPicker.selectAtCanvasPixel(xPixel, yPixelFromTop, canvasWidth, canvasHeight, selectTile);
    }

    /** Selects from a physical top-left-origin JOGL surface pixel, as used by MCP captures. */
    public void selectTerrainAtSurfacePixel(int xPixel, int yPixelFromTop) {
        terrainPicker.selectAtSurfacePixel(xPixel, yPixelFromTop);
    }

    /** JSON fragment for remote diagnostics; positions are in the viewer's world coordinates. */
    public String selectedCameraModelJson() {
        Camera camera = viewports.selectedView().getCamera();
        return "{\"position\":" + vectorJson(camera.getPosition())
            + ",\"focus\":" + vectorJson(camera.getFocusedPosition())
            + ",\"up\":" + vectorJson(camera.getUp())
            + ",\"front\":" + vectorJson(camera.getFront())
            + ",\"projectionMode\":" + camera.getProjectionMode()
            + ",\"operationMode\":\"" + model.getOperationMode() + "\""
            + ",\"imageryVisible\":" + model.isImageryVisible()
            + ",\"subdivisionScheme\":\"" + model.getTerrainSubdivisionScheme() + "\""
            + ",\"uniformLod\":" + uniformLodVisible
            + ",\"tileBounds\":" + terrainTileBoundsVisible + "}";
    }

    private static String vectorJson(Vector3Dd vector) {
        return "{\"x\":" + vector.x() + ",\"y\":" + vector.y() + ",\"z\":" + vector.z() + "}";
    }

    /** Renders the whole stack, top view, to an image file without opening a window. */
    public void startOffscreen(String outputPath, int width, int height) {
        offlineRenderer = new Jogl4OfflineStackRenderer(outputPath);
        offlineRenderer.run(this, width, height);
    }

    /** Must run with the canvas GL context current (for example through GLCanvas.invoke). */
    public Path captureSnapshot(GLAutoDrawable drawable, GL4 gl, Path output) {
        return Jogl4FramebufferCaptureRenderer.writePng(drawable, gl, output);
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        gl.glEnable(GL.GL_DEPTH_TEST);
        gl.glEnable(GL.GL_BLEND);
        gl.glBlendFunc(GL.GL_SRC_ALPHA, GL.GL_ONE_MINUS_SRC_ALPHA);
        hud.init();
        viewOverlays.init();
        tileLabels.init();
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        hud.dispose(gl);
        viewOverlays.dispose(gl);
        tileLabels.dispose(gl);
        quadtreeRenderer.dispose(gl);
        terrainRenderer.dispose(gl);
        Jogl4CameraRenderer.dispose(gl);
        Jogl4LineRenderer.release(gl);
        tileImageLoader.shutdown();
        if (imageryTileLoader != null) imageryTileLoader.shutdown();
        treeDiscoveryService.shutdown();
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        model.writeLocked(() -> displayLocked(drawable));
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
        terrainPicker.setRenderedSurfaceSize(canvasWidth, canvasHeight);
        gl.glViewport(0, 0, canvasWidth, canvasHeight);
        gl.glClearColor(0.02f, 0.02f, 0.05f, 1.0f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
        gl.glDisable(GL.GL_DEPTH_TEST);

        releasePendingGpuResources(gl);

        if (offlineRenderer != null) {
            offlineRenderer.drawFrame(drawable, gl, orderedByStackHeight(), quadtreeRenderer,
                model.getRenderingConfiguration().isWiresSet(), tileImageLoader);
            gl.glEnable(GL.GL_DEPTH_TEST);
            return;
        }

        PscUpdater.update(model, viewports.mainView().getCamera());
        terrainRenderer.beginPickFrame();

        for (View view : viewports.views()) {
            if (view.isActive()) {
                drawView(gl, view, canvasWidth, canvasHeight);
            }
        }
        gl.glViewport(0, 0, canvasWidth, canvasHeight);
        gl.glEnable(GL.GL_DEPTH_TEST);

        resourceTelemetry.setBytes(ResourceType.TERRAIN_GPU, terrainRenderer.getGpuBytesAssigned());
        resourceTelemetry.setBytes(ResourceType.RASTER_GPU, quadtreeRenderer.getGpuBytesAssigned());

        hud.draw(gl, canvasWidth, canvasHeight, hudFrameStats());
    }

    private void releasePendingGpuResources(GL4 gl) {
        if (paletteInvalidationRequested) {
            quadtreeRenderer.invalidateTextures(gl);
            paletteInvalidationRequested = false;
        }
        for (TileAddress subtree : pendingImageryReleases) {
            terrainRenderer.releaseImagerySubtree(gl, subtree);
        }
        pendingImageryReleases.clear();
    }

    private HudFrameStats hudFrameStats() {
        return new HudFrameStats(
            quadtreeRenderer.getResidentTextureCount(),
            quadtreeRenderer.getGpuBytesAssigned(),
            terrainRenderer.getResidentMeshCount(),
            terrainRenderer.getGpuBytesAssigned(),
            tileImageLoader.getRamBytesUsed(),
            treeDiscoveryService.getPendingCount(),
            uniformLodVisible);
    }

    private void drawView(GL4 gl, View view, int canvasWidth, int canvasHeight) {
        int[] viewport = view.viewportInPixels(canvasWidth, canvasHeight);
        gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        view.getCamera().updateViewportResize(viewport[2], viewport[3]);

        for (PyramidalImageInstance instance : orderedByStackHeight()) {
            List<DrawCommand> drawnCommands = model.getOperationMode() == OperationModes.BASIC_TRIANGULATION
                ? drawTerrain(gl, instance, view)
                : drawPaletteImage(gl, instance, view);
            if (terrainTileBoundsVisible) {
                tileLabels.draw(gl, drawnCommands, view.getCamera(), viewport, canvasWidth, canvasHeight);
            }
        }

        viewOverlays.draw(gl, view, viewport, canvasWidth, canvasHeight);
    }

    private List<DrawCommand> drawTerrain(GL4 gl, PyramidalImageInstance instance, View view) {
        model.positionTerrainLightBehind(view.getCamera());
        return terrainRenderer.draw(
            gl,
            instance,
            cullingCamera(),
            view.getCamera(),
            model.relativeScale(instance.getPsc()),
            model.getHeightExagerationFactor(),
            view.getRenderingConfiguration(),
            model.getTerrainLight(),
            model.isImageryVisible() ? whiteTerrainMaterial() : model.getTerrainMaterial(),
            tileImageLoader,
            treeDiscoveryService,
            terrainTileBoundsVisible,
            uniformLodVisible, imageryTileLoader, model.isImageryVisible(),
            terrainPicker.selectedTile(),
            model.getTerrainSubdivisionScheme()
        );
    }

    private List<DrawCommand> drawPaletteImage(GL4 gl, PyramidalImageInstance instance, View view) {
        return quadtreeRenderer.draw(
            gl,
            instance,
            cullingCamera(),
            model.relativeScale(instance.getPsc()),
            view.getRenderingConfiguration().isWiresSet(),
            terrainTileBoundsVisible,
            uniformLodVisible,
            tileImageLoader,
            treeDiscoveryService
        );
    }

    /**
     * Culling/LOD selection always runs against the first (main) view's camera, regardless
     * of which view is being rendered, so views other than the first show the first camera's
     * frustum culling from an outside vantage point (visual debugging of the AABB-vs-frustum test).
     */
    private Camera cullingCamera() {
        return viewports.mainView().getCamera();
    }

    private List<PyramidalImageInstance> orderedByStackHeight() {
        List<PyramidalImageInstance> ordered = new ArrayList<>(model.getStack());
        ordered.sort(Comparator.comparingDouble(PyramidalImageInstance::getZOffset));
        return ordered;
    }

    private static SimpleMaterial whiteTerrainMaterial() {
        SimpleMaterial material = new SimpleMaterial();
        material = material.withAmbient(new ColorRgb(0.18, 0.18, 0.18));
        material = material.withDiffuse(new ColorRgb(1.0, 1.0, 1.0));
        return material.withSpecular(new ColorRgb(1.0, 1.0, 1.0)).withPhongExponent(32.0);
    }
}
