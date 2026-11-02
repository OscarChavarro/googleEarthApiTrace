package planetdemviewer.render;

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
import planetdemviewer.render.terrain.TerrainFramePlanner;
import planetdemviewer.render.terrain.TerrainFrameSettings;
import planetdemviewer.render.terrain.TerrainImageryCounts;
import planetdemviewer.render.terrain.TerrainImageryStatistics;
import planetdemviewer.render.terrain.TerrainPickFrame;
import planetdemviewer.render.terrain.TerrainPicker;
import planetdemviewer.render.terrain.TerrainSimplificationCounts;
import planetdemviewer.render.terrain.TerrainSimplificationStatistics;
import planetdemviewer.scheduler.ResourceTelemetry;
import planetdemviewer.scheduler.ResourceType;
import planetdemviewer.spatial.TileAddress;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.light.Light;
import vsdk.toolkit.environment.material.RendererConfiguration;
import vsdk.toolkit.environment.material.SimpleMaterial;
import vsdk.toolkit.gui.CameraControllerAquynza;

/**
 * Everything about the running viewer that does not depend on the graphics API:
 * the tile loaders, the views, the selection and what the user chose to see. The
 * application talks to this class, and a graphics backend reads from it to draw
 * each frame. PSC renormalization always tracks the first view's camera, so the
 * whole stack shares a single "universe" scale regardless of which view is being
 * manipulated.
 */
public final class PlanetViewerSession {
    private final PlanetViewerModel model;
    private final ResourceTelemetry resourceTelemetry = new ResourceTelemetry();
    private final TileImageLoader tileImageLoader;
    private final TileTreeDiscoveryService treeDiscoveryService;
    private final ImageryTileLoader imageryTileLoader;
    private final ViewportLayout viewports;
    private final TerrainSimplificationStatistics simplificationStatistics = new TerrainSimplificationStatistics();
    private final TerrainImageryStatistics imageryStatistics = new TerrainImageryStatistics();
    private final TerrainPickFrame pickFrame = new TerrainPickFrame();
    private final TerrainPicker terrainPicker;
    private final HudContent hud;
    private final ViewOverlayPlanner viewOverlays;
    /** Guarded by the model's critical section; drained by the frame, which owns the graphics context. */
    private final List<TileAddress> pendingImageryReleases = new ArrayList<>();
    private GpuResourceUsage rasterGpuUsage = GpuResourceUsage.NONE;
    private GpuResourceUsage terrainGpuUsage = GpuResourceUsage.NONE;
    private boolean terrainTileBoundsVisible;
    private boolean uniformLodVisible;
    private volatile boolean paletteInvalidationRequested;

    public PlanetViewerSession(PlanetViewerModel model) {
        this(model, null);
    }

    public PlanetViewerSession(PlanetViewerModel model, Path imageryRoot) {
        this.model = model;
        this.tileImageLoader = new TileImageLoader(model.getPalettes(), model.getStorageProfile(), resourceTelemetry);
        this.treeDiscoveryService = new TileTreeDiscoveryService(model.getStorageProfile());
        this.imageryTileLoader = imageryRoot == null ? null : new ImageryTileLoader(imageryRoot);
        model.setImageryAvailable(imageryTileLoader != null);
        this.viewports = new ViewportLayout(model.getViewingCamera());
        this.terrainPicker = new TerrainPicker(model, viewports, pickFrame, tileImageLoader);
        this.hud = new HudContent(model, viewports);
        this.viewOverlays = new ViewOverlayPlanner(model, viewports);
    }

    public PlanetViewerModel model() {
        return model;
    }

    public <T> PaletteFramePlanner<T> newPaletteFramePlanner() {
        PaletteFramePlanner<T> planner = new PaletteFramePlanner<>(tileImageLoader, treeDiscoveryService);
        rasterGpuUsage = planner.textureUsage();
        return planner;
    }

