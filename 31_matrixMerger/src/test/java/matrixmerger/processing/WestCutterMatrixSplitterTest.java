package matrixmerger.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import matrixmerger.model.contract.FrameMatrixSet;
import matrixmerger.model.contract.FrameTileMatrix;
import matrixmerger.model.contract.ParentGridTransform;
import org.junit.jupiter.api.Test;

final class WestCutterMatrixSplitterTest {
    @Test
    void preservesTheParentGridTransformOnBothSidesOfTheCut() {
        FrameTileMatrix matrix = new FrameTileMatrix();
        matrix.setRows(1);
        matrix.setCols(4);
        matrix.setTiles(List.of(
            tile("00010_1", 0),
            tile("00010_2", 1),
            tile("00010_3", 2),
            tile("00010_4", 3)
        ));
        FrameMatrixSet frame = new FrameMatrixSet();
        frame.setFrameId(10);
        frame.setMatrices(List.of(matrix));
        frame.setParentGridTransform(new ParentGridTransform(5, 7));

        WestCutterMatrixSplitter.FrameSplitResult result =
            new WestCutterMatrixSplitter().splitFrame(frame, Set.of("00010_3"));

        assertEquals(new ParentGridTransform(5, 9), result.mainFrame().getParentGridTransform());
        assertEquals(new ParentGridTransform(5, 7), result.transientFrame().getParentGridTransform());
        assertEquals(List.of("00010_3", "00010_4"), ids(result.mainFrame()));
        assertEquals(List.of("00010_1", "00010_2"), ids(result.transientFrame()));
    }

    private static FrameTileMatrix.TileCoord tile(String id, int j) {
        FrameTileMatrix.TileCoord tile = new FrameTileMatrix.TileCoord();
        tile.setId(id);
        tile.setI(0);
        tile.setJ(j);
        return tile;
    }

    private static List<String> ids(FrameMatrixSet frame) {
        return frame.getMatrices().get(0).getTiles().stream().map(FrameTileMatrix.TileCoord::getId).toList();
    }
}
