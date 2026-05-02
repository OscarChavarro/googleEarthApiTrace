package dumpanalyzer.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import dumpanalyzer.logger.FatalErrorHandler;
import dumpanalyzer.model.DumpAnalyzerModel;

public final class TexturePathScanner {
    private static final Pattern TEXTURE_NAME_PATTERN = Pattern.compile(".*_(\\d+)\\.(dds|png)$", Pattern.CASE_INSENSITIVE);

    private TexturePathScanner() {
    }

    public static void scanRecursive(Path outputRoot, DumpAnalyzerModel model) {
        if (!Files.isDirectory(outputRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(outputRoot)) {
            paths
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String n = path.getFileName().toString().toLowerCase();
                    return n.endsWith(".dds") || n.endsWith(".png");
                })
                .forEach(path -> register(path, model));
        } catch (IOException e) {
            FatalErrorHandler.fail(outputRoot, "Failed to recursively scan texture files: " + e.getMessage());
        }
    }

    private static void register(Path path, DumpAnalyzerModel model) {
        String name = path.getFileName().toString();
        Matcher matcher = TEXTURE_NAME_PATTERN.matcher(name);
        if (!matcher.matches()) {
            return;
        }
        Path parent = path.getParent();
        if (parent == null || parent.getFileName() == null) {
            return;
        }
        int frameId;
        try {
            frameId = Integer.parseInt(parent.getFileName().toString());
        } catch (NumberFormatException ex) {
            return;
        }
        int textureId = Integer.parseInt(matcher.group(1));
        model.registerTexturePath(frameId, textureId, path.toAbsolutePath().toString());
    }
}
