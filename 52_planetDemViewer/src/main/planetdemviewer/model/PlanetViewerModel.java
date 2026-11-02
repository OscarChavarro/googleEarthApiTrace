package planetdemviewer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import planetdemviewer.config.Configuration;
import planetdemviewer.config.StorageProfile;
import planetdemviewer.palette.PaletteCatalog;
import planetdemviewer.render.terrain.TerrainSubdivisionScheme;
import planetdemviewer.scene.CameraSnapshot;
import planetdemviewer.scene.LayerSnapshot;
import planetdemviewer.scene.ProjectionType;
import planetdemviewer.scene.SceneSnapshot;
import planetdemviewer.scene.TerrainSurfaceSelection;
import planetdemviewer.spatial.WorldPosition;
import vsdk.toolkit.common.color.ColorRgb;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.light.Light;
import vsdk.toolkit.environment.light.PointLight;
import vsdk.toolkit.environment.material.RendererConfiguration;
import vsdk.toolkit.environment.material.SimpleMaterial;
import vsdk.toolkit.gui.widget.Widget;

/**
 * The scene: an ordered stack of pyramidal image instances plus the
 * "Powers of Ten" Power Scaled Coordinates (PSC) state, ported from the old
 * prototype's AquynzaUniverse. currentPSC is renormalized once per frame
 * against the main view's camera (see planetdemviewer.processing.PscUpdater),
 * and relativeScale(psc) is how every drawn tile is scaled so that
 * arbitrarily deep zoom never loses floating-point precision.
 */
public final class PlanetViewerModel {
    /** The single critical section shared by animation, input, I/O callbacks and JOGL rendering. */
    private final ReentrantReadWriteLock accessLock = new ReentrantReadWriteLock(true);
    private final List<PyramidalImageInstance> stack = new ArrayList<>();
    private final Camera viewingCamera = new Camera();
    private final RendererConfiguration renderingConfiguration = new RendererConfiguration();
    private final Light terrainLight;
    private final SimpleMaterial terrainMaterial;
    private final PaletteCatalog palettes = new PaletteCatalog(
        Configuration.PALETTE_DIRECTORY,
        Configuration.MINIMUM_ELEVATION_METRES,
        Configuration.MAXIMUM_ELEVATION_METRES
    );
    private int selectedIndex = -1;
    private int currentPSC = 0;
    private String hudStatus = "";
    private final StorageProfile storageProfile;
    private OperationModes operationMode = OperationModes.BASIC_TRIANGULATION;
    private TerrainSubdivisionScheme terrainSubdivisionScheme = TerrainSubdivisionScheme.TRIANGLE_EDGE;
    private double screenErrorThresholdPixels = Configuration.DEFAULT_SCREEN_ERROR_THRESHOLD_PIXELS;
    private boolean dependenciesCrossTiles = true;
    private boolean independentImageryLevels = true;
    private boolean imageryAvailable;
    private boolean imageryEnabled = true;
    private Widget guiDefinitions;
    private final List<Runnable> layerStateListeners = new CopyOnWriteArrayList<>();
    private double heightExagerationFactor = 1.0;
    private TerrainSurfaceSelection terrainSurfaceSelection;

    public PlanetViewerModel() {
        this(StorageProfile.SLOW);
    }

    public PlanetViewerModel(StorageProfile storageProfile) {
        this.storageProfile = storageProfile == null ? StorageProfile.SLOW : storageProfile;
        viewingCamera.setName("PlanetDemViewerCamera");
        viewingCamera.setPosition(new vsdk.toolkit.common.linealAlgebra.Vector3Dd(0.0, 0.0, 5.0));
        viewingCamera.setFocusedPositionDirect(new vsdk.toolkit.common.linealAlgebra.Vector3Dd(0.0, 0.0, 0.0));
        viewingCamera.setUpDirect(new vsdk.toolkit.common.linealAlgebra.Vector3Dd(0.0, 1.0, 0.0));
        viewingCamera.updateVectors();
        renderingConfiguration.setWires(false);
        renderingConfiguration.setTexture(false);
        renderingConfiguration.setBumpMap(false);
        renderingConfiguration.setShadingType(RendererConfiguration.SHADING_TYPE_PHONG);

        terrainLight = new PointLight(new Vector3Dd(0.0, 0.0, 7.0), new ColorRgb(1.0, 1.0, 1.0));
        terrainLight.setId(0);
        terrainLight.setName("Camera back light");

        SimpleMaterial material = new SimpleMaterial();
        material = material.withAmbient(new ColorRgb(0.12, 0.14, 0.10));
        material = material.withDiffuse(new ColorRgb(0.55, 0.68, 0.38));
        material = material.withSpecular(new ColorRgb(0.25, 0.25, 0.25));
        terrainMaterial = material.withPhongExponent(24.0);
    }

