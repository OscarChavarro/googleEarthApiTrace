package pyramidalimageexporter.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Catalogues tiles from the deepest levels of an existing folder-based pyramidal image. */
public final class PyramidalImageReferenceCatalog {
    public Map<String, String> readDeepestLevel(Path rootFolder) {
        return readDeepestLevels(rootFolder, 1);
    }

    public Map<String, String> readDeepestLevels(Path rootFolder, int levelCount) {
        if (levelCount <= 0) {
            throw new IllegalArgumentException("Reference level count must be positive.");
        }
        Map<String, String> valid = new LinkedHashMap<>();
        int deepestLevel = -1;
        try (Stream<Path> paths = Files.walk(rootFolder)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(this::isPng).toList()) {
                String fileName = path.getFileName().toString();
                String quadPath = fileName.substring(0, fileName.length() - 4);
                if (!quadPath.matches("0[0-3]*") || !matchesSupportedLayout(rootFolder, path, quadPath)) {
                    continue;
                }
                int level = quadPath.length() - 1;
                deepestLevel = Math.max(deepestLevel, level);
                valid.put(path.toAbsolutePath().normalize().toString(), quadPath);
            }
        }
        catch (IOException ex) {
            throw new IllegalArgumentException("Could not scan reference pyramid " + rootFolder + ": " + ex.getMessage(), ex);
        }
        int shallowestIncludedLevel = Math.max(0, deepestLevel - levelCount + 1);
        Map<String, String> deepest = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : valid.entrySet()) {
            int level = entry.getValue().length() - 1;
            if (level >= shallowestIncludedLevel) {
                deepest.put(entry.getKey(), entry.getValue());
            }
        }
        System.out.println(
            "PyramidalImageReferenceCatalog: catalogued " + deepest.size()
                + " reference tile(s) at level(s) " + shallowestIncludedLevel
                + ".." + deepestLevel + "."
        );
        return deepest;
    }

    private boolean isPng(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png");
    }

    private boolean matchesSupportedLayout(Path rootFolder, Path path, String quadPath) {
        Path relative = rootFolder.relativize(path);
        return relative.equals(relativePathForDigitFolders(quadPath))
            || relative.equals(relativePathForLegacyFolders(quadPath));
    }

    private Path relativePathForDigitFolders(String quadPath) {
        Path path = Path.of(quadPath + ".png");
        for (int index = quadPath.length() - 1; index >= 1; index--) {
            path = Path.of(String.valueOf(quadPath.charAt(index)), path.toString());
        }
        return path;
    }

    private Path relativePathForLegacyFolders(String quadPath) {
        Path path = Path.of(quadPath + ".png");
        for (int length = quadPath.length(); length >= 2; length--) {
            path = Path.of(quadPath.substring(0, length), path.toString());
        }
        return path;
    }
}
