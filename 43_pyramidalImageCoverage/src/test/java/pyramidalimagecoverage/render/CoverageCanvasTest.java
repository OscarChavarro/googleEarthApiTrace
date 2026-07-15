package pyramidalimagecoverage.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pyramidalimagecoverage.io.TileImageRepository;
import pyramidalimagecoverage.model.PixelSize;
import pyramidalimagecoverage.model.PyramidCatalog;
import pyramidalimagecoverage.model.TileAddress;
import pyramidalimagecoverage.model.TileRecord;
import pyramidalimagecoverage.model.ViewerModel;
import pyramidalimagecoverage.processing.LevelLayout;

class CoverageCanvasTest {
    @TempDir
    Path temporaryFolder;

    @Test
    void nativeImageKeepsItsPixelsAndLeavesOneBlackBorderPixelPerSide() throws IOException {
        Path tilePath = temporaryFolder.resolve("0.png");
        BufferedImage tile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D tileGraphics = tile.createGraphics();
        tileGraphics.setColor(Color.RED);
        tileGraphics.fillRect(0, 0, 256, 256);
        tileGraphics.dispose();
        ImageIO.write(tile, "png", tilePath.toFile());

        PyramidCatalog catalog = new PyramidCatalog(temporaryFolder);
        TileAddress root = TileAddress.fromQuadKey("0");
        catalog.add(new TileRecord(root, tilePath));
        CoverageCanvas canvas = new CoverageCanvas(new ViewerModel(catalog), new TileImageRepository());
        canvas.setSize(258, 258);
        canvas.setLayoutDescription(LevelLayout.choose(0, new PixelSize(258, 258)));

        BufferedImage result = new BufferedImage(258, 258, BufferedImage.TYPE_INT_RGB);
        canvas.paint(result.createGraphics());

        assertEquals(Color.BLACK.getRGB(), result.getRGB(0, 200));
        assertEquals(Color.RED.getRGB(), result.getRGB(1, 200));
        assertEquals(Color.RED.getRGB(), result.getRGB(256, 200));
        assertEquals(Color.BLACK.getRGB(), result.getRGB(257, 200));
    }

    @Test
    void selectedTilePaintsGreenBorder() throws IOException {
        Path tilePath = temporaryFolder.resolve("0.png");
        BufferedImage tile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D tileGraphics = tile.createGraphics();
        tileGraphics.setColor(Color.BLUE);
        tileGraphics.fillRect(0, 0, 256, 256);
        tileGraphics.dispose();
        ImageIO.write(tile, "png", tilePath.toFile());

        PyramidCatalog catalog = new PyramidCatalog(temporaryFolder);
        TileRecord rootTile = new TileRecord(TileAddress.fromQuadKey("0"), tilePath);
        catalog.add(rootTile);
        ViewerModel model = new ViewerModel(catalog);
        model.toggleSelection(rootTile);
        CoverageCanvas canvas = new CoverageCanvas(model, new TileImageRepository());
        canvas.setSize(258, 258);
        canvas.setLayoutDescription(LevelLayout.choose(0, new PixelSize(258, 258)));

        BufferedImage result = new BufferedImage(258, 258, BufferedImage.TYPE_INT_RGB);
        canvas.paint(result.createGraphics());

        assertEquals(new Color(0, 255, 0).getRGB(), result.getRGB(0, 200));
        assertEquals(Color.BLUE.getRGB(), result.getRGB(1, 200));
    }
}
