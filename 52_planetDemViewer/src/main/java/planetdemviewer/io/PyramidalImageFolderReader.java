package planetdemviewer.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;
import planetdemviewer.model.DemTile;
import planetdemviewer.model.PyramidalImage;
import planetdemviewer.model.QuadtreeNode;

/**
 * Opens a DEM pyramid without walking it. Startup performs one metadata read
 * for 0.bin; topology below the root is discovered later by
 * {@link TileTreeDiscoveryService}.
 */
public final class PyramidalImageFolderReader {
    public Optional<PyramidalImage> read(Path rootDirectory) {
        if (rootDirectory == null) {
            return Optional.empty();
        }
        Path normalizedRoot = rootDirectory.toAbsolutePath().normalize();
        Path rootTile = normalizedRoot.resolve("0.bin");
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(rootTile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        catch (IOException | SecurityException ex) {
            return Optional.empty();
        }
        if (!attributes.isRegularFile() || attributes.size() != DemTile.BYTE_COUNT) {
            return Optional.empty();
        }
        QuadtreeNode root = new QuadtreeNode("0", null, rootTile.toFile(), normalizedRoot);
        return Optional.of(new PyramidalImage(normalizedRoot.toString(), root));
    }
}
