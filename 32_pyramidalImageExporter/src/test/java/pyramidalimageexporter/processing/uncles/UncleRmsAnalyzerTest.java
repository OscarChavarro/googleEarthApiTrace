package pyramidalimageexporter.processing.uncles;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pyramidalimageexporter.model.MatrixLayer;
import pyramidalimageexporter.model.MatrixLayerTile;

final class UncleRmsAnalyzerTest {
    @TempDir
    Path tempDirectory;

    @Test
    void selectsTheMinimumRmsQuadrantWithoutAnAbsoluteThreshold() throws IOException {
        Path parentTexture = writeParentQuadrants();
        // Deliberately far from the exact blue parent quadrant: it still wins
        // relatively and must not be rejected by an absolute terrain threshold.
        Path childTexture = writeSolid("child.png", new Color(20, 20, 120));

        MatrixLayerTile parent = tile("parent", parentTexture);
        MatrixLayer parentLayer = layer("matrix_0", parent);
        MatrixLayerTile child = tile("child", childTexture);
        ToUncleRelationship correct = new ToUncleRelationship(UncleDirections.EAST_NORTH, "parent");
        ToUncleRelationship wrong = new ToUncleRelationship(UncleDirections.WEST_NORTH, "parent");
        child.setUncles(List.of(correct, wrong));
        MatrixLayer childLayer = layer("matrix_1", child);

        UncleRmsAnalyzer.Analysis analysis = new UncleRmsAnalyzer().analyze(
            List.of(parentLayer, childLayer),
            Map.of()
        );

        UncleRmsAnalyzer.Match correctMatch = analysis.matchFor(childLayer, child, correct);
        UncleRmsAnalyzer.Match wrongMatch = analysis.matchFor(childLayer, child, wrong);
        assertNotNull(correctMatch);
        assertNotNull(wrongMatch);
        assertTrue(correctMatch.declaredQuadrantIsMinimum());
        assertTrue(correctMatch.declaredRms() > 35.0, "the relative match must survive a high absolute RMS");
        assertFalse(wrongMatch.declaredQuadrantIsMinimum());
        assertTrue(analysis.accepts(childLayer, child, correct));
        assertFalse(analysis.accepts(childLayer, child, wrong));
        assertTrue(analysis.scoreFor(childLayer, child).hasVisualMatch());

        TileRootPathResolver.Resolution resolution = new TileRootPathResolver().resolve(
            List.of(parentLayer, childLayer),
            Map.of("parent", "03"),
            Map.of()
        );
        assertTrue(resolution.pathById().containsKey("child"));
        assertEquals("032", resolution.pathById().get("child"));
    }

    private Path writeParentQuadrants() throws IOException {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, 128, 128);       // NW = 3
        graphics.setColor(Color.BLUE); graphics.fillRect(128, 0, 128, 128);      // NE = 2
        graphics.setColor(Color.RED); graphics.fillRect(0, 128, 128, 128);       // SW = 0
        graphics.setColor(Color.GREEN); graphics.fillRect(128, 128, 128, 128);   // SE = 1
        graphics.dispose();
        Path file = tempDirectory.resolve("parent.png");
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private Path writeSolid(String name, Color color) throws IOException {
        BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.dispose();
        Path file = tempDirectory.resolve(name);
        ImageIO.write(image, "png", file.toFile());
        return file;
    }

    private static MatrixLayerTile tile(String id, Path texture) {
        MatrixLayerTile tile = new MatrixLayerTile();
        tile.setId(id);
        tile.setTextureFile(texture.toString());
        return tile;
    }

    private static MatrixLayer layer(String name, MatrixLayerTile... tiles) {
        MatrixLayer layer = new MatrixLayer();
        layer.setSourceFolderName(name);
        layer.setTiles(List.of(tiles));
        return layer;
    }
}
