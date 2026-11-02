package planetdemviewer.render.terrain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.common.linealAlgebra.Vector4Dd;
import vsdk.toolkit.environment.camera.Camera;

class ProjectedDeltaCriterionTest {
    private static Camera camera(Vector3Dd position, Vector3Dd focus, Vector3Dd up, int width, int height) {
        Camera camera = new Camera();
        camera.setPosition(position);
        camera.setFocusedPositionDirect(focus);
        camera.setUpDirect(up);
        camera.updateVectors();
        camera.updateViewportResize(width, height);
        return camera;
    }

    /** The length in pixels of a vertical segment, with the same matrix that renders it. */
    private static double projectedLengthPixels(Camera camera, Vector3Dd low, Vector3Dd high) {
        Matrix4x4d projection = camera.calculateProjectionMatrix();
        Vector4Dd a = projection.multiply(new Vector4Dd(low)).dividedByW();
        Vector4Dd b = projection.multiply(new Vector4Dd(high)).dividedByW();
        double dx = (a.x() - b.x()) * camera.getViewportXSize() * 0.5;
        double dy = (a.y() - b.y()) * camera.getViewportYSize() * 0.5;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** At the center of the view the approximations of section [LIND1996].4.1 do not apply. */
    @Test
    void thresholdMatchesTheRenderingProjectionAtTheCenterOfTheView() {
        Vector3Dd vertex = new Vector3Dd(2.0, 3.0, 0.5);
        Camera camera = camera(new Vector3Dd(2.0, -2.0, 4.0), vertex, new Vector3Dd(0.0, 3.5, 5.0).normalized(), 1600, 900);
        double delta = 1e-3;
        double pixels = projectedLengthPixels(camera,
            new Vector3Dd(vertex.x(), vertex.y(), vertex.z() - delta * 0.5),
            new Vector3Dd(vertex.x(), vertex.y(), vertex.z() + delta * 0.5));
        ProjectedDeltaCriterion above = ProjectedDeltaCriterion.of(camera, pixels * 1.001);
        ProjectedDeltaCriterion below = ProjectedDeltaCriterion.of(camera, pixels * 0.999);
        assertTrue(above.coalesces(delta, vertex.x(), vertex.y(), vertex.z()));
        assertFalse(below.coalesces(delta, vertex.x(), vertex.y(), vertex.z()));
    }

    @Test
    void aSegmentSeenFromAboveProjectsToNothing() {
        ProjectedDeltaCriterion criterion = new ProjectedDeltaCriterion(1.0, 1.0, 5.0, 1e-9);
        assertTrue(criterion.coalesces(100.0, 1.0, 1.0, 0.0));
        assertFalse(criterion.coalesces(100.0, 1.1, 1.0, 0.0));
    }

    @Test
    void anInfiniteDeltaNeverCoalesces() {
        ProjectedDeltaCriterion criterion = new ProjectedDeltaCriterion(1.0, 1.0, 5.0, 1.0);
        assertFalse(criterion.coalesces(Double.POSITIVE_INFINITY, 1.0, 1.0, 0.0));
        assertFalse(criterion.coalesces(Double.POSITIVE_INFINITY, 2.0, 1.0, 0.0));
    }

    @Test
    void aZeroThresholdOnlyCoalescesFlatTriangles() {
        ProjectedDeltaCriterion criterion = new ProjectedDeltaCriterion(4.0, 0.0, 1.0, 0.0);
        assertTrue(criterion.coalesces(0.0, 0.0, 0.0, 0.0));
        assertFalse(criterion.coalesces(1e-12, 0.0, 0.0, 0.0));
        ProjectedDeltaCriterion.UncertaintyInterval interval = criterion.uncertaintyInterval(0, 0, 0, 1, 1, 1);
        assertEquals(0.0, interval.low());
        assertEquals(0.0, interval.high());
    }

    @Test
    void theOrthogonalProjectionHasNoCriterion() {
        Camera camera = camera(new Vector3Dd(0, 0, 5), new Vector3Dd(0, 0, 0), new Vector3Dd(0, 1, 0), 800, 600);
        camera.setProjectionMode(Camera.PROJECTION_MODE_ORTHOGONAL);
        assertNull(ProjectedDeltaCriterion.of(camera, 1.0));
    }

    /** Section [LIND1996].4.2: outside Iu, the interval must give the answer of Equation 2. */
    @Test
    void theUncertaintyIntervalNeverContradictsTheEquation() {
        Random random = new Random(11);
        for (int trial = 0; trial < 2_000; trial++) {
            double[] box = {random.nextDouble(), random.nextDouble(), random.nextDouble()};
            double[] size = {random.nextDouble(), random.nextDouble(), random.nextDouble() * 0.1};
            ProjectedDeltaCriterion criterion = new ProjectedDeltaCriterion(
                random.nextDouble() * 4 - 2, random.nextDouble() * 4 - 2, random.nextDouble() * 4 - 2,
                1e-4 * random.nextDouble());
            ProjectedDeltaCriterion.UncertaintyInterval interval = criterion.uncertaintyInterval(
                box[0], box[1], box[2], box[0] + size[0], box[1] + size[1], box[2] + size[2]);
            assertTrue(interval.low() <= interval.high());
            for (int sample = 0; sample < 50; sample++) {
                double x = box[0] + random.nextDouble() * size[0];
                double y = box[1] + random.nextDouble() * size[1];
                double z = box[2] + random.nextDouble() * size[2];
                if (interval.low() > 0.0) {
                    assertTrue(criterion.coalesces(interval.low() * 0.999, x, y, z));
                }
                if (!Double.isInfinite(interval.high())) {
                    assertFalse(criterion.coalesces(interval.high() * 1.001, x, y, z));
                }
            }
        }
    }

    @Test
    void aViewpointInsideTheBoxLeavesEverythingUncertain() {
        ProjectedDeltaCriterion.UncertaintyInterval interval =
            new ProjectedDeltaCriterion(0.5, 0.5, 0.5, 1e-3).uncertaintyInterval(0, 0, 0, 1, 1, 1);
        assertEquals(0.0, interval.low());
    }
}
