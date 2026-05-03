package pyramidalimagebuilder.render;

import com.jogamp.opengl.GL2;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import pyramidalimagebuilder.model.PyramidalImageModel;
import pyramidalimagebuilder.model.TileInstance;
import pyramidalimagebuilder.model.TileInstance.TriangleStripGeometry;
import pyramidalimagebuilder.model.TileInstance.TriangleStripVertex;

final class Jogl4NeighborhoodRenderer {
    void drawForSelection(GL2 gl2, List<TileInstance> tiles, int selectedTileIndex) {
        if (gl2 == null || tiles == null || tiles.isEmpty() || selectedTileIndex < 0 || selectedTileIndex >= tiles.size()) {
            return;
        }
        Map<Integer, Integer> indexById = new HashMap<>();
        for (int i = 0; i < tiles.size(); i++) {
            indexById.put(tiles.get(i).getTileId(), i);
        }

        TileInstance source = tiles.get(selectedTileIndex);
        double[] c0 = centerOf(source);
        if (c0 == null) {
            return;
        }
        gl2.glDisable(GL2.GL_TEXTURE_2D);
        gl2.glLineWidth(2.0f);
        drawArcToNeighbor(gl2, tiles, indexById, c0, source.getNorthNeighbor(), 0.2, 1.0, 0.2);
        drawArcToNeighbor(gl2, tiles, indexById, c0, source.getSouthNeighbor(), 1.0, 0.6, 0.2);
        drawArcToNeighbor(gl2, tiles, indexById, c0, source.getEastNeighbor(), 0.2, 0.8, 1.0);
        drawArcToNeighbor(gl2, tiles, indexById, c0, source.getWestNeighbor(), 1.0, 0.2, 0.8);
    }

    private static void drawArcToNeighbor(
        GL2 gl2,
        List<TileInstance> tiles,
        Map<Integer, Integer> indexById,
        double[] sourceCenter,
        Integer neighborId,
        double r, double g, double b
    ) {
        if (neighborId == null) {
            return;
        }
        Integer idx = indexById.get(neighborId);
        if (idx == null || idx < 0 || idx >= tiles.size()) {
            return;
        }
        double[] targetCenter = centerOf(tiles.get(idx));
        if (targetCenter == null) {
            return;
        }
        double sx = sourceCenter[0];
        double sy = sourceCenter[1];
        double sz = sourceCenter[2];
        double tx = targetCenter[0];
        double ty = targetCenter[1];
        double tz = targetCenter[2];
        double dx = tx - sx;
        double dy = ty - sy;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1.0e-9) {
            return;
        }
        double nx = -dy / len;
        double ny = dx / len;
        double mx = (sx + tx) * 0.5;
        double my = (sy + ty) * 0.5;
        double mz = (sz + tz) * 0.5;
        double curve = 0.20 * len;
        double cx = mx + nx * curve;
        double cy = my + ny * curve;

        gl2.glColor3d(r, g, b);
        gl2.glBegin(GL2.GL_LINE_STRIP);
        for (int i = 0; i <= 20; i++) {
            double t = i / 20.0;
            double omt = 1.0 - t;
            double x = omt * omt * sx + 2.0 * omt * t * cx + t * t * tx;
            double y = omt * omt * sy + 2.0 * omt * t * cy + t * t * ty;
            gl2.glVertex3d(x, y, mz);
        }
        gl2.glEnd();

        double ux = dx / len;
        double uy = dy / len;
        double ax = tx - ux * 0.10 + nx * 0.05;
        double ay = ty - uy * 0.10 + ny * 0.05;
        double bx = tx - ux * 0.10 - nx * 0.05;
        double by = ty - uy * 0.10 - ny * 0.05;
        gl2.glBegin(GL2.GL_LINES);
        gl2.glVertex3d(tx, ty, tz);
        gl2.glVertex3d(ax, ay, tz);
        gl2.glVertex3d(tx, ty, tz);
        gl2.glVertex3d(bx, by, tz);
        gl2.glEnd();
    }

    private static double[] centerOf(TileInstance tile) {
        TriangleStripGeometry strip = tile == null ? null : tile.getTriangleStrip();
        if (strip == null || strip.vertices() == null || strip.vertices().isEmpty()) {
            return null;
        }
        double sx = 0.0;
        double sy = 0.0;
        double sz = 0.0;
        int n = 0;
        for (TriangleStripVertex v : strip.vertices()) {
            sx += v.x();
            sy += v.y();
            sz += v.z();
            n++;
        }
        if (n <= 0) {
            return null;
        }
        return new double[] { sx / n, sy / n, sz / n };
    }
}
