package matrixmerger.model.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FrameMatrixSet {
    private int frameId;
    private List<FrameTileMatrix> matrices = new ArrayList<>();
    private Map<String, List<String>> hierarchyUnclesByTileId = new LinkedHashMap<>();

    public int getFrameId() {
        return frameId;
    }

    public void setFrameId(int frameId) {
        this.frameId = frameId;
    }

    public List<FrameTileMatrix> getMatrices() {
        return matrices;
    }

    public void setMatrices(List<FrameTileMatrix> matrices) {
        this.matrices = matrices == null ? new ArrayList<>() : new ArrayList<>(matrices);
    }

    public Map<String, List<String>> getHierarchyUnclesByTileId() {
        return hierarchyUnclesByTileId;
    }

    public void setHierarchyUnclesByTileId(Map<String, List<String>> hierarchyUnclesByTileId) {
        this.hierarchyUnclesByTileId = new LinkedHashMap<>();
        if (hierarchyUnclesByTileId == null) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : hierarchyUnclesByTileId.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            List<String> uncleIds = entry.getValue() == null ? List.of() : entry.getValue();
            this.hierarchyUnclesByTileId.put(entry.getKey(), new ArrayList<>(uncleIds));
        }
    }
}
