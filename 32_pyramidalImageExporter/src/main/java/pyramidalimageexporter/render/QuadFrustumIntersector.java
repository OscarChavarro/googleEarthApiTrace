package pyramidalimageexporter.render;

import vsdk.toolkit.common.linealAlgebra.Vector3D;
import vsdk.toolkit.environment.camera.Camera;
import vsdk.toolkit.environment.geometry.elements.Ray;

final class QuadFrustumIntersector {
    private static final double EPS = 1e-9;

    private QuadFrustumIntersector() {
    }

    static boolean intersectsCameraFrustum(Camera camera, float x0, float y0, float x1, float y1) {
        if (camera == null) {
            return false;
        }

        float minX = Math.min(x0, x1);
        float maxX = Math.max(x0, x1);
        float minY = Math.min(y0, y1);
        float maxY = Math.max(y0, y1);

        Vector3D p00 = new Vector3D(minX, minY, 0.0);
        Vector3D p10 = new Vector3D(maxX, minY, 0.0);
        Vector3D p11 = new Vector3D(maxX, maxY, 0.0);
        Vector3D p01 = new Vector3D(minX, maxY, 0.0);

        if (isProjected(camera, p00) || isProjected(camera, p10) || isProjected(camera, p11) || isProjected(camera, p01)) {
            return true;
        }
        if (clipEdge(camera, p00, p10)
            || clipEdge(camera, p10, p11)
            || clipEdge(camera, p11, p01)
            || clipEdge(camera, p01, p00)) {
            return true;
        }
        return frustumCenterRayHitsQuad(camera, minX, maxX, minY, maxY);
    }

    private static boolean isProjected(Camera camera, Vector3D point) {
        return camera.projectPoint(point, new Vector3D());
    }

    private static boolean clipEdge(Camera camera, Vector3D a, Vector3D b) {
        return camera.clipLineCohenSutherlandPlanes(a, b, new Vector3D(), new Vector3D());
    }

    private static boolean frustumCenterRayHitsQuad(Camera camera, float minX, float maxX, float minY, float maxY) {
        int cx = (int)Math.round(camera.getViewportXSize() * 0.5);
        int cy = (int)Math.round(camera.getViewportYSize() * 0.5);
        Ray ray = camera.generateRay(cx, cy);

        double dz = ray.direction().z();
        if (Math.abs(dz) <= EPS) {
            return false;
        }
        double t = -ray.origin().z() / dz;
        if (t < 0.0) {
            return false;
        }

        Vector3D hit = ray.origin().add(ray.direction().multiply(t));
        return hit.x() >= minX - EPS
            && hit.x() <= maxX + EPS
            && hit.y() >= minY - EPS
            && hit.y() <= maxY + EPS;
    }
}
