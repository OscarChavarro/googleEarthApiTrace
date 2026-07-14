package pyramidalimageexporter.processing.toplevels;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;

final class TopLevelLayerMergerTest {
    @Test
    void mergesImportedTilesIntoOneReconstructedTopLevelLayer() {
        MatrixLayer inferredLevelFive = layer(
            "topLevel_matrix_05",
            tile("021121", 17, 9, "/tmp/inferred-a.png"),
            tile("021122", 17, 10, "/tmp/inferred-b.png")
        );
        inferredLevelFive.setRows(32);
        inferredLevelFive.setCols(32);

        MatrixLayer imported = layer("matrix_0", tile("021121", 3, 4, "/tmp/read.png"));

        TopLevelLayerMerger.MergeResult result = new TopLevelLayerMerger().merge(
            List.of(inferredLevelFive),
            List.of(imported),
            Map.of(),
            Path.of("/tmp")
        );

        assertEquals(1, result.layers().size());
        assertEquals("topLevel_matrix_05", result.layers().get(0).getSourceFolderName());
        assertEquals(
            List.of("021121", "021122"),
            result.layers().get(0).getTiles().stream().map(MatrixLayerTile::getId).toList()
        );
        assertEquals("021121", result.mergedFullPathByOriginalId().get("021121"));
        MatrixLayerTile mergedTile = result.layers().get(0).getTiles().get(0);
        assertEquals(17, mergedTile.getI());
        assertEquals(9, mergedTile.getJ());
        assertEquals("/tmp/read.png", mergedTile.getTextureFile());
    }

    @Test
    void retainsImportedLayersThatBelongBelowTopLevels() {
        MatrixLayer inferredLevelFive = layer(
            "topLevel_matrix_05",
            tile("021121", 17, 9, "/tmp/inferred.png")
        );
        MatrixLayer importedTop = layer("matrix_0", tile("021121", 0, 0, "/tmp/read.png"));
        MatrixLayer importedNext = layer("matrix_1", tile("0211212", 0, 0, "/tmp/next.png"));

        TopLevelLayerMerger.MergeResult result = new TopLevelLayerMerger().merge(
            List.of(inferredLevelFive),
            List.of(importedTop, importedNext),
            Map.of(),
            Path.of("/tmp")
        );

        assertEquals(2, result.layers().size());
        assertEquals("topLevel_matrix_05", result.layers().get(0).getSourceFolderName());
        assertEquals("matrix_1", result.layers().get(1).getSourceFolderName());
    }

    private static MatrixLayer layer(String name, MatrixLayerTile... tiles) {
        MatrixLayer layer = new MatrixLayer();
        layer.setSourceFolderName(name);
        layer.setTiles(List.of(tiles));
        return layer;
    }

    private static MatrixLayerTile tile(String id, int i, int j, String texture) {
        MatrixLayerTile tile = new MatrixLayerTile();
        tile.setId(id);
        tile.setI(i);
        tile.setJ(j);
        tile.setTextureFile(texture);
        return tile;
    }
}
