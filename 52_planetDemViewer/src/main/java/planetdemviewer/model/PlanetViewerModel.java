package planetdemviewer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import planetdemviewer.config.Configuration;
import planetdemviewer.config.StorageProfile;
import planetdemviewer.palette.PaletteCatalog;
import vsdk.toolkit.common.color.ColorRgb;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.light.Light;
import vsdk.toolkit.environment.light.PointLight;
import vsdk.toolkit.environment.material.RendererConfiguration;
import vsdk.toolkit.environment.material.SimpleMaterial;

/**
 * The scene: an ordered stack of pyramidal image instances plus the
 * "Powers of Ten" Power Scaled Coordinates (PSC) state, ported from the old
 * prototype's AquynzaUniverse. currentPSC is renormalized once per frame
 * against the main view's camera (see planetdemviewer.processing.PscUpdater),
 * and relativeScale(psc) is how every drawn tile is scaled so that
 * arbitrarily deep zoom never loses floating-point precision.
 */
public final class PlanetViewerModel {
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
    private OperationModes operationMode = OperationModes.PALETTE_BASED_IMAGE;
    private double heightExagerationFactor = 1.0;

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
    }

    public double getHeightExagerationFactor() {
        return heightExagerationFactor;
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
}
