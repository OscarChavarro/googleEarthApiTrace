package planetdemviewer.spatial;

import java.util.Optional;
import planetdemviewer.config.Configuration;
import planetdemviewer.geo.GeographicCoordinate;
import planetdemviewer.geo.GeographicPosition;
import planetdemviewer.scene.CameraSnapshot;
import planetdemviewer.scene.ProjectionType;

/**
 * Reference implementation for the current normalized equirectangular plane.
 * The reference surface is z=0; terrain BLAS/TLAS intersection will later
 * replace only viewportToReferenceSurface.
 */
public final class PlanarCoordinateTransformService implements CoordinateTransformService {
    @Override
    public Ray3 viewportRay(ViewportPoint point, Viewport viewport, CameraSnapshot camera) {
        double x = ((point.u() + 0.5) / viewport.width()) * 2.0 - 1.0;
        double y = 1.0 - ((point.v() + 0.5) / viewport.height()) * 2.0;
        WorldPosition forward = normalize(subtract(camera.focus(), camera.position()));
        WorldPosition right = normalize(cross(forward, camera.up()));
        WorldPosition up = normalize(cross(right, forward));
        if (camera.projectionType() == ProjectionType.ORTHOGRAPHIC) {
            double halfHeight = camera.orthogonalZoom();
            double halfWidth = halfHeight * viewport.width() / viewport.height();
            WorldPosition origin = add(camera.position(),
                add(scale(right, x * halfWidth), scale(up, y * halfHeight)));
            return new Ray3(origin, forward);
        }
        double halfFovRadians = Math.toRadians(camera.fieldOfViewDegrees() * 0.5);
        double tangent = Math.tan(halfFovRadians);
        double aspect = (double) viewport.width() / viewport.height();
        return new Ray3(camera.position(), add(forward,
            add(scale(right, x * aspect * tangent), scale(up, y * tangent))));
    }

    @Override
    public Optional<WorldPosition> viewportToReferenceSurface(
        ViewportPoint point, Viewport viewport, CameraSnapshot camera
    ) {
        Ray3 ray = viewportRay(point, viewport, camera);
        if (Math.abs(ray.direction().z()) < 1e-12) {
            return Optional.empty();
        }
        double distance = -ray.origin().z() / ray.direction().z();
        if (distance < 0.0) {
            return Optional.empty();
        }
        return Optional.of(add(ray.origin(), scale(ray.direction(), distance)));
    }

    @Override
    public GeographicPosition worldToGeographic(WorldPosition world) {
        return new GeographicPosition(
            new GeographicCoordinate(world.x() * 360.0, world.y() * 180.0),
            world.z() * Configuration.WORLD_WIDTH_METRES
        );
    }

    @Override
    public WorldPosition geographicToWorld(GeographicPosition geographic) {
        return new WorldPosition(
            geographic.horizontal().longitudeDegrees() / 360.0,
            geographic.horizontal().latitudeDegrees() / 180.0,
            geographic.heightMetres() / Configuration.WORLD_WIDTH_METRES
        );
    }

    private static WorldPosition add(WorldPosition first, WorldPosition second) {
        return new WorldPosition(first.x() + second.x(), first.y() + second.y(), first.z() + second.z());
    }

    private static WorldPosition subtract(WorldPosition first, WorldPosition second) {
        return new WorldPosition(first.x() - second.x(), first.y() - second.y(), first.z() - second.z());
    }

    private static WorldPosition scale(WorldPosition value, double factor) {
        return new WorldPosition(value.x() * factor, value.y() * factor, value.z() * factor);
    }

    private static WorldPosition cross(WorldPosition first, WorldPosition second) {
        return new WorldPosition(
            first.y() * second.z() - first.z() * second.y(),
            first.z() * second.x() - first.x() * second.z(),
            first.x() * second.y() - first.y() * second.x()
        );
    }

    private static WorldPosition normalize(WorldPosition value) {
        double length = Math.sqrt(value.x() * value.x() + value.y() * value.y() + value.z() * value.z());
        if (!(length > 0.0)) {
            throw new IllegalArgumentException("Camera vectors cannot be zero");
        }
        return scale(value, 1.0 / length);
    }
}
