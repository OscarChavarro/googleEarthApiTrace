package pyramidalimageexporter.processing.content;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import pyramidalimageexporter.diagnostics.PerformanceReport;

public final class ContentHashCatalog {
    private final Map<String, String> quadPathByContentHash = new HashMap<>();
    private final Set<String> ambiguousContentHashes = new HashSet<>();
    private final Map<String, String> contentHashByImagePath = new HashMap<>();

    public static ContentHashCatalog build(
        Map<String, String> quadPathByImagePath,
        Map<String, String> knownContentHashByImagePath
    ) {
        return PerformanceReport.time("contentHashCatalog.build", () -> {
            ContentHashCatalog catalog = new ContentHashCatalog();
            catalog.indexCataloguedImages(quadPathByImagePath, knownContentHashByImagePath);
            return catalog;
        });
    }

    public void indexCataloguedImages(
        Map<String, String> quadPathByImagePath,
        Map<String, String> knownContentHashByImagePath
    ) {
        if (quadPathByImagePath == null) {
            return;
        }
        Map<String, String> knownHashes = knownContentHashByImagePath == null ? Map.of() : knownContentHashByImagePath;
        PerformanceReport.incrementBy("contentHash.indexCataloguedImages.inputPaths", quadPathByImagePath.size());
        for (Map.Entry<String, String> entry : quadPathByImagePath.entrySet()) {
            indexFile(Path.of(entry.getKey()), entry.getValue(), knownHashes.get(entry.getKey()));
        }
    }

    public Optional<String> resolveQuadPath(String textureFile) {
        return PerformanceReport.time("contentHashCatalog.resolve", () -> {
            if (textureFile == null || textureFile.isBlank()) {
                return Optional.empty();
            }
            Path path = Path.of(textureFile);
            String key = path.toAbsolutePath().normalize().toString();
            String hash = hashFile(path, contentHashByImagePath.get(key));
            return hash == null || ambiguousContentHashes.contains(hash)
                ? Optional.empty()
                : Optional.ofNullable(quadPathByContentHash.get(hash));
        });
    }

    public Optional<String> contentHash(String textureFile) {
        if (textureFile == null || textureFile.isBlank()) {
            return Optional.empty();
        }
        Path path = Path.of(textureFile);
        String key = path.toAbsolutePath().normalize().toString();
        return Optional.ofNullable(hashFile(path, contentHashByImagePath.get(key)));
    }

    private void indexFile(Path imageFile, String quadPath, String knownHash) {
        if (imageFile == null || quadPath == null || quadPath.isBlank()) {
            return;
        }
        String hash = hashFile(imageFile, knownHash);
        if (hash == null || ambiguousContentHashes.contains(hash)) {
            return;
        }
        String previousPath = quadPathByContentHash.putIfAbsent(hash, quadPath);
        if (previousPath != null && !previousPath.equals(quadPath)) {
            quadPathByContentHash.remove(hash);
            ambiguousContentHashes.add(hash);
        }
    }

    private String hashFile(Path file, String knownHash) {
        String key = file == null ? null : file.toAbsolutePath().normalize().toString();
        if (key != null) {
            String cached = contentHashByImagePath.get(key);
            if (cached != null) {
                PerformanceReport.increment("contentHashCatalog.hash.memoryHit");
                return cached;
            }
        }
        if (knownHash != null && !knownHash.isBlank()) {
            if (key != null) {
                contentHashByImagePath.put(key, knownHash);
            }
            PerformanceReport.increment("referenceCache.hash.reused");
            return knownHash;
        }
        String computed = sha256(file, "contentHash.hashFile");
        if (computed != null && key != null) {
            contentHashByImagePath.put(key, computed);
        }
        return computed;
    }

    public static String sha256(Path file, String metricPrefix) {
        if (file == null
            || !PerformanceReport.time(metricPrefix + ".stat", () -> Files.isRegularFile(file))
            || !PerformanceReport.time(metricPrefix + ".stat", () -> Files.isReadable(file))) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = PerformanceReport.time(
                metricPrefix + ".readAllBytes",
                () -> {
                    try {
                        return Files.readAllBytes(file);
                    }
                    catch (IOException ex) {
                        throw new HashReadException(ex);
                    }
                }
            );
            PerformanceReport.increment(metricPrefix + ".count");
            PerformanceReport.incrementBy(metricPrefix + ".bytes", bytes.length);
            digest.update(bytes);
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (HashReadException | NoSuchAlgorithmException ex) {
            return null;
        }
    }

    private static final class HashReadException extends RuntimeException {
        private HashReadException(Throwable cause) {
            super(cause);
        }
    }
}
