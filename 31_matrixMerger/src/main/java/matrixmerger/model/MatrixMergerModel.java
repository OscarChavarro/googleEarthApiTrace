package matrixmerger.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import matrixmerger.io.TileMatrix;
import matrixmerger.processing.FullSetMerger;
import matrixmerger.processing.MatrixMerger;
import vsdk.toolkit.common.RendererConfiguration;
import vsdk.toolkit.environment.Camera;

public final class MatrixMergerModel {
    private final Camera viewingCamera = new Camera();
    private final RendererConfiguration renderingConfiguration = new RendererConfiguration();
    private final List<TileMatrix> tileMatrices = new ArrayList<>();
    private final Set<String> residentTexturePaths = new HashSet<>();
    private final ArrayDeque<String> residentTexturesFifo = new ArrayDeque<>();
    private final MatrixMerger matrixMerger = new MatrixMerger();
    private final FullSetMerger fullSetMerger = new FullSetMerger();
    private long gpuTextureBytesAssigned = 0L;
    private int selectedMatrixIndex = 0;
    private boolean lastMergeFailedForCurrentSelection = false;

    public MatrixMergerModel() {
        viewingCamera.setName("OrbiterCamera");
        renderingConfiguration.setWires(false);
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
        lastMergeFailedForCurrentSelection = false;
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
        lastMergeFailedForCurrentSelection = false;
        return true;
    }

    public boolean selectNextMatrix() {
        if (tileMatrices.isEmpty() || selectedMatrixIndex >= tileMatrices.size() - 1) {
            return false;
        }
        selectedMatrixIndex++;
        lastMergeFailedForCurrentSelection = false;
        return true;
    }

    public boolean mergeSelectedMatrixWithNext() {
        int idx = getSelectedMatrixIndex();
        if (idx < 0 || idx + 1 >= tileMatrices.size()) {
            lastMergeFailedForCurrentSelection = false;
            return false;
        }
        TileMatrix a = tileMatrices.get(idx);
        TileMatrix b = tileMatrices.get(idx + 1);
        if (!matrixMerger.merge(a, b)) {
            lastMergeFailedForCurrentSelection = true;
            return false;
        }
        tileMatrices.remove(idx + 1);
        selectedMatrixIndex = Math.max(0, Math.min(idx, tileMatrices.size() - 1));
        lastMergeFailedForCurrentSelection = false;
        return true;
    }

    public boolean hasNextMatrixForSelection() {
        int idx = getSelectedMatrixIndex();
        return idx >= 0 && idx + 1 < tileMatrices.size();
    }

    public TileMatrix getNextMatrixForSelection() {
        int idx = getSelectedMatrixIndex();
        if (idx < 0 || idx + 1 >= tileMatrices.size()) {
            return null;
        }
        return tileMatrices.get(idx + 1);
    }

    public boolean hasLastMergeFailedForCurrentSelection() {
        return lastMergeFailedForCurrentSelection;
    }

    public boolean mergeFullSet() {
        boolean merged = fullSetMerger.merge(tileMatrices);
        selectedMatrixIndex = Math.max(0, Math.min(selectedMatrixIndex, Math.max(0, tileMatrices.size() - 1)));
        lastMergeFailedForCurrentSelection = false;
        return merged;
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
