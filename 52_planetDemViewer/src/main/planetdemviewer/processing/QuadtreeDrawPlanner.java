package planetdemviewer.processing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import planetdemviewer.config.Configuration;
import planetdemviewer.io.elevation.ElevationTileSource;
import planetdemviewer.model.DemTile;
import planetdemviewer.model.PyramidalImageInstance;
import planetdemviewer.model.QuadtreeNode;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4d;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;
import vsdk.toolkit.common.linealAlgebra.Vector4Dd;
import vsdk.toolkit.environment.camera.Camera;

/**
 * GL-independent quadtree traversal: given a camera and a placed pyramidal
 * image instance, decides which nodes to draw. Ported from the old
 * prototype's JoglCachedPyramidalImageRenderer#drawCuadtreeNode: frustum
 * culling, then a projected-screen-area LOD test (recurse into children
 * once the node's projected quad covers more than
 * Configuration.SCREEN_AREA_SUBDIVISION_THRESHOLD of the viewport). Missing
 * children (sparse quadtree) are represented as a synthesized, untethered
 * QuadtreeNode with no tile file of its own, so the renderer falls back to
 * the nearest loaded ancestor's texture for that quadrant.
 */
public final class QuadtreeDrawPlanner {
    private QuadtreeDrawPlanner() {
    }

    public static List<DrawCommand> select(PyramidalImageInstance instance, Camera camera, double relativeScale) {
        return select(instance, camera, relativeScale, new LodState());
    }

    /** Dynamic image LOD with per-tile hysteresis. */
    public static List<DrawCommand> select(PyramidalImageInstance instance, Camera camera,
                                           double relativeScale, LodState lodState) {
        List<DrawCommand> out = new ArrayList<>();
        if (instance == null || instance.getImage() == null || !instance.isVisible()) {
            return out;
        }
        visit(instance.getImage().getRoot(), instance, camera, relativeScale,
            BoundsProvider.flat(), lodState == null ? new LodState() : lodState, out);
        return out;
    }

    /** Dynamic terrain LOD using a true height AABB when the tile is available. */
    public static List<DrawCommand> selectTerrain(PyramidalImageInstance instance, Camera camera,
                                                   double relativeScale, double heightExaggeration,
                                                   ElevationTileSource loader, LodState lodState) {
        List<DrawCommand> out = new ArrayList<>();
        if (instance == null || instance.getImage() == null || !instance.isVisible()) {
            return out;
        }
        visit(instance.getImage().getRoot(), instance, camera, relativeScale,
            BoundsProvider.terrain(loader, heightExaggeration), lodState == null ? new LodState() : lodState, out);
        return out;
    }

    /**
     * Uses the finest level chosen by normal camera-based LOD, but forces every
     * visible branch to that same depth.  A branch that has not yet exposed
     * real descendants remains at its current tile, avoiding a synthetic
     * image fallback in place of terrain geometry.
     */
    public static List<DrawCommand> selectUniformLod(PyramidalImageInstance instance, Camera camera,
                                                       double relativeScale) {
        List<DrawCommand> dynamicSelection = select(instance, camera, relativeScale);
        if (dynamicSelection.isEmpty()) {
            return dynamicSelection;
        }
        int targetDepth = dynamicSelection.stream()
            .mapToInt(command -> command.node().getDepth())
            .max()
            .orElse(0);
        List<DrawCommand> out = new ArrayList<>();
        visitAtDepth(instance.getImage().getRoot(), instance, camera, relativeScale, targetDepth, out);
        return out;
    }

    /**
     * Unconditionally recurses to every leaf tile, ignoring camera
     * culling/LOD. Used by the offline snapshot mode, where the whole
     * pyramid must be visible regardless of viewport framing.
     */
    public static List<DrawCommand> selectAllLeaves(PyramidalImageInstance instance, double relativeScale) {
        List<DrawCommand> out = new ArrayList<>();
        if (instance == null || instance.getImage() == null || !instance.isVisible()) {
            return out;
        }
        visitAll(instance.getImage().getRoot(), instance, relativeScale, out);
        return out;
    }