    public void writeLocked(Runnable action) {
        accessLock.writeLock().lock();
        try {
            action.run();
        }
        finally {
            accessLock.writeLock().unlock();
        }
    }

    public <T> T readLocked(Supplier<T> action) {
        accessLock.readLock().lock();
        try {
            return action.get();
        }
        finally {
            accessLock.readLock().unlock();
        }
    }

    /**
     * Copies all planner-relevant state while holding the model read lock.
     * Consumers must use this outside the lock rather than retaining mutable
     * Camera or PyramidalImageInstance objects.
     */
    public SceneSnapshot snapshot() {
        return readLocked(this::snapshotLocked);
    }

    private SceneSnapshot snapshotLocked() {
        CameraSnapshot camera = new CameraSnapshot(
            worldPosition(viewingCamera.getPosition()),
            worldPosition(viewingCamera.getFocusedPosition()),
            worldPosition(viewingCamera.getUp()),
            viewingCamera.getFov(),
            viewingCamera.getNearPlaneDistance(),
            viewingCamera.getFarPlaneDistance(),
            viewingCamera.getProjectionMode() == Camera.PROJECTION_MODE_ORTHOGONAL
                ? ProjectionType.ORTHOGRAPHIC : ProjectionType.PERSPECTIVE,
            viewingCamera.getOrthogonalZoom(),
            viewingCamera.getViewportXSize(),
            viewingCamera.getViewportYSize(),
            viewingCamera.getModificationVersion()
        );
        List<LayerSnapshot> layers = new ArrayList<>(stack.size());
        for (PyramidalImageInstance instance : stack) {
            layers.add(new LayerSnapshot(
                instance.layerId(),
                instance.descriptor(),
                instance.getImage().getSourceFolder(),
                instance.isVisible(),
                instance.getOffsetX(),
                instance.getOffsetY(),
                instance.getPsc(),
                instance.getZOffset(),
                instance.getOpacity()
            ));
        }
        return new SceneSnapshot(camera, layers, selectedIndex, currentPSC,
            operationMode, heightExagerationFactor, hudStatus, terrainSurfaceSelection);
    }

    private static WorldPosition worldPosition(Vector3Dd vector) {
        return new WorldPosition(vector.x(), vector.y(), vector.z());
    }

    public StorageProfile getStorageProfile() {
        return storageProfile;
    }

    public Camera getViewingCamera() {
        return viewingCamera;
    }

    public RendererConfiguration getRenderingConfiguration() {
        return renderingConfiguration;
    }

    public Light getTerrainLight() {
        return terrainLight;
    }

    public SimpleMaterial getTerrainMaterial() {
        return terrainMaterial;
    }

    /** Keeps the point light just behind the active camera, looking toward the terrain. */
    public void positionTerrainLightBehind(Camera camera) {
        if (camera == null) {
            return;
        }
        camera.updateVectors();
        Vector3Dd position = camera.getPosition();
        Vector3Dd behind = position.subtract(camera.getFront().multiply(2.0));
        terrainLight.setPosition(behind);
    }

    public OperationModes getOperationMode() {
        return operationMode;
    }

    public void cycleOperationMode() {
        operationMode = operationMode.next();
        notifyLayerStateChanged();
    }

    public void setOperationMode(OperationModes operationMode) {
        this.operationMode = operationMode == null ? OperationModes.BASIC_TRIANGULATION : operationMode;
        notifyLayerStateChanged();
    }

    public TerrainSubdivisionScheme getTerrainSubdivisionScheme() {
        return terrainSubdivisionScheme;
    }

    public void setTerrainSubdivisionScheme(TerrainSubdivisionScheme scheme) {
        terrainSubdivisionScheme = scheme;
        notifyLayerStateChanged();
    }

    /** The largest projected error, in pixels, that the view-dependent subdivision schemes accept. */
    public double getScreenErrorThresholdPixels() {
        return screenErrorThresholdPixels;
    }

    public void setScreenErrorThresholdPixels(double pixels) {
        screenErrorThresholdPixels = pixels;
        notifyLayerStateChanged();
    }

    /** Whether adjacent tiles of a view-dependent subdivision scheme simplify their common edge together. */
    public boolean isDependenciesCrossTiles() {
        return dependenciesCrossTiles;
    }

    public void setDependenciesCrossTiles(boolean enabled) {
        dependenciesCrossTiles = enabled;
        notifyLayerStateChanged();
    }

    /** Whether color may come from other levels than the geometry, in the schemes that allow it. */
    public boolean isIndependentImageryLevels() {
        return independentImageryLevels;
    }

    public void setIndependentImageryLevels(boolean enabled) {
        independentImageryLevels = enabled;
        notifyLayerStateChanged();
    }

    /** Whether the current scheme does draw color from other levels than the geometry. */
    public boolean usesIndependentImageryLevels() {
        return independentImageryLevels && terrainSubdivisionScheme.allowsImageryBeyondGeometryLevel();
    }

