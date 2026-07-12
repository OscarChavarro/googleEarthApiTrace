package planetviewer.processing;

import planetviewer.model.PlanetViewerModel;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;

/**
 * Ported from the old prototype's AquynzaUniverse#updateCameraPscWithRespectToZ0Plane:
 * keeps the camera's z coordinate inside [1, 10] by rescaling its position
 * by powers of 10 and adjusting PlanetViewerModel's currentPSC accordingly.
 * This is what makes arbitrary zoom possible without floating-point
 * precision loss: the camera never actually gets closer than "1 unit" to
 * the z = 0 plane, everything else is rescaled around it instead.
 */
public final class PscUpdater {
    private PscUpdater() {
    }

    public static void update(PlanetViewerModel model, Camera camera) {
        Vector3Dd p = camera.getPosition();
        int currentPSC = model.getCurrentPSC();

        while (p.z() < 1.0 || p.z() > 10.0) {
            if (p.z() < 0) {
                p = p.withZ(1.0);
                currentPSC = 0;
                camera.setPosition(p);
                break;
            }
            if (p.z() > 10.0) {
                p = p.multiply(1 / 10.0);
                camera.setPosition(p);
                currentPSC++;
            }
            if (p.z() < 1.0) {
                p = p.multiply(10.0);
                camera.setPosition(p);
                currentPSC--;
            }
        }
        model.setCurrentPSC(currentPSC);
    }
}