    private static void visitAll(QuadtreeNode node, PyramidalImageInstance instance, double relativeScale, List<DrawCommand> out) {
        if (node.hasChildren()) {
            QuadtreeNode[] children = node.getChildren();
            for (int digit = 0; digit < 4; digit++) {
                QuadtreeNode child = children[digit];
                if (child != null) {
                    visitAll(child, instance, relativeScale, out);
                }
                else {
                    QuadtreeNode virtualChild = new QuadtreeNode(node.getId() + digit, node, null);
                    out.add(new DrawCommand(virtualChild, worldCorners(virtualChild, instance, relativeScale)));
                }
            }
            return;
        }
        out.add(new DrawCommand(node, worldCorners(node, instance, relativeScale)));
    }

    private static void visitAtDepth(QuadtreeNode node, PyramidalImageInstance instance, Camera camera,
                                     double relativeScale, int targetDepth, List<DrawCommand> out) {
        Vector3Dd[] corners = worldCorners(node, instance, relativeScale);
        if (!camera.boundingConvexPolyhedraIsVisible(corners)) {
            return;
        }
        if (node.getDepth() >= targetDepth || !node.hasChildren()) {
            out.add(new DrawCommand(node, corners));
            return;
        }
        QuadtreeNode[] children = node.getChildren();
        for (int digit = 0; digit < 4; digit++) {
            QuadtreeNode child = children[digit];
            if (child != null) {
                visitAtDepth(child, instance, camera, relativeScale, targetDepth, out);
            }
        }
    }

    private static void visit(QuadtreeNode node, PyramidalImageInstance instance, Camera camera,
                              double relativeScale, BoundsProvider boundsProvider, LodState lodState,
                              List<DrawCommand> out) {
        Vector3Dd[] corners = worldCorners(node, instance, relativeScale);
        WorldBounds bounds = boundsProvider.bounds(node, instance, relativeScale);
        if (!camera.boundingConvexPolyhedraIsVisible(bounds.corners)) {
            return;
        }
        double coverage = projectedCoverage(camera, bounds.corners);
        if (node.hasChildren() && lodState.shouldRefine(node, coverage)) {
            QuadtreeNode[] children = node.getChildren();
            for (int digit = 0; digit < 4; digit++) {
                QuadtreeNode child = children[digit];
                if (child != null) {
                    visit(child, instance, camera, relativeScale, boundsProvider, lodState, out);
                }
                else {
                    QuadtreeNode virtualChild = new QuadtreeNode(node.getId() + digit, node, null);
                    out.add(new DrawCommand(virtualChild, worldCorners(virtualChild, instance, relativeScale)));
                }
            }
            return;
        }
        out.add(new DrawCommand(node, corners));
    }

    private static Vector3Dd[] worldCorners(QuadtreeNode node, PyramidalImageInstance instance, double relativeScale) {
        double x0 = (node.getX0() - 0.5) * relativeScale + instance.getOffsetX();
        double x1 = (node.getX1() - 0.5) * relativeScale + instance.getOffsetX();
        double y0 = (node.getY0() - 0.5) * relativeScale + instance.getOffsetY();
        double y1 = (node.getY1() - 0.5) * relativeScale + instance.getOffsetY();
        double z = instance.getZOffset() * 1e-4;
        return new Vector3Dd[] {
            new Vector3Dd(x0, y0, z),
            new Vector3Dd(x1, y0, z),
            new Vector3Dd(x1, y1, z),
            new Vector3Dd(x0, y1, z),
        };
    }

