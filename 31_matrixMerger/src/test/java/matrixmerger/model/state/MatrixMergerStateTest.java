package matrixmerger.model.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import matrixmerger.model.contract.FrameMatrixSet;
import matrixmerger.model.contract.FrameTileMatrix;
import matrixmerger.processing.uncles.ToUncleRelationship;
import matrixmerger.processing.uncles.UncleDirections;
import org.junit.jupiter.api.Test;

final class MatrixMergerStateTest {
    @Test
    void sortsMatricesFromTopToBottomUsingUncleRelationships() {
        FrameMatrixSet deepest = frame(-1, "30_1", "20_1");
        FrameMatrixSet top = frame(-1, "10_1", null);
        FrameMatrixSet middle = frame(-1, "20_1", "10_1");
        MatrixMergerState state = new MatrixMergerState();

        state.setFrameMatrices(List.of(deepest, top, middle));
        state.sortFramesByUncleHierarchy();

        assertEquals(List.of("00010_1", "00020_1", "00030_1"), state.getFrameMatrices().stream()
            .map(MatrixMergerStateTest::tileId)
            .toList());
        assertEquals("l", state.getSelectedHierarchyLabel());
        state.selectNextMatrix();
        assertEquals("l + 1", state.getSelectedHierarchyLabel());
        state.selectNextMatrix();
        assertEquals("l + 2", state.getSelectedHierarchyLabel());
    }

    @Test
    void sortsUsingPreservedHierarchyMetadataWhenVisibleUnclesAreGone() {
        FrameMatrixSet top = frame(-1, "10_1", null);
        FrameMatrixSet mergedMiddle = frame(-1, "20_1", null, "20_3");
        Map<String, List<String>> hierarchyUnclesByTileId = new LinkedHashMap<>();
        hierarchyUnclesByTileId.put("00020_1", List.of("00010_1"));
        mergedMiddle.setHierarchyUnclesByTileId(hierarchyUnclesByTileId);
        FrameMatrixSet deepest = frame(-1, "30_1", "20_3");
        MatrixMergerState state = new MatrixMergerState();

        state.setFrameMatrices(List.of(deepest, mergedMiddle, top));
        state.sortFramesByUncleHierarchy();

        assertEquals(3, state.getFrameMatrices().size());
        assertEquals(List.of("00010_1", "00020_1", "00030_1"), state.getFrameMatrices().stream()
            .map(MatrixMergerStateTest::tileId)
            .toList());
        assertEquals("l", state.getSelectedHierarchyLabel());
        state.selectNextMatrix();
        assertEquals("l + 1", state.getSelectedHierarchyLabel());
        state.selectNextMatrix();
        assertEquals("l + 2", state.getSelectedHierarchyLabel());
    }

    private static FrameMatrixSet frame(int frameId, String tileId, String uncleId) {
        return frame(frameId, tileId, uncleId, null);
    }

    private static FrameMatrixSet frame(int frameId, String tileId, String uncleId, String extraTileId) {
        FrameTileMatrix.TileCoord tile = new FrameTileMatrix.TileCoord();
        tile.setId(tileId);
        if (uncleId != null) {
            tile.setUncles(List.of(new ToUncleRelationship(UncleDirections.WEST_NORTH, uncleId)));
        }
        FrameTileMatrix.TileCoord extraTile = null;
        if (extraTileId != null) {
            extraTile = new FrameTileMatrix.TileCoord();
            extraTile.setId(extraTileId);
        }

        FrameTileMatrix matrix = new FrameTileMatrix();
        matrix.setFrameId(frameId);
        matrix.setRows(1);
        matrix.setCols(extraTile == null ? 1 : 2);
        matrix.setTiles(extraTile == null ? List.of(tile) : List.of(tile, extraTile));

        FrameMatrixSet frame = new FrameMatrixSet();
        frame.setFrameId(frameId);
        frame.setMatrices(List.of(matrix));
        return frame;
    }

    private static String tileId(FrameMatrixSet frame) {
        return frame.getMatrices().get(0).getTiles().get(0).getId();
    }
}
