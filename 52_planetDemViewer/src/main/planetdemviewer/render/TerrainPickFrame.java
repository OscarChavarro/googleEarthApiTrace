package planetdemviewer.render;

import java.util.ArrayList;
import java.util.List;
import planetdemviewer.config.Configuration;
import planetdemviewer.spatial.Ray3;
import planetdemviewer.spatial.WorldPosition;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.environment.geometry.surface.TriangleMesh;

/** Intersects rays, on the CPU, with the actual triangle meshes of the terrain drawn in the last frame. */
public final class TerrainPickFrame implements TerrainRayIntersector {
    private final List<DrawnMesh> drawnMeshes = new ArrayList<>();

    /** Starts a new visible-terrain snapshot. */
    public void begin() {
        drawnMeshes.clear();
    }

    public void meshDrawn(TerrainDrawItem item, TriangleMesh mesh) {
        drawnMeshes.add(new DrawnMesh(item, mesh));
    }

    @Override
    public TerrainPickHit intersect(Ray3 ray) {
        TerrainPickHit closest = null;
        for (DrawnMesh drawn : drawnMeshes) {
            TerrainDrawItem item = drawn.item;
            double[] positions = drawn.mesh.getVertexPositions();
            int[] indices = drawn.mesh.getTriangleIndexes();
            for (int offset = 0; offset < indices.length; offset += 3) {
                WorldPosition a = transformed(positions, indices[offset], item);
                WorldPosition b = transformed(positions, indices[offset + 1], item);
                WorldPosition c = transformed(positions, indices[offset + 2], item);
                double distance = rayTriangleDistance(ray, a, b, c);
                if (Double.isFinite(distance) && (closest == null || distance < closest.distanceMetres())) {
                    WorldPosition world = pointOnRay(ray, distance);
                    double localX = (world.x() - item.offsetX()) / item.relativeScale() + 0.5;
                    double localY = (world.y() - item.offsetY()) / item.relativeScale() + 0.5;
                    double elevationMetres = (world.z() - item.offsetZ())
                        / (item.relativeScale() * item.heightExaggeration())
                        * Configuration.WORLD_WIDTH_METRES;
                    closest = new TerrainPickHit(item.command(), world, distance, localX, localY, elevationMetres);
                }
            }
        }
        return closest;
    }

    private static WorldPosition transformed(double[] positions, int vertex, TerrainDrawItem item) {
        int offset = vertex * 3;
        Vector3Dd result = item.modelMatrix().multiply(new Vector3Dd(
            positions[offset], positions[offset + 1], positions[offset + 2]));
        return new WorldPosition(result.x(), result.y(), result.z());
    }

    private static double rayTriangleDistance(Ray3 ray, WorldPosition a, WorldPosition b, WorldPosition c) {
        WorldPosition edge1 = subtract(b, a);
        WorldPosition edge2 = subtract(c, a);
        WorldPosition p = cross(ray.direction(), edge2);
        double determinant = dot(edge1, p);
        if (Math.abs(determinant) < 1e-12) {
            return Double.NaN;
        }
        double inverse = 1.0 / determinant;
        WorldPosition t = subtract(ray.origin(), a);
        double u = dot(t, p) * inverse;
        if (u < 0.0 || u > 1.0) {
            return Double.NaN;
        }
        WorldPosition q = cross(t, edge1);
        double v = dot(ray.direction(), q) * inverse;
        if (v < 0.0 || u + v > 1.0) {
            return Double.NaN;
        }
        double distance = dot(edge2, q) * inverse;
        return distance >= 0.0 ? distance : Double.NaN;
    }

    private static WorldPosition pointOnRay(Ray3 ray, double distance) {
        return new WorldPosition(ray.origin().x() + ray.direction().x() * distance,
            ray.origin().y() + ray.direction().y() * distance,
            ray.origin().z() + ray.direction().z() * distance);
    }

    private static WorldPosition subtract(WorldPosition first, WorldPosition second) {
        return new WorldPosition(first.x() - second.x(), first.y() - second.y(), first.z() - second.z());
    }

    private static WorldPosition cross(WorldPosition first, WorldPosition second) {
        return new WorldPosition(first.y() * second.z() - first.z() * second.y(),
            first.z() * second.x() - first.x() * second.z(),
            first.x() * second.y() - first.y() * second.x());
    }

    private static double dot(WorldPosition first, WorldPosition second) {
        return first.x() * second.x() + first.y() * second.y() + first.z() * second.z();
    }

    private record DrawnMesh(TerrainDrawItem item, TriangleMesh mesh) {
    }
}
