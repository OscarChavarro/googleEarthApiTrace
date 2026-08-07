package matrixmerger.model.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void preservesMissingHierarchyLevelsFromAGenericAncestorRelationship() {
        FrameMatrixSet ancestor = frame(10, "10_1", null);
        FrameMatrixSet island = frame(30, "30_1", null);
        island.getMatrices().get(0).getTiles().get(0).setUncles(List.of(new ToUncleRelationship(
            UncleDirections.EAST_SOUTH,
            "10_1",
            matrixmerger.processing.uncles.UncleRelationshipKind.ADJACENT_BORDER,
            2,
            2,
            4
        )));
        MatrixMergerState state = new MatrixMergerState();

        state.setFrameMatrices(List.of(island, ancestor));
        state.sortFramesByUncleHierarchy();

        assertEquals(List.of(0, 2), state.getHierarchyOrderDiagnostics().stream()
            .map(MatrixMergerState.HierarchyOrderDiagnostic::level)
            .toList());
        assertEquals(2, state.getFrameMatrices().get(1).getParentLevelDelta());
    }

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

        assertEquals(List.of("00010_1", "00090_1", "00020_1", "00030_1"), state.getFrameMatrices().stream()
            .map(MatrixMergerStateTest::tileId)
            .toList());
        List<String> expectedLevels = List.of("l", "l", "l + 1", "l + 2");
        for (int i = 0; i < state.getFrameMatrices().size(); i++) {
            state.selectFrameIndex(i);
            assertEquals(expectedLevels.get(i), state.getSelectedHierarchyLabel());
        }
    }

    @Test
    void groupsMultipleMatricesAtEachDepthBeforeShowingTheNextDepth() {
        FrameMatrixSet secondChild = frame(-1, "50_1", "40_1");
        FrameMatrixSet firstChild = frame(-1, "20_1", "10_1");
        FrameMatrixSet secondRoot = frame(-1, "40_1", null);
        FrameMatrixSet firstRoot = frame(-1, "10_1", null);
        MatrixMergerState state = new MatrixMergerState();

        state.setFrameMatrices(List.of(secondChild, firstChild, secondRoot, firstRoot));
        state.sortFramesByUncleHierarchy();

        assertEquals(List.of("00010_1", "00040_1", "00020_1", "00050_1"), state.getFrameMatrices().stream()
            .map(MatrixMergerStateTest::tileId)
            .toList());
        assertEquals(List.of(0, 0, 1, 1), state.getHierarchyOrderDiagnostics().stream()
            .map(MatrixMergerState.HierarchyOrderDiagnostic::level)
            .toList());
    }

    @Test
    void resolvesAChildWhoseParentLevelIsSplitAcrossMatrices() {
        FrameMatrixSet child = frame(-1, "30_1", "10_1");
        child.getMatrices().get(0).getTiles().get(1).setUncles(List.of(
            new ToUncleRelationship(UncleDirections.WEST_NORTH, "20_1")
        ));
        MatrixMergerState state = new MatrixMergerState();

        state.setFrameMatrices(List.of(
            child,
            frame(-1, "20_1", null),
            frame(-1, "10_1", null)
        ));
        state.sortFramesByUncleHierarchy();

        assertEquals(List.of("00010_1", "00020_1", "00030_1"), state.getFrameMatrices().stream()
            .map(MatrixMergerStateTest::tileId)
            .toList());
        assertEquals(List.of(0, 0, 1), state.getHierarchyOrderDiagnostics().stream()
            .map(MatrixMergerState.HierarchyOrderDiagnostic::level)
            .toList());
        assertEquals(List.of(0, 1), state.getHierarchyOrderDiagnostics().get(2).resolvedParentIndexes());
        state.selectFrameIndex(2);
        assertEquals(MatrixMergerState.UncleHudState.NORMAL, state.getSelectedMatrixUncleHudStatus().state());
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
    void deletingLastMatrixMovesSelectionToNewLastMatrix() {
        MatrixMergerState state = new MatrixMergerState();
        state.setFrameMatrices(List.of(
            frame(10, "10_1", null),
            frame(20, "20_1", null),
            frame(30, "30_1", null)
        ));
        state.selectFrameIndex(2);

        assertTrue(state.deleteSelectedMatrix());

        assertEquals(2, state.getMatrixCount());
        assertEquals("20", state.getSelectedFrameLabel());
        assertEquals(List.of(10, 20), state.getFrameMatrices().stream()
            .map(FrameMatrixSet::getFrameId)
            .toList());
    }

    @Test
    void doesNotDeleteOnlyRemainingMatrix() {
        MatrixMergerState state = new MatrixMergerState();
        state.setFrameMatrices(List.of(frame(10, "10_1", null)));

        assertFalse(state.deleteSelectedMatrix());

        assertEquals(1, state.getMatrixCount());
        assertEquals("10", state.getSelectedFrameLabel());
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

    @Test
    void removesSmallFourConnectedAreasCreatedByExclusiveOwnership() {
        MatrixMergerState state = new MatrixMergerState();
        FrameMatrixSet owner = frame(10, "10_500", null);
        List<FrameTileMatrix.TileCoord> stripTiles = java.util.stream.IntStream.range(0, 40)
            .mapToObj(col -> tile(col == 20 ? "00010_500" : "00020_" + col, 0, col))
            .toList();
        FrameMatrixSet strip = new FrameMatrixSet();
        strip.setFrameId(20);
        strip.setMatrices(List.of(matrix(20, stripTiles, 1, 40)));
        state.setFrameMatrices(List.of(owner, strip));

        MatrixMergerState.ExclusiveTileOwnershipReport ownership = state.enforceExclusiveTileOwnership();
        MatrixMergerState.TopologyFilterReport topology = state.discardSmallFourConnectedComponents(20);
        MatrixMergerState.ExclusiveTileOwnershipReport finalOwnership = state.enforceExclusiveTileOwnership();

        assertEquals(1, ownership.duplicateOccurrencesRemoved());
        assertEquals(2, topology.discardedComponentCount());
        assertEquals(21, topology.discardedTileCount());
        assertEquals(1, state.getMatrixCount());
        assertEquals(20, state.getFrameMatrices().get(0).getMatrices().get(0).getTiles().size());
        assertEquals(0, finalOwnership.duplicateOccurrencesRemoved());
        assertEquals(20, state.getFrameMatrices().stream()
            .flatMap(item -> item.getMatrices().get(0).getTiles().stream())
            .map(FrameTileMatrix.TileCoord::getId)
            .distinct()
            .count());
    }

    @Test
    void splitsMultipleUsefulFourConnectedAreasInsteadOfKeepingADisconnectedMatrix() {
        MatrixMergerState state = new MatrixMergerState();
        FrameMatrixSet owner = frame(10, "10_500", null);
        List<FrameTileMatrix.TileCoord> stripTiles = java.util.stream.IntStream.range(0, 41)
            .mapToObj(col -> tile(col == 20 ? "00010_500" : "00020_" + col, 0, col))
            .toList();
        FrameMatrixSet strip = new FrameMatrixSet();
        strip.setFrameId(20);
        strip.setMatrices(List.of(matrix(20, stripTiles, 1, 41)));
        state.setFrameMatrices(List.of(owner, strip));

        state.enforceExclusiveTileOwnership();
        MatrixMergerState.TopologyFilterReport topology = state.discardSmallFourConnectedComponents(20);

        assertEquals(1, topology.splitMatrixCount());
        assertEquals(2, state.getMatrixCount());
        assertEquals(List.of(20, 20), state.getFrameMatrices().stream()
            .map(item -> item.getMatrices().get(0).getTiles().size())
            .sorted()
            .toList());
    }

    @Test
    void assignsEveryTileToOnlyTheFirstRemainingMatrix() {
        MatrixMergerState state = new MatrixMergerState();
        FrameMatrixSet first = frame(10, "10_1", null, "10_2");
        FrameMatrixSet overlapping = frame(20, "10_2", null, "20_1");
        state.setFrameMatrices(List.of(first, overlapping));

        MatrixMergerState.ExclusiveTileOwnershipReport report =
            state.enforceExclusiveTileOwnership();

        assertEquals(1, report.duplicateOccurrencesRemoved());
        assertEquals(1, report.affectedMatrices());
        assertEquals(0, report.emptyMatricesRemoved());
        assertEquals(List.of("00010_1", "00010_2", "00020_1"), state.getFrameMatrices().stream()
            .flatMap(frame -> frame.getMatrices().get(0).getTiles().stream())
            .map(FrameTileMatrix.TileCoord::getId)
            .toList());
    }

    @Test
    void collapsesAdjacentMatricesAtTheSameLevelUsingSharedTiles() {
        MatrixMergerState state = new MatrixMergerState();
        state.setFrameMatrices(List.of(
            frame(10, "10_1", null, "10_2"),
            frame(20, "10_2", null, "20_1")
        ));

        MatrixMergerState.SameLevelCollapseReport report =
            state.collapseAdjacentMatricesAtSameHierarchyLevel();

        assertEquals(2, report.inputMatrixCount());
        assertEquals(1, report.retainedMatrixCount());
        assertEquals(1, report.sharedTileMergeCount());
        assertEquals(3, state.getFrameMatrices().get(0).getMatrices().get(0).getTiles().size());
    }

    @Test
    void collapsesSameLevelMatricesUsingObservedCluesToACommonParent() {
        FrameMatrixSet parent = frame(10, "10_1", null, "10_2");
        FrameTileMatrix.TileCoord a0 = tile("20_1", 0, 0);
        FrameTileMatrix.TileCoord a1 = tile("20_2", 0, 1);
        a0.setUncles(List.of(new ToUncleRelationship(UncleDirections.WEST_NORTH, "10_1")));
        a1.setUncles(List.of(new ToUncleRelationship(UncleDirections.EAST_NORTH, "10_1")));
        FrameTileMatrix.TileCoord b0 = tile("30_1", 0, 0);
        FrameTileMatrix.TileCoord b1 = tile("30_2", 0, 1);
        b0.setUncles(List.of(new ToUncleRelationship(UncleDirections.WEST_NORTH, "10_2")));
        b1.setUncles(List.of(new ToUncleRelationship(UncleDirections.EAST_NORTH, "10_2")));
        FrameMatrixSet childA = frameWithTiles(20, List.of(a0, a1), 1, 2);
        FrameMatrixSet childB = frameWithTiles(30, List.of(b0, b1), 1, 2);
        MatrixMergerState state = new MatrixMergerState();
        state.setFrameMatrices(List.of(childB, parent, childA));

        MatrixMergerState.SameLevelCollapseReport report =
            state.collapseAdjacentMatricesAtSameHierarchyLevel();

        assertEquals(1, report.relationshipClueMergeCount());
        assertEquals(2, state.getMatrixCount());
        assertEquals(4, state.getFrameMatrices().get(1).getMatrices().get(0).getTiles().size());
    }

    @Test
    void collapsesCompatibleSameLevelOutputGridsWithoutConflictingCells() {
        FrameMatrixSet first = frameWithTiles(10, List.of(
            tile("10_1", 0, 0),
            tile("10_2", 0, 1)
        ), 2, 2);
        FrameMatrixSet second = frameWithTiles(20, List.of(
            tile("20_1", 1, 0),
            tile("20_2", 1, 1)
        ), 2, 2);
        MatrixMergerState state = new MatrixMergerState();
        state.setFrameMatrices(List.of(first, second));

        MatrixMergerState.SameLevelCollapseReport report =
            state.collapseAdjacentMatricesAtSameHierarchyLevel();

        assertEquals(1, report.compatibleGridMergeCount());
        assertEquals(1, state.getMatrixCount());
        assertEquals(4, state.getFrameMatrices().get(0).getMatrices().get(0).getTiles().size());
    }

    @Test
    void doesNotPlaceAStandaloneSmallGridAtTheOriginOfALargerGrid() {
        FrameMatrixSet large = frameWithTiles(10, List.of(
            tile("10_1", 0, 1),
            tile("10_2", 1, 1)
        ), 4, 4);
        FrameMatrixSet isolated = frameWithTiles(20, List.of(
            tile("20_1", 0, 0),
            tile("20_2", 1, 0),
            tile("20_3", 2, 0)
        ), 3, 1);
        MatrixMergerState state = new MatrixMergerState();
        state.setFrameMatrices(List.of(large, isolated));

        MatrixMergerState.SameLevelCollapseReport report =
            state.collapseAdjacentMatricesAtSameHierarchyLevel();

        assertEquals(0, report.compatibleGridMergeCount());
        assertEquals(2, state.getMatrixCount());
    }

    @Test
    void neverCollapsesAdjacentMatricesFromDifferentLevels() {
        FrameMatrixSet parent = frame(10, "10_1", null, "10_2");
        FrameMatrixSet child = frame(20, "10_1", "10_2", "20_1");
        MatrixMergerState state = new MatrixMergerState();
        state.setFrameMatrices(List.of(child, parent));

        MatrixMergerState.SameLevelCollapseReport report =
            state.collapseAdjacentMatricesAtSameHierarchyLevel();

        assertEquals(0, report.sharedTileMergeCount());
        assertEquals(0, report.relationshipClueMergeCount());
        assertEquals(2, state.getMatrixCount());
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

    private static FrameMatrixSet frameWithTiles(
        int frameId,
        List<FrameTileMatrix.TileCoord> tiles,
        int rows,
        int cols
    ) {
        FrameMatrixSet frame = new FrameMatrixSet();
        frame.setFrameId(frameId);
        frame.setMatrices(List.of(matrix(frameId, tiles, rows, cols)));
        return frame;
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
