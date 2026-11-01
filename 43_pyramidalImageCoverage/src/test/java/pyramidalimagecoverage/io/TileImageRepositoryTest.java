package pyramidalimagecoverage.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TileImageRepositoryTest {
    @TempDir Path folder;

    @Test
    void refreshReloadsAnImageOverwrittenAtTheSamePath() throws Exception {
        Path path = folder.resolve("0.png");
        writePixel(path, Color.BLUE);
        TileImageRepository images = new TileImageRepository();
        assertEquals(Color.BLUE.getRGB(), images.load(path).getRGB(0, 0));
        writePixel(path, Color.GREEN);
        images.invalidateAll();
        assertEquals(Color.GREEN.getRGB(), images.load(path).getRGB(0, 0));
    }

    @Test
    void refreshRetriesAnImageThatPreviouslyFailedToLoad() throws Exception {
        Path path = folder.resolve("0.png");
        TileImageRepository images = new TileImageRepository();
        assertNull(images.load(path));
        assertTrue(images.failed(path));
        writePixel(path, Color.BLUE);
        images.invalidateAll();
        assertFalse(images.failed(path));
        assertEquals(Color.BLUE.getRGB(), images.load(path).getRGB(0, 0));
    }

    private void writePixel(Path path, Color color) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, color.getRGB());
        ImageIO.write(image, "png", path.toFile());
    }
}
