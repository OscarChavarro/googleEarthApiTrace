package planetdemviewer.terrain;

import vsdk.toolkit.environment.camera.Camera;

/**
 * Decides, for one viewpoint and screen-space threshold, whether a vertex's delta
 * segment projects to less than the threshold. Every length is in world units.
 *
 * <p>References:</p>
 * <ul>
 * <li>[LIND1996] Lindstrom, P. et al. "Real-Time, Continuous Level of Detail
 * Rendering of Height Fields", SIGGRAPH 1996.</li>
 * </ul>
 *
 * <p>The paper's constant d*lambda is the projection plane distance times the pixels
 * per world unit on that plane. Their product does not depend on where the plane is,
 * so it is unrelated to the near plane of the camera, and equals
 * viewportHeight / (2 tan(fov / 2)) for Vitral's vertical field of view. This
 * derivation is not in the paper.</p>
 *
 * <p>Equations 1 and 2 assume that the segment is at the center of the view, so they
 * make it relatively smaller at the periphery of the screen (section [LIND1996].4.1).
 * They assume a perspective projection, so there is no criterion for an orthogonal
 * camera.</p>
 *
 * @param kappa the constant of Equation [LIND1996](2)
 */
public record ProjectedDeltaCriterion(double eyeX, double eyeY, double eyeZ, double kappa) {
    /** Returns null for a camera that has no perspective projection. */
    public static ProjectedDeltaCriterion of(Camera camera, double thresholdPixels) {
        if (camera.getProjectionMode() != Camera.PROJECTION_MODE_PERSPECTIVE) {
            return null;
        }
        double distanceTimesPixelsPerUnit = camera.getViewportYSize()
            / (2.0 * Math.tan(Math.toRadians(camera.getFov() / 2.0)));
        return new ProjectedDeltaCriterion(camera.getPosition().x(), camera.getPosition().y(),
            camera.getPosition().z(), thresholdPixels / distanceTimesPixelsPerUnit);
    }

    /** Equation [LIND1996](2): true when the triangles of the vertex may be coalesced. */
    boolean coalesces(double delta, double vertexX, double vertexY, double vertexZ) {
        if (Double.isInfinite(delta)) {
            return false;
        }
        double dx = eyeX - vertexX;
        double dy = eyeY - vertexY;
        double dz = eyeZ - vertexZ;
        double horizontal = dx * dx + dy * dy;
        double squaredDistance = horizontal + dz * dz;
        return delta * delta * horizontal <= kappa * kappa * squaredDistance * squaredDistance;
    }

    /**
     * The uncertainty interval Iu of section [LIND1996].4.2 for the vertices of a bounding
     * box: Equations [LIND1996](3) and (4). The constraint that the viewpoint stays
     * beyond the projection plane is not used, which only makes the interval larger.
     */
    UncertaintyInterval uncertaintyInterval(double minX, double minY, double minZ,
                                             double maxX, double maxY, double maxZ) {
        double nearestX = eyeX - clamp(minX, eyeX, maxX);
        double nearestY = eyeY - clamp(minY, eyeY, maxY);
        double farthestX = Math.max(Math.abs(eyeX - minX), Math.abs(eyeX - maxX));
        double farthestY = Math.max(Math.abs(eyeY - minY), Math.abs(eyeY - maxY));
        double rMin = Math.sqrt(nearestX * nearestX + nearestY * nearestY);
        double rMax = Math.sqrt(farthestX * farthestX + farthestY * farthestY);

        double hMin = Math.abs(eyeZ - clamp(minZ, eyeZ, maxZ));
        double hMax = Math.max(Math.abs(eyeZ - minZ), Math.abs(eyeZ - maxZ));

        // The maximum of f(r, h) = r / (r^2 + h^2) is at r = h, and at the nearest allowed r otherwise.
        double fMax = deltaProjectionFactor(clamp(rMin, hMin, rMax), hMin);
        double fMin = Math.min(deltaProjectionFactor(rMin, hMax), deltaProjectionFactor(rMax, hMax));

        // A viewpoint inside the box makes fMax unbounded, and then nothing can be discarded.
        double deltaLow = Double.isInfinite(fMax) ? 0.0 : kappa / fMax;
        double deltaHigh = kappa == 0.0 ? 0.0 : fMin > 0.0 ? kappa / fMin : Double.POSITIVE_INFINITY;
        return new UncertaintyInterval(deltaLow, deltaHigh);
    }

    /** The function f(r, h) of section [LIND1996].4.2, whose limit at r = h = 0 is unbounded. */
    private static double deltaProjectionFactor(double r, double h) {
        double squaredDistance = r * r + h * h;
        return squaredDistance > 0.0 ? r / squaredDistance : Double.POSITIVE_INFINITY;
    }

    private static double clamp(double minimum, double value, double maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    /**
     * Deltas up to low always coalesce, and deltas above high never do.
     * Between them, Equation [LIND1996](2) decides for each vertex.
     */
    record UncertaintyInterval(double low, double high) {
    }
}
