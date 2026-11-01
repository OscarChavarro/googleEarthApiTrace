package planetdemviewer.spatial;

import java.util.Optional;
import planetdemviewer.geo.GeographicPosition;
import planetdemviewer.scene.CameraSnapshot;

/**
 * CPU-canonical coordinate conversion contract. GPU implementations may
 * accelerate intersection, but must preserve these input/output semantics.
 */
public interface CoordinateTransformService {
    Ray3 viewportRay(ViewportPoint point, Viewport viewport, CameraSnapshot camera);

    Optional<WorldPosition> viewportToReferenceSurface(
        ViewportPoint point, Viewport viewport, CameraSnapshot camera);

    GeographicPosition worldToGeographic(WorldPosition world);

    WorldPosition geographicToWorld(GeographicPosition geographic);
}