    public <T, M> TerrainFramePlanner<T, M> newTerrainFramePlanner() {
        TerrainFramePlanner<T, M> planner =
            new TerrainFramePlanner<>(tileImageLoader, treeDiscoveryService, imageryTileLoader,
                simplificationStatistics, imageryStatistics, pickFrame);
        terrainGpuUsage = planner.meshUsage();
        return planner;
    }

    public ViewOverlayPlanner viewOverlays() {
        return viewOverlays;
    }

    /** Repaints are requested whenever a background-loaded tile becomes ready, so the image sharpens progressively. */
    public void setRepaintOnTileReady(Runnable repaintAction) {
        tileImageLoader.setOnTileReady(repaintAction);
        treeDiscoveryService.setOnTreeChanged(repaintAction);
        if (imageryTileLoader != null) {
            imageryTileLoader.setOnReady(repaintAction);
        }
    }

    public CameraControllerAquynza getCameraController() {
        return viewports.cameraController();
    }

    /** Called from the AWT event thread; the textures are destroyed by the next frame. */
    public void cyclePalette(int delta) {
        model.cyclePalette(delta);
        tileImageLoader.paletteChanged();
        paletteInvalidationRequested = true;
    }

    public void toggleImageryVisible() {
        model.toggleImageryVisible();
    }

