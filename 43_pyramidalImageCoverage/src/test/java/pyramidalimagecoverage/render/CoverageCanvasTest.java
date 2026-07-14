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
    void nativeImageKeepsItsPixelsAndLeavesOneBackgroundPixelPerSide() throws IOException {
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

        assertEquals(new Color(18, 18, 20).getRGB(), result.getRGB(0, 200));
        assertEquals(Color.RED.getRGB(), result.getRGB(1, 200));
        assertEquals(Color.RED.getRGB(), result.getRGB(256, 200));
        assertEquals(new Color(18, 18, 20).getRGB(), result.getRGB(257, 200));
    }
}
