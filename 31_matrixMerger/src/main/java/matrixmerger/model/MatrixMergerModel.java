package matrixmerger.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import matrixmerger.io.TileMatrix;
import vsdk.toolkit.common.RendererConfiguration;
import vsdk.toolkit.environment.Camera;

public final class MatrixMergerModel {
    private final Camera viewingCamera = new Camera();
    private final RendererConfiguration renderingConfiguration = new RendererConfiguration();
    private final List<TileMatrix> tileMatrices = new ArrayList<>();
    private final Set<String> residentTexturePaths = new HashSet<>();
    private final ArrayDeque<String> residentTexturesFifo = new ArrayDeque<>();
    private long gpuTextureBytesAssigned = 0L;
    private int selectedMatrixIndex = 0;

    public MatrixMergerModel() {
        viewingCamera.setName("OrbiterCamera");
        renderingConfiguration.setWires(true);
    }

    public Camera getViewingCamera() {
        return viewingCamera;
    }

    public RendererConfiguration getRenderingConfiguration() {
        return renderingConfiguration;
    }

    public void setTileMatrices(List<TileMatrix> matrices) {
        tileMatrices.clear();
        if (matrices != null) {
            tileMatrices.addAll(matrices);
        }
        selectedMatrixIndex = 0;
    }

    public List<TileMatrix> getTileMatrices() {
        return Collections.unmodifiableList(tileMatrices);
    }

    public TileMatrix getSelectedMatrix() {
        int idx = getSelectedMatrixIndex();
        if (idx < 0) {
            return null;
        }
        return tileMatrices.get(idx);
    }

    public int getSelectedMatrixIndex() {
        if (tileMatrices.isEmpty()) {
            return -1;
        }
        selectedMatrixIndex = Math.max(0, Math.min(selectedMatrixIndex, tileMatrices.size() - 1));
        return selectedMatrixIndex;
    }

    public int getSelectedMatrixOrdinal() {
        int idx = getSelectedMatrixIndex();
        return idx < 0 ? 0 : (idx + 1);
    }

    public int getMatrixCount() {
        return tileMatrices.size();
    }

    public boolean selectPreviousMatrix() {
        if (tileMatrices.isEmpty() || selectedMatrixIndex <= 0) {
            return false;
        }
        selectedMatrixIndex--;
        return true;
    }

    public boolean selectNextMatrix() {
        if (tileMatrices.isEmpty() || selectedMatrixIndex >= tileMatrices.size() - 1) {
            return false;
        }
        selectedMatrixIndex++;
        return true;
    }

    public synchronized long getGpuTextureBytesAssigned() {
        return gpuTextureBytesAssigned;
    }

    public synchronized boolean markTextureResident(String texturePath, long bytes) {
        if (texturePath == null || texturePath.isBlank() || bytes <= 0L || residentTexturePaths.contains(texturePath)) {
            return false;
        }
        residentTexturePaths.add(texturePath);
        residentTexturesFifo.addLast(texturePath);
        gpuTextureBytesAssigned += bytes;
        return true;
    }

    public synchronized String popOldestResidentTexturePath() {
        while (!residentTexturesFifo.isEmpty()) {
            String texturePath = residentTexturesFifo.pollFirst();
            if (residentTexturePaths.contains(texturePath)) {
                return texturePath;
            }
        }
        return null;
    }

    public synchronized void unmarkTextureResident(String texturePath, long bytes) {
        if (texturePath == null || !residentTexturePaths.remove(texturePath)) {
            return;
        }
        residentTexturesFifo.removeFirstOccurrence(texturePath);
        gpuTextureBytesAssigned = Math.max(0L, gpuTextureBytesAssigned - Math.max(0L, bytes));
    }
}
