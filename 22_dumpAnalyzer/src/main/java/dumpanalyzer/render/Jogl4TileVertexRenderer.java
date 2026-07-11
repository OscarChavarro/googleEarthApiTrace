package dumpanalyzer.render;

import java.util.List;

import com.jogamp.opengl.GL2;

import dumpanalyzer.model.TileInstance;
import dumpanalyzer.processing.TriangleMeshVertexComparator;
import dumpanalyzer.processing.TriangleStripTileClassifier;
import dumpanalyzer.processing.TriangleStripTileTopology;
import vsdk.toolkit.common.linealAlgebra.Vector3Dd;

final class Jogl4TileVertexRenderer {
    private static final TriangleStripTileClassifier TRIANGLE_STRIP_CLASSIFIER = new TriangleStripTileClassifier();
    private static final double[][] ORDERED_COLORS = {
        {1.0, 0.0, 0.0},   // #ff0000
        {0.0, 1.0, 0.0},   // #00ff00
        {0.0, 0.0, 1.0},   // #0000ff
        {1.0, 1.0, 0.0},   // #ffff00
        {1.0, 0.0, 1.0},   // #ff00ff
        {0.0, 1.0, 1.0},   // #00ffff
        {1.0, 0.5019607843137255, 0.5019607843137255}, // #ff8080
        {0.5019607843137255, 1.0, 0.5019607843137255}  // #80ff80
    };

    private Jogl4TileVertexRenderer() {
    }

    static double[] colorForIndex(int index) {
        return ORDERED_COLORS[Math.floorMod(index, ORDERED_COLORS.length)];
    }

    static void drawTilePoints(GL2 gl2, TileInstance tile, boolean singleTileMode) {
        if (!singleTileMode) {
            drawAllPoints(gl2, tile);
            return;
        }
        drawSingleTilePoints(gl2, tile);
    }

    private static void drawAllPoints(GL2 gl2, TileInstance tile) {
        gl2.glColor3d(1.0, 0.0, 0.0);
        for (List<Vector3Dd> strip : tile.getStrips()) {
            gl2.glBegin(GL2.GL_POINTS);
            for (Vector3Dd p : strip) {
                gl2.glVertex3d(p.x(), p.y(), p.z());
            }
            gl2.glEnd();
        }
    }

    private static void drawSingleTilePoints(GL2 gl2, TileInstance tile) {
        TileInstance.TriangleStripGeometry triangleStrip = tile.getTriangleStrip();
        if (triangleStrip == null
            || triangleStrip.vertexCount() != TriangleStripTileClassifier.TRIANGLE_STRIP_VERTEX_COUNT) {
            drawAllInColor(gl2, tile.getPoints(), 1.0, 1.0, 0.0);
            return;
        }

        TriangleStripTileTopology topology = TRIANGLE_STRIP_CLASSIFIER.classify(triangleStrip);
        if (topology != TriangleStripTileTopology.DEDUPLICATED_9_VERTICES_QUAD
            && topology != TriangleStripTileTopology.DEDUPLICATED_7_VERTICES_NORTH_POLE_TRIANGLE) {
            drawAllInColor(gl2, tile.getPoints(), 0.0, 0.0, 1.0);
            return;
        }
        List<TileInstance.TriangleStripVertex> unique = TRIANGLE_STRIP_CLASSIFIER.deduplicateVertices(
            triangleStrip.vertices(),
            TriangleMeshVertexComparator.VERTEX_EPSILON
        );

        gl2.glBegin(GL2.GL_POINTS);
        for (int i = 0; i < unique.size(); i++) {
            double[] c = ORDERED_COLORS[i % ORDERED_COLORS.length];
            gl2.glColor3d(c[0], c[1], c[2]);
            TileInstance.TriangleStripVertex v = unique.get(i);
            gl2.glVertex3d(v.x(), v.y(), v.z());
        }
        gl2.glEnd();
    }

    private static void drawAllInColor(GL2 gl2, List<Vector3Dd> points, double r, double g, double b) {
        gl2.glColor3d(r, g, b);
        gl2.glBegin(GL2.GL_POINTS);
        for (Vector3Dd p : points) {
            gl2.glVertex3d(p.x(), p.y(), p.z());
        }
        gl2.glEnd();
    }

}
