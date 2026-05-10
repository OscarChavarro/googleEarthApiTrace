package dumpanalyzer.render;

import com.jogamp.opengl.GL2;
import dumpanalyzer.model.DumpAnalyzerModel;
import dumpanalyzer.model.Frame;
import dumpanalyzer.model.TileInstance;
import java.util.List;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.common.linealAlgebra.Vector3D;
import vsdk.toolkit.environment.camera.Camera;

public final class Jogl4NeighborRelationshipRenderer {
    public void drawForSelection(
        GL2 gl2,
        Frame frameData,
        int selectedTileIndex,
        Matrix4x4 projection,
        boolean useGoogleCameraView,
        Camera viewingCamera,
        int viewportWidth,
        int viewportHeight
    ) {
        if (frameData == null || projection == null || viewportWidth <= 0 || viewportHeight <= 0) {
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
        if (selectedTileIndex == DumpAnalyzerModel.SELECT_ALL_TILES) {
            List<TileInstance> selectableTiles = frameData.getSelectableTiles();
            for (int i = 0; i < selectableTiles.size(); i++) {
                drawNeighborsForTile(
                    gl2,
                    selectableTiles,
                    frameData,
                    i,
                    projection,
                    useGoogleCameraView,
                    viewingCamera,
                    viewportWidth,
                    viewportHeight
                );
            }
        }
        else if (selectedTileIndex >= 0 && selectedTileIndex < frameData.getSelectableTiles().size()) {
            drawNeighborsForTile(
                gl2,
                frameData.getSelectableTiles(),
                frameData,
                selectedTileIndex,
                projection,
                useGoogleCameraView,
                viewingCamera,
                viewportWidth,
                viewportHeight
            );
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
        Frame frameData,
        int sourceTileIndex,
        Matrix4x4 projection,
        boolean useGoogleCameraView,
        Camera viewingCamera,
        int viewportWidth,
        int viewportHeight
    ) {
        TileInstance source = tiles.get(sourceTileIndex);
        int[] sourcePixel = centerOfTile(source, frameData, projection, useGoogleCameraView, viewingCamera, viewportWidth, viewportHeight);
        if (sourcePixel == null) {
            return;
        }
        drawDirectedNeighbor(gl2, tiles, frameData, sourcePixel, source.getDetectedNorthNeighborIndex(), sourceTileIndex, projection, useGoogleCameraView, viewingCamera, viewportWidth, viewportHeight, 0.2, 1.0, 0.2);
        drawDirectedNeighbor(gl2, tiles, frameData, sourcePixel, source.getDetectedSouthNeighborIndex(), sourceTileIndex, projection, useGoogleCameraView, viewingCamera, viewportWidth, viewportHeight, 1.0, 0.6, 0.2);
        drawDirectedNeighbor(gl2, tiles, frameData, sourcePixel, source.getDetectedEastNeighborIndex(), sourceTileIndex, projection, useGoogleCameraView, viewingCamera, viewportWidth, viewportHeight, 0.2, 0.8, 1.0);
        drawDirectedNeighbor(gl2, tiles, frameData, sourcePixel, source.getDetectedWestNeighborIndex(), sourceTileIndex, projection, useGoogleCameraView, viewingCamera, viewportWidth, viewportHeight, 1.0, 0.2, 0.8);
    }

    private static void drawDirectedNeighbor(
        GL2 gl2,
        List<TileInstance> tiles,
        Frame frameData,
        int[] sourcePixel,
        int targetTileIndex,
        int sourceIndex,
        Matrix4x4 projection,
        boolean useGoogleCameraView,
        Camera viewingCamera,
        int viewportWidth,
        int viewportHeight,
        double red,
        double green,
        double blue
    ) {
        if (targetTileIndex == TileInstance.NO_NEIGHBOR) {
            return;
        }
        if (targetTileIndex == sourceIndex) {
            return;
        }
        if (targetTileIndex < 0 || targetTileIndex >= tiles.size()) {
            return;
        }
        TileInstance target = tiles.get(targetTileIndex);
        int[] targetPixel = centerOfTile(target, frameData, projection, useGoogleCameraView, viewingCamera, viewportWidth, viewportHeight);
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
        if (!(len > 1e-6)) {
            return;
        }

        double ux = dx / len;
        double uy = dy / len;
        double nx = -uy;
        double ny = ux;
        double curveAmount = Math.max(10.0, Math.min(36.0, len * 0.20));
        double sideSign = 1.0;

        double controlX = (sx + ex) * 0.5 + nx * sideSign * curveAmount;
        double controlY = (sy + ey) * 0.5 + ny * sideSign * curveAmount;

        gl2.glColor3d(red, green, blue);
        drawCurvedBody(gl2, sx, sy, ex, ey, controlX, controlY);
        drawTriangularArrowHead(gl2, ex, ey, controlX, controlY, len);
    }

    private static void drawCurvedBody(
        GL2 gl2,
        double sx,
        double sy,
        double ex,
        double ey,
        double controlX,
        double controlY
    ) {
        gl2.glBegin(GL2.GL_LINE_STRIP);
        for (int i = 0; i <= 18; i++) {
            double t = i / 18.0;
            double omt = 1.0 - t;
            double x = omt * omt * sx + 2.0 * omt * t * controlX + t * t * ex;
            double y = omt * omt * sy + 2.0 * omt * t * controlY + t * t * ey;
            gl2.glVertex2d(x, y);
        }
        gl2.glEnd();
    }

    private static void drawTriangularArrowHead(GL2 gl2, double tipX, double tipY, double controlX, double controlY, double length) {
        double tx = tipX - controlX;
        double ty = tipY - controlY;
        double tlen = Math.sqrt(tx * tx + ty * ty);
        if (!(tlen > 1e-6)) {
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
        Frame frameData,
        Matrix4x4 projection,
        boolean useGoogleCameraView,
        Camera viewingCamera,
        int viewportWidth,
        int viewportHeight
    ) {
        if (tile == null || tile.getMin() == null || tile.getMax() == null) {
            return null;
        }
        Vector3D center = new Vector3D(
            (tile.getMin().x() + tile.getMax().x()) * 0.5,
            (tile.getMin().y() + tile.getMax().y()) * 0.5,
            (tile.getMin().z() + tile.getMax().z()) * 0.5
        );
        double[] modelView = CoordinatesTransforms.geometryModelView(useGoogleCameraView, viewingCamera, frameData);
        if (useGoogleCameraView && tile.getModelViewMatrix() != null && tile.getModelViewMatrix().length == 16) {
            modelView = tile.getModelViewMatrix();
        }
        return projectToViewportPixel(center, modelView, projection, viewportWidth, viewportHeight);
    }

    private static int[] projectToViewportPixel(
        Vector3D p,
        double[] modelView,
        Matrix4x4 projection,
        int viewportWidth,
        int viewportHeight
    ) {
        if (p == null || modelView == null || modelView.length != 16 || projection == null) {
            return null;
        }
        double ex = modelView[0] * p.x() + modelView[4] * p.y() + modelView[8] * p.z() + modelView[12];
        double ey = modelView[1] * p.x() + modelView[5] * p.y() + modelView[9] * p.z() + modelView[13];
        double ez = modelView[2] * p.x() + modelView[6] * p.y() + modelView[10] * p.z() + modelView[14];
        double ew = modelView[3] * p.x() + modelView[7] * p.y() + modelView[11] * p.z() + modelView[15];

        double[] proj = projection.exportToDoubleArrayColumnOrder();
        if (proj == null || proj.length != 16) {
            return null;
        }
        double cx = proj[0] * ex + proj[4] * ey + proj[8] * ez + proj[12] * ew;
        double cy = proj[1] * ex + proj[5] * ey + proj[9] * ez + proj[13] * ew;
        double cw = proj[3] * ex + proj[7] * ey + proj[11] * ez + proj[15] * ew;
        if (Math.abs(cw) < 1e-12) {
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
        return new int[] { px, py };
    }
}
