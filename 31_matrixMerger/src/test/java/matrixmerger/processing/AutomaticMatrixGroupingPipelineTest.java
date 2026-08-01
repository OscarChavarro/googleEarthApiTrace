package matrixmerger.processing;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import matrixmerger.model.contract.FrameMatrixSet;
import matrixmerger.model.contract.FrameTileMatrix;
import matrixmerger.model.state.MatrixMergerState;
import org.junit.jupiter.api.Test;

final class AutomaticMatrixGroupingPipelineTest {
    @Test
    void disconnectedMatricesRemainDisconnectedWithoutObservedHierarchyEvidence() {
        MatrixMergerState state = new MatrixMergerState();
        state.setFrameMatrices(List.of(
            frame(10, tile("00010_1", 0), tile("00010_2", 1)),
            frame(20, tile("00020_1", 0), tile("00020_2", 1))
        ));

        new AutomaticMatrixGroupingPipeline().run(state);

        for (FrameMatrixSet frame : state.getFrameMatrices()) {
            assertNull(frame.getInferredParent());
            assertNull(frame.getParentGridTransform());
        }
    }

    private static FrameTileMatrix.TileCoord tile(String id, int col) {
        FrameTileMatrix.TileCoord tile = new FrameTileMatrix.TileCoord();
        tile.setId(id);
        tile.setI(0);
        tile.setJ(col);
        tile.setTextureFile("/tmp/visually-similar.png");
        return tile;
    }

    private static FrameMatrixSet frame(int frameId, FrameTileMatrix.TileCoord... tiles) {
        FrameTileMatrix matrix = new FrameTileMatrix();
        matrix.setFrameId(frameId);
        matrix.setRows(1);
        matrix.setCols(tiles.length);
        matrix.setTiles(List.of(tiles));
        FrameMatrixSet frame = new FrameMatrixSet();
        frame.setFrameId(frameId);
        frame.setMatrices(List.of(matrix));
        return frame;
    }
}
