package planetdemviewer.render;

import planetdemviewer.spatial.Ray3;

/** Finds where a ray meets the visible terrain, whichever backend drew it. */
@FunctionalInterface
public interface TerrainRayIntersector {
    /** Returns the closest hit, or null when the ray misses the terrain. */
    TerrainPickHit intersect(Ray3 ray);
}
