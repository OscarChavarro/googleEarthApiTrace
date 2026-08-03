package pyramidalimageexporter.processing.toplevels;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;
import pyramidalimageexporter.processing.uncles.TileRootPathResolver;

final class ImportedLayerVisualAnchorResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void anchorsAChildGridFromStrictParentQuadrantMatches() throws IOException {
        Path parentTexture = tempDir.resolve("parent.png");
        writeParent(parentTexture);
        MatrixLayerTile parentTile = tile("parent", 0, 0, parentTexture);
        MatrixLayer parentLayer = layer("parent-layer", parentTile);

        MatrixLayerTile northWest = child("north-west", 0, 0, Color.RED, "nw.png");
        MatrixLayerTile northEast = child("north-east", 0, 1, Color.GREEN, "ne.png");
        MatrixLayerTile southWest = child("south-west", 1, 0, Color.BLUE, "sw.png");
        MatrixLayer childLayer = layer("child-layer", northWest, northEast, southWest);
        TileRootPathResolver.Resolution resolution = new TileRootPathResolver.Resolution(
            Map.of("parent", quadPath(2, 1, 1)),
            Set.of(),
            Map.of("parent", TileRootPathResolver.PathSource.GRID)
        );

        Map<String, String> anchors = new ImportedLayerVisualAnchorResolver().resolve(
            List.of(parentLayer, childLayer),
            resolution
        );

        assertEquals(quadPath(3, 2, 2), anchors.get("north-west"));
        assertEquals(quadPath(3, 2, 3), anchors.get("north-east"));
        assertEquals(quadPath(3, 3, 2), anchors.get("south-west"));
    }

    @Test
    void anchorsADisconnectedChildGridFromAReferenceParent() throws IOException {
        Path parentTexture = tempDir.resolve("reference-parent.png");
        writeParent(parentTexture);
        MatrixLayerTile northWest = child("north-west", 0, 0, Color.RED, "reference-nw.png");
        MatrixLayerTile northEast = child("north-east", 0, 1, Color.GREEN, "reference-ne.png");
        MatrixLayerTile southWest = child("south-west", 1, 0, Color.BLUE, "reference-sw.png");
        MatrixLayer childLayer = layer("disconnected-child", northWest, northEast, southWest);
        String parentPath = quadPath(2, 1, 1);
        TileRootPathResolver.Resolution emptyResolution = new TileRootPathResolver.Resolution(
            Map.of(), Set.of(), Map.of()
        );

        Map<String, String> anchors = new ImportedLayerVisualAnchorResolver().resolve(
            List.of(childLayer),
            emptyResolution,
            Map.of(parentTexture.toString(), parentPath, tempDir.resolve("deepest.png").toString(), quadPath(3, 0, 0))
        );

        assertEquals(quadPath(3, 2, 2), anchors.get("north-west"));
        assertEquals(quadPath(3, 2, 3), anchors.get("north-east"));
        assertEquals(quadPath(3, 3, 2), anchors.get("south-west"));
    }

    @Test
    void anchorsAcrossAMissingIntermediateLevel() throws IOException {
        Path ancestorTexture = tempDir.resolve("ancestor.png");
        writeGrandParent(ancestorTexture);
        MatrixLayerTile ancestorTile = tile("ancestor", 0, 0, ancestorTexture);
        MatrixLayer ancestorLayer = layer("ancestor-layer", ancestorTile);
        MatrixLayerTile first = child("first", 0, 0, grandParentColor(0, 0), "gap-first.png");
        MatrixLayerTile second = child("second", 0, 1, grandParentColor(0, 1), "gap-second.png");
        MatrixLayerTile third = child("third", 1, 0, grandParentColor(1, 0), "gap-third.png");
        MatrixLayer childLayer = layer("gap-child", first, second, third);
        TileRootPathResolver.Resolution resolution = new TileRootPathResolver.Resolution(
            Map.of("ancestor", quadPath(1, 0, 0)),
            Set.of(),
            Map.of("ancestor", TileRootPathResolver.PathSource.GRID)
        );

        Map<String, String> anchors = new ImportedLayerVisualAnchorResolver().resolve(
            List.of(ancestorLayer, childLayer),
            resolution
        );

        assertEquals(quadPath(3, 0, 0), anchors.get("first"));
        assertEquals(quadPath(3, 0, 1), anchors.get("second"));
        assertEquals(quadPath(3, 1, 0), anchors.get("third"));
    }

    @Test
    void anchorsAnExternalIntermediateTextureAgainstAResolvedParent() throws IOException {
        Path parentTexture = tempDir.resolve("external-parent.png");
        writeParent(parentTexture);
        MatrixLayerTile parentTile = tile("parent", 0, 0, parentTexture);
        MatrixLayer parentLayer = layer("parent-layer", parentTile);
        Path externalTexture = tempDir.resolve("external-child.png");
        child("external", 0, 0, Color.GREEN, "external-child.png");
        TileRootPathResolver.Resolution resolution = new TileRootPathResolver.Resolution(
            Map.of("parent", quadPath(2, 1, 1)),
            Set.of(),
            Map.of("parent", TileRootPathResolver.PathSource.GRID)
        );

        Map<String, String> anchors = new ImportedLayerVisualAnchorResolver().resolveExternalTextures(
            Map.of("external-id", externalTexture.toString()),
            List.of(parentLayer),
            resolution,
            3
        );

        assertEquals(quadPath(3, 2, 3), anchors.get("external-id"));
    }

    private MatrixLayerTile child(String id, int i, int j, Color color, String fileName) throws IOException {
        Path texture = tempDir.resolve(fileName);
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 256, 256);
        graphics.dispose();
        ImageIO.write(image, "png", texture.toFile());
        return tile(id, i, j, texture);
    }

    private static void writeParent(Path output) throws IOException {
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

    private static void writeGrandParent(Path output) throws IOException {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                graphics.setColor(grandParentColor(row, col));
                graphics.fillRect(col * 64, row * 64, 64, 64);
            }
        }
        graphics.dispose();
        ImageIO.write(image, "png", output.toFile());
    }

    private static Color grandParentColor(int row, int col) {
        return Color.getHSBColor((row * 4 + col) / 16.0f, 0.8f, 0.9f);
    }

    private static MatrixLayer layer(String name, MatrixLayerTile... tiles) {
        MatrixLayer layer = new MatrixLayer();
        layer.setSourceFolderName(name);
        layer.setTiles(List.of(tiles));
        return layer;
    }

    private static MatrixLayerTile tile(String id, int i, int j, Path texture) {
        MatrixLayerTile tile = new MatrixLayerTile();
        tile.setId(id);
        tile.setI(i);
        tile.setJ(j);
        tile.setTextureFile(texture.toString());
        return tile;
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
}
