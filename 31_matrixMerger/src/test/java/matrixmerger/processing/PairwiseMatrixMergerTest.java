package matrixmerger.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import matrixmerger.model.contract.FrameTileMatrix;
import org.junit.jupiter.api.Test;

final class PairwiseMatrixMergerTest {
    private final PairwiseMatrixMerger merger = new PairwiseMatrixMerger();

    @Test
    void mergesSparseMatricesUsingStrictMajorityOffsetAndKeepsSharedTilesOnce() {
        FrameTileMatrix a = matrix(List.of(
            tile("100_1", 0, 0),
            tile("100_2", 0, 1),
            tile("100_3", 0, 2)
        ));
        FrameTileMatrix b = matrix(List.of(
            tile("100_1", 10, 10),
            tile("100_2", 10, 11),
            tile("100_3", 99, 99), // stale placement; the other shared IDs agree
            tile("100_4", 11, 10)
        ));

        assertTrue(merger.merge(a, b));

        assertEquals(4, a.getTiles().size());
        assertEquals(4, a.getTiles().stream().map(FrameTileMatrix.TileCoord::getId).distinct().count());
        FrameTileMatrix.TileCoord appended = a.getTiles().stream()
            .filter(tile -> tile.getId().equals("00100_4"))
            .findFirst()
            .orElseThrow();
        assertEquals(1, appended.getI());
        assertEquals(0, appended.getJ());
        assertEquals(2, a.getRows());
        assertEquals(3, a.getCols());
    }

    @Test
    void refusesAmbiguousOffsetsWithoutMutatingDestination() {
        FrameTileMatrix a = matrix(List.of(tile("100_1", 0, 0), tile("100_2", 0, 1)));
        FrameTileMatrix b = matrix(List.of(tile("100_1", 5, 5), tile("100_2", 8, 8)));

        assertFalse(merger.merge(a, b));

        assertEquals(2, a.getTiles().size());
        assertEquals(List.of("00100_1", "00100_2"),
            a.getTiles().stream().map(FrameTileMatrix.TileCoord::getId).toList());
    }

    private static FrameTileMatrix matrix(List<FrameTileMatrix.TileCoord> tiles) {
        FrameTileMatrix matrix = new FrameTileMatrix();
        matrix.setRows(100);
        matrix.setCols(100);
        matrix.setTiles(new ArrayList<>(tiles));
        return matrix;
    }

    private static FrameTileMatrix.TileCoord tile(String id, int i, int j) {
        FrameTileMatrix.TileCoord tile = new FrameTileMatrix.TileCoord();
        tile.setId(id);
        tile.setI(i);
        tile.setJ(j);
        tile.setTextureFile("/tmp/" + id + ".png");
        return tile;
    }
}