    /**
     * Conservative screen coverage of an AABB.  A box touching the eye/near
     * plane refines rather than dividing by an unstable homogeneous W.
     */
    public static double projectedCoverage(Camera camera, Vector3Dd[] corners) {
        Matrix4x4d projection = camera.calculateProjectionMatrix();
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        boolean anyInFront = false;
        for (Vector3Dd corner : corners) {
            Vector4Dd clip = projection.multiply(new Vector4Dd(corner));
            if (clip.w() <= 1e-7) {
                continue;
            }
            anyInFront = true;
            double x = clip.x() / clip.w();
            double y = clip.y() / clip.w();
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        if (!anyInFront) {
            return 0.0;
        }
        // A mixture of points behind and before the eye intersects the
        // clipping boundary.  Treat it as large to avoid an abrupt collapse.
        for (Vector3Dd corner : corners) {
            if (projection.multiply(new Vector4Dd(corner)).w() <= 1e-7) {
                return Double.POSITIVE_INFINITY;
            }
        }
        return Math.max(0.0, (maxX - minX) * (maxY - minY) * 0.25);
    }

    /** Stateful hysteresis for one renderer/view; a node is any key that identifies a block. */
    public static final class LodState {
        private static final int MAX_ENTRIES = 8_192;
        private final Map<Object, Boolean> refined = new LinkedHashMap<>(256, 0.75f, true);

        public synchronized boolean shouldRefine(Object node, double coverage) {
            boolean wasRefined = Boolean.TRUE.equals(refined.get(node));
            boolean result = wasRefined
                ? coverage > Configuration.SCREEN_AREA_COARSEN_THRESHOLD
                : coverage > Configuration.SCREEN_AREA_REFINE_THRESHOLD;
            refined.put(node, result);
            if (refined.size() > MAX_ENTRIES) {
                refined.remove(refined.keySet().iterator().next());
            }
            return result;
        }
    }

    private interface BoundsProvider {
        WorldBounds bounds(QuadtreeNode node, PyramidalImageInstance instance, double relativeScale);

        static BoundsProvider flat() {
            return (node, instance, scale) -> worldBounds(node, instance, scale,
                instance.getZOffset() * 1e-4, instance.getZOffset() * 1e-4);
        }

        static BoundsProvider terrain(ElevationTileSource loader, double heightExaggeration) {
            return (node, instance, scale) -> {
                QuadtreeNode elevationOwner = node.nearestSelfOrAncestorWithTile();
                DemTile tile = loader == null || elevationOwner == null ? null
                    : loader.peekElevation(elevationOwner.getTileFile());
                double minMetres = Configuration.MINIMUM_ELEVATION_METRES;
                double maxMetres = Configuration.MAXIMUM_ELEVATION_METRES;
                if (tile != null) {
                    DemTile.CanonicalElevationRange range = tile.canonicalCoreElevationRange();
                    minMetres = range.minimumMetres();
                    maxMetres = range.maximumMetres();
                }
                double baseZ = instance.getZOffset() * 1e-4;
                return worldBounds(node, instance, scale,
                    baseZ + minMetres / Configuration.WORLD_WIDTH_METRES * scale * heightExaggeration,
                    baseZ + maxMetres / Configuration.WORLD_WIDTH_METRES * scale * heightExaggeration);
            };
        }
    }

    private static WorldBounds worldBounds(QuadtreeNode node, PyramidalImageInstance instance,
                                           double relativeScale, double minZ, double maxZ) {
        double x0 = (node.getX0() - 0.5) * relativeScale + instance.getOffsetX();
        double x1 = (node.getX1() - 0.5) * relativeScale + instance.getOffsetX();
        double y0 = (node.getY0() - 0.5) * relativeScale + instance.getOffsetY();
        double y1 = (node.getY1() - 0.5) * relativeScale + instance.getOffsetY();
        return new WorldBounds(new Vector3Dd[] {
            new Vector3Dd(x0, y0, minZ), new Vector3Dd(x1, y0, minZ),
            new Vector3Dd(x1, y1, minZ), new Vector3Dd(x0, y1, minZ),
            new Vector3Dd(x0, y0, maxZ), new Vector3Dd(x1, y0, maxZ),
            new Vector3Dd(x1, y1, maxZ), new Vector3Dd(x0, y1, maxZ)
        });
    }

    private record WorldBounds(Vector3Dd[] corners) {
    }
}
