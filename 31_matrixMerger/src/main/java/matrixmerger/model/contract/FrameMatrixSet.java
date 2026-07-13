package matrixmerger.model.contract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import matrixmerger.processing.uncles.ToUncleRelationship;

public final class FrameMatrixSet {
    private Integer contractVersion;
    private Integer hierarchyLevel;
    private Integer parentMatrixIndex;
    private int frameId;
    private List<FrameTileMatrix> matrices = new ArrayList<>();
    private Map<String, List<String>> hierarchyUnclesByTileId = new LinkedHashMap<>();
    private Map<String, List<ToUncleRelationship>> hierarchyRelationshipsByTileId = new LinkedHashMap<>();

    public Integer getContractVersion() {
        return contractVersion;
    }

    public void setContractVersion(Integer contractVersion) {
        this.contractVersion = contractVersion;
    }

    public Integer getHierarchyLevel() {
        return hierarchyLevel;
    }

    public void setHierarchyLevel(Integer hierarchyLevel) {
        this.hierarchyLevel = hierarchyLevel;
    }

    public Integer getParentMatrixIndex() {
        return parentMatrixIndex;
    }

    public void setParentMatrixIndex(Integer parentMatrixIndex) {
        this.parentMatrixIndex = parentMatrixIndex;
    }

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

    public Map<String, List<ToUncleRelationship>> getHierarchyRelationshipsByTileId() {
        return hierarchyRelationshipsByTileId;
    }

    public void setHierarchyRelationshipsByTileId(
        Map<String, List<ToUncleRelationship>> hierarchyRelationshipsByTileId
    ) {
        this.hierarchyRelationshipsByTileId = new LinkedHashMap<>();
        if (hierarchyRelationshipsByTileId == null) {
            return;
        }
        for (Map.Entry<String, List<ToUncleRelationship>> entry : hierarchyRelationshipsByTileId.entrySet()) {
            if (entry == null || entry.getKey() == null || entry.getKey().isBlank()) {
                continue;
            }
            List<ToUncleRelationship> relationships = entry.getValue() == null ? List.of() : entry.getValue();
            this.hierarchyRelationshipsByTileId.put(entry.getKey(), new ArrayList<>(relationships));
        }
    }
}
