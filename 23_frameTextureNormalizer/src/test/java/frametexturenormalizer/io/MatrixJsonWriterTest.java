package frametexturenormalizer.io;

import static org.junit.jupiter.api.Assertions.assertEquals;

import frametexturenormalizer.model.TileMatrix;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class MatrixJsonWriterTest {
    @Test
    void identicalCanonicalTexturesDoNotCollapseDistinctNativeTileIds() {
        String repeatedTexture = "/capture/00347/523.png";
        TileMatrix matrix = new TileMatrix(758, 1, 3, List.of(
            tile(10, 0, repeatedTexture),
            tile(20, 1, repeatedTexture),
            tile(30, 2, "/capture/00754/920.png")
        ));

        Map<Integer, String> ids = MatrixJsonWriter.buildExportIds(758, List.of(matrix));

        assertEquals("00758_10", ids.get(10));
        assertEquals("00758_20", ids.get(20));
        assertEquals("00754_920", ids.get(30));
    }

    @Test
    void externalOccurrenceUsesCanonicalIdOfAnExportedTextureCell() {
        String resolved = MatrixJsonWriter.resolveReferenceId(
            1471,
            "01693_1060",
            Map.of(1109, "01058_1109"),
            Map.of("01693_1060", "00930_1060")
        );

        assertEquals("00930_1060", resolved);
    }

    private static TileMatrix.TileCoord tile(int id, int col, String texture) {
        return new TileMatrix.TileCoord(id, 0, col, texture, List.of());
    }
}
