package planetdemviewer.io.imagery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import planetdemviewer.spatial.TileAddress;

/**
 * Resolves same-depth Google Earth PNG pages using the FABDEM quadkey convention.
 *
 * <p>The Google Earth pyramid may split its first child level across mounted
 * volumes through direct symbolic links under its root (for example,
 * {@code toplevel/2 -> /Volumes/GE_2}).  Deliberately use the normal
 * {@link Files#isDirectory(Path, java.nio.file.LinkOption...)} and
 * {@link Files#isRegularFile(Path, java.nio.file.LinkOption...)} semantics:
 * they follow those links and resolve the final PNG to its mounted-volume
 * path before it reaches the decoder.</p>
 */
public final class ImageryTileResolver {
    private final Path root;
    public ImageryTileResolver(Path root) { this.root = root == null ? null : root.toAbsolutePath().normalize(); }
    public Optional<Path> resolve(TileAddress address) {
        if (root == null || address == null || !TileAddress.FABDEM_QUADKEY_SCHEME.equals(address.scheme())) return Optional.empty();
        String key = address.quadkey();
        Path directory = root;
        // Tile 0 is stored as <root>/0.png.  Every following quadkey digit is
        // a directory; the first of those directories can be a volume link.
        for (int digitIndex = 1; digitIndex < key.length(); digitIndex++) {
            directory = directory.resolve(Character.toString(key.charAt(digitIndex)));
            if (!Files.isDirectory(directory)) return Optional.empty();
        }
        Path file = directory.resolve(key + ".png");
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            // Canonicalize only the completed file: this makes the decode
            // request target the mounted volume behind a direct link.
            return Optional.of(file.toRealPath());
        }
        catch (IOException ignored) {
            return Optional.empty();
        }
    }
}
