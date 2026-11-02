package planetdemviewer.render;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLDrawableFactory;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLOffscreenAutoDrawable;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import java.awt.Color;
import java.awt.Font;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import planetdemviewer.io.TileImageLoader;
import planetdemviewer.io.TileTreeDiscoveryService;
import planetdemviewer.io.imagery.ImageryTileLoader;
import planetdemviewer.logger.Logger;
import planetdemviewer.model.PlanetViewerModel;
import planetdemviewer.model.OperationModes;
import planetdemviewer.terrain.TerrainSubdivisionScheme;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.processing.PscUpdater;
import planetdemviewer.scheduler.ResourceTelemetry;
import planetdemviewer.scheduler.ResourceType;
import planetdemviewer.processing.DrawCommand;
import planetdemviewer.geo.GeographicSurfacePosition;
import planetdemviewer.scene.CameraSnapshot;
import planetdemviewer.scene.ProjectionType;
import planetdemviewer.scene.TerrainSurfaceSelection;
import planetdemviewer.spatial.Ray3;
import planetdemviewer.spatial.TileAddress;
import planetdemviewer.spatial.WorldPosition;
import planetdemviewer.spatial.ViewportPoint;
import planetdemviewer.config.Configuration;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.common.linealAlgebra.Vector4Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.geometry.volume.Sphere;
import vsdk.toolkit.environment.material.RendererConfiguration;
import vsdk.toolkit.environment.material.SimpleMaterial;
import vsdk.toolkit.common.color.ColorRgb;
import vsdk.toolkit.gui.CameraControllerAquynza;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBImageUncompressed;
import vsdk.toolkit.render.jogl.Jogl4CameraRenderer;
import vsdk.toolkit.render.jogl.Jogl4LineRenderer;
import vsdk.toolkit.render.jogl.Jogl4SphereRenderer;

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
    private final Jogl4TerrainRenderer terrainRenderer = new Jogl4TerrainRenderer();
    private final ResourceTelemetry resourceTelemetry = new ResourceTelemetry();
    private final TileImageLoader tileImageLoader;
    private final TileTreeDiscoveryService treeDiscoveryService;
    private final ImageryTileLoader imageryTileLoader;
    /** Guarded by the model's critical section; drained by display(), which owns the GL context. */
    private final List<TileAddress> pendingImageryReleases = new ArrayList<>();
    // JOGL's drawable surface may use a higher pixel density than AWT's logical canvas.
    // Keep the last surface extent so both native clicks and MCP capture pixels map to the
    // same raster that was used to render the terrain.
    private volatile int renderedSurfaceWidth = 1;
    private volatile int renderedSurfaceHeight = 1;
    private final Sphere terrainSelectionSphere = new Sphere(1.0);
    private final RendererConfiguration terrainSelectionQuality = selectionQuality();
    private final SimpleMaterial terrainSelectionMaterial = selectionMaterial();
    private final List<View> views = new ArrayList<>();
    private int layoutStyle;
    private int fullScreenViewIndex = -1;
    private boolean cameraFrustumsVisible = true;
    private boolean terrainTileBoundsVisible;
    private boolean uniformLodVisible;
    private volatile TileAddress selectedTerrainTile;
    private Gl4TextRenderer hudTextRenderer;
    private Gl4TextRenderer terrainSelectionTextRenderer;
    private Gl4TextRenderer tileLabelTextRenderer;
    private Gl4TextRenderer performanceTextRenderer;
    private boolean offlineMode;
    private boolean offlineCaptureDone;
    private String offlineOutputPath;
    private volatile boolean paletteInvalidationRequested;
    private long fpsWindowStartedNanos = System.nanoTime();
    private int framesInFpsWindow;
    private int framesPerSecond;

    public Jogl4PlanetViewerRenderer(PlanetViewerModel model) { this(model, null); }

    public Jogl4PlanetViewerRenderer(PlanetViewerModel model, Path imageryRoot) {
        this.model = model;
        this.tileImageLoader = new TileImageLoader(model.getPalettes(), model.getStorageProfile(), resourceTelemetry);
        this.treeDiscoveryService = new TileTreeDiscoveryService(model.getStorageProfile());
        this.imageryTileLoader = imageryRoot == null ? null : new ImageryTileLoader(imageryRoot);
        model.setImageryAvailable(imageryTileLoader != null);
        View mainView = new View("View 1", model.getViewingCamera());
        mainView.setSelected(true);
        views.add(mainView);
        this.cameraController = new CameraControllerAquynza(model.getViewingCamera());
        this.cameraController.setDeltaMovement(0.2);
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
        return cameraController;
    }

    public long getGpuBytesAssigned() {
        return quadtreeRenderer.getGpuBytesAssigned() + terrainRenderer.getGpuBytesAssigned();
    }

    public int getResidentTextureCount() {
        return quadtreeRenderer.getResidentTextureCount();
    }

    public ResourceTelemetry resourceTelemetry() {
        return resourceTelemetry;
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
        TileAddress tile = selectedTerrainTile;
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

    /**
     * Selects from an AWT component pixel. Native AWT events use logical pixels, which
     * are scaled to JOGL's physical drawable pixels before picking.
     */
    public void selectTerrainAtPixel(int xPixel, int yPixelFromTop, int canvasWidth, int canvasHeight,
                                     boolean selectTile) {
        int surfaceWidth = renderedSurfaceWidth;
        int surfaceHeight = renderedSurfaceHeight;
        if (canvasWidth <= 0 || canvasHeight <= 0) {
            model.setTerrainSurfaceSelection(null);
            return;
        }
        int surfaceX = (int) Math.floor((xPixel + 0.5) * surfaceWidth / canvasWidth);
        int surfaceY = (int) Math.floor((yPixelFromTop + 0.5) * surfaceHeight / canvasHeight);
        selectTerrainAtSurfacePixel(surfaceX, surfaceY, surfaceWidth, surfaceHeight, selectTile);
    }

    /** Selects from a physical top-left-origin JOGL surface pixel, as used by MCP captures. */
    public void selectTerrainAtSurfacePixel(int xPixel, int yPixelFromTop) {
        selectTerrainAtSurfacePixel(xPixel, yPixelFromTop, renderedSurfaceWidth, renderedSurfaceHeight, false);
    }

    private void selectTerrainAtSurfacePixel(int xPixel, int yPixelFromTop, int surfaceWidth, int surfaceHeight,
                                             boolean selectTile) {
        if (model.getOperationMode() != OperationModes.BASIC_TRIANGULATION) {
            model.setTerrainSurfaceSelection(null);
            if (selectTile) {
                selectedTerrainTile = null;
            }
            return;
        }
        View view = views.get(indexOfSelected());
        int[] viewport = view.viewportInPixels(surfaceWidth, surfaceHeight);
        int viewportTop = surfaceHeight - viewport[1] - viewport[3];
        int localX = xPixel - viewport[0];
        int localY = yPixelFromTop - viewportTop;
        if (localX < 0 || localY < 0 || localX >= viewport[2] || localY >= viewport[3]) {
            model.setTerrainSurfaceSelection(null);
            if (selectTile) {
                selectedTerrainTile = null;
            }
            return;
        }
        Camera camera = view.getCamera();
        Ray3 ray = viewportRay(camera, localX, localY, viewport[2], viewport[3]);
        Jogl4TerrainRenderer.TerrainPickHit hit = terrainRenderer.intersectVisibleTerrain(ray);
        model.setTerrainSurfaceSelection(hit == null ? null : terrainSelection(hit));
        if (selectTile) {
            selectedTerrainTile = hit == null ? null : TileAddress.fabdem(hit.command().node().getId());
        }
    }

    /**
     * Reconstructs the picking ray by unprojecting with the exact Vitral matrix used by
     * rendering. This deliberately avoids an approximate camera-basis/FOV reconstruction:
     * Vitral's camera transform contains its own coordinate-system rotations.
     */
    private static Ray3 viewportRay(Camera camera, int xPixel, int yPixelFromTop, int width, int height) {
        double x = ((xPixel + 0.5) / width) * 2.0 - 1.0;
        double y = 1.0 - ((yPixelFromTop + 0.5) / height) * 2.0;
        Matrix4x4d inverseProjection = camera.calculateProjectionMatrix().inverse();
        WorldPosition near = unproject(inverseProjection, x, y, -1.0);
        WorldPosition far = unproject(inverseProjection, x, y, 1.0);
        return new Ray3(near, new WorldPosition(far.x() - near.x(), far.y() - near.y(), far.z() - near.z()));
    }

    private static WorldPosition unproject(Matrix4x4d inverseProjection, double x, double y, double z) {
        Vector4Dd point = inverseProjection.multiply(new Vector4Dd(x, y, z, 1.0)).dividedByW();
        return new WorldPosition(point.x(), point.y(), point.z());
    }

    public void setGeometryMode(boolean enabled) {
        model.setOperationMode(enabled ? OperationModes.BASIC_TRIANGULATION : OperationModes.PALETTE_BASED_IMAGE);
    }

    /** JSON fragment for remote diagnostics; positions are in the viewer's world coordinates. */
    public String selectedCameraModelJson() {
        Camera camera = views.get(indexOfSelected()).getCamera();
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
        String selected = viewTitle(views.get(indexOfSelected()));
        return fullScreenViewIndex >= 0
            ? model.text("IDM_HUD_VIEWS_FULL_SCREEN", views.size(), selected)
            : model.text("IDM_HUD_VIEWS_LAYOUT", views.size(), layoutStyle, selected);
    }

    private String viewTitle(View view) {
        return model.text("IDM_HUD_VIEW_TITLE", views.indexOf(view) + 1);
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

        GLProfile profile = GLProfile.get(GLProfile.GL4);
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
        hudTextRenderer = new Gl4TextRenderer(new Font("SansSerif", Font.BOLD, 16));
        terrainSelectionTextRenderer = new Gl4TextRenderer(new Font("Arial", Font.PLAIN, 32));
        tileLabelTextRenderer = new Gl4TextRenderer(new Font("Arial", Font.PLAIN, 12));
        performanceTextRenderer = new Gl4TextRenderer(new Font("Arial", Font.BOLD, 32));
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        if (hudTextRenderer != null) {
            hudTextRenderer.dispose(gl);
            hudTextRenderer = null;
        }
        if (terrainSelectionTextRenderer != null) {
            terrainSelectionTextRenderer.dispose(gl);
            terrainSelectionTextRenderer = null;
        }
        if (tileLabelTextRenderer != null) {
            tileLabelTextRenderer.dispose(gl);
            tileLabelTextRenderer = null;
        }
        if (performanceTextRenderer != null) {
            performanceTextRenderer.dispose(gl);
            performanceTextRenderer = null;
        }
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

    /** Runs exclusively on the JOGL animator thread while holding the scene critical section. */
    private void displayLocked(GLAutoDrawable drawable) {
        GL4 gl = drawable.getGL().getGL4();
        int canvasWidth = Math.max(1, drawable.getSurfaceWidth());
        int canvasHeight = Math.max(1, drawable.getSurfaceHeight());
        renderedSurfaceWidth = canvasWidth;
        renderedSurfaceHeight = canvasHeight;
        gl.glViewport(0, 0, canvasWidth, canvasHeight);
        gl.glClearColor(0.02f, 0.02f, 0.05f, 1.0f);
        gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
        gl.glDisable(GL.GL_DEPTH_TEST);

        if (paletteInvalidationRequested) {
            quadtreeRenderer.invalidateTextures(gl);
            paletteInvalidationRequested = false;
        }
        for (TileAddress subtree : pendingImageryReleases) {
            terrainRenderer.releaseImagerySubtree(gl, subtree);
        }
        pendingImageryReleases.clear();

        if (offlineMode) {
            Matrix4x4d projection = offlineTopProjection(drawable);
            for (PyramidalImageInstance instance : orderedByStackHeight()) {
                quadtreeRenderer.drawAll(gl, instance, 1.0, projection,
                    model.getRenderingConfiguration().isWiresSet(), tileImageLoader);
            }
            if (!offlineCaptureDone) {
                offlineCaptureDone = true;
                captureOffscreen(drawable, gl);
            }
            gl.glEnable(GL.GL_DEPTH_TEST);
            return;
        }

        PscUpdater.update(model, views.get(0).getCamera());
        terrainRenderer.beginPickFrame();

        for (View view : views) {
            if (!view.isActive()) {
                continue;
            }
            drawView(gl, view, canvasWidth, canvasHeight);
        }
        gl.glViewport(0, 0, canvasWidth, canvasHeight);
        gl.glEnable(GL.GL_DEPTH_TEST);

        resourceTelemetry.setBytes(ResourceType.TERRAIN_GPU, terrainRenderer.getGpuBytesAssigned());
        resourceTelemetry.setBytes(ResourceType.RASTER_GPU, quadtreeRenderer.getGpuBytesAssigned());

        updateFramesPerSecond();
        drawHud(gl, canvasWidth, canvasHeight);
    }

    private void drawView(GL4 gl, View view, int canvasWidth, int canvasHeight) {
        int[] viewport = view.viewportInPixels(canvasWidth, canvasHeight);
        gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        view.getCamera().updateViewportResize(viewport[2], viewport[3]);

        // Culling/LOD selection always runs against the first (main) view's camera, regardless
        // of which view is being rendered, so views other than the first show the first camera's
        // frustum culling from an outside vantage point (visual debugging of the AABB-vs-frustum test).
        Camera cullingCamera = views.get(0).getCamera();
        boolean wires = view.getRenderingConfiguration().isWiresSet();
        for (PyramidalImageInstance instance : orderedByStackHeight()) {
            double relativeScale = model.relativeScale(instance.getPsc());
            List<DrawCommand> drawnCommands;
            if (model.getOperationMode() == OperationModes.BASIC_TRIANGULATION) {
                model.positionTerrainLightBehind(view.getCamera());
                drawnCommands = terrainRenderer.draw(
                    gl,
                    instance,
                    cullingCamera,
                    view.getCamera(),
                    relativeScale,
                    model.getHeightExagerationFactor(),
                    view.getRenderingConfiguration(),
                    model.getTerrainLight(),
                    model.isImageryVisible() ? whiteTerrainMaterial() : model.getTerrainMaterial(),
                    tileImageLoader,
                    treeDiscoveryService,
                    terrainTileBoundsVisible,
                    uniformLodVisible, imageryTileLoader, model.isImageryVisible(),
                    selectedTerrainTile(),
                    model.getTerrainSubdivisionScheme()
                );
            }
            else {
                drawnCommands = quadtreeRenderer.draw(
                    gl,
                    instance,
                    cullingCamera,
                    relativeScale,
                    wires,
                    terrainTileBoundsVisible,
                    uniformLodVisible,
                    tileImageLoader,
                    treeDiscoveryService
                );
            }
            if (terrainTileBoundsVisible) {
                drawTileLabels(gl, drawnCommands, view.getCamera(), viewport, canvasWidth, canvasHeight);
            }
        }

        drawOtherCameraFrustums(gl, view);
        drawTerrainSelection(gl, view, viewport);
        drawViewBorder(gl, view, viewport, canvasWidth, canvasHeight);
    }

    private void drawTerrainSelection(GL4 gl, View view, int[] viewport) {
        TerrainSurfaceSelection selection = model.getTerrainSurfaceSelection();
        if (selection == null || model.getOperationMode() != OperationModes.BASIC_TRIANGULATION) {
            return;
        }
        WorldPosition position = selection.worldPosition();
        Vector3Dd cameraPosition = view.getCamera().getPosition();
        double distance = Math.sqrt(Math.pow(cameraPosition.x() - position.x(), 2)
            + Math.pow(cameraPosition.y() - position.y(), 2)
            + Math.pow(cameraPosition.z() - position.z(), 2));
        double radius = view.getCamera().getProjectionMode() == Camera.PROJECTION_MODE_ORTHOGONAL
            ? view.getCamera().getOrthogonalZoom() * 14.0 / Math.max(1, viewport[3])
            : distance * Math.tan(Math.toRadians(view.getCamera().getFov() * 0.5))
                * 14.0 / Math.max(1, viewport[3]);
        radius = Math.max(1e-8, radius);
        Matrix4x4d matrix = new Matrix4x4d().translation(position.x(), position.y(), position.z())
            .multiply(new Matrix4x4d().scale(radius, radius, radius));
        gl.glEnable(GL.GL_DEPTH_TEST);
        Jogl4SphereRenderer.draw(gl, terrainSelectionSphere, view.getCamera(), model.getTerrainLight(),
            terrainSelectionMaterial, terrainSelectionQuality, null, null, matrix, 16, 12);
    }

    private TerrainSurfaceSelection terrainSelection(Jogl4TerrainRenderer.TerrainPickHit hit) {
        TileAddress tile = TileAddress.fabdem(hit.command().node().getId());
        double longitude = hit.localX() * 360.0 - 180.0;
        double latitude = hit.localY() * 180.0 - 90.0;
        double centerLongitude = (hit.command().node().getX0() + hit.command().node().getX1()) * 180.0 - 180.0;
        double centerLatitude = (hit.command().node().getY0() + hit.command().node().getY1()) * 90.0 - 90.0;
        double radius = Configuration.WORLD_WIDTH_METRES / (2.0 * Math.PI);
        double geoidX = Math.toRadians(longitude - centerLongitude) * radius * Math.cos(Math.toRadians(centerLatitude));
        double geoidY = Math.toRadians(latitude - centerLatitude) * radius;
        GeographicSurfacePosition position = new GeographicSurfacePosition(longitude, latitude);
        double orthometricHeight = tileImageLoader.orthometricHeight(
            new planetdemviewer.geo.GeographicCoordinate(longitude, latitude),
            hit.ellipsoidalElevationMetres());
        return new TerrainSurfaceSelection(position, hit.worldPosition(), tile,
            orthometricHeight, geoidX, geoidY);
    }

    private TileAddress selectedTerrainTile() {
        return selectedTerrainTile;
    }

    private static CameraSnapshot snapshot(Camera camera) {
        return new CameraSnapshot(new WorldPosition(camera.getPosition().x(), camera.getPosition().y(), camera.getPosition().z()),
            new WorldPosition(camera.getFocusedPosition().x(), camera.getFocusedPosition().y(), camera.getFocusedPosition().z()),
            new WorldPosition(camera.getUp().x(), camera.getUp().y(), camera.getUp().z()),
            camera.getFov(), camera.getNearPlaneDistance(), camera.getFarPlaneDistance(),
            camera.getProjectionMode() == Camera.PROJECTION_MODE_ORTHOGONAL
                ? ProjectionType.ORTHOGRAPHIC : ProjectionType.PERSPECTIVE,
            camera.getOrthogonalZoom(), camera.getViewportXSize(), camera.getViewportYSize(),
            camera.getModificationVersion());
    }

    private static RendererConfiguration selectionQuality() {
        RendererConfiguration quality = new RendererConfiguration();
        quality.setSurfaces(true);
        quality.setWires(false);
        quality.setTexture(false);
        quality.setBumpMap(false);
        quality.setShadingType(RendererConfiguration.SHADING_TYPE_PHONG);
        return quality;
    }

    private static SimpleMaterial selectionMaterial() {
        SimpleMaterial material = new SimpleMaterial();
        material = material.withAmbient(new ColorRgb(0.10, 0.45, 0.75));
        material = material.withDiffuse(new ColorRgb(0.30, 0.80, 1.00));
        return material.withSpecular(new ColorRgb(0.70, 0.90, 1.00)).withPhongExponent(24.0);
    }

    private static SimpleMaterial whiteTerrainMaterial() {
        SimpleMaterial material = new SimpleMaterial();
        material = material.withAmbient(new ColorRgb(0.18, 0.18, 0.18));
        material = material.withDiffuse(new ColorRgb(1.0, 1.0, 1.0));
        return material.withSpecular(new ColorRgb(1.0, 1.0, 1.0)).withPhongExponent(32.0);
    }

    private void drawTileLabels(GL4 gl, List<DrawCommand> commands, Camera camera, int[] viewport,
                                int canvasWidth, int canvasHeight) {
        if (tileLabelTextRenderer == null || commands.isEmpty()) {
            return;
        }
        Matrix4x4d projection = camera.calculateProjectionMatrix();
        gl.glViewport(0, 0, canvasWidth, canvasHeight);
        gl.glDisable(GL.GL_DEPTH_TEST);
        for (DrawCommand command : commands) {
            ViewportPoint center = visibleViewportCenter(command.corners(), projection);
            if (center == null) {
                continue;
            }
            int x = viewport[0] + (int) Math.round((center.x + 1.0) * viewport[2] * 0.5);
            int y = viewport[1] + (int) Math.round((center.y + 1.0) * viewport[3] * 0.5);
            tileLabelTextRenderer.drawCentered(gl, tileName(command), x, y + 6,
                canvasWidth, canvasHeight, Color.YELLOW);
            tileLabelTextRenderer.drawCentered(gl, geographicCenter(command), x, y - 9,
                canvasWidth, canvasHeight, Color.YELLOW);
        }
        gl.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
    }

    /** Center of the tile portion clipped to the current viewport in NDC. */
    private static ViewportPoint visibleViewportCenter(Vector3Dd[] corners, Matrix4x4d projection) {
        List<ViewportPoint> polygon = new ArrayList<>(corners.length);
        for (Vector3Dd corner : corners) {
            Vector4Dd clip = projection.multiply(new Vector4Dd(corner));
            if (clip.w() <= 1e-7) {
                return null;
            }
            polygon.add(new ViewportPoint(clip.x() / clip.w(), clip.y() / clip.w()));
        }
        polygon = clipToViewport(polygon, point -> point.x + 1.0);
        polygon = clipToViewport(polygon, point -> 1.0 - point.x);
        polygon = clipToViewport(polygon, point -> point.y + 1.0);
        polygon = clipToViewport(polygon, point -> 1.0 - point.y);
        if (polygon.isEmpty()) {
            return null;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (ViewportPoint point : polygon) {
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }
        return new ViewportPoint((minX + maxX) * 0.5, (minY + maxY) * 0.5);
    }

    private static List<ViewportPoint> clipToViewport(List<ViewportPoint> polygon,
                                                        java.util.function.ToDoubleFunction<ViewportPoint> distance) {
        List<ViewportPoint> result = new ArrayList<>();
        for (int i = 0; i < polygon.size(); i++) {
            ViewportPoint from = polygon.get(i);
            ViewportPoint to = polygon.get((i + 1) % polygon.size());
            double fromDistance = distance.applyAsDouble(from);
            double toDistance = distance.applyAsDouble(to);
            boolean fromInside = fromDistance >= 0.0;
            boolean toInside = toDistance >= 0.0;
            if (fromInside) {
                result.add(from);
            }
            if (fromInside != toInside) {
                double fraction = fromDistance / (fromDistance - toDistance);
                result.add(new ViewportPoint(from.x + (to.x - from.x) * fraction,
                    from.y + (to.y - from.y) * fraction));
            }
        }
        return result;
    }

    private static String tileName(DrawCommand command) {
        File file = command.node().getTileFile();
        String name = file == null ? command.node().getId() : file.getName();
        int extension = name.lastIndexOf('.');
        return extension > 0 ? name.substring(0, extension) : name;
    }

    private static String geographicCenter(DrawCommand command) {
        double longitude = (command.node().getX0() + command.node().getX1()) * 180.0 - 180.0;
        double latitude = (command.node().getY0() + command.node().getY1()) * 90.0 - 90.0;
        return String.format(Locale.ROOT, "%.4f°, %.4f°", longitude, latitude);
    }

    private record ViewportPoint(double x, double y) {
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

    private void drawViewBorder(GL4 gl, View view, int[] viewport, int canvasWidth, int canvasHeight) {
        if (views.size() < 2) {
            return;
        }
        gl.glViewport(0, 0, canvasWidth, canvasHeight);
        gl.glDisable(GL.GL_DEPTH_TEST);
        float x0 = 2.0f * (viewport[0] + 1) / canvasWidth - 1.0f;
        float y0 = 2.0f * (viewport[1] + 1) / canvasHeight - 1.0f;
        float x1 = 2.0f * (viewport[0] + viewport[2] - 1) / canvasWidth - 1.0f;
        float y1 = 2.0f * (viewport[1] + viewport[3] - 1) / canvasHeight - 1.0f;
        float[] positions = {
            x0, y0, 0, x1, y0, 0,
            x1, y0, 0, x1, y1, 0,
            x1, y1, 0, x0, y1, 0,
            x0, y1, 0, x0, y0, 0
        };
        float r = view.isSelected() ? 1.0f : 0.4f;
        float g = view.isSelected() ? 0.85f : 0.4f;
        float[] colors = new float[positions.length];
        for (int i = 0; i < colors.length; i += 3) {
            colors[i] = r;
            colors[i + 1] = g;
            colors[i + 2] = 0.1f;
        }
        Jogl4LineRenderer.drawLines(gl, Matrix4x4d.identityMatrix(), positions, colors,
            view.isSelected() ? 2.5f : 1.0f);
        if (hudTextRenderer != null) {
            hudTextRenderer.draw(gl, viewTitle(view), viewport[0] + 6,
                viewport[1] + viewport[3] - 18, canvasWidth, canvasHeight);
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

    private Matrix4x4d offlineTopProjection(GLAutoDrawable drawable) {
        double aspect = (double) Math.max(1, drawable.getSurfaceWidth()) / Math.max(1, drawable.getSurfaceHeight());
        double halfHeight = 0.55;
        double halfWidth = halfHeight * aspect;
        return new Matrix4x4d().orthogonalProjection(
            -halfWidth, halfWidth, -halfHeight, halfHeight, -10.0, 10.0);
    }

    private void captureOffscreen(GLAutoDrawable drawable, GL4 gl) {
        Path output = Path.of(offlineOutputPath == null || offlineOutputPath.isBlank()
            ? "/tmp/planetDemViewer_offline.png" : offlineOutputPath);
        captureSnapshot(drawable, gl, output);
    }

    /** Must run with the canvas GL context current (for example through GLCanvas.invoke). */
    public Path captureSnapshot(GLAutoDrawable drawable, GL4 gl, Path output) {
        int width = Math.max(1, drawable.getSurfaceWidth());
        int height = Math.max(1, drawable.getSurfaceHeight());
        ByteBuffer bb = ByteBuffer.allocateDirect(3 * width * height);
        gl.glPixelStorei(GL.GL_PACK_ALIGNMENT, 1);
        gl.glReadPixels(0, 0, width, height, GL.GL_RGB, GL.GL_UNSIGNED_BYTE, bb);

        RGBImageUncompressed image = new RGBImageUncompressed();
        image.init(width, height);
        int k = 0;
        for (int y = height - 1; y >= 0; y--) {
            for (int x = 0; x < width; x++) {
                image.putPixel(x, y, bb.get(k++), bb.get(k++), bb.get(k++));
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

    private void drawHud(GL4 gl, int canvasWidth, int canvasHeight) {
        if (hudTextRenderer == null) {
            return;
        }
        gl.glDisable(GL.GL_DEPTH_TEST);
        hudTextRenderer.draw(gl, selectedImageLabel(), 16, canvasHeight - 24, canvasWidth, canvasHeight);
        hudTextRenderer.draw(
            gl, model.text("IDM_HUD_CAMERA", model.getCurrentPSC(),
                String.format("%.4f", views.get(0).getCamera().getPosition().z()),
                quadtreeRenderer.getResidentTextureCount(),
                quadtreeRenderer.getGpuBytesAssigned() / (1024 * 1024),
                viewSummary()),
            16, canvasHeight - 46, canvasWidth, canvasHeight
        );
        hudTextRenderer.draw(
            gl, model.text("IDM_HUD_MODE", operationModeLabel(),
                String.format("%.4gx", model.getHeightExagerationFactor()),
                terrainRenderer.getResidentMeshCount(),
                terrainRenderer.getGpuBytesAssigned() / (1024 * 1024),
                model.text(uniformLodVisible ? "IDM_HUD_ON" : "IDM_HUD_OFF"),
                subdivisionSchemeLabel()),
            16, canvasHeight - 68, canvasWidth, canvasHeight
        );
        hudTextRenderer.draw(
            gl, model.text("IDM_HUD_PALETTE", model.getPalettes().selectedName(),
                model.getPalettes().minimumElevation(), model.getPalettes().maximumElevation()),
            16, canvasHeight - 90, canvasWidth, canvasHeight
        );
        hudTextRenderer.draw(gl, model.text("IDM_HUD_KEYS"), 16, canvasHeight - 112, canvasWidth, canvasHeight);
        if (performanceTextRenderer != null) {
            int right = canvasWidth - 18;
            performanceTextRenderer.drawRightAligned(gl, model.text("IDM_HUD_FPS", framesPerSecond),
                right, 94, canvasWidth, canvasHeight, Color.YELLOW);
            performanceTextRenderer.drawRightAligned(gl,
                model.text("IDM_HUD_TILE_RAM", formatBytes(tileImageLoader.getRamBytesUsed())),
                right, 58, canvasWidth, canvasHeight, Color.YELLOW);
            performanceTextRenderer.drawRightAligned(gl,
                model.text("IDM_HUD_TILE_GPU", formatBytes(getGpuBytesAssigned())),
                right, 22, canvasWidth, canvasHeight, Color.YELLOW);
        }
        TerrainSurfaceSelection selection = model.getTerrainSurfaceSelection();
        if (selection != null && terrainSelectionTextRenderer != null) {
            Color selectionColor = new Color(45, 125, 205);
            terrainSelectionTextRenderer.draw(gl, model.text("IDM_HUD_SURFACE",
                String.format(Locale.ROOT, "%.6f", selection.position().longitude()),
                String.format(Locale.ROOT, "%.6f", selection.position().latitude()),
                String.format(Locale.ROOT, "%.2f", selection.heightAboveMeanSeaLevelMetres())),
                16, 76, canvasWidth, canvasHeight, selectionColor);
            terrainSelectionTextRenderer.draw(gl, model.text("IDM_HUD_SELECTED_TILE",
                selection.tile().quadkey(), selection.tile().level(),
                String.format(Locale.ROOT, "%.2f", selection.geoidXMetres()),
                String.format(Locale.ROOT, "%.2f", selection.geoidYMetres())),
                16, 38, canvasWidth, canvasHeight, selectionColor);
        }
    }

    private String subdivisionSchemeLabel() {
        return model.text(model.getTerrainSubdivisionScheme() == TerrainSubdivisionScheme.DIAMOND_SQUARE
            ? "IDM_LAYER_SUBDIVISION_DIAMOND_SQUARE" : "IDM_LAYER_SUBDIVISION_TRIANGLE_EDGE");
    }

    private String operationModeLabel() {
        return model.text(model.getOperationMode() == OperationModes.BASIC_TRIANGULATION
            ? "IDM_LAYER_ELEVATION_MESH" : "IDM_LAYER_ELEVATION_PALETTE");
    }

    private void updateFramesPerSecond() {
        framesInFpsWindow++;
        long now = System.nanoTime();
        long elapsed = now - fpsWindowStartedNanos;
        if (elapsed < 1_000_000_000L) {
            return;
        }
        framesPerSecond = (int) Math.round(framesInFpsWindow * 1_000_000_000.0 / elapsed);
        framesInFpsWindow = 0;
        fpsWindowStartedNanos = now;
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        }
        while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    private String selectedImageLabel() {
        PyramidalImageInstance selected = model.getSelectedInstance();
        if (selected == null) {
            return model.text("IDM_HUD_NO_IMAGE");
        }
        return model.text("IDM_HUD_IMAGE", model.getSelectedIndex() + 1, model.getInstanceCount(),
            selected.getImage().getSourceFolder(),
            selected.getImage().getTileCount(), selected.getImage().getHeight(),
            treeDiscoveryService.getPendingCount(),
            String.format("%.3f", selected.getOpacity()),
            String.format("%.2f", selected.getZOffset()));
    }
}
