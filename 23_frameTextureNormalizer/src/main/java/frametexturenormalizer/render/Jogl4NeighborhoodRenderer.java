package frametexturenormalizer.render;

import com.jogamp.opengl.GL2;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import frametexturenormalizer.model.FrameTextureNormalizerModel;
import frametexturenormalizer.model.TileInstance;
import frametexturenormalizer.model.TileInstance.TriangleStripGeometry;
import frametexturenormalizer.model.TileInstance.TriangleStripVertex;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;

public final class Jogl4NeighborhoodRenderer {
    public void drawForSelection(
        GL2 gl2,
        List<TileInstance> tiles,
        int selectedTileIndex,
        Matrix4x4 projection,
        float[] modelView,
        int viewportWidth,
        int viewportHeight
    ) {
        if (gl2 == null
            || tiles == null
            || tiles.isEmpty()
            || projection == null
            || modelView == null
            || modelView.length != 16
            || viewportWidth <= 0
            || viewportHeight <= 0) {
            return;
        }

        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glPushMatrix();
        gl2.glLoadIdentity();
        gl2.glOrtho(0.0, viewportWidth - 1.0, 0.0, viewportHeight - 1.0, -1.0, 1.0);
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glPushMatrix();
        gl2.glLoadIdentity();

        gl2.glDisable(GL2.GL_LIGHTING);
        gl2.glDisable(GL2.GL_DEPTH_TEST);
        gl2.glDepthMask(false);
        gl2.glLineWidth(2.0f);

        Map<Integer, Integer> indexById = buildIndexById(tiles);
        if (selectedTileIndex == FrameTextureNormalizerModel.SELECT_ALL_TILES) {
            for (int i = 0; i < tiles.size(); i++) {
                drawNeighborsForTile(gl2, tiles, i, indexById, projection, modelView, viewportWidth, viewportHeight);
            }
        }
        else if (selectedTileIndex >= 0 && selectedTileIndex < tiles.size()) {
            drawNeighborsForTile(gl2, tiles, selectedTileIndex, indexById, projection, modelView, viewportWidth, viewportHeight);
        }

        gl2.glDepthMask(true);
        gl2.glPopMatrix();
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glPopMatrix();
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
    }

    private static void drawNeighborsForTile(
        GL2 gl2,
        List<TileInstance> tiles,
        int sourceIndex,
        Map<Integer, Integer> indexById,
        Matrix4x4 projection,
        float[] modelView,
        int viewportWidth,
        int viewportHeight
    ) {
        TileInstance source = tiles.get(sourceIndex);
        int[] sourcePixel = centerOfTile(source, projection, modelView, viewportWidth, viewportHeight);
        if (sourcePixel == null) {
            return;
        }
        drawDirectedNeighbor(gl2, tiles, sourcePixel, source.getNorthNeighbor(), sourceIndex, indexById, projection, modelView, viewportWidth, viewportHeight, 0.2, 1.0, 0.2);
        drawDirectedNeighbor(gl2, tiles, sourcePixel, source.getSouthNeighbor(), sourceIndex, indexById, projection, modelView, viewportWidth, viewportHeight, 1.0, 0.6, 0.2);
        drawDirectedNeighbor(gl2, tiles, sourcePixel, source.getEastNeighbor(), sourceIndex, indexById, projection, modelView, viewportWidth, viewportHeight, 0.2, 0.8, 1.0);
        drawDirectedNeighbor(gl2, tiles, sourcePixel, source.getWestNeighbor(), sourceIndex, indexById, projection, modelView, viewportWidth, viewportHeight, 1.0, 0.2, 0.8);
    }

    private static void drawDirectedNeighbor(
        GL2 gl2,
        List<TileInstance> tiles,
        int[] sourcePixel,
        Integer targetTileId,
        int sourceIndex,
        Map<Integer, Integer> indexById,
        Matrix4x4 projection,
        float[] modelView,
        int viewportWidth,
        int viewportHeight,
        double red,
        double green,
        double blue
    ) {
        if (targetTileId == null) {
            return;
        }
        Integer targetIndex = indexById.get(targetTileId);
        if (targetIndex == null || targetIndex == sourceIndex || targetIndex < 0 || targetIndex >= tiles.size()) {
            return;
        }
        int[] targetPixel = centerOfTile(tiles.get(targetIndex), projection, modelView, viewportWidth, viewportHeight);
        if (targetPixel == null) {
            return;
        }

        double sx = sourcePixel[0];
        double sy = sourcePixel[1];
        double tx = targetPixel[0];
        double ty = targetPixel[1];
        double ex = sx + (tx - sx) * 0.9;
        double ey = sy + (ty - sy) * 0.9;
        double dx = ex - sx;
        double dy = ey - sy;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (!(len > 1.0e-6)) {
            return;
        }

        double ux = dx / len;
        double uy = dy / len;
        double nx = -uy;
        double ny = ux;
        double curveAmount = Math.max(10.0, Math.min(36.0, len * 0.20));
        double controlX = (sx + ex) * 0.5 + nx * curveAmount;
        double controlY = (sy + ey) * 0.5 + ny * curveAmount;

        gl2.glColor3d(red, green, blue);
        drawCurvedBody(gl2, sx, sy, ex, ey, controlX, controlY);
        drawTriangularArrowHead(gl2, ex, ey, controlX, controlY, len);
    }

