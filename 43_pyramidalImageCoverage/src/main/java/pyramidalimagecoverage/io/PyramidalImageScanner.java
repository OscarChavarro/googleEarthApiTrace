package pyramidalimagecoverage.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;
import pyramidalimagecoverage.model.PyramidCatalog;
import pyramidalimagecoverage.model.TileAddress;
import pyramidalimagecoverage.model.TileRecord;

public final class PyramidalImageScanner {
    public PyramidCatalog scan(Path rootFolder) throws IOException {
        PyramidCatalog catalog = new PyramidCatalog(rootFolder);
        try (Stream<Path> paths = Files.walk(rootFolder)) {
            paths.filter(Files::isRegularFile)
                .filter(this::isPng)
                .forEach(path -> addIfTile(catalog, path));
        }
        if (catalog.tileAt(0, 0, 0) == null) {
            throw new IOException("The pyramid does not contain a valid root tile named 0.png");
        }
        return catalog;
    }

    private void addIfTile(PyramidCatalog catalog, Path path) {
        String fileName = path.getFileName().toString();
        String quadKey = fileName.substring(0, fileName.length() - 4);
        if (!quadKey.matches("0[0-3]*")) {
            return;
        }
        try {
            catalog.add(new TileRecord(TileAddress.fromQuadKey(quadKey), path));
        }
        catch (IllegalArgumentException ignored) {
            // A PNG unrelated to the quadtree is not part of the catalog.
        }
    }

    private boolean isPng(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png");
    }
}
