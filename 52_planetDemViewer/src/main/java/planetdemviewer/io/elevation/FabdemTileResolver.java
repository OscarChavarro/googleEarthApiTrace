package planetdemviewer.io.elevation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import planetdemviewer.model.DemTile;
import planetdemviewer.spatial.TileAddress;

/** Resolves FABDEM's physical directory variants behind a source-neutral tile address. */
public final class FabdemTileResolver {
    private final Path rootDirectory;

    public FabdemTileResolver(Path rootDirectory) {
        if (rootDirectory == null) {
            throw new IllegalArgumentException("FABDEM root directory is required");
        }
        this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
    }

    public Optional<Path> resolve(TileAddress address) {
        if (address == null || !TileAddress.FABDEM_QUADKEY_SCHEME.equals(address.scheme())) {
            return Optional.empty();
        }
        Path directory = rootDirectory;
        String quadkey = address.quadkey();
        for (int index = 1; index < quadkey.length(); index++) {
            String digit = Character.toString(quadkey.charAt(index));
            Path perDigit = directory.resolve(digit);
            Path cumulative = directory.resolve(quadkey.substring(0, index + 1));
            if (Files.isDirectory(perDigit)) {
                directory = perDigit;
            }
            else if (Files.isDirectory(cumulative)) {
                directory = cumulative;
            }
            else {
                return Optional.empty();
            }
        }
        Path tile = directory.resolve(quadkey + ".bin");
        try {
            return Files.isRegularFile(tile) && Files.size(tile) == DemTile.BYTE_COUNT
                ? Optional.of(tile) : Optional.empty();
        }
        catch (Exception ignored) {
            return Optional.empty();
        }
    }
}