    public boolean isImageryAvailable() {
        return imageryAvailable;
    }

    /** Declares whether a color pyramid was loaded; without one, imagery is not drawn. */
    public void setImageryAvailable(boolean imageryAvailable) {
        this.imageryAvailable = imageryAvailable;
        notifyLayerStateChanged();
    }

    /** The user's choice, which is kept and saved even while no color pyramid is loaded. */
    public boolean isImageryEnabled() {
        return imageryEnabled;
    }

    public boolean isImageryVisible() {
        return imageryEnabled && imageryAvailable;
    }

    public void setImageryEnabled(boolean enabled) {
        imageryEnabled = enabled;
        notifyLayerStateChanged();
    }

    public void toggleImageryVisible() {
        if (imageryAvailable) {
            setImageryEnabled(!imageryEnabled);
        }
    }

    public void setGuiDefinitions(Widget guiDefinitions) {
        this.guiDefinitions = guiDefinitions;
    }

    /**
     * Returns a text of the current GUI language, replacing {0}, {1}... in one
     * pass, so an argument such as a folder name is never read as a placeholder.
     */
    public String text(String key, Object... arguments) {
        String template = guiDefinitions == null ? key : guiDefinitions.getMessage(key);
        StringBuilder result = new StringBuilder(template.length() + 32);
        int position = 0;
        while (position < template.length()) {
            int open = template.indexOf('{', position);
            int close = open < 0 ? -1 : template.indexOf('}', open);
            int index = close < 0 ? -1 : argumentIndex(template.substring(open + 1, close), arguments.length);
            if (open < 0) {
                result.append(template, position, template.length());
                break;
            }
            if (index < 0) {
                result.append(template, position, open + 1);
                position = open + 1;
                continue;
            }
            result.append(template, position, open).append(arguments[index]);
            position = close + 1;
        }
        return result.toString();
    }

    private static int argumentIndex(String text, int argumentCount) {
        if (text.length() != 1 || !Character.isDigit(text.charAt(0))) {
            return -1;
        }
        int index = text.charAt(0) - '0';
        return index < argumentCount ? index : -1;
    }

    /** Lets a GUI follow layer changes from any input path. May run while the model lock is held. */
    public void addLayerStateListener(Runnable layerStateListener) {
        layerStateListeners.add(layerStateListener);
    }

    private void notifyLayerStateChanged() {
        layerStateListeners.forEach(Runnable::run);
    }

    public double getHeightExagerationFactor() {
        return heightExagerationFactor;
    }

    public void setHeightExagerationFactor(double factor) {
        heightExagerationFactor = Math.max(1.0 / 1024.0, Math.min(1024.0, factor));
    }

    public void decreaseHeightExagerationFactor() {
        heightExagerationFactor = Math.max(1.0 / 1024.0, heightExagerationFactor / 2.0);
    }

    public void increaseHeightExagerationFactor() {
        heightExagerationFactor = Math.min(1024.0, heightExagerationFactor * 2.0);
    }

    public PaletteCatalog getPalettes() {
        return palettes;
    }

    public void cyclePalette(int delta) {
        palettes.cycle(delta);
    }

    public List<PyramidalImageInstance> getStack() {
        return Collections.unmodifiableList(stack);
    }

    public PyramidalImageInstance addImage(PyramidalImage image) {
        PyramidalImageInstance instance = new PyramidalImageInstance(image);
        stack.add(instance);
        selectedIndex = stack.size() - 1;
        return instance;
    }

    public int getInstanceCount() {
        return stack.size();
    }

    public PyramidalImageInstance getSelectedInstance() {
        if (selectedIndex < 0 || selectedIndex >= stack.size()) {
            return null;
        }
        return stack.get(selectedIndex);
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public boolean selectPreviousImage() {
        if (stack.isEmpty() || selectedIndex <= 0) {
            return false;
        }
        selectedIndex--;
        return true;
    }

    public boolean selectNextImage() {
        if (stack.isEmpty() || selectedIndex >= stack.size() - 1) {
            return false;
        }
        selectedIndex++;
        return true;
    }

    public int getCurrentPSC() {
        return currentPSC;
    }

    public void setCurrentPSC(int currentPSC) {
        this.currentPSC = currentPSC;
    }

    public double relativeScale(int psc) {
        int rel = psc - currentPSC - 1;
        return Math.pow(10.0, rel);
    }

    public String getHudStatus() {
        return hudStatus;
    }

    public void setHudStatus(String hudStatus) {
        this.hudStatus = hudStatus;
    }

    public TerrainSurfaceSelection getTerrainSurfaceSelection() {
        return terrainSurfaceSelection;
    }

    /** Null clears the marker after a sky click or when its source becomes unavailable. */
    public void setTerrainSurfaceSelection(TerrainSurfaceSelection terrainSurfaceSelection) {
        this.terrainSurfaceSelection = terrainSurfaceSelection;
    }
}