    private static Map<Integer, Integer> buildIndexById(List<TileInstance> tiles) {
        Map<Integer, Integer> out = new HashMap<>();
        for (int i = 0; i < tiles.size(); i++) {
            TileInstance tile = tiles.get(i);
            if (tile != null) {
                out.put(tile.getTileId(), i);
            }
        }
        return out;
    }

    private static void drawCurvedBody(GL2 gl2, double sx, double sy, double ex, double ey, double cx, double cy) {
        gl2.glBegin(GL2.GL_LINE_STRIP);
        for (int i = 0; i <= 18; i++) {
            double t = i / 18.0;
            double omt = 1.0 - t;
            double x = omt * omt * sx + 2.0 * omt * t * cx + t * t * ex;
            double y = omt * omt * sy + 2.0 * omt * t * cy + t * t * ey;
            gl2.glVertex2d(x, y);
        }
        gl2.glEnd();
    }

    private static void drawTriangularArrowHead(GL2 gl2, double tipX, double tipY, double controlX, double controlY, double length) {
        double tx = tipX - controlX;
        double ty = tipY - controlY;
        double tlen = Math.sqrt(tx * tx + ty * ty);
        if (!(tlen > 1.0e-6)) {
            return;
        }
        tx /= tlen;
        ty /= tlen;
        double nx = -ty;
        double ny = tx;

        double headLen = Math.max(8.0, Math.min(18.0, length * 0.15));
        double halfWidth = headLen * 0.45;
        double baseX = tipX - tx * headLen;
        double baseY = tipY - ty * headLen;
        double leftX = baseX + nx * halfWidth;
        double leftY = baseY + ny * halfWidth;
        double rightX = baseX - nx * halfWidth;
        double rightY = baseY - ny * halfWidth;

        gl2.glBegin(GL2.GL_LINES);
        gl2.glVertex2d(tipX, tipY);
        gl2.glVertex2d(leftX, leftY);
        gl2.glVertex2d(tipX, tipY);
        gl2.glVertex2d(rightX, rightY);
        gl2.glEnd();
    }

    private static int[] centerOfTile(
        TileInstance tile,
        Matrix4x4 projection,
        float[] modelView,
        int viewportWidth,
        int viewportHeight
    ) {
        double[] center = centerOf(tile);
        if (center == null) {
            return null;
        }
        float[] tileModelView = toFloat16(tile == null ? null : tile.getModelViewMatrix());
        if (tileModelView == null) {
            tileModelView = modelView;
        }
        return projectToViewportPixel(center[0], center[1], center[2], tileModelView, projection, viewportWidth, viewportHeight);
    }

    private static int[] projectToViewportPixel(
        double x,
        double y,
        double z,
        float[] modelView,
        Matrix4x4 projection,
        int viewportWidth,
        int viewportHeight
    ) {
        double ex = modelView[0] * x + modelView[4] * y + modelView[8] * z + modelView[12];
        double ey = modelView[1] * x + modelView[5] * y + modelView[9] * z + modelView[13];
        double ez = modelView[2] * x + modelView[6] * y + modelView[10] * z + modelView[14];
        double ew = modelView[3] * x + modelView[7] * y + modelView[11] * z + modelView[15];

        double[] proj = projection.exportToDoubleArrayColumnOrder();
        if (proj == null || proj.length != 16) {
            return null;
        }
        double cx = proj[0] * ex + proj[4] * ey + proj[8] * ez + proj[12] * ew;
        double cy = proj[1] * ex + proj[5] * ey + proj[9] * ez + proj[13] * ew;
        double cw = proj[3] * ex + proj[7] * ey + proj[11] * ez + proj[15] * ew;
        if (Math.abs(cw) < 1.0e-12) {
            return null;
        }
        double ndcX = cx / cw;
        double ndcY = cy / cw;
        if (!Double.isFinite(ndcX) || !Double.isFinite(ndcY)) {
            return null;
        }
        int px = (int)Math.round((ndcX * 0.5 + 0.5) * (viewportWidth - 1));
        int py = (int)Math.round((ndcY * 0.5 + 0.5) * (viewportHeight - 1));
        if (px < 0 || px >= viewportWidth || py < 0 || py >= viewportHeight) {
            return null;
        }
        return new int[] {px, py};
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
            if (v == null) {
                continue;
            }
            sx += v.x();
            sy += v.y();
            sz += v.z();
            n++;
        }
        if (n <= 0) {
            return null;
        }
        return new double[] {sx / n, sy / n, sz / n};
    }

    private static float[] toFloat16(double[] values) {
        if (values == null || values.length != 16) {
            return null;
        }
        float[] out = new float[16];
        for (int i = 0; i < 16; i++) {
            out[i] = (float)values[i];
        }
        return out;
    }
}