    /**
     * Removes the selected tile's optional PNG and everything below it; DEM .bin
     * pages are never deleted. Runs inside the model's critical section on the AWT
     * event thread, so textures are left to the next frame, and the files to the loader.
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

    public void addView() {
        viewports.addView();
    }

    public void removeView() {
        viewports.removeView();
    }

    public void cycleSelectedView() {
        viewports.cycleSelectedView();
    }

    public void cycleLayoutStyle() {
        viewports.cycleLayoutStyle();
    }

    public void toggleCameraFrustumsVisible() {
        viewOverlays.toggleCameraFrustumsVisible();
    }

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

    /** Selects from a physical top-left-origin surface pixel, as used by MCP captures. */
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
            + ",\"screenErrorThresholdPixels\":" + model.getScreenErrorThresholdPixels()
            + ",\"dependenciesCrossTiles\":" + model.isDependenciesCrossTiles()
            + ",\"independentImageryLevels\":" + model.isIndependentImageryLevels()
            + ",\"imageryLevels\":" + imageryStatistics.lastFrame().json()
            + ",\"wires\":" + viewports.selectedRenderingConfiguration().isWiresSet()
            + ",\"terrainSimplification\":" + simplificationStatistics.lastFrame().json()
            + ",\"uniformLod\":" + uniformLodVisible
            + ",\"tileBounds\":" + terrainTileBoundsVisible + "}";
    }

    /** What the view-dependent subdivision did in the last finished frame. Safe to read from any thread. */
    public TerrainSimplificationCounts terrainSimplificationCounts() {
        return simplificationStatistics.lastFrame();
    }

    /** Which levels of the two quadtrees the last finished frame used. Safe to read from any thread. */
    public TerrainImageryCounts terrainImageryCounts() {
        return imageryStatistics.lastFrame();
    }

    public void setSelectedCameraModel(Vector3Dd position, Vector3Dd focus, Vector3Dd up) {
        Camera camera = viewports.selectedView().getCamera();
        camera.setPosition(position);
        camera.setFocusedPositionDirect(focus);
        camera.setUpDirect(up);
        camera.updateVectors();
    }

    private static String vectorJson(Vector3Dd vector) {
        return "{\"x\":" + vector.x() + ",\"y\":" + vector.y() + ",\"z\":" + vector.z() + "}";
    }

    public void surfaceRendered(int width, int height) {
        terrainPicker.setRenderedSurfaceSize(width, height);
    }

    /** True once after the palette changed, when the textures derived from it must be destroyed. */
    public boolean takePaletteInvalidation() {
        boolean requested = paletteInvalidationRequested;
        paletteInvalidationRequested = false;
        return requested;
    }

    /** The imagery subtrees whose textures must be destroyed. */
    public List<TileAddress> takePendingImageryReleases() {
        List<TileAddress> released = List.copyOf(pendingImageryReleases);
        pendingImageryReleases.clear();
        return released;
    }

    public void beginFrame() {
        PscUpdater.update(model, viewports.mainView().getCamera());
        pickFrame.begin();
        simplificationStatistics.beginFrame();
        imageryStatistics.beginFrame();
    }

    public List<View> activeViews() {
        return viewports.views().stream().filter(View::isActive).toList();
    }

    /** What to write over the tiles drawn in a view: nothing unless their bounds are shown. */
    public List<TextLabel> tileLabels(List<DrawCommand> drawn, View view, int[] viewport) {
        return terrainTileBoundsVisible ? TileLabelLayout.labels(drawn, view.getCamera(), viewport) : List.of();
    }

    public List<PyramidalImageInstance> orderedByStackHeight() {
        List<PyramidalImageInstance> ordered = new ArrayList<>(model.getStack());
        ordered.sort(Comparator.comparingDouble(PyramidalImageInstance::getZOffset));
        return ordered;
    }

    public boolean isTerrainMode() {
        return model.getOperationMode() == OperationModes.BASIC_TRIANGULATION;
    }

    /**
     * Culling/LOD selection always runs against the first (main) view's camera, regardless
     * of which view is being rendered, so views other than the first show the first camera's
     * frustum culling from an outside vantage point (visual debugging of the AABB-vs-frustum test).
     */
    public Camera cullingCamera() {
        return viewports.mainView().getCamera();
    }

    public double relativeScale(PyramidalImageInstance instance) {
        return model.relativeScale(instance.getPsc());
    }

    public TerrainFrameSettings terrainFrameSettings(PyramidalImageInstance instance) {
        return new TerrainFrameSettings(cullingCamera(), relativeScale(instance),
            model.getHeightExagerationFactor(), uniformLodVisible, model.getTerrainSubdivisionScheme(),
            terrainTileBoundsVisible, terrainPicker.selectedTile(), model.isImageryVisible(),
            model.getScreenErrorThresholdPixels(), model.isDependenciesCrossTiles(),
            model.usesIndependentImageryLevels());
    }

    /** The light follows the camera of the view being drawn. */
    public Light terrainLightFor(Camera camera) {
        model.positionTerrainLightBehind(camera);
        return model.getTerrainLight();
    }

    public SimpleMaterial terrainMaterial() {
        return model.isImageryVisible() ? RenderStyles.whiteTerrainMaterial() : model.getTerrainMaterial();
    }

    /** The whole stack seen from above, for the offline snapshot. */
    public static Matrix4x4d offlineTopProjection(int width, int height) {
        double aspect = (double) Math.max(1, width) / Math.max(1, height);
        double halfHeight = 0.55;
        double halfWidth = halfHeight * aspect;
        return new Matrix4x4d().orthogonalProjection(
            -halfWidth, halfWidth, -halfHeight, halfHeight, -10.0, 10.0);
    }

    public boolean isOfflineWiresVisible() {
        return model.getRenderingConfiguration().isWiresSet();
    }

    /** Publishes what the planners keep on the GPU, and returns the HUD to write over the finished frame. */
    public List<TextLabel> finishFrame(int canvasWidth, int canvasHeight) {
        resourceTelemetry.setBytes(ResourceType.TERRAIN_GPU, terrainGpuUsage.bytesAssigned());
        resourceTelemetry.setBytes(ResourceType.RASTER_GPU, rasterGpuUsage.bytesAssigned());
        hud.frameRendered();
        return hud.labels(canvasWidth, canvasHeight, new HudFrameStats(
            rasterGpuUsage.residentCount(), rasterGpuUsage.bytesAssigned(),
            terrainGpuUsage.residentCount(), terrainGpuUsage.bytesAssigned(), tileImageLoader.getRamBytesUsed(),
            treeDiscoveryService.getPendingCount(), uniformLodVisible, simplificationStatistics.currentFrame()));
    }

    public void shutdown() {
        tileImageLoader.shutdown();
        if (imageryTileLoader != null) {
            imageryTileLoader.shutdown();
        }
        treeDiscoveryService.shutdown();
    }
}
