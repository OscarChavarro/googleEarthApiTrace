package matrixmerger.model;

import java.util.ArrayList;
import java.util.List;
import matrixmerger.io.TileMatrix;
import vsdk.toolkit.environment.Camera;

public class MatrixMergerModel {
    private Camera camera;
    private List<TileMatrix> tileMatrices = new ArrayList<>();

    public Camera getCamera() {
        return camera;
    }

    public void setCamera(Camera camera) {
        this.camera = camera;
    }

    public List<TileMatrix> getTileMatrices() {
        return tileMatrices;
    }

    public void setTileMatrices(List<TileMatrix> tileMatrices) {
        this.tileMatrices = tileMatrices == null ? new ArrayList<>() : tileMatrices;
    }
}
