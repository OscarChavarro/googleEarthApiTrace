package pyramidalimageexporter.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pyramidalimageexporter.processing.uncles.ToUncleRelationship;

public final class MatrixLayer {
    private Integer contractVersion;
    private Integer hierarchyLevel;
    private Integer parentMatrixIndex;
    private ParentGridTransform parentGridTransform;
    private int frameId;
    private int rows;
    private int cols;
    private String sourceFolderName;
    private List<MatrixLayerTile> tiles = new ArrayList<>();
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

    public ParentGridTransform getParentGridTransform() {
        return parentGridTransform;
    }

    public void setParentGridTransform(ParentGridTransform parentGridTransform) {
        this.parentGridTransform = parentGridTransform;
    }

    public int getFrameId() {
        return frameId;
    }

    public void setFrameId(int frameId) {
        this.frameId = frameId;
    }

    public int getRows() {
        return rows;
    }

    public void setRows(int rows) {
        this.rows = rows;
    }

    public int getCols() {
        return cols;
    }

    public void setCols(int cols) {
        this.cols = cols;
    }

    public String getSourceFolderName() {
        return sourceFolderName;
    }

    public void setSourceFolderName(String sourceFolderName) {
        this.sourceFolderName = sourceFolderName;
    }

    public List<MatrixLayerTile> getTiles() {
        return tiles;
    }

    public void setTiles(List<MatrixLayerTile> tiles) {
        this.tiles = tiles == null ? new ArrayList<>() : new ArrayList<>(tiles);
    }

    public Map<String, List<String>> getHierarchyUnclesByTileId() {
        return hierarchyUnclesByTileId;
    }

    public void setHierarchyUnclesByTileId(Map<String, List<String>> hierarchyUnclesByTileId) {
        this.hierarchyUnclesByTileId = copyListMap(hierarchyUnclesByTileId);
    }

    public Map<String, List<ToUncleRelationship>> getHierarchyRelationshipsByTileId() {
        return hierarchyRelationshipsByTileId;
    }

    public void setHierarchyRelationshipsByTileId(
        Map<String, List<ToUncleRelationship>> hierarchyRelationshipsByTileId
    ) {
        this.hierarchyRelationshipsByTileId = copyListMap(hierarchyRelationshipsByTileId);
    }

    private static <T> Map<String, List<T>> copyListMap(Map<String, List<T>> source) {
        Map<String, List<T>> out = new LinkedHashMap<>();
        if (source == null) {
            return out;
        }
        for (Map.Entry<String, List<T>> entry : source.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank()) {
                out.put(entry.getKey(), entry.getValue() == null ? List.of() : new ArrayList<>(entry.getValue()));
            }
        }
        return out;
    }
}
