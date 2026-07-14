package pyramidalimagecoverage.io;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

public final class TileImageRepository {
    private static final int MAX_CACHED_IMAGES = 256;

    private final Map<Path, BufferedImage> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, BufferedImage> eldest) {
            return size() > MAX_CACHED_IMAGES;
        }
    };

    public synchronized BufferedImage load(Path path) {
        BufferedImage cached = cache.get(path);
        if (cached != null) {
            return cached;
        }
        try {
            BufferedImage image = ImageIO.read(path.toFile());
            if (image != null) {
                cache.put(path, image);
            }
            return image;
        }
        catch (IOException ex) {
            System.err.println("WARNING: Could not read tile image " + path + ": " + ex.getMessage());
            return null;
        }
    }
}
