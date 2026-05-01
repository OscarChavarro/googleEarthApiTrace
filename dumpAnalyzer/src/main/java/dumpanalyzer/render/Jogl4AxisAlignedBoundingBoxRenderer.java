package dumpanalyzer.render;

import java.util.ArrayList;
import java.util.List;

import com.jogamp.opengl.GL2;
import dumpanalyzer.model.AxisAlignedBoundingBox;
import dumpanalyzer.model.DumpAnalyzerModel;
import dumpanalyzer.model.Frame;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.common.linealAlgebra.Vector3D;
import vsdk.toolkit.environment.Camera;

public final class Jogl4AxisAlignedBoundingBoxRenderer {
    public void drawForSelection(
        GL2 gl2,
        Frame frameData,
        int selectedTileIndex,
        Matrix4x4 projection,
        boolean useGoogleCameraView,
        Camera viewingCamera
    ) {
        if (frameData == null) {
            return;
        }
        float[] mvp = projection.exportToFloatArrayColumnOrder();
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glPushMatrix();
        gl2.glLoadMatrixf(mvp, 0);
        List<AxisAlignedBoundingBox> aabbs = frameData.getAxisAlignedBoundingBoxes();
        if (selectedTileIndex == DumpAnalyzerModel.SELECT_ALL_TILES) {
            for (AxisAlignedBoundingBox aabb : aabbs) {
                drawAabb(gl2, aabb, frameData, useGoogleCameraView, viewingCamera);
            }
            gl2.glMatrixMode(GL2.GL_PROJECTION);
            gl2.glPopMatrix();
            gl2.glMatrixMode(GL2.GL_MODELVIEW);
            return;
        }
        if (selectedTileIndex < 0 || selectedTileIndex >= aabbs.size()) {
            gl2.glMatrixMode(GL2.GL_PROJECTION);
            gl2.glPopMatrix();
            gl2.glMatrixMode(GL2.GL_MODELVIEW);
            return;
        }
        drawAabb(gl2, aabbs.get(selectedTileIndex), frameData, useGoogleCameraView, viewingCamera);
        gl2.glMatrixMode(GL2.GL_PROJECTION);
        gl2.glPopMatrix();
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
    }

    public List<Jogl4HudRenderer.ScreenLabel> buildLabelsForSelection(
        Frame frameData,
        int selectedTileIndex,
        Matrix4x4 projection,
        boolean useGoogleCameraView,
        Camera viewingCamera,
        int viewportWidth,
        int viewportHeight
    ) {
        if (frameData == null || viewportWidth <= 0 || viewportHeight <= 0) {
            return List.of();
        }
        List<AxisAlignedBoundingBox> aabbs = frameData.getAxisAlignedBoundingBoxes();
        List<Jogl4HudRenderer.ScreenLabel> labels = new ArrayList<>();
        if (selectedTileIndex == DumpAnalyzerModel.SELECT_ALL_TILES) {
            for (AxisAlignedBoundingBox aabb : aabbs) {
                appendLabel(labels, aabb, frameData, projection, useGoogleCameraView, viewingCamera, viewportWidth, viewportHeight);
            }
            return labels;
        }
        if (selectedTileIndex < 0 || selectedTileIndex >= aabbs.size()) {
            return labels;
        }
        appendLabel(
            labels,
            aabbs.get(selectedTileIndex),
            frameData,
            projection,
            useGoogleCameraView,
            viewingCamera,
            viewportWidth,
            viewportHeight
        );
        return labels;
    }

