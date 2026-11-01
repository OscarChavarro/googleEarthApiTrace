package planetdemviewer.spatial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import planetdemviewer.geo.GeographicCoordinate;
import planetdemviewer.geo.GeographicPosition;
import planetdemviewer.scene.CameraSnapshot;
import planetdemviewer.scene.ProjectionType;
import org.junit.jupiter.api.Test;

class PlanarCoordinateTransformServiceTest {
    private final PlanarCoordinateTransformService transforms = new PlanarCoordinateTransformService();
    private final CameraSnapshot camera = new CameraSnapshot(
        new WorldPosition(0.0, 0.0, 5.0), new WorldPosition(0.0, 0.0, 0.0),
        new WorldPosition(0.0, 1.0, 0.0), 60.0, 0.1, 1_000.0,
        ProjectionType.PERSPECTIVE, 1.0, 800.0, 600.0, 1L);
    private final Viewport viewport = new Viewport(800, 600);

    @Test
    void centerViewportRayIntersectsThePlanarReferenceOrigin() {
        WorldPosition hit = transforms.viewportToReferenceSurface(
            new ViewportPoint(399.5, 299.5), viewport, camera).orElseThrow();
        assertEquals(0.0, hit.x(), 1e-12);
        assertEquals(0.0, hit.y(), 1e-12);
        assertEquals(0.0, hit.z(), 1e-12);
    }

    @Test
    void convertsTheCurrentNormalizedPlaneToGeographicCoordinates() {
        GeographicPosition geographic = new GeographicPosition(new GeographicCoordinate(-74.0817, 4.6097), 2_640.0);
        GeographicPosition roundTrip = transforms.worldToGeographic(transforms.geographicToWorld(geographic));
        assertEquals(geographic.horizontal().longitudeDegrees(), roundTrip.horizontal().longitudeDegrees(), 1e-12);
        assertEquals(geographic.horizontal().latitudeDegrees(), roundTrip.horizontal().latitudeDegrees(), 1e-12);
        assertEquals(geographic.heightMetres(), roundTrip.heightMetres(), 1e-9);
    }
}
