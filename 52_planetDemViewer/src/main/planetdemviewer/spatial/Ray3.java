package planetdemviewer.spatial;

/** Immutable world-space ray with a normalized direction. */
public record Ray3(WorldPosition origin, WorldPosition direction) {
    public Ray3 {
        if (origin == null || direction == null) {
            throw new IllegalArgumentException("Ray origin and direction are required");
        }
        double length = Math.sqrt(direction.x() * direction.x()
            + direction.y() * direction.y() + direction.z() * direction.z());
        if (!(length > 0.0)) {
            throw new IllegalArgumentException("Ray direction cannot be zero");
        }
        direction = new WorldPosition(direction.x() / length, direction.y() / length, direction.z() / length);
    }
}