    private static void drawAabb(GL2 gl2, AxisAlignedBoundingBox aabb, Frame frameData, boolean useGoogleCameraView, Camera viewingCamera) {
        if (aabb == null || aabb.getMin() == null || aabb.getMax() == null) {
            return;
        }
        gl2.glMatrixMode(GL2.GL_MODELVIEW);
        gl2.glPushMatrix();
        double[] modelView = CoordinatesTransforms.geometryModelView(useGoogleCameraView, viewingCamera, frameData);
        if (useGoogleCameraView && aabb.getModelViewMatrix() != null && aabb.getModelViewMatrix().length == 16) {
            modelView = aabb.getModelViewMatrix();
        }
        if (modelView != null && modelView.length == 16) {
            float[] mv = new float[16];
            for (int i = 0; i < 16; i++) {
                mv[i] = (float)modelView[i];
            }
            gl2.glLoadMatrixf(mv, 0);
        }
        else {
            gl2.glLoadIdentity();
        }

        gl2.glDisable(GL2.GL_LIGHTING);
        gl2.glEnable(GL2.GL_DEPTH_TEST);
        gl2.glDepthMask(false);
        gl2.glDepthFunc(GL2.GL_LEQUAL);
        gl2.glColor3d(1.0, 1.0, 0.0);
        gl2.glLineWidth(2.0f);

        Vector3D min = aabb.getMin();
        Vector3D max = aabb.getMax();
        double x0 = min.x();
        double y0 = min.y();
        double z0 = min.z();
        double x1 = max.x();
        double y1 = max.y();
        double z1 = max.z();

        gl2.glBegin(GL2.GL_LINES);
        gl2.glVertex3d(x0, y0, z0); gl2.glVertex3d(x1, y0, z0);
        gl2.glVertex3d(x1, y0, z0); gl2.glVertex3d(x1, y1, z0);
        gl2.glVertex3d(x1, y1, z0); gl2.glVertex3d(x0, y1, z0);
        gl2.glVertex3d(x0, y1, z0); gl2.glVertex3d(x0, y0, z0);
        gl2.glVertex3d(x0, y0, z1); gl2.glVertex3d(x1, y0, z1);
        gl2.glVertex3d(x1, y0, z1); gl2.glVertex3d(x1, y1, z1);
        gl2.glVertex3d(x1, y1, z1); gl2.glVertex3d(x0, y1, z1);
        gl2.glVertex3d(x0, y1, z1); gl2.glVertex3d(x0, y0, z1);
        gl2.glVertex3d(x0, y0, z0); gl2.glVertex3d(x0, y0, z1);
        gl2.glVertex3d(x1, y0, z0); gl2.glVertex3d(x1, y0, z1);
        gl2.glVertex3d(x1, y1, z0); gl2.glVertex3d(x1, y1, z1);
        gl2.glVertex3d(x0, y1, z0); gl2.glVertex3d(x0, y1, z1);
        gl2.glEnd();

        gl2.glDepthMask(true);
        gl2.glDepthFunc(GL2.GL_LESS);
        gl2.glPopMatrix();
    }

    private static void appendLabel(
        List<Jogl4HudRenderer.ScreenLabel> labels,
        AxisAlignedBoundingBox aabb,
        Frame frameData,
        Matrix4x4 projection,
        boolean useGoogleCameraView,
        Camera viewingCamera,
        int viewportWidth,
        int viewportHeight
    ) {
        if (aabb == null || aabb.getMin() == null || aabb.getMax() == null) {
            return;
        }
        Vector3D center = new Vector3D(
            (aabb.getMin().x() + aabb.getMax().x()) * 0.5,
            (aabb.getMin().y() + aabb.getMax().y()) * 0.5,
            (aabb.getMin().z() + aabb.getMax().z()) * 0.5
        );
        double[] modelView = CoordinatesTransforms.geometryModelView(useGoogleCameraView, viewingCamera, frameData);
        if (useGoogleCameraView && aabb.getModelViewMatrix() != null && aabb.getModelViewMatrix().length == 16) {
            modelView = aabb.getModelViewMatrix();
        }
        int[] pixel = projectToViewportPixel(center, modelView, projection, viewportWidth, viewportHeight);
        if (pixel == null) {
            return;
        }
        labels.add(new Jogl4HudRenderer.ScreenLabel(pixel[0], pixel[1], String.valueOf(aabb.getTextureId())));
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
