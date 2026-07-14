package planetviewer.processing;

import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;

/**
 * Logarithmic dolly zoom towards/away from the z = 0 plane, ported and
 * fixed from the old prototype's AquynzaScales z/Z key handlers (its own
 * mouse wheel handler was marked "not working" in a source comment, and its
 * Z-key branch never called setPosition() because the old Vector3D was
 * mutable; both bugs are fixed here since Vitral 1.3's Vector3Dd is
 * immutable). The step shrinks as the camera nears z = 1 (never reaching
 * the plane in one step) and grows as it nears z = 10, so
 * PscUpdater#update can renormalize it smoothly across PSC boundaries every
 * frame without ever losing floating-point precision, which is what makes
 * arbitrarily deep zoom possible.
 */
public final class CameraZoom {
    private static final double RATE = 1.5;

    private CameraZoom() {
    }

    public static void zoomIn(Camera camera) {
        Vector3Dd p = camera.getPosition();
        double z = p.z() + RATE * Math.log10(clamp(1 - p.z() / 10.0));
        camera.setPosition(p.withZ(z));
    }

    public static void zoomOut(Camera camera) {
        Vector3Dd p = camera.getPosition();
        double z = p.z() - RATE * Math.log10(clamp(1 - p.z() / 10.0));
        camera.setPosition(p.withZ(z));
    }

    private static double clamp(double value) {
        return Math.max(1e-9, Math.min(1.0, value));
    }
}
