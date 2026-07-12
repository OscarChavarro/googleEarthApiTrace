package planetviewer.processing;

import java.util.ArrayList;
import java.util.List;
import planetviewer.config.Configuration;
import planetviewer.model.PyramidalImageInstance;
import planetviewer.model.QuadtreeNode;
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
        List<DrawCommand> out = new ArrayList<>();
        if (instance == null || instance.getImage() == null || !instance.isVisible()) {
            return out;
        }
        visit(instance.getImage().getRoot(), instance, camera, relativeScale, out);
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

    private static void visit(QuadtreeNode node, PyramidalImageInstance instance, Camera camera, double relativeScale, List<DrawCommand> out) {
        Vector3Dd[] corners = worldCorners(node, instance, relativeScale);
        if (!camera.boundingConvexPolyhedraIsVisible(corners)) {
            return;
        }
        if (node.hasChildren() && projectedArea(camera, corners) > Configuration.SCREEN_AREA_SUBDIVISION_THRESHOLD) {
            QuadtreeNode[] children = node.getChildren();
            for (int digit = 0; digit < 4; digit++) {
                QuadtreeNode child = children[digit];
                if (child != null) {
                    visit(child, instance, camera, relativeScale, out);
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

    /** Fraction of the viewport covered by the quad's projected area. */
    private static double projectedArea(Camera camera, Vector3Dd[] corners) {
        camera.updateVectors();
        Matrix4x4d canonicalProjection = new Matrix4x4d().canonicalPerspectiveProjection();
        Matrix4x4d normalizing = camera.getNormalizingTransformation();

        Vector3Dd[] projected = new Vector3Dd[4];
        for (int i = 0; i < 4; i++) {
            Vector3Dd normalized = normalizing.multiply(corners[i]);
            Vector4Dd projectedHomogeneous = canonicalProjection.multiply(new Vector4Dd(normalized)).dividedByW();
            projected[i] = new Vector3Dd(projectedHomogeneous.x(), projectedHomogeneous.y(), 0.0);
        }

        Vector3Dd a = projected[1].subtract(projected[0]);
        Vector3Dd b = projected[3].subtract(projected[0]);
        Vector3Dd c = projected[3].subtract(projected[2]);
        return a.crossProduct(b).length() / 2.0 + b.crossProduct(c).length() / 2.0;
    }
}
