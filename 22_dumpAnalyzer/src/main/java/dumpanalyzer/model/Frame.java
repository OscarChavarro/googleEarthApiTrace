package dumpanalyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import vsdk.toolkit.common.linealAlgebra.Vector3D;
import vsdk.toolkit.environment.Camera;

public final class Frame implements Comparable<Frame> {
    private final int id;
    private final List<TileInstance> tiles;
    private final List<AxisAlignedBoundingBox> axisAlignedBoundingBoxes;
    private final FrameCameraState camera;

    public Frame(int id, List<TileInstance> tiles, double[] projectionMatrix, double[] modelViewMatrix, Camera googleCamera) {
        this.id = id;
        List<TileInstance> copy = new ArrayList<>(tiles);
        copy.sort(Comparator.comparingInt(TileInstance::getContentId));
        this.tiles = Collections.unmodifiableList(copy);
        this.camera = new FrameCameraState(projectionMatrix, modelViewMatrix, googleCamera);
        this.axisAlignedBoundingBoxes = Collections.unmodifiableList(buildAabbsFromTiles(copy));
    }

    public int getId() {
        return id;
    }

    public List<TileInstance> getTiles() {
        return tiles;
    }

    public List<AxisAlignedBoundingBox> getAxisAlignedBoundingBoxes() {
        return axisAlignedBoundingBoxes;
    }

    public FrameCameraState getCamera() {
        return camera;
    }

    @JsonIgnore
    public double[] getProjectionMatrix() {
        return camera == null ? null : camera.getProjectionMatrix();
    }

    @JsonIgnore
    public double[] getModelViewMatrix() {
        return camera == null ? null : camera.getModelViewMatrix();
    }

    @JsonIgnore
    public Camera getGoogleCamera() {
        return camera == null ? null : camera.getGoogleCameraRaw();
    }

    @Override
    public int compareTo(Frame other) {
        return Integer.compare(this.id, other.id);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Frame frame)) return false;
        return id == frame.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    private static List<AxisAlignedBoundingBox> buildAabbsFromTiles(List<TileInstance> tiles) {
        List<AxisAlignedBoundingBox> out = new ArrayList<>(tiles.size());
        for (int i = 0; i < tiles.size(); i++) {
            TileInstance tile = tiles.get(i);
            if (tile == null || tile.getMin() == null || tile.getMax() == null) {
                continue;
            }
            out.add(new AxisAlignedBoundingBox(
                tile.getMin(),
                tile.getMax(),
                tile.getContentId(),
                i,
                tile.getModelViewMatrix(),
                tile.getPoints()
            ));
        }
        return out;
    }

    public static final class FrameCameraState {
        private final double[] projectionMatrix;
        private final double[] modelViewMatrix;
        private final Camera googleCamera;

        public FrameCameraState(double[] projectionMatrix, double[] modelViewMatrix, Camera googleCamera) {
            this.projectionMatrix = projectionMatrix == null ? null : projectionMatrix.clone();
            this.modelViewMatrix = modelViewMatrix == null ? null : modelViewMatrix.clone();
            this.googleCamera = googleCamera;
        }

        public double[] getProjectionMatrix() {
            return projectionMatrix == null ? null : projectionMatrix.clone();
        }

        public double[] getModelViewMatrix() {
            return modelViewMatrix == null ? null : modelViewMatrix.clone();
        }

        @JsonIgnore
        public Camera getGoogleCameraRaw() {
            return googleCamera;
        }

        @JsonProperty("googleCamera")
        public CameraJsonView getGoogleCamera() {
            return CameraJsonView.from(googleCamera);
        }
    }

    public static final class CameraJsonView {
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

        private CameraJsonView(Camera camera) {
            camera.updateVectors();
            Vector3D p = camera.getPosition();
            Vector3D f = camera.getFront();
            Vector3D l = camera.getLeft();
            Vector3D u = camera.getUp();
            this.positionX = p.x();
            this.positionY = p.y();
            this.positionZ = p.z();
            this.frontX = f.x();
            this.frontY = f.y();
            this.frontZ = f.z();
            this.leftX = l.x();
            this.leftY = l.y();
            this.leftZ = l.z();
            this.upX = u.x();
            this.upY = u.y();
            this.upZ = u.z();
            this.projectionMode = camera.getProjectionMode();
            this.orthogonalZoom = camera.getOrthogonalZoom();
            this.viewportXSize = camera.getViewportXSize();
            this.viewportYSize = camera.getViewportYSize();
            this.fov = camera.getFov();
            this.nearPlaneDistance = camera.getNearPlaneDistance();
            this.farPlaneDistance = camera.getFarPlaneDistance();
        }

        public static CameraJsonView from(Camera camera) {
            if (camera == null) {
                return null;
            }
            return new CameraJsonView(camera);
        }

        public double getPositionX() { return positionX; }
        public double getPositionY() { return positionY; }
        public double getPositionZ() { return positionZ; }
        public double getFrontX() { return frontX; }
        public double getFrontY() { return frontY; }
        public double getFrontZ() { return frontZ; }
        public double getLeftX() { return leftX; }
        public double getLeftY() { return leftY; }
        public double getLeftZ() { return leftZ; }
        public double getUpX() { return upX; }
        public double getUpY() { return upY; }
        public double getUpZ() { return upZ; }
        public int getProjectionMode() { return projectionMode; }
        public double getOrthogonalZoom() { return orthogonalZoom; }
        public double getViewportXSize() { return viewportXSize; }
        public double getViewportYSize() { return viewportYSize; }
        public double getFov() { return fov; }
        public double getNearPlaneDistance() { return nearPlaneDistance; }
        public double getFarPlaneDistance() { return farPlaneDistance; }
    }
}
