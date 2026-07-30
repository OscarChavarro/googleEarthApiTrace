package demresampler.manifest;

import demresampler.io.FabdemSourceTile;
import demresampler.io.RawTileIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

public final class ManifestStore {
    private static final String ALGORITHM_VERSION = "demresampler-manifest-v1";

    private final Path directory;

    private ManifestStore(Path directory) {
        this.directory = directory;
    }

    public static ManifestStore create(
        Path outputRoot,
        List<FabdemSourceTile> sources,
        int leafLevel
    ) throws IOException {
        System.out.printf(
            "Preparing resume manifest identity for %,d source files%n",
            sources.size());
        MessageDigest digest = sha256();
        update(digest, ALGORITHM_VERSION);
        update(digest, outputRoot.toAbsolutePath().normalize().toString());
        update(digest, Integer.toString(leafLevel));
        update(digest, Integer.toString(RawTileIO.BYTE_SIZE));
        BasicFileAttributes outputAttributes =
            Files.readAttributes(outputRoot, BasicFileAttributes.class);
        update(digest, String.valueOf(outputAttributes.fileKey()));

        int completed = 0;
        for (FabdemSourceTile source : sources) {
            BasicFileAttributes attributes =
                Files.readAttributes(source.path(), BasicFileAttributes.class);
            update(digest, source.path().toString());
            update(digest, Long.toString(attributes.size()));
            update(digest, attributes.lastModifiedTime().toString());
            completed++;
            if (completed % 1000 == 0 || completed == sources.size()) {
                System.out.printf(
                    "Resume identity: %,d / %,d source files%n",
                    completed,
                    sources.size());
            }
        }

        String key = HexFormat.of().formatHex(digest.digest());
        Path directory = Path.of("/tmp", "51-demResampler-manifests", key);
        Files.createDirectories(directory);
        System.out.println("Resume manifests: " + directory);
        return new ManifestStore(directory);
    }

    public LevelManifest openLevel(int level, Set<Long> coordinates)
        throws IOException {
        System.out.printf(
            "Loading level %d resume manifest for %,d coordinates%n",
            level,
            coordinates.size());
        return new LevelManifest(directory, level, coordinates);
    }

    public Path directory() {
        return directory;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }
}
