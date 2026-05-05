package frametexturenormalizer.model;

import com.fasterxml.jackson.databind.JsonNode;
import vsdk.toolkit.common.linealAlgebra.Matrix4x4;
import vsdk.toolkit.common.linealAlgebra.Vector3D;
import vsdk.toolkit.environment.Camera;

public final class GoogleCameraState {
    private final double positionX;
    private final double positionY;
    private final double positionZ;
    private final double frontX;
    private final double frontY;
    private final double frontZ;
    private final double leftX;
    private final double leftY;
    private final double leftZ;
    private final double upX;
    private final double upY;
    private final double upZ;
    private final int projectionMode;
    private final double orthogonalZoom;
    private final double viewportXSize;
    private final double viewportYSize;
    private final double fov;
    private final double nearPlaneDistance;
    private final double farPlaneDistance;
    private final double[] rotationMatrix;
    private final double[] modelViewMatrix;

    public GoogleCameraState(
        double positionX,
        double positionY,
        double positionZ,
        double frontX,
        double frontY,
        double frontZ,
        double leftX,
        double leftY,
        double leftZ,
        double upX,
        double upY,
        double upZ,
        int projectionMode,
        double orthogonalZoom,
        double viewportXSize,
        double viewportYSize,
        double fov,
        double nearPlaneDistance,
        double farPlaneDistance,
        double[] rotationMatrix,
        double[] modelViewMatrix
    ) {
        this.positionX = positionX;
        this.positionY = positionY;
        this.positionZ = positionZ;
        this.frontX = frontX;
        this.frontY = frontY;
        this.frontZ = frontZ;
        this.leftX = leftX;
        this.leftY = leftY;
        this.leftZ = leftZ;
        this.upX = upX;
        this.upY = upY;
        this.upZ = upZ;
        this.projectionMode = projectionMode;
        this.orthogonalZoom = orthogonalZoom;
        this.viewportXSize = viewportXSize;
        this.viewportYSize = viewportYSize;
        this.fov = fov;
        this.nearPlaneDistance = nearPlaneDistance;
        this.farPlaneDistance = farPlaneDistance;
        this.rotationMatrix = rotationMatrix == null ? null : rotationMatrix.clone();
        this.modelViewMatrix = modelViewMatrix == null ? null : modelViewMatrix.clone();
    }

    public static GoogleCameraState fromFrameJson(JsonNode root) {
        if (root == null) {
            return null;
        }
        JsonNode cameraNode = root.path("camera");
        JsonNode node = cameraNode.path("googleCamera");
        // Compatibility:
        // - New dumpAnalyzer export: { "camera": { "googleCamera": { ... } } }
        // - Legacy direct export: { "googleCamera": { ... } }
        // - Defensive fallback: { "camera": { ...camera fields... } }
        if (node.isMissingNode() || node.isNull() || !node.isObject()) {
            node = root.path("googleCamera");
        }
        if ((node.isMissingNode() || node.isNull() || !node.isObject()) && cameraNode.isObject()) {
            node = cameraNode;
        }
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return new GoogleCameraState(
            node.path("positionX").asDouble(0.0),
            node.path("positionY").asDouble(0.0),
            node.path("positionZ").asDouble(0.0),
            node.path("frontX").asDouble(0.0),
            node.path("frontY").asDouble(1.0),
            node.path("frontZ").asDouble(0.0),
            node.path("leftX").asDouble(-1.0),
            node.path("leftY").asDouble(0.0),
            node.path("leftZ").asDouble(0.0),
            node.path("upX").asDouble(0.0),
            node.path("upY").asDouble(0.0),
            node.path("upZ").asDouble(1.0),
            node.path("projectionMode").asInt(Camera.PROJECTION_MODE_PERSPECTIVE),
            node.path("orthogonalZoom").asDouble(1.0),
            node.path("viewportXSize").asDouble(320.0),
            node.path("viewportYSize").asDouble(320.0),
            node.path("fov").asDouble(60.0),
            node.path("nearPlaneDistance").asDouble(0.05),
            node.path("farPlaneDistance").asDouble(100.0),
            readArray16(node.path("rotationMatrix")),
            readArray16(cameraNode.path("modelViewMatrix"))
        );
    }

    public void applyTo(Camera camera) {
        if (camera == null) {
            return;
        }
        camera.setProjectionMode(projectionMode);
        camera.setOrthogonalZoom(orthogonalZoom);
        camera.setPosition(new Vector3D(positionX, positionY, positionZ));
        Matrix4x4 rotation = fromArray16(rotationMatrix);
        if (rotation != null) {
            camera.setRotation(rotation);
        }
        else {
            camera.setUpDirect(new Vector3D(upX, upY, upZ));
            camera.setLeftDirect(new Vector3D(leftX, leftY, leftZ));
            camera.setFocusedPositionDirect(new Vector3D(
                positionX + frontX,
                positionY + frontY,
                positionZ + frontZ
            ));
        }
        camera.setFov(fov);
        camera.setNearPlaneDistance(nearPlaneDistance);
        camera.setFarPlaneDistance(farPlaneDistance);
        int vx = (int)Math.max(1, Math.round(viewportXSize));
        int vy = (int)Math.max(1, Math.round(viewportYSize));
        camera.updateViewportResize(vx, vy);
    }

    private static double[] readArray16(JsonNode arrNode) {
        if (arrNode == null || !arrNode.isArray() || arrNode.size() != 16) {
            return null;
        }
        double[] out = new double[16];
        for (int i = 0; i < 16; i++) {
            out[i] = arrNode.get(i).asDouble(0.0);
        }
        return out;
    }

    private static Matrix4x4 fromArray16(double[] values) {
        if (values == null || values.length != 16) {
            return null;
        }
        Matrix4x4 matrix = new Matrix4x4();
        int idx = 0;
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                matrix = matrix.withVal(row, col, values[idx++]);
            }
        }
        return matrix;
    }

    public double[] getModelViewMatrix() {
        return modelViewMatrix == null ? null : modelViewMatrix.clone();
    }
}
