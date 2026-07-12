package planetviewer.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.material.RendererConfiguration;

/**
 * The scene: an ordered stack of pyramidal image instances plus the
 * "Powers of Ten" Power Scaled Coordinates (PSC) state, ported from the old
 * prototype's AquynzaUniverse. currentPSC is renormalized once per frame
 * against the main view's camera (see planetviewer.processing.PscUpdater),
 * and relativeScale(psc) is how every drawn tile is scaled so that
 * arbitrarily deep zoom never loses floating-point precision.
 */
public final class PlanetViewerModel {
    private final List<PyramidalImageInstance> stack = new ArrayList<>();
    private final Camera viewingCamera = new Camera();
    private final RendererConfiguration renderingConfiguration = new RendererConfiguration();
    private int selectedIndex = -1;
    private int currentPSC = 0;
    private String hudStatus = "";

    public PlanetViewerModel() {
        viewingCamera.setName("PlanetViewerCamera");
        viewingCamera.setPosition(new vsdk.toolkit.common.linealAlgebra.Vector3Dd(0.0, 0.0, 5.0));
        viewingCamera.setFocusedPositionDirect(new vsdk.toolkit.common.linealAlgebra.Vector3Dd(0.0, 0.0, 0.0));
        viewingCamera.setUpDirect(new vsdk.toolkit.common.linealAlgebra.Vector3Dd(0.0, 1.0, 0.0));
        viewingCamera.updateVectors();
        renderingConfiguration.setWires(false);
    }

    public Camera getViewingCamera() {
        return viewingCamera;
    }

    public RendererConfiguration getRenderingConfiguration() {
        return renderingConfiguration;
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
