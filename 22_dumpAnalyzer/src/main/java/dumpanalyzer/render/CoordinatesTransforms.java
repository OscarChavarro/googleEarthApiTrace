package dumpanalyzer.render;

import dumpanalyzer.model.Frame;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.common.linealAlgebra.Vector3D;
import vsdk.toolkit.environment.camera.Camera;

final class CoordinatesTransforms {
    private CoordinatesTransforms() {
    }

    static Matrix4x4 projectionForCamera(Camera camera) {
        if (camera == null) {
            return Matrix4x4.identityMatrix();
        }
        return camera.calculateViewVolumeMatrix();
    }

    static double[] geometryModelView(
        boolean useGoogleCameraView,
        Camera viewingCamera,
        Frame frame
    ) {
        double[] frameModelView = frame == null ? null : frame.getModelViewMatrix();
        if (useGoogleCameraView) {
            return frameModelView;
        }
        if (viewingCamera == null) {
            return frameModelView;
        }
        double[] viewingModelView = viewingCamera
            .calculateTransformationMatrix()
            .exportToDoubleArrayColumnOrder();
        return multiplyColumnMajor(viewingModelView, frameModelView);
    }

    static Matrix4x4 helperProjectionForGizmos(
        Matrix4x4 baseProjection,
        boolean useGoogleCameraView,
        Camera viewingCamera,
        Frame frame
    ) {
        Matrix4x4 p = baseProjection == null ? Matrix4x4.identityMatrix() : baseProjection;
        if (useGoogleCameraView || viewingCamera == null) {
            return p;
        }
        Matrix4x4 viewing = viewingCamera.calculateTransformationMatrix();
        Matrix4x4 out = p.multiply(viewing);
        Matrix4x4 frameMv = fromColumnMajorArray(frame == null ? null : frame.getModelViewMatrix());
        if (frameMv != null) {
            out = out.multiply(frameMv);
        }
        return out;
    }

    static Matrix4x4 fromColumnMajorArray(double[] m) {
        if (m == null || m.length != 16) {
            return null;
        }
        Matrix4x4 out = new Matrix4x4();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                out = out.withVal(row, col, m[col * 4 + row]);
            }
        }
        return out;
    }

    static Vector3D[] transformAabb(Vector3D min, Vector3D max, double[] modelViewMatrix) {
        if (min == null || max == null || modelViewMatrix == null || modelViewMatrix.length != 16) {
            return new Vector3D[] { min, max };
        }
        Vector3D[] corners = new Vector3D[] {
            new Vector3D(min.x(), min.y(), min.z()),
            new Vector3D(max.x(), min.y(), min.z()),
            new Vector3D(min.x(), max.y(), min.z()),
            new Vector3D(max.x(), max.y(), min.z()),
            new Vector3D(min.x(), min.y(), max.z()),
            new Vector3D(max.x(), min.y(), max.z()),
            new Vector3D(min.x(), max.y(), max.z()),
            new Vector3D(max.x(), max.y(), max.z())
        };
        Vector3D t0 = transformPoint(modelViewMatrix, corners[0]);
        double minX = t0.x(), minY = t0.y(), minZ = t0.z();
        double maxX = t0.x(), maxY = t0.y(), maxZ = t0.z();
        for (int i = 1; i < corners.length; i++) {
            Vector3D t = transformPoint(modelViewMatrix, corners[i]);
            minX = Math.min(minX, t.x());
            minY = Math.min(minY, t.y());
            minZ = Math.min(minZ, t.z());
            maxX = Math.max(maxX, t.x());
            maxY = Math.max(maxY, t.y());
            maxZ = Math.max(maxZ, t.z());
        }
        return new Vector3D[] { new Vector3D(minX, minY, minZ), new Vector3D(maxX, maxY, maxZ) };
    }

    private static Vector3D transformPoint(double[] m, Vector3D p) {
        double x = m[0] * p.x() + m[4] * p.y() + m[8] * p.z() + m[12];
        double y = m[1] * p.x() + m[5] * p.y() + m[9] * p.z() + m[13];
        double z = m[2] * p.x() + m[6] * p.y() + m[10] * p.z() + m[14];
        double w = m[3] * p.x() + m[7] * p.y() + m[11] * p.z() + m[15];
        if (Math.abs(w) > 1.0e-12) {
            return new Vector3D(x / w, y / w, z / w);
        }
        return new Vector3D(x, y, z);
    }

    private static double[] multiplyColumnMajor(double[] a, double[] b) {
        if (a == null || a.length != 16) {
            return b;
        }
        if (b == null || b.length != 16) {
            return a;
        }
        double[] out = new double[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                out[col * 4 + row] =
                    a[0 * 4 + row] * b[col * 4 + 0] +
                    a[1 * 4 + row] * b[col * 4 + 1] +
                    a[2 * 4 + row] * b[col * 4 + 2] +
                    a[3 * 4 + row] * b[col * 4 + 3];
            }
        }
        return out;
    }
}
