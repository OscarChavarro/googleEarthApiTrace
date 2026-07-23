package matrixmerger.model.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void usesCaptureLocalityToPlaceMatricesWithoutResolvableUncles() {
        FrameMatrixSet lateDisconnected = frame(-1, "90_1", null);
        FrameMatrixSet deepest = frame(-1, "30_1", "20_1");
        FrameMatrixSet top = frame(-1, "10_1", null);
        FrameMatrixSet middle = frame(-1, "20_1", "10_1");
        MatrixMergerState state = new MatrixMergerState();

        state.setFrameMatrices(List.of(lateDisconnected, deepest, top, middle));
        state.sortFramesByUncleHierarchy();

        assertEquals(List.of("00010_1", "00020_1", "00030_1", "00090_1"), state.getFrameMatrices().stream()
            .map(MatrixMergerStateTest::tileId)
            .toList());
        List<String> expectedLevels = List.of("l", "l + 1", "l + 2", "l");
        for (int i = 0; i < state.getFrameMatrices().size(); i++) {
            state.selectFrameIndex(i);
            assertEquals(expectedLevels.get(i), state.getSelectedHierarchyLabel());
        }
    }

    @Test
    void prioritizesExplicitTopLevelEvidenceOverLocality() {
        FrameMatrixSet relationless = frame(-1, "10_1", null);
        FrameMatrixSet explicitTop = frame(-1, "20_1", "1_1");
        MatrixMergerState state = new MatrixMergerState();

        state.setFrameMatrices(List.of(relationless, explicitTop));
        state.sortFramesByUncleHierarchy();

        assertEquals(List.of("00020_1", "00010_1"), state.getFrameMatrices().stream()
            .map(MatrixMergerStateTest::tileId)
            .toList());
    }

    @Test
    void deletesSelectedMatrixAndKeepsSelectionStable() {
        MatrixMergerState state = new MatrixMergerState();
        FrameMatrixSet first = frame(10, "10_1", null);
        FrameMatrixSet second = frame(20, "20_1", null);
        FrameMatrixSet third = frame(30, "30_1", null);

        state.setFrameMatrices(List.of(first, second, third));
        state.selectFrameIndex(1);

        assertTrue(state.deleteSelectedMatrix());
        assertEquals(2, state.getMatrixCount());
        assertEquals("30", state.getSelectedFrameLabel());
        assertEquals(List.of(10, 30), state.getFrameMatrices().stream()
            .map(FrameMatrixSet::getFrameId)
            .toList());
    }

    @Test
    void flattensMultiMatrixFramesIntoSelectableComponents() {
        MatrixMergerState state = new MatrixMergerState();
        FrameMatrixSet frame = new FrameMatrixSet();
        frame.setFrameId(20);
        frame.setMatrices(List.of(
            matrix(20, List.of(tile("20_1", 0, 0), tile("20_2", 0, 1)), 1, 2),
            matrix(20, List.of(tile("20_3", 0, 0), tile("20_4", 1, 0)), 2, 1)
        ));

        state.setFrameMatrices(List.of(frame));

        assertEquals(2, state.getMatrixCount());
        assertEquals(List.of(20, 20), state.getFrameMatrices().stream().map(FrameMatrixSet::getFrameId).toList());
        assertEquals("00020_1", tileId(state.getFrameMatrices().get(0)));
        assertEquals("00020_3", tileId(state.getFrameMatrices().get(1)));
    }

    @Test
    void discardsMatricesBelowMinimumTileCountAndReportsTheirTiles() {
        MatrixMergerState state = new MatrixMergerState();
        FrameMatrixSet small = frame(10, "10_1", null);
        FrameMatrixSet retained = new FrameMatrixSet();
        retained.setFrameId(20);
        retained.setMatrices(List.of(matrix(
            20,
            java.util.stream.IntStream.range(0, 10)
                .mapToObj(i -> tile("20_" + i, 0, i))
                .toList(),
            1,
            10
        )));
        state.setFrameMatrices(List.of(small, retained));

        MatrixMergerState.SmallMatrixDiscardReport report =
            state.discardMatricesWithFewerThanTiles(10);

        assertEquals(1, report.matrixCount());
        assertEquals(2, report.tileCount());
        assertEquals(List.of("00010_1", "00010_9999"), report.tileIds());
        assertEquals(1, state.getMatrixCount());
        assertEquals(10, state.getHierarchyOrderDiagnostics().get(0).tileCount());
    }

    private static FrameMatrixSet frame(int frameId, String tileId, String uncleId) {
        return frame(frameId, tileId, uncleId, null);
    }

    private static FrameMatrixSet frame(int frameId, String tileId, String uncleId, String extraTileId) {
        FrameTileMatrix.TileCoord tile = tile(tileId, 0, 0);
        if (uncleId != null) {
            tile.setUncles(List.of(new ToUncleRelationship(UncleDirections.WEST_NORTH, uncleId)));
        }
        String secondTileId = extraTileId == null ? siblingTileId(tileId) : extraTileId;
        FrameTileMatrix.TileCoord extraTile = tile(secondTileId, 0, 1);

        FrameTileMatrix matrix = matrix(frameId, List.of(tile, extraTile), 1, 2);

        FrameMatrixSet frame = new FrameMatrixSet();
        frame.setFrameId(frameId);
        frame.setMatrices(List.of(matrix));
        return frame;
    }

    private static FrameTileMatrix matrix(int frameId, List<FrameTileMatrix.TileCoord> tiles, int rows, int cols) {
        FrameTileMatrix matrix = new FrameTileMatrix();
        matrix.setFrameId(frameId);
        matrix.setRows(rows);
        matrix.setCols(cols);
        matrix.setTiles(tiles);
        return matrix;
    }

    private static FrameTileMatrix.TileCoord tile(String tileId, int i, int j) {
        FrameTileMatrix.TileCoord tile = new FrameTileMatrix.TileCoord();
        tile.setId(tileId);
        tile.setI(i);
        tile.setJ(j);
        tile.setTextureFile("/tmp/" + tileId + ".png");
        return tile;
    }

    private static String siblingTileId(String tileId) {
        int separator = tileId.indexOf('_');
        String framePart = separator > 0 ? tileId.substring(0, separator) : "0";
        return framePart + "_9999";
    }

    private static String tileId(FrameMatrixSet frame) {
        return frame.getMatrices().get(0).getTiles().get(0).getId();
    }
}
