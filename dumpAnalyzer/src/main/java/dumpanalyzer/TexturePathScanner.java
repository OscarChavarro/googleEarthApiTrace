package dumpanalyzer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import dumpanalyzer.model.DumpAnalyzerModel;

public final class TexturePathScanner {
    private static final Pattern DDS_NAME_PATTERN = Pattern.compile(".*_(\\d+)\\.dds$", Pattern.CASE_INSENSITIVE);

    private TexturePathScanner() {
    }

    public static void scanRecursive(Path outputRoot, DumpAnalyzerModel model) {
        if (!Files.isDirectory(outputRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(outputRoot)) {
            paths
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".dds"))
                .forEach(path -> register(path, model));
        } catch (IOException e) {
            FatalErrorHandler.fail(outputRoot, "Failed to recursively scan DDS files: " + e.getMessage());
        }
    }

    private static void register(Path path, DumpAnalyzerModel model) {
        String name = path.getFileName().toString();
        Matcher matcher = DDS_NAME_PATTERN.matcher(name);
        if (!matcher.matches()) {
            return;
        }
        int textureId = Integer.parseInt(matcher.group(1));
        model.registerTexturePath(textureId, path.toAbsolutePath().toString());
    }
}
