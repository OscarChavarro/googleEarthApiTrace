package pyramidalimageexporter.processing.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;
import pyramidalimageexporter.processing.uncles.TileRootPathResolver;

final class ReferenceContentRigidAnchorResolverTest {
    @TempDir
    Path temporaryFolder;

    @Test
    void correctsAWholeLayerWhenExactReferenceTilesVoteForAnotherRigidOffset() throws IOException {
        MatrixLayer layer = layerWithTiles(3);
        Map<String, String> preliminaryPaths = new LinkedHashMap<>();
        for (int index = 0; index < 3; index++) {
            MatrixLayerTile tile = layer.getTiles().get(index);
            byte[] content = new byte[]{(byte) (index + 1)};
            Files.write(Path.of(tile.getTextureFile()), content);
            preliminaryPaths.put(tile.getId(), encode(6, 20, 10 + index));
            writeReference(encode(6, 20, 14 + index), content);
        }

        ReferenceContentRigidAnchorResolver.Anchors anchors = new ReferenceContentRigidAnchorResolver().resolve(
            List.of(layer),
            temporaryFolder,
            resolution(preliminaryPaths)
        );

        assertEquals(java.util.Set.of("matrix_0"), anchors.anchoredLayerNames());
        assertEquals(encode(6, 20, 14), anchors.fullPathByTileId().get("tile-0"));
        assertEquals(encode(6, 20, 16), anchors.fullPathByTileId().get("tile-2"));
    }

    @Test
    void refusesToMoveALayerWithoutThreeExactMatches() throws IOException {
        MatrixLayer layer = layerWithTiles(3);
        Map<String, String> preliminaryPaths = new LinkedHashMap<>();
        for (int index = 0; index < 3; index++) {
            MatrixLayerTile tile = layer.getTiles().get(index);
            byte[] content = new byte[]{(byte) (index + 1)};
            Files.write(Path.of(tile.getTextureFile()), content);
            preliminaryPaths.put(tile.getId(), encode(6, 20, 10 + index));
            if (index < 2) {
                writeReference(encode(6, 20, 14 + index), content);
            }
        }

        ReferenceContentRigidAnchorResolver.Anchors anchors = new ReferenceContentRigidAnchorResolver().resolve(
            List.of(layer),
            temporaryFolder,
            resolution(preliminaryPaths)
        );

        assertTrue(anchors.anchoredLayerNames().isEmpty());
        assertTrue(anchors.fullPathByTileId().isEmpty());
    }

    private MatrixLayer layerWithTiles(int count) throws IOException {
        MatrixLayer layer = new MatrixLayer();
        layer.setSourceFolderName("matrix_0");
        layer.setRows(1);
        layer.setCols(count);
        List<MatrixLayerTile> tiles = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            MatrixLayerTile tile = new MatrixLayerTile();
            tile.setId("tile-" + index);
            tile.setI(0);
            tile.setJ(index);
            tile.setTextureFile(temporaryFolder.resolve("local-" + index + ".png").toString());
            tiles.add(tile);
        }
        layer.setTiles(tiles);
        return layer;
    }

    private void writeReference(String quadPath, byte[] content) throws IOException {
        Path directory = temporaryFolder;
        for (int index = 1; index < quadPath.length(); index++) {
            directory = directory.resolve(String.valueOf(quadPath.charAt(index)));
        }
        Files.createDirectories(directory);
        Files.write(directory.resolve(quadPath + ".png"), content);
    }

    private static TileRootPathResolver.Resolution resolution(Map<String, String> paths) {
        return new TileRootPathResolver.Resolution(paths, java.util.Set.of(), Map.of());
    }

    private static String encode(int level, int row, int col) {
        StringBuilder path = new StringBuilder("0");
        for (int bit = level - 1; bit >= 0; bit--) {
            boolean south = ((row >> bit) & 1) != 0;
            boolean east = ((col >> bit) & 1) != 0;
            path.append(south ? (east ? '1' : '0') : (east ? '2' : '3'));
        }
        return path.toString();
    }
}
