package planetdemviewer.render.jogl4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.camera.Camera;

class Jogl4TileLabelRendererTest {
    private static Vector3Dd[] square(double x0, double y0, double x1, double y1) {
        return new Vector3Dd[] {
            new Vector3Dd(x0, y0, 0), new Vector3Dd(x1, y0, 0), new Vector3Dd(x1, y1, 0), new Vector3Dd(x0, y1, 0)
        };
    }

    private static Jogl4TileLabelRenderer.NdcPoint center(Vector3Dd[] corners) {
        return Jogl4TileLabelRenderer.visibleViewportCenter(corners, Matrix4x4d.identityMatrix());
    }

    @Test
    void aTileInsideTheViewportIsLabelledAtItsCenter() {
        Jogl4TileLabelRenderer.NdcPoint center = center(square(-0.5, 0.0, 0.5, 0.5));
        assertEquals(0.0, center.x(), 1e-12);
        assertEquals(0.25, center.y(), 1e-12);
    }

    @Test
    void aTileHalfOffScreenIsLabelledAtTheCenterOfItsVisiblePart() {
        Jogl4TileLabelRenderer.NdcPoint center = center(square(0.0, 0.0, 3.0, 3.0));
        assertEquals(0.5, center.x(), 1e-12);
        assertEquals(0.5, center.y(), 1e-12);
    }

    @Test
    void aTileCoveringTheWholeViewportIsLabelledAtTheViewportCenter() {
        Jogl4TileLabelRenderer.NdcPoint center = center(square(-5.0, -5.0, 5.0, 5.0));
        assertEquals(0.0, center.x(), 1e-12);
        assertEquals(0.0, center.y(), 1e-12);
    }

    @Test
    void aTileOutsideTheViewportHasNoLabel() {
        assertNull(center(square(2.0, 2.0, 3.0, 3.0)));
    }

    @Test
    void aTileBehindTheCameraHasNoLabel() {
        Camera camera = new Camera();
        camera.setPosition(new Vector3Dd(0, 0, 5));
        camera.setFocusedPositionDirect(new Vector3Dd(0, 0, 0));
        camera.setUpDirect(new Vector3Dd(0, 1, 0));
        camera.updateVectors();
        camera.updateViewportResize(800, 600);
        Vector3Dd[] behind = {
            new Vector3Dd(-1, -1, 9), new Vector3Dd(1, -1, 9), new Vector3Dd(1, 1, 9), new Vector3Dd(-1, 1, 9)
        };
        assertNull(Jogl4TileLabelRenderer.visibleViewportCenter(behind, camera.calculateProjectionMatrix()));
    }
}
