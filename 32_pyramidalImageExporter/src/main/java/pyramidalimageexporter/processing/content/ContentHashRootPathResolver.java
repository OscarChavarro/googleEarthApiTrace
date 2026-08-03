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

/**
 * Anchors a tile to its absolute quadtree path by content instead of by id:
 * the planet's imagery does not change, so a texture file that is a
 * byte-for-byte duplicate of an already-catalogued top-level image names the
 * same real-world cell, regardless of which capture session or frame/tile
 * numbering produced it. Catalogued images may come from this session or
 * from an explicitly supplied read-only reference pyramid.
 */
public final class ContentHashRootPathResolver {
    private final Map<String, String> quadPathByContentHash = new HashMap<>();
    private final Set<String> ambiguousContentHashes = new HashSet<>();

    public void indexCataloguedImages(Map<String, String> quadPathByImagePath) {
        if (quadPathByImagePath == null) {
            return;
        }
        for (Map.Entry<String, String> entry : quadPathByImagePath.entrySet()) {
            indexFile(Path.of(entry.getKey()), entry.getValue());
        }
    }

    public Optional<String> resolveQuadPath(String textureFile) {
        if (textureFile == null || textureFile.isBlank()) {
            return Optional.empty();
        }
        String hash = hashFile(Path.of(textureFile));
        return hash == null || ambiguousContentHashes.contains(hash)
            ? Optional.empty()
            : Optional.ofNullable(quadPathByContentHash.get(hash));
    }

    private void indexFile(Path imageFile, String quadPath) {
        if (imageFile == null || quadPath == null || quadPath.isBlank()) {
            return;
        }
        String hash = hashFile(imageFile);
        if (hash == null || ambiguousContentHashes.contains(hash)) {
            return;
        }
        String previousPath = quadPathByContentHash.putIfAbsent(hash, quadPath);
        if (previousPath != null && !previousPath.equals(quadPath)) {
            quadPathByContentHash.remove(hash);
            ambiguousContentHashes.add(hash);
        }
    }

    private static String hashFile(Path file) {
        if (file == null || !Files.isRegularFile(file) || !Files.isReadable(file)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (IOException | NoSuchAlgorithmException ex) {
            return null;
        }
    }
}
