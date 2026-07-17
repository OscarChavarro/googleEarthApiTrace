package pyramidalimageexporter.processing.toplevels;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;
import pyramidalimageexporter.model.ParentGridTransform;
import pyramidalimageexporter.processing.uncles.TileRootPathResolver;

final class TopLevelLayerMergerTest {
    @TempDir
    Path tempDir;

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

    @Test
    void retainsAMergedTopLayerWhenAChildNeedsItsParentGridTransform() {
        MatrixLayer inferredLevelOne = layer(
            "topLevel_matrix_01",
            tile("03", 0, 0, "/tmp/inferred.png")
        );
        MatrixLayer importedParent = layer("matrix_0", tile("03", 0, 0, "/tmp/parent.png"));
        MatrixLayer importedChild = layer("matrix_1", tile("child", 0, 0, "/tmp/child.png"));
        importedChild.setParentMatrixIndex(0);
        importedChild.setParentGridTransform(new ParentGridTransform(0, 0));

        TopLevelLayerMerger.MergeResult result = new TopLevelLayerMerger().merge(
            List.of(inferredLevelOne),
            List.of(importedParent, importedChild),
            Map.of(),
            Path.of("/tmp")
        );

        assertEquals(
            List.of("topLevel_matrix_01", "matrix_0", "matrix_1"),
            result.layers().stream().map(MatrixLayer::getSourceFolderName).toList()
        );
        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            result.layers(),
            result.mergedFullPathByOriginalId(),
            Map.of()
        );
        assertEquals("033", resolution.pathById().get("child"));
    }

    @Test
    void visuallyAnchorsAPartialMatrixToReconstructedTopLevelCells() throws IOException {
        Path atlas = tempDir.resolve("atlas.png");
        writeLevelTwoAtlas(atlas);
        List<MatrixLayerTile> topCells = new ArrayList<>();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                MatrixLayerTile cell = tile(quadPath(2, row, col), row, col, atlas.toString());
                cell.setTextureSubRect(col / 4.0, (3 - row) / 4.0, (col + 1) / 4.0, (4 - row) / 4.0);
                topCells.add(cell);
            }
        }
        MatrixLayer top = new MatrixLayer();
        top.setSourceFolderName("topLevel_matrix_02");
        top.setRows(4);
        top.setCols(4);
        top.setTiles(topCells);

        List<MatrixLayerTile> importedTiles = new ArrayList<>();
        for (int localCol = 0; localCol < 3; localCol++) {
            int worldCol = localCol + 1;
            Path texture = tempDir.resolve("imported-" + localCol + ".png");
            writeSolidTile(texture, colorOf(1, worldCol));
            importedTiles.add(tile("imported-" + localCol, 0, localCol, texture.toString()));
        }
        MatrixLayer imported = new MatrixLayer();
        imported.setSourceFolderName("matrix_0");
        imported.setTiles(importedTiles);

        Map<String, String> anchors = new TopLevelVisualAnchorResolver().resolve(List.of(top), List.of(imported));

        assertEquals(quadPath(2, 1, 1), anchors.get("imported-0"));
        assertEquals(quadPath(2, 1, 2), anchors.get("imported-1"));
        assertEquals(quadPath(2, 1, 3), anchors.get("imported-2"));
    }

    @Test
    void visuallyAnchorsAChildMatrixAgainstTopLevelQuadrants() throws IOException {
        Path atlas = tempDir.resolve("quadrant-atlas.png");
        writeQuadrantParent(atlas);
        MatrixLayer top = layer("topLevel_matrix_00", tile("0", 0, 0, atlas.toString()));

        Path northWest = tempDir.resolve("top-child-nw.png");
        Path northEast = tempDir.resolve("top-child-ne.png");
        Path southWest = tempDir.resolve("top-child-sw.png");
        writeSolidTile(northWest, Color.RED.getRGB());
        writeSolidTile(northEast, Color.GREEN.getRGB());
        writeSolidTile(southWest, Color.BLUE.getRGB());
        MatrixLayer imported = layer(
            "matrix_0",
            tile("child-nw", 0, 0, northWest.toString()),
            tile("child-ne", 0, 1, northEast.toString()),
            tile("child-sw", 1, 0, southWest.toString())
        );

        Map<String, String> anchors = new TopLevelVisualAnchorResolver().resolve(List.of(top), List.of(imported));

        assertEquals(quadPath(1, 0, 0), anchors.get("child-nw"));
        assertEquals(quadPath(1, 0, 1), anchors.get("child-ne"));
        assertEquals(quadPath(1, 1, 0), anchors.get("child-sw"));
    }

    @Test
    void retainsVisualDescendantAnchorsForTheLaterExportPass() throws IOException {
        Path parentTexture = tempDir.resolve("descendant-parent.png");
        writeQuadrantParent(parentTexture);
        MatrixLayer parent = layer(
            "matrix_parent",
            tile(quadPath(2, 1, 1), 0, 0, parentTexture.toString())
        );
        Path northWestTexture = tempDir.resolve("descendant-nw.png");
        Path northEastTexture = tempDir.resolve("descendant-ne.png");
        Path southWestTexture = tempDir.resolve("descendant-sw.png");
        writeSolidTile(northWestTexture, Color.RED.getRGB());
        writeSolidTile(northEastTexture, Color.GREEN.getRGB());
        writeSolidTile(southWestTexture, Color.BLUE.getRGB());
        MatrixLayer child = layer(
            "matrix_child",
            tile("child-nw", 0, 0, northWestTexture.toString()),
            tile("child-ne", 0, 1, northEastTexture.toString()),
            tile("child-sw", 1, 0, southWestTexture.toString())
        );

        TopLevelLayerMerger.MergeResult result = new TopLevelLayerMerger().merge(
            List.of(),
            List.of(parent, child),
            Map.of(),
            tempDir
        );

        assertEquals(quadPath(3, 2, 2), result.mergedFullPathByOriginalId().get("child-nw"));
        assertEquals(quadPath(3, 2, 3), result.mergedFullPathByOriginalId().get("child-ne"));
        assertEquals(quadPath(3, 3, 2), result.mergedFullPathByOriginalId().get("child-sw"));
    }

    private static void writeLevelTwoAtlas(Path output) throws IOException {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, colorOf(y / 64, x / 64));
            }
        }
        ImageIO.write(image, "png", output.toFile());
    }

    private static void writeSolidTile(Path output, int rgb) throws IOException {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, rgb);
            }
        }
        ImageIO.write(image, "png", output.toFile());
    }

    private static void writeQuadrantParent(Path output) throws IOException {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 128, 128);
        graphics.setColor(Color.GREEN);
        graphics.fillRect(128, 0, 128, 128);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(0, 128, 128, 128);
        graphics.setColor(Color.YELLOW);
        graphics.fillRect(128, 128, 128, 128);
        graphics.dispose();
        ImageIO.write(image, "png", output.toFile());
    }

    private static int colorOf(int row, int col) {
        return ((20 + row * 55) << 16) | ((25 + col * 50) << 8) | (15 + (row * 4 + col) * 13);
    }

    private static String quadPath(int level, int row, int col) {
        StringBuilder path = new StringBuilder("0");
        for (int depth = level - 1; depth >= 0; depth--) {
            boolean south = ((row >> depth) & 1) == 1;
            boolean east = ((col >> depth) & 1) == 1;
            path.append(south ? (east ? '1' : '0') : (east ? '2' : '3'));
        }
        return path.toString();
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
